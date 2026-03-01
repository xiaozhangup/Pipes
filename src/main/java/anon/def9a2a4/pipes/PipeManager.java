package anon.def9a2a4.pipes;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import anon.def9a2a4.pipes.config.DisplayConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PipeManager {

    private record CachedPath(Location destination, Location lastPipeLocation, List<Location> pipeChain) {}

    private final PipesPlugin plugin;
    private final int offset;
    private final World world;
    private final Random random = new Random();
    private final Map<Location, PipeData> pipes = new HashMap<>();
    private final Map<Location, Long> lastTransferTime = new HashMap<>();

    private final Map<Location, CachedPath> pathCache = new HashMap<>();
    private final Set<Location> dirtyPaths = new HashSet<>();
    private final Map<Location, Long> sleepUntil = new HashMap<>();

    public PipeManager(PipesPlugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        this.offset = random.nextInt(21);
    }

    public void startTasks() {
        // Use fastest interval among all variants for the task
        int fastestInterval = getFastestTransferInterval();
        world.submitCyclicalTask(
                "pipes_transfer",
                () -> {
                    if ((offset + Bukkit.getServer().getCurrentTick()) % fastestInterval == 0) {
                        this.transferAllPipes();
                    }
                }
        );

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

    private int getFastestTransferInterval() {
        int fastest = 10; // Default
        for (PipeVariant variant : plugin.getVariantRegistry().getAllVariants()) {
            if (variant.getTransferIntervalTicks() < fastest) {
                fastest = variant.getTransferIntervalTicks();
            }
        }
        return Math.max(1, fastest);
    }

    public void registerPipe(Location location, BlockFace facing, List<UUID> displayEntityIds, PipeVariant variant) {
        Location normalized = normalizeLocation(location);
        pipes.put(normalized, new PipeData(facing, displayEntityIds, variant));
        // 新管道可能让已有管道的路径在此「拐弯」，全量清缓存保证正确性。
        // 批量 scanChunk 时每次 registerPipe 都会清，但缓存在首次传输前会被按需重建，
        // 开销均摊到各自第一次 transferItems，而非全压在同一 tick。
        pathCache.clear();
        dirtyPaths.add(normalized);
    }

    /**
     * 标记单根管道路径失效，用于传输量参数等局部变化，不影响网络拓扑时使用。
     * 拓扑变化（管道增删、区块事件）应调用 pathCache.clear()。
     *
     * @param location 路径需要重算的管道位置
     */
    public void invalidatePath(Location location) {
        Location normalized = normalizeLocation(location);
        dirtyPaths.add(normalized);
    }

    public void unregisterPipe(Location location) {
        Location normalized = normalizeLocation(location);
        PipeData data = pipes.remove(normalized);
        lastTransferTime.remove(normalized);
        sleepUntil.remove(normalized);
        dirtyPaths.remove(normalized);
        pathCache.clear();

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
            String pipeTag = PipeTags.getPipeTag(entity.getScoreboardTags());
            if (pipeTag != null && PipeTags.matchesLocation(pipeTag, location)) {
                entity.remove();
            }
        }
    }

    public boolean isPipe(Location location) {
        return pipes.containsKey(normalizeLocation(location));
    }

    public PipeData getPipeData(Location location) {
        return pipes.get(normalizeLocation(location));
    }

    public void updateDisplayEntity(Location pipeLocation) {
        Location normalized = normalizeLocation(pipeLocation);
        PipeData data = pipes.get(normalized);
        if (data == null || data.displayEntityIds() == null || data.displayEntityIds().isEmpty()) return;

        if (pipeLocation.getWorld() != world) throw new RuntimeException("Location world does not match PipeManager world");
        if (world == null) return;

        // For corner pipes, we need to refresh all directional displays
        if (data.variant().getBehaviorType() == BehaviorType.CORNER) {
            refreshCornerDisplayEntities(pipeLocation);
        } else {
            // Regular pipes have single display
            UUID uuid = data.displayEntityIds().getFirst();
            Entity entity = world.getEntity(uuid);
            if (entity instanceof ItemDisplay display) {
                Transformation transformation = calculateTransformation(normalized, data.facing(), data.variant());
                display.setTransformation(transformation);
            }
        }
    }

    /**
     * 完全重建转角管道的所有 display 实体：保留主体显示，删除所有旧的方向指示
     * 实体，然后为每个活跃输出方向（主输出 + 副输出）分别生成一个新的方向指示实体。
     * <p>
     * 在转角放置、周围方块变化时调用，以保持视觉与传输逻辑一致。
     */
    public void refreshCornerDisplayEntities(Location location) {
        Location normalized = normalizeLocation(location);
        PipeData data = pipes.get(normalized);
        if (data == null || data.variant().getBehaviorType() != BehaviorType.CORNER) return;
        if (normalized.getWorld() != world) return;

        // 计算当前应存在的活跃输出方向集合
        Set<BlockFace> desiredFaces = new HashSet<>(getCornerActiveOutputFaces(normalized, data.facing()));

        // 分类现有实体：主体实体保留 UUID，directional 实体按方向索引
        List<UUID> finalIds = new ArrayList<>();
        Map<BlockFace, UUID> existingDirEntities = new HashMap<>(); // face -> UUID

        if (data.displayEntityIds() != null) {
            for (UUID uuid : data.displayEntityIds()) {
                Entity entity = world.getEntity(uuid);
                if (entity == null) continue;

                String tag = PipeTags.getPipeTag(entity.getScoreboardTags());
                if (tag == null) continue;

                if (PipeTags.isDirectionalTag(tag)) {
                    BlockFace face = PipeTags.parseFacing(tag);
                    if (face != null) {
                        existingDirEntities.put(face, uuid);
                    } else {
                        entity.remove(); // 无法解析方向，清除
                    }
                } else {
                    finalIds.add(uuid); // 主体实体，直接保留
                }
            }
        }

        // 移除不再需要的 directional 实体
        for (Map.Entry<BlockFace, UUID> entry : existingDirEntities.entrySet()) {
            if (desiredFaces.contains(entry.getKey())) {
                finalIds.add(entry.getValue()); // 仍需要，保留
            } else {
                Entity entity = world.getEntity(entry.getValue());
                if (entity != null) entity.remove(); // 不再需要，删除
            }
        }

        // 新增尚不存在的方向指示实体
        Location spawnLoc = normalized.clone().add(0.5, 0.5, 0.5);
        for (BlockFace outputFace : desiredFaces) {
            if (existingDirEntities.containsKey(outputFace)) continue; // 已存在，跳过

            ItemStack dirItem = plugin.getDirectionalDisplayItem(data.variant(), outputFace);
            Transformation dirTransform = calculateCornerDirectionalTransformation(normalized, outputFace);
            PipeVariant variant = data.variant();
            ItemDisplay dirDisplay = world.spawn(spawnLoc, ItemDisplay.class, entity -> {
                entity.setItemStack(dirItem);
                entity.setPersistent(true);
                entity.addScoreboardTag(PipeTags.createDirectionalTag(normalized, outputFace, variant));
                entity.setTransformation(dirTransform);
            });
            finalIds.add(dirDisplay.getUniqueId());
        }

        // 更新 PipeData 中的 display UUID 列表
        pipes.put(normalized, new PipeData(data.facing(), finalIds, data.variant()));
        pathCache.clear();
    }

    /**
     * 计算转角管道所有活跃输出方向：主输出方向始终包含，
     * 其余方向若有可接收的容器或非 head-to-head 的管道则也包含。
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
            if (adjPipe != null && adjPipe.facing() != face.getOppositeFace()) {
                faces.add(face);
            }
        }
        return faces;
    }

    public List<ItemDisplay> spawnDisplayEntities(Location location, BlockFace facing, PipeVariant variant) {
        if (location.getWorld() != world) throw new RuntimeException("Location world does not match PipeManager world");
        if (world == null) return List.of();

        List<ItemDisplay> displays = new ArrayList<>();
        Location spawnLoc = location.clone().add(0.5, 0.5, 0.5);

        // Spawn main display entity (non-directional for corner, directional for regular)
        ItemStack pipeItem = plugin.getDisplayItem(variant, facing);
        Transformation transformation = calculateTransformation(location, facing, variant);

        ItemDisplay mainDisplay = world.spawn(spawnLoc, ItemDisplay.class, entity -> {
            entity.setItemStack(pipeItem);
            entity.setPersistent(true);
            entity.addScoreboardTag(PipeTags.createTag(location, facing, variant));
            entity.setTransformation(transformation);
        });
        displays.add(mainDisplay);

        // For corner pipes, spawn directional displays for all active output faces
        if (variant.getBehaviorType() == BehaviorType.CORNER) {
            for (BlockFace outputFace : getCornerActiveOutputFaces(location, facing)) {
                ItemStack directionalItem = plugin.getDirectionalDisplayItem(variant, outputFace);
                Transformation directionalTransformation = calculateCornerDirectionalTransformation(location, outputFace);
                ItemDisplay directionalDisplay = world.spawn(spawnLoc, ItemDisplay.class, entity -> {
                    entity.setItemStack(directionalItem);
                    entity.setPersistent(true);
                    entity.addScoreboardTag(PipeTags.createDirectionalTag(location, outputFace, variant));
                    entity.setTransformation(directionalTransformation);
                });
                displays.add(directionalDisplay);
            }
        }

        return displays;
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
            return calculateCornerTransformation();
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

    private Transformation calculateCornerTransformation() {
        DisplayConfig display = plugin.getDisplayConfig();
        float scale = (float) display.getCornerScale();
        float height = (float) display.getCornerHeight();

        // Simple transformation: uniform scale, fixed height, no rotation
        Vector3f translation = new Vector3f(0, height - 0.5f, 0); // Adjust from center (0.5) to desired height
        Vector3f scaleVec = new Vector3f(scale, scale, scale);
        AxisAngle4f rotation = new AxisAngle4f(0, 0, 1, 0); // No rotation

        return new Transformation(
                translation,
                rotation,
                scaleVec,
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

        // Translation: position center of display at displayCenter
        double offsetForward = displayCenter + display.getCornerDirectionalForwardOffset();

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
        List<Location> toRemove = new ArrayList<>();

        for (Map.Entry<Location, PipeData> entry : pipes.entrySet()) {
            Location loc = entry.getKey();
            PipeData data = entry.getValue();

            // 休眠检测：若管道正在休眠则直接跳过，不做任何计算
            Long wakeTime = sleepUntil.get(loc);
            if (wakeTime != null) {
                if (now < wakeTime) continue;
                sleepUntil.remove(loc); // 已醒，清除记录
            }

            // Check if enough time has passed for this pipe's variant
            long intervalMs = data.variant().getTransferIntervalTicks() * 50L;
            Long lastTime = lastTransferTime.get(loc);

            if (lastTime == null || (now - lastTime) >= intervalMs) {
                if (transferItems(loc, data)) {
                    toRemove.add(loc);
                }
                lastTransferTime.put(loc, now);
            }
        }

        toRemove.forEach(pipes::remove);
    }

    /**
     * 让指定位置的管道进入休眠，直到 {@code durationMs} 毫秒后再恢复检测。
     * durationMs <= 0 时不操作（即配置禁用该优化）。
     */
    private void sleepPipe(Location normalized, long durationMs) {
        if (durationMs <= 0) return;
        sleepUntil.put(normalized, System.currentTimeMillis() + durationMs);
    }

    /**
     * 唤醒某位置的管道（如附近的容器发生变化时可主动调用）。
     */
    public void wakeUpPipe(Location location) {
        sleepUntil.remove(normalizeLocation(location));
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

        Block pipeBlock = pipeLocation.getBlock();
        if (pipeBlock.getType() != Material.PLAYER_HEAD && pipeBlock.getType() != Material.PLAYER_WALL_HEAD) {
            return true;  // Signal removal
        }

        BlockFace facing = data.facing();
        BlockFace sourceDirection = facing.getOppositeFace();

        Block sourceBlock = pipeBlock.getRelative(sourceDirection);
        ContainerAdapter sourceAdapter = ContainerAdapterRegistry.findAdapter(sourceBlock).orElse(null);
        if (sourceAdapter == null) {
            return false;
        }

        // Start with this pipe's items per transfer and find minimum along path
        int startingMax = data.variant().getItemsPerTransfer();
        ItemStack toTransfer = sourceAdapter.peekExtract(sourceBlock, startingMax);
        if (toTransfer == null) {
            // 源容器为空，进入休眠：接下来若干毫秒内不再检测此管道
            sleepPipe(pipeLocation, plugin.getPipeConfig().getSourceEmptySleepMs());
            return false;
        }

        // 从缓存获取路径，minItems 从 pipeChain 动态计算
        CachedPath path = getOrBuildPath(pipeLocation, facing);
        int transferAmount = calcMinItems(path.pipeChain(), startingMax);
        toTransfer.setAmount(Math.min(transferAmount, toTransfer.getAmount()));

        boolean transferred = false;
        if (path.destination() == null) {
            // 无容器目的地时，先尝试转角节点的备用输出
            transferred = tryCornerJunctionAlternatives(path, toTransfer);

            if (!transferred) {
                // 仍无法传输，掉落在链条末端
                Location lastPipeLoc = path.lastPipeLocation();
                PipeData lastPipeData = getPipeData(lastPipeLoc);
                BlockFace finalFacing = lastPipeData != null ? lastPipeData.facing() : facing;

                // Spawn at the pipe face (boundary between pipe and destination block)
                // Use lower Y for horizontal pipes since item entity has height
                double yOffset = finalFacing.getModY() == 0 ? 0.25 : 0.5;
                Location dropLoc = lastPipeLoc.getBlock().getLocation().add(0.5, yOffset, 0.5);
                // Offset to the pipe's output face
                dropLoc.add(finalFacing.getModX() * 0.6, finalFacing.getModY() * 0.6, finalFacing.getModZ() * 0.6);

                // For DOWN-facing pipes, lower spawn position to avoid clipping into the head
                if (finalFacing == BlockFace.DOWN) {
                    dropLoc.add(0, -0.05, 0);
                }

                // Spawn item with velocity set during spawn to avoid dropItem's default velocity
                double baseSpeed = (finalFacing == BlockFace.DOWN) ? 0 : 0.25;
                double randomSpread = 0.05;
                final ItemStack finalTransfer = toTransfer;

                Item item = lastPipeLoc.getWorld().spawn(dropLoc, Item.class, spawnedItem -> {
                    spawnedItem.setItemStack(finalTransfer);

                    Vector velocity = new Vector(
                        finalFacing.getModX() * baseSpeed + (random.nextDouble() - 0.5) * randomSpread,
                        finalFacing.getModY() * baseSpeed + (random.nextDouble() - 0.5) * randomSpread,
                        finalFacing.getModZ() * baseSpeed + (random.nextDouble() - 0.5) * randomSpread
                    );
                    spawnedItem.setVelocity(velocity);
                });

                transferred = true;
            }
        } else {
            Block destBlock = path.destination().getBlock();
            ContainerAdapter destAdapter = ContainerAdapterRegistry.findAdapter(destBlock).orElse(null);
            if (destAdapter != null) {
                ItemStack leftover = destAdapter.insert(destBlock, toTransfer);
                if (leftover == null || leftover.getAmount() <= 0) {
                    transferred = true;
                } else {
                    // 主目标已满，先尝试转角节点备用输出，再尝试末端管道周围其他容器
                    transferred = tryCornerJunctionAlternatives(path, toTransfer)
                               || tryAlternativeDestination(path.lastPipeLocation(), path.destination(), toTransfer);
                    if (!transferred) {
                        // 所有出口均已满，进入休眠：接下来若干毫秒内不再检测此管道
                        sleepPipe(pipeLocation, plugin.getPipeConfig().getDestFullSleepMs());
                    }
                }
            }
        }

        if (transferred) {
            sourceAdapter.commitExtract(sourceBlock, toTransfer);
        }
        return false;
    }

    /**
     * 转角管道多路输出：扫描路径链中所有转角节点，对每个节点尝试除入流方向和主输出方向之外
     * 的相邻管道/容器（按 NORTH→SOUTH→EAST→WEST→UP→DOWN 优先级顺序）。
     *
     * @param path 当前路径（含完整管道链）
     * @param item 待传输物品
     * @return 是否成功插入某个备用目的地
     */
    private boolean tryCornerJunctionAlternatives(CachedPath path, ItemStack item) {
        List<Location> chain = path.pipeChain();
        for (int i = 1; i < chain.size(); i++) {
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
                Location adjLoc = normalizeLocation(adjacent.getLocation());

                // 先尝试直接相邻容器
                ContainerAdapter adjAdapter = ContainerAdapterRegistry.findAdapter(adjacent).orElse(null);
                if (adjAdapter != null && adjAdapter.canReceive(adjacent)) {
                    ItemStack leftover = adjAdapter.insert(adjacent, item);
                    if (leftover == null || leftover.getAmount() <= 0) return true;
                    continue;
                }

                // 再尝试相邻管道（沿该管道继续寻路）
                PipeData adjPipeData = getPipeData(adjLoc);
                if (adjPipeData == null) continue;
                // 如果相邻管道朝向指回转角（head-to-head），跳过
                if (adjPipeData.facing() == face.getOppositeFace()) continue;

                Set<Location> visited = new HashSet<>(baseVisited);
                visited.add(loc); // 标记转角自身，防止重入
                CachedPath altPath = findDestination(adjLoc, adjPipeData.facing(), visited, new ArrayList<>());
                if (altPath.destination() == null) continue;

                Block destBlock = altPath.destination().getBlock();
                ContainerAdapter destAdapter = ContainerAdapterRegistry.findAdapter(destBlock).orElse(null);
                if (destAdapter == null) continue;
                ItemStack leftover = destAdapter.insert(destBlock, item);
                if (leftover == null || leftover.getAmount() <= 0) return true;
            }
        }
        return false;
    }

    /**
     * 优先级分流：主目标满时，尝试链条末端管道周围其他相邻容器。
     * <p>
     * 按 NORTH → SOUTH → EAST → WEST → UP → DOWN 固定顺序依次尝试，
     * 找到第一个能接收的容器即插入（优先级由方向顺序决定）。
     *
     * @param lastPipeLoc 链条末端管道的位置
     * @param primaryDest 主目标位置（已满，跳过）
     * @param item        待传输的物品
     * @return 是否成功插入到某个备用容器
     */
    private boolean tryAlternativeDestination(Location lastPipeLoc, Location primaryDest, ItemStack item) {
        Block lastPipeBlock = lastPipeLoc.getBlock();
        PipeData lastPipeData = getPipeData(lastPipeLoc);
        // 链条来向（反方向），不向源头插入
        BlockFace backFace = lastPipeData != null ? lastPipeData.facing().getOppositeFace() : null;

        Location normalizedPrimary = normalizeLocation(primaryDest);
        for (BlockFace face : new BlockFace[]{
                BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
                BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
            // 跳过已尝试的主目标方向
            if (face == backFace) continue;

            Block adjacent = lastPipeBlock.getRelative(face);
            Location adjLoc = normalizeLocation(adjacent.getLocation());

            // 跳过主目标（已满）
            if (adjLoc.equals(normalizedPrimary)) continue;
            // 跳过管道方块（不递归进入链条）
            if (getPipeData(adjLoc) != null) continue;

            ContainerAdapter adapter = ContainerAdapterRegistry.findAdapter(adjacent).orElse(null);
            if (adapter == null || !adapter.canReceive(adjacent)) continue;

            ItemStack leftover = adapter.insert(adjacent, item);
            if (leftover == null || leftover.getAmount() <= 0) {
                return true;
            }
        }
        return false;
    }

    private CachedPath getOrBuildPath(Location pipeLocation, BlockFace facing) {
        Location key = normalizeLocation(pipeLocation);

        if (dirtyPaths.remove(key)) {
            pathCache.remove(key);
        }

        CachedPath cached = pathCache.get(key);
        if (cached != null) {
            if (isPathStillValid(cached)) {
                return cached; // 缓存命中
            }
        }

        CachedPath fresh = findDestination(pipeLocation, facing, new HashSet<>(), new ArrayList<>());
        pathCache.put(key, fresh);
        return fresh;
    }

    private boolean isPathStillValid(CachedPath path) {
        if (path.destination() == null) {
            return true;
        }

        Block destBlock = path.destination().getBlock();
        ContainerAdapter destAdapter = ContainerAdapterRegistry.findAdapter(destBlock).orElse(null);
        if (destAdapter == null || !destAdapter.canReceive(destBlock)) {
            return false;
        }

        List<Location> chain = path.pipeChain();
        for (int i = 0; i < chain.size() - 1; i++) {
            PipeData pipeData = getPipeData(chain.get(i));
            if (pipeData == null) continue; // 管道已被移除，pathCache.clear() 会处理
            Block nextBlock = chain.get(i).getBlock().getRelative(pipeData.facing());
            ContainerAdapter midAdapter = ContainerAdapterRegistry.findAdapter(nextBlock).orElse(null);
            if (midAdapter != null && midAdapter.canReceive(nextBlock)) {
                return false; // 链条中途出现了新容器，路径需要重建
            }
        }

        return true;
    }

    private int calcMinItems(List<Location> pipeChain, int startingMax) {
        int min = startingMax;
        for (Location loc : pipeChain) {
            PipeData d = getPipeData(loc);
            if (d != null) min = Math.min(min, d.variant().getItemsPerTransfer());
        }
        return min;
    }

    private CachedPath findDestination(Location pipeLocation, BlockFace facing,
                                       Set<Location> visited, List<Location> chain) {
        chain.add(normalizeLocation(pipeLocation));

        Block nextBlock = pipeLocation.getBlock().getRelative(facing);
        Location nextLoc = normalizeLocation(nextBlock.getLocation());

        if (visited.contains(nextLoc)) {
            return new CachedPath(null, pipeLocation, chain);
        }
        visited.add(nextLoc);

        ContainerAdapter destAdapter = ContainerAdapterRegistry.findAdapter(nextBlock).orElse(null);
        if (destAdapter != null && destAdapter.canReceive(nextBlock)) {
            return new CachedPath(nextLoc, pipeLocation, chain);
        }

        PipeData nextPipeData = getPipeData(nextLoc);
        if (nextPipeData != null) {
            // If the next pipe is facing INTO this pipe (head-to-head), drop the item
            if (nextPipeData.facing() == facing.getOppositeFace()) {
                return new CachedPath(null, pipeLocation, chain);
            }
            // Otherwise, follow the next pipe's direction (same direction, perpendicular, etc.)
            return findDestination(nextLoc, nextPipeData.facing(), visited, chain);
        }

        return new CachedPath(null, pipeLocation, chain);
    }

    public void shutdown() {
        stopTasks();
        pipes.clear();
        lastTransferTime.clear();
        pathCache.clear();
        dirtyPaths.clear();
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

            Set<String> tags = entity.getScoreboardTags();
            if (!PipeTags.isPipeEntity(tags)) continue;

            String pipeTag = PipeTags.getPipeTag(tags);
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

            Set<String> tags = entity.getScoreboardTags();
            if (!PipeTags.isPipeEntity(tags)) continue;

            String pipeTag = PipeTags.getPipeTag(tags);
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

    public void restartTasks() {
        stopTasks();
        startTasks();
    }

    private void stopTasks() {
        world.removeCyclicalTask("pipes_transfer");
        world.removeCyclicalTask("pipes_particles");
    }

    private Location normalizeLocation(Location location) {
        return new Location(location.getWorld(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ());
    }

    public void scanForExistingPipes() {
        int count = 0;

        for (Chunk chunk : world.getLoadedChunks()) {
            count += scanChunk(chunk);
        }
    }

    public int scanChunk(Chunk chunk) {
        if (!chunk.isLoaded()) {
            return 0;
        }
        if (chunk.getWorld() != world) {
            throw new IllegalArgumentException("Chunk world does not match PipeManager world");
        }

        int count = 0;
        VariantRegistry registry = plugin.getVariantRegistry();

        // Group entities by location to handle multiple entities per pipe
        Map<Location, List<UUID>> entityGroups = new HashMap<>();
        Map<Location, BlockFace> facingByLocation = new HashMap<>();
        Map<Location, PipeVariant> variantByLocation = new HashMap<>();

        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof ItemDisplay)) continue;

            String pipeTag = PipeTags.getPipeTag(entity.getScoreboardTags());
            if (pipeTag == null) continue;

            Location location = PipeTags.parseLocation(pipeTag, world);
            BlockFace facing = PipeTags.parseFacing(pipeTag);
            String variantId = PipeTags.parseVariantId(pipeTag);

            if (location == null || facing == null || variantId == null) continue;

            PipeVariant variant = registry.getVariant(variantId);
            if (variant == null) {
                continue;
            }

            Location normalized = normalizeLocation(location);

            // Verify the pipe block still exists
            Block block = location.getBlock();
            if (block.getType() == Material.PLAYER_HEAD || block.getType() == Material.PLAYER_WALL_HEAD) {
                // Group entities by location
                entityGroups.computeIfAbsent(normalized, k -> new ArrayList<>()).add(entity.getUniqueId());
                // Only read pipe facing from the main (non-directional) entity to avoid
                // secondary directional entities overwriting the correct pipe facing.
                if (!PipeTags.isDirectionalTag(pipeTag)) {
                    facingByLocation.put(normalized, facing);
                    variantByLocation.put(normalized, variant);
                } else {
                    // Still record variant in case main entity hasn't been seen yet
                    variantByLocation.putIfAbsent(normalized, variant);
                }
            } else {
                // Orphaned display entity - pipe block was removed while chunk was unloaded
                entity.remove();
            }
        }

        // Register all grouped pipes
        for (Map.Entry<Location, List<UUID>> entry : entityGroups.entrySet()) {
            Location location = entry.getKey();
            if (!isPipe(location)) {
                List<UUID> uuids = entry.getValue();
                BlockFace facing = facingByLocation.get(location);
                PipeVariant variant = variantByLocation.get(location);
                registerPipe(location, facing, uuids, variant);
                count++;
            }
        }

        return count;
    }

    public void unloadPipesInChunk(Chunk chunk) {
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        if (chunk.getWorld() != world) {
            throw new IllegalArgumentException("Chunk world does not match PipeManager world");
        }

        pipes.entrySet().removeIf(entry -> {
            Location loc = entry.getKey();
            if (!world.equals(loc.getWorld())) return false;

            int locChunkX = loc.getBlockX() >> 4;
            int locChunkZ = loc.getBlockZ() >> 4;

            if (locChunkX == chunkX && locChunkZ == chunkZ) {
                lastTransferTime.remove(loc);
                dirtyPaths.remove(loc);
                pathCache.clear();
                return true;
            }
            return false;
        });
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
