package anon.def9a2a4.pipes.adapter;

import anon.def9a2a4.pipes.ContainerAdapterRegistry;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * 自定义容器适配器接口。
 * <p>
 * 实现此接口并通过 {@link ContainerAdapterRegistry#register(ContainerAdapter)} 注册，
 * 即可让管道系统识别和操作任意自定义容器方块，而无需实现原版 {@code org.bukkit.block.Container}。
 * <p>
 * 调用顺序（每次传输）：
 * <ol>
 *   <li>source 侧：{@link #hasItems(Block)} → {@link #peekExtract(Block, int, Predicate)}</li>
 *   <li>dest 侧：{@link #canReceive(Block, ItemStack)} → {@link #insert(Block, ItemStack)}</li>
 *   <li>存入成功后：{@link #commitExtract(Block, ItemStack)}</li>
 * </ol>
 */
public interface ContainerAdapter {

    /**
     * 判断此适配器是否能处理该方块。
     * <p>
     * 每次传输前调用一次，若返回 {@code true} 则使用此适配器，不再 fallback 到内置逻辑。
     *
     * @param block 被检测的方块
     * @return 是否由此适配器负责处理
     */
    boolean canHandle(Block block);

    /**
     * 快速判断该方块当前是否有可提取的物品。
     * <p>
     * 用于在 source 侧跳过明显为空的容器，避免不必要的开销。
     *
     * @param block 源方块
     * @return 是否存在可提取物品
     */
    default boolean hasItems(Block block) {
        return peekExtract(block, 1) != null;
    }

    /**
     * 预览可提取的物品，但不实际移除。
     * <p>
     * 仅在传输开始、确定源容器后调用。实际移除请等待 {@link #commitExtract} 被调用。
     *
     * @param block     源方块
     * @param maxAmount 本次允许提取的最大数量（由管道传输量限制决定）
     * @return 将被提取的物品副本；若无可提取物品则返回 {@code null}
     */
    @Nullable ItemStack peekExtract(Block block, int maxAmount);

    /**
     * 预览符合过滤条件的可提取物品，但不实际移除。
     * <p>
     * 默认实现只检查 {@link #peekExtract(Block, int)} 返回的第一个候选；
     * 普通清单类适配器可覆盖此方法以扫描整个清单，跳过不符合过滤条件的候选。
     *
     * @param block     源方块
     * @param maxAmount 本次允许提取的最大数量
     * @param filter    候选物品过滤条件
     * @return 将被提取的物品副本；若无符合条件的物品则返回 {@code null}
     */
    @Nullable
    default ItemStack peekExtract(Block block, int maxAmount, Predicate<ItemStack> filter) {
        ItemStack item = peekExtract(block, maxAmount);
        if (item == null) return null;
        return filter.test(item) ? item : null;
    }

    /**
     * 提交提取操作，从容器中实际移除物品。
     * <p>
     * 仅在 {@link #insert} 成功后调用，保证物品不会凭空消失。
     *
     * @param block     源方块
     * @param extracted 与 {@link #peekExtract} 返回值数量一致的物品
     */
    void commitExtract(Block block, ItemStack extracted);

    /**
     * 返回该容器当前期望接收的物品类型列表，供管道系统按顺序从源容器提取对应物品。
     * <p>
     * 默认返回空列表，表示该容器不声明需求，管道将以默认方式（提取源中任意物品）传输。
     * 若返回非空列表，管道将按顺序尝试从源容器中提取与候选值 {@link ItemStack#isSimilar} 匹配的物品。
     * 当源容器中没有任何匹配物品时，本次传输跳过（不进入空容器休眠状态）。
     *
     * @param block 目标方块
     * @return 期望接收的物品列表（数量字段仅作参考）；不声明需求时返回空列表
     */
    default List<ItemStack> requestedItems(Block block) {
        return List.of();
    }

    /**
     * 快速判断该方块当前是否存在任意可接收空间。
     * <p>
     * 用于 {@code findDestination} 路径寻找时判断终点是否有效；此时尚无具体物品上下文。
     *
     * @param block 目标方块
     * @return 是否能接收任意物品
     */
    boolean canReceive(Block block);

    /**
     * 判断该方块当前是否能接受指定物品。
     * <p>
     * 默认沿用 {@link #canReceive(Block)}，只关心容器是否能作为目标；
     * 需要拒收特定物品的适配器可覆盖此方法。
     *
     * @param block 目标方块
     * @param item  待存入物品
     * @return 是否接受该物品
     */
    default boolean canReceive(Block block, ItemStack item) {
        return canReceive(block);
    }

    /**
     * 尝试将物品存入容器。
     * <p>
     * 应尽量存入所有物品；若空间不足，返回未能存入的剩余部分。
     *
     * @param block 目标方块
     * @param item  要存入的物品（不要修改原始引用，应操作副本）
     * @return 未能存入的剩余物品；完全存入时返回 {@code null} 或 amount &le; 0 的物品
     */
    @Nullable ItemStack insert(Block block, ItemStack item);
}
