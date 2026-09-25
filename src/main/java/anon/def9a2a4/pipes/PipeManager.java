package anon.def9a2a4.pipes;

import anon.def9a2a4.pipes.adapter.ContainerAdapter;
import anon.def9a2a4.pipes.config.DisplayConfig;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

public class PipeManager {

    private static final int MAX_FALLBACK_DEPTH = 24;
    private static final float DISPLAY_VIEW_RANGE = 0.8F;
    private static final float DISPLAY_CULLING_SIZE = 3.0F;
    private static final String DISPLAY_UPDATE_TASK = "pipes_display_updates";
    private static final BlockFace[] ADJACENT_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
            BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    private record CachedPath(Location destination, Location lastPipeLocation,
                               List<Location> pipeChain, int minItemsPerTransfer, Location unavailableBlock) {
        private CachedPath(Location destination, Location lastPipeLocation,
                           List<Location> pipeChain, int minItemsPerTransfer) {
            this(destination, lastPipeLocation, pipeChain, minItemsPerTransfer, null);
        }
    }

    private final PipesPlugin plugin;
    private final int offset;
    private final World world;
    private final Random random = new Random();
    private final Map<Location, PipeData> pipes = new HashMap<>();
    private final Map<Long, Set<Location>> pipesByChunk = new HashMap<>();
    private final Set<Long> entitiesLoadedChunks = new HashSet<>();
    // One world task owns a transfer; an incomplete branch must not turn into an item drop.
    private boolean waitingForTransferChunk;
    private final Map<Location, Long> lastTransferTick = new HashMap<>();
    private final TransferSchedule<Location> transferSchedule = new TransferSchedule<>();

    private final Map<Location, CachedPath> pathCache = new HashMap<>();
    private final Set<Location> dirtyPaths = new HashSet<>();
    private final Map<Location, Long> nullDestRecheckUntil = new HashMap<>();
    private final Map<Location, Set<Location>> chainMembership = new HashMap<>();
    private final ArrayDeque<Location> pendingDisplayUpdates = new ArrayDeque<>();
    private final Set<Location> queuedDisplayUpdates = new HashSet<>();
    private final Map<Long, Set<Location>> deferredDisplayUpdates = new HashMap<>();
    private final Map<Location, Set<Long>> missingChunksByDisplay = new HashMap<>();

    public PipeManager(PipesPlugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        this.offset = random.nextInt(21);
    }

    public void startTasks() {
        world.submitCyclicalTask(
                "pipes_transfer",
                this::transferAllPipes
        );
        world.submitCyclicalTask(DISPLAY_UPDATE_TASK, this::processPendingDisplayUpdates);

        if (plugin.getPipeConfig().isDebugParticles()) {
            int particleInterval = plugin.getPipeConfig().getParticleInterval();
            world.submitCyclicalTask(
                    "pipes_particles",
                    () -> {
                        if ((offset + Bukkit.getServer().getCurrentTick()) % particleInterval == 0) {
                            this.spawnDebugParticles();
                        }
                    }
            );
        }
    }

    public void registerPipe(Location location, BlockFace facing, List<UUID> displayEntityIds, PipeVariant variant) {
        Location normalized = normalizeLocation(location);
        PipeData data = new PipeData(facing, List.copyOf(displayEntityIds), variant);
        pipes.put(normalized, data);
        pipesByChunk.computeIfAbsent(Chunk.getChunkKey(normalized), ignored -> new HashSet<>()).add(normalized);
        reconcileTransferSchedule(normalized, data);
        evictCacheByMember(normalized);
        dirtyPaths.add(normalized);
    }

    /**
     * 精准驱逐以 {@code location} 为起点的路径缓存条目。
     * 适用于管道输出方向前方的方块发生变化时（放置/破坏容器或管道），
     * 使该管道在下一个传输 tick 时重新寻路。
     * 拓扑变化（管道本身增删）由 registerPipe / unregisterPipe 内部调用 evictCacheByMember 处理。
     *
     * @param location 需要重算路径的管道位置
     */
    public void invalidatePath(Location location) {
        Location normalized = normalizeLocation(location);
        evictCacheEntry(normalized);
    }

    public void unregisterPipe(Location location) {
        Location normalized = normalizeLocation(location);
        PipeData data = detachPipe(normalized);

        if (location.getWorld() != world) throw new RuntimeException("Location world does not match PipeManager world");
        if (world == null) return;

        // Try to remove all entities by UUID first
        boolean allRemoved = true;
        if (data != null && data.displayEntityIds() != null) {
            for (UUID uuid : data.displayEntityIds()) {
                Entity entity = world.getEntity(uuid);
                if (entity != null) {
                    entity.remove();
                } else {
                    allRemoved = false;
                }
            }
        } else {
            allRemoved = false;
        }

        // Fallback: Find entities by scoreboard tag (handles UUID mismatch after restart)
        if (!allRemoved) {
            removeDisplaysByTag(normalized);
        }
    }

    private PipeData detachPipe(Location normalized) {
        PipeData data = pipes.remove(normalized);
        long chunkKey = Chunk.getChunkKey(normalized);
        Set<Location> chunkPipes = pipesByChunk.get(chunkKey);
        if (chunkPipes != null) {
            chunkPipes.remove(normalized);
            if (chunkPipes.isEmpty()) pipesByChunk.remove(chunkKey);
        }
        lastTransferTick.remove(normalized);
        transferSchedule.cancel(normalized);
        nullDestRecheckUntil.remove(normalized);
        dirtyPaths.remove(normalized);
        evictCacheByMember(normalized);
        queuedDisplayUpdates.remove(normalized);
        clearDeferredDisplayUpdate(normalized);
        return data;
    }

    private void removeDisplaysByTag(Location location) {
        if (location.getWorld() != world) throw new RuntimeException("Location world does not match PipeManager world");
        if (world == null) return;

        Collection<Entity> nearby = world.getNearbyEntities(
                location.clone().add(0.5, 0.5, 0.5),
                1.0, 1.0, 1.0,
                e -> e instanceof ItemDisplay
        );

        // Remove ALL matching entities, not just the first one
        for (Entity entity : nearby) {
            String pipeTag = PipeTags.getPipeTag(entity);
            if (pipeTag != null && PipeTags.matchesLocation(pipeTag, location)) {
                entity.remove();
            }
        }
    }

    /**
     * 就地更换管道的变体（用于氧化、涂蜡、打磨等操作）。
     * 更新 PipeData、头颅贴图、展示实体贴图及 PDC 标签，不改变位置和朝向。
     */
    public void convertPipeVariant(Location location, PipeVariant newVariant) {
        Location normalized = normalizeLocation(location);
        applyVariantConversion(normalized, newVariant);
        evictCacheByMember(normalized);
    }

    /**
     * 执行变体替换的核心逻辑（不触碰路径缓存，由调用方统一处理）。
     */
    private void applyVariantConversion(Location normalized, PipeVariant newVariant) {
        PipeData data = pipes.get(normalized);
        if (data == null) return;

        // 更新 PipeData（朝向和显示实体 UUID 不变，仅替换变体）
        PipeData converted = new PipeData(data.facing(), data.displayEntityIds(), newVariant);
        pipes.put(normalized, converted);
        reconcileTransferSchedule(normalized, converted);

        updatePipeBlockHead(normalized, newVariant, data.facing());

        // 更新展示实体贴图与 PDC 标签
        if (data.displayEntityIds() != null) {
            for (UUID uuid : data.displayEntityIds()) {
                Entity entity = world.getEntity(uuid);
                if (!(entity instanceof ItemDisplay display)) continue;

                String oldTag = PipeTags.getPipeTag(entity);
                if (oldTag == null) continue;

                if (PipeTags.isHeadDisplayTag(oldTag)) {
                    ItemStack newHeadItem = plugin.getHeadItemForDirection(newVariant, data.facing());
                    setDisplayItemIfChanged(display, newHeadItem);
                    display.setTransformation(calculateCornerHeadTransformation(data.facing()));
                    PipeTags.addPipeTag(entity, PipeTags.createHeadDisplayTag(normalized, data.facing(), newVariant));
                } else if (PipeTags.isDirectionalTag(oldTag)) {
                    BlockFace dirFacing = PipeTags.parseFacing(oldTag);
                    if (dirFacing == null) continue;
                    ItemStack newDirItem = plugin.getDirectionalDisplayItem(newVariant, dirFacing);
                    setDisplayItemIfChanged(display, newDirItem);
                    PipeTags.addPipeTag(entity, PipeTags.createDirectionalTag(normalized, dirFacing, newVariant));
                } else {
                    ItemStack newDisplayItem = plugin.getDisplayItem(newVariant, data.facing());
                    setDisplayItemIfChanged(display, newDisplayItem);
                    PipeTags.addPipeTag(entity, PipeTags.createTag(normalized, data.facing(), newVariant));
                    PipeTags.setRenderRevision(entity, plugin.getRenderRevision());
                }
            }
        }
    }

    /**
     * 随机氧化检查：遍历所有已加载的可氧化管道，按概率进行变体转换。
     * 批量收集所有变换位置后，对路径缓存做一次性清理，避免逐根清理的重复扫描。
     * @param transitions  变体ID → 目标变体ID 的映射
     * @param chanceNum    概率分子
     * @param chanceDenom  概率分母
     * @param random       随机数生成器
     */
    public void tickOxidation(Map<String, String> transitions, int chanceNum, int chanceDenom, Random random) {
        if (transitions.isEmpty()) return;

        // 收集本轮所有需要转换的管道
        List<Map.Entry<Location, PipeData>> snapshot = new ArrayList<>(pipes.entrySet());
        Set<Location> convertedLocations = new HashSet<>();

        for (Map.Entry<Location, PipeData> entry : snapshot) {
            String variantId = entry.getValue().variant().getId();
            String targetId = transitions.get(variantId);
            if (targetId == null) continue;

            if (random.nextInt(chanceDenom) < chanceNum) {
                PipeVariant newVariant = plugin.getVariantRegistry().getVariant(targetId);
                if (newVariant != null) {
                    Location normalized = normalizeLocation(entry.getKey());
                    applyVariantConversion(normalized, newVariant);
                    convertedLocations.add(normalized);
                }
            }
        }

        // 对路径缓存做一次性清理：精准驱逐链路经过任意已转换位置的缓存条目
        for (Location loc : convertedLocations) {
            evictCacheByMember(loc);
        }
    }

    public boolean isPipe(Location location) {
        return pipes.containsKey(normalizeLocation(location));
    }

