package anon.def9a2a4.pipes.adapter;

import anon.def9a2a4.pipes.ContainerAdapter;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 熔炉类容器适配器（熔炉、高炉、烟熏炉）。
 * <p>
 * 与原版漏斗行为保持一致：
 * <ul>
 *   <li>提取：仅从产物格（slot 2）取出物品。</li>
 *   <li>存入：仅向原料格（slot 0）存入物品，不操作燃料格或产物格。</li>
 * </ul>
 */
public class FurnaceContainerAdapter implements ContainerAdapter {

    @Override
    public boolean canHandle(Block block) {
        return block.getState() instanceof Furnace;
    }

    @Override
    public boolean hasItems(Block block) {
        if (!(block.getState() instanceof Furnace furnace)) return false;
        ItemStack result = furnace.getInventory().getResult();
        return result != null && !result.getType().isAir();
    }

    @Override
    public @Nullable ItemStack peekExtract(Block block, int maxAmount) {
        if (!(block.getState() instanceof Furnace furnace)) return null;
        ItemStack result = furnace.getInventory().getResult();
        if (result == null || result.getType().isAir()) return null;
        ItemStack copy = result.clone();
        copy.setAmount(Math.min(maxAmount, result.getAmount()));
        return copy;
    }

    @Override
    public void commitExtract(Block block, ItemStack extracted) {
        if (!(block.getState() instanceof Furnace furnace)) return;
        FurnaceInventory inv = furnace.getInventory();
        ItemStack result = inv.getResult();
        if (result == null || result.getType().isAir()) return;
        int remaining = result.getAmount() - extracted.getAmount();
        if (remaining <= 0) {
            inv.setResult(null);
        } else {
            result.setAmount(remaining);
            inv.setResult(result);
        }
    }

    @Override
    public boolean canReceive(Block block) {
        if (!(block.getState() instanceof Furnace furnace)) return false;
        ItemStack smelting = furnace.getInventory().getSmelting();
        // 原料格为空，或与待存入物品相同且未满（实际 item 类型在 insert 时判断）
        return smelting == null || smelting.getType().isAir()
                || smelting.getAmount() < smelting.getMaxStackSize();
    }

    @Override
    public @Nullable ItemStack insert(Block block, ItemStack item) {
        if (!(block.getState() instanceof Furnace furnace)) return item;
        FurnaceInventory inv = furnace.getInventory();
        ItemStack smelting = inv.getSmelting();

        if (smelting == null || smelting.getType().isAir()) {
            inv.setSmelting(item.clone());
            return null;
        }

        if (smelting.isSimilar(item)) {
            int space = smelting.getMaxStackSize() - smelting.getAmount();
            if (space <= 0) return item;
            int toAdd = Math.min(space, item.getAmount());
            smelting.setAmount(smelting.getAmount() + toAdd);
            inv.setSmelting(smelting);
            int leftoverAmount = item.getAmount() - toAdd;
            if (leftoverAmount <= 0) return null;
            ItemStack leftover = item.clone();
            leftover.setAmount(leftoverAmount);
            return leftover;
        }

        // 原料格已有不同物品，无法存入
        return item;
    }
}
