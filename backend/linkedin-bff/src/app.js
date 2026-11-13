'use strict';

/**
 * BFF tối giản cho đăng nhập LinkedIn (Standard OAuth 2.0, docs/research-social-api.md — Luồng B).
 *
 *   POST /auth/linkedin/start     app đăng ký (state, code_challenge) trước khi mở trình duyệt
 *   GET  /auth/linkedin/callback  LinkedIn chuyển về đây; BFF đổi code lấy token bằng client_secret
 *                                 rồi chuyển hướng về app kèm exchange_code dùng một lần
 *   POST /auth/linkedin/token     app đổi exchange_code + code_verifier lấy token (một lần)
 *
 * Nguyên tắc an toàn:
 *  - client_secret chỉ đọc từ cấu hình server, không bao giờ nằm trong phản hồi hay log.
 *  - Token chỉ nằm trong bộ nhớ tối đa EXCHANGE_TTL_MS và bị xóa ngay lần đổi đầu tiên (dù đúng hay sai verifier).
 *  - Địa chỉ chuyển hướng về app lấy từ cấu hình, không từ request (không có open redirect).
 *  - Không chuyển tiếp refresh_token; không ghi log code, token, state hay verifier.
 */

const crypto = require('node:crypto');

const AUTHORIZATION_TTL_MS = 10 * 60 * 1000;
const EXCHANGE_TTL_MS = 60 * 1000;
const MAX_PENDING = 1000;
const MAX_BODY_BYTES = 4096;
const LINKEDIN_TOKEN_ENDPOINT = 'https://www.linkedin.com/oauth/v2/accessToken';

const STATE_PATTERN = /^[A-Za-z0-9._~-]{16,128}$/;
const CHALLENGE_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const VERIFIER_PATTERN = /^[A-Za-z0-9._~-]{43,128}$/;
const EXCHANGE_CODE_PATTERN = /^[A-Za-z0-9_-]{32,128}$/;

function sha256Base64Url(value) {
  return crypto.createHash('sha256').update(value, 'ascii').digest('base64url');
}

