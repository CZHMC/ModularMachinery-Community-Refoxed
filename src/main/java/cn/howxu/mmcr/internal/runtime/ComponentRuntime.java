package cn.howxu.mmcr.internal.runtime;

import cn.howxu.mmcr.api.capability.CapabilityHost;
import cn.howxu.mmcr.api.capability.CapabilitySnapshot;
import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.capability.facet.TickFacet;
import cn.howxu.mmcr.api.capability.plan.CapabilityOperation;
import cn.howxu.mmcr.api.capability.plan.CapabilityResult;
import cn.howxu.mmcr.api.capability.storage.CapabilityStorage;
import cn.howxu.mmcr.api.capability.status.BuiltinFailureReasons;
import cn.howxu.mmcr.api.capability.status.ExecutionStatus;
import cn.howxu.mmcr.api.capability.status.FailureOccurrence;
import cn.howxu.mmcr.api.capability.status.FailurePhase;
import cn.howxu.mmcr.api.capability.status.StatusSeverity;
import cn.howxu.mmcr.api.capability.tick.CapabilityTickContext;
import cn.howxu.mmcr.api.capability.tick.CapabilityTickResult;
import cn.howxu.mmcr.api.capability.storage.LongValueStorage;
import cn.howxu.mmcr.api.capability.storage.ResourceStorage;
import cn.howxu.mmcr.api.machine.Machine;
import cn.howxu.mmcr.api.machine.level.MachineLevel;
import cn.howxu.mmcr.api.publicapi.machine.ModifierDefinition;
import cn.howxu.mmcr.internal.capability.CapabilityFactories;
import cn.howxu.mmcr.api.recipe.MachineComponent;
import cn.howxu.mmcr.api.recipe.helper.ProcessingComponent;
import cn.howxu.mmcr.api.recipe.modifier.ModifierRegistry;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier;
import cn.howxu.mmcr.internal.multiblock.ModuleConnectionStatus;
import cn.howxu.mmcr.internal.tile.ParallelControllerBlockEntity;
import cn.howxu.mmcr.util.IOType;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Owns the effective component, modifier, level, link, module, and capability state of a controller.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class ComponentRuntime {
    private static final ExecutionStatus UNSPECIFIED_TICK_OPERATION_FAILURE = new ExecutionStatus(
            Identifier.fromNamespaceAndPath("mmcr", "capability_tick_operation_failure"), StatusSeverity.FAILURE,
            Identifier.fromNamespaceAndPath("mmcr", "capability_tick"),
            FailureOccurrence.at(BuiltinFailureReasons.OPERATION_FAILED_WITHOUT_STATUS,
                    Identifier.fromNamespaceAndPath("mmcr", "capability_tick"),
                    FailurePhase.CAPABILITY_COMMIT, null, null,
                    Map.of("raw_reason_id", "operation_failed_without_status")));
    private List<ProcessingComponent> components = List.of();
    private List<MachineCapability> capabilities = List.of();
    private List<CapabilityIdentity> capabilityIdentity = List.of();
    private long capabilityVersion;
    private long modifierVersion;
    private long stateVersion;
    private long componentPresentationEpoch;
    private long capabilityPresentationEpoch;
    private long levelVersion;
    private long cachedComponentPresentationEpoch = Long.MIN_VALUE;
    private List<ControllerRuntimeSnapshot.ComponentPresentation> cachedComponentPresentations = List.of();
    private long cachedCapabilityPresentationEpoch = Long.MIN_VALUE;
    private List<ControllerRuntimeSnapshot.CapabilityPresentation> cachedCapabilityPresentations = List.of();
    private Map<String, List<RecipeModifier>> foundModifiers = Map.of();
    private List<RecipeModifier> flattenedModifiers = List.of();
    private Map<Identifier, MachineLevel> foundLevels = Map.of();
    private Set<BlockPos> linkedPortPositions = Set.of();
    private ModuleConnectionStatus moduleConnectionStatus = ModuleConnectionStatus.disconnected();
    private int installedModuleCount;
    private List<UpgradeBusSnapshot> upgradeBuses = List.of();
    private List<ItemStack> upgradeItems = List.of();
    private Map<Identifier, Long> upgradeModifierUnits = Map.of();
    private List<RecipeModifier> upgradeModifiers = List.of();
    private long upgradeContentRevision;
    private boolean modifiersAllowed = true;

    public boolean replaceComponents(List<ProcessingComponent> components) {
        List<ProcessingComponent> nextComponents = List.copyOf(components == null ? List.of() : components);
        CapabilityState capabilityState = capabilityStateFor(nextComponents);
        List<MachineCapability> nextCapabilities = capabilityState.capabilities();
        List<CapabilityIdentity> nextIdentity = capabilityState.identity();
        boolean componentsChanged = !this.components.equals(nextComponents);
        boolean capabilitiesChanged = !capabilityIdentity.equals(nextIdentity);
        this.components = nextComponents;
        if (componentsChanged) {
            stateVersion++;
            componentPresentationEpoch++;
        }
        this.capabilities = nextCapabilities;
        if (capabilitiesChanged) capabilityPresentationEpoch++;
        if (capabilitiesChanged) {
            this.capabilityIdentity = nextIdentity;
            capabilityVersion++;
        }
        return componentsChanged;
    }

    public List<ProcessingComponent> components() {
        return components;
    }

    public List<MachineCapability> capabilities() {
        return capabilities;
    }

    /**
     * Plans each tick facet in snapshot order and commits the resulting operations atomically.
     */
    public CapabilityTickResult executeTickPhase(CapabilityTickContext context) {
        Objects.requireNonNull(context, "context");
        List<CapabilityOperation> operations = new ArrayList<>();
        boolean stateChanged = false;
        for (MachineCapability capability : context.capabilitySnapshot().capabilities()) {
            TickFacet facet = capability.facet(TickFacet.class).orElse(null);
            if (facet == null) continue;
            CapabilityTickResult result = Objects.requireNonNull(facet.plan(context), "tick facet result");
            operations.addAll(result.operations());
            stateChanged |= result.stateChanged();
            if (result.failure() != null) {
                return new CapabilityTickResult(operations, result.failure(), stateChanged);
            }
        }
        if (operations.isEmpty()) {
            if (stateChanged) markCapabilityPresentationChanged();
            return new CapabilityTickResult(operations, null, stateChanged);
        }
        try (Transaction transaction = Transaction.openRoot()) {
            for (CapabilityOperation operation : operations) {
                CapabilityResult result = operation.commit(transaction);
                if (result == null || !result.success()) {
                    ExecutionStatus failure = result == null || result.status() == null
                            ? UNSPECIFIED_TICK_OPERATION_FAILURE : result.status();
                    return new CapabilityTickResult(operations, failure, stateChanged);
                }
            }
            transaction.commit();
        }
        if (stateChanged) markCapabilityPresentationChanged();
        return new CapabilityTickResult(operations, null, stateChanged);
    }

    public List<ControllerRuntimeSnapshot.ComponentPresentation> componentPresentations() {
        if (cachedComponentPresentationEpoch == componentPresentationEpoch) return cachedComponentPresentations;
        List<ControllerRuntimeSnapshot.ComponentPresentation> snapshots = new ArrayList<>(components.size());
        for (ProcessingComponent component : components) {
            MachineComponent machineComponent = component.getComponent();
            snapshots.add(new ControllerRuntimeSnapshot.ComponentPresentation(
                    component.getPos(),
                    machineComponent == null || machineComponent.kind() == null ? null : machineComponent.kind().id(),
                    machineComponent == null || machineComponent.kind() == null ? null : machineComponent.kind().ioType(),
                    component.tags()));
        }
        cachedComponentPresentations = List.copyOf(snapshots);
        cachedComponentPresentationEpoch = componentPresentationEpoch;
        return cachedComponentPresentations;
    }

    public List<ControllerRuntimeSnapshot.CapabilityPresentation> capabilityPresentations() {
        if (cachedCapabilityPresentationEpoch == capabilityPresentationEpoch) return cachedCapabilityPresentations;
        List<ControllerRuntimeSnapshot.CapabilityPresentation> snapshots = new ArrayList<>(capabilities.size());
        for (MachineCapability capability : capabilities) {
            LongValueStorage value = CapabilityFactories.valueStorage(capability, LongValueStorage.class);
            ResourceStorage<?> resourceStorage = CapabilityFactories.resourceStorage(capability);
            for (IOType direction : capability.view().directions().values()) {
                if (value != null) {
                    snapshots.add(new ControllerRuntimeSnapshot.CapabilityPresentation(
                            capability.type() == null ? null : capability.type().id(), direction,
                            value.amount(), value.capacity(), List.of()));
                } else if (resourceStorage != null) {
                    snapshots.add(resourcePresentation(capability, resourceStorage, direction));
                } else {
                    snapshots.add(new ControllerRuntimeSnapshot.CapabilityPresentation(
                        capability.type() == null ? null : capability.type().id(), direction, 0L, 0L, List.of()));
                }
            }
        }
        cachedCapabilityPresentations = List.copyOf(snapshots);
        cachedCapabilityPresentationEpoch = capabilityPresentationEpoch;
        return cachedCapabilityPresentations;
    }

    public long capabilityVersion() {
        return capabilityVersion;
    }

    public long modifierVersion() {
        return modifierVersion;
    }

    public long stateVersion() {
        return stateVersion;
    }

    public long levelVersion() {
        return levelVersion;
    }

    public long capabilityPresentationEpoch() {
        return capabilityPresentationEpoch;
    }

    public boolean replaceModifiers(Map<String, List<RecipeModifier>> modifiers) {
        Map<String, List<RecipeModifier>> next = new LinkedHashMap<>();
        if (modifiers != null) {
            modifiers.forEach((key, value) -> next.put(key, List.copyOf(value == null ? List.of() : value)));
        }
        if (foundModifiers.size() == next.size()) {
            var current = foundModifiers.entrySet().iterator();
            var candidate = next.entrySet().iterator();
            boolean orderedEqual = true;
            while (current.hasNext()) {
                if (!Objects.equals(current.next(), candidate.next())) {
                    orderedEqual = false;
                    break;
                }
            }
            if (orderedEqual) return false;
        }
        foundModifiers = immutableMap(next);
        rebuildModifierList();
        modifierVersion++;
        stateVersion++;
        return true;
    }

    public Map<String, List<RecipeModifier>> foundModifiers() {
        return foundModifiers;
    }

    public List<RecipeModifier> modifierList() {
        return flattenedModifiers;
    }

    public boolean replaceUpgradeBuses(List<UpgradeBusSnapshot> buses) {
        return replaceUpgradeBuses(buses, false);
    }

    public void refreshUpgradeBuses(List<UpgradeBusSnapshot> buses) {
        replaceUpgradeBuses(buses, true);
    }

    public List<UpgradeBusSnapshot> upgradeBuses() {
        return upgradeBuses;
    }

    public Set<BlockPos> upgradeBusPositions() {
        return upgradeBuses.stream().map(UpgradeBusSnapshot::position).collect(Collectors.toUnmodifiableSet());
    }

    public List<ItemStack> upgradeItems() {
        return copyStacks(upgradeItems);
    }

    public Map<Identifier, Long> upgradeModifierUnits() {
        return upgradeModifierUnits;
    }

    public long upgradeContentRevision() {
        return upgradeContentRevision;
    }

    public void setModifiersAllowed(boolean allowed) {
        if (modifiersAllowed == allowed) return;
        modifiersAllowed = allowed;
        rebuildModifierList();
        modifierVersion++;
        stateVersion++;
    }

    public boolean replaceLevels(Map<Identifier, MachineLevel> levels) {
        Map<Identifier, MachineLevel> next = new LinkedHashMap<>(levels == null ? Map.of() : levels);
        if (foundLevels.equals(next)) return false;
        foundLevels = immutableMap(next);
        levelVersion++;
        stateVersion++;
        return true;
    }

    public Map<Identifier, MachineLevel> foundLevels() {
        return foundLevels;
    }

    public boolean replaceLinkedPortPositions(Set<BlockPos> positions) {
        Set<BlockPos> next = Set.copyOf(positions == null ? Set.of() : positions);
        if (linkedPortPositions.equals(next)) return false;
        linkedPortPositions = next;
        stateVersion++;
        return true;
    }

    public Set<BlockPos> linkedPortPositions() {
        return linkedPortPositions;
    }

    public boolean hasLinkedPort(BlockPos position) {
        return position != null && linkedPortPositions.contains(position);
    }

    public boolean replaceModuleConnectionState(ModuleConnectionStatus status, int installedModuleCount) {
        if (status == null) status = ModuleConnectionStatus.disconnected();
        if (installedModuleCount < 0) throw new IllegalArgumentException("installedModuleCount must not be negative");
        if (moduleConnectionStatus.equals(status) && this.installedModuleCount == installedModuleCount) return false;
        moduleConnectionStatus = status;
        this.installedModuleCount = installedModuleCount;
        stateVersion++;
        return true;
    }

    public ModuleConnectionStatus moduleConnectionStatus() {
        return moduleConnectionStatus;
    }

    public int installedModuleCount() {
        return installedModuleCount;
    }

    public Optional<Identifier> connectedHostId() {
        return moduleConnectionStatus.connected()
                ? Optional.of(moduleConnectionStatus.connectedHostId())
                : Optional.empty();
    }

    public void markCapabilityPresentationChanged() {
        capabilityPresentationEpoch++;
    }

    public long maxParallelism(Machine machine) {
        if (machine == null || !machine.parallelizable()) {
            return 1L;
        }
        long max = 0L;
        for (ProcessingComponent component : components) {
            if (component.getContainer() instanceof ParallelControllerBlockEntity parallel) {
                int current = parallel.currentParallelism();
                max += current;
            }
        }
        long levelBonus = foundLevels.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)))
                .map(Map.Entry::getValue)
                .mapToLong(foundLevel -> foundLevel.modifier().parallelismBonus())
                .sum();
        long effective = Math.max(1L, max) + levelBonus;
        long bounded = Math.min(Long.MAX_VALUE, Math.max(1L, effective));
        return Math.min(Math.max(1L, machine.maxParallelism()), bounded);
    }

    public void clear() {
        replaceComponents(List.of());
        replaceModifiers(Map.of());
        replaceLevels(Map.of());
        replaceLinkedPortPositions(Set.of());
        replaceModuleConnectionState(ModuleConnectionStatus.disconnected(), 0);
        replaceUpgradeBuses(List.of());
    }

    private static CapabilityState capabilityStateFor(List<ProcessingComponent> components) {
        List<MachineCapability> result = new ArrayList<>();
        List<CapabilityIdentity> identities = new ArrayList<>();
        for (ProcessingComponent component : components) {
            if (component.getContainer() instanceof CapabilityHost host) {
                try {
                    for (MachineCapability capability : host.capabilities()) {
                        for (IOType direction : capability.view().directions().values()) {
                            identities.add(CapabilityIdentity.of(component.getPos(), capability, direction));
                        }
                        result.add(capability);
                    }
                } catch (RuntimeException ignored) {
                    // A partially initialized port must not invalidate the controller runtime snapshot.
                }
            }
        }
        return new CapabilityState(List.copyOf(result), List.copyOf(identities));
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private boolean replaceUpgradeBuses(List<UpgradeBusSnapshot> buses, boolean forceRefresh) {
        List<UpgradeBusSnapshot> next = new ArrayList<>();
        if (buses != null) next.addAll(buses);
        next.sort(Comparator.comparing(UpgradeBusSnapshot::position, ComponentRuntime::comparePositions));
        next = List.copyOf(next);
        if (!forceRefresh && upgradeBuses.equals(next)) return false;

        upgradeBuses = next;
        List<ItemStack> items = new ArrayList<>();
        Map<Identifier, Long> units = new LinkedHashMap<>();
        for (UpgradeBusSnapshot bus : next) {
            List<ItemStack> stacks = bus.stacks();
            for (int slot = 0; slot < stacks.size(); slot++) {
                ItemStack stack = stacks.get(slot);
                if (stack.isEmpty()) continue;
                items.add(stack.copy());
                Identifier modifierId = ModifierRegistry.modifierFor(stack);
                if (modifierId != null) units.merge(modifierId, (long) stack.getCount(), Long::sum);
            }
        }
        upgradeItems = List.copyOf(items);
        upgradeModifierUnits = immutableMap(units);
        upgradeModifiers = upgradeModifiers(units);
        upgradeContentRevision++;
        rebuildModifierList();
        modifierVersion++;
        stateVersion++;
        return true;
    }

    private List<RecipeModifier> upgradeModifiers(Map<Identifier, Long> units) {
        List<RecipeModifier> result = new ArrayList<>();
        for (Map.Entry<Identifier, Long> entry : units.entrySet()) {
            ModifierDefinition definition = ModifierRegistry.get(entry.getKey());
            if (definition == null) continue;
            for (RecipeModifier modifier : definition.modifiers()) {
                result.add(withUnitCount(modifier, entry.getValue()));
            }
        }
        return List.copyOf(result);
    }

    private void rebuildModifierList() {
        if (!modifiersAllowed) {
            flattenedModifiers = List.of();
            return;
        }
        List<RecipeModifier> modifiers = new ArrayList<>();
        foundModifiers.values().forEach(modifiers::addAll);
        modifiers.addAll(upgradeModifiers);
        flattenedModifiers = List.copyOf(modifiers);
    }

    private static RecipeModifier withUnitCount(RecipeModifier modifier, long count) {
        if (count <= 1L) return modifier;
        float value = switch (modifier.getOperation()) {
            case ADD, SUBTRACT -> (float) ((double) modifier.getModifier() * count);
            case MULTIPLY, DIVIDE -> power(modifier.getModifier(), count);
        };
        return new RecipeModifier(modifier.getTarget(), modifier.getIOTarget(), value,
                modifier.getOperation(), modifier.affectsChance());
    }

    private static float power(float base, long exponent) {
        float result = 1F;
        float factor = base;
        long remaining = exponent;
        while (remaining > 0L) {
            if ((remaining & 1L) != 0L) result *= factor;
            remaining >>>= 1;
            if (remaining > 0L) factor *= factor;
        }
        return result;
    }

    private static int comparePositions(BlockPos first, BlockPos second) {
        int x = Integer.compare(first.getX(), second.getX());
        if (x != 0) return x;
        int y = Integer.compare(first.getY(), second.getY());
        return y != 0 ? y : Integer.compare(first.getZ(), second.getZ());
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        return List.copyOf(stacks.stream().map(ItemStack::copy).toList());
    }

    /** Immutable content snapshot for one bound Upgrade Bus. */
    public record UpgradeBusSnapshot(BlockPos position, List<ItemStack> stacks) {
        public UpgradeBusSnapshot {
            position = position == null ? BlockPos.ZERO : position.immutable();
            stacks = copyStacks(stacks == null ? List.of() : stacks);
        }
    }

    private static ControllerRuntimeSnapshot.CapabilityPresentation resourcePresentation(
            MachineCapability capability, ResourceStorage<?> storage, IOType direction) {
        List<ControllerRuntimeSnapshot.StorageSlot> slots = new ArrayList<>(storage.size());
        long amount = 0L;
        long capacity = 0L;
        for (int slot = 0; slot < storage.size(); slot++) {
            Object resource = storage.resource(slot);
            long slotAmount = storage.amount(slot);
            long slotCapacity = storage.capacityResource(slot, resource);
            String resourceId = resource == null || (resource instanceof Resource empty && empty.isEmpty())
                    ? "" : String.valueOf(resource);
            slots.add(new ControllerRuntimeSnapshot.StorageSlot(resourceId, slotAmount, slotCapacity));
            amount = saturatedAdd(amount, slotAmount);
            capacity = saturatedAdd(capacity, slotCapacity);
        }
        return new ControllerRuntimeSnapshot.CapabilityPresentation(
                capability.type() == null ? null : capability.type().id(), direction, amount, capacity, slots);
    }

    private static long saturatedAdd(long current, long value) {
        return value > 0L && current > Long.MAX_VALUE - value ? Long.MAX_VALUE : current + value;
    }

    private record CapabilityState(List<MachineCapability> capabilities, List<CapabilityIdentity> identity) { }

    private record CapabilityIdentity(BlockPos componentPos, Identifier type, IOType ioType, List<String> tags,
        String storageType, Object storageIdentity) {
        private static CapabilityIdentity of(BlockPos componentPos, MachineCapability capability, IOType direction) {
            CapabilityStorage storage = CapabilityFactories.valueStorage(capability, CapabilityStorage.class);
            return new CapabilityIdentity(componentPos.immutable(), capability.type().id(), direction,
                    List.copyOf(capability.view().tags()), storage == null ? "" : storage.getClass().getName(),
                    storageIdentity(storage));
        }

        private static Object storageIdentity(CapabilityStorage storage) {
            return storage;
        }
    }
}
