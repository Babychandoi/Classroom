'use strict';
// R17-01: đua giữa khởi tạo phiên (POST /auth/refresh + GET /me) và các hành động đầu tiên trên trang.
//   - "Tham gia lớp ngay" không được hiện/bấm được trước khi phiên khôi phục xong;
//   - người dùng đã đăng nhập bấm ngay khi nút hiện (kể cả khi /auth/refresh chậm 800ms) KHÔNG bị đưa sang /login;
//   - mở thẳng (deep link) một trang lớp không sinh request ẩn danh/401 rồi lặp lại khi có người dùng;
//   - khách chưa đăng nhập bấm tham gia -> /login -> đăng nhập xong quay lại đúng trang lớp;
//   - người đã đăng nhập mở /login được chuyển đi, không bị kẹt ở form.
// Cần: đã chạy e2e.js (đọc shots/last-run.json) hoặc đặt E2E_CLASS_SLUG. Tự đăng ký một học viên mới chưa vào lớp.
const path = require('path');
const { BASE, PASSWORD, SHOTS_ROOT, log, newRunId, launchBrowser, newCtx, createRunner, register, loadState } = require('./lib');

// slug lớp: đối số dòng lệnh > E2E_CLASS_SLUG > lần chạy e2e.js gần nhất (shots/last-run.json)
const slug = process.argv[2] || loadState().classSlug;

const RUN = newRunId();
const USER = { name: `Race ${RUN}`, email: `e2e.race.${RUN}@example.com` };
const { step, shot, events, summarize, instrument } = createRunner(path.join(SHOTS_ROOT, `race-${RUN}`));

const JOIN = 'Tham gia lớp ngay';

(async () => {
  const browser = await launchBrowser();
  log('browser', browser.version(), 'class', slug, 'base', BASE);
  const ctx = await newCtx(browser);
  const page = await ctx.newPage();
  instrument(page, 'race');

  await step('R1', 'register a fresh non-member', page, async () => { await register(page, USER); });

  await step('R1', 'guest CTA never shows before the session is restored', page, async () => {
    const t0 = Date.now();
    let meAt = null;
    page.on('response', (r) => { if (/\/api\/v1\/me$/.test(r.url()) && meAt === null) meAt = Date.now() - t0; });
    await page.goto(`${BASE}/classes/${slug}/feed`);
    await page.getByRole('button', { name: JOIN }).waitFor();
    const joinAt = Date.now() - t0;
    if (meAt === null) throw new Error('GET /me was never requested');
    if (joinAt < meAt) throw new Error(`join CTA visible at ${joinAt}ms, before /me answered at ${meAt}ms`);
    return `join visible at ${joinAt}ms, /me answered at ${meAt}ms`;
  });

  await step('R1', 'deep link: one class fetch, no 401, one refresh', page, async () => {
    const seen = [];
    const onRequest = (r) => seen.push(`${r.method()} ${r.url().replace(BASE, '').replace(/\?.*/, '')}`);
    const statuses = [];
    const onResponse = (r) => { if (r.status() >= 400) statuses.push(`${r.status()} ${r.request().method()} ${r.url().replace(BASE, '')}`); };
    page.on('request', onRequest);
    page.on('response', onResponse);
    await page.goto(`${BASE}/classes/${slug}/feed`);
    await page.getByRole('button', { name: JOIN }).waitFor();
    await page.waitForTimeout(1500);
    page.off('request', onRequest);
    page.off('response', onResponse);
    const count = (needle) => seen.filter((s) => s === needle).length;
    const classFetches = count(`GET /api/v1/classes/slug/${slug}`);
    const refreshes = count('POST /api/v1/auth/refresh');
    const unauthorized = statuses.filter((s) => s.startsWith('401'));
    const problems = [];
    if (classFetches !== 1) problems.push(`GET /classes/slug/${slug} x${classFetches} (mong đợi 1)`);
    if (refreshes !== 1) problems.push(`POST /auth/refresh x${refreshes} (mong đợi 1)`);
    if (unauthorized.length) problems.push(`401: ${unauthorized.join(', ')}`);
    if (problems.length) throw new Error(problems.join('; '));
    return `classFetches=${classFetches} refresh=${refreshes}`;
  });

  await step('R1', 'signed-in user opening /login is sent on', page, async () => {
    await page.goto(`${BASE}/login`);
    await page.waitForURL(/\/classes$/, { timeout: 10000 });
  });

  await step('R2', 'anonymous visitor: Join -> /login -> back to the class page', page, async () => {
    const anonCtx = await newCtx(browser);
    const anon = await anonCtx.newPage();
    instrument(anon, 'race-anon');
    try {
      await anon.goto(`${BASE}/classes/${slug}/feed`);
      await anon.getByRole('button', { name: JOIN }).click();
      await anon.waitForURL(/\/login$/, { timeout: 10000 });
      await anon.getByLabel('Email').fill(USER.email);
      await anon.getByLabel('Mật khẩu').fill(PASSWORD);
      await anon.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
      await anon.waitForURL(new RegExp(`/classes/${slug}/feed$`), { timeout: 15000 });
      await anon.getByRole('button', { name: JOIN }).waitFor({ timeout: 10000 });
    } catch (e) {
      await shot(anon, 'FAIL-R2-anonymous-return');
      throw e;
    } finally {
      await anonCtx.close();
    }
  });

  await step('R3', 'slow /auth/refresh (800ms): Join clicked as soon as it shows joins, never /login', page, async () => {
    await page.route('**/api/v1/auth/refresh', async (route) => { await new Promise((r) => setTimeout(r, 800)); await route.continue(); });
    try {
      await page.goto(`${BASE}/classes/${slug}/feed`);
      await page.getByRole('button', { name: JOIN }).click();
      await page.getByPlaceholder('Tiêu đề bài viết...').waitFor({ timeout: 10000 });
      const url = page.url();
      if (/\/login/.test(url)) throw new Error(`urlAfterJoinClick=${url}`);
      if (!(await page.getByTitle('Đăng xuất').isVisible())) throw new Error('navbar does not show the signed-in state');
      return `urlAfterJoinClick=${url.replace(BASE, '')}`;
    } finally {
      await page.unroute('**/api/v1/auth/refresh');
    }
  });

  await browser.close();
  const noise = events.filter((e) => /console\.error|pageerror|securitypolicyviolation/.test(`${e.kind} ${e.text}`) && !/http\.40[14]|401|404/.test(e.text));
  if (noise.length) console.log('Cảnh báo - lỗi console trong lúc chạy:', JSON.stringify(noise.slice(0, 3)));
  process.exitCode = summarize('Race khởi tạo phiên (race.js)');
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
