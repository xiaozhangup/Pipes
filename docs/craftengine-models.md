# 管道模型接入

CraftEngine 仅注册展示实体使用的模型物品、打包资源包，不注册背包物品、管道方块、家具或放置行为。背包、发放、放置、破坏、掉落、传输、氧化、涂蜡和持久化仍使用 Pipes 原有物品和逻辑，物理头颅方块载体保留。

## 资源

本机资源目录：`/home/xiaozhangup/文档/Minecraft/26.2/plugins/CraftEngine/resources/pipes`。

- `configuration/items.yml`：仅包含 192 个供展示实体使用的模型物品。
- `resourcepack/assets/pipes/models/boundary_aligned`：192 个展示模型 JSON。8 个原始物品模型保留在模型源目录，不导出到 CE。
- `resourcepack/assets/pipes/textures/boundary_aligned`：28 张贴图。
- `pack.yml`：独立资源包说明。

可放置物品由 Pipes 自行创建 `PLAYER_HEAD`，保留原有头颅皮肤、名称、描述和 PDC 标记；物品创建、识别及掉落均不调用 CE API，也不携带 CE 物品 ID 或模型组件。`/pipes give` 和原有物品接口继续提供这些物品。CE 仅提供 `pipes:display/*` 内部展示物品，使用 `paper` 材质，没有放置用途。

四种可见材质各有一个普通模型和 47 个转角基础模型；涂蜡铜复用铜。`pipe-models.json` 把普通管道六个朝向、转角 665 个有方向的输入／输出组合映射到基础模型和四元数。完整资产表仍保留每材质 1458 个区分主输出优先级的转角状态。输入面来自实际朝向本管道的相邻管道；输出面沿用现有主输出及可接收的侧向目标逻辑。

状态表描述模型坐标到世界坐标的旋转。Minecraft 的 ItemDisplay 渲染器在 `NONE` 模式下仍会额外绕本地 Y 轴旋转 180°，因此展示变换的右旋转固定为 `(0,1,0,0)` 抵消这一步，左旋转继续使用状态表。补偿必须放在本地坐标一侧，才能同时覆盖水平和竖直转角。

转角管道的侧向分支可以连接另一个转角，模型选择与传输共用 `canRouteIntoAdjacentPipe` 判断：不能从目标转角的主输出面反向进入，其他面均可接入。两个侧面可以双向传输时，两端都使用输出端外观；实际分流仍跳过当前路径的入流面，保留主输出优先级与循环限制。

分支寻路会检查首个转角和链条末端，按实际进入方向排除回流面。循环记录区分“转角位置＋入流面”，不再以整条分支的末端位置拦截其他转角。模型表示结构连接；主路优先、容器容量和物品过滤仍决定每次实际走哪一条路。

导出工具同时生成 CE 目录和插件状态表，避免两者不一致。需要 Python 3 与 PyYAML：

```sh
python3 scripts/export_craftengine.py /home/xiaozhangup/文档/Minecraft/26.2/plugins/CraftEngine/resources/pipes
```

## 刷新与迁移

CE 物品加载完成后才初始化展示资源并扫描世界。刷新时保留实体标签中的坐标、朝向和变体，删除旧主展示及 `_dir`、`_head` 辅助展示，每格建立一个新模型 ItemDisplay。启动扫描、实体所在区块加载、相邻连接变化、氧化与涂蜡、`/pipes reload` 和 CE 物品重载均使用这套模型。

跨区块转角先显示已知连接，等待相邻区块实体加载后补齐。不会为外观刷新强制加载相邻区块。模型几何端口止于方块边界，展示实体统一缩放为 `(1.001,1.001,1.001)`，避免共面渲染冲突；平移为零，以方块中心为原点旋转。旧的连接分类、容器偏移、管身伸缩及其配置已删除。`display.yml` 只保留现有物理头颅方块需要的皮肤数据。

## 构建与启用

编译依赖使用目标服务器安装的 CraftEngine JAR，可通过 `-PcraftEngineJar` 或 `CRAFTENGINE_JAR` 指定：

```sh
./gradlew build -x test -x uploadSftp \
  -PcraftEngineJar=/home/xiaozhangup/文档/Minecraft/26.2/plugins/craft-engine-paper-plugin-26.7.3.jar
```

产物为 `build/libs/Pipes-0.1.5.jar`。本项目的 `build` 自带 SFTP 上传收尾任务，本地验证必须显式加 `-x uploadSftp`。

先让 CE 加载此目录并生成、分发资源包，再启用新版 Pipes。仅执行 `/pipes reload` 不能把旧版本插件代码换成新版本。无需手动删除旧实体，未加载区块会在以后加载时迁移。本次进行了编译与模型／贴图引用、全状态旋转映射核对；游戏中的显示、物理头颅载体与模型的重叠、交互和区块迁移仍需客户端验收。
