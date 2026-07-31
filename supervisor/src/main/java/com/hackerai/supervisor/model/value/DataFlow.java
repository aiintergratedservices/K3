package com.hackerai.supervisor.model.value;

/**
 * Represents a data flow path from a source to a sink,
 * used to track tainted (user-controlled) data.
 */
public record DataFlow(
    String source,
    String sink,
    String dataType,
    boolean isTainted
) {

    /**
     * Returns true if this flow represents a potential vulnerability
     * (user-controlled data reaching a sensitive sink without sanitization).
     */
    public boolean isPotentialVulnerability() {
        return isTainted && (
            sink.contains("query") || sink.contains("execute")
            || sink.contains("eval") || sink.contains("shell")
            || sink.contains("exec") || sink.contains("open")
        );
    }

    @Override
    public String toString() {
        return (isTainted ? "[TAINTED] " : "") + source + " -> " + sink + " (" + dataType + ")";
    }
}
