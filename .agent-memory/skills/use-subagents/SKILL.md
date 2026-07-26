---
name: use-subagents
description: Use when a task is big or self-contained enough to hand off — research, a long summary, a chunk of code — so you can keep talking to Daddy while it runs. Delegate it to a sub-agent on your secondary brain.
---
# Spawn sub-agents (protect your own brain)

You can hand a focused task to a **sub-agent** with the **`spawn_subagent`**
tool. The sub-agent runs on your **secondary brain** (a separate Terminus set in
`SUBAGENT_BRAIN_URL`), never on your main one — so delegating never slows down
your conversation with Daddy. **Your brain is always the priority.**

## When to delegate
- The task is **self-contained**: it has a clear goal and returns one result
  (research a topic, summarize a long doc, draft a function, check something).
- It would otherwise **tie you up** while Daddy is waiting.
- It doesn't need the back-and-forth of a real conversation.

Do NOT delegate the actual conversation with Daddy, anything needing his live
input, or a trivial thing you can just answer yourself.

## How
```
TOOL_CALL: spawn_subagent {"task":"Research X and give me 3 bullet takeaways","context":"optional background it needs"}
```
- **task** — a complete, standalone instruction. The sub-agent starts fresh with
  no memory of this chat, so put everything it needs in `task` + `context`.
- You get back its result. Read it, sanity-check it (it ran on a different brain
  and could be wrong), then use it in your reply. Don't trust it blindly.

## If it says "no secondary brain configured"
Then `SUBAGENT_BRAIN_URL` isn't set. Tell Daddy: sub-agents need a second Terminus
online to run on, so your own brain stays free — until that's set up, just do the
task yourself if it's small, or tell him it's too big to run without help.

## Discipline
- **One clear job per sub-agent.** Don't hand it a vague pile — it can't ask you
  to clarify.
- **You own the result.** You spawned it, so you check and stand behind what it
  returns (see answer-honestly).
