package com.hackerai.supervisor.model;

import java.util.List;
import java.util.Map;

/**
 * Ordered execution plan with rollback instructions and retry limits.
 * Produced by PlannerAgent.
 */
public record ExecutionPlan(
    List<Step>          steps,
    Map<String, String> rollbackCommands,
    List<String>        riskFlags,
    int                 estimatedComplexity
) {

    public boolean hasRisks() {
        return riskFlags != null && !riskFlags.isEmpty();
    }

    public Step currentStep(int index) {
        if (index < 0 || index >= steps.size()) return null;
        return steps.get(index);
    }

    public boolean isComplete(int currentIndex) {
        return currentIndex >= steps.size();
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append("Execution Plan (").append(steps.size()).append(" steps, complexity: ")
          .append(estimatedComplexity).append(")\n");
        for (int i = 0; i < steps.size(); i++) {
            sb.append("  ").append(i + 1).append(". ").append(steps.get(i)).append("\n");
        }
        if (!riskFlags.isEmpty()) {
            sb.append("  ⚠ Risks: ").append(String.join("; ", riskFlags)).append("\n");
        }
        return sb.toString();
    }

    /**
     * A single step in the execution plan.
     */
    public record Step(
        int     order,
        String  agent,
        String  description,
        String  successCriteria,
        int     maxRetries
    ) {

        @Override
        public String toString() {
            return "[" + agent + "] " + description + " (retries: " + maxRetries + ")";
        }
    }
}
