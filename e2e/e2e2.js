'use strict';
// Kiểm tra bổ sung trên dữ liệu của lần chạy e2e.js gần nhất (đọc shots/last-run.json):
//   F1  Studio (bước 3 của trình hướng dẫn khóa học): thêm danh mục -> hiện ngay; sắp xếp lại bằng Alt+mũi tên (R17-02, R13)
//   F2  Studio: lịch thi round-trip - không lệch múi giờ khi lưu không sửa, xóa lịch được (R16-04/R16-06)
//   F3  Bảng xếp hạng lọc theo kỳ thi (R16)
//   F4  Danh sách lớp phân trang "Xem thêm lớp học" (R16-08) - chỉ kiểm được khi có > 50 lớp
//       (đặt E2E_PAGING_FILL=1 để tự tạo thêm lớp cho đến khi vượt 50: chậm, ~1 phút).
const path = require('path');
const { BASE, SHOTS_ROOT, log, newRunId, launchBrowser, newCtx, createRunner, login, createClassViaUI, loadState, unexpectedEvents } = require('./lib');

const S = loadState();
if (!S.classId) { console.error('Thiếu classId (shots/last-run.json hoặc E2E_CLASS_ID).'); process.exit(2); }
const RUN = newRunId();
const { step, shot, events, summarize, instrument } = createRunner(path.join(SHOTS_ROOT, `follow-${RUN}`));


