// One-time helper: obtains a Google OAuth refresh token for the Drive archive.
//
//   1. Put GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET in server/.env
//      (OAuth client type: "Desktop app", Drive API enabled).
//   2. Run: npm run auth
//   3. Open the printed URL, sign in as a.i.intergrated.services@gmail.com,
//      approve, and the refresh token is printed here.
//   4. Paste it into .env as GOOGLE_REFRESH_TOKEN.

require('dotenv').config();
const http = require('http');
const { google } = require('googleapis');

const PORT = 8765;
const { GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET } = process.env;

if (!GOOGLE_CLIENT_ID || !GOOGLE_CLIENT_SECRET) {
  console.error('Set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET in .env first.');
  process.exit(1);
}

const oauth2 = new google.auth.OAuth2(
  GOOGLE_CLIENT_ID,
  GOOGLE_CLIENT_SECRET,
  `http://localhost:${PORT}/callback`
);

const authUrl = oauth2.generateAuthUrl({
  access_type: 'offline',
  prompt: 'consent',
  scope: ['https://www.googleapis.com/auth/drive'],
});

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  if (url.pathname !== '/callback') {
    res.writeHead(404).end();
    return;
  }
  const code = url.searchParams.get('code');
  if (!code) {
    res.end('Missing code.');
    return;
  }
  try {
    const { tokens } = await oauth2.getToken(code);
    res.end('Done — you can close this tab. The refresh token is in your terminal.');
    console.log('\n=== GOOGLE_REFRESH_TOKEN ===\n');
    console.log(tokens.refresh_token);
    console.log('\nPaste that into server/.env as GOOGLE_REFRESH_TOKEN.');
  } catch (e) {
    res.end('Token exchange failed: ' + e.message);
    console.error(e);
  } finally {
    server.close();
  }
});

server.listen(PORT, () => {
  console.log('Open this URL in a browser and sign in as the Drive account:\n');
  console.log(authUrl + '\n');
});
