package com.hackerai.supervisor.agent;

import com.hackerai.supervisor.annotation.AgentMethod;
import com.hackerai.supervisor.annotation.K;
import com.hackerai.supervisor.model.*;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Autonomous debug & retry agent. When ExecutorAgent reports a
 * failure, diagnoses the root cause from stdout/stderr/exit code
 * and either suggests a fix back to Implementer or adjusts the
 * execution plan. Tracks retry count per step to prevent infinite
 * loops.
 *
 * Failure categories handled:
 *  - BUILD_ERROR    → compilation failure, missing imports
 *  - TEST_FAILURE   → assertion failure, flaky test
 *  - TIMEOUT        → long-running operation
 *  - WAF_BLOCK      → payload blocked by WAF (pentest)
 *  - NO_VULN_FOUND  → technique didn't work, try variant
 *  - DEPENDENCY_ERR → missing package/library
 *  - SYNTAX_ERROR   → generated code has syntax errors
 */
public interface DebuggerAgent {

    @AgentMethod(description = """
        When ExecutorAgent reports a failure, diagnose the
        root cause and either suggest a fix back to Implementer
        or adjust the plan. Tracks retry count per step.
        """)
    DebugOutcome debug(
        @K(ExecutionResult.class)       ExecutionResult result,
        @K(ExecutionPlan.class)         ExecutionPlan plan,
        @K(ExecutionPlan.class)         ExecutionPlan.Step failedStep,
        @K(ContextSnapshot.class)       ContextSnapshot context,
        @K(Integer.class)               retryCount
    );

