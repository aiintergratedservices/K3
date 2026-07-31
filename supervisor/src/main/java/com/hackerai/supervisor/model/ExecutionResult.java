package com.hackerai.supervisor.model;

import java.util.List;
import java.util.Map;

/**
 * Result from ExecutorAgent after running a build, test, or payload.
 */
public record ExecutionResult(
    boolean             success,
    int                 exitCode,
    String              stdout,
    String              stderr,
    long                durationMs,
    List<String>        artifacts,
    Map<String, String> responseData,
    FailureDiagnosis    failure
) {

    /**
     * Structured diagnosis for failed executions.
     */
    public record FailureDiagnosis(
        String          category,
        String          summary,
        List<String>    possibleCauses,
        List<String>    suggestedFixes
    ) {

        public boolean isBuildError() {
            return "BUILD_ERROR".equals(category);
        }

        public boolean isTestFailure() {
            return "TEST_FAILURE".equals(category);
        }

        public boolean isWafBlock() {
            return "WAF_BLOCK".equals(category);
        }

        public boolean isTimeout() {
            return "TIMEOUT".equals(category);
        }

        @Override
        public String toString() {
            return "[" + category + "] " + summary;
        }
    }

    @Override
    public String toString() {
        var status = success ? "SUCCESS" : "FAILED (exit " + exitCode + ")";
        var time = durationMs + "ms";
        return status + " in " + time
            + (failure != null ? " - " + failure : "");
    }
}
