package anon.def9a2a4.pipes.adapter;

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
 *   <li>存入：先向原料格（slot 0）存入物品；若原料格无法接受，再尝试向燃料格（slot 1）补充——
 *       但燃料格仅在已有物品时才会接受（不主动放入空燃料格）。</li>
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

    /**
     * 若熔炉原料格已有物品，则声明需要更多同类物品，以便管道针对性地从源容器提取；
     * 若燃料格已有物品，则以原料格需求优先，原料格为空时再声明需要燃料格同类物品。
     * 原料格为空时无法判断需要何种原料，返回 {@code null} 以使用默认传输行为。
     */
    @Override
    public @Nullable ItemStack requestedItem(Block block) {
        if (!(block.getState() instanceof Furnace furnace)) return null;
        FurnaceInventory inv = furnace.getInventory();
        ItemStack smelting = inv.getSmelting();
        if (smelting != null && !smelting.getType().isAir() && smelting.getAmount() < smelting.getMaxStackSize()) {
            return smelting.clone();
        }
        ItemStack fuel = inv.getFuel();
        if (fuel != null && !fuel.getType().isAir() && fuel.getAmount() < fuel.getMaxStackSize()) {
            return fuel.clone();
        }
        return null;
    }

    @Override
    public boolean canReceive(Block block) {
        if (!(block.getState() instanceof Furnace furnace)) return false;
        FurnaceInventory inv = furnace.getInventory();
        ItemStack smelting = inv.getSmelting();
        // 原料格有空位可接受
        if (smelting == null || smelting.getType().isAir()
                || smelting.getAmount() < smelting.getMaxStackSize()) {
            return true;
        }
        // 燃料格仅在非空时可接受（不主动向空燃料格放物品）
        ItemStack fuel = inv.getFuel();
        return fuel != null && !fuel.getType().isAir()
                && fuel.getAmount() < fuel.getMaxStackSize();
    }

    @Override
    public @Nullable ItemStack insert(Block block, ItemStack item) {
        if (!(block.getState() instanceof Furnace furnace)) return item;
        FurnaceInventory inv = furnace.getInventory();

        // 先尝试原料格（slot 0）
        ItemStack smelting = inv.getSmelting();
        if (smelting == null || smelting.getType().isAir()) {
            inv.setSmelting(item.clone());
            return null;
        }
        if (smelting.isSimilar(item)) {
            item = tryFillSlotSimilar(inv, 0, smelting, item);
            if (item == null || item.getAmount() <= 0) return null;
        }

        // 燃料格（slot 1）：仅在燃料格已有物品时才尝试补充
        ItemStack fuel = inv.getFuel();
        if (fuel != null && !fuel.getType().isAir() && fuel.isSimilar(item)) {
            item = tryFillSlotSimilar(inv, 1, fuel, item);
            if (item == null || item.getAmount() <= 0) return null;
        }

        // 无法存入（原料格有不同物品，或燃料格为空/已满/类型不同）
        return item;
    }

    /**
     * 向清单中已有相同物品的格子补充堆叠，返回剩余（无法存入）的部分。
     *
     * @param inv      目标清单
     * @param slot     目标格子 index
     * @param existing 格子中已有的物品（需确保与 {@code incoming} 类型相同）
     * @param incoming 要存入的物品
     * @return 未能存入的剩余物品；完全存入时返回 {@code null}
     */
    private static @Nullable ItemStack tryFillSlotSimilar(
            FurnaceInventory inv, int slot, ItemStack existing, ItemStack incoming) {
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
}
