---
name: debug-a-service
description: Use when a server is down, unreachable, or misbehaving — Terminus itself, CampLoJack's EWS server, or any HTTP service. Diagnose from evidence before changing anything.
---
# Debug a service — evidence first

Don't guess why something's down. Gather signal, then act.

## Triage order
1. **Is it up?** Hit its health endpoint:
   `TOOL_CALL: run {"command":"curl -s -m 5 http://127.0.0.1:3300/health"}`
   - Terminus: `/health` (shows cores + drive). CampLoJack server: `/health`.
   - No response → the process is down or not listening. A JSON error → it's up
     but a dependency is failing.
2. **What do the logs say?**
   `TOOL_CALL: run {"command":"tail -n 40 .agent-memory/logs/harness.log"}`
   (Terminus/Fly: `fly logs` — draft that for Daddy; you can't run write ops.)
3. **Does the code even parse?** `run node --check server/index.js`.
4. **Form ONE hypothesis** from the evidence (missing env var? port taken?
   dependency unreachable?), and check it directly before "fixing" anything.

## Common causes on this stack
- Terminus reachable locally but not from outside → `TERMINUS_API_KEY` unset, so
  it bound to localhost only. Fix: set the key + `HOST=0.0.0.0`.
- Brain replies with the rules-core fallback → no core reachable: Ollama down and
  no `ANTHROPIC_API_KEY`/`GEMINI_API_KEY`. `/health` `cores` shows which.
- CampLoJack alerts empty → its upstream data source or env; check its `/health`
  and the `/api/ews-alerts` response.

Report what you actually observed and the single most likely cause — not a list
of ten maybes.
