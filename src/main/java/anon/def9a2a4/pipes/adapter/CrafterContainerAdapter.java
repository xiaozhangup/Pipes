package anon.def9a2a4.pipes.adapter;

import org.bukkit.block.Block;
import org.bukkit.block.Crafter;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 合成器容器适配器。
 * <p>
 * 合成器允许玩家锁定 3x3 合成网格中的格子；管道传输时应跳过这些 disabled slot，
 * 只从可用格子提取，并且只向可用格子补充或放入物品。
 */
public class CrafterContainerAdapter implements ContainerAdapter {

    private static final int SLOT_COUNT = 9;

    @Override
    public boolean canHandle(Block block) {
        return block.getState() instanceof Crafter;
    }

    @Override
    public boolean hasItems(Block block) {
        if (!(block.getState() instanceof Crafter crafter)) return false;
        Inventory inv = crafter.getInventory();
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (crafter.isSlotDisabled(i)) continue;
            ItemStack item = inv.getItem(i);
            if (item != null && !item.getType().isAir()) return true;
        }
        return false;
    }

    @Override
    public @Nullable ItemStack peekExtract(Block block, int maxAmount) {
        if (!(block.getState() instanceof Crafter crafter)) return null;
        Inventory inv = crafter.getInventory();
        ItemStack template = null;
        int collected = 0;
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (crafter.isSlotDisabled(i)) continue;
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            if (template == null) {
                template = item.clone();
                collected = Math.min(maxAmount, item.getAmount());
            } else if (item.isSimilar(template)) {
                collected = Math.min(maxAmount, collected + item.getAmount());
            }
            if (collected >= maxAmount) break;
        }
        if (template == null) return null;
        template.setAmount(collected);
        return template;
    }

    @Override
    public @Nullable ItemStack peekExtractMatching(Block block, int maxAmount, ItemStack filter) {
        if (!(block.getState() instanceof Crafter crafter)) return null;
        Inventory inv = crafter.getInventory();
        int collected = 0;
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (crafter.isSlotDisabled(i)) continue;
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            if (!item.isSimilar(filter)) continue;
            collected = Math.min(maxAmount, collected + item.getAmount());
            if (collected >= maxAmount) break;
        }
        if (collected == 0) return null;
        ItemStack result = filter.clone();
        result.setAmount(collected);
        return result;
    }

    @Override
    public void commitExtract(Block block, ItemStack extracted) {
        if (!(block.getState() instanceof Crafter crafter)) return;
        Inventory inv = crafter.getInventory();
        int toRemove = extracted.getAmount();
        for (int i = 0; i < SLOT_COUNT && toRemove > 0; i++) {
            if (crafter.isSlotDisabled(i)) continue;
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

    /**
     * 若已有可用格子未堆满，则声明需要更多同类物品，让管道优先补齐现有合成材料。
     */
    @Override
    public List<ItemStack> requestedItems(Block block) {
        if (!(block.getState() instanceof Crafter crafter)) return List.of();
        Inventory inv = crafter.getInventory();
        List<ItemStack> requested = new ArrayList<>();
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (crafter.isSlotDisabled(i)) continue;
            ItemStack item = inv.getItem(i);
            if (item != null && !item.getType().isAir() && item.getAmount() < item.getMaxStackSize()) {
                addRequestedItem(requested, item);
            }
        }
        return requested;
    }

    @Override
    public boolean canReceive(Block block) {
        if (!(block.getState() instanceof Crafter crafter)) return false;
        Inventory inv = crafter.getInventory();
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (crafter.isSlotDisabled(i)) continue;
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir() || item.getAmount() < item.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public @Nullable ItemStack insert(Block block, ItemStack item) {
        if (!(block.getState() instanceof Crafter crafter)) return item;
        Inventory inv = crafter.getInventory();
        ItemStack leftover = item.clone();

        for (int i = 0; i < SLOT_COUNT; i++) {
            if (crafter.isSlotDisabled(i)) continue;
            ItemStack existing = inv.getItem(i);
            if (existing == null || existing.getType().isAir() || !existing.isSimilar(leftover)) continue;
            leftover = tryFillSlot(inv, i, existing, leftover);
            if (leftover == null || leftover.getAmount() <= 0) return null;
        }

        for (int i = 0; i < SLOT_COUNT; i++) {
            if (crafter.isSlotDisabled(i)) continue;
            ItemStack existing = inv.getItem(i);
            if (existing != null && !existing.getType().isAir()) continue;
            inv.setItem(i, leftover);
            return null;
        }

        return leftover;
    }

    private static @Nullable ItemStack tryFillSlot(
            Inventory inv, int slot, ItemStack existing, ItemStack incoming) {
        int space = existing.getMaxStackSize() - existing.getAmount();
        if (space <= 0) return incoming;
        int toAdd = Math.min(space, incoming.getAmount());
        existing.setAmount(existing.getAmount() + toAdd);
        inv.setItem(slot, existing);
        int leftoverAmount = incoming.getAmount() - toAdd;
        if (leftoverAmount <= 0) return null;
        ItemStack leftover = incoming.clone();
        leftover.setAmount(leftoverAmount);
        return leftover;
    }

    private static void addRequestedItem(List<ItemStack> requested, ItemStack item) {
        for (ItemStack existing : requested) {
            if (existing.isSimilar(item)) return;
        }
        requested.add(item.clone());
    }
}
