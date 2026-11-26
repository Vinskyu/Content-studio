'use strict';

const http = require('http');
const { spawn, execFile } = require('child_process');
const util = require('util');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const execFileAsync = util.promisify(execFile);

const PORT = parseInt(process.env.PORT || '18950', 10);
const HOST = '127.0.0.1';

const HOME_DIR = process.env.HOME || '/tmp';
const PREFIX_DIR = process.env.PREFIX || '/usr';

const CONTENT_STUDIO_DIR = path.join(HOME_DIR, 'ContentStudio');
const WORKSPACES_DIR = path.join(CONTENT_STUDIO_DIR, 'workspaces');
const SESSIONS_DIR = path.join(HOME_DIR, '.contentstudio', 'sessions');
const STATE_FILE = path.join(HOME_DIR, '.contentstudio', 'state.json');
const CHANNELS_FILE = path.join(CONTENT_STUDIO_DIR, 'channels.json');
const PATTERNS_FILE = path.join(CONTENT_STUDIO_DIR, 'patterns.json');
const LIBRARY_DIR = path.join(CONTENT_STUDIO_DIR, 'library');
const LIBRARY_CATEGORIES = ['video', 'audio', 'image', 'other'];

const ANTIGRAVITY_BIN = path.join(PREFIX_DIR, 'bin', 'antigravity');
const AUTH_MARKER_FILE = path.join(HOME_DIR, '.antigravity_auth_ok');
const PTY_BRIDGE_PATH = path.join(HOME_DIR, 'pty-bridge.py');
const PTY_BRIDGE_FALLBACK = path.join(__dirname, 'pty-bridge.py');

fs.mkdirSync(SESSIONS_DIR, { recursive: true });
fs.mkdirSync(WORKSPACES_DIR, { recursive: true });
for (const cat of LIBRARY_CATEGORIES) {
  fs.mkdirSync(path.join(LIBRARY_DIR, cat), { recursive: true });
}

let activeChatProc = null;
let activeAuthProc = null;
const renderJobs = new Map();
let lastAuthError = null;

function getAntigravityEnv(isAuthMode = false) {
  const env = {
    ...process.env,
    HOME: HOME_DIR,
    XDG_CONFIG_HOME: path.join(HOME_DIR, '.config'),
    XDG_DATA_HOME: path.join(HOME_DIR, '.local', 'share'),
    SSL_CERT_FILE: path.join(PREFIX_DIR, 'etc', 'tls', 'cert.pem'),
    TERM: isAuthMode ? 'xterm-256color' : 'dumb',
    NO_COLOR: isAuthMode ? '0' : '1',
    FORCE_COLOR: '0',
  };

  // Jangan pasang ANTIGRAVITY_SAFECLIS_SOURCE saat auth mode
  if (isAuthMode) {
    delete env.ANTIGRAVITY_SAFECLIS_SOURCE;
  } else {
    env.ANTIGRAVITY_SAFECLIS_SOURCE = '1';
  }

  return env;
}

function resolvePythonBin() {
  const candidates = [
    path.join(PREFIX_DIR, 'bin', 'python3'),
    path.join(PREFIX_DIR, 'bin', 'python'),
    '/data/user/0/com.agy/files/usr/bin/python3',
    '/data/user/0/com.agy/files/usr/bin/python',
    'python3',
    'python',
  ];
  for (const c of candidates) {
    if (c.startsWith('/') && fs.existsSync(c)) return c;
  }
  return 'python3';
}

function resolvePtyBridge() {
  const candidates = [
    PTY_BRIDGE_PATH,
    PTY_BRIDGE_FALLBACK,
    path.join(HOME_DIR, 'pty-bridge.py'),
    path.join(__dirname, 'pty-bridge.py'),
    '/data/user/0/com.agy/files/pty-bridge.py',
  ];
  for (const c of candidates) {
    if (c && fs.existsSync(c)) return c;
  }
  return null;
}

function stripAnsi(str) {
  if (!str) return '';
  return String(str)
    .replace(/\x1B\[[0-9;?]*[ -/]*[@-~]/g, '')
    .replace(/\x1B[@-Z\\-_]/g, '');
}

function cleanStderr(rawStderr) {
  const clean = stripAnsi(rawStderr || '');
  const lines = clean.split(/\r?\n/);
  const kept = [];

  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    if (trimmed.includes('logging before google.Init')) continue;
    if (trimmed.includes('Initialized Android local timezone')) continue;
    kept.push(trimmed);
  }

  return kept.join('\n').trim();
}

function readState() {
  try {
    if (fs.existsSync(STATE_FILE)) {
      return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
    }
  } catch (_) {}
  return { active_session_id: '', active_workspace: '' };
}

function writeState(state) {
  try {
    fs.mkdirSync(path.dirname(STATE_FILE), { recursive: true });
    fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2));
  } catch (_) {}
}

function listSessions() {
  fs.mkdirSync(SESSIONS_DIR, { recursive: true });
  const files = fs.readdirSync(SESSIONS_DIR).filter((f) => f.endsWith('.json'));
  const sessions = [];
  for (const f of files) {
    try {
      const data = JSON.parse(fs.readFileSync(path.join(SESSIONS_DIR, f), 'utf8'));
      const count = Array.isArray(data.messages) ? data.messages.length : 0;
      if (count === 0) continue;
      sessions.push({
        id: data.id,
        title: data.title || 'Sesi Baru',
        workspace: data.workspace || '',
        created_at: data.created_at || 0,
        updated_at: data.updated_at || 0,
        message_count: count,
      });
    } catch (_) {}
  }
  sessions.sort((a, b) => (b.updated_at || 0) - (a.updated_at || 0));
  return sessions;
}

function loadSession(id) {
  const safeId = path.basename(String(id || ''));
  if (!safeId) return null;
  const filePath = path.join(SESSIONS_DIR, `${safeId}.json`);
  if (!fs.existsSync(filePath)) return null;
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'));
  } catch (_) {
    return null;
  }
}

function saveSession(session) {
  const safeId = path.basename(String(session.id));
  const filePath = path.join(SESSIONS_DIR, `${safeId}.json`);
  fs.writeFileSync(filePath, JSON.stringify(session, null, 2));
}

function createSession(title, workspace = '') {
  const id = `ses_${Date.now()}_${crypto.randomBytes(2).toString('hex')}`;
  const now = Date.now();
  const session = {
    id,
    title: title || 'Sesi Baru',
    workspace: workspace || '',
    created_at: now,
    updated_at: now,
    messages: [],
  };
  saveSession(session);
  const st = readState();
  st.active_session_id = id;
  if (workspace) {
    st.active_workspace = workspace;
  }
  writeState(st);
  return session;
}

function ensureActiveSession(workspace = '') {
  const st = readState();
  if (st.active_session_id) {
    const existing = loadSession(st.active_session_id);
    if (existing) {
      if (workspace && !existing.workspace) {
        existing.workspace = workspace;
        saveSession(existing);
      }
      return existing;
    }
  }
  const all = listSessions();
  if (all.length > 0) {
    const first = loadSession(all[0].id);
    if (first) {
      st.active_session_id = first.id;
      writeState(st);
      return first;
    }
  }
  return createSession('Sesi Baru', workspace);
}

function sendJson(res, statusCode, data) {
  const body = JSON.stringify(data);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let raw = '';
    req.on('data', (chunk) => {
      raw += chunk;
      if (raw.length > 2 * 1024 * 1024) {
        reject(new Error('Body terlalu besar'));
      }
    });
    req.on('end', () => {
      if (!raw.trim()) return resolve({});
      try {
        resolve(JSON.parse(raw));
      } catch (e) {
        reject(e);
      }
    });
    req.on('error', reject);
  });
}

// ── Workspace & File Management ─────────────────────────────────────────────

function countFilesRecursive(dir) {
  let count = 0;
  let latestMtime = 0;
  if (!fs.existsSync(dir)) return { count, latestMtime };

  try {
    const entries = fs.readdirSync(dir, { withFileTypes: true });
    for (const ent of entries) {
      const full = path.join(dir, ent.name);
      if (ent.isDirectory()) {
        const sub = countFilesRecursive(full);
        count += sub.count;
        if (sub.latestMtime > latestMtime) latestMtime = sub.latestMtime;
      } else if (ent.isFile()) {
        count++;
        try {
          const st = fs.statSync(full);
          if (st.mtimeMs > latestMtime) latestMtime = st.mtimeMs;
        } catch (_) {}
      }
    }
  } catch (_) {}

  return { count, latestMtime };
}

// ── Living Patterns ─────────────────────────────────────────────────────────

const DEFAULT_PATTERNS = [
  {
    id: 'fast-hook-twist',
    name: 'Fast Hook-Twist',
    aspect_ratio: '9:16',
    beats_count: 4,
    total_duration: 13.0,
    beats: [
      { role: 'Hook', duration: 2.5 },
      { role: 'Setup', duration: 4.0 },
      { role: 'Twist', duration: 4.0 },
      { role: 'Close', duration: 2.5 },
    ],
  },
  {
    id: 'visual-breather',
    name: 'Visual Breather',
    aspect_ratio: '9:16',
    beats_count: 4,
    total_duration: 16.0,
    beats: [
      { role: 'Open Visual', duration: 5.0 },
      { role: 'Short Beat', duration: 3.0 },
      { role: 'Main Visual', duration: 4.5 },
      { role: 'Outro', duration: 3.5 },
    ],
  },
  {
    id: '3-beat-breakdown',
    name: '3-Beat Breakdown',
    aspect_ratio: '9:16',
    beats_count: 5,
    total_duration: 17.0,
    beats: [
      { role: 'Hook', duration: 2.5 },
      { role: 'Point 1', duration: 4.0 },
      { role: 'Point 2', duration: 4.0 },
      { role: 'Point 3', duration: 4.0 },
      { role: 'Wrap', duration: 2.5 },
    ],
  },
];

function readPatterns() {
  try {
    if (fs.existsSync(PATTERNS_FILE)) {
      const data = JSON.parse(fs.readFileSync(PATTERNS_FILE, 'utf8'));
      if (Array.isArray(data)) {
        return data.map((p) => {
          const beats = Array.isArray(p.beats) ? p.beats : [];
          const totalDur = beats.reduce((sum, b) => sum + (parseFloat(b.duration) || 0), 0);
          return {
            id: p.id,
            name: p.name || 'Pola Ritme',
            aspect_ratio: p.aspect_ratio || '9:16',
            beats_count: beats.length,
            total_duration: parseFloat(totalDur.toFixed(1)),
            beats,
          };
        });
      }
    }
  } catch (_) {}

  try {
    fs.mkdirSync(path.dirname(PATTERNS_FILE), { recursive: true });
    fs.writeFileSync(PATTERNS_FILE, JSON.stringify(DEFAULT_PATTERNS, null, 2));
  } catch (_) {}
  return DEFAULT_PATTERNS;
}

function writePatterns(list) {
  try {
    fs.mkdirSync(path.dirname(PATTERNS_FILE), { recursive: true });
    fs.writeFileSync(PATTERNS_FILE, JSON.stringify(list, null, 2));
  } catch (_) {}
}

function createPattern(data) {
  const name = String(data.name || '').trim();
  if (!name) {
    throw new Error('Nama pola tidak boleh kosong');
  }
  const patterns = readPatterns();
  const id = `pat_${Date.now()}_${crypto.randomBytes(2).toString('hex')}`;
  const beats = Array.isArray(data.beats)
    ? data.beats.map((b, idx) => ({
        role: String(b.role || `Beat ${idx + 1}`).trim(),
        duration: Math.max(0.5, parseFloat(b.duration) || 3.0),
      }))
    : [];
  const totalDur = beats.reduce((sum, b) => sum + b.duration, 0);
  const newPat = {
    id,
    name,
    aspect_ratio: data.aspect_ratio === '16:9' ? '16:9' : '9:16',
    beats_count: beats.length,
    total_duration: parseFloat(totalDur.toFixed(1)),
    beats,
  };
  patterns.push(newPat);
  writePatterns(patterns);
  return newPat;
}

function deletePattern(id) {
  const patterns = readPatterns();
  const filtered = patterns.filter((p) => p.id !== id);
  if (filtered.length === patterns.length) {
    throw new Error(`Pola "${id}" tidak ditemukan`);
  }
  writePatterns(filtered);
  return { deleted: id };
}

function saveWorkspaceAsPattern(wsName, body) {
  const cleanWs = path.basename(wsName);
  const proj = readWorkspaceProject(cleanWs);
  const scenes = Array.isArray(proj.scenes) ? proj.scenes : [];
  if (scenes.length === 0) {
    throw new Error('Project belum memiliki adegan untuk disimpan sebagai pola');
  }

  // Wajib STRIP: narration, prompt, visual_file (ambil murni beat_role & duration)
  const beats = scenes.map((s, idx) => ({
    role: String(s.beat_role || s.title || `Beat ${idx + 1}`).trim(),
    duration: Math.max(0.5, parseFloat(s.duration) || 3.0),
  }));

  const patName = String((body && body.name) ? body.name : `${proj.name || cleanWs} Pattern`).trim();
  return createPattern({
    name: patName,
    aspect_ratio: proj.aspect_ratio || '9:16',
    beats,
  });
}

