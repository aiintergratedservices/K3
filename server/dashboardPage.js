// Her learning dashboard — a real, self-contained HTML page (no CDN, no
// external assets) that charts memory growth, goal progress, which
// specialist brain has handled what, and her skills, straight from the
// actual data files. Served by index.js at GET /api/kortana/dashboard.

function renderDashboardPage() {
  return `<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Kortana — Learning Dashboard</title>
<style>
  :root {
    --bg: #0a0a12;
    --card: #14141f;
    --border: #2a2a3d;
    --violet: #a06bff;
    --silver: #dce8ff;
    --gold: #ffd98a;
    --muted: #8a8aa5;
    --green: #4ade80;
    --red: #ff5f7e;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    background: radial-gradient(circle at 50% 0%, #1a1430 0%, var(--bg) 60%);
    color: var(--silver);
    font-family: 'Courier New', monospace;
    padding: 20px;
  }
  h1 {
    font-size: 18px;
    letter-spacing: 2px;
    color: var(--violet);
    text-shadow: 0 0 12px rgba(160, 107, 255, 0.5);
    margin: 0 0 4px 0;
  }
  .subtitle { color: var(--muted); font-size: 11px; margin-bottom: 20px; }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
    gap: 14px;
  }
  .card {
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 14px;
  }
  .card h2 {
    font-size: 11px;
    letter-spacing: 1px;
    color: var(--gold);
    margin: 0 0 10px 0;
    text-transform: uppercase;
  }
  .stat-row { display: flex; justify-content: space-between; gap: 8px; margin-bottom: 6px; font-size: 12px; }
  .stat-row .label { color: var(--muted); }
  .stat-row .value { color: var(--silver); font-weight: bold; }
  canvas { width: 100%; height: 140px; display: block; }
  .list-item {
    font-size: 11px;
    padding: 6px 0;
    border-bottom: 1px solid var(--border);
    color: var(--silver);
  }
  .list-item:last-child { border-bottom: none; }
  .list-item .meta { color: var(--muted); font-size: 10px; }
  .badge {
    display: inline-block;
    padding: 1px 6px;
    border-radius: 4px;
    font-size: 9px;
    font-weight: bold;
    margin-right: 6px;
  }
  .badge.active { background: rgba(160,107,255,0.2); color: var(--violet); }
  .badge.done { background: rgba(74,222,128,0.2); color: var(--green); }
  .badge.blocked { background: rgba(255,95,126,0.2); color: var(--red); }
  .empty { color: var(--muted); font-size: 11px; font-style: italic; }
  #refresh {
    background: var(--card);
    border: 1px solid var(--violet);
    color: var(--violet);
    font-family: inherit;
    font-size: 10px;
    padding: 6px 12px;
    border-radius: 6px;
    cursor: pointer;
    margin-bottom: 16px;
  }
</style>
</head>
<body>
<h1>KORTANA — LEARNING DASHBOARD</h1>
<div class="subtitle" id="genAt">loading...</div>
<button id="refresh" onclick="loadData()">↻ REFRESH</button>
<div class="grid" id="grid">
  <div class="card"><span class="empty">Loading real data...</span></div>
</div>

<script>
const params = new URLSearchParams(window.location.search);
const key = params.get('key') || '';

function fmtDate(iso) {
  try { return new Date(iso).toLocaleString(); } catch { return iso; }
}

function drawLineChart(canvas, series, valueKey) {
  const ctx = canvas.getContext('2d');
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth, h = canvas.clientHeight;
  canvas.width = w * dpr; canvas.height = h * dpr;
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, w, h);
  if (!series.length) {
    ctx.fillStyle = '#8a8aa5';
    ctx.font = '11px monospace';
    ctx.fillText('no data yet', 10, h / 2);
    return;
  }
  const pad = 20;
  const maxV = Math.max(...series.map((s) => s[valueKey]), 1);
  const stepX = (w - pad * 2) / Math.max(series.length - 1, 1);
  ctx.strokeStyle = '#a06bff';
  ctx.lineWidth = 2;
  ctx.beginPath();
  series.forEach((s, i) => {
    const x = pad + i * stepX;
    const y = h - pad - (s[valueKey] / maxV) * (h - pad * 2);
    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  });
  ctx.stroke();
  ctx.fillStyle = 'rgba(160,107,255,0.15)';
  ctx.lineTo(pad + (series.length - 1) * stepX, h - pad);
  ctx.lineTo(pad, h - pad);
  ctx.closePath();
  ctx.fill();
}

function drawBarChart(canvas, items, valueKey, labelKey) {
  const ctx = canvas.getContext('2d');
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth, h = canvas.clientHeight;
  canvas.width = w * dpr; canvas.height = h * dpr;
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, w, h);
  if (!items.length) {
    ctx.fillStyle = '#8a8aa5';
    ctx.font = '11px monospace';
    ctx.fillText('no data yet', 10, h / 2);
    return;
  }
  const pad = 6;
  const maxV = Math.max(...items.map((it) => it[valueKey]), 1);
  const barW = (w - pad * 2) / items.length - 8;
  const colors = ['#a06bff', '#ffd98a', '#4ade80', '#ff5f7e', '#7ec8ff'];
  items.forEach((it, i) => {
    const barH = (it[valueKey] / maxV) * (h - 28);
    const x = pad + i * (barW + 8);
    const y = h - 18 - barH;
    ctx.fillStyle = colors[i % colors.length];
    ctx.fillRect(x, y, barW, barH);
    ctx.fillStyle = '#dce8ff';
    ctx.font = '9px monospace';
    ctx.fillText(String(it[valueKey]), x, y - 3);
    ctx.fillStyle = '#8a8aa5';
    const label = String(it[labelKey]).slice(0, 10);
    ctx.fillText(label, x, h - 5);
  });
}

async function loadData() {
  const grid = document.getElementById('grid');
  grid.innerHTML = '<div class="card"><span class="empty">Loading...</span></div>';
  try {
    const res = await fetch('/api/kortana/dashboard-data?key=' + encodeURIComponent(key));
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    render(data);
  } catch (e) {
    grid.innerHTML = '<div class="card"><span class="empty">Failed to load: ' + e.message + ' — check your ?key= is correct.</span></div>';
  }
}

function render(data) {
  document.getElementById('genAt').textContent = 'as of ' + fmtDate(data.generatedAt);
  const grid = document.getElementById('grid');
  grid.innerHTML = \`
    <div class="card">
      <h2>Memory Growth</h2>
      <div class="stat-row"><span class="label">Verified</span><span class="value">\${data.memory.verified}</span></div>
      <div class="stat-row"><span class="label">Pending</span><span class="value">\${data.memory.pending}</span></div>
      <canvas id="memChart"></canvas>
    </div>
    <div class="card">
      <h2>Goals — Daddy Sets, She Pursues</h2>
      <div class="stat-row"><span class="label">Active</span><span class="value">\${data.goals.active}</span></div>
      <div class="stat-row"><span class="label">Done</span><span class="value">\${data.goals.done}</span></div>
      <div class="stat-row"><span class="label">Blocked</span><span class="value">\${data.goals.blocked}</span></div>
      \${data.goals.list.length ? data.goals.list.map(g => \`
        <div class="list-item">
          <span class="badge \${g.status}">\${g.status.toUpperCase()}</span>\${g.text}
          <div class="meta">\${g.logCount} log entries · since \${fmtDate(g.createdAt)}</div>
        </div>
      \`).join('') : '<div class="empty">No goals set yet — tell her one in conversation.</div>'}
    </div>
    <div class="card">
      <h2>Specialist Brain Usage</h2>
      <canvas id="specChart"></canvas>
      \${!data.specialistUsage.length ? '<div class="empty">No consult_specialist calls logged yet.</div>' : ''}
    </div>
    <div class="card">
      <h2>Skills Written (\${data.skills.length})</h2>
      \${data.skills.length ? data.skills.map(s => \`
        <div class="list-item">\${s.name}<div class="meta">\${s.description || '(no description)'}</div></div>
      \`).join('') : '<div class="empty">No skills yet.</div>'}
    </div>
    <div class="card">
      <h2>Recent Growth Cycles</h2>
      \${data.recentGrowthCycles.length ? data.recentGrowthCycles.map(c => \`
        <div class="list-item">
          <div class="meta">\${fmtDate(c.at)} — tools: \${c.toolsUsed || 'none'}</div>
          \${c.summary || '(no summary)'}
        </div>
      \`).join('') : '<div class="empty">No growth cycles have fired yet (first one runs 10 min after server boot, then every 6h).</div>'}
    </div>
  \`;
  drawLineChart(document.getElementById('memChart'), data.memory.timeline, 'total');
  if (data.specialistUsage.length) {
    drawBarChart(document.getElementById('specChart'), data.specialistUsage, 'ok', 'label');
  }
}

loadData();
</script>
</body>
</html>`;
}

module.exports = { renderDashboardPage };
