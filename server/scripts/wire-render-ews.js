#!/usr/bin/env node
// wire-render-ews.js — set Kortana's Render env vars so she feeds CampLoJack's
// Early Warning System, WITHOUT clicking around the Render dashboard.
//
// It finds her Render service, merges in EWS_SCANNER_URL + INTERNAL_NOTIFY_KEY
// (leaving her other env vars untouched), and triggers a redeploy so she picks
// them up. Your Render API key is used only for these calls and never stored.
//
// USAGE (in Termux, on Render's shell, or anywhere with Node 18+):
//   RENDER_API_KEY=rnd_xxx node server/scripts/wire-render-ews.js
//
// Get the key: Render dashboard → top-right avatar → Account Settings →
//   API Keys → Create API Key. (You can delete it right after this runs.)
//
// Optional overrides (sane defaults built in):
//   EWS_SCANNER_URL      default https://theinvisiblepeople.org/api/ews-alerts?action=scanner
//   INTERNAL_NOTIFY_KEY  the shared secret (MUST match CampLoJack's Vercel value)
//   RENDER_SERVICE       her service name or srv-id, if auto-pick guesses wrong

const API = 'https://api.render.com/v1';
const KEY = process.env.RENDER_API_KEY;
const EWS_SCANNER_URL =
  process.env.EWS_SCANNER_URL ||
  'https://theinvisiblepeople.org/api/ews-alerts?action=scanner';
const INTERNAL_NOTIFY_KEY =
  process.env.INTERNAL_NOTIFY_KEY ||
  '8c992f85f68115127692cee844c2807b015a9718a73785a0';
const WANT_SERVICE = process.env.RENDER_SERVICE || '';

if (!KEY) {
  console.error('Set RENDER_API_KEY first (Render → Account Settings → API Keys).');
  process.exit(1);
}
if (typeof fetch !== 'function') {
  console.error('Needs Node 18+ (global fetch). Check: node --version');
  process.exit(1);
}

const headers = {
  Authorization: `Bearer ${KEY}`,
  'Content-Type': 'application/json',
  Accept: 'application/json',
};

async function api(path, opts = {}) {
  const r = await fetch(`${API}${path}`, { ...opts, headers: { ...headers, ...opts.headers } });
  const text = await r.text();
  let body;
  try { body = text ? JSON.parse(text) : null; } catch { body = text; }
  if (!r.ok) {
    throw new Error(`${opts.method || 'GET'} ${path} → ${r.status}: ${typeof body === 'string' ? body : JSON.stringify(body)}`);
  }
  return body;
}

(async () => {
  // 1) Find her service.
  const list = await api('/services?limit=100');
  const services = (Array.isArray(list) ? list : []).map((x) => x.service || x);
  if (!services.length) throw new Error('No services on this Render account.');

  let svc;
  if (WANT_SERVICE) {
    svc = services.find(
      (s) => s.id === WANT_SERVICE || (s.name || '').toLowerCase() === WANT_SERVICE.toLowerCase(),
    );
  } else {
    svc = services.find((s) => /kortana|k3|terminus/i.test(s.name || '')) ||
      (services.length === 1 ? services[0] : null);
  }
  if (!svc) {
    console.error('Could not auto-pick her service. Found these:');
    services.forEach((s) => console.error(`   ${s.id}   ${s.name}`));
    console.error('Re-run with RENDER_SERVICE="<name or srv-id>".');
    process.exit(1);
  }
  console.log(`Service: ${svc.name} (${svc.id})`);

  // 2) Merge the two vars into her existing set (don't clobber the rest).
  const current = await api(`/services/${svc.id}/env-vars?limit=100`);
  const vars = (Array.isArray(current) ? current : [])
    .map((x) => x.envVar || x)
    .map((v) => ({ key: v.key, value: v.value }));
  const upsert = (key, value) => {
    const existing = vars.find((v) => v.key === key);
    if (existing) existing.value = value;
    else vars.push({ key, value });
  };
  upsert('EWS_SCANNER_URL', EWS_SCANNER_URL);
  upsert('INTERNAL_NOTIFY_KEY', INTERNAL_NOTIFY_KEY);

  // 3) Write them back.
  await api(`/services/${svc.id}/env-vars`, { method: 'PUT', body: JSON.stringify(vars) });
  console.log('✓ Set EWS_SCANNER_URL + INTERNAL_NOTIFY_KEY (her other env vars left as-is).');

  // 4) Redeploy so the running instance picks them up.
  try {
    const dep = await api(`/services/${svc.id}/deploys`, { method: 'POST', body: '{}' });
    console.log(`✓ Triggered redeploy${dep && dep.id ? ` (${dep.id})` : ''}.`);
  } catch (e) {
    console.warn(`(env vars saved, but auto-redeploy failed: ${e.message})`);
    console.warn('Hit "Manual Deploy → Deploy latest commit" in Render to apply.');
  }
  console.log('Done. She can now POST heard dispatches to the EWS scanner.');
})().catch((e) => {
  console.error('FAILED:', e.message);
  process.exit(1);
});
