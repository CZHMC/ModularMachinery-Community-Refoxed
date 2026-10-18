package cn.howxu.mmcr;

import appeng.api.config.Actionable;
import appeng.api.stacks.GenericStack;
import appeng.crafting.execution.CraftingCpuLogic;
import cn.howxu.mmcr.api.recipe.ParallelTier;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.pattern.PatternBatchDetails;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import static cn.howxu.mmcr.PatternBatchNativeCpuFixture.DOUBLE_INT_MAX;
import static cn.howxu.mmcr.PatternBatchNativeCpuFixture.INT_MAX;

/** Actual asynchronous AE plans, native CPU dispatch, real factory ticks and returned long outputs.
 * No hand-made crafting plans, manual interface acceptance or synthetic recipe completion.
 * @author howxu <dev@howxu.cn>
 */
public final class PatternBatchLargeAmountGameTest {
    public void nativeCpuDispatchesIntMaxParallelismAsOneBatch(GameTestHelper helper) {
        completeHugeBatch(helper, "int_max", false, 1);
    }

    public void hugeBatchSettlesOutputsAboveIntRangeWithoutTruncation(GameTestHelper helper) {
        completeHugeBatch(helper, "double_item", false, 2);
    }

    public void hugeFluidBatchSettlesOutputsAboveIntRangeWithoutTruncation(GameTestHelper helper) {
        completeHugeBatch(helper, "double_fluid", true, 2);
    }

    private static void completeHugeBatch(GameTestHelper helper, String name, boolean fluid, int unitAmount) {
        var fixture = hugeFixture(helper, name, fluid, unitAmount);
        fixture.calculate().thenExecute(() -> {
            assertSingleHugePlan(helper, fixture);
            fixture.submit();
            int dispatched = fixture.dispatch();
            helper.assertTrue(dispatched == 1 && fixture.provider.attempts == 1
                            && fixture.provider.accepted == 1 && fixture.provider.lastInputs.get(fixture.input) == fixture.total,
                    "The real CPU makes exactly one standard push containing the complete long input; dispatched="
                            + dispatched + ", attempts=" + fixture.provider.attempts + ", accepted="
                            + fixture.provider.accepted + ", inputs=" + fixture.provider.lastInputs);
            helper.assertTrue(fixture.cpu().getStored(fixture.input) == 0
                            && fixture.cpu().getWaitingFor(fixture.output) == fixture.total
                            && fixture.cpu().getPendingOutputs(fixture.output) == 0
                            && fixture.power.charges == 1
                            && Math.abs(fixture.power.charged
                            - (double) fixture.total / fixture.input.getAmountPerOperation()) < 0.00001D,
                    "CPU input, waiting output and native AE power record the whole batch exactly once");
            helper.assertTrue(fixture.stored(fixture.output) == 0,
                    "A successful push does not synchronously manufacture its promised output");
        }).thenWaitUntil(() -> fixture.assertOneActiveBatch(INT_MAX))
                .thenWaitUntil(() -> fixture.assertSettled(1))
                .thenExecute(() -> {
                    assertStoredBalanceRoundTrips(helper, fixture);
                    helper.assertTrue(fixture.provider.attempts == 1,
                            "Exactly one standard push occurred across the entire real-tick lifecycle");
                    if (unitAmount == 2) {
                        helper.assertTrue(fixture.stored(fixture.output) == DOUBLE_INT_MAX,
                                "The actual completed two-unit output is 4_294_967_294L, not an int-clamped amount");
                    }
                }).thenSucceed();
    }

    public void hugeBatchFailsAtomicallyWhenOneUnitOrAePowerIsMissing(GameTestHelper helper) {
        atomicRejectionThenComplete(helper, "atomic_item", false);
    }

    public void hugeFluidBatchFailsAtomicallyWhenOneUnitOrAePowerIsMissing(GameTestHelper helper) {
        atomicRejectionThenComplete(helper, "atomic_fluid", true);
    }

