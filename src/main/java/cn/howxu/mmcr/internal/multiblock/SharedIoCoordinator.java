package cn.howxu.mmcr.internal.multiblock;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilitySnapshot;
import cn.howxu.mmcr.api.capability.facet.AsyncPlanningFacet;
import cn.howxu.mmcr.internal.async.AsyncContinuation;
import cn.howxu.mmcr.internal.async.AsyncExecutionContext;
import cn.howxu.mmcr.internal.async.MachineAsyncCoordinator;
import cn.howxu.mmcr.internal.async.MainThreadStep;
import cn.howxu.mmcr.internal.recipe.AsyncRequirementPlanner;
import cn.howxu.mmcr.internal.runtime.MachineWorkMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongUnaryOperator;
import java.util.function.LongSupplier;

/**
 * Resolves shared multiblock IO requests once at the end of each server-level tick.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class SharedIoCoordinator {
    private static final Map<ServerLevel, SharedIoCoordinator> COORDINATORS = new WeakHashMap<>();
    private static final Comparator<Request> REQUEST_ORDER = Comparator.comparing(Request::laneKey);
    private final List<Request> pending = new ArrayList<>();
    private final Map<Long, LaneKey> startCursors = new HashMap<>();
    private final Map<Long, LaneKey> tickCursors = new HashMap<>();
    private final Map<Long, LaneKey> finishCursors = new HashMap<>();
    private final Map<Long, List<TickWorkEntry>> pendingTickWork = new HashMap<>();
    private final Map<Long, TickWorkset> submittedTickWork = new HashMap<>();
    private long nextTickWorksetId;

    private record DomainResolution(List<Request> unresolved, int progress) {
    }

    public static synchronized SharedIoCoordinator get(ServerLevel level) {
        return COORDINATORS.computeIfAbsent(level, ignored -> new SharedIoCoordinator());
    }

    public static synchronized void discard(ServerLevel level) {
        COORDINATORS.remove(level);
    }

    public void enqueue(Request request) {
        pending.add(request);
    }

    public boolean enqueueTickWork(ServerLevel level, StructureClaimRegistry.ResourceDomain domain,
                                   MachineAsyncCoordinator.TaskKey laneTaskKey,
                                   AsyncRequirementPlanner.PreparedPlan preparedPlan,
                                   Consumer<AsyncRequirementPlanner.PlanResult> committer, Runnable discard) {
        if (level == null || domain == null || laneTaskKey == null || preparedPlan == null
                || committer == null || discard == null) return false;
        pendingTickWork.computeIfAbsent(domain.id(), ignored -> new ArrayList<>())
                .add(new TickWorkEntry(level, laneTaskKey, preparedPlan, committer, discard));
        return true;
    }

    /** Discards every queued request owned by one controller. */
    public void cancel(BlockPos controllerPos) {
        pending.removeIf(request -> {
            if (!request.laneKey().controllerPos().equals(controllerPos)) return false;
            request.discard();
            return true;
        });
        pendingTickWork.values().forEach(entries -> entries.removeIf(entry -> {
            if (!entry.laneTaskKey().controllerPos().equals(controllerPos)) return false;
            entry.discardWork();
            return true;
        }));
        pendingTickWork.values().removeIf(List::isEmpty);
        submittedTickWork.values().removeIf(workset -> {
            if (workset.entries().stream().noneMatch(entry ->
                    entry.laneTaskKey().controllerPos().equals(controllerPos))) return false;
            TickWorkEntry first = workset.entries().getFirst();
            MachineAsyncCoordinator.get(first.level()).cancel(workset.taskKey());
            workset.entries().forEach(TickWorkEntry::discardWork);
            return true;
        });
    }

    public int resolve(ServerLevel level) {
        StructureClaimRegistry registry = StructureClaimRegistry.get(level);
        Map<StructureClaimRegistry.ResourceDomain, List<Request>> requestsByDomain = new HashMap<>();
        int resolved = 0;
        for (Request request : List.copyOf(pending)) {
            StructureClaimRegistry.ResourceDomain domain = registry.domainFor(request.laneKey().controllerPos());
            if (domain != null && request.domainId() == domain.id() && request.domainGeneration() == domain.generation()) {
                requestsByDomain.computeIfAbsent(domain, ignored -> new ArrayList<>()).add(request);
            } else {
                request.discard();
                resolved++;
            }
        }
        pending.clear();
        for (var entry : requestsByDomain.entrySet()) {
            DomainResolution result = resolveDomain(entry.getKey(), entry.getValue());
            resolved += result.progress();
            pending.addAll(result.unresolved());
        }
        return resolved;
    }

    public int resolve(StructureClaimRegistry.ResourceDomain domain) {
        List<Request> requests = pending.stream()
                .filter(request -> request.domainId() == domain.id()
                        && request.domainGeneration() == domain.generation())
                .toList();
        pending.removeAll(requests);
        DomainResolution result = resolveDomain(domain, requests);
        pending.addAll(result.unresolved());
        return result.progress();
    }

    public LaneKey nextStartLane(long domainId) {
        return startCursors.get(domainId);
    }

    private DomainResolution resolveDomain(StructureClaimRegistry.ResourceDomain domain, List<Request> requests) {
        List<Request> current = new ArrayList<>(requests.size());
        List<StartRequest> startRequests = new ArrayList<>();
        List<TickRequest> tickRequests = new ArrayList<>();
        List<FinishRequest> currentFinishRequests = new ArrayList<>();
        for (Request request : requests) {
            if (request.domainId() != domain.id()
                    || request.domainGeneration() != domain.generation()) {
                continue;
            }
            current.add(request);
            if (request instanceof StartRequest startRequest) {
                startRequests.add(startRequest);
            } else if (request instanceof TickRequest tickRequest) {
                tickRequests.add(tickRequest);
            } else if (request instanceof FinishRequest finishRequest) {
                currentFinishRequests.add(finishRequest);
            }
        }
        current.sort(REQUEST_ORDER);
        startRequests.sort(REQUEST_ORDER);
        tickRequests.sort(REQUEST_ORDER);
        currentFinishRequests.sort(REQUEST_ORDER);
        Set<Request> successful = Collections.newSetFromMap(new IdentityHashMap<>());
        resolveRoundRobin(startRequests, startCursors, domain.id(), successful);
        try (AsyncPlanningFacet.CaptureScope ignored = AsyncPlanningFacet.beginCaptureScope()) {
            resolveRoundRobin(tickRequests, tickCursors, domain.id(), successful);
        }
        submitTickWorkset(domain);
        Set<StartRequest> pendingStartsBeforeFinish = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Request request : pending) {
            if (request instanceof StartRequest startRequest
                    && startRequest.domainId() == domain.id()
                    && startRequest.domainGeneration() == domain.generation()) {
                pendingStartsBeforeFinish.add(startRequest);
            }
        }
        List<FinishRequest> finishRequests = new ArrayList<>(currentFinishRequests);
        finishRequests.addAll(takePendingFinishRequests(domain));
        resolveRoundRobin(finishRequests, finishCursors, domain.id(), successful);
        List<StartRequest> finishSpawnedStarts = takePendingStartRequests(domain, pendingStartsBeforeFinish);
        resolveRoundRobin(finishSpawnedStarts, startCursors, domain.id(), successful);
        List<Request> unresolved = new ArrayList<>(current.size());
        for (Request request : current) {
            if (!successful.contains(request)) unresolved.add(request);
        }
        for (FinishRequest request : finishRequests) {
            if (!current.contains(request) && !successful.contains(request)) unresolved.add(request);
        }
        for (StartRequest request : finishSpawnedStarts) {
            if (!successful.contains(request)) unresolved.add(request);
        }
        return new DomainResolution(unresolved, successful.size() + requests.size() - current.size());
    }

    private void submitTickWorkset(StructureClaimRegistry.ResourceDomain domain) {
        List<TickWorkEntry> entries = pendingTickWork.remove(domain.id());
        if (entries == null || entries.isEmpty()) return;
        long worksetId = ++nextTickWorksetId;
        TickWorkEntry first = entries.getFirst();
        MachineAsyncCoordinator.TaskKey laneKey = first.laneTaskKey();
        MachineAsyncCoordinator.TaskKey worksetKey = new MachineAsyncCoordinator.TaskKey(
                laneKey.controllerPos(), laneKey.gameTime(), laneKey.workMode(),
                "domain-tick/" + domain.id() + "/" + worksetId, laneKey.lifecycleEpoch());
        TickWorkset workset = new TickWorkset(worksetId, worksetKey, List.copyOf(entries));
        submitTickWorkset(MachineAsyncCoordinator.get(first.level()), workset, new TickWorksetContinuation(workset));
    }

    private void submitTickWorkset(MachineAsyncCoordinator coordinator, TickWorkset workset,
                                   AsyncContinuation continuation) {
        submittedTickWork.put(workset.id(), workset);
        MachineAsyncCoordinator.SubmissionResult submission = coordinator.submitDetailed(
                workset.taskKey(), continuation, this::executeTickWorksetStep,
                new MachineAsyncCoordinator.TaskHooks(
                        () -> submittedTickWork.get(workset.id()) == workset,
                        (ignored, outcome) -> {
                            if (!submittedTickWork.remove(workset.id(), workset)) return;
                            if (!(outcome instanceof MachineAsyncCoordinator.TaskOutcome.Succeeded)) {
                                workset.entries().forEach(TickWorkEntry::discardWork);
                            }
                        }));
        if (submission == MachineAsyncCoordinator.SubmissionResult.ACCEPTED) return;
        submittedTickWork.remove(workset.id(), workset);
        workset.entries().forEach(TickWorkEntry::discardWork);
    }

    private MainThreadStep.Result executeTickWorksetStep(MachineAsyncCoordinator.TaskKey ignored,
                                                          MainThreadStep step) {
        if (!(step instanceof MainThreadStep.TickWorkset(
                long worksetId, List<AsyncRequirementPlanner.PlanResult> intents
        ))) {
            return MainThreadStep.Result.failure(new IllegalArgumentException("Unexpected domain tick workset step"));
        }
        TickWorkset workset = submittedTickWork.get(worksetId);
        if (workset == null || workset.entries().size() != intents.size()) {
            return MainThreadStep.Result.failure(new IllegalStateException("Domain tick workset became stale"));
        }
        Throwable firstFailure = null;
        for (int index = 0; index < workset.entries().size(); index++) {
            TickWorkEntry entry = workset.entries().get(index);
            try {
                entry.commit(intents.get(index));
            } catch (Throwable throwable) {
                entry.failCommit();
                if (firstFailure == null) firstFailure = throwable;
                MMCR.LOG.error("Shared IO tick work commit failed: key={}", entry.laneTaskKey(), throwable);
            }
        }
        return firstFailure == null ? MainThreadStep.Result.success() : MainThreadStep.Result.failure(firstFailure);
    }

    private static List<AsyncRequirementPlanner.PlanResult> planTickWorkset(TickWorkset workset) {
        return planTickWorksetForTesting(workset.entries().stream().map(TickWorkEntry::preparedPlan).toList());
    }

    static List<AsyncRequirementPlanner.PlanResult> planTickWorksetForTesting(
            List<AsyncRequirementPlanner.PreparedPlan> preparedPlans) {
        IdentityHashMap<AsyncCapabilitySnapshot, AsyncCapabilitySnapshot> virtualSnapshots = new IdentityHashMap<>();
        List<AsyncRequirementPlanner.PlanResult> results = new ArrayList<>(preparedPlans.size());
        for (AsyncRequirementPlanner.PreparedPlan prepared : preparedPlans) {
            List<AsyncRequirementPlanner.Capability> capabilities = prepared.capabilities().stream()
                    .map(capability -> new AsyncRequirementPlanner.Capability(capability.planner(),
                            virtualSnapshots.getOrDefault(capability.snapshot(), capability.snapshot()),
                            capability.directions()))
                    .toList();
            AsyncRequirementPlanner.PlanResult result = new AsyncRequirementPlanner.PreparedPlan(
                    prepared.requirements(), capabilities, prepared.initialMainThreadRequirements()).plan();
            results.add(result);
            if (!result.mainThreadRequirements().isEmpty()) continue;
            for (AsyncRequirementPlanner.PlannedOperation operation : result.operations()) {
                AsyncCapabilitySnapshot identity = prepared.capabilities().get(operation.capabilityIndex()).snapshot();
                AsyncCapabilitySnapshot current = virtualSnapshots.getOrDefault(identity, identity);
                virtualSnapshots.put(identity, AsyncRequirementPlanner.apply(current, operation.operation()));
            }
        }
        return List.copyOf(results);
    }

    private static final class TickWorkEntry {
        private final ServerLevel level;
        private final MachineAsyncCoordinator.TaskKey laneTaskKey;
        private final AsyncRequirementPlanner.PreparedPlan preparedPlan;
        private final Consumer<AsyncRequirementPlanner.PlanResult> committer;
        private final Runnable discard;
        private final AtomicBoolean terminal = new AtomicBoolean();

        private TickWorkEntry(ServerLevel level, MachineAsyncCoordinator.TaskKey laneTaskKey,
                              AsyncRequirementPlanner.PreparedPlan preparedPlan,
                              Consumer<AsyncRequirementPlanner.PlanResult> committer, Runnable discard) {
            this.level = level;
            this.laneTaskKey = laneTaskKey;
            this.preparedPlan = preparedPlan;
            this.committer = committer;
            this.discard = discard;
        }

        private ServerLevel level() { return level; }
        private MachineAsyncCoordinator.TaskKey laneTaskKey() { return laneTaskKey; }
        private AsyncRequirementPlanner.PreparedPlan preparedPlan() { return preparedPlan; }

        private synchronized void commit(AsyncRequirementPlanner.PlanResult intent) {
            if (terminal.get()) return;
            committer.accept(intent);
            terminal.set(true);
        }

        private synchronized void failCommit() {
            if (!terminal.compareAndSet(false, true)) return;
            discard.run();
        }

        private synchronized void discardWork() {
            if (!terminal.compareAndSet(false, true)) return;
            discard.run();
        }
    }

    private record TickWorkset(long id, MachineAsyncCoordinator.TaskKey taskKey, List<TickWorkEntry> entries) { }

    void submitTickWorksetForTesting(MachineAsyncCoordinator coordinator, AsyncContinuation continuation,
                                     int entryCount, Runnable discard) {
        List<Consumer<AsyncRequirementPlanner.PlanResult>> committers = new ArrayList<>(entryCount);
        List<Runnable> discards = new ArrayList<>(entryCount);
        for (int index = 0; index < entryCount; index++) {
            committers.add(ignored -> { });
            discards.add(discard);
        }
        submitTickWorksetForTesting(coordinator, continuation, committers, discards);
    }

    void submitTickWorksetForTesting(MachineAsyncCoordinator coordinator,
                                     List<Consumer<AsyncRequirementPlanner.PlanResult>> committers,
                                     List<Runnable> discards) {
        submitTickWorksetForTesting(coordinator, null, committers, discards);
    }

    private void submitTickWorksetForTesting(MachineAsyncCoordinator coordinator,
                                             AsyncContinuation continuation,
                                             List<Consumer<AsyncRequirementPlanner.PlanResult>> committers,
                                             List<Runnable> discards) {
        if (committers.size() != discards.size()) throw new IllegalArgumentException("Mismatched test workset entries");
        long worksetId = ++nextTickWorksetId;
        MachineAsyncCoordinator.TaskKey key = new MachineAsyncCoordinator.TaskKey(
                BlockPos.ZERO, worksetId, MachineWorkMode.ASYNC,
                "test-domain-tick/" + worksetId);
        AsyncRequirementPlanner.PreparedPlan plan = new AsyncRequirementPlanner.PreparedPlan(List.of(), List.of(), List.of());
        List<TickWorkEntry> entries = new ArrayList<>(committers.size());
        for (int index = 0; index < committers.size(); index++) {
            entries.add(new TickWorkEntry(null, key, plan, committers.get(index), discards.get(index)));
        }
        TickWorkset workset = new TickWorkset(worksetId, key, List.copyOf(entries));
        submitTickWorkset(coordinator, workset,
                continuation == null ? new TickWorksetContinuation(workset) : continuation);
    }

    int submittedTickWorkCountForTesting() {
        return submittedTickWork.size();
    }

    private record TickWorksetContinuation(TickWorkset workset) implements AsyncContinuation {
        @Override
        public Yield advance(AsyncExecutionContext context) {
            List<AsyncRequirementPlanner.PlanResult> intents = planTickWorkset(workset);
            return Yield.mainThread(new MainThreadStep.TickWorkset(workset.id(), intents),
                    ignored -> ignoredContext -> Yield.complete());
        }
    }

    private List<FinishRequest> takePendingFinishRequests(StructureClaimRegistry.ResourceDomain domain) {
        List<FinishRequest> result = new ArrayList<>();
        for (Request request : pending) {
            if (request instanceof FinishRequest finishRequest
                    && finishRequest.domainId() == domain.id()
                    && finishRequest.domainGeneration() == domain.generation()) {
                result.add(finishRequest);
            }
        }
        pending.removeAll(result);
        return result;
    }

    private List<StartRequest> takePendingStartRequests(StructureClaimRegistry.ResourceDomain domain, Set<StartRequest> excluded) {
        List<StartRequest> matching = new ArrayList<>();
        for (Request request : pending) {
            if (request instanceof StartRequest startRequest
                    && startRequest.domainId() == domain.id()
                    && startRequest.domainGeneration() == domain.generation()
                    && !excluded.contains(startRequest)) {
                matching.add(startRequest);
            }
        }
        pending.removeAll(matching);
        matching.sort(REQUEST_ORDER);
        return matching;
    }

    private <T extends Request> void resolveRoundRobin(List<T> requests, Map<Long, LaneKey> cursors, long domainId, Set<Request> successful) {
        if (requests.isEmpty()) return;
        LaneKey cursor = cursors.get(domainId);
        int start = 0;
        if (cursor != null) {
            while (start < requests.size()
                    && requests.get(start).laneKey().compareTo(cursor) <= 0) start++;
            if (start == requests.size()) start = 0;
        }
        for (int offset = 0; offset < requests.size(); offset++) {
            T request = requests.get((start + offset) % requests.size());
            if (!request.isStillValid()) {
                successful.add(request);
                continue;
            }
            // Validation and commit are deliberately adjacent; transactions must not repeat request validation.
            if (request.tryCommit()) {
                request.onCommitted();
                cursors.put(domainId, request.laneKey());
                successful.add(request);
            }
        }
    }

    public sealed interface Request permits StartRequest, TickRequest, FinishRequest {
        long domainId();

        long domainGeneration();

        LaneKey laneKey();

        long controllerStructureVersion();

        long controllerStateVersion();

        LongSupplier controllerStructureVersionSupplier();

        LongSupplier controllerStateVersionSupplier();

        BooleanSupplier validator();

        default void onCommitted() {
        }

        default boolean isStillValid() {
            boolean versionsCurrent = controllerStructureVersion() == controllerStructureVersionSupplier().getAsLong()
                    && controllerStateVersion() == controllerStateVersionSupplier().getAsLong();
            boolean runtimeValid = validator().getAsBoolean();
            return versionsCurrent && runtimeValid;
        }

        default void discard() {
            validator().getAsBoolean();
        }

        boolean tryCommit();
    }

    public record LaneKey(BlockPos controllerPos, String laneId) implements Comparable<LaneKey> {
        public LaneKey {
            controllerPos = controllerPos.immutable();
        }

        @Override
        public int compareTo(LaneKey other) {
            int result = Integer.compare(controllerPos.getX(), other.controllerPos.getX());
            if (result != 0) return result;
            result = Integer.compare(controllerPos.getY(), other.controllerPos.getY());
            if (result != 0) return result;
            result = Integer.compare(controllerPos.getZ(), other.controllerPos.getZ());
            return result != 0 ? result : laneId.compareTo(other.laneId);
        }
    }

    public record StartRequest(StructureClaimRegistry.ResourceDomain domain, LaneKey laneKey,
                               long controllerStructureVersion, long controllerStateVersion,
                               long maximumParallelism, LongUnaryOperator transaction, LongConsumer committer,
                               BooleanSupplier validator, LongSupplier controllerStructureVersionSupplier,
                               LongSupplier controllerStateVersionSupplier, long catalogVersion,
                               LongSupplier catalogVersionSupplier, Runnable commitNotifier) implements Request {

        public StartRequest(StructureClaimRegistry.ResourceDomain domain, LaneKey laneKey,
                            long controllerStructureVersion, long controllerStateVersion,
                              long maximumParallelism, LongUnaryOperator transaction, LongConsumer committer,
                             BooleanSupplier validator, LongSupplier controllerStructureVersionSupplier,
                             LongSupplier controllerStateVersionSupplier) {
            this(domain, laneKey, controllerStructureVersion, controllerStateVersion, maximumParallelism,
                    transaction, committer, validator, controllerStructureVersionSupplier,
                    controllerStateVersionSupplier, Long.MIN_VALUE, () -> Long.MIN_VALUE, () -> { });
        }

        public StartRequest(StructureClaimRegistry.ResourceDomain domain, LaneKey laneKey,
                            long controllerStructureVersion, long controllerStateVersion,
                             long maximumParallelism, LongUnaryOperator transaction, LongConsumer committer,
                            BooleanSupplier validator, LongSupplier controllerStructureVersionSupplier,
                            LongSupplier controllerStateVersionSupplier, long catalogVersion,
                            LongSupplier catalogVersionSupplier) {
            this(domain, laneKey, controllerStructureVersion, controllerStateVersion, maximumParallelism,
                    transaction, committer, validator, controllerStructureVersionSupplier,
                    controllerStateVersionSupplier, catalogVersion, catalogVersionSupplier, () -> { });
        }

        @Override public long domainId() { return domain.id(); }
        @Override public long domainGeneration() { return domain.generation(); }
        @Override public boolean isStillValid() {
            if (catalogVersion != catalogVersionSupplier.getAsLong()) {
                discard();
                return false;
            }
            return Request.super.isStillValid();
        }
        @Override public boolean tryCommit() {
            long granted = transaction.applyAsLong(maximumParallelism);
            if (granted <= 0L) return false;
            committer.accept(granted);
            return true;
        }

        @Override public void onCommitted() { commitNotifier.run(); }
    }

    public record TickRequest(StructureClaimRegistry.ResourceDomain domain, LaneKey laneKey,
                               long controllerStructureVersion, long controllerStateVersion,
                               BooleanSupplier transaction, BooleanSupplier validator,
                               LongSupplier controllerStructureVersionSupplier,
                               LongSupplier controllerStateVersionSupplier, long catalogVersion,
                               LongSupplier catalogVersionSupplier, Runnable commitNotifier) implements Request {
        public TickRequest(StructureClaimRegistry.ResourceDomain domain, LaneKey laneKey,
                           long controllerStructureVersion, long controllerStateVersion,
                           BooleanSupplier transaction, BooleanSupplier validator,
                           LongSupplier controllerStructureVersionSupplier,
                           LongSupplier controllerStateVersionSupplier) {
            this(domain, laneKey, controllerStructureVersion, controllerStateVersion, transaction, validator,
                    controllerStructureVersionSupplier, controllerStateVersionSupplier,
                    Long.MIN_VALUE, () -> Long.MIN_VALUE, () -> { });
        }

        public TickRequest(StructureClaimRegistry.ResourceDomain domain, LaneKey laneKey,
                           long controllerStructureVersion, long controllerStateVersion,
                           BooleanSupplier transaction, BooleanSupplier validator,
                           LongSupplier controllerStructureVersionSupplier,
                           LongSupplier controllerStateVersionSupplier, Runnable commitNotifier) {
            this(domain, laneKey, controllerStructureVersion, controllerStateVersion, transaction, validator,
                    controllerStructureVersionSupplier, controllerStateVersionSupplier,
                    Long.MIN_VALUE, () -> Long.MIN_VALUE, commitNotifier);
        }

        @Override public long domainId() { return domain.id(); }
        @Override public long domainGeneration() { return domain.generation(); }
        @Override public boolean isStillValid() {
            if (catalogVersion != catalogVersionSupplier.getAsLong()) {
                discard();
                return false;
            }
            return Request.super.isStillValid();
        }
        @Override public boolean tryCommit() { return transaction.getAsBoolean(); }
        @Override public void onCommitted() { commitNotifier.run(); }
    }

    public record FinishRequest(StructureClaimRegistry.ResourceDomain domain, LaneKey laneKey,
                                 long controllerStructureVersion, long controllerStateVersion,
                                 BooleanSupplier transaction, BooleanSupplier validator,
                                 LongSupplier controllerStructureVersionSupplier,
                                 LongSupplier controllerStateVersionSupplier, long catalogVersion,
                                 LongSupplier catalogVersionSupplier, Runnable commitNotifier) implements Request {
        public FinishRequest(StructureClaimRegistry.ResourceDomain domain, LaneKey laneKey,
                             long controllerStructureVersion, long controllerStateVersion,
                             BooleanSupplier transaction, BooleanSupplier validator,
                             LongSupplier controllerStructureVersionSupplier,
                             LongSupplier controllerStateVersionSupplier) {
            this(domain, laneKey, controllerStructureVersion, controllerStateVersion, transaction, validator,
                    controllerStructureVersionSupplier, controllerStateVersionSupplier,
                    Long.MIN_VALUE, () -> Long.MIN_VALUE, () -> { });
        }

        public FinishRequest(StructureClaimRegistry.ResourceDomain domain, LaneKey laneKey,
                             long controllerStructureVersion, long controllerStateVersion,
                             BooleanSupplier transaction, BooleanSupplier validator,
                             LongSupplier controllerStructureVersionSupplier,
                             LongSupplier controllerStateVersionSupplier, Runnable commitNotifier) {
            this(domain, laneKey, controllerStructureVersion, controllerStateVersion, transaction, validator,
                    controllerStructureVersionSupplier, controllerStateVersionSupplier,
                    Long.MIN_VALUE, () -> Long.MIN_VALUE, commitNotifier);
        }

        @Override public long domainId() { return domain.id(); }
        @Override public long domainGeneration() { return domain.generation(); }
        @Override public boolean isStillValid() {
            if (catalogVersion != catalogVersionSupplier.getAsLong()) {
                discard();
                return false;
            }
            return Request.super.isStillValid();
        }
        @Override public boolean tryCommit() { return transaction.getAsBoolean(); }
        @Override public void onCommitted() { commitNotifier.run(); }
    }
}