function applyPatternToWorkspace(wsName, body) {
  const cleanWs = path.basename(wsName);
  const proj = readWorkspaceProject(cleanWs);
  let targetBeats = [];

  if (body && Array.isArray(body.beats) && body.beats.length > 0) {
    targetBeats = body.beats;
  } else if (body && body.pattern_id) {
    const patterns = readPatterns();
    const found = patterns.find((p) => p.id === body.pattern_id);
    if (!found) throw new Error(`Pola dengan ID "${body.pattern_id}" tidak ditemukan`);
    targetBeats = found.beats;
  } else {
    throw new Error('Pattern ID atau beats wajib disertakan');
  }

  if (!Array.isArray(proj.scenes)) {
    proj.scenes = [];
  }

  // Menerapkan susunan durasi & beat_role tanpa merusak naskah/aset yang sudah ada
  for (let i = 0; i < targetBeats.length; i++) {
    const beat = targetBeats[i];
    const dur = Math.max(0.5, parseFloat(beat.duration) || 3.0);
    const role = String(beat.role || `Beat ${i + 1}`).trim();

    if (proj.scenes[i]) {
      proj.scenes[i].beat_role = role;
      proj.scenes[i].duration = dur;
      if (!proj.scenes[i].title) {
        proj.scenes[i].title = role;
      }
    } else {
      proj.scenes.push({
        id: `sc_${Date.now()}_${i + 1}`,
        title: role,
        beat_role: role,
        duration: dur,
        narration: '',
        visual_file: null,
        transition: 'none',
      });
    }
  }

  writeWorkspaceProject(cleanWs, proj);
  return proj;
}

// ── Channel Hub & DNA Evolution ─────────────────────────────────────────────

const DEFAULT_VOICE_PROFILE = {
  voice: 'en-US-BrianMultilingualNeural',
  rate: '+0%',
  pitch: '+0Hz',
  master_preset: 'natural',
};

function normalizeVoiceProfile(vp, fallback) {
  const base = fallback || DEFAULT_VOICE_PROFILE;
  const src = (vp && typeof vp === 'object') ? vp : {};
  return {
    voice: String(src.voice || base.voice || DEFAULT_VOICE_PROFILE.voice).trim(),
    rate: String(src.rate || base.rate || DEFAULT_VOICE_PROFILE.rate).trim(),
    pitch: String(src.pitch || base.pitch || DEFAULT_VOICE_PROFILE.pitch).trim(),
    master_preset: String(src.master_preset || base.master_preset || DEFAULT_VOICE_PROFILE.master_preset).trim(),
  };
}

function readChannels() {
  try {
    if (fs.existsSync(CHANNELS_FILE)) {
      const data = JSON.parse(fs.readFileSync(CHANNELS_FILE, 'utf8'));
      if (Array.isArray(data)) {
        return data.map((c) => {
          if (!c.dna || typeof c.dna !== 'object') {
            c.dna = {
              learned_traits: [],
              reference_notes: c.style_notes || '',
              avg_scene_duration: 3.5,
              style_summary: c.tone || '',
              voice_profile: { ...DEFAULT_VOICE_PROFILE },
            };
          } else {
            if (!Array.isArray(c.dna.learned_traits)) c.dna.learned_traits = [];
            if (typeof c.dna.reference_notes !== 'string') c.dna.reference_notes = '';
            if (typeof c.dna.avg_scene_duration !== 'number') c.dna.avg_scene_duration = 3.5;
            if (typeof c.dna.style_summary !== 'string') c.dna.style_summary = '';
            c.dna.voice_profile = normalizeVoiceProfile(c.dna.voice_profile);
          }
          return c;
        });
      }
    }
  } catch (_) {}
  return [];
}

function writeChannels(list) {
  try {
    fs.mkdirSync(path.dirname(CHANNELS_FILE), { recursive: true });
    fs.writeFileSync(CHANNELS_FILE, JSON.stringify(list, null, 2));
  } catch (_) {}
}

function createChannel(data) {
  const name = String(data.name || '').trim();
  if (!name) {
    throw new Error('Nama channel tidak boleh kosong');
  }
  const channels = readChannels();
  const id = `chan_${Date.now()}_${crypto.randomBytes(2).toString('hex')}`;
  const newChan = {
    id,
    name,
    niche: String(data.niche || '').trim(),
    target_audience: String(data.target_audience || '').trim(),
    aspect_ratio: data.aspect_ratio === '16:9' ? '16:9' : '9:16',
    tone: String(data.tone || '').trim(),
    visual_rules: String(data.visual_rules || data.visualRules || data.style_notes || '').trim(),
    subtitle_style: String(data.subtitle_style || data.subtitleStyle || '').trim(),
    style_notes: String(data.style_notes || data.visual_rules || '').trim(),
    dna: {
      learned_traits: Array.isArray(data.dna && data.dna.learned_traits) ? data.dna.learned_traits : [],
      reference_notes: String((data.dna && data.dna.reference_notes) || data.reference_notes || data.style_notes || '').trim(),
      avg_scene_duration: typeof (data.dna && data.dna.avg_scene_duration) === 'number' ? data.dna.avg_scene_duration : 3.5,
      style_summary: String((data.dna && data.dna.style_summary) || data.tone || '').trim(),
      voice_profile: normalizeVoiceProfile(data.dna && data.dna.voice_profile),
    },
    created_at: Date.now(),
  };
  channels.push(newChan);
  writeChannels(channels);
  return newChan;
}

function updateChannel(id, data) {
  const channels = readChannels();
  const idx = channels.findIndex((c) => c.id === id);
  if (idx === -1) {
    throw new Error(`Channel "${id}" tidak ditemukan`);
  }
  const existing = channels[idx];
  const updated = {
    ...existing,
    name: data.name !== undefined ? String(data.name).trim() : existing.name,
    niche: data.niche !== undefined ? String(data.niche).trim() : existing.niche,
    target_audience: data.target_audience !== undefined ? String(data.target_audience).trim() : existing.target_audience,
    aspect_ratio: data.aspect_ratio === '16:9' ? '16:9' : (data.aspect_ratio === '9:16' ? '9:16' : existing.aspect_ratio),
    tone: data.tone !== undefined ? String(data.tone).trim() : existing.tone,
    visual_rules: data.visual_rules !== undefined ? String(data.visual_rules).trim() : (data.visualRules !== undefined ? String(data.visualRules).trim() : existing.visual_rules || existing.style_notes || ''),
    subtitle_style: data.subtitle_style !== undefined ? String(data.subtitle_style).trim() : (data.subtitleStyle !== undefined ? String(data.subtitleStyle).trim() : existing.subtitle_style || ''),
    style_notes: data.style_notes !== undefined ? String(data.style_notes).trim() : (data.visual_rules !== undefined ? String(data.visual_rules).trim() : existing.style_notes || ''),
    dna: {
      learned_traits: Array.isArray(data.dna && data.dna.learned_traits) ? data.dna.learned_traits : (existing.dna && existing.dna.learned_traits ? existing.dna.learned_traits : []),
      reference_notes: String((data.dna && data.dna.reference_notes) || (existing.dna && existing.dna.reference_notes) || '').trim(),
      avg_scene_duration: typeof (data.dna && data.dna.avg_scene_duration) === 'number' ? data.dna.avg_scene_duration : (existing.dna && typeof existing.dna.avg_scene_duration === 'number' ? existing.dna.avg_scene_duration : 3.5),
      style_summary: String((data.dna && data.dna.style_summary) || (existing.dna && existing.dna.style_summary) || '').trim(),
      voice_profile: normalizeVoiceProfile(data.dna && data.dna.voice_profile, existing.dna && existing.dna.voice_profile),
    },
    updated_at: Date.now(),
  };
  channels[idx] = updated;
  writeChannels(channels);
  return updated;
}

function deleteChannel(id) {
  const channels = readChannels();
  const filtered = channels.filter((c) => c.id !== id);
  if (filtered.length === channels.length) {
    throw new Error(`Channel "${id}" tidak ditemukan`);
  }
  writeChannels(filtered);
  return { deleted: id };
}

function localHeuristicDnaExtraction(referenceText) {
  const text = String(referenceText || '').trim();
  const lower = text.toLowerCase();

  let avgDur = 3.5;
  if (lower.includes('short') || lower.includes('tiktok') || lower.includes('reels') || lower.includes('cepat') || lower.includes('fast')) {
    avgDur = 2.8;
  } else if (lower.includes('panjang') || lower.includes('tutorial') || lower.includes('dokumenter') || lower.includes('deep dive') || lower.includes('explainer')) {
    avgDur = 4.8;
  }

  const traits = [];
  if (lower.includes('hook') || lower.includes('pancing')) {
    traits.push('Hook tajam dan visual punch dalam 3 detik awal');
  } else {
    traits.push('Pembuka langsung to-the-point tanpa intro panjang');
  }

  if (lower.includes('cepat') || lower.includes('dinamis') || lower.includes('ritme')) {
    traits.push('Pacing dinamis dengan pergantian visual setiap 2-3 detik');
  } else {
    traits.push('Pacing teratur menyesuaikan penekanan kata kunci naskah');
  }

  if (lower.includes('sub') || lower.includes('teks') || lower.includes('caption')) {
    traits.push('Tipografi tegas di area tengah bawah dengan penekanan kata');
  } else {
    traits.push('Visual bersih fokus pada kontras subjek utama');
  }

  const summary = text.length > 120 ? text.slice(0, 117) + '...' : (text || 'Gaya konten dinamis');

  return {
    style_summary: summary,
    reference_notes: text.slice(0, 1000),
    avg_scene_duration: avgDur,
    learned_traits: traits,
  };
}

async function extractChannelDna(channelId, refText) {
  const channels = readChannels();
  const chan = channels.find((c) => c.id === channelId);
  if (!chan) throw new Error(`Channel "${channelId}" tidak ditemukan`);

  const text = String(refText || '').trim();
  if (!text) throw new Error('Teks referensi tidak boleh kosong');

  let extracted = null;

  if (fs.existsSync(ANTIGRAVITY_BIN)) {
    try {
      const prompt = `Analisis teks referensi ini dan berikan ringkasan DNA gaya konten dalam format JSON murni:
{
  "style_summary": "1 kalimat ringkasan gaya",
  "reference_notes": "catatan referensi",
  "avg_scene_duration": 3.2,
  "learned_traits": ["trait visual", "trait tempo", "trait audio"]
}
HANYA kembalikan JSON tanpa markdown.
Teks referensi:
${text.slice(0, 2000)}`;

      const { stdout } = await new Promise((resolve, reject) => {
        let p = null;
        const timer = setTimeout(() => {
          if (p) try { p.kill('SIGTERM'); } catch (_) {}
          reject(new Error('Timeout'));
        }, 25000);

        try {
          p = spawn(ANTIGRAVITY_BIN, ['--dangerously-skip-permissions', '-p', prompt], {
            env: getAntigravityEnv(false),
            cwd: HOME_DIR,
          });
          let out = '';
          p.stdout.on('data', (d) => { out += d.toString('utf8'); });
          p.on('close', (code) => {
            clearTimeout(timer);
            if (code === 0) resolve({ stdout: out });
            else reject(new Error(`Antigravity exit code ${code}`));
          });
          p.on('error', (err) => {
            clearTimeout(timer);
            reject(err);
          });
        } catch (e) {
          clearTimeout(timer);
          reject(e);
        }
      });

      const cleanJson = stripAnsi(stdout).replace(/```json/g, '').replace(/```/g, '').trim();
      const match = cleanJson.match(/\{[\s\S]*\}/);
      if (match) {
        extracted = JSON.parse(match[0]);
      }
    } catch (_) {}
  }

  if (!extracted) {
    extracted = localHeuristicDnaExtraction(text);
  }

  if (!chan.dna) {
    chan.dna = { learned_traits: [], reference_notes: '', avg_scene_duration: 3.5, style_summary: '' };
  }
  chan.dna.reference_notes = extracted.reference_notes || text.slice(0, 1000);
  chan.dna.style_summary = extracted.style_summary || chan.dna.style_summary || '';
  chan.dna.avg_scene_duration = typeof extracted.avg_scene_duration === 'number' ? extracted.avg_scene_duration : 3.5;
  if (Array.isArray(extracted.learned_traits)) {
    for (const t of extracted.learned_traits) {
      if (t && !chan.dna.learned_traits.includes(t)) {
        chan.dna.learned_traits.push(t);
      }
    }
  }

  writeChannels(channels);
  return chan;
}

function promoteWorkspaceDna(wsName) {
  const cleanWs = path.basename(wsName);
  const proj = readWorkspaceProject(cleanWs);
  if (!proj) throw new Error(`Workspace "${cleanWs}" tidak ditemukan`);

  const channels = readChannels();
  let chan = proj.channel_id ? channels.find((c) => c.id === proj.channel_id) : null;
  if (!chan && channels.length > 0) {
    chan = channels[0];
    proj.channel_id = chan.id;
    writeWorkspaceProject(cleanWs, proj);
  }
  if (!chan) throw new Error('Belum ada channel untuk menampung DNA proyek ini');

  if (!chan.dna) {
    chan.dna = { learned_traits: [], reference_notes: '', avg_scene_duration: 3.5, style_summary: '' };
  }

  const scenes = Array.isArray(proj.scenes) ? proj.scenes : [];
  const count = scenes.length;
  const totalDur = scenes.reduce((sum, s) => sum + (parseFloat(s.duration) || 0), 0);
  const avgDur = count > 0 ? parseFloat((totalDur / count).toFixed(1)) : 3.5;
  const roles = scenes.map((s) => s.beat_role || 'Beat').join(' -> ');
  const trait = `Pola ${count} adegan (ritme rata-rata ${avgDur}s): ${roles.slice(0, 90)}`;

  if (!chan.dna.learned_traits.includes(trait)) {
    chan.dna.learned_traits.unshift(trait);
  }
  chan.dna.avg_scene_duration = avgDur;
  writeChannels(channels);

  return { channel: chan, dna: chan.dna, learned_trait: trait, avg_scene_duration: avgDur };
}

