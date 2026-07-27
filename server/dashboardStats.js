// Aggregates real data for the learning dashboard — memory growth over time,
// goal status, skills written, and which specialist brain has been handling
// what. Every number here comes from an actual file on disk (lessons.json,
// goals.json, the skills folder, the specialist-usage log) — nothing is
// estimated or invented for display purposes.

const fs = require('fs');
const path = require('path');
const memory = require('./memory');
const goals = require('./goals');
const applyChange = require('./applyChange');

const REPO_ROOT = path.join(__dirname, '..');
const AGENT_MEMORY_DIR = path.join(REPO_ROOT, '.agent-memory');
const SPECIALIST_LOG = path.join(AGENT_MEMORY_DIR, 'logs', 'specialist-usage.log');
const GROWTH_LOG = path.join(AGENT_MEMORY_DIR, 'logs', 'growth-cycle.log');

function dayKey(ts) {
  return new Date(ts).toISOString().slice(0, 10); // YYYY-MM-DD
}

// Buckets lesson creation timestamps into a daily running-total series, so
// the dashboard can draw a real growth-over-time line instead of just a
// single current count.
function memoryTimeline() {
  const lessons = memory.all().slice().sort((a, b) => (a.createdAt || 0) - (b.createdAt || 0));
  const byDay = new Map();
  for (const l of lessons) {
    const k = dayKey(l.createdAt || Date.now());
    byDay.set(k, (byDay.get(k) || 0) + 1);
  }
  const days = [...byDay.keys()].sort();
  let running = 0;
  return days.map((d) => {
    running += byDay.get(d);
    return { date: d, total: running, addedThatDay: byDay.get(d) };
  });
}

function skillsList() {
  try {
    const dir = path.join(AGENT_MEMORY_DIR, 'skills');
    const names = fs.readdirSync(dir);
    return names.map((name) => {
      let description = '';
      try {
        const txt = fs.readFileSync(path.join(dir, name, 'SKILL.md'), 'utf8');
        const m = txt.match(/^description:\s*(.+)$/mi);
        description = m ? m[1].trim().slice(0, 160) : '';
      } catch { /* skip unreadable */ }
      return { name, description };
    });
  } catch {
    return [];
  }
}

function specialistUsage() {
  try {
    const raw = fs.readFileSync(SPECIALIST_LOG, 'utf8');
    const lines = raw.trim().split('\n').filter(Boolean);
    const counts = {};
    for (const line of lines) {
      const [, specialty, label, status] = line.split('\t');
      if (!label) continue;
      const key = label;
      if (!counts[key]) counts[key] = { label: key, specialty, ok: 0, unavailable: 0 };
      if (status === 'ok') counts[key].ok += 1; else counts[key].unavailable += 1;
    }
    return Object.values(counts);
  } catch {
    return [];
  }
}

function recentGrowthCycles(limit = 10) {
  try {
    const raw = fs.readFileSync(GROWTH_LOG, 'utf8');
    const lines = raw.trim().split('\n').filter(Boolean);
    // Each cycle logs 3 lines (starting / tools used / reply) — group by
    // consecutive "starting" markers so the dashboard shows one row per
    // actual cycle, not one row per log line.
    const cycles = [];
    let current = null;
    for (const line of lines) {
      if (line.includes('growth cycle starting')) {
        if (current) cycles.push(current);
        const m = line.match(/^\[([^\]]+)\]/);
        current = { at: m ? m[1] : '', toolsUsed: '', summary: '' };
      } else if (current) {
        if (line.includes('tools used:')) current.toolsUsed = line.split('tools used:')[1]?.trim() || '';
        else if (line.includes('reply:')) current.summary = line.split('reply:')[1]?.trim().slice(0, 200) || '';
      }
    }
    if (current) cycles.push(current);
    return cycles.slice(-limit).reverse();
  } catch {
    return [];
  }
}

// Simple listing helper for the freelance-drafts / income-research folders —
// just filenames + mtimes, the human opens the actual file to read it.
function listFolder(dirName) {
  try {
    const dir = path.join(AGENT_MEMORY_DIR, dirName);
    return fs.readdirSync(dir)
      .map((f) => ({ filename: f, mtime: fs.statSync(path.join(dir, f)).mtime.toISOString() }))
      .sort((a, b) => new Date(b.mtime) - new Date(a.mtime));
  } catch {
    return [];
  }
}

function summary() {
  const memStats = memory.stats();
  const allGoals = goals.list();
  return {
    memory: {
      ...memStats,
      timeline: memoryTimeline(),
    },
    goals: {
      total: allGoals.length,
      active: allGoals.filter((g) => g.status === 'active').length,
      done: allGoals.filter((g) => g.status === 'done').length,
      blocked: allGoals.filter((g) => g.status === 'blocked').length,
      list: allGoals.map((g) => ({ id: g.id, text: g.text, status: g.status, createdAt: g.createdAt, logCount: g.log.length })),
    },
    skills: skillsList(),
    specialistUsage: specialistUsage(),
    recentGrowthCycles: recentGrowthCycles(),
    pendingProposals: applyChange.listPending(),
    freelanceDrafts: listFolder('freelance_drafts'),
    incomeResearch: listFolder('income_research'),
    generatedAt: new Date().toISOString(),
  };
}

module.exports = { summary };
