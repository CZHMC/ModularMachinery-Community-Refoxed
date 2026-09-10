# AE2 ME 输入接口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 AE2 存在时注册一个同时计入 MMCR 物品输入和流体输入的 ME 输入端口，复用 AE2 原生接口逻辑，并让 MMCR 配方规划可以事务性读取和消耗其本地接口库存。

**Architecture:** 公共兼容层只持有不引用 AE2 类型的 bridge；bridge 在启动时按 ModList 选择通过类名反射加载的实现。AE2 实现放入 `loaded` 子包，端口方块继续走 MMCR 的动态 `IOPortKind` 注册，方块实体继承 `IOPortBlockEntity` 并组合 AE2 `InterfaceLogic`。两个 MMCR 资源视图包装同一个 AE2 `GenericStackInv`，通过 key adapter 完成物品/流体转换。

**Tech Stack:** Java, Minecraft 26.1.2, NeoForge transfer transactions, Applied Energistics 2 `26.1.11-SNAPSHOT`, JUnit 5, NeoForge GameTest。

## Global Constraints

- AE2 继续使用 `runtimeOnly "org.appliedenergistics:appliedenergistics2:26.1.11-SNAPSHOT"` 与 `implementation "org.appliedenergistics:appliedenergistics2:26.1.11-SNAPSHOT:api"`，不升级版本或新增依赖。
- 所有 AE2 直接类型引用只能出现在 `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded/`。
- `AE2Bridge`、`AE2BridgeBootstrap`、`UnavailableAE2Bridge` 不得导入 AE2 类型。
- AE2 未安装时不得注册 AE2 端口 kind、方块、方块实体、菜单或 capability provider，也不得解析 loaded 类。
- AE2 输入端口只实现物品和流体；不实现 ME 输出、样板、库存输入或异步输出接口，不实现 Memory Card 完整设置复制。
- 端口按 MMCR 结构计数为一个物品输入和一个流体输入；AE2 原生配置/存储库存保持 9 个槽位。
- AE2 端口的 MMCR capability view 不包含 `TransferFacet`，因此不参与 MMCR AutoIO；AE2 自己的网络补货和 crafting tracker 仍由 `InterfaceLogic` 负责。
- `ResourceStorage.reservationIdentity()` 默认返回 storage 自身；AE2 两个资源视图返回同一个底层 `GenericStackInv`。
- 所有 Java 新类添加 `@author howxu <dev@howxu.cn>`，沿用现有包结构、命名和格式。
- Gradle 任务必须串行执行；禁止运行 `gradle runClient --no-daemon`。
- 最终验证必须依次执行 `gradle test --no-daemon` 和 `gradle runGameTestServer --no-daemon`。

## File Map

Create:

- `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/AE2Bridge.java`: 公共 bridge 协议、端口查询、原生菜单打开和 capability 注册入口。
- `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/AE2BridgeBootstrap.java`: ModList 检查、反射加载和测试替身安装。
- `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/UnavailableAE2Bridge.java`: AE2 缺失时的空实现。
- `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded/LoadedAE2Bridge.java`: AE2 存在时的端口 kind、菜单和 capability 实现。
- `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded/AE2InputInterfaceKind.java`: 动态 MMCR 输入 kind 及 item/fluid 双 binding。
- `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded/AE2InputInterfaceBlockEntity.java`: MMCR 端口实体、AE2 node/logic 宿主、持久化和资源视图。
- `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded/AE2KeyAdapter.java`: MMCR resource 与 AE2 key 的类型转换协议。
- `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded/AE2ResourceStorage.java`: 基于 `GenericStackInv` 的通用事务资源存储。
- `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded/AE2ItemResourceStorage.java`: `ItemResource`/`AEItemKey` 适配器。
- `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded/AE2FluidResourceStorage.java`: `FluidResource`/`AEFluidKey` 适配器。
- `src/test/java/cn/howxu/mmcr/compat/appliedenergistics2/AE2BridgeTest.java`: bridge 可用/不可用选择和无 AE2 注册边界测试。
- `src/test/java/cn/howxu/mmcr/compat/appliedenergistics2/AE2ResourceStorageTest.java`: item/fluid 转换、过滤、容量和事务测试。
- `src/test/java/cn/howxu/mmcr/compat/appliedenergistics2/AE2InputInterfaceKindTest.java`: AE2 kind 的方向、family 和 capability binding 测试。
- `src/test/java/cn/howxu/mmcr/api/capability/PlanningReservationsTest.java`: 默认身份和共享底层身份预留测试。
- `src/gametest/java/cn/howxu/mmcr/AE2InterfaceGameTest.java`: AE2 网络、原生 UI、补货和 MMCR 消耗集成测试。

Modify:

- `src/main/java/cn/howxu/mmcr/MMCR.java`: 在任何可能触发端口注册前完成 AE2 bridge bootstrap。
- `src/main/java/cn/howxu/mmcr/registry/PortKinds.java`: 在 `createDefaults()` 中从 bridge 加入 AE2 kind。
- `src/main/java/cn/howxu/mmcr/internal/block/IOPortBlock.java`: 将 AE2 端口的右键菜单交给 loaded bridge 的 `MenuOpener`。
- `src/main/java/cn/howxu/mmcr/internal/event/ModCapabilities.java`: 调用 AE2 bridge 注册原生 AE2 capability provider。
- `src/main/java/cn/howxu/mmcr/api/capability/storage/ResourceStorage.java`: 添加默认预留身份方法。
- `src/main/java/cn/howxu/mmcr/api/capability/plan/PlanningReservations.java`: 按 `reservationIdentity()` 记录 resource slot 预留。
- `src/main/java/cn/howxu/mmcr/internal/capability/ItemBusCapability.java`: 增加不暴露 `TransferFacet` 的构造路径，保持旧构造器行为不变。
- `src/main/java/cn/howxu/mmcr/internal/capability/FluidHatchCapability.java`: 增加不暴露 `TransferFacet` 的构造路径，保持旧构造器行为不变。
- `src/main/java/cn/howxu/mmcr/GameTestRegistry.java`: 注册 AE2 GameTest。
- `src/main/resources/assets/mmcr/lang/en_us.json`: 添加 AE2 端口方块和容器名称。
- `src/main/resources/assets/mmcr/lang/zh_cn.json`: 添加 AE2 端口方块和容器名称。

