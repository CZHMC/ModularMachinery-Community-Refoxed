# AE2 ME 输入接口设计

## 范围

本阶段只实现 AE2 可用时注册的 MMCR ME 输入接口，支持物品和流体两种 AEKey 类型。接口作为一个 MMCR 输入端口参与多方块结构，端口定义按一个物品输入和一个流体输入计数。

AE2 接口本身仍保留原生的 9 个配置槽，不把“一个物品输入和一个流体输入”解释为槽位数量限制。本阶段不实现 ME 输出接口、样板接口、库存输入接口或异步输出接口。

第一阶段验收的 AE2 功能包括：

- 原生配置槽和存储槽菜单。
- `Fuzzy Card` 的模糊匹配行为。
- `Crafting Card` 的网络合成补货行为。
- 配置库存、存储库存、优先级和升级库存的持久化。
- AE2 网络对接口库存的物品/流体输入和抽取。
- MMCR 配方对接口库存中物品和流体的规划、预留、事务消耗。

Memory Card 的完整设置导入导出依赖 AE2 具体 `AEBaseBlockEntity` 的功能，不纳入本阶段验收范围。

## 方案选择

### 方案 A：组合 AE2 `InterfaceLogic`，采用 MMCR 端口实体

新实体继承 MMCR `IOPortBlockEntity`，在 AE2 可用的 loaded 兼容包中组合原生 `InterfaceLogic`，并实现 `InterfaceLogicHost` 与 `IGridConnectedBlockEntity`。

优点：

- 保留 AE2 接口的配置、补货、合成请求、升级卡和优先级逻辑。
- 继续满足 MMCR 的 `IOPortBlockEntity`、`IOPortKind` 和多方块端口计数协议。
- 只需通过宿主协议复现 AE2 网络节点生命周期，不需要复制 `InterfaceLogic` 内部算法。
- AE2 不存在时可以完全不加载该实体类。

### 方案 B：直接继承 AE2 `InterfaceBlockEntity`

不采用。AE2 方块实体和 MMCR `IOPortBlockEntity` 都需要作为 Java 父类，无法同时继承；若修改 MMCR 端口体系以绕过这一限制，改动范围大且会破坏现有软兼容边界。

### 方案 C：重新实现 MMCR 库存并提供 AE2 外部 facade

不采用。该方案会复制 AE2 接口的配置、合成、升级卡和网络请求逻辑，容易与 AE2 原生行为产生差异，也无法自然复用后续 AE2 接口类型。

采用方案 A。

## 可选依赖边界

所有 AE2 相关代码放在以下包中：

```text
src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/
```

直接引用 AE2 API 的类放在 `loaded` 子包。公共兼容接口不引用 AE2 类型，负责在 AE2 存在时选择 loaded 实现：

```text
compat/appliedenergistics2/
  AppliedEnergistics2Bridge.java
  AppliedEnergistics2BridgeBootstrap.java
  UnavailableAppliedEnergistics2Bridge.java
  loaded/
    LoadedAppliedEnergistics2Bridge.java
    AppliedEnergistics2InputInterfaceBlockEntity.java
    AppliedEnergistics2InputInterfaceBlock.java
    AppliedEnergistics2InputInterfaceKind.java
    AppliedEnergistics2KeyAdapter.java
    AppliedEnergistics2ResourceStorage.java
    AppliedEnergistics2ItemResourceStorage.java
    AppliedEnergistics2FluidResourceStorage.java
```

`AppliedEnergistics2BridgeBootstrap` 使用 `ModList` 判断 AE2 是否存在，并通过类名反射加载 `LoadedAppliedEnergistics2Bridge`。MMCR 主类在注册 MMCR 内容之前完成 bridge bootstrap。AE2 缺失时：

- 不加入 AE2 端口 kind。
- 不注册 AE2 方块、方块实体或菜单。
- 不注册 AE2 capability provider。
- 不加载任何包含 AE2 类型引用的 loaded 类。

AE2 存在时，loaded bridge 在 `PortKinds` 生成默认端口期间提供 ME 输入端口 kind，使其在 `PortDefinitionRegistry.freeze()` 之前完成注册。

## MMCR 端口和 AE2 宿主

`AppliedEnergistics2InputInterfaceKind` 是一个动态的 `IOPortKind`，方向为 `INPUT`，包含两个 `PortFamilyDescriptor`：

- `item` 家族，别名为 `item_input_bus`。
- `fluid` 家族，别名为 `fluid_input_hatch`。

因此，MMCR 结构识别会把它作为一个端口，同时计入一个物品输入和一个流体输入。端口的 capability definition 也包含物品和流体两个绑定。

