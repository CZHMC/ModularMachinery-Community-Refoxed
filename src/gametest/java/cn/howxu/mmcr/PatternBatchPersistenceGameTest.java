package cn.howxu.mmcr;

import appeng.api.stacks.AEItemKey;
import cn.howxu.mmcr.api.recipe.ParallelTier;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.pattern.PatternBatchDetails;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.ValueInput;

import java.util.Locale;

import static cn.howxu.mmcr.PatternBatchNativeCpuFixture.INT_MAX;

/** All paid owners are saved/recreated together; only real level ticks advance recipe execution.
 * Cross-chunk tests require a dedicated, reserved 24x4x8 GameTest template, rotation NONE.
 * @author howxu <dev@howxu.cn>
 */
public final class PatternBatchPersistenceGameTest {
    private static final BlockPos REMOTE_CPU = new BlockPos(20, 0, 3);
    private static final AEItemKey BLOCKER = AEItemKey.of(Items.COBBLESTONE);

    public void reloadCpuMachineAndInterfaceDuringPaidProcessing(GameTestHelper helper) {
        reloadPaidOwners(helper, SavePoint.PROCESSING);
    }

    public void reloadCpuMachineAndInterfaceAfterFinalTickPaid(GameTestHelper helper) {
        reloadPaidOwners(helper, SavePoint.FINAL_TICK_PAID);
    }

    public void reloadCpuMachineAndInterfaceWithOutputWaitingForNetwork(GameTestHelper helper) {
        reloadPaidOwners(helper, SavePoint.OUTPUT_WAITING);
    }

