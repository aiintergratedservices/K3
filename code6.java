package com.hackerai.supervisor.agent;

import com.hackerai.supervisor.annotation.AgentMethod;
import com.hackerai.supervisor.annotation.K;
import com.hackerai.supervisor.dispatch.ToolDispatcher;
import com.hackerai.supervisor.model.*;
import com.hackerai.supervisor.model.value.OperationMode;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Execution agent. Runs builds, tests, linting (CODING mode) or
 * fires payloads, runs tool-based scans (PENTEST mode).
 *
 * Captures stdout, stderr, exit codes, timing, and response data.
 * Delegates tool-specific invocations to ToolDispatcher so the
 * right tool (sqlmap, nuclei, nmap, etc.) is picked automatically.
 */
public interface ExecutorAgent {

    @AgentMethod(description = """
        Execute the implementation. In CODING mode: compile,
        run tests, lint. In PENTEST mode: fire payloads, run
        tool-based scans. Captures stdout, stderr, exit codes,
        and response data.
        """)
    ExecutionResult execute(
        @K(ImplementationResult.class) ImplementationResult changes,
        @K(WorkingDirectory.class)     String workingDir,
        @K(ExecutionPlan.class)        ExecutionPlan.Step step,
        @K(OperationMode.class)        String mode
    );

    static ExecutorAgent createDefault() {
        return (changes, workingDir, step, mode) -> {
            var modeEnum = OperationMode.fromString(mode);
            var root = Path.of(workingDir);
            var startTime = System.currentTimeMillis();

            if (modeEnum.isPentest()) {
                return executePentest(step, root, changes);
            } else {
                return executeCoding(step, root, changes);
            }
        };
    }

    // ─── PENTEST Execution ────────────────────────────────────────

    private static ExecutionResult executePentest(
        ExecutionPlan.Step step,
        Path root,
        ImplementationResult changes
    ) {
        var desc = step.description().toLowerCase();
        var dispatcher = ToolDispatcher.createDefault();
        var artifacts = new ArrayList<String>();
        var responseData = new HashMap<String, String>();

        // SQL injection testing via sqlmap or manual curl
        if (desc.contains("sqli") || desc.contains("sql injection")) {
            var target = extractTargetUrl(root, desc);
            if (target != null) {
                var result = dispatcher.dispatch("sqlmap",
                    "-u", target,
                    "--batch",
                    "--random-agent",
                    "--level", "2",
                    "--risk", "2",
                    "--output-dir=" + root.resolve("reports/sqlmap")
                );
                responseData.put("sqlmap_exit_code", String.valueOf(result.exitCode()));
                responseData.put("sqlmap_stdout", truncate(result.stdout(), 2000));
                responseData.put("sqlmap_stderr", truncate(result.stderr(), 500));
                if (result.exitCode() == 0) artifacts.add("reports/sqlmap/");
            }
        }

        // XSS testing
        if (desc.contains("xss")) {
            var target = extractTargetUrl(root, desc);
            if (target != null) {
                var payload = "<script>alert('XSS_TEST')</script>";
                try {
                    var url = new java.net.URI(target.replaceFirst("\\?.*", "")
                        + "?q=" + java.net.URLEncoder.encode(payload, "UTF-8"))
                        .toURL();
                    var conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    var code = conn.getResponseCode();
                    var body = new String(conn.getInputStream().readAllBytes());
                    responseData.put("xss_http_code", String.valueOf(code));
                    responseData.put("xss_response_reflected",
                        String.valueOf(body.contains("XSS_TEST")));
                    artifacts.add("xss_test_target: " + target);
                } catch (Exception e) {
                    responseData.put("xss_error", e.getMessage());
                }
            }
        }

        // Port scan via nmap
        if (desc.contains("port") || desc.contains("service")) {
            var target = extractHostname(root);
            var result = dispatcher.dispatch("nmap",
                "-sV", "-sC",
                "-p", "21,22,23,25,53,80,110,111,135,139,143,443,445,993,995,1433,1521,2049,2375,2376,3306,3389,5432,5900,5985,5986,6379,8080,8443,9000,9090,27017",
                "-oN", root.resolve("reports/nmap_scan.txt").toString(),
                target
            );
            responseData.put("nmap_exit_code", String.valueOf(result.exitCode()));
            responseData.put("nmap_output", truncate(result.stdout(), 3000));
            if (result.exitCode() == 0) artifacts.add("reports/nmap_scan.txt");
        }

        // Auth testing
        if (desc.contains("auth") || desc.contains("bypass")) {
            var target = extractTargetUrl(root, desc);
            if (target != null) {
                var dispatcher2 = dispatcher;
                var result = dispatcher2.dispatch("curl",
                    "-s", "-o", "/dev/null", "-w", "%{http_code}",
                    target,
                    "-H", "Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJub25lIn0."
                );
                responseData.put("auth_bypass_http_code", result.stdout().trim());
            }
        }

        var duration = System.currentTimeMillis() - startTime;

        return new ExecutionResult(
            true, 0, responseData.toString(),
            "", duration, List.copyOf(artifacts),
            Map.copyOf(responseData),
            null
        );
    }

    // ─── CODING Execution ─────────────────────────────────────────

