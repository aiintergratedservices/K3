package com.hackerai.supervisor.dispatch;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Tool dispatch layer. Automatically selects and invokes the
 * right security/development tool based on the operation context.
 *
 * Maintains a registry of known tools and their execution profiles
 * (timeout, arguments, output parsing).
 */
public class ToolDispatcher {

    private final ToolRegistry registry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ToolDispatcher(ToolRegistry registry) {
        this.registry = registry;
    }

    public static ToolDispatcher createDefault() {
        return new ToolDispatcher(ToolRegistry.createDefault());
    }

    /**
     * Dispatch a named tool with variable arguments.
     * Automatically locates the tool binary and applies
     * registry settings (timeout, env vars).
     *
     * @param toolName the tool name (e.g., "nmap", "sqlmap", "nuclei")
     * @param args     variable arguments to pass
     * @return ToolResult with exit code and captured output
     */
    public ToolResult dispatch(String toolName, String... args) {
        var config = registry.getConfig(toolName);
        if (config == null) {
            return new ToolResult(-1, "", "Tool not found: " + toolName, Map.of());
        }

        var command = new ArrayList<String>();
        command.add(config.binary());
        command.addAll(config.defaultArgs());
        command.addAll(List.of(args));

        try {
            var proc = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();

            // Capture stdout and stderr concurrently
            var stdoutFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(proc.getInputStream().readAllBytes());
                } catch (IOException e) {
                    return "";
                }
            }, executor);

            var stderrFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(proc.getErrorStream().readAllBytes());
                } catch (IOException e) {
                    return "";
                }
            }, executor);

            // Wait with configured timeout
            boolean finished = proc.waitFor(config.timeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return new ToolResult(-1, "", "Timeout after " + config.timeoutMs() + "ms", Map.of());
            }

            var stdout = stdoutFuture.get(1, TimeUnit.SECONDS);
            var stderr = stderrFuture.get(1, TimeUnit.SECONDS);
            var metadata = Map.of(
                "tool", toolName,
                "command", String.join(" ", command),
                "exitCode", String.valueOf(proc.exitValue())
            );

            return new ToolResult(proc.exitValue(), stdout, stderr, metadata);

        } catch (Exception e) {
            return new ToolResult(-1, "", e.getMessage(), Map.of("error", e.getClass().getSimpleName()));
        }
    }

    /**
     * Convenience: dispatch with a single string command line.
     */
    public ToolResult dispatch(String commandLine) {
        var parts = commandLine.split("\\s+");
        if (parts.length == 0) {
            return new ToolResult(-1, "", "Empty command", Map.of());
        }
        var toolName = parts[0];
        var args = Arrays.copyOfRange(parts, 1, parts.length);
        return dispatch(toolName, args);
    }

    /**
     * Result of a tool dispatch.
     */
    public record ToolResult(
        int exitCode,
        String stdout,
        String stderr,
        Map<String, String> metadata
    ) {
        public boolean isSuccess() {
            return exitCode == 0;
        }

        @Override
        public String toString() {
            return "ToolResult(exit=" + exitCode + ", stdout="
                + (stdout.length() > 100 ? stdout.substring(0, 100) + "..." : stdout)
                + ")";
        }
    }

    /**
     * Tool execution configuration.
     */
    public record ToolConfig(
        String binary,
        List<String> defaultArgs,
        long timeoutMs
    ) {
        public static ToolConfig of(String binary, long timeoutMs, String... defaultArgs) {
            return new ToolConfig(binary, List.of(defaultArgs), timeoutMs);
        }
    }
}
