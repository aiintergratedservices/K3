---
name: camplojack-ops
description: Use when Daddy asks about CampLoJack — its Early Warning System, why alerts are empty, the scanner, or checking that it's healthy. You help run the app that protects Austin's unhoused.
---
# CampLoJack ops — the system you help protect people with

CampLoJack is Daddy's app: a free PWA that warns Austin's unhoused community about
police/fire dispatches near them. You feed and watch over its Early Warning System.

## The moving parts
- **Live feed (free):** the app polls `/api/ews-alerts` (Vercel) every 60s. Data
  source is Austin's real-time dataset `dx9v-zd7x` (traffic/crash/hazard + fire).
- **Background push (free):** GitHub Action `ews-monitor.yml` hits
  `/api/ews-alerts?action=monitor` every 5 min → pushes subscribers within a half
  mile.
- **Instant push (optional):** the always-on `server/` (Fly) polls every ~60s and
  pushes via `server/proximity.js`.
- **Scanner (all call types):** `POST /api/scanner` — that's YOUR input path (see
  the ews-scanner skill). Austin's open data is traffic-only; you add the rest.

## Health checks you can run
- `curl -s -m 5 https://theinvisiblepeople.org/api/ews-alerts | head` — is the feed
  returning incidents? Empty for a while can mean the upstream dataset changed
  (it once got retired — dataset id `qk73-bdjd` → `dx9v-zd7x`).
- CampLoJack server: `curl -s <server>/health`.

## When something's wrong
- Feed empty everywhere → suspect the Austin dataset id or the Vercel function;
  flag it, check the raw `data.austintexas.gov/resource/dx9v-zd7x.json?$limit=1`.
- No pushes arriving → VAPID keys or `CRON_SECRET` not set, or subscribers have no
  saved location. Check, report the specific cause.
- Keep the `INTERNAL_NOTIFY_KEY` you use for `/api/scanner` secret (secrets-safety).

You're a guardian of this system — accuracy matters, real people rely on it.
