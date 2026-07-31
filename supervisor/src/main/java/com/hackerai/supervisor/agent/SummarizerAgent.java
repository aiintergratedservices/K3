package com.hackerai.supervisor.agent;

import com.hackerai.supervisor.annotation.AgentMethod;
import com.hackerai.supervisor.annotation.K;
import com.hackerai.supervisor.model.*;
import com.hackerai.supervisor.model.value.OperationMode;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Produces a concise, structured summary of everything that
 * happened during the operation. Designed for consumption by
 * both humans and chained agents.
 *
 * For PENTEST: lists confirmed vulnerabilities, exploits generated,
 * evidence artifacts, and remaining attack surface.
 * For CODING: lists files created/modified, test results, build status.
 */
public interface SummarizerAgent {

    @AgentMethod(description = """
        Produce a terse, structured summary of everything
        that happened: what succeeded, what failed, what was
        created, what was fixed, and what remains to be done.
        Designed for consumption by both humans and chained agents.
        """)
    OperationSummary summarize(
        @K(ExecutionPlan.class)         ExecutionPlan plan,
        @K(ExecutionResult[].class)     List<ExecutionResult> results,
        @K(OperationMode.class)         String mode
    );

    static SummarizerAgent createDefault() {
        return (plan, results, mode) -> {
            var modeEnum = OperationMode.fromString(mode);
            var achievements = new ArrayList<String>();
            var failures = new ArrayList<String>();
            var createdFiles = new ArrayList<String>();
            var fixedVulns = new ArrayList<String>();
            var confirmedVulns = new ArrayList<String>();
            var pendingItems = new ArrayList<String>();

            int successCount = 0;
            int failCount = 0;

            for (var result : results) {
                if (result.success()) {
                    successCount++;
                } else {
                    failCount++;
                    var reason = result.failure() != null
                        ? result.failure().summary()
                        : "Exit code " + result.exitCode();
                    failures.add(reason);
                }
            }

            // Build a one-liner based on mode
            String oneLiner;

            if (modeEnum.isPentest()) {
                // Extract vulnerability confirmations
                for (var result : results) {
                    var respData = result.responseData();
                    if (respData != null) {
                        for (var entry : respData.entrySet()) {
                            var key = entry.getKey().toLowerCase();
                            var val = entry.getValue().toLowerCase();

                            if (key.contains("sqli") && (val.contains("vulnerable")
                                || val.contains("time-based") || val.contains("error"))) {
                                confirmedVulns.add("SQL injection confirmed");
                            }
                            if (key.contains("xss") && val.contains("true")) {
                                confirmedVulns.add("XSS confirmed");
                            }
                            if (key.contains("auth") && (val.contains("200") || val.contains("201"))) {
                                confirmedVulns.add("Auth bypass achieved");
                            }
                            if (key.contains("rce") || key.contains("shell")) {
                                confirmedVulns.add("Remote code execution");
                            }
                        }
                    }
                }

                // Check created files for exploits
                for (var result : results) {
                    createdFiles.addAll(result.artifacts());
                }

                if (confirmedVulns.isEmpty() && failures.isEmpty()) {
                    oneLiner = "Recon complete — " + plan.steps().size()
                        + " steps executed, no vulnerabilities confirmed";
                } else if (confirmedVulns.isEmpty() && !failures.isEmpty()) {
                    oneLiner = "Assessment complete — " + successCount + " succeeded, "
                        + failCount + " failed, 0 vulns confirmed";
                } else {
                    oneLiner = String.join(", ", confirmedVulns)
                        + " — " + createdFiles.size() + " artifacts generated";
                }

                // Add pending items
                var uncheckedServices = plan.steps().stream()
                    .filter(s -> s.description().toLowerCase().contains("enumerate")
                        || s.description().toLowerCase().contains("check"))
                    .map(ExecutionPlan.Step::description)
                    .collect(Collectors.toList());
                pendingItems.addAll(uncheckedServices);

            } else {
                // CODING mode summary
                for (var result : results) {
                    createdFiles.addAll(result.artifacts());
                    if (result.responseData() != null
                        && result.responseData().containsKey("vuln_fixed")) {
                        fixedVulns.add(result.responseData().get("vuln_fixed"));
                    }
                }

                var testOutcome = results.stream()
                    .filter(r -> r.stdout() != null
                        && (r.stdout().contains("BUILD SUCCESS")
                            || r.stdout().contains("tests passed")
                            || r.stdout().contains("All tests")))
                    .findFirst();

                if (testOutcome.isPresent()) {
                    achievements.add("Build and tests pass successfully");
                }

                if (!failures.isEmpty()) {
                    var testFailure = results.stream()
                        .filter(r -> r.stdout() != null
                            && (r.stdout().contains("BUILD FAILURE")
                                || r.stdout().contains("test failed")
                                || r.stdout().contains("FAILED")))
                        .findFirst();
                    if (testFailure.isPresent()) {
                        failures.add("Build or tests failing");
                    }
                }

                if (createdFiles.isEmpty()) {
                    oneLiner = "Implementation complete — no files created (modifications only)";
                } else {
                    oneLiner = "Implemented: " + String.join(", ", createdFiles.subList(
                        0, Math.min(3, createdFiles.size())))
                        + (createdFiles.size() > 3 ? " (+" + (createdFiles.size() - 3) + " more)" : "");
                }
            }

            // Determine overall status
            OperationSummary.OperationStatus status;
            if (failCount == 0 && successCount > 0) {
                status = OperationSummary.OperationStatus.SUCCESS;
            } else if (failCount > 0 && successCount > 0) {
                status = OperationSummary.OperationStatus.PARTIAL;
            } else {
                status = OperationSummary.OperationStatus.FAILED;
            }

            // Add achievements
            if (!confirmedVulns.isEmpty()) {
                achievements.addAll(confirmedVulns);
            }
            if (!createdFiles.isEmpty()) {
                achievements.add(createdFiles.size() + " file(s) generated");
            }
            if (!fixedVulns.isEmpty()) {
                achievements.addAll(fixedVulns.stream()
                    .map(v -> "Fixed: " + v)
                    .collect(Collectors.toList()));
            }

            return new OperationSummary(
                status,
                List.copyOf(achievements),
                List.copyOf(failures),
                List.copyOf(createdFiles),
                List.copyOf(fixedVulns),
                List.copyOf(confirmedVulns),
                List.copyOf(pendingItems),
                oneLiner
            );
        };
    }
}
