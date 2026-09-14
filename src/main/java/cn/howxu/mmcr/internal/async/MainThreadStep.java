package cn.howxu.mmcr.internal.async;

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

    sealed interface Result permits Result.Success, Result.Failure {
        record Success() implements Result {
        }

        record Failure(Throwable cause) implements Result {
        }

        static Success success() {
            return new Success();
        }

        static Failure failure(Throwable cause) {
            return new Failure(cause);
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
}
