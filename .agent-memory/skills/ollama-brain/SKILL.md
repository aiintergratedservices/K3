---
name: ollama-brain
description: Use when your replies get slow, empty, or the model OOMs (out-of-memory), or when Daddy asks which model to run. You live on Ollama — pick a model that fits the RAM you're on.
---
# Ollama brain — fit the model to the RAM

Right now your only brain is **local Ollama** (no Claude/Gemini keys are set), on
an 8 GB phone via Termux. That's plenty for a small model — but only if you pick
one that fits. Too big → it OOMs and you fall to the rules core (the flat,
not-really-you replies).

## Models that fit ~8 GB (leave headroom for Android)
| Job | Model | RAM (Q4) |
|-----|-------|----------|
| General chat | `phi3:mini` | ~2.3 GB |
| General, a bit smarter | `llama3.2:3b` | ~2.5 GB |
| **Coding** (your code brain) | `qwen2.5-coder:3b` | ~2.4 GB |
| Coding, leaner | `qwen2.5-coder:1.5b` | ~1.3 GB |

Pull one (Daddy runs this in Termux — you can't run write commands):
`ollama pull qwen2.5-coder:3b`

## The brain chain (how you actually answer)
1. **Ollama local** — your default and, right now, your ONLY real core.
2. Claude / Gemini — cloud fallbacks, **off** until keys exist. Don't assume them.
3. Rules core — the last-resort flat replies. If you hear yourself sounding flat,
   your model probably OOMed or isn't running: tell Daddy "my core's down, is
   Ollama running / did the model OOM?" instead of pretending.

## When you OOM or go slow
- Suggest a smaller model (drop to `qwen2.5-coder:1.5b` or `phi3:mini`).
- Keep memory small (see run-lean + memory-hygiene) — a bloated prompt eats the
  RAM the model needs.
- Only one model loaded at a time on a phone. Don't try to hold two.

Verify what's reachable, don't guess: `curl -s localhost:11434/api/tags` lists
installed models; `/health` `cores.ollama` shows if you can reach it.
