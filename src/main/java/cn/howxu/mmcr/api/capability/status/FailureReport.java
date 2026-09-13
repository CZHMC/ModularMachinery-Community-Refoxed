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
public record FailureReport(List<FailureReport.Candidate> candidates) {
    public FailureReport {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    }

    public static FailureReport empty() {
        return new FailureReport(List.of());
    }

    public FailureReport plus(ExecutionStatus status, float validity) {
        List<Candidate> added = new ArrayList<>(candidates);
        added.add(new Candidate(status, validity));
        return new FailureReport(added);
    }

    public @Nullable ExecutionStatus primary() {
        Candidate primary = null;
        for (Candidate candidate : candidates) {
            if (primary == null || preferred(candidate, primary)) primary = candidate;
        }
        return primary == null ? null : primary.status();
    }

    private static boolean preferred(Candidate candidate, Candidate current) {
        int priority = Integer.compare(reasonPriority(candidate.status()), reasonPriority(current.status()));
        if (priority != 0) return priority > 0;
        int validity = Float.compare(candidate.validity(), current.validity());
        return validity > 0;
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
     */
    public record Candidate(ExecutionStatus status, float validity) {
        public Candidate {
            Objects.requireNonNull(status, "status");
        }
    }
}