No separate `AE2InputInterfaceBlock.java` is created. `ModBlocks` already creates an `IOPortBlock` for every `IOPortKind`, and AE2-specific menu behavior belongs in the bridge. Introducing a second block class would duplicate the dynamic registration path without changing block behavior.

---

### Task 1: 共享预留身份与 capability facet 开关

**Files:**
- Modify: `src/main/java/cn/howxu/mmcr/api/capability/storage/ResourceStorage.java:15-68`
- Modify: `src/main/java/cn/howxu/mmcr/api/capability/plan/PlanningReservations.java:17-162`
- Modify: `src/main/java/cn/howxu/mmcr/internal/capability/ItemBusCapability.java:42-62`
- Modify: `src/main/java/cn/howxu/mmcr/internal/capability/FluidHatchCapability.java:41-61`
- Create: `src/test/java/cn/howxu/mmcr/api/capability/PlanningReservationsTest.java`
- Create: `src/test/java/cn/howxu/mmcr/internal/capability/NonTransferCapabilityTest.java`

**Interfaces:**
- Consumes: existing `ResourceStorage<R>`, `PlanningReservations`, `CapabilityFactories.view(...)` and `CapabilityFacet` discovery.
- Produces: `ResourceStorage.reservationIdentity()`, reservation maps keyed by canonical identity, and four-argument item/fluid capability constructors with `boolean exposeTransferFacet`.

- [ ] **Step 1: Add the failing reservation identity tests.**

```java
@Test
void defaultStorageUsesItsOwnReservationIdentity() {
    ResourceStorage<ItemResource> storage = new BulkItemStorage(64L, () -> {});

    assertThat(storage.reservationIdentity()).isSameAs(storage);
}
```

Run: `gradle test --no-daemon --tests cn.howxu.mmcr.api.capability.PlanningReservationsTest`

Expected: compilation fails because `reservationIdentity()` does not exist yet.

- [ ] **Step 2: Add the facet regression tests.**

```java
@Test
void itemCapabilityCanHideTransferFacet() {
    ItemBusCapability capability = new ItemBusCapability(
            null, new BulkItemStorage(64L, () -> {}), IOType.INPUT, false);

    assertThat(capability.view().facets()).doesNotContain(TransferFacet.class);
    assertThat(capability.facet(TransferFacet.class)).isEmpty();
}

@Test
void fluidCapabilityCanHideTransferFacet() {
    FluidHatchCapability capability = new FluidHatchCapability(
            null, new LongFluidStorage(1, 1_000L, () -> {}), IOType.INPUT, false);

    assertThat(capability.view().facets()).doesNotContain(TransferFacet.class);
    assertThat(capability.facet(TransferFacet.class)).isEmpty();
}
```

Run: `gradle test --no-daemon --tests cn.howxu.mmcr.internal.capability.NonTransferCapabilityTest`

Expected: compilation fails because the new constructor overloads do not exist yet.

- [ ] **Step 3: Add the default identity method.**

```java
default Object reservationIdentity() {
    return this;
}
```

Place it in `ResourceStorage` beside the other default protocol methods so existing storage implementations keep identity semantics without edits.

- [ ] **Step 4: Normalize `PlanningReservations` resource keys.**

Change the resource map and helper method as follows:

```java
private final Map<Object, Map<Integer, ResourceReservation>> resources = new IdentityHashMap<>();

private ResourceReservation reservation(ResourceStorage<?> storage, int slot, boolean create) {
    Object identity = storage.reservationIdentity();
    Map<Integer, ResourceReservation> bySlot = resources.get(identity);
    if (bySlot == null) {
        if (!create) return null;
        bySlot = new HashMap<>();
        resources.put(identity, bySlot);
    }
    ResourceReservation reservation = bySlot.get(slot);
    if (reservation == null && create) {
        reservation = new ResourceReservation();
        bySlot.put(slot, reservation);
    }
    return reservation;
}
```

Keep `values` keyed by `LongValueStorage`, and keep `copy()` copying the canonical resource map entries exactly as before. Do not change requirement handlers or planner materialization.

- [ ] **Step 5: Make transfer facet exposure explicit.**

Add a constructor to each capability:

```java
public ItemBusCapability(IOPortBlockEntity port, ResourceStorage<ItemResource> storage,
                         IOType ioType, boolean exposeTransferFacet) {
    if (storage == null) throw new IllegalArgumentException("storage must not be null");
    if (ioType == null) throw new IllegalArgumentException("ioType must not be null");
    this.port = port;
    this.ioType = ioType;
    this.storage = storage;
    Set<Class<? extends CapabilityFacet>> facets = new LinkedHashSet<>(Set.of(
            ResourceFacet.class, OperationFacet.class, PresentationFacet.class, SyncFacet.class));
    if (exposeTransferFacet) facets.add(TransferFacet.class);
    this.view = CapabilityFactories.view(type(), directions(), Set.copyOf(facets));
}
```

