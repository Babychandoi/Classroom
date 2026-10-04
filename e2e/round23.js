'use strict';
// Isolated adversarial checks against the local demo stack; no production data is modified.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { BASE, PASSWORD, launchBrowser, newCtx, newRunId, SHOTS_ROOT } = require('./lib');
const run = newRunId();
let passed = 0;
async function api(method, route, token, body, headers = {}) {
  const r = await fetch(`${BASE}/api/v1${route}`, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers }, body: body === undefined ? undefined : JSON.stringify(body) });
  return { status: r.status, headers: r.headers, ...(await r.json().catch(() => ({}))) };
}
async function ok(method, route, token, body) { const r = await api(method, route, token, body); assert.equal(r.status, 200, `${method} ${route}: ${JSON.stringify(r.error)}`); return r.data; }
async function check(label, fn) { await fn(); passed++; console.log(`PASS ${label}`); }
(async () => {
  assert.equal(new URL(BASE).hostname, 'localhost');
  const owner = (await ok('POST', '/auth/login', null, { email: 'owner@classroom.local', password: 'Password123!' })).token;
  const admin = (await ok('POST', '/auth/login', null, { email: 'admin@classroom.local', password: 'Password123!' })).token;
  async function user(label) { const email = `r23.${label}.${run}@example.com`; const data = await ok('POST', '/auth/register', null, { email, password: PASSWORD, fullName: `R23 ${label}` }); return { token: data.token, id: data.userId, email }; }
  const a = await user('a'), b = await user('b');
  const classroom = await ok('POST', '/classes', owner, { title: `R23 ${run}`, slug: `r23-${run}`, visibility: 'PRIVATE' });
  const id = classroom.id;
  await check('private class and About concealed from anonymous and outsider', async () => {
    for (const token of [null, a.token]) for (const route of [`/classes/${id}`, `/classes/slug/${classroom.slug}`, `/classes/${id}/about`]) assert.equal((await api('GET', route, token)).status, 404);
  });
  await check('forged invite cannot join', async () => assert.equal((await api('POST', '/classes/invites/r23-invalid-secret/join', a.token)).status, 404));
  const invite = await ok('POST', `/classes/${id}/invites`, owner, { maxUses: 1 });
  await check('one-use invite resists concurrent joins', async () => {
    const results = await Promise.all([a, b].map(u => api('POST', `/classes/invites/${invite.code}/join`, u.token)));
    assert.equal(results.filter(r => r.status === 200).length, 1);
    assert.equal(results.filter(r => r.status === 404).length, 1);
  });
  const paidClass = await ok('PUT', `/classes/${id}/access`, owner, { accessType: 'PAID', price: 199000, currency: 'VND', durationDays: 30 });
  const c = await user('buyer');
  await check('outsider cannot change price or create invite', async () => {
    assert.ok([403, 404].includes((await api('PUT', `/classes/${id}/access`, c.token, { accessType: 'FREE' })).status));
    assert.ok([403, 404].includes((await api('POST', `/classes/${id}/invites`, c.token, {})).status));
  });
  const paidInvite = await ok('POST', `/classes/${id}/invites`, owner, {});
  const preview = await ok('GET', `/classes/invites/${paidInvite.code}`, null);
  const product = preview.classAccessProduct || preview.product;
  const products = await ok('GET', `/classes/${id}/studio/products`, owner);
  const access = paidClass.accessProduct || product || products.find(p => p.kind === 'CLASS_ACCESS');
  assert.ok(access.id, 'class access product');
  const orderBody = { classId: id, productId: access.id, idempotencyKey: `r23-${run}`, inviteCode: paidInvite.code };
  await check('private class purchase requires invite', async () => assert.equal((await api('POST', '/orders', c.token, { ...orderBody, inviteCode: undefined })).status, 404));
  const order = await ok('POST', '/orders', c.token, orderBody);
  await check('duplicate purchase returns same order', async () => assert.equal((await ok('POST', '/orders', c.token, orderBody)).id, order.id));
  await check('buyer cannot simulate settlement or refund', async () => {
    for (const eventType of ['PAYMENT_SUCCESS', 'PAYMENT_REFUNDED']) assert.equal((await api('POST', '/payments/mock/simulate', c.token, { orderNumber: order.orderNumber, eventType })).status, 403);
  });
  await check('forged payment webhook rejected', async () => { const r = await api('POST', '/payments/MOCK/webhook', null, { orderNumber: order.orderNumber, eventType: 'PAYMENT_SUCCESS', amount: 199000, currency: 'VND' }, { 'X-Signature': 'forged' }); assert.equal(r.error?.code, 'INVALID_WEBHOOK_SIGNATURE', `status=${r.status}, error=${JSON.stringify(r.error)}`); });
  await ok('POST', '/payments/mock/simulate', owner, { orderNumber: order.orderNumber, eventType: 'PAYMENT_SUCCESS' });
  await check('paid membership granted', async () => assert.equal((await api('GET', `/classes/${id}/members`, c.token)).status, 200));
  await ok('POST', '/payments/mock/simulate', owner, { orderNumber: order.orderNumber, eventType: 'PAYMENT_REFUNDED' });
  await check('refund revokes member content', async () => assert.equal((await api('GET', `/classes/${id}/members`, c.token)).status, 403));
  const about = await ok('GET', `/classes/${id}/about`, owner);
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a6XkAAAAASUVORK5CYII=', 'base64');
  const upload = await ok('POST', `/classes/${id}/media/upload-intents`, owner, { filename: 'r23.png', mimeType: 'image/png', sizeBytes: png.length, purpose: 'ABOUT' });
  assert.ok((await fetch(upload.uploadUrl, { method: 'PUT', body: png, headers: { 'Content-Type': 'image/png' } })).ok);
  await ok('POST', `/media/${upload.assetId}/complete`, owner);
  const sections = [{ title: 'Tổng quan', contentMarkdown: '<script>window.pwned=1</script>', mediaAssetId: upload.assetId, imageAlt: 'Ảnh kiểm thử lớp' }, { title: 'Lộ trình', contentMarkdown: 'Học theo từng bài', imageUrl: '', imageAlt: '' }];
  const saved = await ok('PUT', `/classes/${id}/about`, owner, { contentMarkdown: 'R23', sections, publishedVersion: about.publishedVersion });
  await check('uploaded About image downloads and stale edits conflict', async () => {
    assert.equal((await fetch(saved.sections[0].imageUrl)).status, 200);
    assert.equal((await api('PUT', `/classes/${id}/about`, owner, { sections, publishedVersion: about.publishedVersion })).status, 409);
    assert.equal((await api('PUT', `/classes/${id}/about`, owner, { sections: [{ ...sections[0], mediaAssetId: null, imageUrl: 'javascript:alert(1)' }] })).status, 400);
  });
  await check('privacy export requires password and excludes credentials and exam snapshots', async () => {
    assert.equal((await api('POST', '/privacy/me/export', c.token, { password: 'wrong' })).status, 401);
    const r = await api('POST', '/privacy/me/export', c.token, { password: PASSWORD }); assert.equal(r.status, 200);
    assert.match(r.headers.get('cache-control'), /no-store/);
    assert.equal(r.data.profile.id, c.id);
    assert.doesNotMatch(JSON.stringify(r.data), /password_hash|questions_snapshot|grading_snapshot|answer_key|refresh_token/);
    assert.equal(r.data.orders.length, 1);
  });
  await check('privacy admin endpoints deny ordinary user', async () => {
    assert.equal((await api('GET', '/privacy/requests', c.token)).status, 403);
    assert.equal((await api('PUT', `/privacy/requests/${a.id}`, c.token, { status: 'COMPLETED', resolution: 'attack' })).status, 403);
  });
  await check('deletion request idempotent and closure revokes existing JWT', async () => {
    const req = await ok('POST', '/privacy/me/deletion-requests', c.token, { password: PASSWORD, reason: 'R23 test closure' });
    assert.equal((await ok('POST', '/privacy/me/deletion-requests', c.token, { password: PASSWORD })).id, req.id);
    await ok('PUT', `/privacy/requests/${c.id}`, admin, { status: 'COMPLETED', resolution: 'R23: close test account; preserve refunded financial record.' });
    assert.equal((await api('GET', '/privacy/me/requests', c.token)).status, 401);
  });
  const browser = await launchBrowser(); const ctx = await newCtx(browser); const page = await ctx.newPage();
  await page.goto(`${BASE}/login`); await page.getByLabel('Email').fill('owner@classroom.local'); await page.getByLabel('Mật khẩu', { exact: true }).fill('Password123!'); await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click(); await page.waitForURL(/\/classes$/);
  await page.goto(`${BASE}/classes/${classroom.slug}/about`);
  await check('About tabs keyboard, uploaded image and HTML escape', async () => {
    const tab = page.getByRole('tab', { name: 'Tổng quan' }); await tab.waitFor(); await tab.focus(); await page.keyboard.press('ArrowRight'); assert.equal(await page.getByRole('tab', { name: 'Lộ trình' }).getAttribute('aria-selected'), 'true');
    await page.keyboard.press('Home'); await page.getByAltText('Ảnh kiểm thử lớp').waitFor(); assert.equal(await page.evaluate(() => window.pwned), undefined);
    await page.getByRole('tabpanel').filter({ visible: true }).getByText('<script>window.pwned=1</script>', { exact: true }).waitFor();
  });
  const dir = path.join(SHOTS_ROOT, `r23-${run}`); fs.mkdirSync(dir, { recursive: true }); await page.screenshot({ path: path.join(dir, 'about-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 }); await page.screenshot({ path: path.join(dir, 'about-mobile.png'), fullPage: true });
  await check('About at 200% equivalent width and mobile has no horizontal overflow', async () => assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)));
  await browser.close(); console.log(`ROUND23 ${passed} checks passed`);
})().catch(e => { console.error(e); process.exitCode = 1; });
