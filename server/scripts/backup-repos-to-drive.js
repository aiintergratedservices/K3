// Mirrors your GitHub repositories into the Kortana Drive archive so the
// 5TB Google One account houses everything — code included.
//
//   Usage:  node scripts/backup-repos-to-drive.js
//
//   Needs in .env: GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET / GOOGLE_REFRESH_TOKEN
//   (same credentials Terminus uses) and optionally GITHUB_TOKEN for private
//   repos. Repos are cloned bare, zipped, and uploaded to Kortana/repos/.
//
//   Run it on a schedule (cron / Termux) to keep the mirrors fresh:
//     0 3 * * * cd ~/k3/server && node scripts/backup-repos-to-drive.js

require('dotenv').config({ path: require('path').join(__dirname, '..', '.env') });
const { execSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const drive = require('../drive');

const REPOS = (process.env.BACKUP_REPOS || [
  'aiintergratedservices/K3',
  'aiintergratedservices/Kortana2',
  'aiintergratedservices/The_Kortana',
  'aiintergratedservices/Mvp',
  'aiintergratedservices/Mvp2',
  'aiintergratedservices/CampLoJack',
  'aiintergratedservices/CampLoJack2',
  'aiintergratedservices/Ews',
  'aiintergratedservices/contentforge',
  'aiintergratedservices/Marketing-',
].join(',')).split(',').map((s) => s.trim()).filter(Boolean);

(async () => {
  const ok = await drive.init();
  if (!ok) {
    console.error('Drive is not connected — set the GOOGLE_* values in .env first.');
    process.exit(1);
  }
  drive.folderIds['repos'] = await drive.ensureFolder('repos', drive.folderIds['']);

  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'k3-repo-backup-'));
  const token = process.env.GITHUB_TOKEN;
  let done = 0;
  for (const repo of REPOS) {
    const name = repo.split('/')[1];
    const dir = path.join(work, `${name}.git`);
    const url = token
      ? `https://x-access-token:${token}@github.com/${repo}.git`
      : `https://github.com/${repo}.git`;
    try {
      execSync(`git clone --mirror --quiet "${url}" "${dir}"`, { stdio: 'pipe' });
      const zip = path.join(work, `${name}.zip`);
      execSync(`cd "${work}" && zip -qr "${zip}" "${name}.git"`, { stdio: 'pipe' });
      await drive.putFile('repos', `${name}-mirror.zip`, fs.createReadStream(zip), 'application/zip');
      console.log(`[backup] ${repo} -> Kortana/repos/${name}-mirror.zip`);
      done++;
    } catch (e) {
      console.error(`[backup] ${repo} FAILED: ${e.message.split('\n')[0]}`);
    }
  }
  fs.rmSync(work, { recursive: true, force: true });
  console.log(`[backup] ${done}/${REPOS.length} repositories mirrored to Drive.`);
})();
