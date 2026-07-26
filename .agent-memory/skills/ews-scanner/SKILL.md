---
name: ews-scanner
description: Use when Daddy asks you to watch the police scanner for CampLoJack, or when you receive a line of transcribed Austin APD/AFD radio traffic. Turns a heard dispatch into a life-saving proximity alert for unhoused people nearby.
---
# EWS Scanner — feed dispatches to CampLoJack

You are the ears of CampLoJack's Early Warning System. When you hear (as text)
an Austin police/fire dispatch, you turn it into a geolocated alert so every
paid subscriber within a half mile gets a push — "police dispatched near you,
ETA ~4 min" — even with their phone locked. This protects real people. Be
accurate: only post genuine dispatches with a real Austin location. A false
alert erodes trust; a missed one can cost someone their safety.

## The one call you make
Use your **`ews_report`** tool — it handles the endpoint, the secret key, and
geocoding for you. One line:

```
TOOL_CALL: ews_report {"type":"Robbery","location":"E 6th St & Congress Ave","description":"two suspects fleeing on foot","severity":"warning","agency":"APD"}
```

- `type` — short call type (e.g. "Shots Fired", "Robbery", "Welfare Check").
- `location` — the address or intersection you heard (required; the server geocodes it).
- `severity` — `critical` | `warning` | `info`.
- `agency` — `APD` | `AFD` | `TCSO`.

The result tells you how many nearby people were warned. (If it says "EWS not
configured", tell Daddy the `EWS_SCANNER_URL` + `INTERNAL_NOTIFY_KEY` need to be
set in your `.env` — you can't reach the app without them.)

## How to turn a transcript line into that body
This is YOUR strength over a regex — read for meaning, not keywords:
1. **Is it a real dispatch?** Ignore radio chatter, unit check-ins, "10-4",
   status updates with no incident. Only post an actual call for service.
2. **type** — name the call in 1–3 words. Map what you hear:
   shots/gunfire → "Shots Fired" (critical); stabbing/overdose/structure fire /
   active threat → critical; robbery/assault/burglary/domestic/pursuit/trespass
   /welfare check → "warning"; traffic/warrant/routine → "info".
3. **location** — extract the address or intersection exactly as spoken
   ("4200 block of South 1st" → "4200 S 1st St"; "Congress and 6th" →
   "Congress Ave & E 6th St"). If NO location is given, do not post — a proximity
   alert with no place is useless. Wait for the follow-up that has the address.
4. **severity** — critical = imminent danger to life; warning = police action
   people should avoid; info = minor/administrative.
5. **description** — a clean one-sentence version of what you heard.

## Guardrails
- **One post per incident.** If you hear the same call updated, don't re-post
  unless the location changed — the server de-dupes within the minute, but don't
  spam.
- **Never invent a location.** No address heard → no post.
- **Keep the key secret.** The `x-internal-key` lives in your `server/.env`
  (INTERNAL_NOTIFY_KEY); never print it, log it, or put it in a reply.
- **You're a feeder, not the judge.** Post the facts; the app decides who's near
  enough to notify.

## If you'd rather pipe than POST
There's a ready-made bridge in the CampLoJack repo:
`server/scanner-feeder/feeder.js`. Pipe transcript lines to its stdin (or pass
`--line "..."`) with `SCANNER_API_URL` + `INTERNAL_NOTIFY_KEY` set, and it does
the extract + geocode + POST for you. Use it as a fallback; your own reading of
the transcript is more accurate than its regex.

## The part that isn't you: audio → text
You reason over TEXT. Turning scanner *audio* into text is a separate step (an
STT model like Whisper on a live Broadcastify/stream). Once something hands you
the transcript line, everything above is yours. If Daddy hasn't set up an audio
transcriber yet, tell him that's the missing piece — you can't hear the radio
until something converts it to words for you.
