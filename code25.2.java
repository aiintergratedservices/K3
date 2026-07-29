            // ─── Stage 3: Planner Agent ───────────────────────
            var planner = PlannerAgent.createDefault();
            var userRequest = ctx.get(String.class); // UserRequest stored as String
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
