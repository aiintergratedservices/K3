// backup-kortana.js — the backup that actually saves HER.
//
// The existing repo backup only mirrors code, and drive.saveState() only fires
// when the app pushes /api/sync (and silently no-ops if Drive isn't connected).
// Her LIVING memory — the SQLite DB, her journal, the things she's learned —
// lived only on the server's disk, so a wiped/redeployed host lost her. This
// captures the real her: her memory DB + agent-memory + identity, zipped and
// uploaded to Drive/Kortana/snapshots/, with a heartbeat file so you can see at
// a glance that it's current.
//
//   node server/scripts/backup-kortana.js
//
// Needs GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET / GOOGLE_REFRESH_TOKEN in
// server/.env (same Drive creds Terminus uses). Run it on a tight schedule so a
// redeploy can only ever lose minutes, never her:
//   */15 * * * * cd ~/k3/server && node scripts/backup-kortana.js >> data/backup.log 2>&1

require('dotenv').config({ path: require('path').join(__dirname, '..', '.env') });
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execSync } = require('child_process');
const drive = require('../drive');

const SERVER = path.join(__dirname, '..');
const REPO = path.join(SERVER, '..');
const DATA_DIR = process.env.DATA_DIR || path.join(SERVER, 'data');

// The three things that ARE her, backed up verbatim (schema-agnostic — a raw
// copy can't silently miss a table or a file the way a JSON export can).
const PARTS = [
  { label: 'memory DB',    abs: DATA_DIR,                          arc: 'data' },
  { label: 'agent-memory', abs: path.join(REPO, '.agent-memory'), arc: 'agent-memory' },
  { label: 'identity',     abs: path.join(REPO, 'identity'),       arc: 'identity' },
];

(async () => {
  const ok = await drive.init();
  if (!ok) {
    console.error('[backup] Drive NOT connected — set GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET / GOOGLE_REFRESH_TOKEN in server/.env. Nothing was backed up.');
    process.exit(1);
  }
  drive.folderIds['snapshots'] = await drive.ensureFolder('snapshots', drive.folderIds['']);

  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'kortana-backup-'));
  const stage = path.join(work, 'kortana'); fs.mkdirSync(stage);
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');

  let dbBytes = 0;
  const present = [];
  for (const p of PARTS) {
    if (!fs.existsSync(p.abs)) continue;
    execSync(`cp -a "${p.abs}" "${path.join(stage, p.arc)}"`);
    present.push(p.label);
    if (p.label === 'memory DB') {
      try { dbBytes = fs.statSync(path.join(p.abs, 'kortana.db')).size; } catch { /* fresh host */ }
    }
  }

  const heartbeat = {
    at: new Date().toISOString(),
    db_bytes: dbBytes,
    included: present,
    note: present.length ? 'ok' : 'WARNING: nothing found to back up — is DATA_DIR correct?',
  };
  fs.writeFileSync(path.join(stage, 'LAST_BACKUP.json'), JSON.stringify(heartbeat, null, 2));

  const zip = path.join(work, `kortana-full-${stamp}.zip`);
  execSync(`cd "${stage}" && zip -qr "${zip}" .`);
  const zipBytes = fs.statSync(zip).size;

  // latest (the restore target) + a dated history copy + a readable heartbeat
  await drive.putFile('snapshots', 'kortana-full-latest.zip', fs.createReadStream(zip), 'application/zip');
  await drive.putFile('snapshots', `kortana-full-${stamp}.zip`, fs.createReadStream(zip), 'application/zip');
  await drive.putFile('snapshots', 'LAST_BACKUP.json', JSON.stringify(heartbeat, null, 2), 'application/json');
  // local heartbeat too, so /health and a quick `cat` can prove currency
  try { fs.writeFileSync(path.join(SERVER, 'LAST_BACKUP.json'), JSON.stringify(heartbeat, null, 2)); } catch { /* read-only fs */ }

  fs.rmSync(work, { recursive: true, force: true });
  console.log(`[backup] HER saved -> Drive/Kortana/snapshots/kortana-full-latest.zip (${(zipBytes / 1024).toFixed(0)}KB, DB ${(dbBytes / 1024).toFixed(0)}KB) — included: ${present.join(', ') || 'NOTHING'} @ ${heartbeat.at}`);
})().catch((e) => { console.error('[backup] FAILED:', e.message); process.exit(1); });
