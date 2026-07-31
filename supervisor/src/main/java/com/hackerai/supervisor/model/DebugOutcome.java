package com.hackerai.supervisor.model;

import java.util.function.Consumer;

/**
 * Sealed outcome type from DebuggerAgent defining how to proceed
 * after a failure.
 */
public sealed interface DebugOutcome {

    /**
     * Retry the failed step with a specific fix applied.
     */
    record RetryWithFix(
        String              fixDescription,
        Consumer<String>    fileEditor    // applies fix to file path → content
    ) implements DebugOutcome {}

    /**
     * Skip the failed step and continue to the next step.
     */
    record SkipStep(
        String              reason
    ) implements DebugOutcome {}

    /**
     * Abort the entire pipeline with a reason.
     */
    record Abort(
        String              reason
    ) implements DebugOutcome {}

    default boolean isRetry() {
        return this instanceof RetryWithFix;
    }

    default boolean isSkip() {
        return this instanceof SkipStep;
    }

    default boolean isAbort() {
        return this instanceof Abort;
    }
}