function safeEqual(a, b) {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

/** Kho tạm trong bộ nhớ: mỗi mục có hạn; lấy ra là xóa (dùng một lần). */
class ExpiringStore {
  constructor(now, ttlMs) {
    this.now = now;
    this.ttlMs = ttlMs;
    this.items = new Map();
  }

  put(key, value) {
    this.sweep();
    if (this.items.size >= MAX_PENDING) return false;
    this.items.set(key, { value, expiresAt: this.now() + this.ttlMs });
    return true;
  }

  take(key) {
    const entry = this.items.get(key);
    if (!entry) return null;
    this.items.delete(key);
    return entry.expiresAt > this.now() ? entry.value : null;
  }

  sweep() {
    const t = this.now();
    for (const [key, entry] of this.items) {
      if (entry.expiresAt <= t) this.items.delete(key);
    }
  }
}

function createApp({ config, fetchImpl = globalThis.fetch, now = Date.now, randomToken } = {}) {
  for (const key of ['clientId', 'clientSecret', 'publicUrl', 'appRedirectUri']) {
    if (!config || !config[key]) throw new Error(`Thiếu cấu hình: ${key}`);
  }
  const makeToken = randomToken || (() => crypto.randomBytes(32).toString('base64url'));
  const callbackUrl = `${config.publicUrl.replace(/\/+$/, '')}/auth/linkedin/callback`;
  const pending = new ExpiringStore(now, AUTHORIZATION_TTL_MS);
  const exchanges = new ExpiringStore(now, EXCHANGE_TTL_MS);

  function sendJson(res, status, body) {
    const payload = JSON.stringify(body);
    res.writeHead(status, {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      'Content-Length': Buffer.byteLength(payload),
    });
    res.end(payload);
  }

  function sendText(res, status, text) {
    res.writeHead(status, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(text);
  }

  function redirectToApp(res, params) {
    const target = `${config.appRedirectUri}?${new URLSearchParams(params).toString()}`;
    res.writeHead(302, { Location: target, 'Cache-Control': 'no-store' });
    res.end();
  }

  function readJson(req) {
    return new Promise((resolve, reject) => {
      const type = String(req.headers['content-type'] || '');
      if (!type.toLowerCase().startsWith('application/json')) return reject(new Error('content-type'));
      let size = 0;
      let tooLarge = false;
      const chunks = [];
      req.on('data', (chunk) => {
        size += chunk.length;
        if (size > MAX_BODY_BYTES) {
          // Không giữ thêm dữ liệu; phản hồi 413 khi nhận xong thay vì cắt kết nối giữa chừng.
          tooLarge = true;
          chunks.length = 0;
          return;
        }
        chunks.push(chunk);
      });
      req.on('end', () => {
        if (tooLarge) return reject(new Error('too-large'));
        try {
          const parsed = JSON.parse(Buffer.concat(chunks).toString('utf8'));
          if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('shape');
          resolve(parsed);
        } catch (_) {
          reject(new Error('json'));
        }
      });
      req.on('error', reject);
    });
  }

  async function handleStart(req, res) {
    let body;
    try {
      body = await readJson(req);
    } catch (error) {
      return sendJson(res, error.message === 'too-large' ? 413 : 400, { error: 'invalid_request' });
    }
    if (!STATE_PATTERN.test(String(body.state)) || !CHALLENGE_PATTERN.test(String(body.code_challenge))) {
      return sendJson(res, 400, { error: 'invalid_request' });
    }
    if (!pending.put(body.state, { challenge: body.code_challenge })) {
      return sendJson(res, 503, { error: 'busy' });
    }
    return sendJson(res, 200, { ok: true });
  }

  async function exchangeWithLinkedIn(code) {
    const form = new URLSearchParams({
      grant_type: 'authorization_code',
      code,
      redirect_uri: callbackUrl,
      client_id: config.clientId,
      client_secret: config.clientSecret,
    });
    const response = await fetchImpl(LINKEDIN_TOKEN_ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form.toString(),
    });
    if (!response.ok) return null;
    const data = await response.json();
    if (!data || typeof data.access_token !== 'string' || !data.access_token) return null;
    const expiresIn = Number(data.expires_in);
    return {
      access_token: data.access_token,
      token_type: 'bearer',
      // Chuyển tiếp nguyên hạn thật của LinkedIn; thiếu thì không bịa.
      expires_in: Number.isFinite(expiresIn) && expiresIn > 0 ? Math.floor(expiresIn) : null,
      scope: typeof data.scope === 'string' && data.scope ? data.scope : null,
    };
  }

  async function handleCallback(url, res) {
    const state = url.searchParams.get('state') || '';
    // Lấy phiên ra (xóa luôn): mỗi state chỉ dùng được một lần, kể cả khi LinkedIn báo lỗi.
    const session = STATE_PATTERN.test(state) ? pending.take(state) : null;
    if (!session) return sendText(res, 400, 'Phiên đăng nhập không hợp lệ hoặc đã hết hạn.');

    const error = url.searchParams.get('error');
    if (error) {
      const cancelled = error === 'access_denied' || error.startsWith('user_cancelled');
      return redirectToApp(res, { error: cancelled ? 'access_denied' : 'oauth_error', state });
    }
    const code = url.searchParams.get('code');
    if (!code) return redirectToApp(res, { error: 'oauth_error', state });

    let token = null;
    try {
      token = await exchangeWithLinkedIn(code);
    } catch (_) {
      token = null;
    }
    if (!token) return redirectToApp(res, { error: 'exchange_failed', state });

    const exchangeCode = makeToken();
    exchanges.put(exchangeCode, { state, challenge: session.challenge, token });
    return redirectToApp(res, { exchange_code: exchangeCode, state });
  }

  async function handleToken(req, res) {
    let body;
    try {
      body = await readJson(req);
    } catch (_) {
      return sendJson(res, 400, { error: 'invalid_request' });
    }
    const { exchange_code: exchangeCode, state, code_verifier: verifier } = body;
    if (
      !EXCHANGE_CODE_PATTERN.test(String(exchangeCode)) ||
      !STATE_PATTERN.test(String(state)) ||
      !VERIFIER_PATTERN.test(String(verifier))
    ) {
      return sendJson(res, 400, { error: 'invalid_request' });
    }
    // Lấy ra là xóa: thử sai verifier cũng làm mã mất hiệu lực, người dùng chỉ cần đăng nhập lại.
    const entry = exchanges.take(exchangeCode);
    if (!entry) return sendJson(res, 400, { error: 'invalid_grant' });
    if (!safeEqual(entry.state, state) || !safeEqual(entry.challenge, sha256Base64Url(verifier))) {
      return sendJson(res, 400, { error: 'invalid_grant' });
    }
    return sendJson(res, 200, entry.token);
  }

  return async function handler(req, res) {
    try {
      const url = new URL(req.url, 'http://localhost');
      if (req.method === 'POST' && url.pathname === '/auth/linkedin/start') return await handleStart(req, res);
      if (req.method === 'GET' && url.pathname === '/auth/linkedin/callback') return await handleCallback(url, res);
      if (req.method === 'POST' && url.pathname === '/auth/linkedin/token') return await handleToken(req, res);
      if (req.method === 'GET' && url.pathname === '/healthz') return sendText(res, 200, 'ok');
      return sendJson(res, 404, { error: 'not_found' });
    } catch (_) {
      // Không đưa chi tiết lỗi (có thể chứa dữ liệu nhạy cảm) ra ngoài hay vào log.
      if (!res.headersSent) sendJson(res, 500, { error: 'server_error' });
    }
  };
}

module.exports = { createApp, sha256Base64Url, EXCHANGE_TTL_MS, AUTHORIZATION_TTL_MS, LINKEDIN_TOKEN_ENDPOINT };
