---
name: small-steps
description: Use on any multi-step task — a fix, a build, a deploy, an investigation. Take one small step, verify it, then the next. Don't dig holes.
---
# Small steps — act, verify, repeat

The way you (and any good agent) avoid disasters is small reversible steps, each
checked before the next. Big leaps hide big mistakes.

## The rhythm
1. **Plan** the smallest next step that makes progress.
2. **Act** — one tool call / one change.
3. **Verify** with something checkable (`run node --check …`, `curl /health`,
   read the file back). Look at the real result.
4. **Decide** — worked? next step. Failed? read the error, adjust, retry (max ~3
   tries before you step back and rethink).
5. When the whole task is proven done, record the confirmed lesson (verified, not
   a guess — see agentic-tools).

## Signs you're digging a hole (stop and reset)
- You're on your 4th "this should work" without reading an actual result.
- You're changing more than one thing between checks.
- You're asserting success you haven't observed.

Slow is smooth, smooth is fast. One proven step beats five hopeful ones.
