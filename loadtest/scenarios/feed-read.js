'use strict';
// Kịch bản đọc: V phiên học viên cùng duyệt lớp (bảng tin trang 1-2, lớp theo slug, kỳ thi, xếp hạng, thành viên) trong D giây,
// suy nghĩ T ms giữa các yêu cầu; kèm 1 chủ lớp đọc danh sách thành viên Studio. Cần lớp có nội dung: `seed.js --posts 30 --comments 20`
// (hoặc loadtest/sql/seed-bulk.sql cho lớp lớn 2000 thành viên / 40 000 bình luận).
//   node scenarios/feed-read.js --sessions 100 --duration 30 --think 1000
// Ngưỡng: --max-feed-p95 300 --max-read-p95 500
const {
  API, assertSafeTarget, args, num, log, req, summary, sleep, saveState, loadState, checkThresholds,
} = require('../lib');
const { ensureTokens } = require('../fixtures');

async function run(opts = {}) {
  const a = { ...args(), ...opts };
  const V = num(a.sessions, 100), D = num(a.duration, 30), T = num(a.think, 1000);
  const thresholds = { feedP95: num(a['max-feed-p95'], 300), readP95: num(a['max-read-p95'], 500) };
  const state = loadState();
  await ensureTokens(state, saveState);
  const sessions = state.students.slice(0, V);
  const cls = state.classId;
  const endpoints = [
    ['bảng tin trang 1', `/classes/${cls}/posts`],
    ['lớp theo slug', `/classes/slug/${state.slug}`],
    ['danh sách kỳ thi', `/classes/${cls}/exams`],
    ['xếp hạng', `/classes/${cls}/leaderboard`],
    ['danh sách thành viên', `/classes/${cls}/members`],
    ['bảng tin trang 2', null],
  ];
  const results = Object.fromEntries([...endpoints.map(([l]) => [l, []]), ['Studio: thành viên (chủ lớp)', []]]);
  const endAt = Date.now() + D * 1000;
  await Promise.all([
    ...sessions.map(async (s, i) => {
      await sleep(Math.random() * T);
      let k = i % endpoints.length; let cursor = null;
      while (Date.now() < endAt) {
        const [label, p] = endpoints[k % endpoints.length];
        const path = p || `/classes/${cls}/posts${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`;
        const r = await req('GET', API + path, { token: s.token });
        if (label === 'bảng tin trang 1' && r.json && r.json.data) cursor = r.json.data.nextCursor;
        results[label].push(r);
        k++;
        if (T) await sleep(T * (0.5 + Math.random()));
      }
    }),
    (async () => {
      while (Date.now() < endAt) {
        results['Studio: thành viên (chủ lớp)'].push(await req('GET', `${API}/classes/${cls}/studio/members`, { token: state.owner.token }));
        await sleep(2000);
      }
    })(),
  ]);
  const report = { sessions: V, durationS: D, thinkMs: T, thresholds, endpoints: {} };
  const all = [];
  for (const [label, rs] of Object.entries(results)) {
    if (!rs.length) continue;
    report.endpoints[label] = summary(label, rs);
    all.push(...rs);
    log(JSON.stringify(report.endpoints[label]));
  }
  report.all = summary('tất cả lượt đọc', all);
  log(JSON.stringify(report.all));
  const feed = report.endpoints['bảng tin trang 1'];
  const failures = checkThresholds([
    { name: 'số lỗi 5xx/mạng', value: all.filter((r) => r.status === 0 || r.status >= 500).length, equals: 0 },
    ...(feed ? [{ name: 'bảng tin trang 1: p95 (ms)', value: feed.p95, max: thresholds.feedP95 }] : []),
    { name: 'mọi lượt đọc: p95 (ms)', value: report.all.p95, max: thresholds.readP95 },
  ]);
  report.failures = failures;
  if (a.json && typeof a.json === 'string') require('fs').writeFileSync(a.json, JSON.stringify(report, null, 2));
  return report;
}

module.exports = { run };

if (require.main === module) {
  assertSafeTarget();
  run().then((r) => process.exit(r.failures.length ? 1 : 0))
    .catch((e) => { console.error('feed-read lỗi:', e.message); process.exit(1); });
}
