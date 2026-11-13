'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const crypto = require('node:crypto');
const { createApp, sha256Base64Url, EXCHANGE_TTL_MS, LINKEDIN_TOKEN_ENDPOINT } = require('../src/app');

const SECRET = 'bi-mat-cua-server-khong-duoc-lo';
const APP_REDIRECT = 'com.lttbdd.socialagent://oauth/linkedin/callback';
const STATE = 'state_hop_le_0123456789abcdef';
const VERIFIER = 'verifier-hop-le-0123456789-0123456789-0123456789-abcdef';
const CHALLENGE = sha256Base64Url(VERIFIER);

/** Dựng server thật trên cổng ngẫu nhiên, LinkedIn giả và đồng hồ điều khiển được. */
async function setup({ linkedIn } = {}) {
  const clock = { t: 1_000_000 };
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    return (linkedIn || (async () => ({ ok: true, json: async () => ({ access_token: 'AQtoken', expires_in: 5184000, scope: 'openid,profile,w_member_social', refresh_token: 'KHONG-DUOC-CHUYEN-TIEP' }) })))();
  };
  const handler = createApp({
    config: { clientId: 'client_id_cong_khai', clientSecret: SECRET, publicUrl: 'https://bff.example.com/', appRedirectUri: APP_REDIRECT },
    fetchImpl,
    now: () => clock.t,
  });
  const server = http.createServer(handler);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const post = (path, body, headers = { 'Content-Type': 'application/json' }) =>
    fetch(base + path, { method: 'POST', headers, body: typeof body === 'string' ? body : JSON.stringify(body) });
  const get = (path) => fetch(base + path, { redirect: 'manual' });
  return { server, post, get, clock, calls, close: () => new Promise((r) => server.close(r)) };
}

async function startSession(ctx, state = STATE, challenge = CHALLENGE) {
  const res = await ctx.post('/auth/linkedin/start', { state, code_challenge: challenge });
  assert.equal(res.status, 200);
}

function parseLocation(res) {
  assert.equal(res.status, 302);
  const location = res.headers.get('location');
  assert.ok(location.startsWith(`${APP_REDIRECT}?`), `Location sai: ${location}`);
  return new URL(location.replace(APP_REDIRECT, 'https://app.invalid/cb')).searchParams;
}

test('luồng đầy đủ: start → callback → token trả đúng hạn thật và scope thật', async () => {
  const ctx = await setup();
  try {
    await startSession(ctx);
    const cb = await ctx.get(`/auth/linkedin/callback?code=ma_code_linkedin&state=${STATE}`);
    const params = parseLocation(cb);
    assert.equal(params.get('state'), STATE);

    const tokenRes = await ctx.post('/auth/linkedin/token', {
      exchange_code: params.get('exchange_code'),
      state: STATE,
      code_verifier: VERIFIER,
    });
    assert.equal(tokenRes.status, 200);
    assert.equal(tokenRes.headers.get('cache-control'), 'no-store');
    const body = await tokenRes.json();
    assert.equal(body.access_token, 'AQtoken');
    assert.equal(body.expires_in, 5184000);
    assert.equal(body.scope, 'openid,profile,w_member_social');
    assert.equal(body.token_type, 'bearer');
    assert.ok(!JSON.stringify(body).includes('KHONG-DUOC-CHUYEN-TIEP'), 'không chuyển tiếp refresh_token');
  } finally {
    await ctx.close();
  }
});

test('đổi code gửi đúng client_secret, redirect_uri của BFF và không lộ secret ra ngoài', async () => {
  const ctx = await setup();
  try {
    await startSession(ctx);
    const cb = await ctx.get(`/auth/linkedin/callback?code=ma_code_linkedin&state=${STATE}`);
    const location = cb.headers.get('location');
    assert.ok(!location.includes(SECRET));

    assert.equal(ctx.calls.length, 1);
    assert.equal(ctx.calls[0].url, LINKEDIN_TOKEN_ENDPOINT);
    const sent = new URLSearchParams(ctx.calls[0].init.body);
    assert.equal(sent.get('grant_type'), 'authorization_code');
    assert.equal(sent.get('code'), 'ma_code_linkedin');
    assert.equal(sent.get('client_secret'), SECRET);
    assert.equal(sent.get('client_id'), 'client_id_cong_khai');
    assert.equal(sent.get('redirect_uri'), 'https://bff.example.com/auth/linkedin/callback');
  } finally {
    await ctx.close();
  }
});

