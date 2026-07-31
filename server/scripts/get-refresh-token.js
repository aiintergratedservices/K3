// One-time helper: get a Google OAuth refresh token for Kortana's Drive archive
// (her 5TB long-term memory), using the DEVICE FLOW — no localhost port, no
// callback server, no redirect-URI headaches. This is the flow a TV uses, and
// it's the reliable one on a phone / Termux / a headless server.
//
// Why device flow: the old version ran a localhost:8765 callback server. On a
// phone the browser can't redirect back into Termux, and a stale run holding
// the port caused the `invalid_client` / stuck-auth failures. Device flow has
// none of that — you just type a short code into google.com/device.
//
// SETUP (once):
//   1. Google Cloud Console -> APIs & Services -> Enable "Google Drive API".
//   2. Credentials -> Create OAuth client ID -> Application type:
//        "TV and Limited Input devices"
//      (NOT "Desktop app" — device flow needs this type.)
//   3. Put its ID + secret in server/.env:
//        GOOGLE_CLIENT_ID=...
//        GOOGLE_CLIENT_SECRET=...
//   4. If your OAuth consent screen is in "Testing", add
//        a.i.intergrated.services@gmail.com  as a Test user.
//
// RUN:
//   cd ~/k3/server && npm run auth
//   -> follow the printed instructions (visit the URL, type the code,
//      sign in AS a.i.intergrated.services@gmail.com, approve).
//   -> it prints GOOGLE_REFRESH_TOKEN. Put that value in .env (local) AND
//      in the Render dashboard env vars (the always-on server).

require('dotenv').config();

const { GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET } = process.env;
const SCOPE = 'https://www.googleapis.com/auth/drive.file'; // device flow only allows a limited scope set; drive.file (files this app creates — her backups) is allowed and is all the backup needs
const DEVICE_CODE_URL = 'https://oauth2.googleapis.com/device/code';
const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const DEVICE_GRANT = 'urn:ietf:params:oauth:grant-type:device_code';

if (!GOOGLE_CLIENT_ID || !GOOGLE_CLIENT_SECRET) {
  console.error('Set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET in server/.env first.');
  console.error('The client must be an OAuth "TV and Limited Input devices" type (see header).');
  process.exit(1);
}

if (typeof fetch !== 'function') {
  console.error('This helper needs Node 18+ (global fetch). Run: node --version');
  process.exit(1);
}

const form = (obj) => new URLSearchParams(obj).toString();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  // 1) Ask Google for a device + user code.
  const startRes = await fetch(DEVICE_CODE_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: form({ client_id: GOOGLE_CLIENT_ID, scope: SCOPE }),
  });
  const start = await startRes.json();
  if (!startRes.ok) {
    console.error('Could not start device flow:', start.error, start.error_description || '');
    if (start.error === 'invalid_client') {
      console.error('-> The client ID/secret are wrong, or the client is not a');
      console.error('   "TV and Limited Input devices" type. Recreate it as that type.');
    }
    process.exit(1);
  }

  const url = start.verification_url || start.verification_uri || 'https://www.google.com/device';
  console.log('\n=========================================================');
  console.log(' 1. On any device, open:   ' + url);
  console.log(' 2. Enter this code:       ' + start.user_code);
  console.log(' 3. Sign in AS a.i.intergrated.services@gmail.com and approve.');
  console.log('=========================================================\n');
  console.log('Waiting for you to approve… (this window will finish on its own)\n');

  // 2) Poll the token endpoint until the user approves.
  let interval = (start.interval || 5) * 1000;
  const deadline = Date.now() + (start.expires_in || 1800) * 1000;

  while (Date.now() < deadline) {
    await sleep(interval);
    const tokRes = await fetch(TOKEN_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form({
        client_id: GOOGLE_CLIENT_ID,
        client_secret: GOOGLE_CLIENT_SECRET,
        device_code: start.device_code,
        grant_type: DEVICE_GRANT,
      }),
    });
    const tok = await tokRes.json();

    if (tokRes.ok && tok.refresh_token) {
      console.log('\n=== GOOGLE_REFRESH_TOKEN ===\n');
      console.log(tok.refresh_token);
      console.log('\nPut that value in server/.env AND in the Render env vars,');
      console.log('then restart her. /health will show "drive":{"enabled":true}.');
      return;
    }
    if (tok.error === 'authorization_pending') continue;      // not approved yet
    if (tok.error === 'slow_down') { interval += 5000; continue; }
    if (tok.error === 'access_denied') {
      console.error('\nYou denied the request. Run it again to retry.');
      process.exit(1);
    }
    if (tok.error === 'expired_token') {
      console.error('\nThe code expired before approval. Run it again.');
      process.exit(1);
    }
    console.error('\nToken exchange failed:', tok.error, tok.error_description || '');
    process.exit(1);
  }
  console.error('\nTimed out waiting for approval. Run it again.');
  process.exit(1);
}

main().catch((e) => {
  console.error('Unexpected error:', e.message);
  process.exit(1);
});
