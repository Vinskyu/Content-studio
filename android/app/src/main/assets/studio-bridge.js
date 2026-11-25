'use strict';

const http = require('http');
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = 18950;
const HOST = '127.0.0.1';

const HOME_DIR = process.env.HOME || '/tmp';
const PREFIX_DIR = process.env.PREFIX || '/usr';
const SESSIONS_DIR = path.join(HOME_DIR, '.contentstudio', 'sessions');
const STATE_FILE = path.join(HOME_DIR, '.contentstudio', 'state.json');

fs.mkdirSync(SESSIONS_DIR, { recursive: true });

let activeAuthProc = null;
let activeChatProc = null;
let oauthSucceeded = false;

function readState() {
  try {
    if (fs.existsSync(STATE_FILE)) {
      return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
    }
  } catch (_) {}
  return { active_session_id: '' };
}

function writeState(state) {
  try {
    fs.mkdirSync(path.dirname(STATE_FILE), { recursive: true });
    fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2));
  } catch (_) {}
}

function isGeminiInstalled() {
  const binPath = path.join(PREFIX_DIR, 'bin', 'gemini');
  const modPath = path.join(PREFIX_DIR, 'lib', 'node_modules', '@google', 'gemini-cli');
  return fs.existsSync(binPath) || fs.existsSync(modPath);
}

function isLoggedIn() {
  if (oauthSucceeded) return true;
  const geminiDir = path.join(HOME_DIR, '.gemini');
  const oauthCreds = path.join(geminiDir, 'oauth_creds.json');
  const googleAccounts = path.join(geminiDir, 'google_accounts.json');
  try {
    if (fs.existsSync(oauthCreds)) {
      const raw = fs.readFileSync(oauthCreds, 'utf8').trim();
      if (raw.length > 10 && raw !== '{}') return true;
    }
    if (fs.existsSync(googleAccounts)) {
      const acc = JSON.parse(fs.readFileSync(googleAccounts, 'utf8'));
      if (acc && acc.active) return true;
    }
  } catch (_) {}
  return false;
}

function resolveGeminiEntry() {
  const pkgDir = path.join(PREFIX_DIR, 'lib', 'node_modules', '@google', 'gemini-cli');
  const pkgJsonPath = path.join(pkgDir, 'package.json');
  try {
    if (fs.existsSync(pkgJsonPath)) {
      const pkg = JSON.parse(fs.readFileSync(pkgJsonPath, 'utf8'));
      let relBin = null;
      if (typeof pkg.bin === 'string') {
        relBin = pkg.bin;
      } else if (pkg.bin && typeof pkg.bin === 'object') {
        relBin = pkg.bin.gemini || Object.values(pkg.bin)[0];
      }
      if (relBin) {
        const resolved = path.resolve(pkgDir, relBin);
        if (fs.existsSync(resolved)) return resolved;
      }
    }
  } catch (_) {}

  const binPath = path.join(PREFIX_DIR, 'bin', 'gemini');
  try {
    if (fs.existsSync(binPath)) {
      const real = fs.realpathSync(binPath);
      if (real.endsWith('.js') || real.endsWith('.mjs')) {
        return real;
      }
    }
  } catch (_) {}

  const entryCandidates = [
    path.join(pkgDir, 'dist', 'index.js'),
    path.join(pkgDir, 'bundle', 'gemini.js'),
    path.join(pkgDir, 'index.js'),
  ];
  for (const entry of entryCandidates) {
    if (fs.existsSync(entry)) return entry;
  }
  return null;
}

