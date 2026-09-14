package cn.howxu.mmcr.internal.async;

import java.util.function.Function;

/**
 * A worker continuation which can yield a step for the server thread.
 *
 * @author howxu <dev@howxu.cn>
 */
@FunctionalInterface
public interface AsyncContinuation {
    Yield advance(AsyncExecutionContext context);

    sealed interface Yield permits Yield.Complete, Yield.MainThread {
        record Complete() implements Yield {
        }

        record MainThread(MainThreadStep step, Function<MainThreadStep.Result, AsyncContinuation> resume) implements Yield {
        }

        static Complete complete() {
            return new Complete();
        }

        static MainThread mainThread(MainThreadStep step, Function<MainThreadStep.Result, AsyncContinuation> resume) {
            return new MainThread(step, resume);
        }
    }
}