    public PipeData getPipeData(Location location) {
        return pipes.get(normalizeLocation(location));
    }

    public void notifyBlockChanged(Location location) {
        for (BlockFace face : ADJACENT_FACES) {
            Location adjacentLoc = location.getBlock().getRelative(face).getLocation();
            PipeData pipeData = getPipeData(adjacentLoc);
            if (pipeData == null) continue;

            BlockFace pipeFacing = pipeData.facing();
            boolean isCorner = pipeData.variant().getBehaviorType() == BehaviorType.CORNER;
            if (isCorner || face == pipeFacing || face == pipeFacing.getOppositeFace()) {
                updateDisplayEntity(adjacentLoc);
            }
            if (pipeFacing == face.getOppositeFace()) {
                wakeUpPipe(adjacentLoc);
                invalidatePath(adjacentLoc);
            } else if (pipeFacing == face) {
                wakeUpPipe(adjacentLoc);
            }
        }
    }

    private void updatePipeBlockHead(Location normalized, PipeVariant variant, BlockFace facing) {
        Block block = normalized.getBlock();
        if (block.getState() instanceof Skull skull) {
            ItemStack headItem = plugin.getHeadItemForDirection(variant, facing);
            if (headItem != null && headItem.getItemMeta() instanceof SkullMeta skullMeta) {
                if (!Objects.equals(skull.getOwnerProfile(), skullMeta.getOwnerProfile())) {
                    skull.setOwnerProfile(skullMeta.getOwnerProfile());
                    skull.update(true, false);
                }
            }
        }
    }

    private static void setDisplayItemIfChanged(ItemDisplay display, ItemStack item) {
        if (item != null && !Objects.equals(display.getItemStack(), item)) {
            display.setItemStack(item);
        }
    }

    public void updateDisplayEntity(Location pipeLocation) {
        Location normalized = normalizeLocation(pipeLocation);
        PipeData data = pipes.get(normalized);
        if (data == null) return;

        if (pipeLocation.getWorld() != world) throw new RuntimeException("Location world does not match PipeManager world");
        if (world == null) return;

        Set<Long> missingChunks = getMissingDisplayChunks(normalized, data);
        if (!missingChunks.isEmpty()) {
            deferDisplayUpdate(normalized, missingChunks);
            return;
        }
        clearDeferredDisplayUpdate(normalized);

        Material blockType = normalized.getBlock().getType();
        if (blockType != Material.PLAYER_HEAD && blockType != Material.PLAYER_WALL_HEAD) {
            unregisterPipe(normalized);
            return;
        }

        updatePipeBlockHead(normalized, data.variant(), data.facing());

        if (data.variant().getBehaviorType() == BehaviorType.CORNER) {
            refreshCornerDisplayEntities(normalized);
        } else {
            refreshRegularDisplayEntity(normalized, data);
        }
    }

    private void refreshRegularDisplayEntity(Location normalized, PipeData data) {
        ItemDisplay mainDisplay = null;
        if (data.displayEntityIds() != null) {
            for (UUID uuid : data.displayEntityIds()) {
                Entity entity = world.getEntity(uuid);
                if (!(entity instanceof ItemDisplay display)) continue;

                String tag = PipeTags.getPipeTag(display);
                PipeTags.ParsedTag parsed = PipeTags.parse(tag);
                if (mainDisplay == null && parsed != null && parsed.mainDisplay()
                        && PipeTags.matchesLocation(tag, normalized)) {
                    mainDisplay = display;
                } else {
                    display.remove();
                }
            }
        }

        if (mainDisplay == null) {
            mainDisplay = spawnMainDisplay(normalized, data.facing(), data.variant());
        } else {
            configureDisplay(mainDisplay);
            setDisplayItemIfChanged(mainDisplay, plugin.getDisplayItem(data.variant(), data.facing()));
            mainDisplay.setTransformation(calculateTransformation(normalized, data.facing(), data.variant()));
            PipeTags.addPipeTag(mainDisplay, PipeTags.createTag(normalized, data.facing(), data.variant()));
        }
        PipeTags.setRenderRevision(mainDisplay, plugin.getRenderRevision());

        pipes.put(normalized, new PipeData(data.facing(), List.of(mainDisplay.getUniqueId()), data.variant()));
    }

    /** 协调转角管道的主体、头部和方向展示实体。 */
    private void refreshCornerDisplayEntities(Location location) {
        Location normalized = normalizeLocation(location);
        PipeData data = pipes.get(normalized);
        if (data == null || data.variant().getBehaviorType() != BehaviorType.CORNER) return;
        if (normalized.getWorld() != world) return;

        // 计算当前应存在的活跃输出方向集合
        Set<BlockFace> desiredFaces = new HashSet<>(getCornerActiveOutputFaces(normalized, data.facing()));

        // 分类现有实体：每种角色只保留一个，重复实体直接清除
        List<UUID> finalIds = new ArrayList<>();
        Map<BlockFace, UUID> existingDirEntities = new EnumMap<>(BlockFace.class);
        Set<BlockFace> retainedDirFaces = new HashSet<>();
        UUID existingMainDisplay = null;
        UUID existingHeadDisplay = null;

        if (data.displayEntityIds() != null) {
            for (UUID uuid : data.displayEntityIds()) {
                Entity entity = world.getEntity(uuid);
                if (entity == null) continue;

                String tag = PipeTags.getPipeTag(entity);
                PipeTags.ParsedTag parsed = PipeTags.parse(tag);
                if (parsed == null || !PipeTags.matchesLocation(tag, normalized)) {
                    entity.remove();
                    continue;
                }

                if (parsed.headDisplay()) {
                    if (existingHeadDisplay == null) {
                        existingHeadDisplay = uuid;
                    } else {
                        entity.remove();
                    }
                } else if (parsed.directional()) {
                    if (existingDirEntities.putIfAbsent(parsed.facing(), uuid) != null) {
                        entity.remove();
                    }
                } else {
                    if (existingMainDisplay == null) {
                        existingMainDisplay = uuid;
                    } else {
                        entity.remove();
                    }
                }
            }
        }

        ItemDisplay mainDisplay = null;
        if (existingMainDisplay != null) {
            Entity entity = world.getEntity(existingMainDisplay);
            if (entity instanceof ItemDisplay display) {
                mainDisplay = display;
                configureDisplay(display);
                setDisplayItemIfChanged(display, plugin.getDisplayItem(data.variant(), data.facing()));
                display.setTransformation(calculateCornerTransformation(data.facing()));
                PipeTags.addPipeTag(display, PipeTags.createTag(normalized, data.facing(), data.variant()));
            } else if (entity != null) {
                entity.remove();
            }
        }
        if (mainDisplay == null) {
            mainDisplay = spawnMainDisplay(normalized, data.facing(), data.variant());
        }
        finalIds.add(mainDisplay.getUniqueId());

        boolean needsHeadDisplay = needsCornerHeadDisplay(data.facing());
        if (needsHeadDisplay) {
            if (existingHeadDisplay != null) {
                Entity entity = world.getEntity(existingHeadDisplay);
                if (entity instanceof ItemDisplay display) {
                    configureDisplay(display);
                    setDisplayItemIfChanged(display, plugin.getHeadItemForDirection(data.variant(), data.facing()));
                    display.setTransformation(calculateCornerHeadTransformation(data.facing()));
                    PipeTags.addPipeTag(entity, PipeTags.createHeadDisplayTag(normalized, data.facing(), data.variant()));
                    finalIds.add(existingHeadDisplay);
                } else if (entity != null) {
                    entity.remove();
                }
            } else {
                ItemDisplay headDisplay = spawnCornerHeadDisplay(normalized, data.variant(), data.facing());
                finalIds.add(headDisplay.getUniqueId());
            }
        } else if (existingHeadDisplay != null) {
            Entity entity = world.getEntity(existingHeadDisplay);
            if (entity != null) entity.remove();
        }

        // 移除不再需要的 directional 实体；保留的实体也要刷新贴图和变换
        for (Map.Entry<BlockFace, UUID> entry : existingDirEntities.entrySet()) {
            BlockFace outputFace = entry.getKey();
            Entity entity = world.getEntity(entry.getValue());
            if (!desiredFaces.contains(outputFace)) {
                if (entity != null) entity.remove();
                continue;
            }

            if (entity instanceof ItemDisplay display) {
                configureDisplay(display);
                setDisplayItemIfChanged(display, plugin.getDirectionalDisplayItem(data.variant(), outputFace));
                display.setTransformation(calculateCornerDirectionalTransformation(normalized, outputFace));
                PipeTags.addPipeTag(display, PipeTags.createDirectionalTag(normalized, outputFace, data.variant()));
                finalIds.add(entry.getValue());
                retainedDirFaces.add(outputFace);
            } else if (entity != null) {
                entity.remove();
            }
        }

        // 新增尚不存在的方向指示实体
        Location spawnLoc = normalized.clone().add(0.5, 0.5, 0.5);
        for (BlockFace outputFace : desiredFaces) {
            if (retainedDirFaces.contains(outputFace)) continue; // 已刷新，跳过

            ItemStack dirItem = plugin.getDirectionalDisplayItem(data.variant(), outputFace);
            Transformation dirTransform = calculateCornerDirectionalTransformation(normalized, outputFace);
            PipeVariant variant = data.variant();
            ItemDisplay dirDisplay = world.spawn(spawnLoc, ItemDisplay.class, entity -> {
                entity.setItemStack(dirItem);
                entity.setPersistent(true);
                configureDisplay(entity);
                entity.setTransformation(dirTransform);
                PipeTags.addPipeTag(entity, PipeTags.createDirectionalTag(normalized, outputFace, variant));
            });
            finalIds.add(dirDisplay.getUniqueId());
        }

        PipeTags.setRenderRevision(mainDisplay, plugin.getRenderRevision());
        pipes.put(normalized, new PipeData(data.facing(), List.copyOf(finalIds), data.variant()));
    }