async function syncVoDuration(wsName) {
  const cleanWs = path.basename(wsName);
  const proj = readWorkspaceProject(cleanWs);
  if (!proj) throw new Error(`Workspace "${cleanWs}" tidak ditemukan`);

  const wsPath = path.join(WORKSPACES_DIR, cleanWs);
  let voPath = (proj.audio && proj.audio.vo_file) ? proj.audio.vo_file : null;
  if (voPath && !path.isAbsolute(voPath)) {
    voPath = path.join(wsPath, voPath);
  }

  if (!voPath || !fs.existsSync(voPath)) {
    const candidates = [
      path.join(wsPath, 'voiceover.m4a'),
      path.join(wsPath, 'voiceover.mp3'),
      path.join(wsPath, 'voiceover.wav'),
      path.join(wsPath, 'audio', 'voiceover.m4a'),
      path.join(wsPath, 'audio', 'voiceover.mp3'),
    ];
    for (const cand of candidates) {
      if (fs.existsSync(cand) && fs.statSync(cand).isFile()) {
        voPath = cand;
        proj.audio = proj.audio || {};
        proj.audio.vo_file = path.relative(wsPath, cand);
        break;
      }
    }
    if (!voPath || !fs.existsSync(voPath)) {
      const audioSub = path.join(wsPath, 'audio');
      if (fs.existsSync(audioSub)) {
        const audioFiles = fs.readdirSync(audioSub)
          .filter((f) => ['.mp3', '.m4a', '.wav', '.aac'].includes(path.extname(f).toLowerCase()))
          .sort((a, b) => fs.statSync(path.join(audioSub, b)).mtimeMs - fs.statSync(path.join(audioSub, a)).mtimeMs);
        if (audioFiles.length > 0) {
          voPath = path.join(audioSub, audioFiles[0]);
          proj.audio = proj.audio || {};
          proj.audio.vo_file = path.join('audio', audioFiles[0]);
        }
      }
    }
  }

  if (!voPath || !fs.existsSync(voPath)) {
    throw new Error('Belum ada file rekaman VO di audio project atau direktori workspace');
  }

  const ffprobeBin = fs.existsSync(path.join(PREFIX_DIR, 'bin', 'ffprobe')) ? path.join(PREFIX_DIR, 'bin', 'ffprobe') : 'ffprobe';
  let totalVoSec = 0;
  try {
    const { stdout } = await execFileAsync(ffprobeBin, [
      '-v', 'error',
      '-show_entries', 'format=duration',
      '-of', 'default=noprint_wrappers=1:nokey=1',
      voPath,
    ]);
    totalVoSec = Math.max(1.0, parseFloat(stdout.trim()) || 0);
  } catch (probeErr) {
    throw new Error(`Gagal membaca durasi audio VO: ${probeErr.message}`);
  }

  if (totalVoSec <= 0) {
    throw new Error('Durasi file VO tidak valid atau kosong');
  }

  const scenes = Array.isArray(proj.scenes) ? proj.scenes : [];
  if (scenes.length === 0) {
    return { status: 'ok', total_duration: totalVoSec, scenes: [] };
  }

  const wordCounts = scenes.map((sc) => {
    const text = String(sc.narration || sc.text || '').trim();
    if (!text) return 0;
    return text.split(/\s+/).filter(Boolean).length;
  });
  const totalWords = wordCounts.reduce((a, b) => a + b, 0);

  if (totalWords > 0) {
    let accumulated = 0;
    for (let i = 0; i < scenes.length; i++) {
      if (i === scenes.length - 1) {
        const remaining = Math.max(1.0, parseFloat((totalVoSec - accumulated).toFixed(1)));
        scenes[i].duration = remaining;
      } else {
        const ratio = wordCounts[i] > 0 ? (wordCounts[i] / totalWords) : (1 / (totalWords * 2));
        const dur = Math.max(1.0, parseFloat((ratio * totalVoSec).toFixed(1)));
        scenes[i].duration = dur;
        accumulated += dur;
      }
    }
  } else {
    const perScene = Math.max(1.0, parseFloat((totalVoSec / scenes.length).toFixed(1)));
    let accumulated = 0;
    for (let i = 0; i < scenes.length; i++) {
      if (i === scenes.length - 1) {
        scenes[i].duration = Math.max(1.0, parseFloat((totalVoSec - accumulated).toFixed(1)));
      } else {
        scenes[i].duration = perScene;
        accumulated += perScene;
      }
    }
  }

  writeWorkspaceProject(cleanWs, proj);
  return { status: 'ok', total_duration: totalVoSec, audio_duration: totalVoSec, scenes: proj.scenes };
}

function buildLocalPublishKitFallback(cleanWs, proj, scriptContent, chan) {
  const scenes = Array.isArray(proj.scenes) ? proj.scenes : [];
  const firstNarration = scenes.map((s) => String(s.narration || s.text || '').trim()).find(Boolean) || '';
  const baseTitle = (proj.title || proj.name || cleanWs || 'Video Project').trim();
  let title = baseTitle;
  if (firstNarration) {
    const firstSentence = firstNarration.split(/[.!?]/)[0].trim();
    if (firstSentence.length >= 12 && firstSentence.length <= 75) {
      title = firstSentence;
    } else if (firstSentence.length > 75) {
      title = firstSentence.slice(0, 72) + '...';
    }
  }
  const allNarration = scenes.map((s) => String(s.narration || s.text || '').trim()).filter(Boolean).join(' ');
  const bodyText = (scriptContent || allNarration || baseTitle).trim();
  const description = bodyText.length > 350 ? bodyText.slice(0, 347) + '...' : bodyText;

  const stopWords = new Set(['yang', 'dan', 'di', 'ke', 'dari', 'ini', 'itu', 'untuk', 'dengan', 'pada', 'adalah', 'atau', 'juga', 'the', 'and', 'for', 'with', 'this', 'that', 'from', 'into', 'your']);
  const words = (title + ' ' + bodyText + ' ' + ((chan && chan.name) || ''))
    .toLowerCase()
    .replace(/[^a-z0-9\s]/g, ' ')
    .split(/\s+/)
    .filter((w) => w.length >= 4 && !stopWords.has(w));
  const uniqueTags = [];
  for (const w of words) {
    const tag = '#' + w;
    if (!uniqueTags.includes(tag)) {
      uniqueTags.push(tag);
      if (uniqueTags.length >= 8) break;
    }
  }
  if (uniqueTags.length === 0) {
    uniqueTags.push('#shorts', '#reels', '#contentstudio');
  }
  return {
    title,
    description: description || `${title}`,
    tags: uniqueTags.join(' '),
  };
}

async function generatePublishKit(wsName) {
  const cleanWs = path.basename(wsName);
  const wsPath = path.join(WORKSPACES_DIR, cleanWs);
  const proj = readWorkspaceProject(cleanWs);
  if (!proj) throw new Error(`Workspace "${cleanWs}" tidak ditemukan`);

  const scriptPath = path.join(wsPath, 'scripts', 'main.txt');
  let scriptContent = '';
  if (fs.existsSync(scriptPath)) {
    try { scriptContent = fs.readFileSync(scriptPath, 'utf8').trim(); } catch (_) {}
  }

  const scenes = Array.isArray(proj.scenes) ? proj.scenes : [];
  const scenesNarration = scenes.map((s, idx) => `Scene ${idx + 1}: ${(s.narration || s.text || '').trim()}`).filter((l) => !l.endsWith(':')).join('\n');
  const combinedText = (scriptContent || scenesNarration || proj.title || cleanWs).slice(0, 2500);

  const channels = readChannels();
  const chan = proj.channel_id ? channels.find((c) => c.id === proj.channel_id) : null;
  const dnaSummary = chan && chan.dna ? (chan.dna.style_summary || '') : '';

  let kit = null;
  if (fs.existsSync(ANTIGRAVITY_BIN) && combinedText.trim()) {
    try {
      const prompt = `Buatkan metadata publikasi sosial media (Title yang menarik, Description singkat padat, dan Tags/Hashtags relevan) berdasarkan naskah proyek ini.
${dnaSummary ? 'Gaya Channel: ' + dnaSummary : ''}
Naskah/Proyek:
${combinedText}

Kembalikan HANYA JSON murni tanpa markdown:
{
  "title": "Judul video maksimal 70 karakter",
  "description": "Deskripsi 2-3 kalimat yang kuat",
  "tags": "#tag1 #tag2 #tag3 #tag4 #tag5"
}`;
      const { stdout } = await new Promise((resolve, reject) => {
        let p = null;
        const timer = setTimeout(() => {
          if (p) try { p.kill('SIGTERM'); } catch (_) {}
          reject(new Error('Timeout'));
        }, 25000);
        try {
          p = spawn(ANTIGRAVITY_BIN, ['--dangerously-skip-permissions', '-p', prompt], {
            env: getAntigravityEnv(false),
            cwd: HOME_DIR,
          });
          let out = '';
          p.stdout.on('data', (d) => { out += d.toString('utf8'); });
          p.on('close', (code) => {
            clearTimeout(timer);
            if (code === 0) resolve({ stdout: out });
            else reject(new Error(`Exit ${code}`));
          });
          p.on('error', (err) => {
            clearTimeout(timer);
            reject(err);
          });
        } catch (e) {
          clearTimeout(timer);
          reject(e);
        }
      });
      const cleanJson = stripAnsi(stdout).replace(/```json/g, '').replace(/```/g, '').trim();
      const match = cleanJson.match(/\{[\s\S]*\}/);
      if (match) {
        const parsed = JSON.parse(match[0]);
        if (parsed && (parsed.title || parsed.description)) {
          kit = {
            title: String(parsed.title || proj.title || cleanWs).trim(),
            description: String(parsed.description || '').trim(),
            tags: Array.isArray(parsed.tags) ? parsed.tags.join(' ') : String(parsed.tags || '').trim(),
          };
        }
      }
    } catch (_) {}
  }

  if (!kit) {
    kit = buildLocalPublishKitFallback(cleanWs, proj, scriptContent, chan);
  }

  kit.updated_at = Date.now();
  proj.publish_kit = kit;
  writeWorkspaceProject(cleanWs, proj);
  return kit;
}

// ── Workspace & Project Management ──────────────────────────────────────────

function readWorkspaceProject(wsName) {
  const cleanName = path.basename(String(wsName || ''));
  const wsPath = path.join(WORKSPACES_DIR, cleanName);
  if (!cleanName || !fs.existsSync(wsPath)) {
    throw new Error(`Workspace "${wsName}" tidak ditemukan`);
  }
  const projPath = path.join(wsPath, 'project.json');
  if (fs.existsSync(projPath)) {
    try {
      const parsed = JSON.parse(fs.readFileSync(projPath, 'utf8'));
      if (parsed && typeof parsed === 'object') {
        if (!parsed.version) parsed.version = 1;
        if (!parsed.name && parsed.title) parsed.name = parsed.title;
        if (!parsed.aspect_ratio) parsed.aspect_ratio = '9:16';
        if (!parsed.stage) parsed.stage = 'script';
        if (!Array.isArray(parsed.scenes)) parsed.scenes = [];
        if (!parsed.audio) parsed.audio = { vo_file: null, bgm_file: null, bgm_volume: 0.18 };
        return parsed;
      }
    } catch (_) {}
  }
  const defProj = {
    version: 1,
    name: cleanName,
    channel_id: null,
    aspect_ratio: '9:16',
    stage: 'script',
    scenes: [],
    audio: {
      vo_file: null,
      bgm_file: null,
      bgm_volume: 0.18,
    },
    output_file: null,
    updated_at: new Date().toISOString(),
  };
  try {
    fs.writeFileSync(projPath, JSON.stringify(defProj, null, 2));
  } catch (_) {}
  return defProj;
}

function writeWorkspaceProject(wsName, projectData) {
  const cleanName = path.basename(String(wsName || ''));
  const wsPath = path.join(WORKSPACES_DIR, cleanName);
  if (!cleanName || !fs.existsSync(wsPath)) {
    throw new Error(`Workspace "${wsName}" tidak ditemukan`);
  }
  const projPath = path.join(wsPath, 'project.json');
  let current = {};
  if (fs.existsSync(projPath)) {
    try { current = JSON.parse(fs.readFileSync(projPath, 'utf8')); } catch (_) {}
  }

  const merged = {
    version: 1,
    name: cleanName,
    channel_id: null,
    aspect_ratio: '9:16',
    stage: 'script',
    scenes: [],
    audio: { vo_file: null, bgm_file: null, bgm_volume: 0.18 },
    output_file: null,
    ...current,
    ...projectData,
    name: cleanName,
    updated_at: new Date().toISOString(),
  };
  fs.writeFileSync(projPath, JSON.stringify(merged, null, 2));
  return merged;
}

