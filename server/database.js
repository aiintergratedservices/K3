// Kortana's real structured-data store — a sandboxed SQLite database she can
// create tables in and query with real SQL, instead of everything living as
// flat JSON files (goals.js, documents.js, memory.js). Good for anything
// that benefits from actual schema, filtering, or aggregation — e.g. logging
// income leads with status/amount columns and querying "which are still
// open," instead of re-parsing a JSON array by hand every time.
//
// SAFETY BOUNDARY: there is exactly ONE database file, at a fixed path
// (server/data/kortana.db) she can never change — no path argument exists
// anywhere in this module. Inside that one file she has real freedom
// (CREATE/ALTER/DROP her own tables, any INSERT/UPDATE/DELETE/SELECT), but
// ATTACH/DETACH and any PRAGMA other than the read-only `table_info` are
// blocked, so she can never pull a second file into scope or touch
// filesystem-level PRAGMAs (e.g. writable_schema).
//
// HONEST CAVEAT: on a free host (Render) local disk is wiped on every
// restart — the same limitation server/data already has for state snapshots.
// This is NOT yet backed up to the Google Drive archive; treat it as real
// working memory, not guaranteed-permanent storage, until that's wired up.

const fs = require('fs');
const path = require('path');

// better-sqlite3 is a NATIVE module that has to be compiled for the host. On
// some environments (a phone running Termux, or a brand-new Node with no
// prebuilt binary yet) it may not be installed/compilable. Her CORE memory does
// NOT live here — that's .agent-memory + memory.js; this SQLite store only
// powers the OPTIONAL structured-data tools (sql_query/sql_write). So a missing
// better-sqlite3 must never take the whole server down: load it optionally and,
// if it's absent, let just the SQL tools report "unavailable" while everything
// else (brains, memory, supervisor, goals, backup) boots and runs normally.
let Database = null;
let loadError = null;
try {
  Database = require('better-sqlite3');
} catch (e) {
  loadError = e.message;
  console.warn(`[database] better-sqlite3 unavailable — SQL tools disabled, server still boots normally. (${e.message}) To enable them: cd server && npm install better-sqlite3`);
}

const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const DB_PATH = path.join(DATA_DIR, 'kortana.db');

fs.mkdirSync(DATA_DIR, { recursive: true });

let db = null;
function getDb() {
  if (!Database) {
    throw new Error(`SQLite is unavailable on this host (better-sqlite3 not installed${loadError ? ': ' + loadError : ''}). Run \`npm install better-sqlite3\` in server/ to enable the SQL tools — everything else works without them.`);
  }
  if (!db) {
    db = new Database(DB_PATH);
    db.pragma('journal_mode = WAL');
  }
  return db;
}

const FORBIDDEN_RE = /\bATTACH\b|\bDETACH\b|\bPRAGMA\s+(?!table_info)/i;
const SELECT_RE = /^\s*(SELECT|EXPLAIN|WITH)\b/i;

// Read-only: SELECT / WITH (a CTE feeding a SELECT) / EXPLAIN only.
function query(sql, params = []) {
  const s = String(sql || '');
  if (!SELECT_RE.test(s)) {
    throw new Error('query() only accepts SELECT/WITH/EXPLAIN statements — use execute() for writes');
  }
  if (FORBIDDEN_RE.test(s)) {
    throw new Error('statement contains a forbidden clause (ATTACH/DETACH/PRAGMA) — refusing');
  }
  return getDb().prepare(s).all(...params);
}

// Writes: CREATE/ALTER/DROP TABLE, INSERT/UPDATE/DELETE. Still confined to
// the one sandboxed file by construction (no path argument exists to abuse).
function execute(sql, params = []) {
  const s = String(sql || '');
  if (SELECT_RE.test(s)) {
    throw new Error('execute() is for writes — use query() for SELECT statements');
  }
  if (FORBIDDEN_RE.test(s)) {
    throw new Error('statement contains a forbidden clause (ATTACH/DETACH/PRAGMA) — refusing');
  }
  const info = getDb().prepare(s).run(...params);
  return { changes: info.changes, lastInsertRowid: info.lastInsertRowid };
}

// Her own table list + schema, so she can orient without guessing.
function listTables() {
  return getDb().prepare(
    "SELECT name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
  ).all();
}

module.exports = { query, execute, listTables, DB_PATH, available: () => Boolean(Database) };
