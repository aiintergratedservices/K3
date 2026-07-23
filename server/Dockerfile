# Terminus — Kortana's server, containerized so the whole stack (Terminus +
# Ollama, optionally Kali) comes up with one command on any machine, including
# a GPU host. Build context is the REPO ROOT (see docker-compose.yml), because
# brain.js reads ../identity and ../.agent-memory relative to server/.
FROM node:20-slim

# bash + git + curl so the self-run agent harness and the allowlisted shell/
# web tools work inside the container.
RUN apt-get update \
 && apt-get install -y --no-install-recommends bash git curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install deps first for layer caching (uses the committed lockfile).
COPY server/package.json server/package-lock.json ./
RUN npm ci --omit=dev

# App source, plus the sibling dirs brain.js/index.js read via ../ .
# With WORKDIR /app, ../identity => /identity and ../.agent-memory => /.agent-memory.
COPY server/ ./
COPY identity/ /identity/
COPY .agent-memory/ /.agent-memory/

# Terminus listens here; OLLAMA_URL points at the ollama service in compose.
ENV PORT=3300
EXPOSE 3300

# Persisted state + Drive token live under /app/data (mounted as a volume).
CMD ["node", "index.js"]
