// Applies an APPROVED propose_change proposal to its real target file — the
// one-command version of "a human reviewed this, now copy it over for
// real." This module does not decide whether to apply anything; that
// decision already happened when a human chose to call this endpoint. It
// only does the mechanical copy safely: same-file-only, auto-backup first,
// never creates a new file.

const fs = require('fs');
const path = require('path');

const REPO_ROOT = path.join(__dirname, '..');
const PROPOSED_DIR = path.join(REPO_ROOT, '.agent-memory', 'proposed_changes');
const BACKUP_DIR = path.join(REPO_ROOT, '.agent-memory', 'change_backups');
const SEPARATOR = '='.repeat(70);

function safeRepoPath(rel) {
  const p = path.resolve(REPO_ROOT, String(rel || '.'));
  if (p !== REPO_ROOT && !p.startsWith(REPO_ROOT + path.sep)) return null;
  return p;
}

function apply(filename) {
  const safeName = path.basename(String(filename || ''));
  const proposalPath = path.join(PROPOSED_DIR, safeName);
  if (!fs.existsSync(proposalPath)) throw new Error(`no such proposal: ${safeName}`);

  const raw = fs.readFileSync(proposalPath, 'utf8');
  const targetMatch = raw.match(/^target file:\s*(.+)$/m);
  if (!targetMatch) throw new Error('proposal file is missing a "target file:" header line — malformed');
  const targetRel = targetMatch[1].trim();

  const sepIndex = raw.indexOf(SEPARATOR);
  if (sepIndex === -1) throw new Error('proposal file is missing its content separator — malformed');
  const content = raw.slice(sepIndex + SEPARATOR.length).replace(/^\n/, '');

  const targetPath = safeRepoPath(targetRel);
  if (!targetPath) throw new Error(`target path "${targetRel}" is outside the project — refusing`);
  if (!fs.existsSync(targetPath)) throw new Error(`target file "${targetRel}" no longer exists — refusing to create a new file this way`);

  // Trivially reversible even without touching git — back up the current
  // version before overwriting it.
  fs.mkdirSync(BACKUP_DIR, { recursive: true });
  const backupName = `${path.basename(targetRel)}.${Date.now().toString(36)}.bak`;
  fs.copyFileSync(targetPath, path.join(BACKUP_DIR, backupName));

  fs.writeFileSync(targetPath, content);
  return { applied: true, target: targetRel, backup: `.agent-memory/change_backups/${backupName}` };
}

// Lists pending proposals (both propose_tool and propose_change) with their
// verification status, so a human can see what's ready to review without
// digging through directories by hand.
function listPending() {
  const out = [];
  try {
    const toolsDir = path.join(REPO_ROOT, '.agent-memory', 'proposed_tools');
    for (const f of fs.readdirSync(toolsDir)) {
      const full = path.join(toolsDir, f);
      const txt = fs.readFileSync(full, 'utf8');
      const nameMatch = txt.match(/^\/\/ name:\s*(.+)$/m);
      const reasonMatch = txt.match(/^\/\/ reason:\s*(.+)$/m);
      out.push({
        kind: 'tool',
        filename: f,
        target: nameMatch ? nameMatch[1].trim() : f,
        summary: reasonMatch ? reasonMatch[1].trim() : '',
        mtime: fs.statSync(full).mtime.toISOString(),
      });
    }
  } catch { /* dir may not exist yet */ }
  try {
    const changesDir = path.join(REPO_ROOT, '.agent-memory', 'proposed_changes');
    for (const f of fs.readdirSync(changesDir)) {
      if (!f.endsWith('.proposed')) continue; // skip leftover .checktmp.js if any
      const full = path.join(changesDir, f);
      const txt = fs.readFileSync(full, 'utf8');
      const targetMatch = txt.match(/^target file:\s*(.+)$/m);
      const descMatch = txt.match(/^description:\s*(.+)$/m);
      const verMatch = txt.match(/^verification:\s*(.+)$/m);
      out.push({
        kind: 'change',
        filename: f,
        target: targetMatch ? targetMatch[1].trim() : '?',
        summary: descMatch ? descMatch[1].trim() : '',
        verification: verMatch ? verMatch[1].trim() : 'unknown',
        mtime: fs.statSync(full).mtime.toISOString(),
      });
    }
  } catch { /* dir may not exist yet */ }
  out.sort((a, b) => new Date(b.mtime) - new Date(a.mtime));
  return out;
}

module.exports = { apply, listPending };
