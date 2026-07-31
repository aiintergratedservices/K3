package com.hackerai.supervisor;

import com.hackerai.supervisor.annotation.*;
import com.hackerai.supervisor.agent.*;
import com.hackerai.supervisor.concurrent.StageOrchestrator;
import com.hackerai.supervisor.context.ContextMap;
import com.hackerai.supervisor.dispatch.ToolDispatcher;
import com.hackerai.supervisor.model.*;
import com.hackerai.supervisor.model.value.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * =============================================================================
 * SupervisorCoderSystem v2.0
 * =============================================================================
 * Multi-agent orchestration engine for high-velocity software engineering
 * and authorized penetration testing operations.
 *
 * Features:
 *  - Autonomous context indexing (one-pass target analysis)
 *  - Parallel agent exploration with fan-out
 *  - Strategic planning prioritized by exploitability (pentest) or
 *    dependency graph (coding)
 *  - Implementation with style-preserving code/payload generation
 *  - Execution with tool-aware dispatch (sqlmap, nuclei, metasploit, etc.)
 *  - Debugger-driven retry loop (max 3 retries per step, configurable)
 *  - Structured summarization for human or chained-agent consumption
 *  - Concurrency via CompletableFuture with configurable thread pools
 * =============================================================================
 */
@SupervisorAgent(
    description = """
        Multi-agent assistant for software engineering and
        offensive security operations. Autonomously explores,
        plans, implements, executes, and iterates on failures
        without requiring user re-prompting.
        """,
    subAgents = {
        ContextEngine.class,
        ExplorerAgent.class,
        PlannerAgent.class,
        ImplementerAgent.class,
        ExecutorAgent.class,
        DebuggerAgent.class,
        SummarizerAgent.class
    },
    maxIterations = 5,
    parallelStages = { ExplorerAgent.class }
)
public interface SupervisorCoderSystem {

    /**
     * Primary entry point. Orchestrates the full agent pipeline.
     *
     * @param request          natural-language user request
     * @param workingDirectory absolute or relative path to the target
     * @param mode             "CODING" or "PENTEST"
     * @return structured OperationSummary as a single String
     */
    String code(
        @K(UserRequest.class)      String request,
        @K(WorkingDirectory.class) String workingDirectory,
        @K(OperationMode.class)    String mode
    );

    /**
     * Builds the supervisor-level request string injected into each agent's
     * prompt context. Includes detected tools and target scope so the
     * executor can dispatch to the right tool automatically.
     */
    @SupervisorRequest
    static String request(
        @K(UserRequest.class)      String userRequest,
        @K(WorkingDirectory.class) String workingDirectory,
        @K(OperationMode.class)    String mode,
        @K(TargetScope.class)      String targetScope,
        @K(DetectedTools.class)    String availableTools
    ) {
        return """
            Working directory: %s
            Operation mode:    %s
            Target scope:      %s
            Available tools:   [%s]

            Fulfill the following request with full autonomy.
            If a step fails, diagnose and retry up to 3 times
            before reporting final status:

            %s
            """
            .formatted(
                workingDirectory,
                mode,
                targetScope,
                availableTools,
                userRequest
            )
            .stripIndent();
    }

    /**
     * Default implementation — can be used as a standalone orchestrator
     * if you don't need a custom subclass.
     */
    static SupervisorCoderSystem createDefault() {
        return new SupervisorCoderSystem() {
            private final ExecutorService executor =
                Executors.newVirtualThreadPerTaskExecutor();

            @Override
            public String code(String request, String workingDirectory, String mode) {
                var ctx = new ContextMap();
                ctx.put(UserRequest.class, new UserRequest(request));
                ctx.put(WorkingDirectory.class, new WorkingDirectory(workingDirectory));
                ctx.put(OperationMode.class, OperationMode.fromString(mode));

                var orchestrator = new StageOrchestrator(executor, 3);
                return orchestrator.execute(ctx).toString();
            }
        };
    }
}
