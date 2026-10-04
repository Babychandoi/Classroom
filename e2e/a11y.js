'use strict';
// Quét trợ năng bằng axe-core (WCAG 2.0/2.1 A + AA) trên các trang chính, bằng trình duyệt thật.
//   - lỗi mức "critical"  -> bước HỎNG (làm hỏng cả bộ);
//   - lỗi mức "serious"   -> in cảnh báo kèm phần tử; chỉ làm hỏng bước khi đặt A11Y_STRICT=1.
// Bước tùy chọn: nếu chưa cài axe-core (npm install trong e2e/) thì bỏ qua (SKIP), không làm hỏng cả bộ.
// Đọc dữ liệu của lần chạy e2e.js gần nhất (shots/last-run.json).
const fs = require('fs');
const path = require('path');
const { BASE, SHOTS_ROOT, log, newRunId, launchBrowser, newCtx, createRunner, settle, login, loadState } = require('./lib');

let AXE_SOURCE;
try {
  AXE_SOURCE = fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');
} catch {
  console.log('SKIP a11y.js: axe-core chưa được cài (chạy `npm install` trong e2e/). Bước này là tùy chọn.');
  process.exit(0);
}

const S = loadState();
if (!S.classId) { console.error('Thiếu classId (shots/last-run.json hoặc E2E_CLASS_ID).'); process.exit(2); }
const STRICT = process.env.A11Y_STRICT === '1';
const RUN = newRunId();
const { step, shot, summarize, instrument } = createRunner(path.join(SHOTS_ROOT, `a11y-${RUN}`));
const report = [];

async function scan(page, label) {
  await page.evaluate(AXE_SOURCE);
  const violations = await page.evaluate(async () => {
    // eslint-disable-next-line no-undef
    const res = await axe.run(document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] } });
    return res.violations.map((v) => ({
      id: v.id, impact: v.impact, help: v.help, count: v.nodes.length,
      nodes: v.nodes.slice(0, 3).map((n) => ({ target: n.target.join(' '), summary: (n.failureSummary || '').split('\n').slice(0, 2).join(' ').slice(0, 200) })),
    }));
  });
  const critical = violations.filter((v) => v.impact === 'critical');
  const serious = violations.filter((v) => v.impact === 'serious');
  report.push({ label, critical: critical.length, serious: serious.length, violations });
  for (const v of [...critical, ...serious]) {
    log(`  [${v.impact}] ${label}: ${v.id} x${v.count} - ${v.help} :: ${v.nodes.map((n) => n.target).join(' ; ')}`);
  }
  if (critical.length) throw new Error(`critical: ${critical.map((v) => `${v.id}(${v.count})`).join(', ')}`);
  if (STRICT && serious.length) throw new Error(`serious (A11Y_STRICT=1): ${serious.map((v) => `${v.id}(${v.count})`).join(', ')}`);
  return serious.length ? `serious=${serious.map((v) => `${v.id}(${v.count})`).join(', ')} (cảnh báo)` : 'không có lỗi serious/critical';
}

