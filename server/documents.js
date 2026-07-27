// Subject-matter-expert mode — a real, dependency-free document knowledge
// base. Honest about what this is: keyword/term-frequency search across
// chunked documents, NOT embeddings-based semantic search (that needs either
// a paid embedding API or local GPU infra — the DGX-cluster version of this
// feature). This version works today, on the free path, and actually finds
// keyword-overlapping passages in real ingested text.

const fs = require('fs');
const path = require('path');

const REPO_ROOT = path.join(__dirname, '..');
const DOCS_DIR = path.join(REPO_ROOT, '.agent-memory', 'documents');

function slugify(name) {
  return String(name || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60) || 'untitled';
}

// Overlapping chunks so an answer near a chunk boundary doesn't get split
// across two chunks with neither containing the full relevant passage.
function chunkText(text, chunkSize = 800, overlap = 150) {
  const clean = String(text || '').replace(/\r\n/g, '\n');
  const chunks = [];
  let i = 0;
  while (i < clean.length) {
    chunks.push(clean.slice(i, i + chunkSize));
    i += Math.max(chunkSize - overlap, 1);
  }
  return chunks;
}

function ingest(name, content) {
  const slug = slugify(name);
  const text = String(content || '');
  const chunks = chunkText(text);
  const dir = path.join(DOCS_DIR, slug);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'raw.txt'), text);
  fs.writeFileSync(path.join(dir, 'chunks.json'), JSON.stringify(chunks));
  const meta = {
    name: String(name || slug),
    slug,
    chunkCount: chunks.length,
    chars: text.length,
    ingestedAt: new Date().toISOString(),
  };
  fs.writeFileSync(path.join(dir, 'meta.json'), JSON.stringify(meta, null, 2));
  return meta;
}

function list() {
  try {
    return fs.readdirSync(DOCS_DIR)
      .map((slug) => {
        try { return JSON.parse(fs.readFileSync(path.join(DOCS_DIR, slug, 'meta.json'), 'utf8')); }
        catch { return null; }
      })
      .filter(Boolean);
  } catch {
    return [];
  }
}

function remove(name) {
  const slug = slugify(name);
  const dir = path.join(DOCS_DIR, slug);
  if (!fs.existsSync(dir)) return false;
  fs.rmSync(dir, { recursive: true, force: true });
  return true;
}

function tokenize(s) {
  return String(s || '').toLowerCase().match(/[a-z0-9]+/g) || [];
}

// Plain term-frequency scoring — a chunk's score is how many query terms it
// contains, weighted by how many times each appears. No idf/embeddings, on
// purpose: this needs zero external services and zero GPU, and is honest
// about being "keyword overlap," not "semantic similarity."
function search(query, limit = 5) {
  const qTokens = [...new Set(tokenize(query))];
  if (!qTokens.length) return [];
  const docs = list();
  const results = [];
  for (const doc of docs) {
    let chunks;
    try { chunks = JSON.parse(fs.readFileSync(path.join(DOCS_DIR, doc.slug, 'chunks.json'), 'utf8')); }
    catch { continue; }
    chunks.forEach((chunk, idx) => {
      const chunkTokens = tokenize(chunk);
      if (!chunkTokens.length) return;
      let score = 0;
      for (const qt of qTokens) {
        for (const ct of chunkTokens) if (ct === qt) score += 1;
      }
      if (score > 0) results.push({ docName: doc.name, docSlug: doc.slug, chunkIndex: idx, score, text: chunk });
    });
  }
  results.sort((a, b) => b.score - a.score);
  return results.slice(0, limit);
}

module.exports = { ingest, list, remove, search };