// ── Asset Library ───────────────────────────────────────────────────────────

function listLibraryAssets(filterCategory = null) {
  const result = [];
  const categories = filterCategory && LIBRARY_CATEGORIES.includes(filterCategory)
    ? [filterCategory]
    : LIBRARY_CATEGORIES;

  for (const cat of categories) {
    const dir = path.join(LIBRARY_DIR, cat);
    if (!fs.existsSync(dir)) continue;
    try {
      const files = fs.readdirSync(dir, { withFileTypes: true });
      for (const f of files) {
        if (!f.isFile()) continue;
        const full = path.join(dir, f.name);
        try {
          const st = fs.statSync(full);
          result.push({
            name: f.name,
            category: cat,
            size: st.size,
            mtime: st.mtimeMs,
            path: full,
            rel_path: `library/${cat}/${f.name}`,
          });
        } catch (_) {}
      }
    } catch (_) {}
  }
  result.sort((a, b) => b.mtime - a.mtime);
  return result;
}

function deleteLibraryAsset(category, fileName) {
  const safeCat = path.basename(String(category || ''));
  const safeName = path.basename(String(fileName || ''));
  if (!safeCat || !safeName || !LIBRARY_CATEGORIES.includes(safeCat)) {
    throw new Error('Kategori atau nama file library tidak valid');
  }
  const target = path.join(LIBRARY_DIR, safeCat, safeName);
  if (!fs.existsSync(target)) {
    throw new Error(`File "${safeName}" tidak ditemukan di library/${safeCat}`);
  }
  fs.unlinkSync(target);
  return { deleted: safeName, category: safeCat };
}

function copyLibraryAssetToWorkspace(category, fileName, wsName, targetDir = null) {
  const safeCat = path.basename(String(category || ''));
  const safeName = path.basename(String(fileName || ''));
  const cleanWs = path.basename(String(wsName || ''));

  if (!safeCat || !LIBRARY_CATEGORIES.includes(safeCat)) throw new Error('Kategori library tidak valid');
  if (!safeName) throw new Error('Nama file library tidak valid');
  if (!cleanWs) throw new Error('Workspace belum ditentukan');
  const wsPath = path.join(WORKSPACES_DIR, cleanWs);
  if (!fs.existsSync(wsPath)) throw new Error(`Workspace "${cleanWs}" tidak ditemukan`);

  const srcFile = path.join(LIBRARY_DIR, safeCat, safeName);
  if (!fs.existsSync(srcFile) || !fs.statSync(srcFile).isFile()) throw new Error(`File library "${safeName}" tidak ditemukan`);

  const sub = targetDir || (safeCat === 'audio' ? 'audio' : 'assets');
  const destDir = path.join(wsPath, sub);
  fs.mkdirSync(destDir, { recursive: true });

  let finalName = safeName;
  let destFile = path.join(destDir, finalName);
  if (fs.existsSync(destFile)) {
    const ext = path.extname(safeName);
    const base = path.basename(safeName, ext);
    let counter = 1;
    while (fs.existsSync(path.join(destDir, `${base}_${counter}${ext}`))) {
      counter++;
    }
    finalName = `${base}_${counter}${ext}`;
    destFile = path.join(destDir, finalName);
  }

  fs.copyFileSync(srcFile, destFile);
  return {
    copied: finalName,
    filename: finalName,
    name: finalName,
    original_name: safeName,
    source: srcFile,
    target: destFile,
    workspace: cleanWs,
    rel_path: `${sub}/${finalName}`,
  };
}

function listWorkspaces() {
  fs.mkdirSync(WORKSPACES_DIR, { recursive: true });
  const entries = fs.readdirSync(WORKSPACES_DIR, { withFileTypes: true });
  const list = [];

  for (const ent of entries) {
    if (!ent.isDirectory()) continue;
    const wsName = ent.name;
    const wsPath = path.join(WORKSPACES_DIR, wsName);
    const { count, latestMtime } = countFilesRecursive(wsPath);

    let dirMtime = 0;
    try {
      dirMtime = fs.statSync(wsPath).mtimeMs;
    } catch (_) {}

    let project = null;
    try {
      const projPath = path.join(wsPath, 'project.json');
      if (fs.existsSync(projPath)) {
        project = JSON.parse(fs.readFileSync(projPath, 'utf8'));
      }
    } catch (_) {}

    list.push({
      name: wsName,
      path: wsPath,
      files_count: count,
      updated_at: Math.max(dirMtime, latestMtime),
      project: project || {
        version: 1,
        name: wsName,
        channel_id: null,
        aspect_ratio: '9:16',
        stage: 'script',
        scenes: [],
        audio: { vo_file: null, bgm_file: null, bgm_volume: 0.18 },
        output_file: null,
        updated_at: new Date().toISOString(),
      },
    });
  }

  list.sort((a, b) => b.updated_at - a.updated_at);
  return list;
}

function createWorkspace(rawName, channelId = null) {
  const cleanName = String(rawName || '')
    .trim()
    .replace(/[^a-zA-Z0-9_-]/g, '_');

  if (!cleanName) {
    throw new Error('Nama workspace tidak valid');
  }

  const wsPath = path.join(WORKSPACES_DIR, cleanName);
  const subdirs = ['scripts', 'assets', 'audio', 'output'];

  fs.mkdirSync(wsPath, { recursive: true });
  for (const sub of subdirs) {
    fs.mkdirSync(path.join(wsPath, sub), { recursive: true });
  }

  let aspectRatio = '9:16';
  let matchedChannelId = null;
  if (channelId) {
    const channels = readChannels();
    const chan = channels.find((c) => c.id === channelId);
    if (chan) {
      matchedChannelId = chan.id;
      aspectRatio = chan.aspect_ratio || '9:16';
    }
  }

  const proj = {
    version: 1,
    name: cleanName,
    channel_id: matchedChannelId,
    aspect_ratio: aspectRatio,
    stage: 'script',
    scenes: [],
    audio: {
      vo_file: null,
      bgm_file: null,
      bgm_volume: 0.18,
    },
    output_file: null,
    updated_at: new Date().toISOString(),
  };
  try {
    fs.writeFileSync(path.join(wsPath, 'project.json'), JSON.stringify(proj, null, 2));
  } catch (_) {}

  return {
    name: cleanName,
    path: wsPath,
    subdirs,
    project: proj,
  };
}

function deleteWorkspace(rawName) {
  const cleanName = path.basename(String(rawName || '')).trim();
  if (!cleanName || cleanName === '.' || cleanName === '..') {
    throw new Error('Nama workspace tidak valid');
  }
  const wsPath = path.resolve(WORKSPACES_DIR, cleanName);
  const baseWsDir = path.resolve(WORKSPACES_DIR);
  if (wsPath !== baseWsDir && !wsPath.startsWith(baseWsDir + path.sep)) {
    throw new Error('Akses direktori di luar workspaces dilarang');
  }
  if (!fs.existsSync(wsPath) || !fs.statSync(wsPath).isDirectory()) {
    throw new Error(`Workspace "${cleanName}" tidak ditemukan`);
  }
  fs.rmSync(wsPath, { recursive: true, force: true });
  const st = readState();
  if (st.active_workspace === cleanName) {
    st.active_workspace = '';
    writeState(st);
  }
  return { deleted: cleanName };
}

function scanWorkspaceFiles(wsName) {
  const cleanName = path.basename(String(wsName || ''));
  const wsPath = path.join(WORKSPACES_DIR, cleanName);

  if (!cleanName || !fs.existsSync(wsPath) || !fs.statSync(wsPath).isDirectory()) {
    return null;
  }

  const result = [];

  function walk(currentDir) {
    let entries = [];
    try {
      entries = fs.readdirSync(currentDir, { withFileTypes: true });
    } catch (_) {
      return;
    }

    for (const ent of entries) {
      if (ent.name.startsWith('.')) continue;
      const full = path.join(currentDir, ent.name);
      const rel = path.relative(wsPath, full);

      if (ent.isDirectory()) {
        walk(full);
      } else if (ent.isFile()) {
        let size = 0;
        let mtime = 0;
        try {
          const st = fs.statSync(full);
          size = st.size;
          mtime = st.mtimeMs;
        } catch (_) {}

        let category = 'other';
        const lowerRel = rel.toLowerCase();
        if (lowerRel.startsWith('scripts/') || lowerRel.endsWith('.py') || lowerRel.endsWith('.sh') || lowerRel.endsWith('.json')) {
          category = 'scripts';
        } else if (lowerRel.startsWith('audio/') || lowerRel.endsWith('.m4a') || lowerRel.endsWith('.mp3') || lowerRel.endsWith('.wav') || lowerRel.endsWith('.aac')) {
          category = 'audio';
        } else if (lowerRel.startsWith('output/')) {
          category = 'output';
        } else if (lowerRel.startsWith('assets/') || lowerRel.endsWith('.mp4') || lowerRel.endsWith('.jpg') || lowerRel.endsWith('.jpeg') || lowerRel.endsWith('.png') || lowerRel.endsWith('.webp')) {
          category = 'assets';
        }

        result.push({
          name: ent.name,
          rel_path: rel,
          size,
          category,
          updated_at: mtime,
        });
      }
    }
  }

  walk(wsPath);
  result.sort((a, b) => b.updated_at - a.updated_at);
  return result;
}

// ── Auth Handlers ───────────────────────────────────────────────────────────

