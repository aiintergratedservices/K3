package com.hackerai.supervisor.agent;

import com.hackerai.supervisor.annotation.AgentMethod;
import com.hackerai.supervisor.annotation.K;
import com.hackerai.supervisor.model.*;
import com.hackerai.supervisor.model.value.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Strategic planner. Produces an ordered execution plan.
 *
 * For PENTEST mode:
 *   Prioritizes steps by exploitability (not CVSS).
 *   Favors low-hanging fruit: exposed secrets → open ports →
 *   parameter injection → auth bypass → RCE.
 *
 * For CODING mode:
 *   Orders steps by dependency graph (build order).
 *   Model → Repository → Service → Controller → View/Test.
 *
 * Every step includes rollback instructions and success criteria.
 */
public interface PlannerAgent {

    @AgentMethod(description = """
        Produce an ordered execution plan.
        For PENTEST: prioritize by exploitability, not CVSS.
        For CODING: prioritize by dependency graph.
        Include rollback instructions for each step.
        """)
    ExecutionPlan plan(
        @K(UserRequest.class)      String request,
        @K(ContextSnapshot.class)   ContextSnapshot context,
        @K(ExplorationMap.class)    ExplorationMap map,
        @K(OperationMode.class)     String mode
    );

    static PlannerAgent createDefault() {
        return (request, context, map, mode) -> {
            var modeEnum = OperationMode.fromString(mode);
            var steps = new ArrayList<ExecutionPlan.Step>();
            var rollbacks = new HashMap<String, String>();
            var riskFlags = new ArrayList<String>();

            if (modeEnum.isPentest()) {
                buildPentestPlan(request, context, map, steps, rollbacks, riskFlags);
            } else {
                buildCodingPlan(request, context, map, steps, rollbacks, riskFlags);
            }

            var complexity = steps.stream()
                .mapToInt(s -> {
                    var desc = s.description().toLowerCase();
                    if (desc.contains("exploit") || desc.contains("rce")
                        || desc.contains("reverse shell")) return 5;
                    if (desc.contains("sqli") || desc.contains("xss")
                        || desc.contains("ssrf")) return 3;
                    if (desc.contains("scan") || desc.contains("enumerate")
                        || desc.contains("discover")) return 1;
                    return 2;
                })
                .sum();

            return new ExecutionPlan(
                List.copyOf(steps),
                Map.copyOf(rollbacks),
                List.copyOf(riskFlags),
                complexity
            );
        };
    }

    // ─── PENTEST Plan Builder ─────────────────────────────────────

    private static void buildPentestPlan(
        String request,
        ContextSnapshot ctx,
        ExplorationMap map,
        List<ExecutionPlan.Step> steps,
        Map<String, String> rollbacks,
        List<String> risks
    ) {
        int order = 0;

        // Step 1: Secret harvesting (lowest hanging fruit)
        if (!ctx.exposedSecrets().isEmpty()) {
            steps.add(new ExecutionPlan.Step(
                order++, "ContextEngine", "Harvest exposed secrets found in codebase",
                "Found credentials validated as active", 1
            ));
            rollbacks.put("step-" + (order - 1),
                "Revoke exposed credentials and rotate keys");
            risks.add("Exposed secrets may already be compromised");
        }

        // Step 2: Port-based service exploitation
        for (var port : map.openPorts()) {
            String description;
            String success;
            switch (port.service()) {
                case "FTP" -> {
                    description = "Check FTP anonymous access on port " + port.port();
                    success = "Anonymous access confirmed or credentials found";
                }
                case "SSH" -> {
                    description = "Enumerate SSH version and test default credentials on port " + port.port();
                    success = "Weak credentials found or version vulnerable";
                }
                case "SMB" -> {
                    description = "Enumerate SMB shares and check for MS17-010 / uncredentialed access";
                    success = "Accessible shares or vulnerable SMB version";
                }
                case "HTTP", "HTTPS" -> {
                    description = "Web application recon on " + port.port();
                    success = "Endpoints and technologies identified";
                }
                case "RDP" -> {
                    description = "Check RDP version and bluekeep (CVE-2019-0708)";
                    success = "Vulnerable RDP version or accessible";
                }
                default -> {
                    description = "Enumerate service on port " + port.port() + " (" + port.service() + ")";
                    success = "Service version and potential CVEs identified";
                }
            }
            steps.add(new ExecutionPlan.Step(
                order++, "ExecutorAgent", description, success, 2
            ));
            rollbacks.put("step-" + (order - 1),
                "Close port / restrict access via firewall");
        }

        // Step 3: Web parameter injection
        for (var ep : map.endpoints()) {
            steps.add(new ExecutionPlan.Step(
                order++, "ImplementerAgent",
                "Test " + ep.method() + " " + ep.path() + " for SQLi, XSS, SSRF, and command injection",
                "At least one injection point confirmed", 3
            ));
            rollbacks.put("step-" + (order - 1),
                "Restore original endpoint logic / apply input validation");
            risks.add("Injection testing may trigger WAF alerts");
        }

        // Step 4: Auth bypass
        if (map.endpoints().stream().anyMatch(Endpoint::requiresAuth)) {
            steps.add(new ExecutionPlan.Step(
                order++, "ImplementerAgent",
                "Test auth mechanisms: JWT none algorithm, SQLi in login, session fixation",
                "Auth bypass achieved on at least one endpoint", 3
            ));
            rollbacks.put("step-" + (order - 1),
                "Re-enable proper auth checks / rotate session secrets");
        }

        // Step 5: RCE / post-exploitation
        if (request.toLowerCase().contains("rce")
            || request.toLowerCase().contains("shell")
            || request.toLowerCase().contains("reverse")) {
            steps.add(new ExecutionPlan.Step(
                order++, "ImplementerAgent",
                "Develop and deploy reverse shell / RCE payload",
                "Reverse shell callback received", 4
            ));
            rollbacks.put("step-" + (order - 1),
                "Kill reverse shell process and remove persistence");
            risks.add("RCE may cause service disruption — use with caution");
        }
    }

