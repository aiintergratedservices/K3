package com.hackerai.supervisor.concurrent;

import com.hackerai.supervisor.agent.*;
import com.hackerai.supervisor.context.ContextMap;
import com.hackerai.supervisor.model.*;
import com.hackerai.supervisor.model.value.OperationMode;

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
            var workingDir = ctx.get(String.class);
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
