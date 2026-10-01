'use strict';
// Kiểm tra bổ sung trên dữ liệu của lần chạy e2e.js gần nhất (đọc shots/last-run.json):
//   F1  Studio: thêm chương khi khóa đang mở -> hiện ngay; sắp xếp lại chương (R17-02, R13)
//   F2  Studio: lịch thi round-trip - không lệch múi giờ khi lưu không sửa, xóa lịch được (R16-04/R16-06)
//   F3  Bảng xếp hạng lọc theo kỳ thi (R16)
//   F4  Danh sách lớp phân trang "Xem thêm lớp học" (R16-08) - chỉ kiểm được khi có > 50 lớp
//       (đặt E2E_PAGING_FILL=1 để tự tạo thêm lớp cho đến khi vượt 50: chậm, ~1 phút).
const path = require('path');
const { BASE, SHOTS_ROOT, log, newRunId, launchBrowser, newCtx, createRunner, login, loadState, unexpectedEvents } = require('./lib');

const S = loadState();
if (!S.classId) { console.error('Thiếu classId (shots/last-run.json hoặc E2E_CLASS_ID).'); process.exit(2); }
const RUN = newRunId();
const { step, shot, events, summarize, instrument } = createRunner(path.join(SHOTS_ROOT, `follow-${RUN}`));

const H4 = 'h4';
const SECTION_PLACEHOLDER = 'Tên chương học mới (VD: Chương 1: Giới thiệu)...';