test('callback với state chưa đăng ký bị từ chối và không gọi LinkedIn', async () => {
  const ctx = await setup();
  try {
    const cb = await ctx.get('/auth/linkedin/callback?code=x&state=state_la_0123456789abcdef');
    assert.equal(cb.status, 400);
    assert.equal(ctx.calls.length, 0);
    const missing = await ctx.get('/auth/linkedin/callback?code=x');
    assert.equal(missing.status, 400);
  } finally {
    await ctx.close();
  }
});

test('state chỉ dùng được một lần ở callback', async () => {
  const ctx = await setup();
  try {
    await startSession(ctx);
    assert.equal((await ctx.get(`/auth/linkedin/callback?code=a&state=${STATE}`)).status, 302);
    assert.equal((await ctx.get(`/auth/linkedin/callback?code=a&state=${STATE}`)).status, 400);
    assert.equal(ctx.calls.length, 1);
  } finally {
    await ctx.close();
  }
});

test('phiên đăng nhập hết hạn sau 10 phút', async () => {
  const ctx = await setup();
  try {
    await startSession(ctx);
    ctx.clock.t += 10 * 60 * 1000 + 1;
    assert.equal((await ctx.get(`/auth/linkedin/callback?code=a&state=${STATE}`)).status, 400);
  } finally {
    await ctx.close();
  }
});

test('người dùng hủy ở LinkedIn: chuyển về app với access_denied, không có exchange_code', async () => {
  const ctx = await setup();
  try {
    await startSession(ctx);
    const params = parseLocation(await ctx.get(`/auth/linkedin/callback?error=user_cancelled_login&state=${STATE}`));
    assert.equal(params.get('error'), 'access_denied');
    assert.equal(params.get('exchange_code'), null);
    assert.equal(ctx.calls.length, 0);
  } finally {
    await ctx.close();
  }
});

test('LinkedIn báo lỗi OAuth khác: oauth_error', async () => {
  const ctx = await setup();
  try {
    await startSession(ctx);
    const params = parseLocation(await ctx.get(`/auth/linkedin/callback?error=invalid_scope&state=${STATE}`));
    assert.equal(params.get('error'), 'oauth_error');
  } finally {
    await ctx.close();
  }
});

test('LinkedIn từ chối đổi code hoặc ném lỗi: exchange_failed, không lộ chi tiết', async () => {
  for (const linkedIn of [
    async () => ({ ok: false, json: async () => ({ error: 'invalid_client', secret: SECRET }) }),
    async () => { throw new Error(`mạng hỏng ${SECRET}`); },
    async () => ({ ok: true, json: async () => ({}) }),
  ]) {
    const ctx = await setup({ linkedIn });
    try {
      await startSession(ctx);
      const res = await ctx.get(`/auth/linkedin/callback?code=a&state=${STATE}`);
      const params = parseLocation(res);
      assert.equal(params.get('error'), 'exchange_failed');
      assert.ok(!res.headers.get('location').includes(SECRET));
    } finally {
      await ctx.close();
    }
  }
});

async function getExchangeCode(ctx) {
  await startSession(ctx);
  return parseLocation(await ctx.get(`/auth/linkedin/callback?code=a&state=${STATE}`)).get('exchange_code');
}

test('exchange_code dùng một lần: lần hai bị từ chối', async () => {
  const ctx = await setup();
  try {
    const exchange_code = await getExchangeCode(ctx);
    const payload = { exchange_code, state: STATE, code_verifier: VERIFIER };
    assert.equal((await ctx.post('/auth/linkedin/token', payload)).status, 200);
    const again = await ctx.post('/auth/linkedin/token', payload);
    assert.equal(again.status, 400);
    assert.equal((await again.json()).error, 'invalid_grant');
  } finally {
    await ctx.close();
  }
});

