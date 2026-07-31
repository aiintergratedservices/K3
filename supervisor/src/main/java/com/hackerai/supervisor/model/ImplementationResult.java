package com.hackerai.supervisor.model;

import java.util.List;

/**
 * Result from ImplementerAgent after writing/editing files.
 */
public record ImplementationResult(
    List<String>        filesCreated,
    List<String>        filesModified,
    List<String>        filesDeleted,
    String              diffSummary,
    List<String>        warnings,
    boolean             requiresBuild
) {

    public boolean hasChanges() {
        return !filesCreated.isEmpty() || !filesModified.isEmpty() || !filesDeleted.isEmpty();
    }

    public int totalChanges() {
        return filesCreated.size() + filesModified.size() + filesDeleted.size();
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append("Implementation Result: ");
        if (!filesCreated.isEmpty()) sb.append(filesCreated.size()).append(" created, ");
        if (!filesModified.isEmpty()) sb.append(filesModified.size()).append(" modified, ");
        if (!filesDeleted.isEmpty()) sb.append(filesDeleted.size()).append(" deleted");
        if (warnings != null && !warnings.isEmpty()) {
            sb.append("\n  Warnings: ").append(String.join("; ", warnings));
        }
        return sb.toString();
    }
}
