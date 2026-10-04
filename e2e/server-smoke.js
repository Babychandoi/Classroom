'use strict';
// HTTPS smoke on this PC, with a separate CA-verified Node client and an isolated Chrome profile.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const https = require('node:https');
const { chromium } = require('playwright');
const accounts = JSON.parse(fs.readFileSync('../.artifacts/server/accounts.json', 'utf8'));
const ca = fs.readFileSync('../.artifacts/server/classroom-root.crt');
const origin = 'https://192.168.1.7';
function request(route, { method = 'GET', body, token } = {}) {
  return new Promise((resolve, reject) => {
    const req = https.request(origin + route, { ca, method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) } }, res => {
      let text = ''; res.on('data', b => text += b); res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, json: JSON.parse(text) }));
    }); req.on('error', reject); if (body) req.write(JSON.stringify(body)); req.end();
  });
}
(async () => {
  const health = await request('/api/v1/health/readiness'); assert.equal(health.status, 200); assert.equal(health.json.status, 'UP'); console.log('PASS HTTPS certificate chain and readiness');
  const account = accounts.find(a => a.email === 'owner@classroom.local');
  const login = await request('/api/v1/auth/login', { method: 'POST', body: account }); assert.equal(login.status, 200);
  const cookie = login.headers['set-cookie'].join(';'); assert.ok(/Secure/.test(cookie), 'refresh cookie must be Secure'); assert.ok(/HttpOnly/.test(cookie), 'refresh cookie must be HttpOnly'); console.log('PASS secure HttpOnly refresh cookie');
  assert.equal((await request('/api/v1/payments/sandbox-status')).status, 404); console.log('PASS sandbox disabled on server');
  const token = login.json.data.token;
  const ports = (process.env.SERVER_BACKEND_PORTS || '8080').split(',').map(Number);
  assert.ok(ports.length > 0 && ports.every(p => p === 8080 || p === 28080), 'only configured local backend ports may be tested');
  for (const port of ports) { const r = await fetch(`http://localhost:${port}/api/v1/privacy/me/requests`, { headers: { Authorization: `Bearer ${token}` } }); assert.equal(r.status, 200); } console.log(`PASS JWT accepted by ${ports.length} configured backend(s)`);
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  // Windows trusts the public internal CA installed by enable-lan-access.ps1; Chrome must verify it.
  const ctx = await browser.newContext({ locale: 'vi-VN', viewport: { width: 1366, height: 900 } });
  const page = await ctx.newPage(); const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.goto(origin + '/login'); await page.getByLabel('Email').fill(account.email); await page.getByLabel('Mật khẩu', { exact: true }).fill(account.password); await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click(); await page.waitForURL(/\/classes$/);
  await page.reload(); await page.getByRole('button', { name: 'Mở menu tài khoản' }).waitFor(); assert.ok((await ctx.cookies()).some(c => c.secure && c.httpOnly)); console.log('PASS Chrome login and refresh through HTTPS load balancer');
  await page.goto(origin + '/me/profile'); await page.getByText('Dữ liệu cá nhân', { exact: false }).first().waitFor();
  fs.mkdirSync('../.artifacts/server', { recursive: true }); await page.screenshot({ path: '../.artifacts/server/profile-https.png', fullPage: true });
  assert.equal(errors.length, 0); await browser.close(); console.log('SERVER SMOKE PASSED');
})().catch(e => { console.error(e.message); process.exitCode = 1; });
