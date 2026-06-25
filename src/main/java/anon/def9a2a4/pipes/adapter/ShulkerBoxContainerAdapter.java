package anon.def9a2a4.pipes.adapter;

import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * 潜影盒容器适配器。
 * <p>
 * 行为基本沿用普通原版容器，但拒绝把任意种类的潜影盒物品放入潜影盒内。
 */
public final class ShulkerBoxContainerAdapter implements ContainerAdapter {

    private final VanillaContainerAdapter vanilla = new VanillaContainerAdapter();

    @Override
    public boolean canHandle(Block block) {
        return Tag.SHULKER_BOXES.isTagged(block.getType());
    }

    @Override
    public boolean hasItems(Block block) {
        return vanilla.hasItems(block);
    }

    @Override
    public @Nullable ItemStack peekExtract(Block block, int maxAmount) {
        return vanilla.peekExtract(block, maxAmount);
    }

    @Override
    public @Nullable ItemStack peekExtract(Block block, int maxAmount, Predicate<ItemStack> filter) {
        return vanilla.peekExtract(block, maxAmount, filter);
    }

    @Override
    public void commitExtract(Block block, ItemStack extracted) {
        vanilla.commitExtract(block, extracted);
    }

    @Override
    public boolean canReceive(Block block) {
        return canHandle(block);
    }

    @Override
    public boolean canReceive(Block block, ItemStack item) {
        return canReceive(block) && !Tag.ITEMS_SHULKER_BOXES.isTagged(item.getType());
    }

    @Override
    public @Nullable ItemStack insert(Block block, ItemStack item) {
        if (!canReceive(block, item)) {
            return item.clone();
        }
        return vanilla.insert(block, item);
    }
}
