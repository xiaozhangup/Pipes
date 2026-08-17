package anon.def9a2a4.pipes;

import anon.def9a2a4.pipes.adapter.ContainerAdapter;
import anon.def9a2a4.pipes.adapter.BrewingStandContainerAdapter;
import anon.def9a2a4.pipes.adapter.CrafterContainerAdapter;
import anon.def9a2a4.pipes.adapter.FurnaceContainerAdapter;
import anon.def9a2a4.pipes.adapter.ShulkerBoxContainerAdapter;
import anon.def9a2a4.pipes.adapter.VanillaContainerAdapter;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Container;
import org.bukkit.block.Crafter;
import org.bukkit.block.Furnace;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 管道容器适配器注册表。
 * <p>
 * 外部插件可通过此类注册自定义容器适配器，让管道系统识别非原版 Inventory 的容器方块：
 * <pre>{@code
 * ContainerAdapterRegistry.register(new MyCustomContainerAdapter());
 * }</pre>
 * <p>
 * 查找规则：先按注册顺序匹配自定义适配器，均不匹配时 fallback 到内置原版容器适配器。
 */
public final class ContainerAdapterRegistry {

    /** 内置原版容器适配器，始终作为 fallback。 */
    private static final ContainerAdapter SHULKER_BOX = new ShulkerBoxContainerAdapter();
    private static final ContainerAdapter VANILLA_CONTAINER = new VanillaContainerAdapter();
    private static final Map<Material, ContainerAdapter> VANILLA = createVanillaAdapters();

    /** 已注册的自定义适配器列表（越早注册优先级越高）。 */
    private static final List<ContainerAdapter> adapters = new ArrayList<>();

    private ContainerAdapterRegistry() {}

    /**
     * 注册一个自定义容器适配器。
     * <p>
     * 注册顺序即优先级：先注册的优先匹配。自定义适配器始终优先于内置原版适配器。
     *
     * @param adapter 要注册的适配器，不能为 {@code null}
     */
    public static void register(ContainerAdapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("adapter cannot be null");
        adapters.add(adapter);
    }

    /**
     * 注销一个已注册的适配器。
     *
     * @param adapter 要注销的适配器
     */
    public static void unregister(ContainerAdapter adapter) {
        adapters.remove(adapter);
    }

    /**
     * 查找能处理该方块的适配器。
     * <p>
     * 供 {@link PipeManager} 内部使用。先查自定义适配器，再 fallback 到原版适配器，
     * 若原版也不支持则返回 {@link Optional#empty()}。
     *
     * @param block 被检测的方块
     * @return 能处理该方块的适配器，可能为空
     */
    public static Optional<ContainerAdapter> findAdapter(Block block) {
        for (ContainerAdapter adapter : adapters) {
            if (adapter.canHandle(block)) {
                return Optional.of(adapter);
            }
        }
        Material material = block.getType();
        ContainerAdapter adapter = VANILLA.get(material);
        if (adapter == VANILLA_CONTAINER && Tag.SHULKER_BOXES.isTagged(material)) {
            adapter = SHULKER_BOX;
        }
        return Optional.ofNullable(adapter);
    }

    private static Map<Material, ContainerAdapter> createVanillaAdapters() {
        ContainerAdapter furnace = new FurnaceContainerAdapter();
        ContainerAdapter brewingStand = new BrewingStandContainerAdapter();
        ContainerAdapter crafter = new CrafterContainerAdapter();
        Map<Material, ContainerAdapter> adapters = new EnumMap<>(Material.class);
        for (Material material : Material.values()) {
            if (!material.isBlock()) continue;
            if (!(material.createBlockData().createBlockState() instanceof Container state)) continue;

            ContainerAdapter adapter = state instanceof Furnace ? furnace
                    : state instanceof BrewingStand ? brewingStand
                    : state instanceof Crafter ? crafter
                    : VANILLA_CONTAINER;
            adapters.put(material, adapter);
        }
        return adapters;
    }
}
