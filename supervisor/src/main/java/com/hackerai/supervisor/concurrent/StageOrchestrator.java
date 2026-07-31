package com.hackerai.supervisor.concurrent;

import com.hackerai.supervisor.agent.*;
import com.hackerai.supervisor.context.ContextMap;
import com.hackerai.supervisor.model.*;
import com.hackerai.supervisor.model.value.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Concurrent stage orchestrator. Executes the multi-agent pipeline
 * using CompletableFuture for parallel fan-out and sequential
 * stage gates.
 *
 * Pipeline:
 *   ContextEngine ──→ ExplorerAgent (parallel) ──→ PlannerAgent
 *   ──→ ImplementerAgent ──→ ExecutorAgent ──→ DebuggerAgent (loop)
 *   ──→ SummarizerAgent
 *
 * Thread pool: virtual threads (Project Loom) for lightweight
 * parallelism. Falls back to platform threads if unavailable.
 */
public class StageOrchestrator {

    private final ExecutorService executor;
    private final int maxRetriesPerStep;

    public StageOrchestrator(ExecutorService executor, int maxRetriesPerStep) {
        this.executor = executor;
        this.maxRetriesPerStep = maxRetriesPerStep;
    }

    public StageOrchestrator() {
        this(Executors.newVirtualThreadPerTaskExecutor(), 3);
    }

    /**
     * Executes the full pipeline and returns the OperationSummary.
     */
    public OperationSummary execute(ContextMap ctx) {
        var startTime = System.nanoTime();
        var results = new CopyOnWriteArrayList<ExecutionResult>();

        try {
            // ─── Stage 1: Context Engine ──────────────────────
            var contextEngine = ContextEngine.createDefault();
            var workingDir = ctx.get(WorkingDirectory.class).value();
            var context = contextEngine.index(workingDir);
            ctx.put(ContextSnapshot.class, context);

            // ─── Stage 2: Explorer Agent (parallel fan-out) ───
            var mode = ctx.get(OperationMode.class);
            var explorer = ExplorerAgent.createDefault();

            // Run 3 parallel explorers for different aspects
            var explorerFutures = new ArrayList<CompletableFuture<ExplorationMap>>();
            for (int i = 0; i < 3; i++) {
                explorerFutures.add(CompletableFuture.supplyAsync(() ->
                    explorer.explore(workingDir, context, mode.mode()), executor));
            }

            // Merge all exploration results
            var explorationMaps = explorerFutures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

            var mergedMap = mergeExplorationMaps(explorationMaps);
            ctx.put(ExplorationMap.class, mergedMap);

            // ─── Stage 3: Planner Agent ───────────────────────
            var planner = PlannerAgent.createDefault();
            var userRequest = ctx.get(UserRequest.class).value();
            var plan = planner.plan(
                userRequest,
                context,
                mergedMap,
                mode.mode()
            );
            ctx.put(ExecutionPlan.class, plan);

            // ─── Stage 4-6: Implement → Execute → Debug loop ──
            var implementer = ImplementerAgent.createDefault();
            var executorAgent = ExecutorAgent.createDefault();
            var debugger = DebuggerAgent.createDefault();

            for (int stepIdx = 0; stepIdx < plan.steps().size(); stepIdx++) {
                var step = plan.steps().get(stepIdx);
                int retryCount = 0;
                boolean stepComplete = false;

                while (!stepComplete && retryCount <= maxRetriesPerStep) {
                    // Implement
                    var implResult = implementer.implement(step, workingDir, context);
                    ctx.put(ImplementationResult.class, implResult);

                    // Execute
                    var execResult = executorAgent.execute(
                        implResult, workingDir, step, mode.mode()
                    );
                    results.add(execResult);

                    if (execResult.success()) {
                        stepComplete = true;
                    } else if (retryCount < maxRetriesPerStep) {
                        // Debug and retry
                        var outcome = debugger.debug(
                            execResult, plan, step, context, retryCount
                        );

                        if (outcome instanceof DebugOutcome.RetryWithFix fix) {
                            retryCount++;
                            // Apply fix via the fileEditor consumer
                            fix.fileEditor().accept(workingDir);
                        } else if (outcome instanceof DebugOutcome.SkipStep) {
                            stepComplete = true; // skip and continue
                        } else if (outcome instanceof DebugOutcome.Abort abort) {
                            throw new RuntimeException(
                                "Pipeline aborted: " + abort.reason()
                            );
                        }
                    } else {
                        retryCount++; // mark as failed, continue to next step
                    }
                }
            }

            // ─── Stage 7: Summarizer Agent ────────────────────
            var summarizer = SummarizerAgent.createDefault();
            var summary = summarizer.summarize(
                plan,
                List.copyOf(results),
                mode.mode()
            );
            ctx.put(OperationSummary.class, summary);

            var elapsed = System.nanoTime() - startTime;
            System.out.println("Pipeline completed in " +
                TimeUnit.NANOSECONDS.toSeconds(elapsed) + "s");

            return summary;

        } catch (Exception e) {
            // Build a failure summary
            return new OperationSummary(
                OperationSummary.OperationStatus.FAILED,
                List.of(),
                List.of(e.getMessage()),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Pipeline failed: " + e.getMessage()
            );
        }
    }

    /**
     * Merges multiple exploration maps from parallel explorers.
     * Deduplicates endpoints, ports, and directories.
     */
    private static ExplorationMap mergeExplorationMaps(
        List<ExplorationMap> maps
    ) {
        var allDirs = new LinkedHashSet<String>();
        var allEndpoints = new LinkedHashMap<String, Endpoint>();
        var allSubdomains = new LinkedHashSet<String>();
        var allPorts = new LinkedHashMap<Integer, OpenPort>();
        var allFlows = new LinkedHashMap<String, DataFlow>();
        var allEntries = new LinkedHashSet<String>();
        var allSignatures = new HashMap<String, String>();

        for (var map : maps) {
            allDirs.addAll(map.directories());
            for (var ep : map.endpoints()) {
                allEndpoints.putIfAbsent(ep.method() + " " + ep.path(), ep);
            }
            allSubdomains.addAll(map.subdomains());
            for (var port : map.openPorts()) {
                allPorts.putIfAbsent(port.port(), port);
            }
            for (var flow : map.dataFlows()) {
                allFlows.putIfAbsent(flow.source() + " -> " + flow.sink(), flow);
            }
            allEntries.addAll(map.entryPoints());
            allSignatures.putAll(map.fileSignatures());
        }

        return new ExplorationMap(
            List.copyOf(allDirs),
            List.copyOf(allEndpoints.values()),
            List.copyOf(allSubdomains),
            List.copyOf(allPorts.values()),
            List.copyOf(allFlows.values()),
            List.copyOf(allEntries),
            Map.copyOf(allSignatures)
        );
    }
}
