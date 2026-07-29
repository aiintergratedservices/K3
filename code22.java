package com.hackerai.supervisor.model;

import java.util.List;

/**
 * Final structured summary produced by SummarizerAgent.
 * Designed for human readability and chained agent consumption.
 */
public record OperationSummary(
    OperationStatus     status,
    List<String>        achievements,
    List<String>        failures,
    List<String>        createdFiles,
    List<String>        fixedVulnerabilities,
    List<String>        confirmedVulnerabilities,
    List<String>        pendingItems,
    String              oneLiner
) {

    public enum OperationStatus {
        SUCCESS("✓ All steps completed successfully"),
        PARTIAL("⚠ Some steps completed, some failed"),
        FAILED("✗ Operation failed");

        private final String label;

        OperationStatus(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Formats the summary as a concise report string.
     */
    public String toReport() {
        var sb = new StringBuilder();
        sb.append("═══ Operation Summary ═══\n");
        sb.append("Status: ").append(status.label()).append("\n");
        sb.append("├─ ").append(oneLiner).append("\n\n");

        if (!achievements.isEmpty()) {
            sb.append("✓ Achievements:\n");
            achievements.forEach(a -> sb.append("  • ").append(a).append("\n"));
            sb.append("\n");
        }

        if (!failures.isEmpty()) {
            sb.append("✗ Failures:\n");
            failures.forEach(f -> sb.append("  • ").append(f).append("\n"));
            sb.append("\n");
        }

        if (!createdFiles.isEmpty()) {
            sb.append("📁 Files:\n");
            createdFiles.forEach(f -> sb.append("  • ").append(f).append("\n"));
            sb.append("\n");
        }

        if (!confirmedVulnerabilities.isEmpty()) {
            sb.append("⚠ Confirmed Vulnerabilities:\n");
            confirmedVulnerabilities.forEach(v -> sb.append("  • ").append(v).append("\n"));
            sb.append("\n");
        }

        if (!fixedVulnerabilities.isEmpty()) {
            sb.append("🔒 Fixed Vulnerabilities:\n");
            fixedVulnerabilities.forEach(v -> sb.append("  • ").append(v).append("\n"));
            sb.append("\n");
        }

        if (!pendingItems.isEmpty()) {
            sb.append("⏳ Remaining:\n");
            pendingItems.forEach(p -> sb.append("  • ").append(p).append("\n"));
        }

        sb.append("══════════════════════════");
        return sb.toString();
    }

    @Override
    public String toString() {
        return toReport();
    }
}