function listSessions() {
  fs.mkdirSync(SESSIONS_DIR, { recursive: true });
  const files = fs.readdirSync(SESSIONS_DIR).filter((f) => f.endsWith('.json'));
  const sessions = [];
  for (const f of files) {
    try {
      const data = JSON.parse(fs.readFileSync(path.join(SESSIONS_DIR, f), 'utf8'));
      sessions.push({
        id: data.id,
        title: data.title || 'Sesi Baru',
        created_at: data.created_at || 0,
        updated_at: data.updated_at || 0,
        message_count: Array.isArray(data.messages) ? data.messages.length : 0,
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

function createSession(title) {
  const id = `ses_${Date.now()}_${crypto.randomBytes(2).toString('hex')}`;
  const now = Date.now();
  const session = {
    id,
    title: title || 'Sesi Baru',
    created_at: now,
    updated_at: now,
    messages: [],
  };
  saveSession(session);
  const st = readState();
  st.active_session_id = id;
  writeState(st);
  return session;
}

function ensureActiveSession() {
  const st = readState();
  if (st.active_session_id) {
    const existing = loadSession(st.active_session_id);
    if (existing) return existing;
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
  return createSession('Sesi Baru');
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

function handleAuthStart(res) {
  if (isLoggedIn()) {
    return sendJson(res, 200, { status: 'already_logged_in', logged_in: true, auth_url: '' });
  }

  if (activeAuthProc) {
    try { activeAuthProc.kill('SIGTERM'); } catch (_) {}
    activeAuthProc = null;
  }

  const geminiEntry = resolveGeminiEntry();
  if (!geminiEntry) {
    return sendJson(res, 500, { status: 'error', error: 'Entrypoint @google/gemini-cli tidak ditemukan.' });
  }
  const childEnv = {
    ...process.env,
    NO_BROWSER: 'true',
    BROWSER: 'echo',
    TERM: 'dumb',
  };

  let responded = false;
  let outputBuffer = '';

  const proc = spawn(process.execPath, [geminiEntry, '-p', 'hello'], { env: childEnv, cwd: HOME_DIR });
  activeAuthProc = proc;

  const timer = setTimeout(() => {
    if (!responded) {
      responded = true;
      sendJson(res, 504, {
        status: 'error',
        error: 'Timeout menunggu URL OAuth Google dari Gemini CLI.',
        raw: outputBuffer.slice(-400),
      });
    }
  }, 20000);

  function checkChunk(chunk) {
    const str = chunk.toString('utf8');
    outputBuffer += str;
    const match = outputBuffer.match(/(https:\/\/accounts\.google\.com\/[^\s"'<>]+)/);
    if (match && !responded) {
      responded = true;
      clearTimeout(timer);
      sendJson(res, 200, { status: 'ok', auth_url: match[1] });
    }
  }

  proc.stdout.on('data', checkChunk);
  proc.stderr.on('data', checkChunk);

  proc.on('exit', (code) => {
    clearTimeout(timer);
    if (activeAuthProc === proc) activeAuthProc = null;
    if (code === 0) {
      oauthSucceeded = true;
    }
    if (!responded) {
      responded = true;
      if (isLoggedIn()) {
        sendJson(res, 200, { status: 'already_logged_in', logged_in: true, auth_url: '' });
      } else {
        sendJson(res, 500, {
          status: 'error',
          error: `Gemini CLI berhenti dengan kode ${code}`,
          raw: outputBuffer.slice(-400),
        });
      }
    }
  });

  proc.on('error', (err) => {
    clearTimeout(timer);
    if (activeAuthProc === proc) activeAuthProc = null;
    if (!responded) {
      responded = true;
      sendJson(res, 500, { status: 'error', error: err.message });
    }
  });
}

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

  let session = body.session_id ? loadSession(body.session_id) : null;
  if (!session) {
    session = ensureActiveSession();
  }

  const st = readState();
  st.active_session_id = session.id;
  writeState(st);

  session.messages.push({
    role: 'user',
    content: userMsg,
    timestamp: Date.now(),
  });
  if (session.title === 'Sesi Baru' && session.messages.length === 1) {
    session.title = userMsg.slice(0, 40);
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

  emit({ type: 'status', text: 'Menjalankan Gemini CLI...' });

  const recentHistory = session.messages.slice(-8, -1);
  let promptToSend = userMsg;
  if (recentHistory.length > 0) {
    const historyLines = recentHistory.map((m) =>
      `${m.role === 'user' ? 'USER' : 'ASSISTANT'}: ${m.content}`
    );
    promptToSend = `Konteks percakapan sebelumnya:\n${historyLines.join('\n')}\n\nInstruksi terbaru USER:\n${userMsg}`;
  }

  if (activeChatProc) {
    try { activeChatProc.kill('SIGTERM'); } catch (_) {}
    activeChatProc = null;
  }

  const geminiEntry = resolveGeminiEntry();
  if (!geminiEntry) {
    emit({ type: 'error', text: 'Entrypoint @google/gemini-cli tidak ditemukan.' });
    if (!res.writableEnded) res.end();
    return;
  }
  const proc = spawn(process.execPath, [geminiEntry, '-p', promptToSend], {
    env: { ...process.env, NO_BROWSER: 'true', TERM: 'dumb' },
    cwd: HOME_DIR,
  });
  activeChatProc = proc;

  let fullOutput = '';
  let errOutput = '';

  proc.stdout.on('data', (chunk) => {
    const text = chunk.toString('utf8');
    fullOutput += text;
    emit({ type: 'delta', text });
  });

  proc.stderr.on('data', (chunk) => {
    const text = chunk.toString('utf8');
    errOutput += text;
  });

  req.on('close', () => {
    if (activeChatProc === proc) {
      try { proc.kill('SIGTERM'); } catch (_) {}
      activeChatProc = null;
    }
  });

  proc.on('exit', (code) => {
    if (activeChatProc === proc) activeChatProc = null;
    const finalReply = fullOutput.trim() || (code === 0 ? '(Selesai tanpa output teks)' : `[Error kode ${code}] ${errOutput.trim()}`);
    session.messages.push({
      role: 'assistant',
      content: finalReply,
      timestamp: Date.now(),
    });
    session.updated_at = Date.now();
    saveSession(session);

    if (code === 0 || fullOutput.trim().length > 0) {
      emit({ type: 'done', text: finalReply, session_id: session.id });
    } else {
      emit({ type: 'error', text: finalReply, session_id: session.id });
    }
    if (!res.writableEnded) res.end();
  });

  proc.on('error', (err) => {
    if (activeChatProc === proc) activeChatProc = null;
    emit({ type: 'error', text: `Gagal menjalankan Gemini CLI: ${err.message}` });
    if (!res.writableEnded) res.end();
  });
}

const server = http.createServer(async (req, res) => {
  const urlObj = new URL(req.url, `http://${HOST}:${PORT}`);
  const pathname = urlObj.pathname;

  if (req.method === 'GET' && pathname === '/health') {
    const active = ensureActiveSession();
    return sendJson(res, 200, {
      status: 'ok',
      node_version: process.version,
      gemini_installed: isGeminiInstalled(),
      logged_in: isLoggedIn(),
      active_session_id: active.id,
    });
  }

  if (req.method === 'POST' && pathname === '/auth/start') {
    return handleAuthStart(res);
  }

  if (req.method === 'GET' && pathname === '/sessions') {
    const st = readState();
    const sessions = listSessions();
    return sendJson(res, 200, {
      status: 'ok',
      active_session_id: st.active_session_id || (sessions[0] ? sessions[0].id : ''),
      sessions,
    });
  }

  if (req.method === 'GET' && pathname.startsWith('/sessions/')) {
    const id = pathname.slice('/sessions/'.length);
    const ses = loadSession(id);
    if (!ses) return sendJson(res, 404, { status: 'error', error: 'Sesi tidak ditemukan' });
    const st = readState();
    st.active_session_id = ses.id;
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
        writeState(st);
        return sendJson(res, 200, { status: 'ok', session: existing });
      }
    }
    const created = createSession(body.title || 'Sesi Baru');
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

  if (req.method === 'POST' && pathname === '/chat/stop') {
    if (activeChatProc) {
      try { activeChatProc.kill('SIGTERM'); } catch (_) {}
      activeChatProc = null;
    }
    return sendJson(res, 200, { status: 'ok', stopped: true });
  }

  if (req.method === 'POST' && pathname === '/chat') {
    return handleChat(req, res);
  }

  return sendJson(res, 404, { status: 'error', error: 'Endpoint tidak ditemukan' });
});

server.listen(PORT, HOST, () => {
  console.log(`[studio-bridge] listening on http://${HOST}:${PORT}`);
});