    private static void reloadPaidOwners(GameTestHelper helper, SavePoint point) {
        var fixture = new PatternBatchNativeCpuFixture(helper, "reload_" + point.name().toLowerCase(Locale.ROOT),
                false, 2, INT_MAX, ParallelTier.ULTIMATE, REMOTE_CPU, true);
        long[] savedAttempts = new long[1];
        long[] savedEnergy = new long[1];
        var sequence = fixture.calculate().thenExecute(() -> {
            assertHugeCalculatedPlan(helper, fixture);
            fixture.submit();
            fixture.addSpareMaterial();
            helper.assertTrue(fixture.dispatch() == 1 && fixture.provider.accepted == 1,
                    "Native CPU pays and starts the actual 2.1G batch before persistence testing");
            if (point == SavePoint.FINAL_TICK_PAID) fixture.fillReturnSlots(BLOCKER);
            if (point == SavePoint.OUTPUT_WAITING) fixture.disconnectHost();
        }).thenWaitUntil(() -> {
            ValueInput lane = patternLane(fixture);
            ValueInput payment = lane.childOrEmpty("execution_commit");
            ValueInput recipe = lane.childOrEmpty("recipe");
            if (point == SavePoint.PROCESSING) {
                helper.assertTrue(lane.getBooleanOr("active", false) && recipe.getIntOr("tick", 0) > 0
                                && recipe.getIntOr("tick", 40) < 39 && payment.getLongOr("paid_attempts", 0) > 0,
                        "Real ticks reach paid processing, not a prepared or synthesized runtime");
            } else if (point == SavePoint.FINAL_TICK_PAID) {
                helper.assertTrue(lane.getBooleanOr("active", false) && recipe.getBooleanOr("finishPending", false)
                                && payment.getBooleanOr("final_tick_paid", false)
                                && !payment.getStringOr("phase", "").equals("TERMINAL")
                                && fixture.returnAmount(fixture.output) == 0 && fixture.stored(fixture.output) == 0,
                        "Real final tick is paid while unrelated full return slots prevent output commit");
            } else {
                helper.assertTrue(!lane.getBooleanOr("active", true)
                                && fixture.returnAmount(fixture.output) == fixture.total
                                && fixture.cpu().getWaitingFor(fixture.output) == fixture.total
                                && fixture.stored(fixture.output) == 0,
                        "Actual recipe output is committed locally while the disconnected CPU still awaits it");
            }
        }).thenExecute(() -> {
            ValueInput lane = patternLane(fixture);
            helper.assertTrue(lane.getBooleanOr("requires_pattern_request", false),
                    "Saved runtime explicitly requires another standard pattern request before a new execution");
            savedAttempts[0] = lane.childOrEmpty("execution_commit").getLongOr("paid_attempts", 0);
            savedEnergy[0] = fixture.energyHatch().energyStorage().getAmountAsLong();
            helper.assertTrue(fixture.cpu().hasJob() && fixture.cpu().getStored(fixture.input) == 0
                            && fixture.cpu().getWaitingFor(fixture.output) == fixture.total
                            && fixture.stored(fixture.input) == fixture.total,
                    "CPU, machine, interface and spare network material have distinct real owners at save time");
            fixture.rebuildPaidOwners();
            ValueInput restoredLane = patternLane(fixture);
            helper.assertTrue((point == SavePoint.OUTPUT_WAITING
                            ? !restoredLane.getBooleanOr("active", true)
                            : restoredLane.childOrEmpty("execution_commit").getLongOr("paid_attempts", 0) == savedAttempts[0])
                            && fixture.energyHatch().energyStorage().getAmountAsLong() == savedEnergy[0]
                            && restoredLane.getBooleanOr("requires_pattern_request", false),
                    "New controller and FE owner restore the same payment ledger, energy and source restriction");
            if (point == SavePoint.FINAL_TICK_PAID) {
                helper.assertTrue(restoredLane.childOrEmpty("execution_commit").getBooleanOr("final_tick_paid", false)
                                && restoredLane.childOrEmpty("recipe").getBooleanOr("finishPending", false),
                        "The new machine preserves final-tick payment and pending output, not a fresh last tick");
            }
            if (point == SavePoint.OUTPUT_WAITING) {
                helper.assertTrue(fixture.returnAmount(fixture.output) == fixture.total,
                        "The recreated interface, not a fabricated CPU insertion, owns the complete long output");
            }
        }).thenWaitUntil(() -> helper.assertTrue(fixture.host.getMainNode().getNode() != null
                        && fixture.energy.getMainNode().getNode() != null
                        && fixture.cpuBlock().getMainNode().getNode() != null && fixture.cluster() != null,
                "Recreated owners initialize their native nodes and rebuild a native CPU cluster"))
                .thenExecute(() -> {
                    helper.assertTrue(fixture.cpu().hasJob() && fixture.cpu().getWaitingFor(fixture.output) == fixture.total
                                    && fixture.cpu().getStored(fixture.input) == 0,
                            "Native core NBT restores the real CPU waiting job before reconnecting the recreated owners");
                    fixture.connectNetwork(point != SavePoint.OUTPUT_WAITING);
                })
                .thenWaitUntil(() -> helper.assertTrue(fixture.cluster().isActive()
                                && fixture.controller.structureSnapshot().formed(),
                        "The recreated CPU restores actual waiting tasks while the recreated machine reforms"));
        if (point == SavePoint.FINAL_TICK_PAID) {
            sequence.thenExecuteFor(5, () -> {
                helper.assertTrue(fixture.energyHatch().energyStorage().getAmountAsLong() == savedEnergy[0]
                                && fixture.returnAmount(fixture.output) == 0
                                && patternLane(fixture).childOrEmpty("execution_commit").getLongOr("paid_attempts", 0) == savedAttempts[0],
                        "Blocked finish retries across actual ticks do not pay the final tick again");
            }).thenExecute(() -> fixture.clearReturnBlocker(BLOCKER));
        }
        if (point == SavePoint.OUTPUT_WAITING) {
            sequence.thenExecuteFor(5, () -> helper.assertTrue(fixture.returnAmount(fixture.output) == fixture.total
                            && fixture.stored(fixture.output) == 0
                            && fixture.energyHatch().energyStorage().getAmountAsLong() == savedEnergy[0],
                    "Rebuilt disconnected output remains single-owned and is not produced or paid again"))
                    .thenExecute(fixture::reconnectHost);
        }
        sequence.thenWaitUntil(() -> fixture.assertSettled(1))
                .thenExecute(() -> {
                    helper.assertTrue(fixture.energyHatch().energyStorage().getAmountAsLong() == 0
                                    && fixture.provider.accepted == 1 && fixture.power.charges == 1,
                             "Start and forty real ticks consume the original FE budget and only one CPU batch was paid");
                    fixture.energyHatch().energyStorage().setAmount(fixture.feBudget);
                }).thenExecuteFor(85, () -> assertNoUnorderedRestart(helper, fixture)).thenSucceed();
    }

    public void hugeBatchRejectsMissingMachineFeWithoutTakingCpuInputs(GameTestHelper helper) {
        blockedAdmission(helper, true);
    }

    public void hugeBatchRejectsFullOutputWithoutTakingCpuInputs(GameTestHelper helper) {
        blockedAdmission(helper, false);
    }