(async () => {
  const browser = await launchBrowser();
  log('browser', browser.version(), 'class', S.classSlug, 'base', BASE);
  const ctx = await newCtx(browser);
  const page = await ctx.newPage();
  instrument(page, 'follow');
  const dialogs = () => events.filter((e) => e.kind.startsWith('dialog.')).map((e) => e.text);

  await step('F0', 'login as the class owner', page, async () => { await login(page, S.owner); });

  // ---- F1: chương ----
  const card = () => page.locator('div.p-6', { has: page.locator('h3', { hasText: S.courseTitle }) }).first();
  await step('F1', 'new section appears at once in the expanded course', page, async () => {
    await page.goto(`${BASE}/studio/classes/${S.classId}/courses`);
    await card().getByRole('button', { name: 'Xem bài học' }).click();
    await page.locator(H4).first().waitFor();
    const title = `Chuong 2 ${newRunId()}`;
    await card().getByRole('button', { name: 'Thêm chương' }).click();
    await page.getByPlaceholder(SECTION_PLACEHOLDER).fill(title);
    await page.getByRole('button', { name: 'Lưu chương' }).click();
    await page.locator(H4, { hasText: title }).waitFor({ timeout: 5000 });
    S.section2 = title;
    return title;
  });
  await step('F1', 'new section lands after the existing ones and survives collapse/expand', page, async () => {
    const order = async () => (await card().locator(H4).allInnerTexts()).map((t) => t.trim());
    const before = await order();
    if (before[before.length - 1] !== S.section2) throw new Error(`new section is not last: ${before.join(' | ')}`);
    await card().getByRole('button', { name: 'Ẩn nội dung' }).click();
    await card().getByRole('button', { name: 'Xem bài học' }).click();
    await page.locator(H4, { hasText: S.section2 }).waitFor();
    const after = await order();
    if (after.join('|') !== before.join('|')) throw new Error(`order changed after re-expand: ${before.join(' | ')} -> ${after.join(' | ')}`);
    return after.join(' | ');
  });
  await step('F1', 'reorder sections (move new one up)', page, async () => {
    const order = async () => (await card().locator(H4).allInnerTexts()).map((t) => t.trim());
    const before = await order();
    await page.getByRole('button', { name: `Chuyển chương "${S.section2}" lên trên` }).click();
    await page.waitForFunction(
      (first) => document.querySelector('h4') && document.querySelector('h4').textContent.includes(first),
      S.section2,
      { timeout: 5000 },
    );
    const after = await order();
    if (after[0] !== S.section2) throw new Error(`reorder did not move it first: ${before.join(' | ')} -> ${after.join(' | ')}`);
    await shot(page, 'F1-sections-reorder');
    return `${before.join(' | ')} -> ${after.join(' | ')}`;
  });

  // ---- F2: lịch thi ----
  const examTitle = `Sched ${newRunId()}`;
  const ecard = () => page.locator('div.p-5', { has: page.locator('h3', { hasText: examTitle }) }).first();
  const created = {};
  await step('F2', 'create an exam with a schedule; API returns the same instants', page, async () => {
    await page.goto(`${BASE}/studio/classes/${S.classId}/exams`);
    await page.getByRole('button', { name: 'Tạo kỳ thi mới' }).click();
    await page.getByLabel('Tên kỳ thi').fill(examTitle);
    await page.getByLabel('Bắt đầu mở đề (Tùy chọn)').fill('2026-12-01T15:00');
    await page.getByLabel('Đóng đề thi (Tùy chọn)').fill('2026-12-01T17:30');
    const [resp] = await Promise.all([
      page.waitForResponse((r) => r.request().method() === 'POST' && /\/classes\/[^/]+\/exams$/.test(r.url())),
      page.getByRole('button', { name: 'Tạo kỳ thi', exact: true }).click(),
    ]);
    const data = (await resp.json()).data;
    created.start = data.scheduleStart;
    created.end = data.scheduleEnd;
    // Trình duyệt chạy ở Asia/Ho_Chi_Minh (UTC+7): 15:00 địa phương = 08:00Z.
    if (!/^2026-12-01T08:00/.test(created.start) || !/^2026-12-01T10:30/.test(created.end)) {
      throw new Error(`unexpected instants ${created.start} / ${created.end}`);
    }
    await ecard().waitFor();
    return `${created.start} -> ${created.end}`;
  });
  await step('F2', 'edit form prefills the local wall clock and an untouched save keeps the schedule', page, async () => {
    await ecard().getByRole('button', { name: 'Sửa cấu hình' }).click();
    const start = await page.getByLabel('Bắt đầu mở đề (Tùy chọn)').inputValue();
    const end = await page.getByLabel('Đóng đề thi (Tùy chọn)').inputValue();
    if (start !== '2026-12-01T15:00' || end !== '2026-12-01T17:30') throw new Error(`prefill ${start} / ${end}`);
    const [resp] = await Promise.all([
      page.waitForResponse((r) => r.request().method() === 'PUT' && /\/exams\/[^/]+$/.test(r.url())),
      page.getByRole('button', { name: 'Lưu thay đổi' }).click(),
    ]);
    const data = (await resp.json()).data;
    if (data.scheduleStart !== created.start || data.scheduleEnd !== created.end) {
      throw new Error(`schedule shifted: ${created.start} -> ${data.scheduleStart}, ${created.end} -> ${data.scheduleEnd}`);
    }
  });
  await step('F2', 'clearing both dates removes the schedule', page, async () => {
    await ecard().getByRole('button', { name: 'Sửa cấu hình' }).click();
    await page.getByLabel('Bắt đầu mở đề (Tùy chọn)').fill('');
    await page.getByLabel('Đóng đề thi (Tùy chọn)').fill('');
    const [resp] = await Promise.all([
      page.waitForResponse((r) => r.request().method() === 'PUT' && /\/exams\/[^/]+$/.test(r.url())),
      page.getByRole('button', { name: 'Lưu thay đổi' }).click(),
    ]);
    const data = (await resp.json()).data;
    if (data.scheduleStart || data.scheduleEnd) throw new Error(`schedule not cleared: ${data.scheduleStart} / ${data.scheduleEnd}`);
  });

  // ---- F3: xếp hạng theo kỳ thi ----
  await step('F3', 'per-exam leaderboard lists the graded student', page, async () => {
    await page.goto(`${BASE}/classes/${S.classSlug}/leaderboard`);
    const select = page.locator('select').first();
    await select.waitFor({ timeout: 10000 });
    // The exam options arrive from a separate GET /classes/{id}/exams after the <select> is already on screen (it starts
    // with just the class-wide option), so poll for the option instead of reading the list once.
    let options = [];
    let target;
    for (let i = 0; i < 20 && !target; i++) {
      options = await select.locator('option').allInnerTexts();
      target = options.find((o) => o.includes(S.examTitle));
      if (!target) await page.waitForTimeout(400);
    }
    if (!target) throw new Error(`no option for ${S.examTitle}; options=${options.join('|')}`);
    await select.selectOption({ label: target });
    await page.locator('main').getByText(S.student.name).first().waitFor({ timeout: 8000 });
  });

  // ---- F4: phân trang danh sách lớp ----
  await step('F4', 'classes list pages with "Xem thêm lớp học" without duplicates', page, async () => {
    const links = page.getByRole('link', { name: 'Vào lớp' });
    const more = page.getByRole('button', { name: 'Xem thêm lớp học' });
    if (process.env.E2E_PAGING_FILL === '1') {
      await page.goto(`${BASE}/classes`);
      await page.waitForTimeout(1500);
      let total = await links.count();
      while (total <= 50 && !(await more.isVisible())) {
        const slug = `e2e-page-${newRunId()}-${total}`;
        await page.goto(`${BASE}/classes`);
        await page.getByRole('button', { name: 'Tạo lớp học mới' }).click();
        await page.getByLabel('Tên lớp học').fill(`E2E Paging ${slug}`);
        await page.getByLabel('Đường dẫn slug (URL)').fill(slug);
        await page.getByRole('button', { name: 'Xác nhận tạo lớp' }).click();
        await page.waitForURL(new RegExp(`/classes/${slug}/feed`));
        await page.goto(`${BASE}/classes`);
        await page.waitForTimeout(800);
        total = await links.count();
      }
    }
    await page.goto(`${BASE}/classes`);
    await links.first().waitFor({ timeout: 10000 });
    await page.waitForTimeout(800);
    const first = await links.count();
    if (!(await more.isVisible())) {
      return `SKIP: chỉ có ${first} lớp (< 50), chưa có trang thứ hai để kiểm (đặt E2E_PAGING_FILL=1 để tự tạo thêm)`;
    }
    await more.click();
    await page.waitForFunction((n) => Array.from(document.querySelectorAll('a')).filter((a) => a.textContent.includes('Vào lớp')).length > n, first, { timeout: 8000 });
    const hrefs = await links.evaluateAll((els) => els.map((e) => e.getAttribute('href')));
    const dups = hrefs.length - new Set(hrefs).size;
    if (dups) throw new Error(`${dups} duplicate classes after "Xem thêm"`);
    return `${first} -> ${hrefs.length} lớp`;
  });

  await step('F5', 'no unexpected dialogs or console/CSP errors during the whole run', page, async () => {
    // 401 của lần khởi tạo phiên ẩn danh (trước khi đăng nhập) là nhiễu đã biết - xem unexpectedEvents trong lib.js.
    const problems = unexpectedEvents(events).filter((e) => e.kind === 'console.error' || e.kind === 'pageerror' || e.kind.startsWith('dialog.'));
    if (problems.length) throw new Error(problems.slice(0, 3).map((p) => `${p.kind}: ${p.text}`).join(' | '));
    return `dialogs=${dialogs().length}`;
  });

  await browser.close();
  process.exitCode = summarize('Kiểm tra bổ sung (e2e2.js)');
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
