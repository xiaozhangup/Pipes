package anon.def9a2a4.pipes;

import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;

/**
 * 内置原版容器适配器。
 * <p>
 * 处理所有实现了 {@link org.bukkit.block.Container} 接口的原版方块
 * （箱子、漏斗、熔炉、潜影箱、发射器等）。
 * 由 {@link ContainerAdapterRegistry} 作为 fallback 自动使用，无需手动注册。
 */
final class VanillaContainerAdapter implements ContainerAdapter {

    @Override
    public boolean canHandle(Block block) {
        return block.getState() instanceof Container;
    }

    @Override
    public boolean hasItems(Block block) {
        if (!(block.getState() instanceof Container container)) return false;
        Inventory inv = container.getInventory();
        for (ItemStack item : inv.getContents()) {
            if (item != null && !item.getType().isAir()) return true;
        }
        return false;
    }

    @Override
    public @Nullable ItemStack peekExtract(Block block, int maxAmount) {
        if (!(block.getState() instanceof Container container)) return null;
        Inventory inv = container.getInventory();
        for (ItemStack item : inv.getContents()) {
            if (item != null && !item.getType().isAir()) {
                ItemStack result = item.clone();
                result.setAmount(Math.min(maxAmount, item.getAmount()));
                return result;
            }
        }
        return null;
    }

    @Override
    public void commitExtract(Block block, ItemStack extracted) {
        // 重新获取最新方块状态（保证数据最新），找到与 extracted 匹配的第一个 slot 并扣除
        if (!(block.getState() instanceof Container container)) return;
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
        // 与原版行为一致：只要是 Container 就视为有效目的地，
        // 实际空间检测在 insert() 时通过 leftover 判断
        return block.getState() instanceof Container;
    }

    @Override
    public @Nullable ItemStack insert(Block block, ItemStack item) {
        if (!(block.getState() instanceof Container container)) return item;
        HashMap<Integer, ItemStack> leftover = container.getInventory().addItem(item.clone());
        if (leftover.isEmpty()) return null;
        return leftover.get(0);
    }
}
