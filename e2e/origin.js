'use strict';
// R19-02: đăng ký / đăng nhập cùng nguồn phải chạy từ MỌI origin của frontend, không chỉ localhost:3000.
//   Trình duyệt gửi header Origin trong mọi POST (kể cả cùng nguồn); backend chỉ coi đó là cùng nguồn khi thấy đúng
//   host:port công khai mà nginx chuyển tiếp (X-Forwarded-Host/Port/Proto). Trước khi sửa, mọi origin khác
//   localhost:3000 (drill :13000, FRONTEND_PORT tùy chỉnh, IP LAN, tên miền) nhận 403 "Invalid CORS request".
//   - đăng ký + đăng xuất + đăng nhập lại qua giao diện tại BASE_URL, không có lỗi 403/console nào;
//   - (E2E_DEMO_LOGIN=1) đăng nhập bằng tài khoản demo owner@classroom.local / Password123! của lớp phủ demo;
//   - qua cổng frontend: Origin = BASE_URL cho 401 (sai mật khẩu, KHÔNG phải 403); Origin lạ (https://evil.example) vẫn 403,
//     kể cả khi kẻ gọi cố giả X-Forwarded-Host / Forwarded (nginx ghi đè/xóa chúng).
// Không cần dữ liệu từ lần chạy e2e.js. Tự đăng ký một người dùng mới (e2e.origin.<run>@example.com).
//   BASE_URL=http://localhost:13000 E2E_DEMO_LOGIN=1 node origin.js     # stack drill có lớp phủ demo
const http = require('http');
const https = require('https');
const path = require('path');
const { BASE, SHOTS_ROOT, log, newRunId, launchBrowser, newCtx, createRunner, register, login, unexpectedEvents } = require('./lib');

const RUN = newRunId();
const USER = { name: `Origin ${RUN}`, email: `e2e.origin.${RUN}@example.com` };
const { step, events, summarize, instrument } = createRunner(path.join(SHOTS_ROOT, `origin-${RUN}`));
const ORIGIN = new URL(BASE).origin;

/** POST /api/v1/auth/login through the frontend port with an arbitrary Origin (a browser cannot forge one; Node can). */
function postLogin(origin, extraHeaders = {}) {
  const url = new URL(`${BASE}/api/v1/auth/login`);
  const body = JSON.stringify({ email: `nobody.${RUN}@example.com`, password: 'sai-mat-khau-1' });
  const client = url.protocol === 'https:' ? https : http;
  return new Promise((resolve, reject) => {
    const req = client.request(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body), Origin: origin, ...extraHeaders },
    }, (res) => {
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => resolve({ status: res.statusCode, body: data }));
    });
    req.on('error', reject);
    req.end(body);
  });
}

(async () => {
  const browser = await launchBrowser();
  log('browser', browser.version(), 'base', BASE);
  const ctx = await newCtx(browser);
  const page = await ctx.newPage();
  instrument(page, 'origin');

  await step('O1', `register from ${ORIGIN} (same-origin POST carries Origin)`, page, async () => { await register(page, USER); });

  await step('O1', 'log out, then log in again from this origin', page, async () => {
    await page.getByTitle('Đăng xuất').click();
    await page.getByTitle('Đăng xuất').waitFor({ state: 'hidden', timeout: 10000 });
    await login(page, USER);
  });

  if (process.env.E2E_DEMO_LOGIN === '1') {
    await step('O1', 'demo account (owner@classroom.local) logs in from this origin', page, async () => {
      await page.getByTitle('Đăng xuất').click();
      await page.getByTitle('Đăng xuất').waitFor({ state: 'hidden', timeout: 10000 });
      await page.goto(`${BASE}/login`);
      await page.getByLabel('Email').fill('owner@classroom.local');
      await page.getByLabel('Mật khẩu').fill('Password123!');
      await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
      await page.waitForURL(/\/classes$/, { timeout: 15000 });
      await page.getByTitle('Đăng xuất').waitFor({ timeout: 10000 });
    });
  }

  await step('O2', 'this origin is same-origin for the API: a wrong password is 401, never 403', null, async () => {
    const r = await postLogin(ORIGIN);
    if (r.status !== 401) throw new Error(`Origin ${ORIGIN} -> HTTP ${r.status} ${r.body.slice(0, 120)} (mong đợi 401)`);
    return `Origin ${ORIGIN} -> 401`;
  });

  await step('O2', 'a foreign Origin is still rejected (403)', null, async () => {
    const r = await postLogin('https://evil.example');
    if (r.status !== 403) throw new Error(`Origin https://evil.example -> HTTP ${r.status} ${r.body.slice(0, 120)} (mong đợi 403)`);
    return 'https://evil.example -> 403';
  });

  await step('O2', 'a foreign Origin cannot be laundered through forged X-Forwarded-Host / Forwarded headers', null, async () => {
    const spoofs = [
      { 'X-Forwarded-Host': 'evil.example', 'X-Forwarded-Proto': 'https' },
      { Forwarded: 'host=evil.example;proto=https', 'X-Forwarded-Host': 'evil.example', 'X-Forwarded-Proto': 'https', 'X-Forwarded-Port': '443' },
    ];
    const outcomes = [];
    for (const headers of spoofs) {
      const r = await postLogin('https://evil.example', headers);
      outcomes.push(r.status);
      if (r.status !== 403) throw new Error(`Origin https://evil.example + ${Object.keys(headers).join('/')} -> HTTP ${r.status} (mong đợi 403)`);
    }
    return `spoofed forwarding headers -> ${outcomes.join(',')}`;
  });

  await step('O3', 'no unexpected browser errors (no 403 / CORS / console errors) while doing all of the above', page, async () => {
    // Only problems with THIS origin count: another run's class may carry an external cover image (e.g. example.com/c.png)
    // that the browser blocks (net::ERR_BLOCKED_BY_ORB) - that is not a login/CORS problem.
    const host = new URL(BASE).host;
    const bad = unexpectedEvents(events).filter((e) => !(e.kind === 'requestfailed' && !e.text.includes(host)));
    if (bad.length) throw new Error(`${bad.length} sự kiện: ${JSON.stringify(bad.slice(0, 3))}`);
  });

  await browser.close();
  process.exitCode = summarize(`Đăng nhập theo origin (origin.js) tại ${ORIGIN}`);
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