(async () => {
  const browser = await launchBrowser();
  log('browser', browser.version(), 'class', S.classSlug, 'base', BASE);
  const ctx = await newCtx(browser);
  const page = await ctx.newPage();
  instrument(page, 'follow');
  const dialogs = () => events.filter((e) => e.kind.startsWith('dialog.')).map((e) => e.text);

  await step('F0', 'login as the class owner', page, async () => { await login(page, S.owner); });

  // ---- F1: danh mục (chương) trong bước 3 của trình hướng dẫn khóa học ----
  const card = () => page.locator('article', { has: page.locator('h3', { hasText: S.courseTitle }) }).first();
  const tree = () => page.getByRole('region', { name: 'Cấu trúc nội dung' });
  const order = async () => (await tree().locator('button[aria-expanded]').allInnerTexts()).map((t) => t.trim());
  await step('F1', 'new category appears at once in the step-3 tree', page, async () => {
    await page.goto(`${BASE}/studio/classes/${S.classId}/courses`);
    await card().getByRole('link', { name: /Chỉnh sửa/ }).click();
    await page.getByLabel(/Tên khóa học/).waitFor({ timeout: 10000 });
    await page.getByRole('navigation', { name: 'Các bước tạo khóa học' }).getByRole('button').nth(2).click();
    await tree().waitFor({ timeout: 10000 });
    const title = `Chuong 2 ${newRunId()}`;
    await page.getByRole('button', { name: 'Thêm danh mục' }).click();
    await page.getByLabel('Tên danh mục mới').fill(title);
    await page.getByRole('button', { name: 'Lưu danh mục' }).click();
    await tree().getByText(title).waitFor({ timeout: 5000 });
    S.section2 = title;
    return title;
  });
  await step('F1', 'new category lands after the existing ones and survives a reload', page, async () => {
    const before = await order();
    if (!before[before.length - 1].endsWith(S.section2)) throw new Error(`new category is not last: ${before.join(' | ')}`);
    await page.reload();
    await tree().getByText(S.section2).waitFor();
    const after = await order();
    if (after.join('|') !== before.join('|')) throw new Error(`order changed after reload: ${before.join(' | ')} -> ${after.join(' | ')}`);
    return after.join(' | ');
  });
  await step('F1', 'reorder categories with Alt+ArrowUp on the handle (move new one up)', page, async () => {
    const before = await order();
    const handle = page.getByRole('button', { name: `Sắp xếp danh mục "${S.section2}": kéo thả, hoặc nhấn Alt + mũi tên lên/xuống` });
    await handle.focus();
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === 'PUT' && /\/sections\/reorder$/.test(r.url())),
      page.keyboard.press('Alt+ArrowUp'),
    ]);
    await page.waitForFunction(
      (first) => { const b = document.querySelector('section[aria-label="Cấu trúc nội dung"] button[aria-expanded]'); return b && b.textContent.includes(first); },
      S.section2,
      { timeout: 5000 },
    );
    const after = await order();
    if (!after[0].endsWith(S.section2)) throw new Error(`reorder did not move it first: ${before.join(' | ')} -> ${after.join(' | ')}`);
    await shot(page, 'F1-sections-reorder');
    return `${before.join(' | ')} -> ${after.join(' | ')}`;
  });

  // ---- F2: lịch thi ----
  const examTitle = `Sched ${newRunId()}`;
  const ecard = () => page.locator('article', { has: page.locator('h3', { hasText: examTitle }) }).first();
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
    // Bộ lọc kỳ thi giờ là nhóm chip (thay cho <select>): chip của kỳ thi đến từ GET /classes/{id}/exams riêng,
    // nên chờ chip xuất hiện; bấm chip phải gọi bảng xếp hạng với ?examId= và chip chuyển sang aria-pressed=true.
    const filters = page.getByRole('group', { name: 'Bộ lọc kỳ thi' });
    await filters.getByRole('button', { name: 'Toàn bộ lớp (tổng điểm)' }).waitFor({ timeout: 10000 });
    const chip = filters.getByRole('button', { name: S.examTitle, exact: true });
    await chip.waitFor({ timeout: 8000 });
    const [resp] = await Promise.all([
      page.waitForResponse((r) => /\/leaderboard\?examId=/.test(r.url()) && r.request().method() === 'GET', { timeout: 8000 }),
      chip.click(),
    ]);
    if (resp.status() !== 200) throw new Error(`per-exam leaderboard ${resp.status()}`);
    if ((await chip.getAttribute('aria-pressed')) !== 'true') throw new Error('exam chip not selected');
    await page.locator('main').getByText(S.student.name).first().waitFor({ timeout: 8000 });
    return resp.url().replace(/^.*\/api\/v1/, '');
  });

  // ---- F4: phân trang danh sách lớp ----
  await step('F4', 'classes list pages with "Xem thêm lớp học" without duplicates', page, async () => {
    // Trang chủ /classes giờ là các rail chọn lọc; danh mục đầy đủ có phân trang mở bằng "Xem tất cả lớp học".
    // Mỗi thẻ lớp có đúng một liên kết "Vào lớp" (lớp của mình) hoặc "Xem lớp".
    const links = page.getByRole('link', { name: /^(Vào lớp|Xem lớp)$/ });
    const openCatalog = async () => {
      await page.getByRole('button', { name: 'Xem tất cả lớp học' }).click();
      await page.getByRole('heading', { name: 'Tất cả lớp học' }).waitFor({ timeout: 10000 });
    };
    const more = page.getByRole('button', { name: 'Xem thêm lớp học' });
    if (process.env.E2E_PAGING_FILL === '1') {
      await page.goto(`${BASE}/classes`);
      await openCatalog();
      await page.waitForTimeout(1500);
      let total = await links.count();
      while (total <= 50 && !(await more.isVisible())) {
        await createClassViaUI(page, { title: `E2E Paging ${newRunId()} ${total}` });
        await page.goto(`${BASE}/classes`);
        await openCatalog();
        await page.waitForTimeout(800);
        total = await links.count();
      }
    }
    await page.goto(`${BASE}/classes`);
    await openCatalog();
    await links.first().waitFor({ timeout: 10000 });
    await page.waitForTimeout(800);
    const first = await links.count();
    if (!(await more.isVisible())) {
      return `SKIP: chỉ có ${first} lớp (< 50), chưa có trang thứ hai để kiểm (đặt E2E_PAGING_FILL=1 để tự tạo thêm)`;
    }
    await more.click();
    await page.waitForFunction((n) => Array.from(document.querySelectorAll('a')).filter((a) => /^(Vào lớp|Xem lớp)$/.test(a.textContent.trim())).length > n, first, { timeout: 8000 });
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
