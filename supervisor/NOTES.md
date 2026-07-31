# SupervisorCoderSystem — build notes

Multi-agent orchestrator (one supervisor coordinating ContextEngine, 3 parallel
Explorers, Planner, Implementer, Executor, Debugger, Summarizer).

## Why this folder exists
The sources were originally committed to the repo root as flat, mis-named files
(`code1.java` … `code31.java`). Java requires every public type to live in a file
named after it, under a directory matching its `package`, so **none of it could
compile** — which is why the supervisor could never actually run its sub-agents.

They have been reorganized here into the correct package tree
(`src/main/java/com/hackerai/supervisor/...`) and the compile/run bugs fixed
(split StageOrchestrator rejoined; missing marker key types added; illegal
interface fields, a junk `Pattern` field, a missing import, an undeclared
`startTime`, an invalid `@Override`, an unhandled `NoSuchAlgorithmException`, and
a `ContextMap` key collision all fixed).

## Build / run
```
./build.sh                       # compile
./build.sh run /path/to/target CODING
```
Requires JDK 21+ (uses virtual threads). Zero external dependencies.
