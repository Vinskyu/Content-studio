'use strict';

const http = require('http');
const https = require('https');
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

// Konstanta URL & scope resmi google-gemini/gemini-cli; Client ID/Secret dibaca dinamis dari paket lokal
const OAUTH_SCOPES = [
  'https://www.googleapis.com/auth/cloud-platform',
  'https://www.googleapis.com/auth/userinfo.email',
  'https://www.googleapis.com/auth/userinfo.profile',
];
const SIGN_IN_SUCCESS_URL = 'https://developers.google.com/gemini-code-assist/auth_success_gemini';
const SIGN_IN_FAILURE_URL = 'https://developers.google.com/gemini-code-assist/auth_failure_gemini';

function resolveOAuthClientConfig() {
  const roots = [
    path.join(PREFIX_DIR, 'lib', 'node_modules', '@google', 'gemini-cli', 'node_modules', '@google', 'gemini-cli-core'),
    path.join(PREFIX_DIR, 'lib', 'node_modules', '@google', 'gemini-cli'),
  ];
  const queue = [];
  for (const r of roots) {
    if (fs.existsSync(r)) queue.push(r);
  }
  const visited = new Set();
  while (queue.length > 0) {
    const dir = queue.shift();
    if (visited.has(dir)) continue;
    visited.add(dir);
    let entries = [];
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch (_) { continue; }
    for (const ent of entries) {
      const full = path.join(dir, ent.name);
      if (ent.isDirectory()) {
        if (ent.name === 'dist' || ent.name === 'bundle' || ent.name === 'src' || ent.name === 'code_assist' || ent.name === 'node_modules' || ent.name === '@google' || ent.name === 'gemini-cli-core') {
          queue.push(full);
        }
      } else if (ent.isFile() && (ent.name === 'oauth2.js' || ent.name.endsWith('.js') || ent.name.endsWith('.mjs'))) {
        if (ent.name !== 'oauth2.js' && !dir.endsWith('bundle') && !dir.endsWith('dist')) continue;
        try {
          const txt = fs.readFileSync(full, 'utf8');
          const idMatch = txt.match(/['"]([0-9]+-[a-zA-Z0-9_]+\.apps\.googleusercontent\.com)['"]/);
          const secMatch = txt.match(/['"](GOCSPX-[a-zA-Z0-9_-]+)['"]/);
          if (idMatch && secMatch) {
            return { clientId: idMatch[1], clientSecret: secMatch[1] };
          }
        } catch (_) {}
      }
    }
  }
  return null;
}

fs.mkdirSync(SESSIONS_DIR, { recursive: true });

let activeOAuthServer = null;
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

function ensureGeminiSettings() {
  const geminiDir = path.join(HOME_DIR, '.gemini');
  const settingsPath = path.join(geminiDir, 'settings.json');
  fs.mkdirSync(geminiDir, { recursive: true });
  let cfg = {};
  try {
    if (fs.existsSync(settingsPath)) {
      cfg = JSON.parse(fs.readFileSync(settingsPath, 'utf8')) || {};
    }
  } catch (_) {}
  if (!cfg.security || typeof cfg.security !== 'object') cfg.security = {};
  if (!cfg.security.auth || typeof cfg.security.auth !== 'object') cfg.security.auth = {};
  if (cfg.security.auth.selectedType !== 'oauth-personal') {
    cfg.security.auth.selectedType = 'oauth-personal';
    fs.writeFileSync(settingsPath, JSON.stringify(cfg, null, 2));
  }
}

function isLoggedIn() {
  if (oauthSucceeded) return true;
  const geminiDir = path.join(HOME_DIR, '.gemini');
  const oauthCreds = path.join(geminiDir, 'oauth_creds.json');
  try {
    if (fs.existsSync(oauthCreds)) {
      const creds = JSON.parse(fs.readFileSync(oauthCreds, 'utf8'));
      if (creds && (creds.refresh_token || creds.access_token)) {
        return true;
      }
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

function httpsRequest(urlStr, method, headers, bodyStr) {
  return new Promise((resolve, reject) => {
    const u = new URL(urlStr);
    const req = https.request(
      {
        hostname: u.hostname,
        port: u.port || 443,
        path: u.pathname + u.search,
        method: method || 'GET',
        headers: headers || {},
        timeout: 20000,
      },
      (resp) => {
        let data = '';
        resp.on('data', (chunk) => {
          data += chunk.toString('utf8');
        });
        resp.on('end', () => {
          resolve({ statusCode: resp.statusCode || 0, body: data });
        });
      },
    );
    req.on('error', reject);
    req.on('timeout', () => {
      req.destroy(new Error('HTTPS request timeout'));
    });
    if (bodyStr) {
      req.write(bodyStr);
    }
    req.end();
  });
}

function handleAuthStart(res) {
  if (isLoggedIn()) {
    ensureGeminiSettings();
    return sendJson(res, 200, { status: 'already_logged_in', logged_in: true, auth_url: '' });
  }

  const oauthCfg = resolveOAuthClientConfig();
  if (!oauthCfg) {
    return sendJson(res, 500, {
      status: 'error',
      error: 'Konfigurasi OAuth tidak ditemukan di paket @google/gemini-cli lokal.',
    });
  }

  if (activeOAuthServer) {
    try { activeOAuthServer.close(); } catch (_) {}
    activeOAuthServer = null;
  }

  const state = crypto.randomBytes(32).toString('hex');
  let redirectUri = '';

  const oauthServer = http.createServer(async (req, cbRes) => {
    try {
      const reqUrl = new URL(req.url || '/', 'http://127.0.0.1');
      if (reqUrl.pathname !== '/oauth2callback') {
        cbRes.writeHead(404);
        cbRes.end('Not found');
        return;
      }

      const qs = reqUrl.searchParams;
      if (qs.get('error')) {
        cbRes.writeHead(301, { Location: SIGN_IN_FAILURE_URL });
        cbRes.end();
        return;
      }

      const code = qs.get('code');
      const retState = qs.get('state');
      if (!code || retState !== state) {
        cbRes.writeHead(301, { Location: SIGN_IN_FAILURE_URL });
        cbRes.end();
        return;
      }

      // Tukar authorization code menjadi token OAuth2 resmi Gemini CLI
      const formBody = new URLSearchParams({
        code,
        client_id: oauthCfg.clientId,
        client_secret: oauthCfg.clientSecret,
        redirect_uri: redirectUri,
        grant_type: 'authorization_code',
      }).toString();

      const tokResp = await httpsRequest(
        'https://oauth2.googleapis.com/token',
        'POST',
        {
          'Content-Type': 'application/x-www-form-urlencoded',
          'Content-Length': String(Buffer.byteLength(formBody)),
        },
        formBody,
      );

      if (tokResp.statusCode < 200 || tokResp.statusCode >= 300) {
        console.error('[studio-bridge] Token exchange failed:', tokResp.statusCode, tokResp.body);
        cbRes.writeHead(301, { Location: SIGN_IN_FAILURE_URL });
        cbRes.end();
        return;
      }

      const tokData = JSON.parse(tokResp.body);
      const expiryDate = Date.now() + (Number(tokData.expires_in) || 3600) * 1000;
      const credsToSave = {
        access_token: tokData.access_token,
        refresh_token: tokData.refresh_token,
        scope: tokData.scope || OAUTH_SCOPES.join(' '),
        token_type: tokData.token_type || 'Bearer',
        id_token: tokData.id_token,
        expiry_date: expiryDate,
      };

      const geminiDir = path.join(HOME_DIR, '.gemini');
      fs.mkdirSync(geminiDir, { recursive: true });
      const credsPath = path.join(geminiDir, 'oauth_creds.json');
      fs.writeFileSync(credsPath, JSON.stringify(credsToSave, null, 2), { mode: 0o600 });

      // Ambil email user untuk google_accounts.json
      try {
        const uiResp = await httpsRequest(
          'https://www.googleapis.com/oauth2/v2/userinfo',
          'GET',
          { Authorization: `Bearer ${tokData.access_token}` },
          null,
        );
        if (uiResp.statusCode >= 200 && uiResp.statusCode < 300) {
          const uiData = JSON.parse(uiResp.body);
          if (uiData && uiData.email) {
            const accPath = path.join(geminiDir, 'google_accounts.json');
            fs.writeFileSync(
              accPath,
              JSON.stringify({ active: uiData.email, old: [] }, null, 2),
            );
          }
        }
      } catch (_) {}

      ensureGeminiSettings();
      oauthSucceeded = true;

      cbRes.writeHead(301, { Location: SIGN_IN_SUCCESS_URL });
      cbRes.end();
    } catch (e) {
      console.error('[studio-bridge] OAuth callback error:', e.message);
      try {
        cbRes.writeHead(301, { Location: SIGN_IN_FAILURE_URL });
        cbRes.end();
      } catch (_) {}
    } finally {
      setTimeout(() => {
        try { oauthServer.close(); } catch (_) {}
        if (activeOAuthServer === oauthServer) activeOAuthServer = null;
      }, 500);
    }
  });

  oauthServer.on('error', (err) => {
    if (activeOAuthServer === oauthServer) activeOAuthServer = null;
    if (!res.headersSent) {
      sendJson(res, 500, { status: 'error', error: `Gagal membuka server callback OAuth: ${err.message}` });
    }
  });

  oauthServer.listen(0, '127.0.0.1', () => {
    activeOAuthServer = oauthServer;
    const addr = oauthServer.address();
    const cbPort = addr && typeof addr === 'object' ? addr.port : 0;
    redirectUri = `http://127.0.0.1:${cbPort}/oauth2callback`;

    const authParams = new URLSearchParams({
      client_id: oauthCfg.clientId,
      redirect_uri: redirectUri,
      response_type: 'code',
      scope: OAUTH_SCOPES.join(' '),
      access_type: 'offline',
      prompt: 'consent',
      state,
    });
    const authUrl = `https://accounts.google.com/o/oauth2/v2/auth?${authParams.toString()}`;
    sendJson(res, 200, { status: 'ok', auth_url: authUrl });

    // Timeout 5 menit agar server callback tidak menggantung selamanya jika user batal
    setTimeout(() => {
      if (activeOAuthServer === oauthServer) {
        try { oauthServer.close(); } catch (_) {}
        activeOAuthServer = null;
      }
    }, 5 * 60 * 1000).unref();
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

  ensureGeminiSettings();

  const geminiEntry = resolveGeminiEntry();
  if (!geminiEntry) {
    emit({ type: 'error', text: 'Entrypoint @google/gemini-cli tidak ditemukan.' });
    if (!res.writableEnded) res.end();
    return;
  }
  const chatEnv = { ...process.env, TERM: 'dumb', GEMINI_FORCE_ENCRYPTED_FILE: 'false' };
  delete chatEnv.NO_BROWSER;
  const proc = spawn(process.execPath, [geminiEntry, '-p', promptToSend], {
    env: chatEnv,
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