`AppliedEnergistics2InputInterfaceBlockEntity` 继承 `IOPortBlockEntity`，并组合：

- 一个由 `GridHelper.createManagedNode` 创建的 AE2 managed grid node。
- 一个以该 node、宿主和接口方块物品构造的 `InterfaceLogic`。
- 一个包含物品资源视图和流体资源视图的 MMCR `CapabilitySnapshot`。

实体实现 `IGridConnectedBlockEntity` 的节点访问和宿主保存协议，并复现 AE2 网络实体的必要生命周期：

- 保存和加载时分别序列化/反序列化 grid node 与 `InterfaceLogic`。
- 首次进入 ticking chunk 时创建 grid node。
- 区块卸载、实体移除时销毁 grid node。
- 主节点发生网络变化时调用 `InterfaceLogic.gridChanged()`。
- AE2 `InterfaceLogic` 的 `saveChanges()` 回调同时触发 MMCR `notifyStorageChanged()`，让 MMCR 控制器刷新 capability presentation 和资源可用性。
- 破坏和清空时委托 `InterfaceLogic.addDrops()`、`InterfaceLogic.clearContent()`。

方块交互通过 loaded bridge 调用 AE2 `MenuOpener` 和 `InterfaceMenu`，不在 MMCR namespace 中复制菜单。AE2 自己负责注册对应的原生界面和客户端屏幕。

由于端口方块仍复用 MMCR `IOPortBlock`，`IOPortBlock` 的菜单分派增加 AE2 bridge 分支：识别 AE2 端口 id 后由 bridge 调用 AE2 的 `MenuOpener`，而不是落入普通 MMCR 菜单或 Mekanism 菜单分支。该分支只依赖不含 AE2 类型的 bridge 接口。

AE2 capability provider 注册如下：

- `AECapabilities.IN_WORLD_GRID_NODE_HOST` 返回该实体自身。
- `AECapabilities.GENERIC_INTERNAL_INV` 返回 `InterfaceLogic.getStorage()`。
- `AECapabilities.ME_STORAGE` 返回 `InterfaceLogic.getInventory()`。

这一区分必须保留：`getStorage()` 是 MMCR 读取和消耗的配置库存；`getInventory()` 是 AE2 外部访问接口库存的原生语义，在有配置时使用本地库存、无配置时回退网络库存。

## AE2 资源适配层

适配层只依赖 AE2 的通用 `GenericStackInv` 和 `AEKey` 协议，不把物品或流体逻辑写死在一个大类中。

### `AppliedEnergistics2KeyAdapter<R>`

适配一种 MMCR resource 和 AE2 key 的转换：

- 返回对应 `AEKeyType`。
- 返回 MMCR resource 的 `Class<R>`。
- 将 `R` 转成 `AEKey`。
- 将 `AEKey` 转回 `R`，遇到其它 key 类型时返回空。

物品实现使用 `AEItemKey.of(ItemResource)` 和 `AEItemKey.toResource()`；流体实现使用 `AEFluidKey.of(FluidResource)` 和 `AEFluidKey.toResource()`。以后增加化学品或其它资源类型时，只增加新的 key adapter 和具体 storage 类。

### `AppliedEnergistics2ResourceStorage<R>`

抽象类实现 `ResourceStorage<R>`，持有 `GenericStackInv` 和 key adapter，统一实现：

- slot 数量、resource、amount、capacity。
- `isValid`、`canInsert`、`canExtract` 语义。
- 对指定 slot 的插入和抽取。
- `GenericStackInv` 的 `updateSnapshots(TransactionContext)`、batch 和 `setStack` 操作。
- AE2 的 slot filter、key 类型过滤和容量限制。

所有状态修改都通过 NeoForge transaction context 连接到 AE2 的 snapshot 机制，模拟操作不改状态，事务回滚时恢复原始 `GenericStack` 数组，根事务提交时只产生一次变化通知。

具体的 `AppliedEnergistics2ItemResourceStorage` 和 `AppliedEnergistics2FluidResourceStorage` 只提供类型转换。两个视图包装同一个 `GenericStackInv`，因此能保留 AE2 的混合物品/流体配置槽。

## 共享预留身份

同一个 AE2 `GenericStackInv` 会被物品和流体两个 `ResourceStorage` 视图包装。若 `PlanningReservations` 只按包装对象身份记录预留，未来输出接口可能将同一个空槽同时预留给物品和流体。

因此修改 MMCR 基础协议：

