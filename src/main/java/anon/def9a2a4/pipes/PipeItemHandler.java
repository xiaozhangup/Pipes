package anon.def9a2a4.pipes;

import me.xiaozhangup.slimecargo.utils.flexible.FlexibleItemHandler;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NonNull;

import java.util.Optional;

public class PipeItemHandler implements FlexibleItemHandler {
    @Override
    public @NonNull String getNamespace() {
        return "pipes";
    }

    @Override
    public @NonNull Optional<ItemStack> getStack(@NonNull String s) {
        PipeVariant variant = PipesPlugin.getInstance().getVariantRegistry().getVariant(s);
        if (variant == null) return Optional.empty();

        ItemStack item = PipesPlugin.getInstance().getPipeItem(variant);
        return Optional.ofNullable(item);
    }

    @Override
    public @NonNull Optional<String> getName(@NonNull ItemStack itemStack) {
        PipeVariant variant = PipesPlugin.getInstance().getVariant(itemStack);
        if (variant == null) return Optional.empty();
        return Optional.of(variant.getId());
    }
}