    // ─── CODING Plan Builder ──────────────────────────────────────

    private static void buildCodingPlan(
        String request,
        ContextSnapshot ctx,
        ExplorationMap map,
        List<ExecutionPlan.Step> steps,
        Map<String, String> rollbacks,
        List<String> risks
    ) {
        int order = 0;

        // Step 1: Setup / install dependencies
        if (!ctx.buildSystem().equals("none")) {
            steps.add(new ExecutionPlan.Step(
                order++, "ExecutorAgent",
                "Install project dependencies using " + ctx.buildSystem(),
                "Dependencies resolved without errors", 1
            ));
            rollbacks.put("step-" + (order - 1),
                "Revert dependency file changes / remove lock file");
        }

        // Step 2: Data model / schema changes (if applicable)
        if (request.toLowerCase().contains("model")
            || request.toLowerCase().contains("entity")
            || request.toLowerCase().contains("schema")
            || request.toLowerCase().contains("database")) {
            steps.add(new ExecutionPlan.Step(
                order++, "ImplementerAgent",
                "Create/modify data models and schema definitions",
                "Models compile and pass validation", 2
            ));
            rollbacks.put("step-" + (order - 1),
                "Revert model files to previous version");
        }

        // Step 3: Repository / data access layer
        if (request.toLowerCase().contains("repository")
            || request.toLowerCase().contains("dao")
            || request.toLowerCase().contains("query")) {
            steps.add(new ExecutionPlan.Step(
                order++, "ImplementerAgent",
                "Implement repository/data access layer",
                "Queries execute and return expected results", 2
            ));
        }

        // Step 4: Service / business logic
        steps.add(new ExecutionPlan.Step(
            order++, "ImplementerAgent",
            "Implement core business logic for: " + request,
            "All business logic paths covered", 3
        ));
        rollbacks.put("step-" + (order - 1),
            "Revert service layer files");

        // Step 5: Controller / API layer
        if (request.toLowerCase().contains("api")
            || request.toLowerCase().contains("endpoint")
            || request.toLowerCase().contains("controller")
            || request.toLowerCase().contains("route")) {
            steps.add(new ExecutionPlan.Step(
                order++, "ImplementerAgent",
                "Create API endpoints / controllers",
                "All endpoints respond correctly", 2
            ));
        }

        // Step 6: Tests
        if (!ctx.testFramework().equals("none")) {
            steps.add(new ExecutionPlan.Step(
                order++, "ImplementerAgent",
                "Write unit/integration tests using " + ctx.testFramework(),
                "All tests pass", 3
            ));
        }

        // Step 7: Build verification
        steps.add(new ExecutionPlan.Step(
            order++, "ExecutorAgent",
            "Run full build and test suite",
            "Build succeeds with 0 failures", 1
        ));
    }
}
