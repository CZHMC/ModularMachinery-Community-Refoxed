package cn.howxu.mmcr.internal.async;

/**
 * A state mutation that must execute on the server thread.
 *
 * @author howxu <dev@howxu.cn>
 */
@FunctionalInterface
public interface MainThreadStep {
    Result execute();

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
}
