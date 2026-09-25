package anon.def9a2a4.pipes.adapter;

import anon.def9a2a4.pipes.ContainerAdapterRegistry;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;

/**
 * 内置原版容器适配器。
 * <p>
 * 处理所有实现了 {@link org.bukkit.block.Container} 接口的原版方块
 * （箱子、漏斗、熔炉、潜影箱、发射器等）。
 * 由 {@link ContainerAdapterRegistry} 作为 fallback 自动使用，无需手动注册。
 */
public final class VanillaContainerAdapter implements ContainerAdapter {

    @Override
    public boolean canHandle(Block block) {
        return ContainerAdapterRegistry.isVanillaContainer(block);
    }

    @Override
    public boolean hasItems(Block block) {
        if (!(block.getState(false) instanceof Container container)) return false;
        Inventory inv = container.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (item != null && !item.getType().isAir()) return true;
        }
        return false;
    }

    @Override
    public @Nullable ItemStack peekExtract(Block block, int maxAmount) {
        if (!(block.getState(false) instanceof Container container)) return null;
        Inventory inv = container.getInventory();
        ItemStack template = null;
        int collected = 0;
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
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
    public @Nullable ItemStack peekExtract(Block block, int maxAmount, Predicate<ItemStack> filter) {
        if (!(block.getState(false) instanceof Container container)) return null;
        Inventory inv = container.getInventory();
        ItemStack template = null;
        int collected = 0;
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            if (template == null) {
                ItemStack candidate = item.clone();
                candidate.setAmount(Math.min(maxAmount, item.getAmount()));
                if (!filter.test(candidate)) continue;
                template = item.clone();
                collected = candidate.getAmount();
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
    public Extraction previewExtract(Block block, int maxAmount, List<ItemStack> requested,
                                     Predicate<ItemStack> filter) {
        if (!(block.getState(false) instanceof Container container)) return new Extraction(null, null);
        return Extraction.fromInventory(container.getInventory(), slot -> true, maxAmount, requested, filter);
    }

    @Override
    public void commitExtract(Block block, ItemStack extracted) {
        // 重新获取最新方块状态（保证数据最新），找到与 extracted 匹配的第一个 slot 并扣除
        if (!(block.getState(false) instanceof Container container)) return;
        Inventory inv = container.getInventory();
        int toRemove = extracted.getAmount();
        for (int i = 0; i < inv.getSize() && toRemove > 0; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            if (!item.isSimilar(extracted)) continue;

            int available = item.getAmount();
            if (available <= toRemove) {
                inv.setItem(i, null);
                toRemove -= available;
            } else {
                item.setAmount(available - toRemove);
                toRemove = 0;
            }
        }
    }

    @Override
    public boolean canReceive(Block block) {
        return canHandle(block);
    }

    @Override
    public @Nullable ItemStack insert(Block block, ItemStack item) {
        if (!(block.getState(false) instanceof Container container)) return item;
        HashMap<Integer, ItemStack> leftover = container.getInventory().addItem(item.clone());
        if (leftover.isEmpty()) return null;
        return leftover.get(0);
    }
}