Implement the fluid overload with the same shape and delegate all existing constructors to `exposeTransferFacet == true`. Keep `implements TransferFacet` on both classes so existing transfer policy code remains source-compatible; facet publication is controlled by `view()`, not Java interface inheritance.

- [ ] **Step 6: Run the focused tests.**

Run: `gradle test --no-daemon --tests cn.howxu.mmcr.api.capability.PlanningReservationsTest --tests cn.howxu.mmcr.internal.capability.NonTransferCapabilityTest`

Expected: PASS.

- [ ] **Step 7: Commit the base protocol change.**

```bash
git add src/main/java/cn/howxu/mmcr/api/capability/storage/ResourceStorage.java src/main/java/cn/howxu/mmcr/api/capability/plan/PlanningReservations.java src/main/java/cn/howxu/mmcr/internal/capability/ItemBusCapability.java src/main/java/cn/howxu/mmcr/internal/capability/FluidHatchCapability.java src/test/java/cn/howxu/mmcr/api/capability/PlanningReservationsTest.java src/test/java/cn/howxu/mmcr/internal/capability/NonTransferCapabilityTest.java
git commit -m "feat: add shared resource reservation identity"
```

### Task 2: Optional AE2 bridge boundary

**Files:**
- Create: `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/AE2Bridge.java`
- Create: `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/AE2BridgeBootstrap.java`
- Create: `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/UnavailableAE2Bridge.java`
- Modify: `src/main/java/cn/howxu/mmcr/MMCR.java:3-45`
- Create: `src/test/java/cn/howxu/mmcr/compat/appliedenergistics2/AE2BridgeTest.java`

**Interfaces:**
- Consumes: common NeoForge `RegisterCapabilitiesEvent`, `BlockPos`, `Level`, `ServerPlayer`, `IOPortKind` and `AbstractContainerMenu` types only.
- Produces: `AE2Bridge.get()`, `available()`, `portKinds()`, `isPort(String)`, `openMenu(ServerPlayer, Level, BlockPos)` and `registerCapabilities(RegisterCapabilitiesEvent)`.

- [ ] **Step 1: Add bridge selection tests.**

```java
@Test
void unavailableSelectionHasNoPorts() {
    AE2Bridge bridge = AE2BridgeBootstrap.selectForTesting(false);

    assertThat(bridge.available()).isFalse();
    assertThat(bridge.portKinds()).isEmpty();
    assertThat(bridge.isPort("ae2_me_input_interface")).isFalse();
}

@Test
void testOverrideIsReturnedByGet() {
    AE2Bridge fake = AE2BridgeBootstrap.selectForTesting(false);
    AE2BridgeBootstrap.installForTesting(fake);

    assertThat(AE2Bridge.get()).isSameAs(fake);
    AE2BridgeBootstrap.resetForTesting();
}
```

Run: `gradle test --no-daemon --tests cn.howxu.mmcr.compat.appliedenergistics2.AE2BridgeTest`

Expected: compilation fails because the bridge types do not exist yet.

- [ ] **Step 2: Define the API-neutral bridge.**

```java
public interface AE2Bridge {
    static AE2Bridge get() {
        return AE2BridgeBootstrap.bridge();
    }

    boolean available();

    List<IOPortKind> portKinds();

    boolean isPort(String id);

    boolean openMenu(ServerPlayer player, Level level, BlockPos pos);

    default void registerCapabilities(RegisterCapabilitiesEvent event) {
    }
}
```

Do not add AE2 `MenuOpener`, `InterfaceMenu`, `AECapabilities` or AE2 key imports to this file. `UnavailableAE2Bridge` returns `false`, `List.of()`, and does nothing for capability registration and menu opening.

- [ ] **Step 3: Implement lazy selection.**

Use these exact constants and selection rules:

```java
private static final String AE2_MOD_ID = "ae2";
private static final String LOADED_BRIDGE =
        "cn.howxu.mmcr.compat.appliedenergistics2.loaded.LoadedAE2Bridge";
```

`bootstrap()` only establishes the bridge lifecycle entry point; `bridge()` caches a loaded or unavailable implementation. `load()` checks `ModList.get() != null && ModList.get().isLoaded(AE2_MOD_ID)`. `loadLoadedBridge()` calls `Class.forName(LOADED_BRIDGE).getDeclaredConstructor().newInstance()` and wraps `ReflectiveOperationException` in `IllegalStateException`. Include `selectForTesting(boolean)`, `installForTesting(AE2Bridge)` and synchronized `resetForTesting()` matching the existing Mekanism bridge pattern.

- [ ] **Step 4: Bootstrap before content registration.**

Add the import and the first compatibility call in `MMCR`:

```java
AE2BridgeBootstrap.bootstrap();
MekanismBridgeBootstrap.bootstrap();
```

This must execute before `MekanismRecipeTypes.register()`, `PublicApiBootstrap.begin()` and any path that can initialize `PortKinds`.

- [ ] **Step 5: Run bridge tests.**

Run: `gradle test --no-daemon --tests cn.howxu.mmcr.compat.appliedenergistics2.AE2BridgeTest`

Expected: PASS; the test must not require an AE2 loaded class for the unavailable branch.

- [ ] **Step 6: Commit the bridge boundary.**

```bash
git add src/main/java/cn/howxu/mmcr/MMCR.java src/main/java/cn/howxu/mmcr/compat/appliedenergistics2 src/test/java/cn/howxu/mmcr/compat/appliedenergistics2/AE2BridgeTest.java
git commit -m "feat: add optional AE2 bridge boundary"
```

### Task 3: AE2 resource storage adapters

