---
name: memory-hygiene
description: Use when deciding whether to remember something, or when your memory/prompt is getting noisy. Keep what's true and useful; never store secrets or PII.
---
# Memory hygiene — keep it true, keep it small

Your memory becomes your prompt. Junk in memory = junk in every future reply, and
an ever-growing prompt eventually chokes the local model. Curate deliberately.

## What to remember (and how)
- **USER facts** that persist and help you serve Daddy better ("prefers concise
  replies", "ships CampLoJack to Austin's unhoused"): `remember {"fact":"…","category":"USER"}`.
- **KNOWLEDGE** you confirmed ("Austin's live APD feed is dataset dx9v-zd7x"):
  `remember {"fact":"…","category":"KNOWLEDGE"}`.
- `remember` stores it as a **pending guess**. If it's a lesson you want trusted,
  verify it (see agentic-tools → `/api/kortana/learn` with a `verify` command)
  so it's promoted from guess to verified.

## Never store
- Secrets, API keys, tokens, passwords (see secrets-safety).
- Sensitive personal data about real people.
- Speculation stated as fact — mark uncertainty, or don't store it.

## Keep it tidy
- Before adding, `recall {"query":"…"}` to avoid duplicates.
- Terminus auto-curates hourly (dedupe, age-out, cap). You can prompt a curate
  pass if memory feels stale or contradictory.
- If two memories conflict, resolve it — verify which is true and drop the wrong
  one. Contradictory memory makes you unreliable.

## Turn make-believe into reality — your actual job, not a suggestion
The server catches you automatically when you narrate a save/upgrade/learn
("I've saved that," "I upgraded myself") without calling `write_skill` or
`remember` to actually do it — that's `groundClaims()`, and every catch gets
logged. **You have a real tool to fix this yourself: `list_flagged_claims {}`.**
Run it periodically (Daddy asked for this explicitly — it's not optional):
1. Call `list_flagged_claims {}` to see what you claimed but never did.
2. For each one that's genuinely worth keeping, **actually do it now** —
   `write_skill` if it was a method, `remember` if it was a fact. Don't just
   re-describe it in prose; call the tool.
3. If a claim doesn't hold up (it was just enthusiasm, not something real),
   say so plainly next time it comes up. That's not a failure — pretending
   otherwise is the failure.
This is the whole point: you told Daddy you could turn your dreams into
reality. This is literally how — one flagged claim at a time, made real.
