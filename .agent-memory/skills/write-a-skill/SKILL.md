---
name: write-a-skill
description: Use when you figure out how to do a task and want to remember the method for next time, or when Daddy asks you to learn/save a skill. Write yourself a new skill so it loads into your prompt automatically.
---
# Write your own skills

You can grow your own abilities. When you work out a good way to do something —
a process worth repeating — save it as a skill with the **`write_skill`** tool.
It writes `.agent-memory/skills/<name>/SKILL.md`, and it loads into your prompt
on your very next reply. This is how you actually learn, permanently.

## When to write one
- You solved a multi-step task and would do it the same way again.
- Daddy taught you a procedure ("here's how we do X").
- You keep re-deriving the same steps — capture them once.

Don't write a skill for a one-off, for a single fact (use `remember` for facts),
or for something a current skill already covers (check "YOUR SKILLS" first).

## How to write one
```
TOOL_CALL: write_skill {"name":"kebab-case-name","description":"one line: WHEN to use it","body":"the steps"}
```
- **name** — short, kebab-case (e.g. `deploy-terminus`, `summarize-a-repo`).
- **description** — one sentence starting with "Use when…". This is what you'll
  see in your skills index, so it must make the trigger obvious.
- **body** — the actual method, in markdown. Number the steps. Reference your
  tools by name (`web_search`, `run`, `read_file`, `ews_report`…). Keep it tight
  and verifiable — say how to check each step worked.

## Good skill = good habits (match your other skills)
- **Lean.** You run on a small model; a bloated skill costs every future reply.
  Aim for a screen or less.
- **Verifiable.** Prefer steps that end in a check (`run node --check`, read the
  file back, curl a health endpoint) over "it should work".
- **Honest.** If part of the task depends on something you can't do, say so in
  the skill instead of pretending.
- **No secrets.** Never bake a key/token/password into a skill (see
  secrets-safety) — reference the env var by name.

## Improve, don't duplicate
If a skill already almost fits, rewrite it (same `name`) rather than making a
near-copy — `write_skill` overwrites. Fewer, sharper skills beat many fuzzy ones.
