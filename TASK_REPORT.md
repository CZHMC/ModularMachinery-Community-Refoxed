# Task Report

## Task 4 P1

- Resource async planning now distributes each action across compatible slots in order and rejects incomplete actions without returning partial intents.
- Energy snapshots retain the storage transfer limit. Scalar plans apply the synchronous `transferLimit * parallelism` cap and split accepted amounts into committable operations.
- Energy group commits revalidate live storage in a nested transaction and roll back the whole group if any operation is rejected.
- Verified with focused async planning and requirement planner tests, plus `compileJava`.

## Task 5 Start Shared-IO Yield

- Shared-IO starts now enter `SharedIoCoordinator` through an explicit deferred main-thread continuation step.
- `SharedIoCoordinator.StartRequest` remains the only path that grants parallelism and commits `CraftingRuntime.start`; its completion resumes the waiting worker continuation.
- Start continuations retain the original structure, capability, modifier, component-state, catalog, recipe-pool, and lane ticket until coordinator validation.
- Focused async continuation and shared-IO coordinator tests passed, along with `compileJava`.
- Finish remains on its existing main-thread `SharedIoCoordinator.FinishRequest` path; this change does not migrate its protocol to a worker.

## Task 5 Review Fixes

- Start, tick, and finish now use pure-value async continuations keyed by the real lane id; factory lanes can progress independently in one tick.
- Shared worker intents retain their catalog version and re-enter `SharedIoCoordinator`; its existing LaneKey arbitration is the only path that revalidates and commits shared capability transactions.
- Behavior callbacks, capability facets, unsupported requirements, intent commits, and screen flushes are named `MainThreadStep` operations on the server thread.
- Focused continuation/coordinator tests and `compileJava` passed; full test and GameTest runs were intentionally not requested.

## Task 5 Latest P1/P2 Fixes

- The level tick now uses a bounded, progress-observed fence: it pumps yielded main-thread steps, resolves shared IO, and waits only for the next worker yield or completion. Chains with two consecutive no-progress passes are cancelled with a recorded failure.
- Shared IO round-robin cursors compare the full `LaneKey`, so factory lanes sharing a controller rotate fairly.
- Start, tick, and finish continuations yield lifecycle steps before shared-IO arbitration. Their callbacks and screen flushes run on the main thread, while start grants, tick intents, unsupported fallback plans, and finish output commits remain in coordinator transactions.
- Every lifecycle, intent, unsupported fallback, and shared request validates catalog, controller versions, domain, and recipe pool before a worker resume. Failed main steps terminate the chain without resource consumption.
- Verified with focused async continuation and shared-IO coordinator tests plus `compileJava`; full test and GameTest runs were intentionally not requested.

## Task 5 Review Follow-up

- A deferred shared-IO request now preserves its own tick fence while the coordinator pumps runnable work from later ticks, so one resource wait cannot starve other controllers or factory lanes.
- Async starts reserve their lane before worker submission, count toward factory activity and per-recipe limits immediately, and use the synchronous failure cleanup path when `beforeStart` cancels or fails.
- BEFORE_RECIPE, AFTER_INPUTS, and AFTER_RECIPE capability facets are separate main-thread continuation steps. Shared I/O only commits planned resource operations; later facets and progress publication occur after the worker resumes.
- Recipe tick and screen flush results revalidate catalog, controller versions, domain, and lane state before continuation resume.
- Verified with focused coordinator/continuation/factory tests and `compileJava`; full test and GameTest runs were intentionally not requested.
