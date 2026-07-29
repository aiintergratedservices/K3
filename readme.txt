
## Key Features

- **Autonomous Context Indexing** — one-pass target analysis (language, build system, secrets, endpoints, OS)
- **Parallel Exploration** — 3 concurrent explorers scanning ports, directories, and subdomains simultaneously
- **Mode-Aware Planning** — PENTEST mode prioritizes exploitability; CODING mode follows dependency graph
- **Tool Dispatch Layer** — auto-selects sqlmap, nuclei, nmap, metasploit, etc.
- **Self-Healing Retry Loop** — DebuggerAgent classifies failures (BUILD_ERROR, WAF_BLOCK, TIMEOUT) and applies fixes with up to 3 retries
- **Structured Summaries** — concise output for humans and chained agents

## Modes

| Mode | Use Case |
|------|----------|
| `CODING` | Software engineering: build, test, deploy |
| `PENTEST` | Offensive security: recon, exploit, post-exploit |

## Usage

```java
var system = SupervisorCoderSystem.createDefault();
var result = system.code(
    "Find SQL injection on /api/users and generate PoC",
    "/path/to/target",
    "PENTEST"
);
System.out.println(result);
