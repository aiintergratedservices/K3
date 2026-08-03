# Kortana for VS Code

Talk to **Kortana** — your self-hosted Terminus brain — from inside VS Code.
It calls the same `POST /api/brain` endpoint the phone app uses, so there's no
cloud account and no extra service: point it at your Terminus server and go.

## Features
- **Kortana: Open Chat** — a chat panel beside your editor that keeps context.
- **Kortana: Ask a Question** — quick one-off question from the command palette.
- **Kortana: Ask About Selection** — right-click a code selection → ask her about
  it (she gets the code fenced with its language).

## Setup
1. Make sure your Terminus server is running (default `http://127.0.0.1:3300`).
2. Open **Settings → Extensions → Kortana** and set:
   - `kortana.terminusUrl` — your server's base URL (default `http://127.0.0.1:3300`).
   - `kortana.apiKey` — only if your server requires one (sent as `x-api-key` and
     `authorization`). Leave blank for an open localhost server.

## Run it without publishing
This extension has **no build step and no dependencies**. To try it:
1. Open the `kortana-vscode/` folder in VS Code.
2. Press **F5** (Run → Start Debugging) to launch an Extension Development Host.
3. In that new window, run **Kortana: Open Chat** from the command palette.

To install it persistently, package it with [`vsce`](https://github.com/microsoft/vscode-vsce):
`npx @vscode/vsce package` → then `code --install-extension kortana-vscode-0.1.0.vsix`.

## Honest limits
- It's a thin, honest client: it shows exactly what Terminus returns.
- Long replies can take a while — the whole brain chain (and any sub-agents) runs
  server-side; the request waits up to 120s.
