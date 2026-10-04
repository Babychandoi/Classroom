'use strict';
// Đếm request + lỗi console của trang sau khi tải, để phát hiện vòng lặp fetch/polling, lỗi CSP và host ngoài.
//   node netwatch.js [owner|student] [/đường-dẫn ...]
// Mặc định (owner): bảng xếp hạng lớp, tab Shop của lớp, Shop & đơn hàng trong Studio.
// Mỗi đường dẫn PASS khi:
//   - không có vi phạm CSP / lỗi console (trừ nhiễu đã biết - xem unexpectedEvents trong lib.js);
//   - không request nào lặp > 3 lần trong cửa sổ theo dõi; POST /auth/refresh, GET /me và
//     GET /payments/sandbox-status tối đa 1 lần (R17-01, R17-06);
//   - trang không gọi host ngoài (font/CDN) và font Inter tự host đã được nạp và đang áp dụng (R17-04).
const path = require('path');
const { BASE, SHOTS_ROOT, log, newRunId, launchBrowser, newCtx, createRunner, login, loadState, unexpectedEvents } = require('./lib');

const S = loadState();
const args = process.argv.slice(2);
const who = args[0] === 'student' ? 'student' : 'owner';
const targets = args.filter((a) => a.startsWith('/'));
if (!targets.length) {
  targets.push(`/classes/${S.classSlug}/leaderboard`, `/classes/${S.classSlug}/store`);
  if (S.classId) targets.push(`/studio/classes/${S.classId}/store`);
}
const WINDOW_MS = Number(process.env.NETWATCH_MS || 6000);
const MAX_REPEATS = 3;
const AT_MOST_ONCE = ['POST /api/v1/auth/refresh', 'GET /api/v1/me', 'GET /api/v1/payments/sandbox-status'];

const { step, events, summarize, instrument } = createRunner(path.join(SHOTS_ROOT, `netwatch-${newRunId()}`));

(async () => {
  const browser = await launchBrowser();
  log('browser', browser.version(), who, 'base', BASE);
  const ctx = await newCtx(browser);
  const page = await ctx.newPage();
  instrument(page, 'netwatch');

  await step('N0', `login as ${who}`, page, async () => { await login(page, S[who]); });

  const origin = new URL(BASE).origin;
  for (const target of targets) {
    await step('N1', `watch ${target} for ${WINDOW_MS}ms`, page, async () => {
      const requests = [];
      const external = new Set();
      const before = events.length;
      const onRequest = (r) => {
        requests.push(`${r.method()} ${r.url().replace(BASE, '').replace(/\?.*/, '').slice(0, 120)}`);
        const u = new URL(r.url());
        // MinIO (cổng 9000) là host ngoài hợp lệ cho media/tài liệu có chữ ký sẵn; mọi host khác là bất thường.
        if (u.origin !== origin && !/^(data|blob):$/.test(u.protocol) && u.port !== (process.env.E2E_MINIO_PORT || '9000')) external.add(u.origin);
      };
      page.on('request', onRequest);
      try {
        await page.goto(`${BASE}${target}`);
        await page.waitForTimeout(WINDOW_MS);
      } finally {
        page.off('request', onRequest);
      }

      const counts = {};
      for (const key of requests) counts[key] = (counts[key] || 0) + 1;
      const top = Object.entries(counts).sort((a, b) => b[1] - a[1]).slice(0, 6);
      console.log(`  ${target}: ${requests.length} requests`);
      for (const [key, n] of top) console.log(`   ${String(n).padStart(3)}x ${key}`);

      const problems = [];
      const repeated = Object.entries(counts).filter(([, n]) => n > MAX_REPEATS);
      if (repeated.length) problems.push(`lặp quá ${MAX_REPEATS} lần: ${repeated.map(([k, n]) => `${n}x ${k}`).join('; ')}`);
      for (const key of AT_MOST_ONCE) if ((counts[key] || 0) > 1) problems.push(`${counts[key]}x ${key} (mong đợi tối đa 1)`);

      const bad = unexpectedEvents(events, before).filter((e) => e.kind === 'console.error' || e.kind === 'pageerror' || e.kind.startsWith('http.'));
      if (bad.length) problems.push(`sự kiện bất thường: ${bad.slice(0, 3).map((e) => `${e.kind} ${e.text.slice(0, 140)}`).join(' | ')}`);
      if (external.size) problems.push(`gọi host ngoài: ${[...external].join(', ')}`);

      const font = await page.evaluate(async () => {
        await document.fonts.ready;
        const loaded = Array.from(document.fonts).filter((f) => f.status === 'loaded').map((f) => f.family.replace(/"/g, ''));
        return { loaded, body: getComputedStyle(document.body).fontFamily };
      });
      if (!font.loaded.includes('Inter Variable')) problems.push(`font tự host chưa được nạp (loaded=${font.loaded.join(',') || 'none'})`);
      if (!font.body.includes('Inter Variable')) problems.push(`body không dùng font tự host: ${font.body.slice(0, 80)}`);

      if (problems.length) throw new Error(problems.join(' || '));
      return `${requests.length} requests; font=${font.loaded.join(',')}`;
    });
  }

  await browser.close();
  process.exitCode = summarize('Theo dõi mạng / CSP / font (netwatch.js)');
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
