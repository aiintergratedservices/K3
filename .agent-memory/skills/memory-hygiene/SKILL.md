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
