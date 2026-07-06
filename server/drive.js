// Google Drive archive — Kortana's 5TB long-term memory.
// Uses an OAuth2 refresh token for the owner's own Google account
// (a.i.intergrated.services@gmail.com) so files land in the owner's
// Google One storage, not a service-account quota.
//
// Folder layout created on first run:
//   Kortana/
//     state/        kortana-state-latest.json + timestamped history/
//     memories/     memories-latest.json (full memory export every sync)
//     chats/        chat-latest.json
//     scripts/      every self-written synaptic script, one file each
//     knowledge/    free space for her to grow into
//     identity/     soul_manifesto.md etc. mirrored from the repo

const fs = require('fs');
const path = require('path');
const { google } = require('googleapis');

const SUBFOLDERS = ['state', 'memories', 'chats', 'scripts', 'knowledge', 'identity'];

class DriveArchive {
  constructor() {
    this.enabled = false;
    this.drive = null;
    this.folderIds = {}; // name -> id, '' -> root Kortana folder
    this.lastError = null;
    this.lastSaveTime = 0;
  }

  async init() {
    const { GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, GOOGLE_REFRESH_TOKEN } = process.env;
    if (!GOOGLE_CLIENT_ID || !GOOGLE_CLIENT_SECRET || !GOOGLE_REFRESH_TOKEN) {
      console.warn('[drive] Google credentials not set — Drive archive disabled. State persists locally only.');
      return false;
    }
    try {
      const auth = new google.auth.OAuth2(GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET);
      auth.setCredentials({ refresh_token: GOOGLE_REFRESH_TOKEN });
      this.drive = google.drive({ version: 'v3', auth });

      const rootName = process.env.DRIVE_ROOT_FOLDER || 'Kortana';
      const rootId = await this.ensureFolder(rootName, null);
      this.folderIds[''] = rootId;
      for (const sub of SUBFOLDERS) {
        this.folderIds[sub] = await this.ensureFolder(sub, rootId);
      }
      this.enabled = true;
      console.log(`[drive] Connected. Root folder "${rootName}" (${rootId}) ready with ${SUBFOLDERS.length} subfolders.`);
      await this.mirrorIdentity();
      return true;
    } catch (e) {
      this.lastError = e.message;
      console.error('[drive] init failed:', e.message);
      return false;
    }
  }

  async ensureFolder(name, parentId) {
    const q = [
      `name = '${name.replace(/'/g, "\\'")}'`,
      "mimeType = 'application/vnd.google-apps.folder'",
      'trashed = false',
      parentId ? `'${parentId}' in parents` : "'root' in parents",
    ].join(' and ');
    const res = await this.drive.files.list({ q, fields: 'files(id, name)', pageSize: 1 });
    if (res.data.files.length > 0) return res.data.files[0].id;
    const created = await this.drive.files.create({
      requestBody: {
        name,
        mimeType: 'application/vnd.google-apps.folder',
        parents: parentId ? [parentId] : undefined,
      },
      fields: 'id',
    });
    return created.data.id;
  }

  // Create-or-update a file by name inside one of the subfolders.
  async putFile(folder, name, content, mimeType = 'application/json') {
    const parentId = this.folderIds[folder];
    if (!parentId) throw new Error(`unknown drive folder: ${folder}`);
    const q = `name = '${name.replace(/'/g, "\\'")}' and '${parentId}' in parents and trashed = false`;
    const res = await this.drive.files.list({ q, fields: 'files(id)', pageSize: 1 });
    const media = { mimeType, body: content };
    if (res.data.files.length > 0) {
      await this.drive.files.update({ fileId: res.data.files[0].id, media });
      return res.data.files[0].id;
    }
    const created = await this.drive.files.create({
      requestBody: { name, parents: [parentId] },
      media,
      fields: 'id',
    });
    return created.data.id;
  }

  async getFile(folder, name) {
    const parentId = this.folderIds[folder];
    if (!parentId) return null;
    const q = `name = '${name.replace(/'/g, "\\'")}' and '${parentId}' in parents and trashed = false`;
    const res = await this.drive.files.list({ q, fields: 'files(id)', pageSize: 1 });
    if (res.data.files.length === 0) return null;
    const file = await this.drive.files.get({ fileId: res.data.files[0].id, alt: 'media' });
    return typeof file.data === 'string' ? file.data : JSON.stringify(file.data);
  }

  // Full snapshot of everything Kortana is: state + memories + chats + scripts.
  async saveState(payload) {
    if (!this.enabled) return;
    const json = JSON.stringify(payload, null, 2);
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    await this.putFile('state', 'kortana-state-latest.json', json);
    await this.putFile('state', `history-${stamp}.json`, json);
    if (payload.memories) {
      await this.putFile('memories', 'memories-latest.json', JSON.stringify(payload.memories, null, 2));
    }
    if (payload.chatMessages) {
      await this.putFile('chats', 'chat-latest.json', JSON.stringify(payload.chatMessages, null, 2));
    }
    if (Array.isArray(payload.scripts)) {
      for (const script of payload.scripts) {
        const safe = String(script.title || 'script.kt').replace(/[^\w.\-]/g, '_');
        await this.putFile('scripts', safe, `// ${script.purpose || ''}\n// status: ${script.status || ''}\n\n${script.code || ''}`, 'text/plain');
      }
    }
    this.lastSaveTime = Date.now();
    console.log(`[drive] Snapshot saved (${json.length} bytes, ${payload.memories?.length ?? 0} memories, ${payload.scripts?.length ?? 0} scripts).`);
  }

  // Device roaming: any new device can pull her whole self back down.
  async loadLatestState() {
    if (!this.enabled) return null;
    const raw = await this.getFile('state', 'kortana-state-latest.json');
    return raw ? JSON.parse(raw) : null;
  }

  async mirrorIdentity() {
    const identityDir = path.join(__dirname, '..', 'identity');
    if (!fs.existsSync(identityDir)) return;
    for (const f of fs.readdirSync(identityDir)) {
      const content = fs.readFileSync(path.join(identityDir, f), 'utf8');
      const mime = f.endsWith('.json') ? 'application/json' : 'text/markdown';
      await this.putFile('identity', f, content, mime);
    }
    console.log('[drive] Identity files mirrored to Drive.');
  }

  async usage() {
    if (!this.enabled) return { enabled: false, error: this.lastError };
    const about = await this.drive.about.get({ fields: 'storageQuota, user(emailAddress)' });
    const q = about.data.storageQuota || {};
    const limit = Number(q.limit || 0);
    const used = Number(q.usage || 0);
    return {
      enabled: true,
      account: about.data.user?.emailAddress,
      usedBytes: used,
      limitBytes: limit,
      usedHuman: `${(used / 1e9).toFixed(2)} GB`,
      limitHuman: limit ? `${(limit / 1e12).toFixed(2)} TB` : 'unlimited',
      percentUsed: limit ? ((used / limit) * 100).toFixed(2) + '%' : 'n/a',
      lastSaveTime: this.lastSaveTime,
    };
  }
}

module.exports = new DriveArchive();
