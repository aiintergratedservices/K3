package com.hackerai.supervisor.model;

import com.hackerai.supervisor.model.value.*;

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of the indexed working directory.
 * Produced by ContextEngine in a single pass.
 */
public record ContextSnapshot(
    String                        language,
    String                        buildSystem,
    String                        testFramework,
    List<String>                  detectedFrameworks,
    List<SecretCandidate>         exposedSecrets,
    List<String>                  exposedEndpoints,
    Map<String, String>           envVariables,
    List<String>                  availableTools,
    boolean                       hasDocker,
    boolean                       hasKubernetes,
    OSInfo                        targetOS
) {

    public boolean hasSecrets() {
        return exposedSecrets != null && !exposedSecrets.isEmpty();
    }

    public boolean hasHighSeveritySecrets() {
        return exposedSecrets != null
            && exposedSecrets.stream().anyMatch(SecretCandidate::isHighSeverity);
    }

    public boolean isJavaProject() {
        return "java".equals(language);
    }

    public boolean isPythonProject() {
        return "python".equals(language);
    }

    public boolean isJavaScriptProject() {
        return "javascript".equals(language) || "typescript".equals(language);
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append("Language: ").append(language).append("\n");
        sb.append("Build System: ").append(buildSystem).append("\n");
        sb.append("Test Framework: ").append(testFramework).append("\n");
        if (!detectedFrameworks.isEmpty()) {
            sb.append("Frameworks: ").append(String.join(", ", detectedFrameworks)).append("\n");
        }
        if (hasSecrets()) {
            sb.append("Secrets: ").append(exposedSecrets.size()).append(" found\n");
        }
        sb.append("Available Tools: ").append(String.join(", ", availableTools)).append("\n");
        sb.append("Docker: ").append(hasDocker).append(", K8s: ").append(hasKubernetes).append("\n");
        sb.append("OS: ").append(targetOS);
        return sb.toString();
    }
}
