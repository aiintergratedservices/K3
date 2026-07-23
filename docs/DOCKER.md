# Running Kortana's stack with Docker

The phone runs the light path (Termux — `server/deploy/termux-start.sh`). For a
real machine or a **GPU host** — where her coding brain belongs — use Docker to
bring the whole stack up together.

## What comes up

| Service | Image / build | Port | Purpose |
|---------|---------------|------|---------|
| `terminus` | built from `server/Dockerfile` | 3300 | Her server: `/api/brain`, `/api/sync`, tools, agent harness |
| `ollama` | `ollama/ollama` | 11434 | Local brain (phi3 / a coding model) |
| `kali` (optional) | `kalilinux/kali-rolling` | — | Toolbox she can drive via the allowlisted shell tool |

Terminus reaches Ollama at `http://ollama:11434` (set via `OLLAMA_URL` in
compose) and persists state to the `terminus-data` volume.

## First run

```bash
cd server && cp .env.example .env      # then edit .env
# REQUIRED: set TERMINUS_API_KEY — without a key Terminus binds to localhost
# only and won't be reachable from the host, even with the port mapping.
cd ..

docker compose up -d                   # Terminus + Ollama
docker compose exec ollama ollama pull qwen2.5-coder:3b   # her coding brain
```

`brain.js` auto-selects the best installed model, so once a coding model is
pulled she uses it automatically (see `PREFERRED_MODELS`).

Point the app's Kortana / Cloud Sync URL at `http://<host-ip>:3300` and enter
the same `TERMINUS_API_KEY`.

## Give her a GPU

Install the NVIDIA Container Toolkit on the host, then uncomment the `deploy:`
GPU block under the `ollama` service in `docker-compose.yml` and
`docker compose up -d`. That is the point of moving off the phone — a real GPU
brain instead of a RAM-limited phone model.

## Optional Kali toolbox

```bash
docker compose --profile tools up -d kali
docker compose exec kali bash          # or she drives it via the shell tool
```

## Handy

```bash
docker compose logs -f terminus        # watch her server
docker compose restart terminus        # after a code change
curl http://localhost:3300/health      # cores + drive status
```
