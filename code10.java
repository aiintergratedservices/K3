package com.hackerai.supervisor.model.value;

/**
 * Describes the authorized scope of the operation.
 *
 * Examples:
 *   "*.example.com"
 *   "10.0.0.0/24"
 *   "specific-repo"
 *   "single-endpoint:/api/users"
 */
public record TargetScope(String scope) {

    public static final TargetScope UNKNOWN = new TargetScope("unknown");

    public boolean isDefined() {
        return scope != null && !scope.isBlank() && !scope.equals("unknown");
    }

    public boolean matches(String host) {
        if (!isDefined()) return false;
        var pattern = scope
            .replace(".", "\\.")
            .replace("*", ".*");
        return host.matches(pattern);
    }

    @Override
    public String toString() {
        return scope;
    }
}