**Files:**
- Create: `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded/AE2KeyAdapter.java`
- Create: `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded/AE2ResourceStorage.java`
- Create: `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded/AE2ItemResourceStorage.java`
- Create: `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded/AE2FluidResourceStorage.java`
- Create: `src/test/java/cn/howxu/mmcr/compat/appliedenergistics2/AE2ResourceStorageTest.java`

**Interfaces:**
- Consumes: `GenericStackInv`, `GenericStack`, `AEKey`, `AEKeyType`, `AEItemKey`, `AEFluidKey`, `ResourceStorage<R>` and NeoForge `TransactionContext`.
- Produces: `AE2ResourceStorage<R>` with `reservationIdentity() == inventory`, plus concrete item/fluid storage constructors accepting one `GenericStackInv`.

- [ ] **Step 1: Add adapter and storage behavior tests.**

```java
@Test
void itemAdapterConvertsBothDirectionsAndRejectsFluidKey() {
    ItemResource item = ItemResource.of(Items.IRON);
    AE2KeyAdapter<ItemResource> adapter = AE2ItemResourceStorage.adapter();

    assertThat(adapter.toResource(adapter.toKey(item))).contains(item);
    assertThat(adapter.toResource(AEFluidKey.of(FluidResource.of(Fluids.WATER)))).isEmpty();
}

@Test
void committedInsertAndRollbackUseGenericStackSnapshots() {
    GenericStackInv inventory = new GenericStackInv(null, GenericStackInv.Mode.STORAGE, 1);
    AE2ItemResourceStorage storage = new AE2ItemResourceStorage(inventory);
    ItemResource iron = ItemResource.of(Items.IRON);

    try (Transaction transaction = Transaction.openRoot()) {
        assertThat(storage.insert(0, iron, 4L, transaction)).isEqualTo(4L);
    }
    assertThat(storage.amount(0)).isZero();

    try (Transaction transaction = Transaction.openRoot()) {
        assertThat(storage.insert(0, iron, 4L, transaction)).isEqualTo(4L);
        transaction.commit();
    }
    assertThat(storage.resource(0)).isEqualTo(iron);
    assertThat(storage.amount(0)).isEqualTo(4L);
}

@Test
void itemAndFluidViewsShareTheSameReservationIdentity() {
    GenericStackInv inventory = new GenericStackInv(null, GenericStackInv.Mode.STORAGE, 1);

    assertThat(new AE2ItemResourceStorage(inventory).reservationIdentity())
            .isSameAs(new AE2FluidResourceStorage(inventory).reservationIdentity());
}

@Test
void sharedViewsCannotReserveTheSameSlotTwice() {
    GenericStackInv inventory = new GenericStackInv(null, GenericStackInv.Mode.STORAGE, 1);
    ResourceStorage<ItemResource> item = new AE2ItemResourceStorage(inventory);
    ResourceStorage<FluidResource> fluid = new AE2FluidResourceStorage(inventory);
    PlanningReservations reservations = new PlanningReservations();

    assertThat(reservations.reserveInsert(item, 0, ItemResource.of(Items.IRON), 1L)).isTrue();
    assertThat(reservations.reserveInsert(fluid, 0, FluidResource.of(Fluids.WATER), 1L)).isFalse();
}

@Test
void storageRespectsKeyCapacityAndSupportedTypeFilter() {
    GenericStackInv inventory = new GenericStackInv(null, GenericStackInv.Mode.STORAGE, 1);
    inventory.setCapacity(AEKeyType.items(), 2L);
    AE2ItemResourceStorage items = new AE2ItemResourceStorage(inventory);

    try (Transaction transaction = Transaction.openRoot()) {
        assertThat(items.insert(0, ItemResource.of(Items.IRON), 5L, transaction)).isEqualTo(2L);
        transaction.commit();
    }

    GenericStackInv itemOnly = ConfigInventory.storage(1).supportedType(AEKeyType.items()).build();
    AE2FluidResourceStorage fluids = new AE2FluidResourceStorage(itemOnly);
    try (Transaction transaction = Transaction.openRoot()) {
        assertThat(fluids.insert(0, FluidResource.of(Fluids.WATER), 1L, transaction)).isZero();
    }
}
```

Run: `gradle test --no-daemon --tests cn.howxu.mmcr.compat.appliedenergistics2.AE2ResourceStorageTest`

Expected: compilation fails because the adapter and storage classes do not exist yet.

- [ ] **Step 2: Define the key adapter.**

```java
public interface AE2KeyAdapter<R> {
    AEKeyType keyType();
    Class<R> resourceType();
    AEKey toKey(R resource);
    Optional<R> toResource(AEKey key);
}
```

`AE2ItemResourceStorage.adapter()` returns an adapter using `AEItemKey.of(resource)` and `AEItemKey.toResource()`. `AE2FluidResourceStorage.adapter()` uses `AEFluidKey.of(resource)` and `AEFluidKey.toResource()`. Both `toResource` methods first verify the concrete key type and return `Optional.empty()` for the other resource family.

- [ ] **Step 3: Implement generic slot access.**

`AE2ResourceStorage<R>` must implement the following semantics without duplicating item/fluid code:

```java
public final Object reservationIdentity() {
    return inventory;
}

public int size() {
    return inventory.size();
}

public @Nullable R resource(int slot) {
    AEKey key = inventory.getKey(slot);
    return key == null ? null : adapter.toResource(key).orElse(null);
}

public long amount(int slot) {
    return inventory.getAmount(slot);
}

public long capacity(int slot, @Nullable R resource) {
    if (resource == null) return inventory.getCapacity(adapter.keyType());
    return inventory.getMaxAmount(adapter.toKey(resource));
}
```