- `ResourceStorage` 增加 `reservationIdentity()` 默认方法，默认返回自身，保持现有 storage 行为不变。
- `PlanningReservations` 使用 `reservationIdentity()` 返回值作为 `IdentityHashMap` 的 key。
- `AppliedEnergistics2ResourceStorage` 返回其底层 `GenericStackInv` 作为预留身份。

这样，物品和流体视图在同一 AE2 槽位上的预留会互相可见；同一资源的多次预留仍按现有虚拟 amount 计算，提交阶段仍使用原有 `CapabilityOperation` 事务。

## MMCR capability

输入接口提供两个 capability：

- `ItemBusCapability`，资源类型为 `ItemResource`，方向为 `INPUT`。
- `FluidHatchCapability`，资源类型为 `FluidResource`，方向为 `INPUT`。

两个 capability 复用现有 operation、presentation 和 sync 机制，但 capability view 不包含 `TransferFacet`。因此：

- MMCR 配方规划可以读取和抽取接口库存。
- AE2 的 NeoForge generic inventory adapter 仍可对接口执行物品/流体输入和抽取。
- MMCR `IOPortBlockEntity` 不会为该接口建立 AutoIO 配置或运行 AutoIO 转移。

`InterfaceLogic` 的 AE2 tick 自己负责根据配置从网络补货，网络合成结果也由 AE2 的 crafting tracker 写入接口 storage。其 storage change listener 经宿主回调通知 MMCR，触发资源可用性 epoch 和配方搜索唤醒。

## 数据流

### AE2 网络补货到 MMCR 配方

```text
AE2 network storage/crafting service
  -> InterfaceLogic ticker
  -> GenericStackInv storage
  -> AppliedEnergistics2ResourceStorage
  -> MMCR ItemBusCapability / FluidHatchCapability
  -> RequirementPlanner reservation
  -> CapabilityOperation transaction
  -> GenericStackInv snapshot commit
```

### MMCR 配方消耗

MMCR 仍使用现有 `ItemRequirementHandler` 和 `FluidRequirementHandler`：

1. 从两个 capability 的 storage 视图读取当前资源。
2. 在 `PlanningReservations` 中按底层 `GenericStackInv` 身份进行预留。
3. 最终并行度确定后创建 item/fluid operation。
4. operation 在 NeoForge transaction 中调用 AE2 storage adapter。
5. 成功提交后由 `GenericStackInv` 的 listener 保存 AE2 接口并通知 MMCR 控制器。

不修改 `RequirementPlanner` 的候选并行度、二分预留或 operation materialization 流程。

## 错误处理

- AE2 未安装时返回不可用 bridge，不能因为解析 AE2 类型失败而影响 MMCR 启动。
- AE2 节点未连接、未供电或网络未完成启动时，`InterfaceLogic` 保持原生无操作行为，MMCR 只看到当前本地 storage。
- AE2 storage adapter 遇到错误 key 类型、错误 resource 类型、无效 slot 或不匹配 resource 时返回 0，不改变底层库存。
- transaction 中任一 action 未完成时返回 MMCR capability failure，由现有执行流程回滚整个事务。
- storage adapter 不自行吞掉运行时错误；仅对类型和容量不匹配返回失败，保持现有 capability operation 的错误语义。

## 测试策略

单元测试覆盖：

- item/fluid key adapter 的双向转换和错误 key 类型过滤。
- `AppliedEnergistics2ResourceStorage` 的模拟、提交、回滚、slot filter 和容量行为。
- 物品视图与流体视图共享同一底层预留身份，不能重复预留一个槽位。
- 输入 capability 不暴露 `TransferFacet`，不会生成 AutoIO capability。
- `PlanningReservations` 默认身份对既有 storage 保持兼容。

GameTest 或带 AE2 运行时的集成测试覆盖：

- AE2 接口节点连接网络并打开原生 Interface UI。
- 配置物品和流体后，AE2 网络补货能进入接口本地库存。
- MMCR 多方块从接口消耗物品和流体后，AE2 接口库存与 MMCR 状态一致。
- 移除 AE2 后 MMCR 注册流程不包含 AE2 端口和 capability。

验证命令必须串行执行：

```text
gradle test --no-daemon
gradle runGameTestServer --no-daemon
```

## 后续扩展边界

输出接口、样板接口、库存输入接口和异步输出接口复用 `AppliedEnergistics2ResourceStorage<R>` 及 key adapter，不复制 slot/transaction 逻辑。若物品和流体 capability 继续共享同一 AE2 inventory，统一使用底层预留身份。

AE2 与 Mekanism 或其它模组的组合只在新增 key adapter、资源 capability 和 loaded bridge 扩展点中实现；当前阶段不注册化学品 capability，也不改变 Mekanism 现有 bridge。