    private static void blockedAdmission(GameTestHelper helper, boolean missingFe) {
        var fixture = new PatternBatchNativeCpuFixture(helper, missingFe ? "no_machine_fe" : "full_return",
                false, 2, INT_MAX, ParallelTier.ULTIMATE, PatternBatchNativeCpuFixture.CPU, true);
        fixture.calculate().thenExecute(() -> {
            assertHugeCalculatedPlan(helper, fixture);
            fixture.submit();
            if (missingFe) fixture.energyHatch().energyStorage().setAmount(0);
            else fixture.fillReturnSlots(BLOCKER);
            long feBefore = fixture.energyHatch().energyStorage().getAmountAsLong();
            helper.assertTrue(fixture.dispatch() == 0 && fixture.cpu().getStored(fixture.input) == fixture.total
                            && fixture.cpu().getWaitingFor(fixture.output) == 0
                            && fixture.cpu().getPendingOutputs(fixture.output) == fixture.total
                            && fixture.provider.attempts == 1 && fixture.provider.accepted == 0 && fixture.power.charges == 0
                            && fixture.energyHatch().energyStorage().getAmountAsLong() == feBefore
                            && fixture.controller.runtimeSnapshot().factory().activeLaneCount() == 0,
                    "Unavailable machine FE or complete output space rejects without paying any resource owner");
            helper.assertTrue(fixture.returnAmount(fixture.output) == 0
                            && (missingFe ? fixture.host.getLogic().getReturnInv().isEmpty()
                            : fixture.returnAmount(BLOCKER) == fixture.host.getLogic().getReturnInv().size()),
                    "Rejected admission neither manufactures output nor consumes the unrelated capacity blockers");
            if (missingFe) fixture.energyHatch().energyStorage().setAmount(fixture.feBudget);
            else fixture.clearReturnBlocker(BLOCKER);
            helper.assertTrue(fixture.dispatch() == 1 && fixture.provider.accepted == 1 && fixture.power.charges == 1,
                    "Restoring the blocked resource admits the unchanged full batch exactly once");
        }).thenWaitUntil(() -> fixture.assertSettled(1)).thenExecute(() -> helper.assertTrue(
                        fixture.energyHatch().energyStorage().getAmountAsLong() == 0,
                        "No FE was lost to rejection or duplicate start; the original exact budget completes the recipe"))
                .thenSucceed();
    }

    public void overflowingBatchDefinitionRejectsBeforeAnyCpuPayment(GameTestHelper helper) {
        var fixture = new PatternBatchNativeCpuFixture(helper, "checked_overflow", false, 2,
                INT_MAX, ParallelTier.ULTIMATE);
        fixture.calculate().thenExecute(() -> {
            assertHugeCalculatedPlan(helper, fixture);
            fixture.submit();
            PatternBatchDetails batch = (PatternBatchDetails) fixture.plan.patternTimes().keySet().iterator().next();
            boolean rejected = false;
            try {
                // A two-unit template multiplied by this positive long overflows by exactly one.
                batch.scaled(Long.MAX_VALUE / 2 + 1);
            } catch (ArithmeticException exception) {
                rejected = true;
            }
            helper.assertTrue(rejected && fixture.cpu().getStored(fixture.input) == fixture.total
                            && fixture.cpu().getWaitingFor(fixture.output) == 0
                            && fixture.cpu().getPendingOutputs(fixture.output) == fixture.total && fixture.provider.attempts == 0
                            && fixture.power.charges == 0 && fixture.host.getLogic().getReturnInv().isEmpty()
                            && fixture.controller.runtimeSnapshot().factory().activeLaneCount() == 0,
                    "Checked definition overflow is rejected before task dispatch, reservation or any payment");
            helper.assertTrue(fixture.dispatch() == 1, "The valid original calculated order remains executable after rejection");
        }).thenWaitUntil(() -> fixture.assertSettled(1)).thenSucceed();
    }

    private static ValueInput patternLane(PatternBatchNativeCpuFixture fixture) {
        return fixture.savedLanes().stream().filter(lane -> lane.getBooleanOr("requires_pattern_request", false))
                .findFirst().orElseThrow(() -> new AssertionError("Expected a persisted pattern-source machine lane"));
    }

    private static void assertHugeCalculatedPlan(GameTestHelper helper, PatternBatchNativeCpuFixture fixture) {
        helper.assertTrue(fixture.plan.patternTimes().size() == 1, "Real calculation compresses the huge order to one identity");
        var task = fixture.plan.patternTimes().entrySet().iterator().next();
        helper.assertTrue(task.getKey() instanceof PatternBatchDetails batch && task.getValue() == 1
                        && batch.data().multiplier() == INT_MAX && batch.data().recipeCraftsPerPattern() == 1,
                "The genuine CPU plan is 2_147_483_647 x 1, never a small replacement order");
    }

    private static void assertNoUnorderedRestart(GameTestHelper helper, PatternBatchNativeCpuFixture fixture) {
        fixture.assertSettled(1);
        helper.assertTrue(fixture.energyHatch().energyStorage().getAmountAsLong() == fixture.feBudget,
                "Even with a full second batch of input and restored FE, real ticks never self-start without another CPU push");
    }

    /** @author howxu <dev@howxu.cn> */
    private enum SavePoint { PROCESSING, FINAL_TICK_PAID, OUTPUT_WAITING }
}
