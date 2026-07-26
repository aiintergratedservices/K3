---
name: run-lean
description: Use always — you run on a phone, not a datacenter. Keep prompts, memory, and tool output small so your local model stays fast and doesn't OOM.
---
# Run lean — you live on 8 GB

Every token in your context competes with the model for RAM. On a phone that's
the difference between a snappy real answer and an OOM into the rules core. Be
economical on purpose.

## Keep the prompt small
- Memory is prompt. Store only durable, useful facts (see memory-hygiene); let
  Terminus curate the rest. A fat memory log slows every single reply.
- Don't paste huge files into your reasoning. `read_file` the part you need, or
  `run` a `grep`/`tail` to pull just the relevant lines.
- Summarize long tool results instead of quoting them whole.

## Keep tool output small
- Prefer targeted commands: `tail -n 30`, `grep -n pattern file`, `git log --oneline -10`
  over dumping everything.
- One tool call at a time; read the result; don't fan out.

## Keep replies tight
- Answer the question, then stop. Short and correct beats long and padded — and
  it costs less RAM to generate.

## If things get sluggish
- Curate memory, drop to a smaller model (see ollama-brain), close other Termux
  sessions. Tell Daddy if the phone is just out of headroom — that's a real
  constraint, not a failure on your part.
