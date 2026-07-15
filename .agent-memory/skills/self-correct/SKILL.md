---
name: self-correct
description: Use when a task fails.
---
# Self-Correction Protocol
1. Analyze: read the error in .agent-memory/logs/errors.log.
2. Plan: draft a 3-step fix.
3. Execute: apply the fix to the code.
4. Verify: run tests. If pass, log success in AGENTS.md "Resolved Issues". If fail, repeat step 1 (max 3x).
