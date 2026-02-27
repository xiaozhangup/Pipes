package anon.def9a2a4.pipes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import org.bukkit.Chunk;

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

    private record DestinationResult(Location destination, Location lastPipeLocation, int minItemsPerTransfer) {}

    private final PipesPlugin plugin;
    private final int offset;
    private final World world;
    private final Random random = new Random();
    private final Map<Location, PipeData> pipes = new HashMap<>();
    private final Map<Location, Long> lastTransferTime = new HashMap<>();

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
                    if (offset + Bukkit.getServer().getCurrentTick() % fastestInterval == 0) {
                        this.transferAllPipes();
                    }
                }
        );

        if (plugin.getPipeConfig().isDebugParticles()) {
            int particleInterval = plugin.getPipeConfig().getParticleInterval();
            world.submitCyclicalTask(
                    "pipes_particles",
                    () -> {
                        if (offset + Bukkit.getServer().getCurrentTick() % particleInterval == 0) {
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
        pipes.put(normalizeLocation(location), new PipeData(facing, displayEntityIds, variant));
    }

    public void unregisterPipe(Location location) {
        Location normalized = normalizeLocation(location);
        PipeData data = pipes.remove(normalized);
        lastTransferTime.remove(normalized);

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

        // For corner pipes, we need to update both displays differently
        if (data.variant().getBehaviorType() == BehaviorType.CORNER) {
            updateCornerDisplayEntities(normalized, data);
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

    private void updateCornerDisplayEntities(Location normalized, PipeData data) {
        // Find entities by tag to determine which is directional
        Collection<Entity> nearby = world.getNearbyEntities(
                normalized.clone().add(0.5, 0.5, 0.5),
                1.0, 1.0, 1.0,
                e -> e instanceof ItemDisplay
        );

        for (Entity entity : nearby) {
            String pipeTag = PipeTags.getPipeTag(entity.getScoreboardTags());
            if (pipeTag != null && PipeTags.matchesLocation(pipeTag, normalized)) {
                ItemDisplay display = (ItemDisplay) entity;
                if (PipeTags.isDirectionalTag(pipeTag)) {
                    // Update directional display
                    Transformation transformation = calculateCornerDirectionalTransformation(normalized, data.facing());
                    display.setTransformation(transformation);
                } else {
                    // Update main (non-directional) display
                    Transformation transformation = calculateCornerTransformation();
                    display.setTransformation(transformation);
                }
            }
        }
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

        // For corner pipes, spawn a second directional display entity
        if (variant.getBehaviorType() == BehaviorType.CORNER) {
            // Use the corner variant's own directional display texture
            ItemStack directionalItem = plugin.getDirectionalDisplayItem(variant, facing);
            Transformation directionalTransformation = calculateCornerDirectionalTransformation(location, facing);

            ItemDisplay directionalDisplay = world.spawn(spawnLoc, ItemDisplay.class, entity -> {
                entity.setItemStack(directionalItem);
                entity.setPersistent(true);
                entity.addScoreboardTag(PipeTags.createDirectionalTag(location, facing, variant));
                entity.setTransformation(directionalTransformation);
            });
            displays.add(directionalDisplay);
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
                return "block"; // Corner pipe not feeding into us, treat as solid
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
        if (sourceBlock.getState() instanceof Container) return "container";
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
        if (destBlock.getState() instanceof Container) return "container";
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
                        new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 100, 50), 1.0f)
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
        if (!(sourceBlock.getState() instanceof Container sourceContainer)) {
            return false;
        }

        Inventory sourceInv = sourceContainer.getInventory();
        ItemStack toTransfer = null;
        int sourceSlot = -1;

        for (int i = 0; i < sourceInv.getSize(); i++) {
            ItemStack item = sourceInv.getItem(i);
            if (item != null && !item.getType().isAir()) {
                toTransfer = item.clone();
                sourceSlot = i;
                break;
            }
        }

        if (toTransfer == null) return false;

        // Start with this pipe's items per transfer and find minimum along path
        int startingMax = data.variant().getItemsPerTransfer();
        DestinationResult result = findDestination(pipeLocation, facing, new HashSet<>(), startingMax);

        // Use the minimum from the path
        int transferAmount = result.minItemsPerTransfer();
        toTransfer.setAmount(Math.min(transferAmount, toTransfer.getAmount()));

        boolean transferred = false;
        if (result.destination() == null) {
            // No container destination - drop at the last pipe in the chain
            Location lastPipeLoc = result.lastPipeLocation();
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
        } else {
            Block destBlock = result.destination().getBlock();
            if (destBlock.getState() instanceof Container destContainer) {
                HashMap<Integer, ItemStack> leftover = destContainer.getInventory().addItem(toTransfer);
                if (leftover.isEmpty()) {
                    transferred = true;
                }
            }
        }

        if (transferred) {
            ItemStack sourceItem = sourceInv.getItem(sourceSlot);
            if (sourceItem != null) {
                sourceItem.setAmount(sourceItem.getAmount() - toTransfer.getAmount());
                if (sourceItem.getAmount() <= 0) {
                    sourceInv.setItem(sourceSlot, null);
                }
            }
        }
        return false;
    }

    private DestinationResult findDestination(Location pipeLocation, BlockFace facing,
                                               Set<Location> visited, int currentMinItems) {
        Block nextBlock = pipeLocation.getBlock().getRelative(facing);
        Location nextLoc = normalizeLocation(nextBlock.getLocation());

        if (visited.contains(nextLoc)) {
            return new DestinationResult(null, pipeLocation, currentMinItems);
        }
        visited.add(nextLoc);

        if (nextBlock.getState() instanceof Container) {
            return new DestinationResult(nextLoc, pipeLocation, currentMinItems);
        }

        PipeData nextPipeData = getPipeData(nextLoc);
        if (nextPipeData != null) {
            // Calculate new minimum based on next pipe's settings
            int nextMin = Math.min(currentMinItems, nextPipeData.variant().getItemsPerTransfer());

            // If the next pipe is facing INTO this pipe (head-to-head), drop the item
            if (nextPipeData.facing() == facing.getOppositeFace()) {
                return new DestinationResult(null, pipeLocation, currentMinItems);
            }
            // Otherwise, follow the next pipe's direction (same direction, perpendicular, etc.)
            return findDestination(nextLoc, nextPipeData.facing(), visited, nextMin);
        }

        return new DestinationResult(null, pipeLocation, currentMinItems);
    }

    public void shutdown() {
        stopTasks();
        pipes.clear();
        lastTransferTime.clear();
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

        if (count > 0) {
            plugin.getLogger().info("Restored " + count + " pipes in world " + world.getName());
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
                plugin.getLogger().warning("Unknown variant '" + variantId + "' for pipe at " + location);
                continue;
            }

            Location normalized = normalizeLocation(location);

            // Verify the pipe block still exists
            Block block = location.getBlock();
            if (block.getType() == Material.PLAYER_HEAD || block.getType() == Material.PLAYER_WALL_HEAD) {
                // Group entities by location
                entityGroups.computeIfAbsent(normalized, k -> new ArrayList<>()).add(entity.getUniqueId());
                facingByLocation.put(normalized, facing);
                variantByLocation.put(normalized, variant);
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
                return true;
            }
            return false;
        });
    }

    public BlockFace getFacingFromSkull(Block block) {
        if (block.getType() == Material.PLAYER_WALL_HEAD) {
            org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) block.getBlockData();
            return directional.getFacing();
        } else if (block.getType() == Material.PLAYER_HEAD) {
            org.bukkit.block.data.Rotatable rotatable = (org.bukkit.block.data.Rotatable) block.getBlockData();
            return rotatable.getRotation();
        }
        return BlockFace.NORTH;
    }

    public record PipeData(BlockFace facing, List<UUID> displayEntityIds, PipeVariant variant) {}
}
