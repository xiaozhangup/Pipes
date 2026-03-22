package anon.def9a2a4.pipes;

import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 自定义容器适配器接口。
 * <p>
 * 实现此接口并通过 {@link ContainerAdapterRegistry#register(ContainerAdapter)} 注册，
 * 即可让管道系统识别和操作任意自定义容器方块，而无需实现原版 {@code org.bukkit.block.Container}。
 * <p>
 * 调用顺序（每次传输）：
 * <ol>
 *   <li>source 侧：{@link #hasItems(Block)} → {@link #peekExtractMatching(Block, int, ItemStack)}
 *       （若目的地声明了 {@link #requestedItem}，则使用过滤提取；否则 {@link #peekExtract(Block, int)}）</li>
 *   <li>dest 侧：{@link #canReceive(Block)} → {@link #insert(Block, ItemStack)}</li>
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
    boolean hasItems(Block block);

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
     * 提交提取操作，从容器中实际移除物品。
     * <p>
     * 仅在 {@link #insert} 成功后调用，保证物品不会凭空消失。
     *
     * @param block     源方块
     * @param extracted 与 {@link #peekExtract} 返回值数量一致的物品
     */
    void commitExtract(Block block, ItemStack extracted);

    /**
     * 预览与指定过滤器匹配的可提取物品，但不实际移除。
     * <p>
     * 当目的地通过 {@link #requestedItem} 声明了需求时，管道系统将调用此方法以针对性地提取物品。
     * 默认实现：调用 {@link #peekExtract}，若返回物品与 {@code filter} 不匹配则返回 {@code null}。
     * 实现类可覆盖此方法以扫描整个清单，找到第一个匹配的物品。
     *
     * @param block     源方块
     * @param maxAmount 本次允许提取的最大数量
     * @param filter    目的地期望的物品类型（通过 {@link ItemStack#isSimilar} 判断匹配）
     * @return 将被提取的物品副本；若无匹配物品则返回 {@code null}
     */
    @Nullable
    default ItemStack peekExtractMatching(Block block, int maxAmount, ItemStack filter) {
        ItemStack item = peekExtract(block, maxAmount);
        if (item == null) return null;
        return item.isSimilar(filter) ? item : null;
    }

    /**
     * 返回该容器当前期望接收的物品类型，供管道系统针对性地从源容器提取对应物品。
     * <p>
     * 默认返回 {@code null}，表示该容器不声明需求，管道将以默认方式（提取源中任意物品）传输。
     * 若返回非 {@code null}，管道将仅从源容器中提取与返回值 {@link ItemStack#isSimilar} 匹配的物品。
     * 当源容器中没有匹配物品时，本次传输跳过（不进入空容器休眠状态）。
     *
     * @param block 目标方块
     * @return 期望接收的物品（数量字段仅作参考）；不声明需求时返回 {@code null}
     */
    @Nullable
    default ItemStack requestedItem(Block block) {
        return null;
    }

    /**
     * 快速判断该方块当前是否能接受物品。
     * <p>
     * 用于 {@code findDestination} 路径寻找时判断终点是否有效。
     *
     * @param block 目标方块
     * @return 是否接受物品
     */
    boolean canReceive(Block block);

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