    static DebuggerAgent createDefault() {
        return (result, plan, failedStep, context, retryCount) -> {
            var stdout = result.stdout() != null ? result.stdout() : "";
            var stderr = result.stderr() != null ? result.stderr() : "";
            var combined = stdout + "\n" + stderr;

            // ─── 1. Classify the failure ───────────────────────
            String category;
            String summary;
            List<String> causes;
            List<String> fixes;

            if (result.failure() != null && result.failure().category() != null) {
                // Use pre-classified failure from ExecutorAgent
                category = result.failure().category();
                summary = result.failure().summary();
                causes = result.failure().possibleCauses();
                fixes = result.failure().suggestedFixes();
            } else {
                // Classify from output text
                if (combined.contains("cannot find symbol")
                    || combined.contains("undefined reference")
                    || combined.contains("unresolved import")
                    || combined.contains("ModuleNotFoundError")
                    || combined.contains("Cannot resolve symbol")
                    || combined.contains("package does not exist")) {
                    category = "BUILD_ERROR";
                    summary = extractRelevantLine(combined, "import|symbol|resolve|not found|undefined");
                    causes = List.of("Missing import or dependency", "Type name mismatch");
                    fixes = List.of("Add the missing import statement",
                        "Check class/type name spelling",
                        "Ensure dependency is declared in build file");
                }
                else if (combined.contains("error:")
                    || combined.contains("Error:")
                    || combined.contains("compilation error")
                    || combined.contains("syntax error")
                    || combined.contains("SyntaxError")
                    || combined.contains("CompileError")) {
                    category = "BUILD_ERROR";
                    summary = extractRelevantLine(combined, "error:|Error:");
                    causes = List.of("Syntax error in generated code");
                    fixes = List.of("Fix syntax based on compiler output line",
                        "Check for missing semicolons, brackets, or parentheses");
                }
                else if (combined.contains("FAILED")
                    || combined.contains("failed")
                    || combined.contains("AssertionError")
                    || combined.contains("AssertionFailedError")
                    || combined.contains("expected")
                    || combined.contains("but was")
                    || combined.contains("Expected:")) {
                    category = "TEST_FAILURE";
                    summary = extractRelevantLine(combined, "FAILED|failed|Expected:|but was|expected");
                    causes = List.of("Assertion failure in test",
                        "Implementation does not match expected behavior");
                    fixes = List.of("Review the failing assertion",
                        "Verify expected vs actual values",
                        "Update implementation to match test expectations");
                }
                else if (combined.contains("timeout")
                    || combined.contains("Timeout")
                    || combined.contains("timed out")
                    || combined.contains("TimeoutException")
                    || combined.contains("Read timed out")
                    || combined.contains("connect timed out")) {
                    category = "TIMEOUT";
                    summary = extractRelevantLine(combined, "timeout|Timeout|timed out");
                    causes = List.of("Operation took too long",
                        "Network latency or resource contention");
                    fixes = List.of("Increase timeout value",
                        "Optimize the operation",
                        "Check network connectivity");
                }
                else if (combined.contains("403")
                    || combined.contains("Forbidden")
                    || combined.contains("WAF")
                    || combined.contains("blocked")
                    || combined.contains("Blocked")
                    || combined.contains("ModSecurity")
                    || combined.contains("CloudFlare")
                    || combined.contains("Cloudflare")) {
                    category = "WAF_BLOCK";
                    summary = "WAF or access control blocked the request";
                    causes = List.of("Payload signature detected by WAF",
                        "IP rate-limited or blacklisted");
                    fixes = List.of("Use WAF bypass techniques (encoding, splitting)",
                        "Rotate source IP",
                        "Use slower request rate",
                        "Try different payload variant");
                }
                else if (combined.contains("not vulnerable")
                    || combined.contains("not found")
                    || (result.exitCode() == 0 && combined.length() < 50)) {
                    category = "NO_VULN_FOUND";
                    summary = "No vulnerability detected with current technique";
                    causes = List.of("Vulnerability may not exist at this endpoint",
                        "Technique may be wrong for this target");
                    fixes = List.of("Try a different injection technique",
                        "Try a different endpoint",
                        "Escalate to manual testing");
                }
                else if (combined.contains("ModuleNotFoundError")
                    || combined.contains("ImportError")
                    || combined.contains("Cannot find module")
                    || combined.contains("No module named")
                    || combined.contains("package not found")
                    || combined.contains("Could not resolve")) {
                    category = "DEPENDENCY_ERR";
                    summary = extractRelevantLine(combined, "ModuleNotFoundError|ImportError|Cannot find|No module|not found|Could not resolve");
                    causes = List.of("Missing dependency declaration",
                        "Package not installed");
                    fixes = List.of("Add dependency to build file (pom.xml, package.json, etc.)",
                        "Run dependency install command");
                }
                else if (result.exitCode() != 0 && combined.length() < 200) {
                    category = "UNKNOWN_ERROR";
                    summary = "Exit code " + result.exitCode() + " with minimal output";
                    causes = List.of("Generic failure with no clear error message");
                    fixes = List.of("Review the full command output",
                        "Run the command manually for more details");
                }
                else {
                    category = "UNKNOWN_ERROR";
                    summary = truncate(stderr.length() > 50 ? stderr : stdout, 200);
                    causes = List.of("Unclassified failure");
                    fixes = List.of("Review full output and adjust approach");
                }
            }

            // ─── 2. Decide retry outcome ───────────────────────
            if (retryCount >= 3) {
                return new DebugOutcome.Abort(
                    "Exceeded max retries (3) for step: " + failedStep.description()
                    + "\nLast failure: [" + category + "] " + summary
                );
            }

            // For WAF blocks, try a different technique
            if (category.equals("WAF_BLOCK")) {
                var fixDesc = "WAF detected — applying bypass: " + fixes.get(0);
                return new DebugOutcome.RetryWithFix(
                    fixDesc,
                    fileEditor -> {
                        // The editor lambda would modify the payload file
                        // to add WAF bypass encoding
                    }
                );
            }

            // For NO_VULN_FOUND, try a different approach
            if (category.equals("NO_VULN_FOUND")) {
                return new DebugOutcome.RetryWithFix(
                    "No vulnerability found — switching technique: " + fixes.get(0),
                    fileEditor -> {}
                );
            }

            // For build/test errors, retry with fix
            if (category.equals("BUILD_ERROR") || category.equals("TEST_FAILURE")
                || category.equals("DEPENDENCY_ERR") || category.equals("SYNTAX_ERROR")) {
                var fixDesc = "[" + category + "] " + summary
                    + "\nSuggested fix: " + fixes.get(0);
                return new DebugOutcome.RetryWithFix(
                    fixDesc,
                    fileEditor -> {
                        // Apply the fix to the relevant file
                    }
                );
            }

            // For timeouts, retry with longer timeout
            if (category.equals("TIMEOUT")) {
                return new DebugOutcome.RetryWithFix(
                    "Timeout detected — retrying with extended timeout",
                    fileEditor -> {}
                );
            }

            // For unknown errors on last retry, skip the step
            if (retryCount >= 2) {
                return new DebugOutcome.SkipStep(
                    "Unknown error after " + retryCount + " retries: " + summary
                );
            }

            // Default: retry with generic fix
            return new DebugOutcome.RetryWithFix(
                "Retrying step with adjusted approach",
                fileEditor -> {}
            );
        };
    }

    private static String extractRelevantLine(String output, String patternStr) {
        var pattern = Pattern.compile("(?m)^.*(?:" + patternStr + ").*$");
        var m = pattern.matcher(output);
        if (m.find()) return m.group().trim();
        // Fallback: first non-empty line
        return output.lines()
            .filter(l -> !l.isBlank())
            .findFirst()
            .orElse("Unknown error");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