    /**
     * 计算转角管道所有活跃输出方向：主输出方向始终包含，
     * 其余方向若有可接收的容器或朝向匹配的普通管道则也包含。
     * 侧向不自动接入另一个转角管道，避免两个转角紧挨时材质互相交融。
     */
    private List<BlockFace> getCornerActiveOutputFaces(Location cornerLoc, BlockFace primaryFacing) {
        List<BlockFace> faces = new ArrayList<>();
        faces.add(primaryFacing);

        Block cornerBlock = cornerLoc.getBlock();
        for (BlockFace face : new BlockFace[]{
                BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
                BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
            if (face == primaryFacing) continue;

            Block adjacent = cornerBlock.getRelative(face);

            ContainerAdapter adapter = ContainerAdapterRegistry.findAdapter(adjacent).orElse(null);
            if (adapter != null && adapter.canReceive(adjacent)) {
                faces.add(face);
                continue;
            }

            PipeData adjPipe = getPipeData(adjacent.getLocation());
            if (adjPipe != null && canRouteIntoAdjacentPipe(face, adjPipe, false)) {
                faces.add(face);
            }
        }
        return faces;
    }

    private boolean canRouteIntoAdjacentPipe(BlockFace directionToAdjacent, PipeData adjacentPipeData) {
        return canRouteIntoAdjacentPipe(directionToAdjacent, adjacentPipeData, true);
    }

    private boolean canRouteIntoAdjacentPipe(BlockFace directionToAdjacent, PipeData adjacentPipeData, boolean allowCornerPipe) {
        if (adjacentPipeData.variant().getBehaviorType() == BehaviorType.REGULAR) {
            return adjacentPipeData.facing() == directionToAdjacent;
        }
        if (!allowCornerPipe) {
            return false;
        }
        return adjacentPipeData.facing() != directionToAdjacent.getOppositeFace();
    }

    public List<ItemDisplay> spawnDisplayEntities(Location location, BlockFace facing, PipeVariant variant) {
        if (location.getWorld() != world) throw new RuntimeException("Location world does not match PipeManager world");
        if (world == null) return List.of();

        List<ItemDisplay> displays = new ArrayList<>();
        Location spawnLoc = location.clone().add(0.5, 0.5, 0.5);

        displays.add(spawnMainDisplay(location, facing, variant));

        // For corner pipes, spawn directional displays for all active output faces
        if (variant.getBehaviorType() == BehaviorType.CORNER) {
            if (needsCornerHeadDisplay(facing)) {
                displays.add(spawnCornerHeadDisplay(location, variant, facing));
            }
            for (BlockFace outputFace : getCornerActiveOutputFaces(location, facing)) {
                ItemStack directionalItem = plugin.getDirectionalDisplayItem(variant, outputFace);
                Transformation directionalTransformation = calculateCornerDirectionalTransformation(location, outputFace);
                ItemDisplay directionalDisplay = world.spawn(spawnLoc, ItemDisplay.class, entity -> {
                    entity.setItemStack(directionalItem);
                    entity.setPersistent(true);
                    configureDisplay(entity);
                    entity.setTransformation(directionalTransformation);
                    PipeTags.addPipeTag(entity, PipeTags.createDirectionalTag(location, outputFace, variant));
                });
                displays.add(directionalDisplay);
            }
        }

        return displays;
    }

    private ItemDisplay spawnMainDisplay(Location location, BlockFace facing, PipeVariant variant) {
        Location normalized = normalizeLocation(location);
        Location spawnLoc = normalized.clone().add(0.5, 0.5, 0.5);
        ItemStack item = plugin.getDisplayItem(variant, facing);
        Transformation transformation = calculateTransformation(normalized, facing, variant);
        return world.spawn(spawnLoc, ItemDisplay.class, entity -> {
            entity.setItemStack(item);
            entity.setPersistent(true);
            configureDisplay(entity);
            entity.setTransformation(transformation);
            PipeTags.addPipeTag(entity, PipeTags.createTag(normalized, facing, variant));
        });
    }

    public void markDisplayRevision(Location location) {
        PipeData data = pipes.get(normalizeLocation(location));
        if (data == null) return;
        for (UUID uuid : data.displayEntityIds()) {
            Entity entity = world.getEntity(uuid);
            String tag = entity != null ? PipeTags.getPipeTag(entity) : null;
            PipeTags.ParsedTag parsed = PipeTags.parse(tag);
            if (parsed != null && parsed.mainDisplay()) {
                PipeTags.setRenderRevision(entity, plugin.getRenderRevision());
                return;
            }
        }
    }

    private boolean isChest(Block block) {
        Material type = block.getType();
        String typeName = type.name();
        return type == Material.CHEST
            || type == Material.TRAPPED_CHEST
            || type == Material.ENDER_CHEST
            || typeName.contains("COPPER") && typeName.contains("CHEST");
    }

    private boolean isHopper(Block block) {
        return block.getType() == Material.HOPPER;
    }

    private boolean isPipe(Block block) {
        return pipes.containsKey(normalizeLocation(block.getLocation()));
    }

    /**
     * Categorize the block at the source (input) side of the pipe for display adjustments.
     */
    private String categorizeSourceBlock(Block sourceBlock, BlockFace currentFacing) {
        // Check if it's a pipe first
        PipeData pipeData = getPipeData(sourceBlock.getLocation());
        if (pipeData != null) {
            if (pipeData.variant().getBehaviorType() == BehaviorType.CORNER) {
                // Corner pipe outputs INTO this pipe if corner's facing == opposite of currentFacing
                if (pipeData.facing() == currentFacing.getOppositeFace()) {
                    return "corner-into";
                }
                // Corner pipe on our source side but facing orthogonally:
                // it may be feeding items sideways into us (secondary output), treat as connected
                if (pipeData.facing() != currentFacing) {
                    return "corner-into";
                }
                return "block"; // Corner pipe facing same direction as us, treat as solid
            }
            // Regular pipe
            if (pipeData.facing() == currentFacing) {
                return "pipe-continuous";
            }
            if (pipeData.facing() == currentFacing.getOppositeFace()) {
                return "pipe-into";
            }
            return "pipe-orthogonal"; // Orthogonal pipe behind us
        }

        // Check container types
        if (isChest(sourceBlock)) return "chest";
        if (isHopper(sourceBlock)) return "hopper";
        if (ContainerAdapterRegistry.findAdapter(sourceBlock).isPresent()) return "container";
        if (sourceBlock.getType().isAir() || !sourceBlock.getType().isSolid()) return "air";
        return "block";
    }

    /**
     * Categorize the block at the destination (output) side of the pipe for display adjustments.
     */
    private String categorizeDestinationBlock(Block destBlock, BlockFace currentFacing) {
        PipeData pipeData = getPipeData(destBlock.getLocation());
        if (pipeData != null) {
            if (pipeData.variant().getBehaviorType() == BehaviorType.CORNER) {
                // Corner outputs INTO this pipe if corner's facing == opposite of currentFacing
                if (pipeData.facing() == currentFacing.getOppositeFace()) {
                    return "corner-into";
                }
                return "corner-pipe";
            }
            // Regular pipe
            if (pipeData.facing() == currentFacing) {
                return "pipe-continuous";
            }
            if (pipeData.facing() == currentFacing.getOppositeFace()) {
                return "pipe-into";
            }
            return "pipe-orthogonal";
        }

        if (isChest(destBlock)) return "chest";
        if (isHopper(destBlock)) return "hopper";
        if (ContainerAdapterRegistry.findAdapter(destBlock).isPresent()) return "container";
        if (destBlock.getType().isAir() || !destBlock.getType().isSolid()) return "air";
        return "block";
    }

    /**
     * Get the direction key for config lookup based on pipe facing.
     * @param facing The direction the pipe is facing
     * @param isSource True for source side, false for destination side
     * @return "side", "up", or "down"
     */
    private String getDirectionKey(BlockFace facing, boolean isSource) {
        return switch (facing) {
            case UP -> isSource ? "down" : "up";
            case DOWN -> isSource ? "up" : "down";
            default -> "side";
        };
    }

    private Transformation calculateTransformation(Location pipeLocation, BlockFace facing, PipeVariant variant) {
        // Corner pipes use simple fixed transformation
        if (variant.getBehaviorType() == BehaviorType.CORNER) {
            return calculateCornerTransformation(facing);
        }

        // ============================================================
        // REGULAR PIPE DISPLAY TRANSFORMATION
        // ============================================================
        // The item display entity spawns at block center (0.5, 0.5, 0.5).
        // Without any transformation, the display's geometric center sits
        // at the source-side block boundary (the wall the head attaches to).
        //
        // We control the display by specifying where each endpoint should be:
        // - sourceEnd: position of back of display (relative to source boundary)
        //   Positive = extend into source block, Negative = retract toward dest
        // - destEnd: position of front of display (relative to dest boundary)
        //   Positive = extend into dest block, Negative = retract toward source
        //
        // All positions are in "forward" units along the pipe's facing direction.
        // ============================================================

        Block pipeBlock = pipeLocation.getBlock();
        DisplayConfig display = plugin.getDisplayConfig();

        // Base scale factor (2.0 means 1 block of model = 1 block of world space)
        double baseFacingScale = display.getFacingScale();
        double perpScale = display.getPerpendicularScale();

        // Perpendicular offsets (right/up) - these don't change with endpoint logic
        DisplayConfig.DirectionalOffset offset = switch (facing) {
            case UP -> display.getOffsetUp();
            case DOWN -> display.getOffsetDown();
            default -> display.getOffsetHorizontal();
        };
        double offsetRight = offset.right();
        double offsetUp = offset.up();

        // Get adjacent blocks and categorize them
        Block sourceBlock = pipeBlock.getRelative(facing.getOppositeFace());
        Block destBlock = pipeBlock.getRelative(facing);
        String sourceCategory = categorizeSourceBlock(sourceBlock, facing);
        String destCategory = categorizeDestinationBlock(destBlock, facing);

        // Get endpoint adjustments from config (with directional variants)
        String sourceDir = getDirectionKey(facing, true);
        String destDir = getDirectionKey(facing, false);
        double sourceEndOffset = display.getSourceAdjustment(sourceCategory, sourceDir);
        double destEndOffset = display.getDestinationAdjustment(destCategory, destDir);

        // ============================================================
        // ENDPOINT MATH
        // ============================================================
        // Block boundaries (relative to block center at 0):
        //   Source boundary: -0.5 (back of block)
        //   Dest boundary:   +0.5 (front of block)
        //
        // Desired endpoint positions:
        //   sourceEndPos = -0.5 - sourceEndOffset  (back of display)
        //   destEndPos   = +0.5 + destEndOffset    (front of display)
        //
        // Display length and center:
        //   displayLength = destEndPos - sourceEndPos
        //                 = (0.5 + destEndOffset) - (-0.5 - sourceEndOffset)
        //                 = 1.0 + sourceEndOffset + destEndOffset
        //
        //   displayCenter = (destEndPos + sourceEndPos) / 2
        //                 = ((0.5 + destEndOffset) + (-0.5 - sourceEndOffset)) / 2
        //                 = (destEndOffset - sourceEndOffset) / 2
        // ============================================================

        double sourceEndPos = -0.5 - sourceEndOffset;
        double destEndPos = 0.5 + destEndOffset;
        double displayLength = destEndPos - sourceEndPos;

        // Scale factor for the facing direction
        double facingScale = baseFacingScale * displayLength;

        // ============================================================
        // TRANSLATION CALCULATION
        // ============================================================
        // For HORIZONTAL pipes:
        //   The display model extends symmetrically from its center.
        //   After scaling by facingScale, the model extends facingScale/2 in each direction.
        //   We position it so its center is at displayCenter.
        //
        // For VERTICAL pipes (UP/DOWN):
        //   The display model extends from its origin in one direction only.
        //   For UP: origin is at the bottom, display extends upward.
        //   For DOWN: origin is at the top, display extends downward.
        //   We position the origin at sourceEndPos, letting scale extend toward dest.
        // ============================================================

        double offsetForward;
        if (facing == BlockFace.UP) {
            // UP pipes: origin is at top (destination end), display extends downward
            // Anchor at destEndPos, scale extends toward source
            offsetForward = destEndPos + 0.5;
        } else if (facing == BlockFace.DOWN) {
            // DOWN pipes: origin is at top (source end in world space), display extends downward
            // Anchor at sourceEndPos, scale extends toward destination
            offsetForward = sourceEndPos + 0.5;
        } else {
            // Horizontal pipes: center the display between endpoints
            double displayCenter = (destEndPos + sourceEndPos) / 2.0;
            offsetForward = 0.5 + displayCenter;
        }

        // Build the transformation components
        Vector3f scale = buildScale(facing, (float) facingScale, (float) perpScale);
        Vector3f translation = buildTranslation(facing,
            (float) offsetForward, (float) offsetRight, (float) offsetUp);
        AxisAngle4f rotation = buildRotation(facing);

        return new Transformation(
                translation,
                rotation,
                scale,
                new AxisAngle4f(0, 0, 0, 1)
        );
    }

    // ============================================================
    // TRANSFORMATION HELPER METHODS
    // ============================================================

    private Vector3f buildScale(BlockFace facing, float facingScale, float perpScale) {
        return switch (facing) {
            case NORTH, SOUTH, EAST, WEST -> new Vector3f(perpScale, perpScale, facingScale);
            case UP, DOWN -> new Vector3f(perpScale, facingScale, perpScale);
            default -> new Vector3f(perpScale, perpScale, perpScale);
        };
    }

    private Vector3f buildTranslation(BlockFace facing, float forward, float right, float up) {
        return switch (facing) {
            case NORTH -> new Vector3f(right, up, -forward);
            case SOUTH -> new Vector3f(-right, up, forward);
            case EAST -> new Vector3f(forward, up, right);
            case WEST -> new Vector3f(-forward, up, -right);
            case UP -> new Vector3f(right, forward, up);
            case DOWN -> new Vector3f(right, -forward, -up);
            default -> new Vector3f(0, 0, 0);
        };
    }

    private AxisAngle4f buildRotation(BlockFace facing) {
        return switch (facing) {
            case SOUTH -> new AxisAngle4f((float) Math.PI, 0, 1, 0);
            case EAST -> new AxisAngle4f((float) -Math.PI / 2, 0, 1, 0);
            case WEST -> new AxisAngle4f((float) Math.PI / 2, 0, 1, 0);
            default -> new AxisAngle4f(0, 0, 1, 0);
        };
    }

    private Transformation calculateCornerTransformation(BlockFace facing) {
        DisplayConfig display = plugin.getDisplayConfig();
        float scale = (float) display.getCornerScale();

        String direction = getDirectionKey(facing, false);
        Vector3f translation = new Vector3f(0, (float) display.getCornerBodyVerticalOffset(direction), 0);
        Vector3f scaleVec = new Vector3f(scale, scale, scale);
        AxisAngle4f rotation = buildCornerVerticalRotation(facing);

        return new Transformation(
                translation,
                rotation,
                scaleVec,
                new AxisAngle4f(0, 0, 0, 1)
        );
    }

    private boolean needsCornerHeadDisplay(BlockFace facing) {
        return facing == BlockFace.DOWN;
    }

    private ItemDisplay spawnCornerHeadDisplay(Location location, PipeVariant variant, BlockFace facing) {
        Location normalized = normalizeLocation(location);
        Location spawnLoc = normalized.clone().add(0.5, 0.5, 0.5);
        ItemStack headItem = plugin.getHeadItemForDirection(variant, facing);
        Transformation transformation = calculateCornerHeadTransformation(facing);
        return world.spawn(spawnLoc, ItemDisplay.class, entity -> {
            entity.setItemStack(headItem);
            entity.setPersistent(true);
            configureDisplay(entity);
            entity.setTransformation(transformation);
            PipeTags.addPipeTag(entity, PipeTags.createHeadDisplayTag(normalized, facing, variant));
        });
    }

    private static void configureDisplay(ItemDisplay display) {
        display.setViewRange(DISPLAY_VIEW_RANGE);
        display.setDisplayWidth(DISPLAY_CULLING_SIZE);
        display.setDisplayHeight(DISPLAY_CULLING_SIZE);
    }

    private AxisAngle4f buildCornerVerticalRotation(BlockFace facing) {
        return facing == BlockFace.DOWN
                ? new AxisAngle4f((float) Math.PI, 1, 0, 0)
                : new AxisAngle4f(0, 0, 1, 0);
    }

    private Transformation calculateCornerHeadTransformation(BlockFace facing) {
        DisplayConfig display = plugin.getDisplayConfig();
        String direction = getDirectionKey(facing, false);
        AxisAngle4f rotation = buildCornerVerticalRotation(facing);
        Vector3f translation = new Vector3f(0, (float) display.getCornerHeadVerticalOffset(direction), 0);
        return new Transformation(
                translation,
                rotation,
                new Vector3f(1.0f, 1.0f, 1.0f),
                new AxisAngle4f(0, 0, 0, 1)
        );
    }

    /**
     * Calculate transformation for the directional display entity of a corner pipe.
     * Uses adjustments.destination config values similar to regular pipes.
     */
    private Transformation calculateCornerDirectionalTransformation(Location pipeLocation, BlockFace facing) {
        Block pipeBlock = pipeLocation.getBlock();
        DisplayConfig display = plugin.getDisplayConfig();

        // Use regular pipe display settings for the directional component
        double baseFacingScale = display.getFacingScale();
        double perpScale = display.getPerpendicularScale();

        // Perpendicular offsets
        DisplayConfig.DirectionalOffset offset = switch (facing) {
            case UP -> display.getOffsetUp();
            case DOWN -> display.getOffsetDown();
            default -> display.getOffsetHorizontal();
        };
        double offsetRight = offset.right();
        double offsetUp = offset.up();

        // Get destination block and categorize it
        Block destBlock = pipeBlock.getRelative(facing);
        String destCategory = categorizeDestinationBlock(destBlock, facing);

        // Get destination endpoint adjustment (corner-specific, with fallback to global)
        String destDir = getDirectionKey(facing, false);
        double destEndOffset = display.getCornerDestinationAdjustment(destCategory, destDir);

        // For corner directional display:
        // - Source is at the corner piece center (0.0 offset from center)
        // - Destination uses the normal adjustment
        double sourceEndPos = 0.0; // Start from center of block
        double destEndPos = 0.5 + destEndOffset;
        double displayLength = destEndPos - sourceEndPos;
        double displayCenter = (destEndPos + sourceEndPos) / 2.0;

        // Scale factor for the facing direction
        double facingScale = baseFacingScale * displayLength;

        double offsetForward = displayCenter + display.getCornerDirectionalForwardOffset(destDir);

        // Build the transformation components
        Vector3f scale = buildScale(facing, (float) facingScale, (float) perpScale);
        Vector3f translation = buildTranslation(facing,
            (float) offsetForward, (float) offsetRight, (float) offsetUp);
        AxisAngle4f rotation = buildRotation(facing);

        return new Transformation(
                translation,
                rotation,
                scale,
                new AxisAngle4f(0, 0, 0, 1)
        );
    }

    private void spawnDebugParticles() {
        for (Map.Entry<Location, PipeData> entry : pipes.entrySet()) {
            Location loc = entry.getKey();
            World world = loc.getWorld();
            if (world != null) {
                world.spawnParticle(
                        Particle.DUST,
                        loc.clone().add(0.5, 0.5, 0.5),
                        3,
                        0.2, 0.2, 0.2,
                        0,
                        new Particle.DustOptions(Color.fromRGB(255, 100, 50), 1.0f)
                );
            }
        }
    }

    private void transferAllPipes() {
        long now = System.currentTimeMillis();
        long currentTick = Bukkit.getServer().getCurrentTick();
        for (Location loc : transferSchedule.pollWoken(now)) {
            reconcileTransferSchedule(loc, pipes.get(loc));
        }

        List<Location> duePipes = transferSchedule.pollDue(currentTick);
        // Keep the whole batch recoverable if an adapter throws before later entries are processed.
        for (Location loc : duePipes) transferSchedule.schedule(loc, currentTick);
        for (Location loc : duePipes) {
            PipeData data = pipes.get(loc);
            if (data == null || data.variant().getBehaviorType() == BehaviorType.CORNER) {
                transferSchedule.cancel(loc);
                lastTransferTick.remove(loc);
                continue;
            }

            if (transferItems(loc, data)) {
                unregisterPipe(loc);
                continue;
            }

            lastTransferTick.put(loc, currentTick);
            PipeData currentData = pipes.get(loc);
            if (currentData != null
                    && currentData.variant().getBehaviorType() != BehaviorType.CORNER
                    && !transferSchedule.isSleeping(loc)) {
                int intervalTicks = Math.max(1, currentData.variant().getTransferIntervalTicks());
                transferSchedule.schedule(loc, currentTick + intervalTicks);
            }
        }
    }

    private void reconcileTransferSchedule(Location loc, PipeData data) {
        if (data == null || data.variant().getBehaviorType() == BehaviorType.CORNER) {
            transferSchedule.cancel(loc);
            lastTransferTick.remove(loc);
            return;
        }
        if (transferSchedule.isSleeping(loc)) return;

        int intervalTicks = Math.max(1, data.variant().getTransferIntervalTicks());
        long currentTick = Bukkit.getServer().getCurrentTick();
        Long lastTick = lastTransferTick.get(loc);
        long dueTick = TransferSchedule.nextDueTick(
                currentTick, lastTick, getTransferPhase(loc, intervalTicks), intervalTicks);
        transferSchedule.schedule(loc, dueTick);
    }

    private int getTransferPhase(Location location, int intervalTicks) {
        int hash = Objects.hash(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        return Math.floorMod(hash, intervalTicks);
    }

    /**
     * 让指定位置的管道进入休眠，直到 {@code durationMs} 毫秒后再恢复检测。
     * durationMs <= 0 时不操作（即配置禁用该优化）。
     */
    private void sleepPipe(Location normalized, long durationMs) {
        if (durationMs <= 0) return;
        transferSchedule.sleep(normalized, System.currentTimeMillis() + durationMs);
    }

    /**
     * 唤醒某位置的管道（如附近的容器发生变化时可主动调用）。
     */
    public void wakeUpPipe(Location location) {
        Location normalized = normalizeLocation(location);
        transferSchedule.cancel(normalized);
        nullDestRecheckUntil.remove(normalized); // 同时重置末端探测冷却
        reconcileTransferSchedule(normalized, pipes.get(normalized));
    }

    /**
     * Attempts to transfer items from this pipe.
     * @return true if the pipe should be removed (block no longer exists)
     */
    private boolean transferItems(Location pipeLocation, PipeData data) {
        if (data == null) return false;

        // Corner pipes never pull items - they only relay when items are pushed into them
        if (data.variant().getBehaviorType() == BehaviorType.CORNER) {
            return false;
        }

        waitingForTransferChunk = false;
        if (!isTransferBlockReady(pipeLocation.getBlock())) return false;
        Block pipeBlock = pipeLocation.getBlock();
        if (pipeBlock.getType() != Material.PLAYER_HEAD && pipeBlock.getType() != Material.PLAYER_WALL_HEAD) {
            return true;  // Signal removal
        }

        BlockFace facing = data.facing();
        BlockFace sourceDirection = facing.getOppositeFace();

        Block sourceBlock = pipeBlock.getRelative(sourceDirection);
        if (!isTransferBlockReady(sourceBlock)) return false;
        ContainerAdapter sourceAdapter = ContainerAdapterRegistry.findAdapter(sourceBlock).orElse(null);
        if (sourceAdapter == null) {
            sleepPipe(pipeLocation, plugin.getPipeConfig().getSourceEmptySleepMs());
            return false;
        }

        // Start with this pipe's items per transfer and find minimum along path
        int startingMax = data.variant().getItemsPerTransfer();

        // 先获取路径（含缓存），以便在提取前了解目的地的物品需求
        CachedPath path = getOrBuildPath(pipeLocation, facing);
        if (path.unavailableBlock() != null) return false;
        int transferAmount = path.minItemsPerTransfer();
        int maxToExtract = Math.min(startingMax, transferAmount);

        // 检查目的地是否声明了所需物品，若声明则针对性地从源容器提取。
        // 同时缓存适配器引用，避免后续对同一方块的重复查找。
        Block destBlock = path.destination() != null ? path.destination().getBlock() : null;
        ContainerAdapter destAdapter = destBlock != null
                ? ContainerAdapterRegistry.findAdapter(destBlock).orElse(null) : null;
        List<ItemStack> requestedItems = destAdapter != null ? destAdapter.requestedItems(destBlock) : List.of();

        ContainerAdapter.Extraction extraction = sourceAdapter.previewExtract(sourceBlock, maxToExtract,
                requestedItems, item -> destAdapter == null || destAdapter.canReceive(destBlock, item));
        ItemStack toTransfer = extraction.selected();
        if (toTransfer == null) {
            ItemStack anyItem = extraction.fallback();
            if (anyItem == null) {
                sleepPipe(pipeLocation, plugin.getPipeConfig().getSourceEmptySleepMs());
                return false;
            }
            ItemStack remaining = tryCornerJunctionAlternatives(path, anyItem);
            if (remaining != null && remaining.getAmount() > 0) {
                remaining = tryAlternativeDestination(path.lastPipeLocation(), path.destination(), remaining);
            }
            int remainingAmount = remaining == null ? 0 : Math.max(0, remaining.getAmount());
            int insertedAmount = anyItem.getAmount() - remainingAmount;
            if (insertedAmount > 0) {
                ItemStack extracted = anyItem.clone();
                extracted.setAmount(insertedAmount);
                sourceAdapter.commitExtract(sourceBlock, extracted);
                if (!requestedItems.isEmpty()) evictCacheEntry(normalizeLocation(pipeLocation));
            } else if (!waitingForTransferChunk) {
                sleepPipe(pipeLocation, plugin.getPipeConfig().getDestFullSleepMs());
            }
            return false;
        }

        boolean transferred = false;
        if (path.destination() == null) {
            // 无容器目的地时，先尝试转角节点的备用输出
            ItemStack remaining = tryCornerJunctionAlternatives(path, toTransfer);
            if (remaining != null && remaining.getAmount() > 0) {
                remaining = tryAlternativeDestination(path.lastPipeLocation(), null, remaining);
            }

            int remainingAmount = (remaining == null) ? 0 : Math.max(0, remaining.getAmount());
            int insertedAmount = toTransfer.getAmount() - remainingAmount;
            if (remainingAmount <= 0) {
                transferred = true;
            } else if (!waitingForTransferChunk && isDroppable(path.lastPipeLocation())) {
                // 仍有剩余无法传输，掉落在链条末端
                Location lastPipeLoc = path.lastPipeLocation();
                PipeData lastPipeData = getPipeData(lastPipeLoc);
                BlockFace finalFacing = lastPipeData != null ? lastPipeData.facing() : facing;

                // Spawn at the pipe face (boundary between pipe and destination block)
                double yOffset = switch (finalFacing) {
                    case UP -> 0.65;
                    case DOWN -> -0.35;
                    default -> 0.25;
                };
                Location dropLoc = lastPipeLoc.getBlock().getLocation().add(0.5, yOffset, 0.5);
                // Offset horizontal pipes to the pipe's output face
                if (finalFacing.getModY() == 0) {
                    dropLoc.add(finalFacing.getModX() * 0.6, 0, finalFacing.getModZ() * 0.6);
                }

                // Spawn item with velocity set during spawn to avoid dropItem's default velocity
                double baseSpeed = (finalFacing == BlockFace.DOWN) ? 0 : 0.25;
                double randomSpread = 0.05;
                final ItemStack finalTransfer = remaining;

                lastPipeLoc.getWorld().spawn(dropLoc, Item.class, spawnedItem -> {
                    spawnedItem.setItemStack(finalTransfer);

                    Vector velocity = new Vector(
                        finalFacing.getModX() * baseSpeed + (random.nextDouble() - 0.5) * randomSpread,
                        finalFacing.getModY() * baseSpeed + (random.nextDouble() - 0.5) * randomSpread,
                        finalFacing.getModZ() * baseSpeed + (random.nextDouble() - 0.5) * randomSpread
                    );
                    spawnedItem.setVelocity(velocity);
                });
                transferred = true;
            } else if (insertedAmount > 0) {
                ItemStack partialExtract = toTransfer.clone();
                partialExtract.setAmount(insertedAmount);
                sourceAdapter.commitExtract(sourceBlock, partialExtract);
                return false;
            } else {
                // 末端被非空气方块或当前不可接收的容器堵住时，不把物品喷出到方块里。
                if (!waitingForTransferChunk) sleepPipe(pipeLocation, plugin.getPipeConfig().getDestFullSleepMs());
            }
        } else {
            // destBlock and destAdapter are already resolved above when building the 'requested' check
            if (destAdapter != null) {
                ItemStack leftover = destAdapter.canReceive(destBlock, toTransfer)
                        ? destAdapter.insert(destBlock, toTransfer)
                        : toTransfer;
                int leftoverAmount = (leftover == null) ? 0 : Math.max(0, leftover.getAmount());
                int insertedAmount = toTransfer.getAmount() - leftoverAmount;
                if (leftoverAmount <= 0) {
                    // 全部插入成功
                    transferred = true;
                } else if (insertedAmount > 0) {
                    // 部分插入：仅提交实际插入数量，不进入休眠
                    ItemStack partialExtract = toTransfer.clone();
                    partialExtract.setAmount(insertedAmount);
                    sourceAdapter.commitExtract(sourceBlock, partialExtract);
                    return false;
                } else {
                    // 完全无法插入，尝试备用输出
                    ItemStack remaining = tryCornerJunctionAlternatives(path, toTransfer);
                    if (remaining != null && remaining.getAmount() > 0) {
                        remaining = tryAlternativeDestination(path.lastPipeLocation(), path.destination(), remaining);
                    }

                    int remainingAmount = (remaining == null) ? 0 : Math.max(0, remaining.getAmount());
                    int altInsertedAmount = toTransfer.getAmount() - remainingAmount;
                    if (remainingAmount <= 0) {
                        transferred = true;
                    } else if (altInsertedAmount > 0) {
                        ItemStack partialExtract = toTransfer.clone();
                        partialExtract.setAmount(altInsertedAmount);
                        sourceAdapter.commitExtract(sourceBlock, partialExtract);
                        return false;
                    } else {
                        // 所有出口均已满，进入休眠：接下来若干毫秒内不再检测此管道
                        if (!waitingForTransferChunk) sleepPipe(pipeLocation, plugin.getPipeConfig().getDestFullSleepMs());
                    }
                }
            }
        }

        if (transferred) {
            sourceAdapter.commitExtract(sourceBlock, toTransfer);
        }
        return false;
    }

    private boolean isTransferBlockReady(Block block) {
        boolean ready = entitiesLoadedChunks.contains(Chunk.getChunkKey(block.getX() >> 4, block.getZ() >> 4))
                && ContainerAdapterRegistry.isInventoryLoaded(block);
        if (ready && block.getType().name().endsWith("CHEST")
                && block.getBlockData() instanceof org.bukkit.block.data.type.Chest chest
                && chest.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE) {
            BlockFace facing = chest.getFacing();
            int side = chest.getType() == org.bukkit.block.data.type.Chest.Type.LEFT ? 1 : -1;
            ready = entitiesLoadedChunks.contains(Chunk.getChunkKey(
                    (block.getX() - facing.getModZ() * side) >> 4,
                    (block.getZ() + facing.getModX() * side) >> 4));
        }
        if (!ready) waitingForTransferChunk = true;
        return ready;
    }

    private boolean isDroppable(Location lastPipeLoc) {
        PipeData lastPipeData = getPipeData(lastPipeLoc);
        if (lastPipeData == null) return true;
        Block outputBlock = lastPipeLoc.getBlock().getRelative(lastPipeData.facing());
        return isTransferBlockReady(outputBlock) && outputBlock.isPassable();
    }

    /**
     * 转角管道多路输出：扫描路径链中所有转角节点，对每个节点尝试除入流方向和主输出方向之外
     * 的相邻管道/容器（按 NORTH→SOUTH→EAST→WEST→UP→DOWN 优先级顺序）。
     *
     * @param path 当前路径（含完整管道链）
     * @param item 待传输物品
     * @return 剩余未插入的物品；若全部插入则返回 {@code null}
     */
    private ItemStack tryCornerJunctionAlternatives(CachedPath path, ItemStack item) {
        return tryCornerJunctionAlternatives(path, item, new HashSet<>(), 0);
    }

    private ItemStack tryCornerJunctionAlternatives(CachedPath path, ItemStack item,
                                                    Set<Location> visitedTails, int depth) {
        if (item == null || item.getAmount() <= 0) return null;
        if (depth > MAX_FALLBACK_DEPTH) return item;

        Location currentTail = normalizeLocation(path.lastPipeLocation());
        if (!visitedTails.add(currentTail)) return item;

        ItemStack remaining = item.clone();
        List<Location> chain = path.pipeChain();
        for (int i = 1; i < chain.size(); i++) {
            if (remaining.getAmount() <= 0) return null;

            Location loc = chain.get(i);
            PipeData pipeData = getPipeData(loc);
            if (pipeData == null || pipeData.variant().getBehaviorType() != BehaviorType.CORNER) continue;

            // 推断物品入流方向：上一节点的朝向即为物品的行进方向，
            // 从转角管道视角看，入流面 = 行进方向的反方向
            PipeData prevPipeData = getPipeData(chain.get(i - 1));
            if (prevPipeData == null) continue;
            // 物品行进方向 = prevPipeData.facing()；入流面（要跳过）= 行进方向的反面
            BlockFace skipIncoming = prevPipeData.facing().getOppositeFace();
            BlockFace primaryOut = pipeData.facing(); // 主输出已尝试过，跳过

            Block cornerBlock = loc.getBlock();
            // 以链条内所有位置作为初始 visited，避免循环
            Set<Location> baseVisited = new HashSet<>(chain);

            for (BlockFace face : new BlockFace[]{
                    BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
                    BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
                if (face == skipIncoming) continue;
                if (face == primaryOut) continue;

                Block adjacent = cornerBlock.getRelative(face);
                if (!isTransferBlockReady(adjacent)) continue;
                Location adjLoc = normalizeLocation(adjacent.getLocation());

                // 先尝试直接相邻容器
                ContainerAdapter adjAdapter = ContainerAdapterRegistry.findAdapter(adjacent).orElse(null);
                if (adjAdapter != null && adjAdapter.canReceive(adjacent, remaining)) {
                    ItemStack leftover = adjAdapter.insert(adjacent, remaining);
                    if (leftover == null || leftover.getAmount() <= 0) return null;
                    remaining = leftover;
                    continue;
                }

                // 再尝试相邻普通管道（沿该管道继续寻路）
                PipeData adjPipeData = getPipeData(adjLoc);
                if (adjPipeData == null) continue;
                if (!canRouteIntoAdjacentPipe(face, adjPipeData, false)) continue;

                Set<Location> visited = new HashSet<>(baseVisited);
                visited.add(loc); // 标记转角自身，防止重入
                CachedPath altPath = findDestination(adjLoc, adjPipeData.facing(), visited, new ArrayList<>(), Integer.MAX_VALUE);
                if (altPath.unavailableBlock() != null) continue;

                ItemStack branchRemaining = remaining;
                if (altPath.destination() != null) {
                    Block destBlock = altPath.destination().getBlock();
                    ContainerAdapter destAdapter = ContainerAdapterRegistry.findAdapter(destBlock).orElse(null);
                    if (destAdapter != null && destAdapter.canReceive(destBlock, branchRemaining)) {
                        ItemStack leftover = destAdapter.insert(destBlock, branchRemaining);
                        branchRemaining = (leftover == null || leftover.getAmount() <= 0) ? null : leftover;
                    }
                }

                if (branchRemaining != null && branchRemaining.getAmount() > 0) {
                    branchRemaining = tryCornerJunctionAlternatives(altPath, branchRemaining, visitedTails, depth + 1);
                }
                if (branchRemaining != null && branchRemaining.getAmount() > 0) {
                    branchRemaining = tryAlternativeDestination(
                            altPath.lastPipeLocation(), altPath.destination(), branchRemaining, visitedTails, depth + 1);
                }

                if (branchRemaining == null || branchRemaining.getAmount() <= 0) return null;
                remaining = branchRemaining;
            }
        }
        return remaining;
    }

    /**
     * 优先级分流：主目标不可用时，尝试链条末端周围的相邻容器或相邻管道分支。
     * <p>
     * 按 NORTH → SOUTH → EAST → WEST → UP → DOWN 固定顺序依次尝试，
     * 若相邻方块是管道，则沿该分支继续寻路到可接收容器。
     *
     * @param lastPipeLoc 链条末端管道的位置
     * @param primaryDest 主目标位置（已满，跳过）
     * @param item        待传输的物品
     * @return 剩余未插入的物品；若全部插入则返回 {@code null}
     */
    private ItemStack tryAlternativeDestination(Location lastPipeLoc, Location primaryDest, ItemStack item) {
        return tryAlternativeDestination(lastPipeLoc, primaryDest, item, new HashSet<>(), 0);
    }

    private ItemStack tryAlternativeDestination(Location lastPipeLoc, Location primaryDest, ItemStack item,
                                                Set<Location> visitedTails, int depth) {
        if (item == null || item.getAmount() <= 0) return null;
        if (depth > MAX_FALLBACK_DEPTH) return item;

        Location currentTail = normalizeLocation(lastPipeLoc);
        if (!visitedTails.add(currentTail)) return item;

        Block lastPipeBlock = lastPipeLoc.getBlock();
        PipeData lastPipeData = getPipeData(lastPipeLoc);
        // 链条来向（反方向），不向源头插入
        BlockFace backFace = lastPipeData != null ? lastPipeData.facing().getOppositeFace() : null;
        BlockFace primaryFace = lastPipeData != null ? lastPipeData.facing() : null;
        boolean lastPipeIsCorner = lastPipeData != null
                && lastPipeData.variant().getBehaviorType() == BehaviorType.CORNER;

        ItemStack remaining = item.clone();
        Location normalizedPrimary = primaryDest == null ? null : normalizeLocation(primaryDest);
        for (BlockFace face : new BlockFace[]{
                BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
                BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
            if (remaining.getAmount() <= 0) return null;

            // 跳过已尝试的主目标方向
            if (face == backFace) continue;
            if (!lastPipeIsCorner && face != primaryFace) continue;

            Block adjacent = lastPipeBlock.getRelative(face);
            if (!isTransferBlockReady(adjacent)) continue;
            Location adjLoc = normalizeLocation(adjacent.getLocation());

            // 跳过主目标（已满）
            if (Objects.equals(adjLoc, normalizedPrimary)) continue;
            // 先尝试直接相邻容器
            ContainerAdapter adapter = ContainerAdapterRegistry.findAdapter(adjacent).orElse(null);
            if (adapter != null && adapter.canReceive(adjacent, remaining)) {
                ItemStack leftover = adapter.insert(adjacent, remaining);
                if (leftover == null || leftover.getAmount() <= 0) return null;
                remaining = leftover;
                continue;
            }

            // 再尝试相邻管道分支（多层并联）。转角管道只允许在主出口方向接入，侧向不自动串接。
            PipeData adjPipeData = getPipeData(adjLoc);
            if (adjPipeData == null) continue;
            if (!canRouteIntoAdjacentPipe(face, adjPipeData, face == primaryFace)) continue;

            Set<Location> visited = new HashSet<>();
            visited.add(normalizeLocation(lastPipeLoc));
            CachedPath altPath = findDestination(adjLoc, adjPipeData.facing(), visited, new ArrayList<>(), Integer.MAX_VALUE);
            if (altPath.unavailableBlock() != null) continue;

            ItemStack branchRemaining = remaining;
            if (altPath.destination() != null) {
                Location altDestLoc = normalizeLocation(altPath.destination());
                if (!Objects.equals(altDestLoc, normalizedPrimary)) {
                    Block altDestBlock = altPath.destination().getBlock();
                    ContainerAdapter altDestAdapter = ContainerAdapterRegistry.findAdapter(altDestBlock).orElse(null);
                    if (altDestAdapter != null && altDestAdapter.canReceive(altDestBlock, branchRemaining)) {
                        ItemStack leftover = altDestAdapter.insert(altDestBlock, branchRemaining);
                        branchRemaining = (leftover == null || leftover.getAmount() <= 0) ? null : leftover;
                    }
                }
            }

            // 分支路径自身也可能包含转角并联输出，继续沿分支做一次并联分流。
            if (branchRemaining != null && branchRemaining.getAmount() > 0) {
                branchRemaining = tryCornerJunctionAlternatives(altPath, branchRemaining, visitedTails, depth + 1);
            }
            if (branchRemaining != null && branchRemaining.getAmount() > 0) {
                branchRemaining = tryAlternativeDestination(
                        altPath.lastPipeLocation(), altPath.destination(), branchRemaining, visitedTails, depth + 1);
            }

            if (branchRemaining == null || branchRemaining.getAmount() <= 0) return null;
            remaining = branchRemaining;
        }
        return remaining;
    }

    private CachedPath getOrBuildPath(Location pipeLocation, BlockFace facing) {
        Location key = normalizeLocation(pipeLocation);

        if (dirtyPaths.remove(key)) {
            evictCacheEntry(key);
        }

        CachedPath cached = pathCache.get(key);
        if (cached != null) {
            if (isPathStillValid(key, cached)) {
                return cached;
            }
            evictCacheEntry(key);
        }

        CachedPath fresh = findDestination(pipeLocation, facing, new HashSet<>(), new ArrayList<>(), Integer.MAX_VALUE);
        pathCache.put(key, fresh);
        for (Location member : fresh.pipeChain()) {
            chainMembership.computeIfAbsent(member, k -> new HashSet<>()).add(key);
        }
        return fresh;
    }

    /**
     * 驱逐单条路径缓存并同步清理反向索引。
     */
    private void evictCacheEntry(Location key) {
        CachedPath old = pathCache.remove(key);
        if (old == null) return;
        nullDestRecheckUntil.remove(normalizeLocation(old.lastPipeLocation()));
        for (Location member : old.pipeChain()) {
            Set<Location> starts = chainMembership.get(member);
            if (starts != null) {
                starts.remove(key);
                if (starts.isEmpty()) chainMembership.remove(member);
            }
        }
    }

    /**
     * 通过反向索引精准驱逐所有链路经过 {@code member} 的缓存条目。
     * O(出度链路数)，与网络总规模无关。
     */
    private void evictCacheByMember(Location member) {
        Set<Location> starts = chainMembership.remove(member);
        if (starts == null) return;
        for (Location start : starts) {
            CachedPath old = pathCache.remove(start);
            if (old == null) continue;
            nullDestRecheckUntil.remove(normalizeLocation(old.lastPipeLocation()));
            // 同步清理该路径其他成员对 start 的反向引用
            for (Location otherMember : old.pipeChain()) {
                if (otherMember.equals(member)) continue;
                Set<Location> otherStarts = chainMembership.get(otherMember);
                if (otherStarts != null) {
                    otherStarts.remove(start);
                    if (otherStarts.isEmpty()) chainMembership.remove(otherMember);
                }
            }
        }
    }

    private boolean isPathStillValid(Location pipeKey, CachedPath path) {
        if (path.unavailableBlock() != null) return !isTransferBlockReady(path.unavailableBlock().getBlock());
        if (path.destination() == null) {
            Location recheckKey = normalizeLocation(path.lastPipeLocation());
            long recheckMs = plugin.getPipeConfig().getEndRecheckSleepMs();
            if (recheckMs > 0) {
                Long recheckAt = nullDestRecheckUntil.get(recheckKey);
                long now = System.currentTimeMillis();
                if (recheckAt != null && now < recheckAt) {
                    return true; // 冷却中，O(1)
                }
            }

            // 冷却到期，检查末端是否新放置了容器或管道
            PipeData lastPipeData = getPipeData(recheckKey);
            if (lastPipeData != null) {
                Block endBlock = recheckKey.getBlock().getRelative(lastPipeData.facing());
                if (!isTransferBlockReady(endBlock)) return false;
                Location endLoc = normalizeLocation(endBlock.getLocation());
                ContainerAdapter endAdapter = ContainerAdapterRegistry.findAdapter(endBlock).orElse(null);
                if ((endAdapter != null && endAdapter.canReceive(endBlock)) || getPipeData(endLoc) != null) {
                    return false; // 末端出现了新的容器或管道，需要重新寻路
                }
            }

            // 仍无目标，重置冷却计时
            if (recheckMs > 0) {
                nullDestRecheckUntil.put(recheckKey, System.currentTimeMillis() + recheckMs);
            }
            return true;
        }

        Block destBlock = path.destination().getBlock();
        if (!isTransferBlockReady(destBlock)) return false;
        ContainerAdapter destAdapter = ContainerAdapterRegistry.findAdapter(destBlock).orElse(null);
        return destAdapter != null && destAdapter.canReceive(destBlock);
    }

    private CachedPath findDestination(Location pipeLocation, BlockFace facing,
                                       Set<Location> visited, List<Location> chain, int currentMin) {
        Location normalized = normalizeLocation(pipeLocation);
        chain.add(normalized);

        // 将本节点的传输速率纳入最小值计算
        PipeData selfData = getPipeData(normalized);
        if (selfData != null) currentMin = Math.min(currentMin, selfData.variant().getItemsPerTransfer());

        Block nextBlock = pipeLocation.getBlock().getRelative(facing);
        Location nextLoc = normalizeLocation(nextBlock.getLocation());
        if (!isTransferBlockReady(nextBlock)) {
            return new CachedPath(null, pipeLocation, chain, currentMin, nextLoc);
        }

        if (visited.contains(nextLoc)) {
            return new CachedPath(null, pipeLocation, chain, currentMin);
        }
        visited.add(nextLoc);

        ContainerAdapter destAdapter = ContainerAdapterRegistry.findAdapter(nextBlock).orElse(null);
        if (destAdapter != null && destAdapter.canReceive(nextBlock)) {
            return new CachedPath(nextLoc, pipeLocation, chain, currentMin);
        }

        PipeData nextPipeData = getPipeData(nextLoc);
        if (nextPipeData != null) {
            if (!canRouteIntoAdjacentPipe(facing, nextPipeData)) {
                return new CachedPath(null, pipeLocation, chain, currentMin);
            }
            // Otherwise, follow the next pipe's own output direction.
            return findDestination(nextLoc, nextPipeData.facing(), visited, chain, currentMin);
        }

        return new CachedPath(null, pipeLocation, chain, currentMin);
    }

    public void shutdown() {
        stopTasks();
        pipes.clear();
        pipesByChunk.clear();
        entitiesLoadedChunks.clear();
        lastTransferTick.clear();
        transferSchedule.clear();
        pathCache.clear();
        dirtyPaths.clear();
        nullDestRecheckUntil.clear();
        chainMembership.clear();
        pendingDisplayUpdates.clear();
        queuedDisplayUpdates.clear();
        deferredDisplayUpdates.clear();
        missingChunksByDisplay.clear();
    }

    /**
     * Removes orphaned display entities in the given world.
     * An orphaned display entity is one that has a pipe tag but no corresponding pipe block.
     * @return The number of orphaned display entities removed
     */
    public int cleanupOrphanedDisplays() {
        int removed = 0;
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof ItemDisplay)) continue;
            if (!PipeTags.isPipeEntity(entity)) continue;

            String pipeTag = PipeTags.getPipeTag(entity);
            if (pipeTag == null) continue;

            Location blockLoc = PipeTags.parseLocation(pipeTag, world);
            if (blockLoc == null) continue;

            Block block = blockLoc.getBlock();
            Material type = block.getType();

            // Check if there's a valid pipe block at this location
            if (type != Material.PLAYER_HEAD && type != Material.PLAYER_WALL_HEAD) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * Counts orphaned display entities in the given world.
     * @return The number of orphaned display entities
     */
    public int countOrphanedDisplays() {
        int count = 0;
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof ItemDisplay)) continue;
            if (!PipeTags.isPipeEntity(entity)) continue;

            String pipeTag = PipeTags.getPipeTag(entity);
            if (pipeTag == null) continue;

            Location blockLoc = PipeTags.parseLocation(pipeTag, world);
            if (blockLoc == null) continue;

            Block block = blockLoc.getBlock();
            Material type = block.getType();

            if (type != Material.PLAYER_HEAD && type != Material.PLAYER_WALL_HEAD) {
                count++;
            }
        }
        return count;
    }