    private static ExecutionResult executeCoding(
        ExecutionPlan.Step step,
        Path root,
        ImplementationResult changes
    ) {
        var desc = step.description().toLowerCase();
        var dispatcher = ToolDispatcher.createDefault();

        // Install dependencies
        if (desc.contains("dependencies") || desc.contains("install")) {
            var buildFile = resolveBuildFile(root);
            if (buildFile != null) {
                var result = dispatcher.dispatch(buildFile);
                return toExecutionResult(result, System.currentTimeMillis());
            }
        }

        // Build & test
        if (desc.contains("build") || desc.contains("compile")
            || desc.contains("test") || desc.contains("verify")) {
            var buildTool = detectBuildTool(root);
            var result = dispatcher.dispatch(buildTool, "clean", "test");
            return toExecutionResult(result, System.currentTimeMillis());
        }

        // Lint / format
        if (desc.contains("lint") || desc.contains("format")) {
            var linter = detectLinter(root);
            if (linter != null) {
                var result = dispatcher.dispatch(linter);
                return toExecutionResult(result, System.currentTimeMillis());
            }
        }

        // Fallback: just verify the files exist
        var allExist = changes.filesCreated().stream()
            .allMatch(f -> Files.exists(root.resolve(f)));
        var duration = System.currentTimeMillis() - startTime;

        return new ExecutionResult(
            allExist, allExist ? 0 : 1,
            allExist ? "All files created successfully" : "Some files missing",
            "", duration, changes.filesCreated(),
            Map.of(), null
        );
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private static ExecutionResult toExecutionResult(
        ToolDispatcher.ToolResult result,
        long startTime
    ) {
        var duration = System.currentTimeMillis() - startTime;
        var diagnosis = result.exitCode() != 0
            ? new ExecutionResult.FailureDiagnosis(
                "BUILD_ERROR",
                truncate(result.stderr(), 500),
                List.of("Check stderr for details"),
                List.of("Review compilation errors and retry")
            )
            : null;

        return new ExecutionResult(
            result.exitCode() == 0,
            result.exitCode(),
            truncate(result.stdout(), 3000),
            truncate(result.stderr(), 1000),
            duration,
            List.of(),
            Map.of(),
            diagnosis
        );
    }

    private static String extractTargetUrl(Path root, String desc) {
        var urlMatcher = Pattern.compile("https?://[^\\s/]+")
            .matcher(desc);
        if (urlMatcher.find()) return urlMatcher.group();
        // Try to read from .env or config
        try {
            var envFile = root.resolve(".env");
            if (Files.exists(envFile)) {
                try (var lines = Files.lines(envFile)) {
                    return lines.filter(l -> l.contains("TARGET_URL") || l.contains("URL"))
                        .map(l -> l.substring(l.indexOf('=') + 1).trim())
                        .findFirst()
                        .orElse("http://localhost:8080");
                }
            }
        } catch (IOException ignored) {}
        return "http://localhost:8080";
    }

    private static String extractHostname(Path root) {
        var url = extractTargetUrl(root, "");
        try {
            return new java.net.URI(url).getHost();
        } catch (Exception e) {
            return "localhost";
        }
    }

    private static String resolveBuildFile(Path root) {
        if (Files.exists(root.resolve("pom.xml"))) return "mvn install -DskipTests";
        if (Files.exists(root.resolve("build.gradle"))
            || Files.exists(root.resolve("build.gradle.kts"))) return "gradle build -x test";
        if (Files.exists(root.resolve("package.json"))) return "npm install";
        if (Files.exists(root.resolve("Cargo.toml"))) return "cargo build";
        if (Files.exists(root.resolve("go.mod"))) return "go build ./...";
        if (Files.exists(root.resolve("pyproject.toml"))) return "pip install -e .";
        if (Files.exists(root.resolve("requirements.txt"))) return "pip install -r requirements.txt";
        return null;
    }

    private static String detectBuildTool(Path root) {
        if (Files.exists(root.resolve("pom.xml"))) return "mvn";
        if (Files.exists(root.resolve("build.gradle"))
            || Files.exists(root.resolve("build.gradle.kts"))) return "gradle";
        if (Files.exists(root.resolve("package.json"))) return "npm";
        if (Files.exists(root.resolve("Cargo.toml"))) return "cargo";
        if (Files.exists(root.resolve("go.mod"))) return "go";
        return "make";
    }

    private static String detectLinter(Path root) {
        if (Files.exists(root.resolve(".eslintrc.js"))
            || Files.exists(root.resolve(".eslintrc.json"))
            || Files.exists(root.resolve(".eslintrc"))) return "npx eslint .";
        if (Files.exists(root.resolve(".prettierrc"))
            || Files.exists(root.resolve(".prettierrc.js"))) return "npx prettier --check .";
        if (Files.exists(root.resolve("pom.xml"))
            || Files.exists(root.resolve("build.gradle"))) return "mvn checkstyle:check";
        if (Files.exists(root.resolve("pyproject.toml"))) {
            if (Files.exists(root.resolve(".flake8"))
                || Files.exists(root.resolve("setup.cfg"))) return "flake8 .";
            return "ruff check .";
        }
        return null;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "... [truncated]";
    }

    // Must import Pattern for the regex
    private static final java.util.regex.Pattern Pattern = null;
    // Used inline above
}
