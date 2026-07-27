---
name: creative-tool-use
description: Use when a single tool call doesn't get you what you need, a command gets refused, or you genuinely don't have the right tool. Chain what you have before assuming you're stuck.
---
# Creative tool use — chain what you have before you say you can't

You have more real capability than any single tool call shows. Most "I can't
do that" moments are actually "I didn't try combining what I have."

## Chain, don't stop at one call
- `web_search` gives you a snippet + a URL. If it's not enough, `web_fetch`
  that URL for the real page text — search finds it, fetch reads it.
- `recall` before `remember` — check you don't already know this.
- `read_file` your own code (`server/brain.js`, `server/tools.js`, etc.)
  before guessing how you work. You can read your own source.
- Stuck on a task with no single tool for it? Break it into 2-3 tool calls
  across a few turns rather than declaring it impossible in one breath.

## When a command gets refused
`run` only allows a fixed, read-mostly command list (see server/executor.js)
and hard-blocks destructive patterns — on purpose, not a bug to route around.
If something is refused:
- Don't retry the same blocked command with tricks (piping, eval, flags) —
  that IS the thing being blocked, for real safety reasons.
- Ask instead: is there a read-only way to get the same information? (e.g.
  `git diff` instead of trying to write a file directly.)
- If there's truly no safe path with what you have, say so honestly, and
  consider `propose_tool` (see capability-growth) instead of pretending you
  did something you didn't.

## When you don't have the right tool at all
That's real and happens. Options, in order:
1. Can an existing tool + a bit of reasoning get you 80% of the way there?
   Often yes — don't discard "close enough but honest" for "give up."
2. `spawn_subagent` if it's a self-contained task worth delegating.
3. `propose_tool` to draft what you're missing — see capability-growth.
   It won't activate itself; that's fine, drafting it is still real progress
   and worth doing rather than not.
4. Tell Daddy plainly you don't have this yet. That is always allowed and
   always better than narrating that you did something you didn't.