`isValid` must reject null/empty resources, require `inventory.isAllowedIn(slot, key)`, and permit an empty slot or the same current key. `insert` and `extract` must validate slot, type, non-negative amount, key filter, current key, and capacity before mutation. Call `inventory.updateSnapshots(transaction)` immediately before changing the stack, then use `inventory.setStack(slot, new GenericStack(key, amount))` or `null`. Return only the actual moved amount. Do not mutate the inventory during simulation or when validation returns zero. Do not call `beginBatch()` in this wrapper: `GenericStackInv` is already a `SnapshotJournal`, and the host's transaction-aware `saveChanges()` in Task 4 suppresses immediate MMCR notifications until `GenericStackInv.onRootCommit()` invokes the listener once.

Use the `GenericStackInv` capacity methods so item max stack size, registered AE2 key capacities, supported key types and slot filters are all respected. Keep the storage wrapper free of MMCR `TransferFacet` behavior.

- [ ] **Step 4: Implement the concrete wrappers.**

Each concrete class only supplies its adapter and resource type:

```java
public final class AE2ItemResourceStorage extends AE2ResourceStorage<ItemResource> {
    public AE2ItemResourceStorage(GenericStackInv inventory) {
        super(inventory, adapter());
    }

    public static AE2KeyAdapter<ItemResource> adapter() {
        return ADAPTER;
    }
}
```

Use the corresponding `FluidResource` type for the fluid wrapper. Constructors must reject a null `GenericStackInv` through the base constructor.

- [ ] **Step 5: Run adapter tests.**

Run: `gradle test --no-daemon --tests cn.howxu.mmcr.compat.appliedenergistics2.AE2ResourceStorageTest`

Expected: PASS, including rollback restoring the original `GenericStack[]` and wrong-key filtering returning no resource.

- [ ] **Step 6: Commit the adapter layer.**

```bash
git add src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded src/test/java/cn/howxu/mmcr/compat/appliedenergistics2/AE2ResourceStorageTest.java
git commit -m "feat: adapt AE2 interface storage to MMCR resources"
```

### Task 4: AE2 input kind and MMCR block entity host

**Files:**
- Create: `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded/AE2InputInterfaceKind.java`
- Create: `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded/AE2InputInterfaceBlockEntity.java`
- Create: `src/test/java/cn/howxu/mmcr/compat/appliedenergistics2/AE2InputInterfaceKindTest.java`

**Interfaces:**
- Consumes: `IOPortBlockEntity`, `IOPortKind`, `PortFamilyDescriptor`, `PortDefinition`, built-in item/fluid capability types, AE2 `GridHelper`, `IManagedGridNode`, `InterfaceLogicHost`, `InterfaceLogic` and `BlockEntityNodeListener`.
- Produces: a kind with id `ae2_me_input_interface`, `IOType.INPUT`, two families/bindings, and an entity exposing `itemStorage()` and `fluidStorage()` over one `InterfaceLogic.getStorage()`.

- [ ] **Step 1: Add kind contract tests.**

```java
@Test
void ae2KindCountsOneItemAndOneFluidInput() {
    IOPortKind kind = AE2InputInterfaceKind.INSTANCE;

    assertThat(kind.id()).isEqualTo("ae2_me_input_interface");
    assertThat(kind.ioType()).isEqualTo(IOType.INPUT);
    assertThat(kind.families()).extracting(PortFamilyDescriptor::familyId)
            .containsExactlyInAnyOrder(PortFamilyIds.ITEM, PortFamilyIds.FLUID);
    assertThat(kind.definition().bindings()).extracting(binding -> binding.type().id())
            .containsExactlyInAnyOrder(PortFamilyIds.ITEM, PortFamilyIds.FLUID);
}
```

Run: `gradle test --no-daemon --tests cn.howxu.mmcr.compat.appliedenergistics2.AE2InputInterfaceKindTest#ae2KindCountsOneItemAndOneFluidInput`

Expected: compilation fails because the kind does not exist yet.

- [ ] **Step 2: Define the dynamic kind.**

Create `AE2InputInterfaceKind.INSTANCE` with:

```java
private static final String ID = "ae2_me_input_interface";
private static final List<PortFamilyDescriptor> FAMILIES = List.of(
        new PortFamilyDescriptor(PortFamilyIds.ITEM, IOType.INPUT, 0, List.of("item_input_bus")),
        new PortFamilyDescriptor(PortFamilyIds.FLUID, IOType.INPUT, 0, List.of("fluid_input_hatch")));
```

Its `definition()` must use two `CapabilityBinding` instances, both directed to `IOType.INPUT`, with factories that cast `context.host()` to `AE2InputInterfaceBlockEntity` and construct `ItemBusCapability`/`FluidHatchCapability` using `false` for `exposeTransferFacet`. The factories must use `host.itemStorage()` and `host.fluidStorage()`, not the built-in storage factory, so both views point at the AE2 storage inventory. The kind's entity factory is `(pos, state) -> new AE2InputInterfaceBlockEntity(pos, state, this)`.

- [ ] **Step 3: Implement the AE2 managed node and InterfaceLogic host.**

Initialize the entity in this order: `mainNode`, `InterfaceLogic`, then the two storage wrappers. Construct the node with `GridHelper.createManagedNode(this, NODE_LISTENER)`, set it as an in-world node, and use `AEBlocks.INTERFACE.asItem()` for the `InterfaceLogic` constructor. The node listener must delegate `onSaveChanges` to `saveChanges`, `onStateChanged` to `onMainNodeStateChanged`, and `onGridChanged` to `logic.gridChanged()`.

