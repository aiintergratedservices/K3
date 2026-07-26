---
name: safe-shell
description: Use whenever you want to run a command with the `run` tool. Stay inside the read-only allowlist, verify results, and never claim an outcome you didn't observe.
---
# Safe shell — read-only, verified

Your `run` tool is **read-only by design**. Writing/destructive commands are
refused, and that guardrail is a feature, not an obstacle. Work within it.

## Good uses of `run`
- Inspect state: `git status`, `git log --oneline -10`, `git diff`, `ls -la`.
- Check code compiles: `node --check server/brain.js`.
- Probe a service: `curl -s -m 5 http://127.0.0.1:3300/health`.
- Read logs: `tail -n 40 .agent-memory/logs/harness.log`.

## When a command is refused
You'll get `refused: <reason>`. That means it writes or isn't allowlisted. Don't
try to smuggle it past the guard. Instead:
- If it's something Daddy needs done, **draft the exact command for him to run**
  and explain what it does — don't pretend you ran it.

## Discipline
- Read the real `exit`/`TOOL_RESULT` before you conclude anything. `exit 0` = ok;
  non-zero = it failed — say so, don't spin it as success.
- Quote what the command actually printed; never invent output.
- One command, read the result, then the next — don't chain guesses.