(async () => {
  const browser = await launchBrowser();
  log('browser', browser.version(), 'class', S.classSlug, 'base', BASE);
  const anonCtx = await newCtx(browser);
  const anon = await anonCtx.newPage();
  instrument(anon, 'anon');
  const stuCtx = await newCtx(browser);
  const stu = await stuCtx.newPage();
  instrument(stu, 'student');
  const ownCtx = await newCtx(browser);
  const own = await ownCtx.newPage();
  instrument(own, 'owner');

  await step('A0', 'log in student and owner', stu, async () => { await login(stu, S.student); await login(own, S.owner); });

  await step('A1', 'axe: /login', anon, async () => { await anon.goto(`${BASE}/login`); await settle(anon, 400); return scan(anon, 'login'); });
  await step('A1', 'axe: privacy policy', anon, async () => { await anon.goto(`${BASE}/privacy`); await settle(anon, 400); return scan(anon, 'privacy'); });
  await step('A1', 'axe: /classes (guest)', anon, async () => { await anon.goto(`${BASE}/classes`); await settle(anon, 600); return scan(anon, 'classes'); });
  await step('A1', 'axe: class feed (guest)', anon, async () => { await anon.goto(`${BASE}/classes/${S.classSlug}/feed`); await settle(anon, 600); return scan(anon, 'guest-feed'); });
  await step('A1', 'axe: sign-in prompt on a member-only tab (guest)', anon, async () => { await anon.goto(`${BASE}/classes/${S.classSlug}/learn`); await anon.getByRole('heading', { name: 'Đăng nhập để xem nội dung này' }).waitFor(); return scan(anon, 'guest-learn-prompt'); });
  await step('A2', 'axe: class feed (member)', stu, async () => { await stu.goto(`${BASE}/classes/${S.classSlug}/feed`); await settle(stu, 600); return scan(stu, 'member-feed'); });
  await step('A2', 'axe: Khóa học (member)', stu, async () => { await stu.goto(`${BASE}/classes/${S.classSlug}/learn`); await stu.locator('button[aria-pressed]').first().waitFor({ timeout: 10000 }); await settle(stu, 400); return scan(stu, 'member-learn'); });
  await step('A2', 'axe: Thi (member)', stu, async () => { await stu.goto(`${BASE}/classes/${S.classSlug}/exams`); await settle(stu, 800); return scan(stu, 'member-exams'); });
  await step('A2', 'axe: hồ sơ của tôi', stu, async () => { await stu.goto(`${BASE}/me/profile`); await settle(stu, 400); return scan(stu, 'my-profile'); });
  await step('A3', 'axe: Studio khóa học (owner)', own, async () => { await own.goto(`${BASE}/studio/classes/${S.classId}/courses`); await settle(own, 800); return scan(own, 'studio-courses'); });
  await step('A3', 'axe: Studio cài đặt lớp (owner)', own, async () => { await own.goto(`${BASE}/studio/classes/${S.classId}/settings`); await settle(own, 600); return scan(own, 'studio-settings'); });
  await step('A3', 'axe: Studio nhân sự (owner)', own, async () => { await own.goto(`${BASE}/studio/classes/${S.classId}/staff`); await settle(own, 800); return scan(own, 'studio-staff'); });
  await step('A3', 'axe: Studio nhân sự với hộp thoại phân quyền mở', own, async () => {
    await own.getByRole('button', { name: 'Thêm trợ giảng mới' }).click();
    await own.locator('[role=dialog]').waitFor();
    await shot(own, 'A3-staff-dialog');
    return scan(own, 'studio-staff-dialog');
  });

  await step('A3', 'axe: tạo lớp học /classes/new (owner, cả bản xem trước điện thoại)', own, async () => {
    await own.goto(`${BASE}/classes/new`);
    await own.getByRole('heading', { name: 'Tạo lớp học', level: 1 }).waitFor({ timeout: 10000 });
    await settle(own, 400);
    const desktop = await scan(own, 'create-class');
    await own.getByRole('button', { name: 'Xem trên điện thoại' }).click();
    await own.getByTestId('preview-mobile').waitFor();
    const phone = await scan(own, 'create-class-phone-preview');
    return `${desktop}; xem trước điện thoại: ${phone}`;
  });

  // A11Y_ALL=1: every remaining Studio page and class tab (slower; used to sweep the whole app).
  if (process.env.A11Y_ALL === '1') {
    for (const p of ['overview', 'exams', 'grading', 'leaderboard', 'members', 'segments', 'store', 'audit', 'feed', 'blog', 'events', 'documents', 'about']) {
      await step('A4', `axe: Studio ${p}`, own, async () => { await own.goto(`${BASE}/studio/classes/${S.classId}/${p}`); await settle(own, 800); return scan(own, `studio-${p}`); });
    }
    for (const t of ['blog', 'events', 'leaderboard', 'documents', 'members', 'about', 'store']) {
      await step('A5', `axe: class tab ${t} (member)`, stu, async () => { await stu.goto(`${BASE}/classes/${S.classSlug}/${t}`); await settle(stu, 800); return scan(stu, `member-${t}`); });
    }
  }

  fs.writeFileSync(path.join(SHOTS_ROOT, `a11y-${RUN}`, 'axe-report.json'), JSON.stringify(report, null, 2));
  console.log('\nTóm tắt axe (critical / serious):');
  for (const r of report) console.log(`  ${r.label.padEnd(24)} ${r.critical} / ${r.serious}`);

  await browser.close();
  process.exitCode = summarize('Trợ năng axe-core (a11y.js)');
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
