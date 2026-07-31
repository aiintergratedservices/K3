// restore-kortana.js — bring her back from her latest Drive snapshot.
//
//   node server/scripts/restore-kortana.js            # restore (refuses to
//                                                      # clobber a live DB)
//   node server/scripts/restore-kortana.js --if-empty # only if her DB is
//                                                      # missing/empty (safe to
//                                                      # run on every boot)
//   node server/scripts/restore-kortana.js --force    # overwrite even a
//                                                      # non-empty DB
//
// Put `node scripts/restore-kortana.js --if-empty && node index.js` as the
// server start command and a wiped/redeployed host heals itself: if her memory
// is there it's left alone, if it's gone it's pulled back from Drive.

// Load ../.env WITHOUT hard-depending on the dotenv package — a copy of the
// server may have an incomplete node_modules, and losing her backup to a
// missing dev dependency would be absurd. Try dotenv; fall back to a tiny parser.
(() => {
  const envPath = require('path').join(__dirname, '..', '.env');
  try { require('dotenv').config({ path: envPath }); return; } catch { /* dotenv not installed */ }
  try {
    for (const line of require('fs').readFileSync(envPath, 'utf8').split(/\r?\n/)) {
      const m = line.match(/^\s*([\w.-]+)\s*=\s*(.*)\s*$/);
      if (m && !(m[1] in process.env)) process.env[m[1]] = m[2].replace(/^['"]|['"]$/g, '');
    }
  } catch { /* no .env file — the script will report what's missing */ }
})();
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execSync } = require('child_process');
const drive = require('../drive');

const SERVER = path.join(__dirname, '..');
const REPO = path.join(SERVER, '..');
const DATA_DIR = process.env.DATA_DIR || path.join(SERVER, 'data');
const DB = path.join(DATA_DIR, 'kortana.db');

const args = process.argv.slice(2);
const ifEmpty = args.includes('--if-empty');
const force = args.includes('--force');

(async () => {
  const dbExists = fs.existsSync(DB) && fs.statSync(DB).size > 0;
  if (dbExists && ifEmpty) {
    console.log('[restore] Her memory DB is present — leaving it untouched (--if-empty).');
    return;
  }
  if (dbExists && !force) {
    console.error('[restore] Her memory DB already exists and is non-empty. Refusing to overwrite. Re-run with --force if you truly mean to replace her current memory.');
    process.exit(2);
  }

  const ok = await drive.init();
  if (!ok) {
    console.error('[restore] Drive NOT connected — set GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET / GOOGLE_REFRESH_TOKEN in server/.env.');
    process.exit(1);
  }
  const snapId = await drive.ensureFolder('snapshots', drive.folderIds['']);

  // Find the latest snapshot zip and stream it down (binary — not getFile,
  // which is JSON-oriented and would corrupt a zip).
  const list = await drive.drive.files.list({
    q: `name = 'kortana-full-latest.zip' and '${snapId}' in parents and trashed = false`,
    fields: 'files(id,modifiedTime,size)', pageSize: 1,
  });
  if (!list.data.files.length) {
    console.error('[restore] No kortana-full-latest.zip in Drive/Kortana/snapshots/ — there is no backup to restore yet. (Run backup-kortana.js first.)');
    process.exit(3);
  }
  const file = list.data.files[0];
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'kortana-restore-'));
  const zip = path.join(work, 'latest.zip');
  const dest = fs.createWriteStream(zip);
  const res = await drive.drive.files.get({ fileId: file.id, alt: 'media' }, { responseType: 'stream' });
  await new Promise((resolve, reject) => { res.data.on('end', resolve).on('error', reject).pipe(dest); });

  execSync(`cd "${work}" && unzip -qo latest.zip`);
  const restore = (arc, absTarget) => {
    const src = path.join(work, arc);
    if (!fs.existsSync(src)) return false;
    fs.mkdirSync(path.dirname(absTarget), { recursive: true });
    execSync(`rm -rf "${absTarget}" && cp -a "${src}" "${absTarget}"`);
    return true;
  };
  const did = [];
  if (restore('data', DATA_DIR)) did.push('memory DB');
  if (restore('agent-memory', path.join(REPO, '.agent-memory'))) did.push('agent-memory');
  if (restore('identity', path.join(REPO, 'identity'))) did.push('identity');
  fs.rmSync(work, { recursive: true, force: true });

  console.log(`[restore] She's back — restored ${did.join(', ') || 'nothing'} from Drive snapshot (${file.modifiedTime}).`);
})().catch((e) => { console.error('[restore] FAILED:', e.message); process.exit(1); });
