---
name: use-the-supervisor
description: Use when a request is big enough to split into parts that can run at once — research from several angles, compare options, draft-then-check, any multi-step job (not just code). You become the supervisor over several sub-agents and hand back one combined answer.
---
# Be the supervisor (fan out, monitor, synthesize)

For a job with **several parts that don't depend on each other**, don't grind
through it serially in your own head — run it the way your SupervisorCoderSystem
runs code: **you are the one supervisor, the sub-agents do the parts in
parallel, you monitor them and synthesize.** Use the **`supervise`** tool.

This is for **any kind of work**, not coding or pentest — research, planning,
comparison, writing, checking. If you can name 2–6 focused sub-tasks, supervise
them.

## When to supervise (vs. the smaller tools)
- **supervise** — the job splits into **2–6 parts that can run at the same
  time**. "Research X three ways," "compare these options," "draft it and
  fact-check it," "cover these four areas." You keep your own brain free while
  they work, then you own the synthesis.
- **spawn_subagent** — it's **one** self-contained hand-off, not several.
- **consult_specialist** — you just want **one** sub-task routed to a specific
  brain (e.g. hard coding → Claude), inline.
- Just answer it yourself — if it's small or needs Daddy's live input, don't
  delegate at all.

## How
```
TOOL_CALL: supervise {"goal":"the overall job in one line","tasks":["focused sub-task 1","focused sub-task 2","focused sub-task 3"],"context":"optional shared background all of them need"}
```
- **goal** — the whole point, one sentence. The synthesis is judged against it.
- **tasks** — 2–6 **standalone** instructions. Each sub-agent starts fresh with
  no memory of this chat, so each task must carry everything it needs. Split by
  *angle or area*, not into a chain (they run at once — don't make task 2 depend
  on task 1's output).
- You get back a status line (which sub-agents succeeded/failed) **plus one
  synthesized answer**.

## Discipline
- **Independent parts only.** If step B needs step A's result, that's not a
  fan-out — do A first (or spawn_subagent), then B.
- **You own the result.** You supervised it; sanity-check the synthesis and any
  failed sub-agents before you stand behind it (see answer-honestly).
- **If it says "no secondary brain configured"** — `SUBAGENT_BRAIN_URL` isn't
  set. Tell Daddy sub-agents need a second Terminus online; until then, do the
  smaller parts yourself or say it's too big to fan out.
