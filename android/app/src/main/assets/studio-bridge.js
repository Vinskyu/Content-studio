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

const ANTIGRAVITY_BIN = path.join(PREFIX_DIR, 'bin', 'antigravity');
const AUTH_MARKER_FILE = path.join(HOME_DIR, '.antigravity_auth_ok');

fs.mkdirSync(SESSIONS_DIR, { recursive: true });

let activeChatProc = null;
let activeAuthProc = null;
let lastAuthError = null;

function getAntigravityEnv() {
  return {
    ...process.env,
    HOME: HOME_DIR,
    XDG_CONFIG_HOME: path.join(HOME_DIR, '.config'),
    XDG_DATA_HOME: path.join(HOME_DIR, '.local', 'share'),
    SSL_CERT_FILE: path.join(PREFIX_DIR, 'etc', 'tls', 'cert.pem'),
    TERM: 'dumb',
    NO_COLOR: '1',
    FORCE_COLOR: '0',
    ANTIGRAVITY_SAFECLIS_SOURCE: '1',
  };
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
  return { active_session_id: '' };
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
  if (activeAuthProc) {
    try { activeAuthProc.kill('SIGTERM'); } catch (_) {}
    activeAuthProc = null;
  }

  lastAuthError = null;

  if (!fs.existsSync(ANTIGRAVITY_BIN)) {
    lastAuthError = `Binary Antigravity tidak ditemukan di ${ANTIGRAVITY_BIN}`;
    return sendJson(res, 500, { status: 'error', error: lastAuthError });
  }

  let responded = false;
  let combinedOutput = '';

  const proc = spawn(ANTIGRAVITY_BIN, ['-p', 'ping'], {
    env: getAntigravityEnv(),
    cwd: HOME_DIR,
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  activeAuthProc = proc;

  const urlRegex = /(https:\/\/accounts\.google\.com\/o\/oauth2\/[^\s'"]+)/;

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
      sendJson(res, 500, { status: 'error', error: `Gagal menjalankan Antigravity: ${err.message}` });
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

  emit({ type: 'status', text: 'Menjalankan Antigravity...' });

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

  const proc = spawn(ANTIGRAVITY_BIN, ['-p', promptToSend], {
    env: getAntigravityEnv(),
    cwd: HOME_DIR,
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

  req.on('close', () => {
    if (activeChatProc === proc) {
      try { proc.kill('SIGTERM'); } catch (_) {}
      activeChatProc = null;
    }
  });

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
      const finalReply = cleanStdout || '(Selesai tanpa output teks)';
      session.messages.push({
        role: 'assistant',
        content: finalReply,
        timestamp: Date.now(),
      });
      session.updated_at = Date.now();
      saveSession(session);

      emit({ type: 'done', text: finalReply, session_id: session.id });
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

const server = http.createServer(async (req, res) => {
  const urlObj = new URL(req.url, `http://${HOST}:${PORT}`);
  const pathname = urlObj.pathname;

  if (req.method === 'GET' && pathname === '/health') {
    const active = ensureActiveSession();
    const authenticated = fs.existsSync(AUTH_MARKER_FILE);
    return sendJson(res, 200, {
      status: 'ok',
      engine: 'antigravity',
      authenticated: authenticated,
      logged_in: authenticated,
      auth_in_progress: Boolean(activeAuthProc),
      active_session_id: active.id,
      last_auth_error: lastAuthError,
      antigravity_installed: fs.existsSync(ANTIGRAVITY_BIN),
    });
  }

  if (req.method === 'POST' && pathname === '/auth/start') {
    return handleAuthStart(res);
  }

  if (req.method === 'POST' && pathname === '/auth/code') {
    return handleAuthCode(req, res);
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

if (require.main === module) {
  server.listen(PORT, HOST, () => {
    console.log(`[studio-bridge] listening on http://${HOST}:${PORT}`);
  });
}

module.exports = {
  stripAnsi,
  cleanStderr,
};
