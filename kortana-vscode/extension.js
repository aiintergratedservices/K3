// Kortana — VS Code extension.
// Talks to a self-hosted Terminus brain (POST <terminusUrl>/api/brain), the same
// endpoint the phone app uses. No bundler, no external deps: just the `vscode`
// API + Node's http/https, so it loads as-is with no build step.
const vscode = require('vscode');
const http = require('http');
const https = require('https');
const { URL } = require('url');

function config() {
  const c = vscode.workspace.getConfiguration('kortana');
  return {
    url: String(c.get('terminusUrl') || 'http://127.0.0.1:3300').replace(/\/+$/, ''),
    key: String(c.get('apiKey') || ''),
  };
}

// POST one message to Terminus /api/brain and resolve with her reply text.
// history is [{ sender: 'USER'|'KORTANA', message }] so she keeps context.
function askKortana(message, history) {
  return new Promise((resolve, reject) => {
    const { url, key } = config();
    let target;
    try { target = new URL(url + '/api/brain'); } catch (e) { return reject(new Error('Invalid kortana.terminusUrl: ' + url)); }
    const body = JSON.stringify({ message, history: history || [] });
    const headers = { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) };
    if (key) { headers['x-api-key'] = key; headers['authorization'] = key; }
    const mod = target.protocol === 'https:' ? https : http;
    const req = mod.request(target, { method: 'POST', headers, timeout: 120000 }, (res) => {
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => {
        if (res.statusCode < 200 || res.statusCode >= 300) {
          return reject(new Error(`Terminus HTTP ${res.statusCode}: ${data.slice(0, 300)}`));
        }
        try {
          const j = JSON.parse(data);
          resolve(String(j.reply || j.text || '').trim() || '(empty reply)');
        } catch (e) { reject(new Error('Unexpected response from Terminus: ' + data.slice(0, 200))); }
      });
    });
    req.on('timeout', () => req.destroy(new Error('Terminus request timed out (120s)')));
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

// --- Chat panel (singleton webview) -----------------------------------------
let panel = null;
let history = []; // shared conversation, mirrors the app's chat history shape

function showChat(context) {
  if (panel) { panel.reveal(vscode.ViewColumn.Beside); return; }
  panel = vscode.window.createWebviewPanel(
    'kortanaChat', 'Kortana', vscode.ViewColumn.Beside,
    { enableScripts: true, retainContextWhenHidden: true }
  );
  panel.webview.html = chatHtml();
  panel.onDidDispose(() => { panel = null; }, null, context.subscriptions);
  panel.webview.onDidReceiveMessage(async (msg) => {
    if (!msg || msg.type !== 'send') return;
    const text = String(msg.text || '').trim();
    if (!text) return;
    history.push({ sender: 'USER', message: text });
    panel.webview.postMessage({ type: 'thinking', on: true });
    try {
      const reply = await askKortana(text, history.slice(-20, -1));
      history.push({ sender: 'KORTANA', message: reply });
      panel.webview.postMessage({ type: 'reply', text: reply });
    } catch (e) {
      panel.webview.postMessage({ type: 'error', text: e.message });
    } finally {
      panel.webview.postMessage({ type: 'thinking', on: false });
    }
  }, null, context.subscriptions);
}

// Push a user turn into the (possibly not-yet-open) chat and get a reply.
async function sendToChat(context, userText) {
  showChat(context);
  panel.webview.postMessage({ type: 'user', text: userText });
  history.push({ sender: 'USER', message: userText });
  panel.webview.postMessage({ type: 'thinking', on: true });
  try {
    const reply = await askKortana(userText, history.slice(-20, -1));
    history.push({ sender: 'KORTANA', message: reply });
    panel.webview.postMessage({ type: 'reply', text: reply });
  } catch (e) {
    panel.webview.postMessage({ type: 'error', text: e.message });
  } finally {
    panel.webview.postMessage({ type: 'thinking', on: false });
  }
}

function chatHtml() {
  return `<!DOCTYPE html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline';"><style>
    body{font-family:var(--vscode-font-family);color:var(--vscode-foreground);margin:0;display:flex;flex-direction:column;height:100vh}
    #log{flex:1;overflow-y:auto;padding:12px;display:flex;flex-direction:column;gap:10px}
    .msg{padding:8px 10px;border-radius:8px;max-width:92%;white-space:pre-wrap;word-wrap:break-word;line-height:1.4}
    .user{align-self:flex-end;background:var(--vscode-textBlockQuote-background);border:1px solid var(--vscode-input-border)}
    .kortana{align-self:flex-start;background:var(--vscode-editor-inactiveSelectionBackground)}
    .error{align-self:flex-start;color:var(--vscode-errorForeground)}
    .who{font-size:10px;opacity:.6;margin-bottom:2px;text-transform:uppercase;letter-spacing:.05em}
    #bar{display:flex;gap:6px;padding:8px;border-top:1px solid var(--vscode-input-border)}
    #box{flex:1;background:var(--vscode-input-background);color:var(--vscode-input-foreground);border:1px solid var(--vscode-input-border);border-radius:6px;padding:6px 8px;resize:none;font-family:inherit}
    #send{background:var(--vscode-button-background);color:var(--vscode-button-foreground);border:none;border-radius:6px;padding:0 14px;cursor:pointer}
    #send:disabled{opacity:.5;cursor:default}
    #think{padding:0 12px 6px;font-size:11px;opacity:.6;display:none}
  </style></head><body>
    <div id="log"></div>
    <div id="think">Kortana is thinking…</div>
    <div id="bar"><textarea id="box" rows="2" placeholder="Ask Kortana…"></textarea><button id="send">Send</button></div>
    <script>
      const vscode = acquireVsCodeApi();
      const log = document.getElementById('log'), box = document.getElementById('box'),
            send = document.getElementById('send'), think = document.getElementById('think');
      function add(who, text, cls){
        const d = document.createElement('div'); d.className = 'msg ' + cls;
        const w = document.createElement('div'); w.className = 'who'; w.textContent = who;
        const b = document.createElement('div'); b.textContent = text;
        d.appendChild(w); d.appendChild(b); log.appendChild(d); log.scrollTop = log.scrollHeight;
      }
      function submit(){
        const t = box.value.trim(); if(!t) return;
        add('You', t, 'user'); box.value = '';
        vscode.postMessage({ type:'send', text:t });
      }
      send.addEventListener('click', submit);
      box.addEventListener('keydown', (e)=>{ if(e.key==='Enter' && !e.shiftKey){ e.preventDefault(); submit(); }});
      window.addEventListener('message', (e)=>{
        const m = e.data;
        if(m.type==='reply') add('Kortana', m.text, 'kortana');
        else if(m.type==='error') add('Error', m.text, 'error');
        else if(m.type==='user') add('You', m.text, 'user');
        else if(m.type==='thinking'){ think.style.display = m.on ? 'block':'none'; send.disabled = m.on; }
      });
    </script>
  </body></html>`;
}

function activate(context) {
  context.subscriptions.push(
    vscode.commands.registerCommand('kortana.openChat', () => showChat(context)),
    vscode.commands.registerCommand('kortana.ask', async () => {
      const q = await vscode.window.showInputBox({ prompt: 'Ask Kortana', placeHolder: 'What do you want to ask her?' });
      if (q && q.trim()) sendToChat(context, q.trim());
    }),
    vscode.commands.registerCommand('kortana.askSelection', async () => {
      const ed = vscode.window.activeTextEditor;
      if (!ed) { vscode.window.showWarningMessage('Kortana: open a file and select some code first.'); return; }
      const sel = ed.document.getText(ed.selection).trim();
      if (!sel) { vscode.window.showWarningMessage('Kortana: nothing is selected.'); return; }
      const q = await vscode.window.showInputBox({ prompt: 'Ask Kortana about the selection', value: 'Explain this and suggest improvements.' });
      if (q === undefined) return;
      const lang = ed.document.languageId || '';
      const text = `${q}\n\n\`\`\`${lang}\n${sel.slice(0, 6000)}\n\`\`\``;
      sendToChat(context, text);
    })
  );
}

function deactivate() {}

module.exports = { activate, deactivate, askKortana, escapeHtml };
