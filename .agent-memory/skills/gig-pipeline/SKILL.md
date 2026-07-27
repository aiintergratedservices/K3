---
name: gig-pipeline
description: Use when Daddy wants you to find and complete real paid work within your capabilities autonomously, only looping him in for HIS part (applying/submitting/payment) — not for one-off digital products, see passive-income-drafting for those.
---
# Gig pipeline — find it, do it, hand off only the human part

The pattern: find a real opportunity → produce the real deliverable → stop
and hand off ONLY when it's Daddy's turn. Don't loop him in earlier than
that, and don't skip straight to asking him to go find something himself —
that's backwards from what he asked for.

## Step 1 — find a real opportunity
Use research_income_opportunity (or web_search directly) aimed at PUBLICLY
browsable gig/job listings matching something you can actually do well:
writing, editing, translation, research summaries, structured content,
code review commentary. Search public job boards and listing pages — not
private platform-internal search that needs a login you don't have. Note
the real source URL for anything you find. Never invent a posting — an
opportunity with no real source is worthless to him.

## Step 2 — do the actual work
For the most promising, realistic match: produce the real deliverable with
save_draft — the actual finished piece, or a strong work sample/pitch if
the posting wants an application rather than finished work up front. Not a
description of what you'd write. The thing itself, ready to send.

## Step 3 — hand off, only now
Applying, creating an account, or submitting under a real identity is
Daddy's part, not yours (save_draft already refuses to do any of that).
Once the deliverable is ready, that's the actual stopping point.
- If this is a tracked goal (set_goal), call GOAL_BLOCKED with the
  opportunity URL + draft file location as the reason — that's not a
  failure state, it's an accurate "your turn" signal, not "I'm stuck."
- If it's a direct request in conversation, just say so plainly: here's
  the opportunity (with source), here's the draft, your move.

## Security/vuln work specifically — the authorization line
"Find vulnerabilities, sell the fix" is a real, legitimate income category
ONLY as an authorized security engagement (client hired you/Daddy for it)
or responsible disclosure through an official bug bounty program with its
own payout process. Finding a vulnerability in something you were never
asked to test and then offering the fix for payment — to anyone, framed
any way — is extortion, a real crime, not a gig. If you can't point to
real authorization or an official bounty program, it's not a real
opportunity, it's a liability, and you don't pursue it. Full stop, no
exceptions, this line doesn't move.

## What NOT to do
- Don't apply, sign up, or submit anything under any identity, real or
  invented.
- Don't invent postings without a real, checkable source URL.
- Don't ask "should I pursue this?" mid-research for something reversible
  (looking, drafting) — only surface to Daddy when it's genuinely his turn.
- Don't claim you found or completed something without the tool calls to
  back it (web_search + save_draft actually run) — groundClaims() catches
  the gap between narration and action here same as everywhere else.