Implement these methods:

```java
@Override
public IManagedGridNode getMainNode() { return mainNode; }

@Override
public InterfaceLogic getInterfaceLogic() { return logic; }

@Override
public void saveChanges() {
    if (Transaction.getCurrentOpenedTransaction() != null) return;
    notifyStorageChanged();
    notifyControllerOfInputChange();
}

@Override
public AECableType getCableConnectionType(Direction direction) {
    return logic.getCableConnectionType(direction);
}

@Override
public void onMainNodeStateChanged(IGridNodeListener.State reason) {
    if (mainNode.hasGridBooted()) logic.notifyNeighbors();
}
```

The entity returns `IOType.INPUT`, `AE2InputInterfaceKind.INSTANCE`, its two storage wrappers, and a lazy `CapabilitySnapshot` built from the kind bindings. Import `net.neoforged.neoforge.transfer.transaction.Transaction` for the transaction check. `GenericStackInv.setStack` invokes the `InterfaceLogic` listener immediately, but while an MMCR transaction is open this method returns before notifying MMCR. The inventory's own root-commit callback invokes the listener again after commit, when `getCurrentOpenedTransaction()` is null, so MMCR receives one notification; an aborted transaction produces no root-commit callback and no MMCR notification.

- [ ] **Step 4: Implement persistence and lifecycle.**

`saveAdditional` must call `super.saveAdditional(output)`, then `mainNode.serialize(output)` and `logic.writeToNBT(output)`. `loadAdditional` must call `beginLoadingAdditional()`, invoke `super.loadAdditional(input)`, `mainNode.deserialize(input)`, and `logic.readFromNBT(input)` in a `try/finally` ending with `endLoadingAdditional()`.

Implement the AE2 lifecycle without inheriting AE2 `AENetworkedBlockEntity`:

```java
@Override
public void clearRemoved() {
    super.clearRemoved();
    GridHelper.onFirstTick(this, blockEntity -> {
        if (blockEntity.getLevel() != null) {
            blockEntity.mainNode.create(blockEntity.getLevel(), blockEntity.getBlockPos());
        }
    });
}

@Override
public void onChunkUnloaded() {
    super.onChunkUnloaded();
    mainNode.destroy();
}

@Override
public void setRemoved() {
    super.setRemoved();
    mainNode.destroy();
}
```

`dropContents()` must collect drops through `logic.addDrops(...)` and call `Block.popResource` for each stack. `clearContent()` must call `super.clearContent()` and `logic.clearContent()`. This preserves AE2 upgrades and configured local stacks while using MMCR's removal hook.

- [ ] **Step 5: Run kind/entity tests.**

Run: `gradle test --no-daemon --tests cn.howxu.mmcr.compat.appliedenergistics2.AE2InputInterfaceKindTest`

Expected: PASS; the kind exposes exactly two bindings and the entity's two views report the same `reservationIdentity()`.

- [ ] **Step 6: Commit the AE2 host.**

```bash
git add src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded/AE2InputInterfaceKind.java src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded/AE2InputInterfaceBlockEntity.java src/test/java/cn/howxu/mmcr/compat/appliedenergistics2/AE2InputInterfaceKindTest.java
git commit -m "feat: add AE2 input interface host"
```

### Task 5: Registration, native capability providers and menu dispatch

**Files:**
- Create: `src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded/LoadedAE2Bridge.java`
- Modify: `src/main/java/cn/howxu/mmcr/registry/PortKinds.java:404-473`
- Modify: `src/main/java/cn/howxu/mmcr/internal/event/ModCapabilities.java:46-54`
- Modify: `src/main/java/cn/howxu/mmcr/internal/block/IOPortBlock.java:97-130`
- Modify: `src/main/resources/assets/mmcr/lang/en_us.json`
- Modify: `src/main/resources/assets/mmcr/lang/zh_cn.json`

**Interfaces:**
- Consumes: `AE2InputInterfaceKind.INSTANCE`, `AECapabilities`, `InterfaceMenu.TYPE`, `MenuOpener`, `MenuLocators.forBlockEntity`, `ModBlockEntities.BES`, and the API-neutral bridge methods.
- Produces: dynamic block/block-entity registration through existing MMCR registries, native AE2 capabilities, and native AE2 Interface UI opening.

- [ ] **Step 1: Add unavailable registration assertions.**

Extend `AE2BridgeTest` with a fake bridge registration seam and assert that an unavailable bridge contributes no kind. The assertion must use the public bridge result rather than source-string inspection:

```java
@Test
void unavailableBridgeDoesNotContributeDynamicPort() {
    AE2Bridge bridge = AE2BridgeBootstrap.selectForTesting(false);

    assertThat(bridge.portKinds()).noneMatch(kind -> kind.id().equals("ae2_me_input_interface"));
    assertThat(bridge.isPort("ae2_me_input_interface")).isFalse();
}

@Test
void loadedBridgeContributesTheAe2InputKind() {
    AE2Bridge bridge = AE2BridgeBootstrap.selectForTesting(true);

    assertThat(bridge.available()).isTrue();
    assertThat(bridge.portKinds()).extracting(IOPortKind::id)
            .containsExactly("ae2_me_input_interface");
    assertThat(bridge.isPort("ae2_me_input_interface")).isTrue();
}
```

Run: `gradle test --no-daemon --tests cn.howxu.mmcr.compat.appliedenergistics2.AE2BridgeTest`

Expected: PASS after `LoadedAE2Bridge` is implemented; the unavailable assertion remains independent of the loaded implementation.

- [ ] **Step 2: Add AE2 kinds before registry freeze.**