function handleAuthStart(res) {
  if (activeAuthProc) {
    try { activeAuthProc.kill('SIGTERM'); } catch (_) {}
    activeAuthProc = null;
  }

  lastAuthError = null;

  if (!fs.existsSync(ANTIGRAVITY_BIN)) {
    lastAuthError = `Binary Antigravity tidak ditemukan di ${ANTIGRAVITY_BIN}`;
    return sendJson(res, 500, { status: 'error', error: lastAuthError });
  }

  const ptyHelper = resolvePtyBridge();
  let proc;
  const authEnv = getAntigravityEnv(true);

  if (ptyHelper && fs.existsSync(ptyHelper)) {
    const pythonBin = resolvePythonBin();
    proc = spawn(pythonBin, [ptyHelper, ANTIGRAVITY_BIN, '-p', 'ping'], {
      env: authEnv,
      cwd: HOME_DIR,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
  } else {
    proc = spawn(ANTIGRAVITY_BIN, ['-p', 'ping'], {
      env: authEnv,
      cwd: HOME_DIR,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
  }

  activeAuthProc = proc;

  let responded = false;
  let combinedOutput = '';

  const urlRegex = /(https:\/\/accounts\.google\.com\/o\/oauth2\/auth[^\s'"]+)/;

  function checkForAuthUrl(chunk) {
    if (responded) return;
    combinedOutput += chunk.toString('utf8');
    const match = combinedOutput.match(urlRegex);
    if (match) {
      responded = true;
      clearTimeout(authTimeout);
      const authUrl = match[1].trim();
      sendJson(res, 200, { status: 'ok', auth_url: authUrl });
    }
  }

  proc.stdout.on('data', checkForAuthUrl);
  proc.stderr.on('data', checkForAuthUrl);

  const authTimeout = setTimeout(() => {
    if (!responded) {
      responded = true;
      if (activeAuthProc === proc) {
        try { proc.kill('SIGTERM'); } catch (_) {}
        activeAuthProc = null;
      }
      lastAuthError = 'Timeout 15 detik: URL login OAuth tidak ditemukan dari Antigravity CLI.';
      sendJson(res, 500, { status: 'error', error: lastAuthError });
    }
  }, 15000);

  proc.on('close', (code) => {
    clearTimeout(authTimeout);
    if (activeAuthProc === proc) {
      activeAuthProc = null;
    }

    if (code === 0) {
      try {
        fs.writeFileSync(AUTH_MARKER_FILE, String(Date.now()));
      } catch (_) {}
      lastAuthError = null;

      if (!responded) {
        responded = true;
        sendJson(res, 200, { status: 'already_authenticated', authenticated: true, auth_url: '' });
      }
    } else {
      const cleanErr = cleanStderr(combinedOutput) || `Antigravity CLI keluar dengan kode ${code}`;
      lastAuthError = cleanErr;

      if (!responded) {
        responded = true;
        sendJson(res, 400, { status: 'error', error: cleanErr });
      }
    }
  });

  proc.on('error', (err) => {
    clearTimeout(authTimeout);
    if (activeAuthProc === proc) {
      activeAuthProc = null;
    }
    lastAuthError = err.message;
    if (!responded) {
      responded = true;
      sendJson(res, 500, { status: 'error', error: `Gagal menjalankan Antigravity PTY: ${err.message}` });
    }
  });
}

async function handleAuthCode(req, res) {
  let body;
  try {
    body = await readJsonBody(req);
  } catch (e) {
    return sendJson(res, 400, { status: 'error', error: 'JSON tidak valid' });
  }

  const code = String(body.code || '').trim();
  if (!code) {
    return sendJson(res, 400, { status: 'error', error: 'Kode autentikasi tidak boleh kosong' });
  }

  if (
    !activeAuthProc ||
    !activeAuthProc.stdin ||
    activeAuthProc.stdin.destroyed ||
    !activeAuthProc.stdin.writable
  ) {
    return sendJson(res, 400, {
      status: 'error',
      error: 'Tidak ada proses login yang sedang aktif. Silakan tekan tombol Login Akun Google terlebih dahulu.',
    });
  }

  const proc = activeAuthProc;
  let procOut = '';
  let procErr = '';

  const onDataOut = (c) => { procOut += c.toString('utf8'); };
  const onDataErr = (c) => { procErr += c.toString('utf8'); };
  proc.stdout.on('data', onDataOut);
  proc.stderr.on('data', onDataErr);

  try {
    proc.stdin.write(code + '\n');
  } catch (err) {
    return sendJson(res, 500, {
      status: 'error',
      error: `Gagal menulis kode ke stdin Antigravity: ${err.message}`,
    });
  }

  let finished = false;
  const timeoutTimer = setTimeout(() => {
    if (!finished) {
      finished = true;
      if (activeAuthProc === proc) {
        try { proc.kill('SIGTERM'); } catch (_) {}
        activeAuthProc = null;
      }
      lastAuthError = 'Konfirmasi kode autentikasi timed out (25 detik).';
      sendJson(res, 400, { status: 'error', error: lastAuthError });
    }
  }, 25000);

  const cleanupListeners = () => {
    clearTimeout(timeoutTimer);
    try {
      proc.stdout.removeListener('data', onDataOut);
      proc.stderr.removeListener('data', onDataErr);
    } catch (_) {}
  };

  proc.once('close', (exitCode) => {
    if (finished) return;
    finished = true;
    cleanupListeners();

    if (exitCode === 0) {
      try {
        fs.writeFileSync(AUTH_MARKER_FILE, String(Date.now()));
      } catch (_) {}
      lastAuthError = null;
      return sendJson(res, 200, { status: 'ok', authenticated: true });
    } else {
      const cleanErr = cleanStderr(procErr) || stripAnsi(procOut).trim() || `Autentikasi gagal dengan exit code ${exitCode}`;
      lastAuthError = cleanErr;
      return sendJson(res, 400, { status: 'error', error: cleanErr });
    }
  });

  proc.once('error', (err) => {
    if (finished) return;
    finished = true;
    cleanupListeners();
    lastAuthError = err.message;
    return sendJson(res, 400, { status: 'error', error: err.message });
  });
}

// ── Audio Studio (TTS & Mastering) ─────────────────────────────────────────

function ensureEdgeTts() {
  return new Promise((resolve) => {
    const pythonBin = resolvePythonBin();
    const checkProc = spawn(pythonBin, ['-c', 'import edge_tts'], {
      env: getAntigravityEnv(false),
    });
    checkProc.on('close', (code) => {
      if (code === 0) return resolve(true);
      console.log('[studio-bridge] edge_tts tidak ditemukan, mencoba auto-install via pip...');
      const pipProc = spawn(pythonBin, ['-m', 'pip', 'install', '--no-cache-dir', '--break-system-packages', 'edge-tts'], {
        env: getAntigravityEnv(false),
      });
      pipProc.on('close', (pipCode) => {
        if (pipCode === 0) return resolve(true);
        const pipProc2 = spawn(pythonBin, ['-m', 'pip', 'install', '--no-cache-dir', 'edge-tts'], {
          env: getAntigravityEnv(false),
        });
        pipProc2.on('close', (pipCode2) => resolve(pipCode2 === 0));
        pipProc2.on('error', () => resolve(false));
      });
      pipProc.on('error', () => resolve(false));
    });
    checkProc.on('error', () => resolve(false));
  });
}

async function handleTts(req, res) {
  let body;
  try {
    body = await readJsonBody(req);
  } catch (e) {
    return sendJson(res, 400, { status: 'error', error: 'JSON tidak valid' });
  }

  const workspace = path.basename(String(body.workspace || '').trim());
  const text = String(body.text || '').trim();

  if (!workspace) {
    return sendJson(res, 400, { status: 'error', error: 'Workspace belum dipilih' });
  }
  if (!text) {
    return sendJson(res, 400, { status: 'error', error: 'Teks naskah kosong' });
  }

  const wsPath = path.join(WORKSPACES_DIR, workspace);
  if (!fs.existsSync(wsPath)) {
    return sendJson(res, 404, { status: 'error', error: `Workspace "${workspace}" tidak ditemukan` });
  }

  const audioDir = path.join(wsPath, 'audio');
  fs.mkdirSync(audioDir, { recursive: true });

  // Khusus Multilingual Neural - Dilarang keras pakai Ardi atau Gadis
  const allowedVoices = {
    'brian': 'en-US-BrianMultilingualNeural',
    'en-us-brianmultilingualneural': 'en-US-BrianMultilingualNeural',
    'andrew': 'en-US-AndrewMultilingualNeural',
    'en-us-andrewmultilingualneural': 'en-US-AndrewMultilingualNeural',
    'remy': 'fr-FR-RemyMultilingualNeural',
    'fr-fr-remymultilingualneural': 'fr-FR-RemyMultilingualNeural',
    'emma': 'en-US-EmmaMultilingualNeural',
    'en-us-emmamultilingualneural': 'en-US-EmmaMultilingualNeural',
    'ava': 'en-US-AvaMultilingualNeural',
    'en-us-avamultilingualneural': 'en-US-AvaMultilingualNeural',
  };

  const voiceKey = String(body.voice || '').trim().toLowerCase();
  const voice = allowedVoices[voiceKey] || 'en-US-BrianMultilingualNeural';

  const rate = String(body.rate || '+0%').trim();
  const pitch = String(body.pitch || '+0Hz').trim();

  await ensureEdgeTts();

  const tmpDir = path.join(CONTENT_STUDIO_DIR, 'tmp');
  fs.mkdirSync(tmpDir, { recursive: true });
  const tmpTxt = path.join(tmpDir, `tmp_tts_${Date.now()}_${crypto.randomBytes(3).toString('hex')}.txt`);
  const outName = `tts_${Date.now()}.mp3`;
  const outMp3 = path.join(audioDir, outName);

  try {
    fs.writeFileSync(tmpTxt, text, 'utf8');
  } catch (err) {
    return sendJson(res, 500, { status: 'error', error: `Gagal membuat file sementara: ${err.message}` });
  }

  const pythonBin = resolvePythonBin();
  // WAJIB gunakan --rate=... dan --pitch=... agar nilai negatif tidak tertukar sebagai argumen CLI
  const args = [
    '-m',
    'edge_tts',
    '--voice', voice,
    `--rate=${rate}`,
    `--pitch=${pitch}`,
    '--file', tmpTxt,
    '--write-media', outMp3,
  ];

  const proc = spawn(pythonBin, args, {
    env: getAntigravityEnv(false),
    cwd: CONTENT_STUDIO_DIR,
  });

  let procErr = '';
  proc.stderr.on('data', (d) => { procErr += d.toString('utf8'); });

  proc.on('close', (code) => {
    try { fs.unlinkSync(tmpTxt); } catch (_) {}
    if (code === 0 && fs.existsSync(outMp3) && fs.statSync(outMp3).size > 0) {
      const st = fs.statSync(outMp3);
      return sendJson(res, 200, {
        status: 'ok',
        file: {
          name: outName,
          rel_path: `audio/${outName}`,
          size: st.size,
          path: outMp3,
        },
      });
    } else {
      const errClean = cleanStderr(procErr) || `edge-tts selesai dengan kode ${code}`;
      return sendJson(res, 500, { status: 'error', error: `Gagal membuat TTS: ${errClean}` });
    }
  });

  proc.on('error', (err) => {
    try { fs.unlinkSync(tmpTxt); } catch (_) {}
    return sendJson(res, 500, { status: 'error', error: `Gagal menjalankan edge-tts: ${err.message}` });
  });
}

async function handleAudioMaster(req, res) {
  let body;
  try {
    body = await readJsonBody(req);
  } catch (e) {
    return sendJson(res, 400, { status: 'error', error: 'JSON tidak valid' });
  }

  const workspace = path.basename(String(body.workspace || '').trim());
  const relOrFileName = String(body.file || '').trim();

  if (!workspace) {
    return sendJson(res, 400, { status: 'error', error: 'Workspace belum dipilih' });
  }
  if (!relOrFileName) {
    return sendJson(res, 400, { status: 'error', error: 'Parameter file audio kosong' });
  }

  const wsPath = path.join(WORKSPACES_DIR, workspace);
  if (!fs.existsSync(wsPath)) {
    return sendJson(res, 404, { status: 'error', error: `Workspace "${workspace}" tidak ditemukan` });
  }

  let inputPath = path.resolve(wsPath, relOrFileName);
  if (!fs.existsSync(inputPath)) {
    inputPath = path.resolve(wsPath, 'audio', path.basename(relOrFileName));
  }
  if (!fs.existsSync(inputPath) || !fs.statSync(inputPath).isFile()) {
    return sendJson(res, 404, { status: 'error', error: `File audio input tidak ditemukan: ${relOrFileName}` });
  }

  const ext = path.extname(inputPath);
  const baseName = path.basename(inputPath, ext);
  // Hindari nama ganda _master_master.m4a
  const cleanBase = baseName.replace(/(_master)+$/, '');
  const outName = `${cleanBase}_master.m4a`;
  const outputPath = path.join(path.dirname(inputPath), outName);

  const ffmpegBin = path.join(PREFIX_DIR, 'bin', 'ffmpeg');
  const targetFfmpeg = fs.existsSync(ffmpegBin) ? ffmpegBin : 'ffmpeg';

  const preset = String(body.preset || 'natural').trim().toLowerCase();
  let filterChain = 'highpass=f=80,afftdn=nf=-25,acompressor=threshold=-18dB:ratio=2.5:attack=5:release=50,loudnorm=I=-14:TP=-1.5:LRA=11';
  if (preset === 'deep_doc') {
    filterChain = 'highpass=f=70,afftdn=nf=-25,equalizer=f=120:t=q:w=1:g=3,equalizer=f=3000:t=q:w=1:g=1.5,acompressor=threshold=-16dB:ratio=4:attack=5:release=60,loudnorm=I=-14:TP=-1.5:LRA=11';
  } else if (preset === 'crisp_reels') {
    filterChain = 'highpass=f=100,afftdn=nf=-25,equalizer=f=4000:t=q:w=1:g=3,acompressor=threshold=-16dB:ratio=3.5:attack=2:release=40,loudnorm=I=-13:TP=-1.0:LRA=9';
  }

  const args = [
    '-y',
    '-i', inputPath,
    '-vn',
    '-af', filterChain,
    '-c:a', 'aac',
    '-b:a', '192k',
    outputPath,
  ];

  const proc = spawn(targetFfmpeg, args, {
    env: getAntigravityEnv(false),
    cwd: wsPath,
  });

  let procErr = '';
  proc.stderr.on('data', (d) => { procErr += d.toString('utf8'); });

  proc.on('close', (code) => {
    if (code === 0 && fs.existsSync(outputPath) && fs.statSync(outputPath).size > 0) {
      const st = fs.statSync(outputPath);
      const relOut = path.relative(wsPath, outputPath);
      return sendJson(res, 200, {
        status: 'ok',
        file: {
          name: outName,
          rel_path: relOut,
          size: st.size,
          path: outputPath,
        },
      });
    } else {
      const errClean = cleanStderr(procErr) || `FFmpeg selesai dengan kode ${code}`;
      return sendJson(res, 500, { status: 'error', error: `Gagal mastering audio: ${errClean}` });
    }
  });

  proc.on('error', (err) => {
    return sendJson(res, 500, { status: 'error', error: `Gagal menjalankan FFmpeg: ${err.message}` });
  });
}

// ── FFmpeg Auto-Render Pipeline ─────────────────────────────────────────────

function formatSrtTime(sec) {
  const totalMs = Math.max(0, Math.round(sec * 1000));
  const hrs = Math.floor(totalMs / 3600000);
  const mins = Math.floor((totalMs % 3600000) / 60000);
  const secs = Math.floor((totalMs % 60000) / 1000);
  const ms = totalMs % 1000;
  return `${String(hrs).padStart(2, '0')}:${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')},${String(ms).padStart(3, '0')}`;
}

async function executeRenderPipeline(cleanWs, proj, options, job) {
  const wsPath = path.join(WORKSPACES_DIR, cleanWs);
  const ffmpegBin = fs.existsSync(path.join(PREFIX_DIR, 'bin', 'ffmpeg')) ? path.join(PREFIX_DIR, 'bin', 'ffmpeg') : 'ffmpeg';
  const tmpDir = path.join(wsPath, '.tmp_render');

  try {
    fs.rmSync(tmpDir, { recursive: true, force: true });
    fs.mkdirSync(tmpDir, { recursive: true });

    const is169 = (proj.aspect_ratio === '16:9');
    const is1080p = (options && options.resolution === '1080p');
    const targetW = is169 ? (is1080p ? 1920 : 1280) : (is1080p ? 1080 : 720);
    const targetH = is169 ? (is1080p ? 1080 : 720) : (is1080p ? 1920 : 1280);

    const scenes = Array.isArray(proj.scenes) ? proj.scenes : [];
    if (scenes.length === 0) {
      throw new Error('Tambahkan minimal 1 adegan sebelum render');
    }

    job.step = 'Mempersiapkan adegan...';
    job.progress = 10;

    const segmentFiles = [];

    // 1. Process each scene into uniform 30fps MP4 segment
    for (let i = 0; i < scenes.length; i++) {
      const scene = scenes[i];
      const duration = Math.max(0.5, parseFloat(scene.duration) || 4.0);
      const segPath = path.join(tmpDir, `seg_${i}.mp4`);
      segmentFiles.push(segPath);

      job.step = `Memproses adegan ${i + 1} dari ${scenes.length}...`;
      job.progress = Math.round(10 + ((i + 0.5) / scenes.length) * 50);

      let visualPath = null;
      if (scene.visual && typeof scene.visual === 'string' && scene.visual.trim() && scene.visual !== 'Empty Visual') {
        const candidate1 = path.join(wsPath, scene.visual.trim());
        const candidate2 = path.join(wsPath, 'assets', scene.visual.trim());
        const candidate3 = path.join(LIBRARY_DIR, scene.visual.trim());
        if (fs.existsSync(candidate1) && fs.statSync(candidate1).isFile()) {
          visualPath = candidate1;
        } else if (fs.existsSync(candidate2) && fs.statSync(candidate2).isFile()) {
          visualPath = candidate2;
        } else if (fs.existsSync(candidate3) && fs.statSync(candidate3).isFile()) {
          visualPath = candidate3;
        }
      }

      if (visualPath) {
        const ext = path.extname(visualPath).toLowerCase();
        const isImage = ['.jpg', '.jpeg', '.png', '.webp', '.bmp'].includes(ext);

        if (isImage) {
          if (options && options.motion) {
            const numFrames = Math.max(1, Math.round(duration * 30));
            const vf = `zoompan=z='min(zoom+0.0015,1.15)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=${numFrames}:s=${targetW}x${targetH}:fps=30,setsar=1`;
            try {
              await execFileAsync(ffmpegBin, [
                '-y',
                '-loop', '1',
                '-i', visualPath,
                '-vf', vf,
                '-t', String(duration),
                '-pix_fmt', 'yuv420p',
                '-c:v', 'libx264',
                '-preset', 'ultrafast',
                '-crf', '23',
                '-an',
                segPath,
              ], { cwd: wsPath });
            } catch (_) {
              // Fallback to static scale/crop if zoompan fails
              const fallbackVf = `scale=${targetW}:${targetH}:force_original_aspect_ratio=increase,crop=${targetW}:${targetH},setsar=1`;
              await execFileAsync(ffmpegBin, [
                '-y',
                '-loop', '1',
                '-i', visualPath,
                '-t', String(duration),
                '-vf', fallbackVf,
                '-r', '30',
                '-pix_fmt', 'yuv420p',
                '-c:v', 'libx264',
                '-preset', 'ultrafast',
                '-crf', '23',
                '-an',
                segPath,
              ], { cwd: wsPath });
            }
          } else {
            const vf = `scale=${targetW}:${targetH}:force_original_aspect_ratio=increase,crop=${targetW}:${targetH},setsar=1`;
            await execFileAsync(ffmpegBin, [
              '-y',
              '-loop', '1',
              '-i', visualPath,
              '-t', String(duration),
              '-vf', vf,
              '-r', '30',
              '-pix_fmt', 'yuv420p',
              '-c:v', 'libx264',
              '-preset', 'ultrafast',
              '-crf', '23',
              '-an',
              segPath,
            ], { cwd: wsPath });
          }
        } else {
          // Video input
          const vf = `scale=${targetW}:${targetH}:force_original_aspect_ratio=increase,crop=${targetW}:${targetH},setsar=1`;
          await execFileAsync(ffmpegBin, [
            '-y',
            '-stream_loop', '-1',
            '-i', visualPath,
            '-t', String(duration),
            '-vf', vf,
            '-r', '30',
            '-pix_fmt', 'yuv420p',
            '-c:v', 'libx264',
            '-preset', 'ultrafast',
            '-crf', '23',
            '-an',
            segPath,
          ], { cwd: wsPath });
        }
      } else {
        // Dark canvas for empty visual
        await execFileAsync(ffmpegBin, [
          '-y',
          '-f', 'lavfi',
          '-i', `color=c=0x101014:s=${targetW}x${targetH}:d=${duration}:r=30`,
          '-pix_fmt', 'yuv420p',
          '-c:v', 'libx264',
          '-preset', 'ultrafast',
          '-crf', '23',
          '-an',
          segPath,
        ], { cwd: wsPath });
      }
    }

    // 2. Concat segments
    job.step = 'Menggabungkan segmen video...';
    job.progress = 65;

    const concatTxtPath = path.join(tmpDir, 'concat.txt');
    const concatLines = segmentFiles.map((f) => `file '${f.replace(/'/g, "'\\''")}'`).join('\n');
    fs.writeFileSync(concatTxtPath, concatLines, 'utf8');

    const mergedVideoPath = path.join(tmpDir, 'merged.mp4');
    try {
      await execFileAsync(ffmpegBin, [
        '-y',
        '-f', 'concat',
        '-safe', '0',
        '-i', concatTxtPath,
        '-c', 'copy',
        mergedVideoPath,
      ], { cwd: wsPath });
    } catch (_) {
      await execFileAsync(ffmpegBin, [
        '-y',
        '-f', 'concat',
        '-safe', '0',
        '-i', concatTxtPath,
        '-c:v', 'libx264',
        '-preset', 'ultrafast',
        '-crf', '23',
        '-an',
        mergedVideoPath,
      ], { cwd: wsPath });
    }

    let currentVideoPath = mergedVideoPath;

    // 3. Generate subtitles (.srt) & burn-in if requested
    job.step = 'Membuat naskah subtitle (.srt)...';
    job.progress = 75;

    const rendersDir = path.join(wsPath, 'renders');
    fs.mkdirSync(rendersDir, { recursive: true });
    const srtPath = path.join(rendersDir, 'subtitles.srt');

    let srtContent = '';
    let cumSec = 0;
    scenes.forEach((sc, idx) => {
      const dur = Math.max(0.5, parseFloat(sc.duration) || 4.0);
      const startStr = formatSrtTime(cumSec);
      const endStr = formatSrtTime(cumSec + dur);
      cumSec += dur;
      const narration = (sc.narration || sc.text || '').trim();
      if (narration) {
        srtContent += `${idx + 1}\n${startStr} --> ${endStr}\n${narration}\n\n`;
      }
    });
    fs.writeFileSync(srtPath, srtContent, 'utf8');

    if (options && options.burn_subtitles && srtContent.trim()) {
      job.step = 'Burn-in subtitle ke video...';
      job.progress = 80;
      const subbedVideoPath = path.join(tmpDir, 'subbed.mp4');
      const escapedSrt = srtPath.replace(/\\/g, '/').replace(/:/g, '\\:');
      const defaultSubPreset = is169 ? 'bottom_169' : 'safe_916';
      const subPreset = String((options && options.subtitle_preset) || defaultSubPreset).trim().toLowerCase();
      let subStyle = 'FontSize=18,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,Outline=2,Alignment=2,MarginV=45';
      if (subPreset === 'safe_916') {
        subStyle = 'FontSize=18,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,Outline=2,Alignment=2,MarginV=180';
      } else if (subPreset === 'center_bold') {
        subStyle = 'FontSize=21,Bold=1,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,Outline=2,Alignment=5,MarginV=0';
      }
      const subVf = `subtitles='${escapedSrt}':force_style='${subStyle}'`;
      try {
        await execFileAsync(ffmpegBin, [
          '-y',
          '-i', currentVideoPath,
          '-vf', subVf,
          '-c:v', 'libx264',
          '-preset', 'ultrafast',
          '-crf', '23',
          '-an',
          subbedVideoPath,
        ], { cwd: wsPath });
        if (fs.existsSync(subbedVideoPath) && fs.statSync(subbedVideoPath).size > 0) {
          currentVideoPath = subbedVideoPath;
        }
      } catch (subErr) {
        job.step = 'Burn-in subtitle dilewati, melanjutkan mixing audio...';
      }
    }

    // 4. Audio Mixing (VO + BGM)
    job.step = 'Mixing audio (VO & BGM)...';
    job.progress = 88;

    let voPath = null;
    if (proj.audio && proj.audio.vo_file) {
      const c1 = path.join(wsPath, 'audio', proj.audio.vo_file);
      const c2 = path.join(wsPath, proj.audio.vo_file);
      if (fs.existsSync(c1) && fs.statSync(c1).isFile()) voPath = c1;
      else if (fs.existsSync(c2) && fs.statSync(c2).isFile()) voPath = c2;
    }

    let bgmPath = null;
    if (proj.audio && proj.audio.bgm_file) {
      const c1 = path.join(wsPath, 'audio', proj.audio.bgm_file);
      const c2 = path.join(wsPath, proj.audio.bgm_file);
      const c3 = path.join(LIBRARY_DIR, 'audio', proj.audio.bgm_file);
      if (fs.existsSync(c1) && fs.statSync(c1).isFile()) bgmPath = c1;
      else if (fs.existsSync(c2) && fs.statSync(c2).isFile()) bgmPath = c2;
      else if (fs.existsSync(c3) && fs.statSync(c3).isFile()) bgmPath = c3;
    }

    const bgmVol = (proj.audio && proj.audio.bgm_volume != null) ? Number(proj.audio.bgm_volume) : 0.18;
    const autoDuck = !!(proj.audio && proj.audio.auto_duck);

    const timestamp = Date.now();
    const finalName = `final_${timestamp}.mp4`;
    const finalOutPath = path.join(rendersDir, finalName);

    if (voPath && bgmPath) {
      const filterComplex = autoDuck
        ? `[2:a]volume=${bgmVol}[bgm];[bgm][1:a]sidechaincompress=threshold=0.1:ratio=5:attack=20:release=300[ducked];[1:a][ducked]amix=inputs=2:duration=first:dropout_transition=2[aout]`
        : `[2:a]volume=${bgmVol}[bgm];[1:a][bgm]amix=inputs=2:duration=first:dropout_transition=2[aout]`;

      try {
        await execFileAsync(ffmpegBin, [
          '-y',
          '-i', currentVideoPath,
          '-i', voPath,
          '-stream_loop', '-1',
          '-i', bgmPath,
          '-filter_complex', filterComplex,
          '-map', '0:v',
          '-map', '[aout]',
          '-c:v', 'copy',
          '-c:a', 'aac',
          '-b:a', '192k',
          '-shortest',
          finalOutPath,
        ], { cwd: wsPath });
      } catch (_) {
        const fallbackFilter = `[2:a]volume=${bgmVol}[bgm];[1:a][bgm]amix=inputs=2:duration=first:dropout_transition=2[aout]`;
        await execFileAsync(ffmpegBin, [
          '-y',
          '-i', currentVideoPath,
          '-i', voPath,
          '-stream_loop', '-1',
          '-i', bgmPath,
          '-filter_complex', fallbackFilter,
          '-map', '0:v',
          '-map', '[aout]',
          '-c:v', 'copy',
          '-c:a', 'aac',
          '-b:a', '192k',
          '-shortest',
          finalOutPath,
        ], { cwd: wsPath });
      }
    } else if (voPath) {
      await execFileAsync(ffmpegBin, [
        '-y',
        '-i', currentVideoPath,
        '-i', voPath,
        '-map', '0:v',
        '-map', '1:a',
        '-c:v', 'copy',
        '-c:a', 'aac',
        '-b:a', '192k',
        '-shortest',
        finalOutPath,
      ], { cwd: wsPath });
    } else if (bgmPath) {
      await execFileAsync(ffmpegBin, [
        '-y',
        '-i', currentVideoPath,
        '-stream_loop', '-1',
        '-i', bgmPath,
        '-filter:a', `volume=${bgmVol}`,
        '-map', '0:v',
        '-map', '1:a',
        '-c:v', 'copy',
        '-c:a', 'aac',
        '-b:a', '192k',
        '-shortest',
        finalOutPath,
      ], { cwd: wsPath });
    } else {
      await execFileAsync(ffmpegBin, [
        '-y',
        '-i', currentVideoPath,
        '-f', 'lavfi',
        '-i', 'anullsrc=channel_layout=stereo:sample_rate=44100',
        '-map', '0:v',
        '-map', '1:a',
        '-c:v', 'copy',
        '-c:a', 'aac',
        '-b:a', '192k',
        '-shortest',
        finalOutPath,
      ], { cwd: wsPath });
    }

    if (!fs.existsSync(finalOutPath) || fs.statSync(finalOutPath).size === 0) {
      throw new Error('File output video akhir tidak terbuat');
    }

    const st = fs.statSync(finalOutPath);

    proj.output_file = `renders/${finalName}`;
    proj.stage = 'render';
    writeWorkspaceProject(cleanWs, proj);

    fs.rmSync(tmpDir, { recursive: true, force: true });

    job.status = 'done';
    job.progress = 100;
    job.step = 'Render selesai';
    job.output_file = `renders/${finalName}`;
    job.size = st.size;
    job.error = null;
  } catch (err) {
    fs.rmSync(tmpDir, { recursive: true, force: true });
    job.status = 'error';
    job.error = err.message || 'Render gagal';
    job.step = 'Gagal: ' + (err.message || 'Render error');
  }
}

// ── Chat Stream Handler (Connected to ContentStudio Kitchen) ────────────────

async function handleChat(req, res) {
  let body;
  try {
    body = await readJsonBody(req);
  } catch (e) {
    return sendJson(res, 400, { status: 'error', error: 'JSON tidak valid' });
  }

  const userMsg = String(body.message || '').trim();
  if (!userMsg) {
    return sendJson(res, 400, { status: 'error', error: 'Pesan kosong' });
  }

  if (!fs.existsSync(ANTIGRAVITY_BIN)) {
    return sendJson(res, 500, { status: 'error', error: `Binary Antigravity tidak ditemukan di ${ANTIGRAVITY_BIN}` });
  }

  const reqWs = body.workspace ? path.basename(String(body.workspace).trim()) : '';
  let session = body.session_id ? loadSession(body.session_id) : null;
  if (!session) {
    session = ensureActiveSession(reqWs);
  } else if (reqWs && session.workspace !== reqWs) {
    session.workspace = reqWs;
  }

  const st = readState();
  st.active_session_id = session.id;
  if (reqWs) {
    st.active_workspace = reqWs;
  }
  writeState(st);

  session.messages.push({
    role: 'user',
    content: userMsg,
    timestamp: Date.now(),
  });
  if (session.title === 'Sesi Baru' && session.messages.length === 1) {
    session.title = userMsg.slice(0, 45).trim() || 'Sesi Baru';
  }
  session.updated_at = Date.now();
  saveSession(session);

  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive',
  });

  function emit(obj) {
    if (!res.writableEnded) {
      res.write(`data: ${JSON.stringify(obj)}\n\n`);
    }
  }

  emit({ type: 'status', text: 'Menjalankan Antigravity...' });

  // Susun instruksi dengan konteks dapur produksi, channel, dan workspace project
  const activeWorkspace = session.workspace || st.active_workspace || '';
  let wsContextPrefix = '';
  let wsPath = '';

  if (activeWorkspace) {
    wsPath = path.join(WORKSPACES_DIR, activeWorkspace);
    if (fs.existsSync(wsPath)) {
      let chanInfo = '';
      let projInfo = '';
      try {
        const proj = readWorkspaceProject(activeWorkspace);
        projInfo = `[Project File: "${wsPath}/project.json" | Stage: "${proj.stage || 'script'}" | Aspect Ratio: "${proj.aspect_ratio || '9:16'}" | Scenes: ${Array.isArray(proj.scenes) ? proj.scenes.length : 0}]\n` +
          `[Scene Schema: [{"id": "scene_1", "title": "Hook", "beat_role": "Hook", "duration": 2.5, "narration": "...", "prompt": "...", "visual": "assets/...", "transition": "none"}]]\n`;
        if (proj.channel_id) {
          const channels = readChannels();
          const matchedChan = channels.find((c) => c.id === proj.channel_id);
          if (matchedChan) {
            const dnaObj = matchedChan.dna || {};
            chanInfo = `[Channel DNA: Name="${matchedChan.name}", Aspect Ratio="${matchedChan.aspect_ratio}", Avg Scene Duration=${dnaObj.avg_scene_duration || 3.5}s, Learned Traits=${JSON.stringify(dnaObj.learned_traits || [])}]\n`;
          }
        }
      } catch (_) {}

      let rawPrefix = `[Konteks Workspace: "${activeWorkspace}" | Path: "${wsPath}"]\n` +
        chanInfo +
        projInfo +
        `Anda beroperasi di workspace ini. Anda dapat langsung memodifikasi file "${wsPath}/project.json" atau "${wsPath}/scripts/main.txt" saat diminta membuat atau mengubah naskah dan susunan adegan. Simpan file script ke "${wsPath}/scripts", aset ke "${wsPath}/assets", audio ke "${wsPath}/audio", dan render ke "${wsPath}/renders".\n\n`;
      if (rawPrefix.length > 1800) {
        rawPrefix = rawPrefix.slice(0, 1790) + '...\n\n';
      }
      wsContextPrefix = rawPrefix;
    }
  }

  const previousTurns = session.messages.slice(0, -1);
  const recentHistory = previousTurns.slice(-6);
  let promptToSend = userMsg;
  if (recentHistory.length > 0) {
    const historyLines = [];
    let historyChars = 0;
    for (let i = recentHistory.length - 1; i >= 0; i--) {
      const m = recentHistory[i];
      const roleStr = m.role === 'user' ? 'USER' : 'ASSISTANT';
      const cleanContent = String(m.content || '').slice(0, 2000);
      const line = `${roleStr}: ${cleanContent}`;
      if (historyChars + line.length > 5000 && historyLines.length > 0) {
        break;
      }
      historyLines.unshift(line);
      historyChars += line.length;
    }
    if (historyLines.length > 0) {
      promptToSend = `${wsContextPrefix}Konteks percakapan sebelumnya:\n${historyLines.join('\n')}\n\nInstruksi terbaru USER:\n${userMsg}`;
    } else if (wsContextPrefix) {
      promptToSend = `${wsContextPrefix}${userMsg}`;
    }
  } else if (wsContextPrefix) {
    promptToSend = `${wsContextPrefix}${userMsg}`;
  }

  if (activeChatProc) {
    try { activeChatProc.kill('SIGTERM'); } catch (_) {}
    activeChatProc = null;
  }

  // Parameter antigravity: cwd ke $HOME/ContentStudio agar membaca AGENTS.md, .agents/skills, dan engine/
  const workingDir = fs.existsSync(CONTENT_STUDIO_DIR) ? CONTENT_STUDIO_DIR : HOME_DIR;
  const cliArgs = [];
  cliArgs.push('--dangerously-skip-permissions');

  if (wsPath && fs.existsSync(wsPath)) {
    cliArgs.push('--add-dir', wsPath);
  }
  cliArgs.push('-p', promptToSend);

  const proc = spawn(ANTIGRAVITY_BIN, cliArgs, {
    env: getAntigravityEnv(false),
    cwd: workingDir,
  });
  activeChatProc = proc;

  let fullOutput = '';
  let errOutput = '';

  proc.stdout.on('data', (chunk) => {
    const rawText = chunk.toString('utf8');
    const cleanText = stripAnsi(rawText);
    fullOutput += cleanText;
    if (cleanText) {
      emit({ type: 'delta', text: cleanText });
    }
  });

  proc.stderr.on('data', (chunk) => {
    const text = chunk.toString('utf8');
    errOutput += text;
  });

  function killProcessSafely(p) {
    if (!p) return;
    try {
      p.kill('SIGTERM');
      const timer = setTimeout(() => {
        try {
          if (!p.killed) p.kill('SIGKILL');
        } catch (_) {}
      }, 1000);
      p.once('exit', () => clearTimeout(timer));
    } catch (_) {}
  }

  const onClientClose = () => {
    if (activeChatProc === proc) {
      killProcessSafely(proc);
      activeChatProc = null;
    }
  };
  req.once('close', onClientClose);
  res.once('close', onClientClose);

  proc.on('exit', (code) => {
    if (activeChatProc === proc) activeChatProc = null;

    const cleanStdout = stripAnsi(fullOutput).trim();
    const cleanErr = cleanStderr(errOutput);

    // Cek jika proses meminta autentikasi (sesi berakhir)
    if (
      cleanStdout.includes('Authentication required') ||
      cleanErr.includes('Authentication required')
    ) {
      try { fs.unlinkSync(AUTH_MARKER_FILE); } catch (_) {}
      const authErrMsg = 'Sesi login berakhir. Silakan login ulang.';
      emit({ type: 'error', text: authErrMsg, session_id: session.id, auth_required: true });
      if (!res.writableEnded) res.end();
      return;
    }

    if (code === 0) {
      let finalReply = cleanStdout;
      if (!finalReply) {
        if (cleanErr) {
          finalReply = `[Error/Stderr Antigravity]: ${cleanErr}`;
        } else {
          finalReply = '[Peringatan] Antigravity menyelesaikan proses tanpa output teks.';
        }
      }
      session.messages.push({
        role: 'assistant',
        content: finalReply,
        timestamp: Date.now(),
      });
      session.updated_at = Date.now();
      saveSession(session);

      if (!cleanStdout && cleanErr) {
        emit({ type: 'error', text: finalReply, session_id: session.id });
      } else {
        emit({ type: 'done', text: finalReply, session_id: session.id });
      }
    } else {
      const errorDetail = cleanErr || cleanStdout || `Antigravity CLI keluar dengan kode ${code}`;
      const finalReply = `[Error kode ${code}] ${errorDetail}`;
      session.messages.push({
        role: 'assistant',
        content: finalReply,
        timestamp: Date.now(),
      });
      session.updated_at = Date.now();
      saveSession(session);

      emit({ type: 'error', text: finalReply, session_id: session.id });
    }
    if (!res.writableEnded) res.end();
  });

  proc.on('error', (err) => {
    if (activeChatProc === proc) activeChatProc = null;
    const cleanMsg = stripAnsi(err.message);
    emit({ type: 'error', text: `Gagal menjalankan Antigravity: ${cleanMsg}` });
    if (!res.writableEnded) res.end();
  });
}

// ── HTTP Router ─────────────────────────────────────────────────────────────

const server = http.createServer(async (req, res) => {
  const urlObj = new URL(req.url, `http://${HOST}:${PORT}`);
  const pathname = urlObj.pathname;

  if (req.method === 'GET' && pathname === '/health') {
    const st = readState();
    const active = ensureActiveSession();
    const authenticated = fs.existsSync(AUTH_MARKER_FILE);
    return sendJson(res, 200, {
      status: 'ok',
      engine: 'antigravity',
      authenticated: authenticated,
      logged_in: authenticated,
      auth_in_progress: Boolean(activeAuthProc),
      active_session_id: active.id,
      active_workspace: st.active_workspace || '',
      last_auth_error: lastAuthError,
      antigravity_installed: fs.existsSync(ANTIGRAVITY_BIN),
      pty_bridge_available: Boolean(resolvePtyBridge()),
    });
  }

  // ── Auth Endpoints ────────────────────────────────────────────────────────
  if (req.method === 'POST' && pathname === '/auth/start') {
    return handleAuthStart(res);
  }

  if (req.method === 'POST' && pathname === '/auth/code') {
    return handleAuthCode(req, res);
  }

  // ── Channels Endpoints ────────────────────────────────────────────────────
  if (req.method === 'GET' && pathname === '/channels') {
    const channels = readChannels();
    return sendJson(res, 200, { status: 'ok', channels });
  }

  if (req.method === 'POST' && pathname === '/channels') {
    let body = {};
    try { body = await readJsonBody(req); } catch (_) {}
    try {
      const chan = createChannel(body);
      return sendJson(res, 200, { status: 'ok', channel: chan });
    } catch (e) {
      return sendJson(res, 400, { status: 'error', error: e.message || 'Gagal membuat channel' });
    }
  }

  const chanMatch = pathname.match(/^\/channels\/([^\/]+)$/);
  if (req.method === 'PUT' && chanMatch) {
    const chanId = decodeURIComponent(chanMatch[1]);
    let body = {};
    try { body = await readJsonBody(req); } catch (_) {}
    try {
      const updated = updateChannel(chanId, body);
      return sendJson(res, 200, { status: 'ok', channel: updated });
    } catch (e) {
      return sendJson(res, 400, { status: 'error', error: e.message || 'Gagal memperbarui channel' });
    }
  }

  if (req.method === 'DELETE' && chanMatch) {
    const chanId = decodeURIComponent(chanMatch[1]);
    try {
      const result = deleteChannel(chanId);
      return sendJson(res, 200, { status: 'ok', ...result });
    } catch (e) {
      return sendJson(res, 400, { status: 'error', error: e.message || 'Gagal menghapus channel' });
    }
  }

  const chanDnaMatch = pathname.match(/^\/channels\/([^\/]+)\/extract-dna$/);
  if (req.method === 'POST' && chanDnaMatch) {
    const chanId = decodeURIComponent(chanDnaMatch[1]);
    let body = {};
    try { body = await readJsonBody(req); } catch (_) {}
    try {
      const updated = await extractChannelDna(chanId, body.reference_text || body.text || '');
      return sendJson(res, 200, { status: 'ok', channel: updated });
    } catch (e) {
      return sendJson(res, 400, { status: 'error', error: e.message || 'Gagal mengekstrak DNA channel' });
    }
  }

  // ── Workspace Endpoints ───────────────────────────────────────────────────
  if (req.method === 'GET' && pathname === '/workspaces') {
    const workspaces = listWorkspaces();
    const st = readState();
    return sendJson(res, 200, {
      status: 'ok',
      active_workspace: st.active_workspace || '',
      workspaces,
    });
  }

  if (req.method === 'POST' && pathname === '/workspaces') {
    let body = {};
    try { body = await readJsonBody(req); } catch (_) {}
    try {
      const created = createWorkspace(body.name, body.channel_id || body.channelId || null);
      const st = readState();
      st.active_workspace = created.name;
      writeState(st);
      return sendJson(res, 200, { status: 'ok', workspace: created });
    } catch (e) {
      return sendJson(res, 400, { status: 'error', error: e.message || 'Gagal membuat workspace' });
    }
  }

  const wsProjMatch = pathname.match(/^\/workspaces\/([^\/]+)\/project$/);
  if (req.method === 'GET' && wsProjMatch) {
    const wsName = decodeURIComponent(wsProjMatch[1]);
    try {
      const proj = readWorkspaceProject(wsName);
      return sendJson(res, 200, { status: 'ok', workspace: wsName, project: proj });
    } catch (e) {
      return sendJson(res, 404, { status: 'error', error: e.message || 'Gagal membaca project.json' });
    }
  }

  if (req.method === 'PUT' && wsProjMatch) {
    const wsName = decodeURIComponent(wsProjMatch[1]);
    let body = {};
    try { body = await readJsonBody(req); } catch (_) {}
    try {
      const updated = writeWorkspaceProject(wsName, body.project || body);
      return sendJson(res, 200, { status: 'ok', workspace: wsName, project: updated });
    } catch (e) {
      return sendJson(res, 400, { status: 'error', error: e.message || 'Gagal menyimpan project.json' });
    }
  }

  const wsFilesMatch = pathname.match(/^\/workspaces\/([^\/]+)\/files$/);
  if (req.method === 'GET' && wsFilesMatch) {
    const wsName = decodeURIComponent(wsFilesMatch[1]);
    const files = scanWorkspaceFiles(wsName);
    if (!files) {
      return sendJson(res, 404, { status: 'error', error: `Workspace "${wsName}" tidak ditemukan` });
    }
    return sendJson(res, 200, { status: 'ok', workspace: wsName, files });
  }

  const wsDeleteMatch = pathname.match(/^\/workspaces\/([^\/]+)$/);
  if (req.method === 'DELETE' && wsDeleteMatch) {
    const wsName = decodeURIComponent(wsDeleteMatch[1]);
    try {
      const result = deleteWorkspace(wsName);
      return sendJson(res, 200, { status: 'ok', ...result });
    } catch (e) {
      return sendJson(res, 400, { status: 'error', error: e.message || 'Gagal menghapus workspace' });
    }
  }

  const wsRenderMatch = pathname.match(/^\/workspaces\/([^\/]+)\/render$/);
  if (req.method === 'POST' && wsRenderMatch) {
    const wsName = decodeURIComponent(wsRenderMatch[1]);
    const cleanName = path.basename(wsName);
    const wsPath = path.join(WORKSPACES_DIR, cleanName);
    if (!fs.existsSync(wsPath)) {
      return sendJson(res, 404, { status: 'error', error: `Workspace "${cleanName}" tidak ditemukan` });
    }

    let proj;
    try {
      proj = readWorkspaceProject(cleanName);
    } catch (e) {
      return sendJson(res, 400, { status: 'error', error: e.message || 'Gagal membaca project.json' });
    }

    if (!Array.isArray(proj.scenes) || proj.scenes.length === 0) {
      return sendJson(res, 400, { status: 'error', error: 'Tambahkan minimal 1 adegan sebelum render' });
    }

    const existingJob = renderJobs.get(cleanName);
    if (existingJob && existingJob.status === 'running') {
      return sendJson(res, 200, { status: 'ok', job_id: existingJob.id, message: 'Render job already running' });
    }

    let body = {};
    try { body = await readJsonBody(req); } catch (_) {}

    const job = {
      id: `render_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
      workspace: cleanName,
      status: 'running',
      progress: 5,
      step: 'Memulai pipeline render...',
      output_file: null,
      size: 0,
      error: null,
      created_at: new Date().toISOString(),
    };
    renderJobs.set(cleanName, job);

    executeRenderPipeline(cleanName, proj, body, job).catch((err) => {
      job.status = 'error';
      job.error = err.message || 'Render error';
      job.step = 'Gagal: ' + (err.message || 'Render error');
    });

    return sendJson(res, 200, { status: 'ok', job_id: job.id, message: 'Render job started' });
  }

  const wsRenderStatusMatch = pathname.match(/^\/workspaces\/([^\/]+)\/render\/status$/);
  if (req.method === 'GET' && wsRenderStatusMatch) {
    const wsName = decodeURIComponent(wsRenderStatusMatch[1]);
    const cleanName = path.basename(wsName);
    const job = renderJobs.get(cleanName);
    if (!job) {
      return sendJson(res, 200, {
        status: 'idle',
        progress: 0,
        step: 'Belum ada proses render',
        output_file: null,
        size: 0,
        error: null,
      });
    }
    return sendJson(res, 200, job);
  }

  const wsSavePatMatch = pathname.match(/^\/workspaces\/([^\/]+)\/save-as-pattern$/);
  if (req.method === 'POST' && wsSavePatMatch) {
    const wsName = decodeURIComponent(wsSavePatMatch[1]);
    let body = {};
    try { body = await readJsonBody(req); } catch (_) {}
    try {
      const pat = saveWorkspaceAsPattern(wsName, body);
      return sendJson(res, 200, { status: 'ok', pattern: pat });
    } catch (e) {
      return sendJson(res, 400, { status: 'error', error: e.message || 'Gagal menyimpan pola dari proyek' });
    }
  }

  const wsApplyPatMatch = pathname.match(/^\/workspaces\/([^\/]+)\/apply-pattern$/);
  if (req.method === 'POST' && wsApplyPatMatch) {
    const wsName = decodeURIComponent(wsApplyPatMatch[1]);
    let body = {};
    try { body = await readJsonBody(req); } catch (_) {}
    try {
      const updatedProj = applyPatternToWorkspace(wsName, body);
      return sendJson(res, 200, { status: 'ok', project: updatedProj, scenes: updatedProj.scenes || [] });
    } catch (e) {
      return sendJson(res, 400, { status: 'error', error: e.message || 'Gagal menerapkan pola ke proyek' });
    }
  }

  const wsPromoteDnaMatch = pathname.match(/^\/workspaces\/([^\/]+)\/promote-dna$/);
  if (req.method === 'POST' && wsPromoteDnaMatch) {
    const wsName = decodeURIComponent(wsPromoteDnaMatch[1]);
    try {
      const result = promoteWorkspaceDna(wsName);
      return sendJson(res, 200, { status: 'ok', ...result });
    } catch (e) {
      return sendJson(res, 400, { status: 'error', error: e.message || 'Gagal mempromosikan ritme ke DNA channel' });
    }
  }

  const wsSyncVoMatch = pathname.match(/^\/workspaces\/([^\/]+)\/sync-vo-duration$/);
  if (req.method === 'POST' && wsSyncVoMatch) {
    const wsName = decodeURIComponent(wsSyncVoMatch[1]);
    try {
      const result = await syncVoDuration(wsName);
      return sendJson(res, 200, result);
    } catch (e) {
      return sendJson(res, 400, { status: 'error', error: e.message || 'Gagal sinkronisasi durasi audio VO' });
    }
  }

  const wsPubKitMatch = pathname.match(/^\/workspaces\/([^\/]+)\/publish-kit$/);
  if (req.method === 'POST' && wsPubKitMatch) {
    const wsName = decodeURIComponent(wsPubKitMatch[1]);
    try {
      const kit = await generatePublishKit(wsName);
      return sendJson(res, 200, { status: 'ok', publish_kit: kit });
    } catch (e) {
      return sendJson(res, 400, { status: 'error', error: e.message || 'Gagal membuat Publish Kit' });
    }
  }

  // ── Living Patterns Endpoints ─────────────────────────────────────────────
  if (req.method === 'GET' && pathname === '/patterns') {
    const patterns = readPatterns();
    return sendJson(res, 200, { status: 'ok', patterns });
  }

  if (req.method === 'POST' && pathname === '/patterns') {
    let body = {};
    try { body = await readJsonBody(req); } catch (_) {}
    try {
      const created = createPattern(body);
      return sendJson(res, 200, { status: 'ok', pattern: created });
    } catch (e) {
      return sendJson(res, 400, { status: 'error', error: e.message || 'Gagal membuat pola ritme' });
    }
  }

  const patDeleteMatch = pathname.match(/^\/patterns\/([^\/]+)$/);
  if (req.method === 'DELETE' && patDeleteMatch) {
    const patId = decodeURIComponent(patDeleteMatch[1]);
    try {
      const result = deletePattern(patId);
      return sendJson(res, 200, { status: 'ok', ...result });
    } catch (e) {
      return sendJson(res, 400, { status: 'error', error: e.message || 'Gagal menghapus pola ritme' });
    }
  }

  // ── Asset Library Endpoints ───────────────────────────────────────────────
  if (req.method === 'GET' && pathname === '/library') {
    const category = urlObj.searchParams.get('category');
    const assets = listLibraryAssets(category);
    return sendJson(res, 200, { status: 'ok', assets });
  }

  const libDeleteMatch = pathname.match(/^\/library\/([^\/]+)\/([^\/]+)$/);
  if (req.method === 'DELETE' && libDeleteMatch) {
    const cat = decodeURIComponent(libDeleteMatch[1]);
    const name = decodeURIComponent(libDeleteMatch[2]);
    try {
      const result = deleteLibraryAsset(cat, name);
      return sendJson(res, 200, { status: 'ok', ...result });
    } catch (e) {
      return sendJson(res, 400, { status: 'error', error: e.message || 'Gagal menghapus aset library' });
    }
  }

  if (req.method === 'POST' && pathname === '/library/copy-to-workspace') {
    let body = {};
    try { body = await readJsonBody(req); } catch (_) {}
    try {
      const result = copyLibraryAssetToWorkspace(
        body.category,
        body.file_name || body.fileName || body.filename || body.name,
        body.workspace,
        body.target_dir
      );
      return sendJson(res, 200, { status: 'ok', ...result });
    } catch (e) {
      return sendJson(res, 400, { status: 'error', error: e.message || 'Gagal menyalin aset ke workspace' });
    }
  }

  // ── Sessions Endpoints ────────────────────────────────────────────────────
  if (req.method === 'GET' && pathname === '/sessions') {
    const st = readState();
    const sessions = listSessions();
    return sendJson(res, 200, {
      status: 'ok',
      active_session_id: st.active_session_id || (sessions[0] ? sessions[0].id : ''),
      active_workspace: st.active_workspace || '',
      sessions,
    });
  }

  if (req.method === 'GET' && pathname.startsWith('/sessions/')) {
    const id = pathname.slice('/sessions/'.length);
    const ses = loadSession(id);
    if (!ses) return sendJson(res, 404, { status: 'error', error: 'Sesi tidak ditemukan' });
    const st = readState();
    st.active_session_id = ses.id;
    if (ses.workspace) st.active_workspace = ses.workspace;
    writeState(st);
    return sendJson(res, 200, { status: 'ok', session: ses });
  }

  if (req.method === 'POST' && pathname === '/sessions') {
    let body = {};
    try { body = await readJsonBody(req); } catch (_) {}
    if (body.id) {
      const existing = loadSession(body.id);
      if (existing) {
        const st = readState();
        st.active_session_id = existing.id;
        if (existing.workspace) st.active_workspace = existing.workspace;
        writeState(st);
        return sendJson(res, 200, { status: 'ok', session: existing });
      }
    }
    const created = createSession(body.title || 'Sesi Baru', body.workspace || '');
    return sendJson(res, 200, { status: 'ok', session: created });
  }

  if (req.method === 'DELETE' && pathname.startsWith('/sessions/')) {
    const id = path.basename(pathname.slice('/sessions/'.length));
    const filePath = path.join(SESSIONS_DIR, `${id}.json`);
    if (fs.existsSync(filePath)) {
      fs.unlinkSync(filePath);
    }
    const st = readState();
    if (st.active_session_id === id) {
      st.active_session_id = '';
      writeState(st);
    }
    return sendJson(res, 200, { status: 'ok', deleted: id });
  }

  // ── Chat Endpoints ────────────────────────────────────────────────────────
  if (req.method === 'POST' && pathname === '/chat/stop') {
    if (activeChatProc) {
      try {
        const p = activeChatProc;
        p.kill('SIGTERM');
        const timer = setTimeout(() => {
          try { if (!p.killed) p.kill('SIGKILL'); } catch (_) {}
        }, 1000);
        p.once('exit', () => clearTimeout(timer));
      } catch (_) {}
      activeChatProc = null;
    }
    return sendJson(res, 200, { status: 'ok', stopped: true });
  }

  if (req.method === 'POST' && pathname === '/chat') {
    return handleChat(req, res);
  }

  // ── Audio Studio Endpoints ───────────────────────────────────────────────
  if (req.method === 'POST' && pathname === '/tts') {
    return handleTts(req, res);
  }

  if (req.method === 'POST' && pathname === '/audio/master') {
    return handleAudioMaster(req, res);
  }

  return sendJson(res, 404, { status: 'error', error: 'Endpoint tidak ditemukan' });
});

if (require.main === module) {
  server.listen(PORT, HOST, () => {
    console.log(`[studio-bridge] listening on http://${HOST}:${PORT}`);
  });
}

module.exports = {
  stripAnsi,
  cleanStderr,
  listWorkspaces,
  createWorkspace,
  deleteWorkspace,
  scanWorkspaceFiles,
  readChannels,
  writeChannels,
  createChannel,
  updateChannel,
  deleteChannel,
  readWorkspaceProject,
  writeWorkspaceProject,
  listLibraryAssets,
  deleteLibraryAsset,
  copyLibraryAssetToWorkspace,
  handleTts,
  handleAudioMaster,
  readPatterns,
  writePatterns,
  createPattern,
  deletePattern,
  saveWorkspaceAsPattern,
  applyPatternToWorkspace,
  extractChannelDna,
  promoteWorkspaceDna,
  syncVoDuration,
  generatePublishKit,
};