    private static void atomicRejectionThenComplete(GameTestHelper helper, String name, boolean fluid) {
        var fixture = hugeFixture(helper, name, fluid, 2);
        fixture.calculate().thenExecute(() -> {
            assertSingleHugePlan(helper, fixture);
            fixture.submit();
            helper.assertTrue(fixture.cpu().getInventory().extract(fixture.input, 1, Actionable.MODULATE) == 1,
                    "One real CPU-held unit is temporarily removed after planning");
            helper.assertTrue(fixture.dispatch() == 0, "Missing just one unit rejects the entire giant batch");
            assertUnpaid(helper, fixture, fixture.total - 1);
            helper.assertTrue(fixture.provider.attempts == 0, "Incomplete counters never reach the provider");
            fixture.cpu().getInventory().insert(fixture.input, 1, Actionable.MODULATE);
            fixture.power.blocked = true;
            helper.assertTrue(fixture.dispatch() == 0, "Zero instantaneous AE power rejects the full native push");
            assertUnpaid(helper, fixture, fixture.total);
            helper.assertTrue(fixture.provider.attempts == 0, "Energy simulation rejects before provider payment");
            fixture.power.blocked = false;
            helper.assertTrue(fixture.dispatch() == 1 && fixture.provider.accepted == 1
                            && fixture.power.charges == 1,
                    "Restoring energy dispatches the unchanged full batch with exactly one native charge");
        }).thenWaitUntil(() -> fixture.assertOneActiveBatch(INT_MAX))
                .thenWaitUntil(() -> fixture.assertSettled(1)).thenSucceed();
    }

    public void cancelUndispatchedHugeBatchReturnsEveryCpuHeldUnit(GameTestHelper helper) {
        var fixture = hugeFixture(helper, "cancel", false, 2);
        fixture.calculate().thenExecute(() -> {
            assertSingleHugePlan(helper, fixture);
            fixture.submit();
            fixture.power.blocked = true;
            helper.assertTrue(fixture.dispatch() == 0, "Unpowered batch remains un-dispatched");
            assertUnpaid(helper, fixture, DOUBLE_INT_MAX);
            fixture.cpu().cancel();
        }).thenWaitUntil(() -> helper.assertTrue(!fixture.cpu().hasJob()
                        && fixture.cpu().getStored(fixture.input) == 0
                        && fixture.stored(fixture.input) == DOUBLE_INT_MAX && fixture.stored(fixture.output) == 0
                        && fixture.provider.accepted == 0 && fixture.provider.attempts == 0
                        && fixture.host.getLogic().getReturnInv().isEmpty()
                        && fixture.controller.runtimeSnapshot().factory().activeLaneCount() == 0,
                "AE cancellation returns every unpaid unit without starting a machine or manufacturing output"))
                .thenExecute(() -> assertStoredBalanceRoundTrips(helper, fixture)).thenSucceed();
    }

