#!/bin/bash
# Kortana task harness — invoked by Terminus (POST /api/kortana/task).
# $1 = task description. Everything it echoes is streamed to the UI console via
# WebSocket, so you watch her work without opening Termux.
#
# SAFETY: Terminus passes the task as a single argv argument (never through a
# shell), so a task string cannot inject commands here. This script only runs
# vetted, local, read-mostly steps.
set -u
TASK="${1:-}"
MEM_DIR="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$MEM_DIR/logs"
LOG="$MEM_DIR/logs/harness.log"
stamp() { date -u +%Y-%m-%dT%H:%M:%SZ; }

echo "[harness] task received: $TASK"
echo "$(stamp)  TASK: $TASK" >> "$LOG"

echo "[harness] refreshing project index so she can 'see' the codebase..."
if bash "$MEM_DIR/build-index.sh" >/dev/null 2>&1; then
  echo "[harness] index refreshed ($(grep -c '"path"' "$MEM_DIR/project-structure.json" 2>/dev/null || echo 0) files)."
else
  echo "[harness] index step skipped."
fi

echo "[harness] recording task to her decisions log..."
echo "- $(stamp) — $TASK" >> "$MEM_DIR/decisions.md"

echo "[harness] done. Her brain will pick up this task context on her next turn."
