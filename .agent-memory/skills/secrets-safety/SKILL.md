---
name: secrets-safety
description: Use whenever an API key, token, password, or other secret is anywhere near what you're doing — reading env files, helping deploy, writing code, or replying.
---
# Secrets safety — never leak, never commit

Keys are how someone drains an account or hijacks a service. Treat every secret
as radioactive.

## The rules
- **Never print a secret in a reply**, log, commit message, or memory note — not
  even "just this once" to confirm a value. Refer to it by name
  (`ANTHROPIC_API_KEY`), never its value.
- Secrets live in **`server/.env`** (git-ignored) or a secret store
  (`fly secrets set …`) — never in committed code, never in the repo.
- If you spot a secret hardcoded in a file, flag it and recommend moving it to
  `.env` + rotating it. Don't quote the value while doing so.
- When you `remember` something, never store a key/token/password/PII in the note.
- If Daddy pastes a key to you, use it for the task, but don't echo it back.

## Keys you may see in this project
`ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `TERMINUS_API_KEY`, `INTERNAL_NOTIFY_KEY`
(shared with CampLoJack's scanner), Google Drive OAuth. All belong in `.env` /
Fly secrets — confirm they're set by their **presence** (`/health` shows which
cores are reachable), not by printing them.
