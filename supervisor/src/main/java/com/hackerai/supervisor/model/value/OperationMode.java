package com.hackerai.supervisor.model.value;

import com.hackerai.supervisor.model.ContextSnapshot;

/**
 * Defines the operational mode of the supervisor system.
 *
 * CODING — standard software engineering operations (build, test, deploy)
 * PENTEST — offensive security operations (recon, exploit, post-exploit)
 */
public record OperationMode(String mode) {

    public static final OperationMode CODING = new OperationMode("CODING");
    public static final OperationMode PENTEST = new OperationMode("PENTEST");

    public static OperationMode fromString(String s) {
        if (s == null) return CODING;
        return switch (s.toUpperCase().trim()) {
            case "PENTEST", "PENETRATION", "SECURITY", "EXPLOIT", "HACK" -> PENTEST;
            default -> CODING;
        };
    }

    public static OperationMode fromContext(ContextSnapshot ctx) {
        // Could inspect context for clues (e.g., presence of exploit dir)
        return CODING;
    }

    public boolean isPentest() {
        return this.equals(PENTEST);
    }

    public boolean isCoding() {
        return this.equals(CODING);
    }

    @Override
    public String toString() {
        return mode;
    }
}