    /** CPU job NBT restoration, not a claim of the multi-chunk machine/interface reload matrix. */
    public void nativeCpuReloadKeepsFullAndRemainderThenSettles(GameTestHelper helper) {
        var fixture = new PatternBatchNativeCpuFixture(helper, "cpu_reload_tail", false, 1, 10_000,
                ParallelTier.PRO);
        fixture.calculate().thenExecute(() -> {
            helper.assertTrue(fixture.plan.patternTimes().size() == 2, "The actual 10000/256 plan has full and tail tasks");
            for (var entry : fixture.plan.patternTimes().entrySet()) {
                helper.assertTrue(entry.getKey() instanceof PatternBatchDetails,
                        "Both tasks use self-describing batch identities");
                long multiplier = ((PatternBatchDetails) entry.getKey()).data().multiplier();
                helper.assertTrue(multiplier == 256 && entry.getValue() == 39
                                || multiplier == 16 && entry.getValue() == 1,
                        "Plan preserves 256 x 39 plus 16 x 1 without overwriting either identity");
            }
            fixture.submit();
            var saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
            fixture.cpu().writeToNBT(saved);
            // Verify decoding independently before loading back into the real registered CPU logic.
            var restored = new CraftingCpuLogic(fixture.cluster());
            restored.readFromNBT(TagValueInput.create(ProblemReporter.DISCARDING,
                    helper.getLevel().registryAccess(), saved.buildResult()));
            helper.assertTrue(restored.hasJob() && restored.getStored(fixture.input) == 10_000
                            && restored.getPendingOutputs(fixture.output) == 10_000,
                    "Native task decoding restores both virtual definitions and all unpaid material");
            fixture.cpu().readFromNBT(TagValueInput.create(ProblemReporter.DISCARDING,
                    helper.getLevel().registryAccess(), saved.buildResult()));
            fixture.cpu().setJobSuspended(false);
        }).thenWaitUntil(() -> fixture.assertSettled(40))
                .thenExecute(() -> {
                    helper.assertTrue(fixture.provider.acceptedMultipliers.size() == 2
                                    && fixture.provider.acceptedMultipliers.getOrDefault(256L, 0L) == 39
                                    && fixture.provider.acceptedMultipliers.getOrDefault(16L, 0L) == 1,
                            "The reloaded real CPU dispatches exactly 39 full batches and one tail");
                    assertStoredBalanceRoundTrips(helper, fixture);
                }).thenSucceed();
    }

    private static PatternBatchNativeCpuFixture hugeFixture(GameTestHelper helper, String name, boolean fluid, int amount) {
        return new PatternBatchNativeCpuFixture(helper, name, fluid, amount, INT_MAX, ParallelTier.ULTIMATE);
    }

    private static void assertSingleHugePlan(GameTestHelper helper, PatternBatchNativeCpuFixture fixture) {
        helper.assertTrue(fixture.plan.patternTimes().size() == 1,
                "The real asynchronous plan has exactly one task identity");
        var task = fixture.plan.patternTimes().entrySet().iterator().next();
        helper.assertTrue(task.getKey() instanceof PatternBatchDetails && task.getValue() == 1,
                "The actual plan contains one batch execution, not 2.1G standard executions");
        var batch = (PatternBatchDetails) task.getKey();
        helper.assertTrue(batch.data().multiplier() == INT_MAX && batch.data().recipeCraftsPerPattern() == 1
                        && batch.data().recipeId().equals(fixture.recipeId)
                        && batch.getOutputs().stream().mapToLong(GenericStack::amount).sum() == fixture.total,
                "The scheduled task binds this recipe at exact 2_147_483_647 x 1 and preserves promised output");
    }

    private static void assertUnpaid(GameTestHelper helper, PatternBatchNativeCpuFixture fixture, long held) {
        helper.assertTrue(fixture.cpu().getStored(fixture.input) == held
                        && fixture.cpu().getPendingOutputs(fixture.output) == fixture.total
                        && fixture.cpu().getWaitingFor(fixture.output) == 0
                        && fixture.provider.accepted == 0 && fixture.power.charges == 0
                        && fixture.host.getLogic().getReturnInv().isEmpty()
                        && fixture.controller.runtimeSnapshot().factory().activeLaneCount() == 0,
                "Rejected dispatch preserves CPU materials, tasks, energy and factory ownership atomically");
    }

    private static void assertStoredBalanceRoundTrips(GameTestHelper helper, PatternBatchNativeCpuFixture fixture) {
        var saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        fixture.storage.writeToChildTag(saved, "balances");
        var restored = new PatternBatchNativeCpuFixture.LongStorage();
        restored.readFromChildTag(TagValueInput.create(ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess(), saved.buildResult()), "balances");
        helper.assertTrue(restored.getAvailableStacks().get(fixture.input) == fixture.stored(fixture.input)
                        && restored.getAvailableStacks().get(fixture.output) == fixture.stored(fixture.output),
                "Native AE key/count serialization preserves the actually settled or cancelled long balances");
    }
}
