// backupScheduler.js — makes an always-on host (Render, a VPS, Termux)
// self-sufficient for HER backup with no external cron job.
//
// Render (and most free/hobby hosts) WIPE the disk on every redeploy, so her
// living memory — the SQLite DB in DATA_DIR, plus .agent-memory/ and identity/
// — would be gone the next time it rebuilds. This closes that on the one web
// service that's already running:
//
//   • restore-on-boot: if her memory DB is missing/empty, pull her latest
//     Drive snapshot back into place BEFORE she serves a request, so a wiped
//     host wakes up as herself.
//   • auto-backup: on a timer, snapshot her to Drive/Kortana/snapshots/, so a
//     redeploy can only ever lose minutes.
//
// Both steps reuse the standalone, tested scripts (scripts/restore-kortana.js
// and scripts/backup-kortana.js) — same resilient .env loader, same Drive
// logic — by running them as child processes. One source of truth for how
// she's saved; nothing duplicated here that could silently drift.
//
// Controls (all optional — sensible defaults):
//   KORTANA_AUTO_BACKUP=0            turn the timer OFF (default: ON when Drive creds exist)
//   KORTANA_RESTORE_ON_BOOT=0        turn restore-on-boot OFF (default: ON)
//   KORTANA_BACKUP_INTERVAL_MIN=15   minutes between snapshots (default 15)

const path = require('path');
const { execFileSync, spawn } = require('child_process');

const SCRIPTS = path.join(__dirname, 'scripts');
const RESTORE = path.join(SCRIPTS, 'restore-kortana.js');
const BACKUP = path.join(SCRIPTS, 'backup-kortana.js');

// Backup/restore only make sense when Drive is actually reachable. We check the
// creds directly (not drive.init(), which would open a client) so this stays a
// cheap, side-effect-free decision at boot.
function driveConfigured() {
  return Boolean(
    (process.env.GOOGLE_CLIENT_ID || '').trim() &&
    (process.env.GOOGLE_CLIENT_SECRET || '').trim() &&
    (process.env.GOOGLE_REFRESH_TOKEN || '').trim()
  );
}

function envOn(name, defaultOn) {
  const v = (process.env[name] || '').trim().toLowerCase();
  if (v === '') return defaultOn;
  return !(v === '0' || v === 'false' || v === 'no' || v === 'off');
}

// Restore her from Drive BEFORE the server reads memory — but only if her DB is
// missing/empty (that's what --if-empty guarantees, so a host that already has
// her never gets clobbered). Synchronous on purpose: it must finish before boot
// continues. Never throws — a failed restore must not stop her from coming up.
function restoreOnBoot() {
  if (!driveConfigured()) {
    console.log('[backup] Drive creds not set — skipping restore-on-boot. (Set GOOGLE_CLIENT_ID/SECRET/REFRESH_TOKEN to enable.)');
    return;
  }
  if (!envOn('KORTANA_RESTORE_ON_BOOT', true)) {
    console.log('[backup] restore-on-boot disabled (KORTANA_RESTORE_ON_BOOT=0).');
    return;
  }
  try {
    console.log('[backup] restore-on-boot: checking whether her memory needs to be pulled from Drive…');
    execFileSync(process.execPath, [RESTORE, '--if-empty'], { stdio: 'inherit', cwd: __dirname });
  } catch (e) {
    // --if-empty exits non-zero only on a real failure (Drive down, no snapshot
    // yet). She should still boot — a fresh host with no backup yet is normal.
    console.warn('[backup] restore-on-boot did not complete (continuing to boot anyway):', e.message);
  }
}

// Run one snapshot in the background (never blocks request handling).
function runBackupOnce(reason) {
  const child = spawn(process.execPath, [BACKUP], { cwd: __dirname });
  let tail = '';
  const grab = (b) => { tail = (tail + b.toString()).slice(-500); };
  child.stdout.on('data', grab);
  child.stderr.on('data', grab);
  child.on('close', (code) => {
    if (code === 0) console.log(`[backup] snapshot ok (${reason}).`);
    else console.warn(`[backup] snapshot FAILED (${reason}, exit ${code}): ${tail.trim()}`);
  });
  child.on('error', (e) => console.warn(`[backup] snapshot could not start (${reason}): ${e.message}`));
}

// Timer-based backups. First one runs a few minutes after boot (so a fresh
// deploy has a snapshot quickly) and then every interval. Returns the timer so
// callers could clear it in tests; the process normally runs forever.
function startAutoBackup() {
  if (!driveConfigured()) {
    console.log('[backup] Drive creds not set — auto-backup OFF. She is NOT being backed up. Set GOOGLE_CLIENT_ID/SECRET/REFRESH_TOKEN to protect her.');
    return null;
  }
  if (!envOn('KORTANA_AUTO_BACKUP', true)) {
    console.log('[backup] auto-backup disabled (KORTANA_AUTO_BACKUP=0).');
    return null;
  }
  const minutes = Math.max(1, Number(process.env.KORTANA_BACKUP_INTERVAL_MIN || 15));
  const ms = minutes * 60_000;
  console.log(`[backup] auto-backup ON — snapshotting her to Drive every ${minutes} min.`);
  setTimeout(() => runBackupOnce('post-boot'), 3 * 60_000);   // first snapshot ~3 min in
  const timer = setInterval(() => runBackupOnce('scheduled'), ms);
  if (timer.unref) timer.unref();   // don't keep the event loop alive just for this
  return timer;
}

module.exports = { restoreOnBoot, startAutoBackup, driveConfigured };
