package cn.howxu.mmcr.internal.async;

import cn.howxu.mmcr.internal.recipe.AsyncRequirementPlanner;

/**
 * A state mutation that must execute on the server thread.
 *
 * @author howxu <dev@howxu.cn>
 */
@FunctionalInterface
public interface MainThreadStep {
    Result execute();

    default Kind kind() {
        return Kind.GENERIC;
    }

    enum Kind {
        GENERIC,
        BEFORE_START,
        RECIPE_TICK,
        BEFORE_FINISH,
        CAPABILITY_TICK,
        UNSUPPORTED_REQUIREMENT,
        INTENT_COMMIT,
        SCREEN_TEXT_FLUSH
    }

    sealed interface Result permits Result.Success, Result.Failure, Result.Pending, Result.Value {
        record Success() implements Result {
        }

        record Failure(Throwable cause) implements Result {
        }

        /** The step registered work that will resume its continuation later. */
        record Pending() implements Result {
        }

        record Value(Object value) implements Result {
        }

        static Success success() {
            return new Success();
        }

        static Failure failure(Throwable cause) {
            return new Failure(cause);
        }

        static Pending pending() {
            return new Pending();
        }

        static Value value(Object value) {
            return new Value(value);
        }
    }

    record TestStep(Runnable action) implements MainThreadStep {
        @Override
        public Result execute() {
            action.run();
            return Result.success();
        }
    }

    record Named(Kind kind, Runnable action) implements MainThreadStep {
        @Override
        public Result execute() {
            action.run();
            return Result.success();
        }
    }

    record Deferred(Kind kind, Runnable action) implements MainThreadStep {
        @Override
        public Result execute() {
            action.run();
            return Result.pending();
        }
    }

    /** Pure lifecycle boundary whose owner performs the matching server-thread callback. */
    record Lifecycle(Kind kind, String laneId, long catalogVersion) implements MainThreadStep {
        @Override
        public Result execute() {
            return Result.success();
        }
    }

    /** Identifies the unsupported requirement that the owning runtime must handle on the server thread. */
    record UnsupportedRequirement(int requirementIndex, long catalogVersion,
                                  AsyncRequirementPlanner.PlanResult intent) implements MainThreadStep {
        @Override
        public Kind kind() {
            return Kind.UNSUPPORTED_REQUIREMENT;
        }

        @Override
        public Result execute() {
            return Result.success();
        }
    }

    /** Pure worker result that a server-thread owner routes to shared-IO arbitration. */
    record IntentCommit(String laneId, long catalogVersion, AsyncRequirementPlanner.PlanResult intent) implements MainThreadStep {
        @Override
        public Kind kind() {
            return Kind.INTENT_COMMIT;
        }

        @Override
        public Result execute() {
            return Result.success();
        }
    }

    /** Pure lifecycle request whose owning main-thread coordinator performs the live operation. */
    record SharedIoRequest(Kind kind, String laneId, long catalogVersion) implements MainThreadStep {
        @Override
        public Result execute() {
            return Result.pending();
        }
    }
}