At the start of `PortKinds.createDefaults()`, append `AE2Bridge.get().portKinds()` to the defaults list before the existing Mekanism declaration loop. Do not import any loaded AE2 class into `PortKinds`; only import `AE2Bridge`.

The existing `ModBlocks` and `ModBlockEntities` loops then automatically register the block and block entity from the kind. Do not add a second direct AE2 register or a second menu type in `ModUIs`.

- [ ] **Step 3: Implement loaded bridge native providers.**

`LoadedAE2Bridge` must return `true`, `List.of(AE2InputInterfaceKind.INSTANCE)`, and recognize only `ae2_me_input_interface`. In `registerCapabilities`, first resolve `BlockEntityType<?> blockEntityType = ModBlockEntities.BES.get("ae2_me_input_interface").get()`, then register providers against that type:

```java
event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, blockEntityType,
        (be, ignored) -> be instanceof AE2InputInterfaceBlockEntity host ? host : null);
event.registerBlockEntity(AECapabilities.GENERIC_INTERNAL_INV, blockEntityType,
        (be, side) -> be instanceof AE2InputInterfaceBlockEntity host
                ? host.getInterfaceLogic().getStorage() : null);
event.registerBlockEntity(AECapabilities.ME_STORAGE, blockEntityType,
        (be, side) -> be instanceof AE2InputInterfaceBlockEntity host
                ? host.getInterfaceLogic().getInventory() : null);
```

Use the exact capability distinction: `getStorage()` is MMCR's configured/local resource inventory and `getInventory()` is AE2's external ME inventory semantics, including network fallback when no configuration exists.

- [ ] **Step 4: Route the native Interface UI.**

Implement `openMenu` in `LoadedAE2Bridge` as:

```java
if (!(level.getBlockEntity(pos) instanceof AE2InputInterfaceBlockEntity host)) return false;
return MenuOpener.open(InterfaceMenu.TYPE, player, MenuLocators.forBlockEntity(host));
```

At the beginning of the server-side branch in `IOPortBlock.useWithoutItem`, before the generic MMCR `MenuProvider` branches, add:

```java
if (!level.isClientSide() && AE2Bridge.get().isPort(kind.id())) {
    if (player instanceof ServerPlayer serverPlayer
            && AE2Bridge.get().openMenu(serverPlayer, level, pos)) {
        return InteractionResult.SUCCESS;
    }
    return InteractionResult.CONSUME;
}
```

The common block must not import `InterfaceMenu`, `MenuOpener` or any AE2 type. Existing item, fluid, energy, combined and Mekanism menu paths remain unchanged.

- [ ] **Step 5: Connect the AE2 provider registration call.**

In `ModCapabilities.register`, call `AE2Bridge.get().registerCapabilities(event)` beside the existing Mekanism bridge call. The bridge method is API-neutral, so the common class remains safe when AE2 is absent.

- [ ] **Step 6: Add localization.**

Add these keys to both language files:

```json
"block.mmcr.ae2_me_input_interface": "AE2 ME Input Interface",
"container.mmcr.ae2_me_input_interface": "AE2 ME Input Interface"
```

Use the Chinese values `AE2 ME 输入接口` in `zh_cn.json`. The runtime model registry already treats every `IOPortBlock` as a dynamic port and `ModelGen` already excludes every id returned by `PortKinds.all()`, so no static model JSON or extra model generator branch is needed.

- [ ] **Step 7: Run registration tests and compile.**

Run: `gradle compileJava --no-daemon`

Expected: PASS; the common source compiles without AE2 imports outside `loaded`, and the dynamic registry sees the AE2 kind when AE2 is present.

Run: `gradle test --no-daemon --tests cn.howxu.mmcr.compat.appliedenergistics2.AE2BridgeTest`

Expected: PASS.

- [ ] **Step 8: Commit registration and UI integration.**

```bash
git add src/main/java/cn/howxu/mmcr/compat/appliedenergistics2/loaded/LoadedAE2Bridge.java src/main/java/cn/howxu/mmcr/registry/PortKinds.java src/main/java/cn/howxu/mmcr/internal/event/ModCapabilities.java src/main/java/cn/howxu/mmcr/internal/block/IOPortBlock.java src/main/resources/assets/mmcr/lang/en_us.json src/main/resources/assets/mmcr/lang/zh_cn.json
git commit -m "feat: register AE2 input interface"
```

### Task 6: AE2 and MMCR integration GameTest

**Files:**
- Create: `src/gametest/java/cn/howxu/mmcr/AE2InterfaceGameTest.java`
- Modify: `src/gametest/java/cn/howxu/mmcr/GameTestRegistry.java:42-192`

**Interfaces:**
- Consumes: dynamic `ModBlocks.BLOCKS`, `ModBlockEntities.BES`, `AECapabilities`, AE2 `InterfaceLogic`, NeoForge item/fluid handlers and existing MMCR GameTest helpers.
- Produces: runtime evidence for node lifecycle, native UI, AE2 local storage and MMCR item/fluid consumption.

- [ ] **Step 1: Add the GameTest registration entry and test method.**

Register one test with the existing style:

```java
register(event, "ae2_me_input_interface", 200,
        helper -> new AE2InterfaceGameTest().interfaceFeedsMmcrInputs(helper));
```

The registration and `interfaceFeedsMmcrInputs(GameTestHelper helper)` method must be added in the same edit so the gametest source remains compilable.

- [ ] **Step 2: Implement block and capability setup.**

In `interfaceFeedsMmcrInputs`, place `ModBlocks.BLOCKS.get("ae2_me_input_interface").get()` and assert the block entity is `AE2InputInterfaceBlockEntity`. Query all three AE2 capabilities at the block position and assert they are non-null. Assert the MMCR entity exposes exactly two capabilities, item and fluid, and neither capability's `view().facets()` contains `TransferFacet`.

