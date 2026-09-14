package cn.howxu.mmcr.internal.recipe;

import cn.howxu.mmcr.api.capability.async.AsyncCapabilityOperation;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilityPlanner;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilityRequest;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilitySnapshot;
import cn.howxu.mmcr.api.capability.async.AsyncResourceAction;
import cn.howxu.mmcr.util.IOType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Plans prepared requirement requests without retaining live capability state.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class AsyncRequirementPlanner {
    public PlanResult plan(List<Requirement> requirements, List<Capability> capabilities) {
        Objects.requireNonNull(requirements, "requirements");
        Objects.requireNonNull(capabilities, "capabilities");
        List<AsyncCapabilitySnapshot> snapshots = capabilities.stream().map(Capability::snapshot).toList();
        List<PlannedOperation> operations = new ArrayList<>();
        List<Integer> mainThreadRequirements = new ArrayList<>();

        for (Requirement requirement : requirements) {
            List<AsyncCapabilitySnapshot> plannedSnapshots = new ArrayList<>(snapshots);
            List<PlannedOperation> plannedOperations = new ArrayList<>();
            long remaining = requirement.amount();
            for (AsyncCapabilityRequest request : requirement.requests()) {
                for (int capabilityIndex = 0; capabilityIndex < capabilities.size() && remaining > 0L; capabilityIndex++) {
                    Capability capability = capabilities.get(capabilityIndex);
                    if (requirement.direction() != null && !capability.directions().contains(requirement.direction())) {
                        continue;
                    }
                    AsyncCapabilityRequest remainingRequest = forAmount(request, remaining);
                    var operation = capability.planner().plan(plannedSnapshots.get(capabilityIndex), remainingRequest);
                    if (operation.isEmpty()) continue;
                    long plannedAmount = amount(operation.get());
                    if (plannedAmount <= 0L) continue;
                    plannedSnapshots.set(capabilityIndex, apply(plannedSnapshots.get(capabilityIndex), operation.get()));
                    plannedOperations.add(new PlannedOperation(requirement.index(), capabilityIndex, operation.get()));
                    remaining -= plannedAmount;
                }
            }
            if (remaining == 0L) {
                snapshots = plannedSnapshots;
                operations.addAll(plannedOperations);
            } else {
                mainThreadRequirements.add(requirement.index());
            }
        }
        return new PlanResult(operations, mainThreadRequirements);
    }

    private static AsyncCapabilityRequest forAmount(AsyncCapabilityRequest request, long amount) {
        if (request instanceof AsyncCapabilityRequest.Resource resource && resource.actions().size() == 1) {
            AsyncResourceAction action = resource.actions().getFirst();
            return new AsyncCapabilityRequest.Resource(resource.capabilityId(), resource.parallelism(), List.of(
                    new AsyncResourceAction(action.resource(), Math.min(action.amount(), amount), action.insert())));
        }
        if (request instanceof AsyncCapabilityRequest.Scalar scalar) {
            return new AsyncCapabilityRequest.Scalar(scalar.capabilityId(), scalar.parallelism(),
                    Math.min(scalar.amount(), amount), scalar.insert());
        }
        return request;
    }

    private static long amount(AsyncCapabilityOperation operation) {
        if (operation instanceof AsyncCapabilityOperation.Resource resource) return resource.amount();
        if (operation instanceof AsyncCapabilityOperation.Scalar scalar) return scalar.amount();
        return ((AsyncCapabilityOperation.Group) operation).operations().stream()
                .mapToLong(AsyncRequirementPlanner::amount).sum();
    }

    private static AsyncCapabilitySnapshot apply(AsyncCapabilitySnapshot snapshot, AsyncCapabilityOperation operation) {
        if (snapshot instanceof AsyncCapabilitySnapshot.Resource resource) {
            List<AsyncCapabilitySnapshot.ResourceSlot> slots = new ArrayList<>(resource.slots());
            applyResource(slots, operation);
            return new AsyncCapabilitySnapshot.Resource(resource.capabilityId(), slots);
        }
        if (snapshot instanceof AsyncCapabilitySnapshot.Scalar scalar
                && operation instanceof AsyncCapabilityOperation.Scalar value) {
            long amount = value.insert() ? scalar.amount() + value.amount() : scalar.amount() - value.amount();
            return new AsyncCapabilitySnapshot.Scalar(scalar.capabilityId(), amount, scalar.capacity());
        }
        throw new IllegalArgumentException("operation does not match capability snapshot");
    }

    private static void applyResource(List<AsyncCapabilitySnapshot.ResourceSlot> slots,
                                      AsyncCapabilityOperation operation) {
        if (operation instanceof AsyncCapabilityOperation.Group group) {
            group.operations().forEach(child -> applyResource(slots, child));
            return;
        }
        if (!(operation instanceof AsyncCapabilityOperation.Resource resource)) {
            throw new IllegalArgumentException("resource snapshot requires resource operations");
        }
        AsyncCapabilitySnapshot.ResourceSlot slot = slots.get(resource.slot());
        long amount = resource.insert() ? slot.amount() + resource.amount() : slot.amount() - resource.amount();
        slots.set(resource.slot(), new AsyncCapabilitySnapshot.ResourceSlot(
                amount == 0L ? java.util.Optional.empty() : java.util.Optional.of(resource.resource()), amount,
                slot.capacity()));
    }

    /** A pure planner and immutable snapshot captured for one capability. */
    public record Capability(AsyncCapabilityPlanner planner, AsyncCapabilitySnapshot snapshot, Set<IOType> directions) {
        public Capability {
            Objects.requireNonNull(planner, "planner");
            Objects.requireNonNull(snapshot, "snapshot");
            directions = Set.copyOf(Objects.requireNonNull(directions, "directions"));
        }

        public Capability(AsyncCapabilityPlanner planner, AsyncCapabilitySnapshot snapshot) {
            this(planner, snapshot, Set.of(IOType.INPUT, IOType.OUTPUT));
        }
    }

    /** A main-thread-prepared, worker-safe recipe requirement. */
    public record Requirement(int index, long amount, IOType direction, List<AsyncCapabilityRequest> requests) {
        public Requirement {
            if (index < 0 || amount <= 0L) throw new IllegalArgumentException("index must be non-negative and amount positive");
            requests = List.copyOf(Objects.requireNonNull(requests, "requests"));
            if (requests.isEmpty()) throw new IllegalArgumentException("requests must not be empty");
        }

        public Requirement(int index, long amount, List<AsyncCapabilityRequest> requests) {
            this(index, amount, null, requests);
        }
    }

    /** A logical operation assigned to the captured capability that produced it. */
    public record PlannedOperation(int requirementIndex, int capabilityIndex, AsyncCapabilityOperation operation) {
        public PlannedOperation {
            if (requirementIndex < 0 || capabilityIndex < 0) {
                throw new IllegalArgumentException("indexes must be non-negative");
            }
            Objects.requireNonNull(operation, "operation");
        }
    }

    /** Worker planning result with ordered fallback requirement indexes. */
    public record PlanResult(List<PlannedOperation> operations, List<Integer> mainThreadRequirements) {
        public PlanResult {
            operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
            mainThreadRequirements = List.copyOf(Objects.requireNonNull(mainThreadRequirements, "mainThreadRequirements"));
        }
    }

    /** A worker-safe planning descriptor prepared from live capabilities on the main thread. */
    public record PreparedPlan(List<Requirement> requirements, List<Capability> capabilities,
                               List<Integer> initialMainThreadRequirements) {
        public PreparedPlan {
            requirements = List.copyOf(Objects.requireNonNull(requirements, "requirements"));
            capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
            initialMainThreadRequirements = List.copyOf(Objects.requireNonNull(initialMainThreadRequirements,
                    "initialMainThreadRequirements"));
        }

        public PlanResult plan() {
            PlanResult result = new AsyncRequirementPlanner().plan(requirements, capabilities);
            TreeSet<Integer> fallback = new TreeSet<>(initialMainThreadRequirements);
            fallback.addAll(result.mainThreadRequirements());
            return new PlanResult(result.operations(), List.copyOf(fallback));
        }
    }
}