test('sai code_verifier bị từ chối và làm mã mất hiệu lực', async () => {
  const ctx = await setup();
  try {
    const exchange_code = await getExchangeCode(ctx);
    const wrong = 'verifier-cua-ke-tan-cong-0123456789-0123456789-0123456789';
    const bad = await ctx.post('/auth/linkedin/token', { exchange_code, state: STATE, code_verifier: wrong });
    assert.equal(bad.status, 400);
    const good = await ctx.post('/auth/linkedin/token', { exchange_code, state: STATE, code_verifier: VERIFIER });
    assert.equal(good.status, 400, 'mã đã bị hủy sau lần thử sai');
  } finally {
    await ctx.close();
  }
});

test('sai state ở bước token bị từ chối', async () => {
  const ctx = await setup();
  try {
    const exchange_code = await getExchangeCode(ctx);
    const res = await ctx.post('/auth/linkedin/token', { exchange_code, state: 'state_khac_0123456789abcdef', code_verifier: VERIFIER });
    assert.equal(res.status, 400);
  } finally {
    await ctx.close();
  }
});

test('exchange_code hết hạn sau 60 giây', async () => {
  const ctx = await setup();
  try {
    const exchange_code = await getExchangeCode(ctx);
    ctx.clock.t += EXCHANGE_TTL_MS + 1;
    const res = await ctx.post('/auth/linkedin/token', { exchange_code, state: STATE, code_verifier: VERIFIER });
    assert.equal(res.status, 400);
  } finally {
    await ctx.close();
  }
});

test('start từ chối dữ liệu sai định dạng', async () => {
  const ctx = await setup();
  try {
    const bodies = [
      { state: 'ngan', code_challenge: CHALLENGE },
      { state: STATE, code_challenge: 'khong-phai-sha256' },
      { state: STATE },
      {},
      [],
    ];
    for (const body of bodies) {
      assert.equal((await ctx.post('/auth/linkedin/start', body)).status, 400, JSON.stringify(body));
    }
    assert.equal((await ctx.post('/auth/linkedin/start', '{khong json', { 'Content-Type': 'application/json' })).status, 400);
    assert.equal((await ctx.post('/auth/linkedin/start', { state: STATE, code_challenge: CHALLENGE }, { 'Content-Type': 'text/plain' })).status, 400);
    assert.equal((await ctx.post('/auth/linkedin/start', 'x'.repeat(5000))).status, 413);
  } finally {
    await ctx.close();
  }
});

test('token từ chối trường thiếu hoặc sai định dạng', async () => {
  const ctx = await setup();
  try {
    for (const body of [{}, { exchange_code: 'ngan', state: STATE, code_verifier: VERIFIER }, { exchange_code: 'a'.repeat(43), state: STATE }]) {
      assert.equal((await ctx.post('/auth/linkedin/token', body)).status, 400);
    }
  } finally {
    await ctx.close();
  }
});

test('địa chỉ chuyển hướng về app cố định theo cấu hình, không nhận từ request', async () => {
  const ctx = await setup();
  try {
    await startSession(ctx);
    const res = await ctx.get(`/auth/linkedin/callback?code=a&state=${STATE}&redirect_uri=https://ke-tan-cong.example&app_redirect_uri=https://ke-tan-cong.example`);
    assert.ok(res.headers.get('location').startsWith(`${APP_REDIRECT}?`));
  } finally {
    await ctx.close();
  }
});

test('thiếu cấu hình thì không khởi tạo được', () => {
  assert.throws(() => createApp({ config: { clientId: 'a', publicUrl: 'https://x', appRedirectUri: 'y' } }), /clientSecret/);
});

test('đường dẫn lạ trả 404; healthz trả ok', async () => {
  const ctx = await setup();
  try {
    assert.equal((await ctx.get('/')).status, 404);
    assert.equal((await ctx.get('/auth/linkedin/token')).status, 404);
    assert.equal((await ctx.get('/healthz')).status, 200);
  } finally {
    await ctx.close();
  }
});

test('sha256Base64Url khớp vector RFC 7636 Phụ lục B', () => {
  assert.equal(sha256Base64Url('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'), 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  assert.equal(crypto.createHash('sha256').update('').digest('hex').length, 64);
});