    /**
     * Gets a count of registered pipes grouped by variant ID.
     * @return Map of variant ID to count
     */
    public Map<String, Integer> getPipeCountsByVariant() {
        Map<String, Integer> counts = new HashMap<>();
        for (PipeData data : pipes.values()) {
            String variantId = data.variant().getId();
            counts.merge(variantId, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Gets the total number of registered pipes.
     * @return Total pipe count
     */
    public int getTotalPipeCount() {
        return pipes.size();
    }

    /**
     * Deletes all pipes and their display entities in the given world.
     * Also removes the pipe blocks themselves.
     * @return The number of pipes deleted
     */
    public int deleteAllPipes() {
        List<Location> toRemove = new ArrayList<>();

        for (Map.Entry<Location, PipeData> entry : pipes.entrySet()) {
            Location loc = entry.getKey();
            if (!world.equals(loc.getWorld())) continue;

            toRemove.add(loc);
        }

        for (Location loc : toRemove) {
            // Remove the block
            Block block = loc.getBlock();
            if (block.getType() == Material.PLAYER_HEAD || block.getType() == Material.PLAYER_WALL_HEAD) {
                block.setType(Material.AIR);
            }
            // Remove display entities and unregister
            unregisterPipe(loc);
        }

        return toRemove.size();
    }

    public void reload() {
        world.submitScopedTask(() -> {
            refreshAllDisplays();
            stopTasks();
            startTasks();
        });
    }

    private void refreshAllDisplays() {
        Set<String> removedVariants = new HashSet<>();
        for (Location location : new ArrayList<>(pipes.keySet())) {
            PipeData data = pipes.get(location);
            if (data == null) continue;

            PipeVariant currentVariant = plugin.getVariantRegistry().getVariant(data.variant().getId());
            if (currentVariant == null) {
                removedVariants.add(data.variant().getId());
                continue;
            }
            if (currentVariant != data.variant()) {
                data = new PipeData(data.facing(), data.displayEntityIds(), currentVariant);
                pipes.put(location, data);
                reconcileTransferSchedule(location, data);
                evictCacheByMember(location);
                dirtyPaths.add(location);
            }
            queueDisplayUpdate(location);
        }
        for (String variantId : removedVariants) {
            plugin.getLogger().warning("Cannot refresh removed pipe variant '" + variantId + "'");
        }
    }

    private void queueDisplayUpdate(Location location) {
        Location normalized = normalizeLocation(location);
        PipeData data = pipes.get(normalized);
        if (data == null) return;

        Set<Long> missingChunks = getMissingDisplayChunks(normalized, data);
        if (!missingChunks.isEmpty()) {
            deferDisplayUpdate(normalized, missingChunks);
            return;
        }

        clearDeferredDisplayUpdate(normalized);
        if (queuedDisplayUpdates.add(normalized)) {
            pendingDisplayUpdates.add(normalized);
        }
    }

    private void processPendingDisplayUpdates() {
        long budgetNanos = plugin.getPipeConfig().getDisplayUpdateBudgetNanos();
        long startedAt = System.nanoTime();

        while (!pendingDisplayUpdates.isEmpty()) {
            Location location = pendingDisplayUpdates.removeFirst();
            if (queuedDisplayUpdates.remove(location) && pipes.containsKey(location)) {
                updateDisplayEntity(location);
            }
            if (budgetNanos > 0 && System.nanoTime() - startedAt >= budgetNanos) return;
        }
    }

    private Set<Long> getMissingDisplayChunks(Location location, PipeData data) {
        Set<Long> missing = new HashSet<>();
        addMissingChunk(missing, location.getBlockX(), location.getBlockZ());

        if (data.variant().getBehaviorType() == BehaviorType.CORNER) {
            for (BlockFace face : ADJACENT_FACES) {
                addMissingChunk(missing,
                        location.getBlockX() + face.getModX(),
                        location.getBlockZ() + face.getModZ());
            }
        } else {
            BlockFace facing = data.facing();
            addMissingChunk(missing,
                    location.getBlockX() + facing.getModX(),
                    location.getBlockZ() + facing.getModZ());
            BlockFace opposite = facing.getOppositeFace();
            addMissingChunk(missing,
                    location.getBlockX() + opposite.getModX(),
                    location.getBlockZ() + opposite.getModZ());
        }
        return missing;
    }

    private void addMissingChunk(Set<Long> missing, int blockX, int blockZ) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            missing.add(Chunk.getChunkKey(chunkX, chunkZ));
        }
    }

    private boolean hasCrossChunkDisplayDependency(Location location, PipeData data) {
        long homeChunk = Chunk.getChunkKey(location);
        if (data.variant().getBehaviorType() == BehaviorType.CORNER) {
            for (BlockFace face : ADJACENT_FACES) {
                if (homeChunk != Chunk.getChunkKey(
                        (location.getBlockX() + face.getModX()) >> 4,
                        (location.getBlockZ() + face.getModZ()) >> 4)) {
                    return true;
                }
            }
            return false;
        }

        BlockFace facing = data.facing();
        BlockFace opposite = facing.getOppositeFace();
        return homeChunk != Chunk.getChunkKey(
                (location.getBlockX() + facing.getModX()) >> 4,
                (location.getBlockZ() + facing.getModZ()) >> 4)
                || homeChunk != Chunk.getChunkKey(
                (location.getBlockX() + opposite.getModX()) >> 4,
                (location.getBlockZ() + opposite.getModZ()) >> 4);
    }

    private void deferDisplayUpdate(Location location, Set<Long> missingChunks) {
        clearDeferredDisplayUpdate(location);
        missingChunksByDisplay.put(location, Set.copyOf(missingChunks));
        for (long chunkKey : missingChunks) {
            deferredDisplayUpdates.computeIfAbsent(chunkKey, ignored -> new HashSet<>()).add(location);
        }
    }

    private void clearDeferredDisplayUpdate(Location location) {
        Set<Long> missingChunks = missingChunksByDisplay.remove(location);
        if (missingChunks == null) return;

        for (long chunkKey : missingChunks) {
            Set<Location> locations = deferredDisplayUpdates.get(chunkKey);
            if (locations == null) continue;
            locations.remove(location);
            if (locations.isEmpty()) deferredDisplayUpdates.remove(chunkKey);
        }
    }

    private void resumeDisplayUpdatesWaitingFor(long chunkKey) {
        Set<Location> locations = deferredDisplayUpdates.remove(chunkKey);
        if (locations == null) return;

        for (Location location : List.copyOf(locations)) {
            queueDisplayUpdate(location);
        }
    }

    private void stopTasks() {
        world.removeCyclicalTask("pipes_transfer");
        world.removeCyclicalTask("pipes_particles");
        world.removeCyclicalTask(DISPLAY_UPDATE_TASK);
    }

    private Location normalizeLocation(Location location) {
        return new Location(location.getWorld(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ());
    }

    public void scanForExistingPipes() {
        for (Chunk chunk : world.getLoadedChunks()) {
            if (chunk.isEntitiesLoaded()) entitiesLoadedChunks.add(chunk.getChunkKey());
        }
        loadEntities(world.getEntities());
    }

    public int loadEntities(Chunk chunk, Iterable<? extends Entity> entities) {
        if (chunk.getWorld() != world) {
            throw new IllegalArgumentException("Chunk world does not match PipeManager world");
        }
        int count = loadEntities(entities);
        entitiesLoadedChunks.add(chunk.getChunkKey());
        resumeDisplayUpdatesWaitingFor(chunk.getChunkKey());
        return count;
    }

    public int loadEntities(Iterable<? extends Entity> entities) {
        Map<Location, ScannedPipe> groups = new HashMap<>();
        for (Entity entity : entities) {
            if (!(entity instanceof ItemDisplay)) continue;

            String pipeTag = PipeTags.getPipeTag(entity);
            if (pipeTag == null) continue;

            PipeTags.ParsedTag parsed = PipeTags.parse(pipeTag);
            if (parsed == null) continue;

            Location normalized = parsed.location(world);
            groups.computeIfAbsent(normalized, ignored -> new ScannedPipe())
                    .add(entity, pipeTag, parsed);
        }

        int count = 0;
        for (Map.Entry<Location, ScannedPipe> entry : groups.entrySet()) {
            Location location = entry.getKey();
            ScannedPipe scanned = entry.getValue();
            if (!scanned.hasMainDisplay()) continue;

            PipeVariant variant = plugin.getVariantRegistry().getVariant(scanned.mainVariantId);
            if (variant == null) continue;
            if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) continue;

            Material blockType = location.getBlock().getType();
            if (blockType != Material.PLAYER_HEAD && blockType != Material.PLAYER_WALL_HEAD) {
                for (UUID uuid : scanned.displayIds()) {
                    Entity entity = world.getEntity(uuid);
                    if (entity != null) entity.remove();
                }
                detachPipe(location);
                continue;
            }

            PipeData existing = pipes.get(location);
            List<UUID> displayIds = scanned.displayIds();
            boolean newlyRegistered = existing == null;
            boolean changed = newlyRegistered
                    || existing.facing() != scanned.mainFacing
                    || !existing.variant().getId().equals(variant.getId())
                    || !new HashSet<>(existing.displayEntityIds()).equals(new HashSet<>(displayIds));
            if (changed) {
                registerPipe(location, scanned.mainFacing, displayIds, variant);
            }
            if (newlyRegistered) {
                count++;
                if (hasCrossChunkDisplayDependency(location, pipes.get(location))) {
                    queueDisplayUpdate(location);
                }
                notifyPipeRegistered(location);
            }

            if (scanned.needsDisplayRepair(location, variant, plugin.getRenderRevision())) {
                queueDisplayUpdate(location);
            }
        }
        return count;
    }

    public void unloadEntities(Chunk chunk) {
        if (chunk.getWorld() != world) {
            throw new IllegalArgumentException("Chunk world does not match PipeManager world");
        }
        entitiesLoadedChunks.remove(chunk.getChunkKey());
        Set<Location> locations = pipesByChunk.get(chunk.getChunkKey());
        if (locations == null) return;
        for (Location location : List.copyOf(locations)) detachPipe(location);
    }

    private void notifyPipeRegistered(Location location) {
        PipeData registered = pipes.get(location);
        if (registered == null) return;

        for (BlockFace face : ADJACENT_FACES) {
            Location adjacent = new Location(world,
                    location.getBlockX() + face.getModX(),
                    location.getBlockY() + face.getModY(),
                    location.getBlockZ() + face.getModZ());
            PipeData pipeData = pipes.get(adjacent);
            if (pipeData == null) continue;

            if (Chunk.getChunkKey(location) != Chunk.getChunkKey(adjacent)) {
                boolean registeredDisplayAffected = registered.variant().getBehaviorType() == BehaviorType.CORNER
                        || face == registered.facing()
                        || face == registered.facing().getOppositeFace();
                boolean adjacentDisplayAffected = pipeData.variant().getBehaviorType() == BehaviorType.CORNER
                        || face == pipeData.facing()
                        || face == pipeData.facing().getOppositeFace();
                if (registeredDisplayAffected) queueDisplayUpdate(location);
                if (adjacentDisplayAffected) queueDisplayUpdate(adjacent);
            }

            if (pipeData.facing() == face.getOppositeFace()) {
                wakeUpPipe(adjacent);
                invalidatePath(adjacent);
            } else if (pipeData.facing() == face) {
                wakeUpPipe(adjacent);
            }
        }
    }

    private static final class ScannedPipe {
        private final List<UUID> mainIds = new ArrayList<>();
        private final List<UUID> auxiliaryIds = new ArrayList<>();
        private final Set<String> variantIds = new HashSet<>();
        private final Set<BlockFace> directionalFaces = EnumSet.noneOf(BlockFace.class);
        private final Set<BlockFace> headFacings = EnumSet.noneOf(BlockFace.class);
        private int headCount;
        private boolean duplicateDirectional;
        private String mainVariantId;
        private String mainTag;
        private BlockFace mainFacing;
        private Long mainRevision;

        private void add(Entity entity, String tag, PipeTags.ParsedTag parsed) {
            variantIds.add(parsed.variantId());
            if (parsed.mainDisplay()) {
                mainIds.add(entity.getUniqueId());
                if (mainVariantId == null) {
                    mainVariantId = parsed.variantId();
                    mainTag = tag;
                    mainFacing = parsed.facing();
                    mainRevision = PipeTags.getRenderRevision(entity);
                }
            } else {
                auxiliaryIds.add(entity.getUniqueId());
                if (parsed.headDisplay()) {
                    headCount++;
                    headFacings.add(parsed.facing());
                } else if (!directionalFaces.add(parsed.facing())) {
                    duplicateDirectional = true;
                }
            }
        }

        private boolean hasMainDisplay() {
            return !mainIds.isEmpty();
        }

        private List<UUID> displayIds() {
            List<UUID> ids = new ArrayList<>(mainIds.size() + auxiliaryIds.size());
            ids.addAll(mainIds);
            ids.addAll(auxiliaryIds);
            return ids;
        }

        private boolean needsDisplayRepair(Location location, PipeVariant variant, long renderRevision) {
            if (mainIds.size() != 1 || variantIds.size() != 1
                    || !Objects.equals(mainTag, PipeTags.createTag(location, mainFacing, variant))
                    || !Objects.equals(mainRevision, renderRevision)) {
                return true;
            }
            if (variant.getBehaviorType() == BehaviorType.REGULAR) {
                return !auxiliaryIds.isEmpty();
            }

            int expectedHeadCount = mainFacing == BlockFace.DOWN ? 1 : 0;
            return headCount != expectedHeadCount
                    || headCount > 0 && !headFacings.equals(Set.of(mainFacing))
                    || duplicateDirectional
                    || !directionalFaces.contains(mainFacing);
        }
    }

    public BlockFace getFacingFromSkull(Block block) {
        if (block.getType() == Material.PLAYER_WALL_HEAD) {
            Directional directional = (Directional) block.getBlockData();
            return directional.getFacing();
        } else if (block.getType() == Material.PLAYER_HEAD) {
            Rotatable rotatable = (Rotatable) block.getBlockData();
            return rotatable.getRotation();
        }
        return BlockFace.NORTH;
    }

    public record PipeData(BlockFace facing, List<UUID> displayEntityIds, PipeVariant variant) {}
}
