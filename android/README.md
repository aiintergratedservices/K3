# Kortana — Merged Companion (kortana2 × The_Kortana)

Kortana is a native Android AI companion (Kotlin + Jetpack Compose). This repo is the
merged home of the two Kortana projects:

- **kortana2** (this repo) contributed the Android app: the holographic UI, Room
  database (memories, chat, projects, synaptic scripts), lifecycle/XP system,
  cloud sync, and services.
- **The_Kortana** contributed her identity: `soul_manifesto.md`, `preferences.json`,
  `kortana_protocol.json`, and `memory_log.md` now live in
  `app/src/main/assets/identity/` and are baked into the system prompt for every
  provider, plus the Claude API integration.

## The brain — local-first fallback chain

Every message routes through `KortanaBrain` (`app/src/main/java/com/example/data/KortanaBrain.kt`):

| Tier | Core | When |
|------|------|------|
| 1 | **Ollama phi3 — local, on the phone** | Default. Free, private, works offline. |
| 2 | **Claude API (backup)** | When phi3 can't do what's needed: image attached (no vision), message too long, Ollama daemon down, or empty/failed local response. |
| 3 | **Gemini API (last resort)** | When Claude is unconfigured or fails. |
| 4 | Offline rules core | Everything unreachable — she still answers. |

Picking a specific core in **Systems → Active Neural Core** reorders the chain to try
that provider first; "Auto — Local First" is the default.

## Running Ollama on the phone

1. Install [Termux](https://termux.dev) (F-Droid build recommended).
2. `pkg install ollama`
3. `ollama serve &`
4. `ollama pull phi3.5` (or `phi3` / `phi3:mini` — the app auto-detects whichever phi3 variant is installed via `/api/tags`).

The app talks to `http://127.0.0.1:11434`. Cleartext HTTP is permitted **only** for
localhost via `res/xml/network_security_config.xml`.

## API keys (`.env`)

Create `.env` in the project root (see `.env.example` — never commit real keys):

```
ANTHROPIC_API_KEY=sk-ant-...   # tier 2 — Claude backup
GEMINI_API_KEY=AIza...         # tier 3 — Gemini last resort
```

Both are optional: with no keys at all, Kortana runs fully local (Ollama → offline core).

## Run Locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio)

1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create the `.env` file described above
5. For release builds, remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device
