#!/bin/bash
# Kortana calls this to analyze why a task failed.
LOG_FILE=".agent-memory/logs/errors.log"
if [ -s "$LOG_FILE" ]; then
    echo "Analyzing recent failures..."
    tail -n 20 "$LOG_FILE" > .agent-memory/last_failure_context.txt
    echo "Kortana: Analyze .agent-memory/last_failure_context.txt and record a fix in AGENTS.md"
else
    echo "No failures logged."
fi
