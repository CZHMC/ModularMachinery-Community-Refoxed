package cn.howxu.mmcr.api.capability.status;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable collection of failure candidates and their primary selection.
 *
 * @author howxu <dev@howxu.cn>
 */
public record FailureReport(List<FailureReport.Candidate> candidates, Selection selection) {
    public FailureReport(List<Candidate> candidates) {
        this(candidates, Selection.REASON_PRIORITY);
    }

    public FailureReport {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        Objects.requireNonNull(selection, "selection");
    }

    public static FailureReport empty() {
        return new FailureReport(List.of());
    }

    public static FailureReport forRecipeSearch() {
        return new FailureReport(List.of(), Selection.REQUIREMENT_PROGRESS);
    }

    public FailureReport plus(ExecutionStatus status, float validity) {
        List<Candidate> added = new ArrayList<>(candidates);
        added.add(new Candidate(status, validity));
        return new FailureReport(added, selection);
    }

    public @Nullable ExecutionStatus primary() {
        Candidate primary = null;
        for (Candidate candidate : candidates) {
            if (primary == null || preferred(candidate, primary)) primary = candidate;
        }
        return primary == null ? null : primary.status();
    }

    private boolean preferred(Candidate candidate, Candidate current) {
        int validity = Float.compare(candidate.validity(), current.validity());
        if (selection == Selection.REQUIREMENT_PROGRESS
                && isRequirementPlanningFailure(candidate) && isRequirementPlanningFailure(current)
                && validity != 0) {
            return validity > 0;
        }
        int priority = Integer.compare(reasonPriority(candidate.status()), reasonPriority(current.status()));
        if (priority != 0) return priority > 0;
        if (selection == Selection.REASON_PRIORITY && validity != 0) return validity > 0;
        return false;
    }

    private static boolean isRequirementPlanningFailure(Candidate candidate) {
        FailureOccurrence failure = candidate.status().failure();
        return failure != null && failure.trace().frames().stream()
                .anyMatch(frame -> frame.phase() == FailurePhase.REQUIREMENT_PLAN);
    }

    private static int reasonPriority(ExecutionStatus status) {
        FailureReason reason = status.reason();
        return reason == null ? Integer.MIN_VALUE : reason.priority();
    }

    /**
     * A status candidate with the validity calculated by its producer.
     *
     * @param status candidate status
     * @param validity candidate validity
     * @author howxu <dev@howxu.cn>
     */
    public record Candidate(ExecutionStatus status, float validity) {
        public Candidate {
            Objects.requireNonNull(status, "status");
        }
    }

    public enum Selection {
        REASON_PRIORITY,
        REQUIREMENT_PROGRESS
    }
}
