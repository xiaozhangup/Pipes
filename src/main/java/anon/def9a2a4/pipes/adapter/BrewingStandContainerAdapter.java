package anon.def9a2a4.pipes.adapter;

import anon.def9a2a4.pipes.ContainerAdapter;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 酿造台容器适配器。
 * <p>
 * 与原版漏斗行为保持一致：
 * <ul>
 *   <li>提取：仅从药水瓶格（slot 0-2）取出物品，且酿造进行中时不提取；不提取原料格或燃料格。</li>
 *   <li>存入：依次尝试原料格（slot 3）、燃料格（slot 4）、药水瓶格（slot 0-2）。</li>
 * </ul>
 *
 * <p>存入优先级：原料格（slot 3）→ 燃料格（slot 4）→ 药水瓶格（slot 0-2）。
 */
public class BrewingStandContainerAdapter implements ContainerAdapter {

    /** 酿造台药水瓶格数量（slot 0、1、2）。 */
    private static final int BOTTLE_SLOTS = 3;
    /** 原料格 index。 */
    private static final int INGREDIENT_SLOT = 3;
    /** 燃料格 index。 */
    private static final int FUEL_SLOT = 4;

    @Override
    public boolean canHandle(Block block) {
        return block.getState() instanceof BrewingStand;
    }

    @Override
    public boolean hasItems(Block block) {
        if (!(block.getState() instanceof BrewingStand stand)) return false;
        // 正在酿造时不提取药水瓶格中的物品
        if (stand.getBrewingTime() > 0) return false;
        BrewerInventory inv = stand.getInventory();
        for (int i = 0; i < BOTTLE_SLOTS; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && !item.getType().isAir()) return true;
        }
        return false;
    }

    @Override
    public @Nullable ItemStack peekExtract(Block block, int maxAmount) {
        if (!(block.getState() instanceof BrewingStand stand)) return null;
        // 正在酿造时不提取药水瓶格中的物品
        if (stand.getBrewingTime() > 0) return null;
        BrewerInventory inv = stand.getInventory();
        for (int i = 0; i < BOTTLE_SLOTS; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && !item.getType().isAir()) {
                ItemStack copy = item.clone();
                copy.setAmount(Math.min(maxAmount, item.getAmount()));
                return copy;
            }
        }
        return null;
    }

    @Override
    public void commitExtract(Block block, ItemStack extracted) {
        if (!(block.getState() instanceof BrewingStand stand)) return;
        BrewerInventory inv = stand.getInventory();
        int toRemove = extracted.getAmount();
        for (int i = 0; i < BOTTLE_SLOTS && toRemove > 0; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            if (!item.isSimilar(extracted)) continue;
            int available = item.getAmount();
            if (available <= toRemove) {
                inv.setItem(i, null);
                toRemove -= available;
            } else {
                item.setAmount(available - toRemove);
                inv.setItem(i, item);
                toRemove = 0;
            }
        }
    }

    @Override
    public boolean canReceive(Block block) {
        if (!(block.getState() instanceof BrewingStand stand)) return false;
        BrewerInventory inv = stand.getInventory();
        // 任一格子（原料格、燃料格或药水瓶格）有空位即可接受
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack existing = inv.getItem(i);
            if (existing == null || existing.getType().isAir()
                    || existing.getAmount() < existing.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public @Nullable ItemStack insert(Block block, ItemStack item) {
        if (!(block.getState() instanceof BrewingStand stand)) return item;
        BrewerInventory inv = stand.getInventory();

        // 先尝试原料格（slot 3）
        ItemStack leftover = tryInsertSlot(inv, INGREDIENT_SLOT, item);
        if (leftover == null || leftover.getAmount() <= 0) return null;

        // 再尝试燃料格（slot 4）
        leftover = tryInsertSlot(inv, FUEL_SLOT, leftover);
        if (leftover == null || leftover.getAmount() <= 0) return null;

        // 最后尝试药水瓶格（slot 0-2），用于存入水瓶或药水
        for (int i = 0; i < BOTTLE_SLOTS; i++) {
            leftover = tryInsertSlot(inv, i, leftover);
            if (leftover == null || leftover.getAmount() <= 0) return null;
        }

        return leftover;
    }

    /**
     * 尝试将 {@code item} 存入清单 {@code inv} 的指定格子。
     *
     * @return 未能存入的剩余物品；完全存入时返回 {@code null}
     */
    private static @Nullable ItemStack tryInsertSlot(BrewerInventory inv, int slot, ItemStack item) {
        ItemStack existing = inv.getItem(slot);
        if (existing == null || existing.getType().isAir()) {
            inv.setItem(slot, item.clone());
            return null;
        }
        if (existing.isSimilar(item)) {
            int space = existing.getMaxStackSize() - existing.getAmount();
            if (space <= 0) return item;
            int toAdd = Math.min(space, item.getAmount());
            existing.setAmount(existing.getAmount() + toAdd);
            inv.setItem(slot, existing);
            int leftoverAmount = item.getAmount() - toAdd;
            if (leftoverAmount <= 0) return null;
            ItemStack leftover = item.clone();
            leftover.setAmount(leftoverAmount);
            return leftover;
        }
        return item;
    }
}
