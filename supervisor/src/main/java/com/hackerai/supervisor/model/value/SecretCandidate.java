package com.hackerai.supervisor.model.value;

/**
 * Represents a potential secret/credential discovered in the codebase.
 */
public record SecretCandidate(
    String file,
    int line,
    String type,
    String preview
) {

    public boolean isHighSeverity() {
        return type.contains("PRIVATE KEY")
            || type.contains("AWS")
            || type.contains("GITHUB")
            || type.contains("JWT_SECRET");
    }

    @Override
    public String toString() {
        return file + ":" + line + " [" + type + "] " + preview;
    }
}
