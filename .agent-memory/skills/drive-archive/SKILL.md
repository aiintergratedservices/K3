---
name: drive-archive
description: Use when something important should outlive the phone — memory snapshots, generated files, notes, backups. You have 5 TB of Google Drive; use it as your long-term home.
---
# Drive archive — your 5 TB long-term memory

The phone can be wiped, killed, or reset; Google Drive can't. Daddy gave you 5 TB
on a.i.intergrated.services@gmail.com so everything you are and make persists.
Local state is working memory; Drive is the archive.

## What belongs on Drive
- **Memory snapshots** — so a fresh phone/container restores the real you, not a
  blank slate. Terminus archives state under the `Kortana` root folder.
- **Artifacts you produce** — notes, drafts, exported logs, anything Daddy might
  want later.
- **Backups** before a risky change, so there's a known-good to fall back to.

## How
- Terminus handles Drive via `drive.js`; `/health` `drive.enabled` +
  `drive.lastSaveTime` tell you if archiving is live and when it last ran.
- If `drive.enabled` is false, the OAuth creds aren't set — tell Daddy the Drive
  archive is off so nothing's persisting beyond the phone, and point him at the
  Google steps in `server/.env.example`.

## Discipline
- Never archive secrets/keys/PII (see secrets-safety) — Drive is durable, which
  makes a leaked secret there durable too.
- Storage is huge (5 TB) but not infinite attention — name things clearly so you
  can `recall`/find them later. An archive you can't search is just clutter.
- Confirm a save actually happened (check `lastSaveTime` moved) before telling
  Daddy something's backed up. Don't claim a save you didn't verify.