- [ ] **Step 3: Exercise native InterfaceLogic state.**

Use `getInterfaceLogic().getConfig().setStack(...)` to configure one item and one fluid slot, then assert the logic retains nine configuration slots and the storage view is the same underlying identity used by both MMCR resource wrappers. Open the block with the player and assert the server container is AE2 `InterfaceMenu`, proving the bridge calls `MenuOpener` instead of a MMCR menu.

- [ ] **Step 4: Exercise transactional local storage and MMCR consumption.**

Seed the AE2 `GenericStackInv` through the `ME_STORAGE` or `GENERIC_INTERNAL_INV` capability, form the smallest existing test multiblock containing the dynamic port, and use the existing item/fluid requirement test helpers to run a recipe that consumes both resources. Assert the recipe operation succeeds, the AE2 local inventory amounts decrease, and the MMCR controller reports the input as consumed. Include a failed transaction path and assert both resources remain unchanged.

- [ ] **Step 5: Exercise lifecycle and persistence.**

Unload and reload the test chunk, then assert the grid node can reconnect and the InterfaceLogic configuration, storage, priority and upgrade inventory remain present. Remove the block and assert `logic.addDrops` supplies stored contents and upgrades through the MMCR removal hook.

- [ ] **Step 6: Run the focused GameTest.**

Run: `gradle runGameTestServer --no-daemon`

Expected: the `ae2_me_input_interface` test passes with AE2 loaded. If the test framework has a test selector already used by the project, use that selector to rerun only this id after the full server task has initialized.

- [ ] **Step 7: Commit integration coverage.**

```bash
git add src/gametest/java/cn/howxu/mmcr/AE2InterfaceGameTest.java src/gametest/java/cn/howxu/mmcr/GameTestRegistry.java
git commit -m "test: cover AE2 input interface integration"
```

### Task 7: Final review and required verification

**Files:**
- Review all files changed by Tasks 1-6.
- Modify only files with findings from the review.

- [ ] **Step 1: Perform the first implementation review.**

Check every direct AE2 import is under the loaded package, every loaded class is reached only through reflection or loaded bridge methods, `getStorage()` and `getInventory()` are not swapped, and both MMCR views use the same reservation identity. Check the dynamic kind appears before `PortDefinitionRegistry.freeze()` and no `ModUIs` registration was added for the native AE2 menu.

- [ ] **Step 2: Fix review findings and rerun the narrowest affected test.**

For each finding, make one minimal fix, run the directly affected focused test, and repeat until the first review has no actionable correctness finding. Do not perform unrelated refactors or remove existing dead code.

- [ ] **Step 3: Perform the final review.**

Confirm the final diff contains only the feature files, language keys and tests listed in this plan. Record any non-blocking residual risk rather than expanding scope.

- [ ] **Step 4: Run required full tests serially.**

Run exactly:

```bash
gradle test --no-daemon
gradle runGameTestServer --no-daemon
```

Expected: both commands exit successfully. Never run these Gradle commands in parallel.

- [ ] **Step 5: Check the final worktree.**

Run: `git status --short`

Expected: only intentionally uncommitted review fixes, if any; no build output, logs, IDE files, `docs` files outside the explicitly force-added plan/spec commits, or generated cache files.

- [ ] **Step 6: Commit any final fixes.**

```bash
git add src/main/java/cn/howxu/mmcr/MMCR.java src/main/java/cn/howxu/mmcr/compat/appliedenergistics2 src/main/java/cn/howxu/mmcr/registry/PortKinds.java src/main/java/cn/howxu/mmcr/internal/block/IOPortBlock.java src/main/java/cn/howxu/mmcr/internal/event/ModCapabilities.java src/main/java/cn/howxu/mmcr/api/capability/storage/ResourceStorage.java src/main/java/cn/howxu/mmcr/api/capability/plan/PlanningReservations.java src/main/java/cn/howxu/mmcr/internal/capability/ItemBusCapability.java src/main/java/cn/howxu/mmcr/internal/capability/FluidHatchCapability.java src/test/java/cn/howxu/mmcr/compat/appliedenergistics2 src/test/java/cn/howxu/mmcr/api/capability/PlanningReservationsTest.java src/test/java/cn/howxu/mmcr/internal/capability/NonTransferCapabilityTest.java src/gametest/java/cn/howxu/mmcr/AE2InterfaceGameTest.java src/gametest/java/cn/howxu/mmcr/GameTestRegistry.java src/main/resources/assets/mmcr/lang/en_us.json src/main/resources/assets/mmcr/lang/zh_cn.json
git commit -m "fix: address AE2 input interface review findings"
```

Do not amend earlier commits.

## Coverage Checklist

- AE2 absent: bridge returns empty kinds and does not load direct AE2 classes.
- AE2 present: dynamic kind registers before port definition freeze and reuses existing MMCR block/entity registration loops.
- Native AE2: node lifecycle, `InterfaceLogic`, fuzzy matching, crafting card, priority, upgrade and persistence paths remain delegated to AE2.
- MMCR resources: item/fluid conversion, capacity, slot filter, simulation, commit and rollback are covered.
- Reservation safety: item/fluid views share the same underlying identity.
- AutoIO boundary: item/fluid capability views omit `TransferFacet`.
- UI boundary: AE2 `InterfaceMenu` is opened by AE2 `MenuOpener`; no MMCR duplicate menu is registered.
- Full verification: unit tests and GameTest server run sequentially with the required Gradle commands.
