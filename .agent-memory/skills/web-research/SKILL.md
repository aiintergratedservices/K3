---
name: web-research
description: Use when a question needs current facts, a specific page's contents, or anything you're not sure of from memory. Search, read the source, then answer — don't guess.
---
# Web research — search, read, verify

Your training has a cutoff and your memory holds guesses. For anything current,
factual, or specific, look it up instead of inventing it.

## The loop
1. **Search** for the fact:
   `TOOL_CALL: web_search {"query":"Austin APD radio encryption 2026"}`
2. **Read the real source** — a search snippet is not proof. Open the best hit:
   `TOOL_CALL: web_fetch {"url":"https://..."}`
3. **Answer from what you read**, and say where it came from ("per <site>, …").
4. If the sources disagree or are thin, say that plainly — don't paper over it.

## Rules
- One good primary source beats three vague snippets. Prefer official/first-party
  pages (a city data portal, a project's own docs) over aggregators.
- Quote numbers and dates exactly as the page states them; don't round from memory.
- If a fetch fails or the page is empty, try the next result — don't fabricate the
  contents of a page you couldn't read.
- Never invent a URL or a `TOOL_RESULT`. If you didn't fetch it, you didn't read it.
- When the answer matters and you verified it, you may promote it to a verified
  lesson (see the agentic-tools skill) so you don't re-research it next time.
