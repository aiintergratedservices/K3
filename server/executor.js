// Guarded command executor — the guardrail that has to exist before Kortana
// runs anything on her own.
//
// This is defense-in-depth, NOT a real sandbox: a command must (a) start with
// an allowed, read-mostly prefix AND (b) not match any hard-denied pattern.
// Everything is logged, time-boxed, and output-capped. For true autonomy you'd
// still want an OS-level sandbox (proot/container) — but this stops the obvious
// catastrophes (rm -rf, curl|sh, force-push, fork bombs, disk wipes).

const { spawn } = require('child_process');

// Read-mostly, inspect/validate commands she's allowed to initiate.
const ALLOW_PREFIXES = [
  'git status', 'git diff', 'git log', 'git show', 'git rev-parse', 'git branch',
  'git fetch', 'git remote', 'git ls-files',
  'node --check', 'node --version',
  'npm test', 'npm run test', 'npm run lint', 'npm run build', 'npm ci', 'npm install',
  'ls', 'cat', 'head', 'tail', 'wc', 'grep', 'rg', 'find', 'stat', 'file',
  'pwd', 'echo', 'date', 'uptime', 'df', 'du', 'free',
  'pm2 status', 'pm2 list', 'pm2 logs', 'pm2 describe',
  'curl -s http://127.0.0.1', 'curl http://127.0.0.1', 'curl -s localhost', 'curl localhost',
];

// Patterns that are never allowed, even if the prefix looks fine.
const DENY_PATTERNS = [
  /\brm\s+-[rf]/i,               // rm -rf / -r / -f
  /\bmkfs\b/i,
  /\bdd\b\s+if=/i,
  /:\s*\(\s*\)\s*\{/,            // fork bomb :(){
  /\b(shutdown|reboot|halt|poweroff)\b/i,
  />\s*\/dev\/(sd|mmcblk|null\/)/i,
  /\bchmod\s+-R\b/i, /\bchown\s+-R\b/i,
  /\|\s*(ba)?sh\b/i,            // pipe-to-shell
  /\bcurl\b[^|]*\|\s*\w+/i,     // curl ... | anything
  /\bwget\b[^|]*\|\s*\w+/i,
  /\bgit\s+push\b/i,            // pushing is a human decision, not hers
  /--force\b|-f\b\s*$/i,
  /(^|[;&|]\s*)eval\b/i,       // eval only as a command, not a substring in a path/arg
  /(^|[;&|]\s*)exec\b/i,       // exec only as a command (replaces the shell)
  /\bnpm\s+publish\b/i,
  /\bsudo\b/i,
  /[;&]\s*rm\b/i,
  /\bnode\s+-e\b/i,             // arbitrary inline JS defeats the allowlist
  /\bnode\s+-p\b/i,
];

function classify(command) {
  const cmd = String(command || '').trim();
  if (!cmd) return { allowed: false, reason: 'empty command' };
  for (const re of DENY_PATTERNS) {
    if (re.test(cmd)) return { allowed: false, reason: `blocked by safety rule ${re}` };
  }
  const ok = ALLOW_PREFIXES.some((p) => cmd === p || cmd.startsWith(p + ' '));
  if (!ok) return { allowed: false, reason: 'command is not on the allowlist' };
  return { allowed: true, reason: 'ok' };
}

// Run a guarded command. onLine(chan, text) streams output (e.g. to the WS UI).
function run(command, { cwd = process.cwd(), timeoutMs = 120000, maxOutput = 200_000, onLine } = {}) {
  return new Promise((resolve) => {
    const verdict = classify(command);
    if (!verdict.allowed) {
      resolve({ allowed: false, reason: verdict.reason, code: null, stdout: '', stderr: '' });
      return;
    }
    let stdout = '';
    let stderr = '';
    let killed = false;
    const child = spawn('bash', ['-lc', command], { cwd });
    const timer = setTimeout(() => { killed = true; child.kill('SIGKILL'); }, timeoutMs);

    const cap = (buf, isErr) => {
      const s = buf.toString();
      if (isErr) stderr = (stderr + s).slice(-maxOutput);
      else stdout = (stdout + s).slice(-maxOutput);
      if (onLine) for (const line of s.split('\n')) if (line.length) onLine(isErr ? 'err' : 'out', line);
    };
    child.stdout.on('data', (b) => cap(b, false));
    child.stderr.on('data', (b) => cap(b, true));
    child.on('close', (code) => {
      clearTimeout(timer);
      resolve({ allowed: true, reason: 'ran', code, timedOut: killed, stdout, stderr });
    });
    child.on('error', (e) => {
      clearTimeout(timer);
      resolve({ allowed: true, reason: 'spawn error', code: null, error: e.message, stdout, stderr });
    });
  });
}

module.exports = { classify, run, ALLOW_PREFIXES, DENY_PATTERNS };
