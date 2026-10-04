'use strict';
// Vòng 18 (R18-01 … R18-11) trên trình duyệt thật. Đọc dữ liệu của lần chạy e2e.js gần nhất (shots/last-run.json) và tạo
// thêm một trợ giảng + vài khóa học mới. Chạy SAU các script khác (xem all.js) vì nó đổi vai trò/khóa học của lớp E2E.
//   G0  chuẩn bị: trợ giảng đăng ký + vào lớp, chủ lớp tạo thêm 3 khóa học
//   G1  R18-01 hộp thoại phân quyền trợ giảng cao hơn màn hình: bấm chuột thật vào "Lưu quyền" ở 1366x768 và 390x844;
//       R18-08 quyền theo khóa hiện tên khóa
//   G2  R18-01 quét mọi hộp thoại còn lại (lớp/khóa học/kỳ thi/sản phẩm/phân khúc) ở màn hình thấp 1024x420
//   G3  R18-04 thứ tự khóa học = thứ tự tạo, đổi chỗ bằng mũi tên và giữ sau khi tải lại
//   G4  R18-07 + R18-06 xóa khóa học đang có quyền trợ giảng gắn riêng -> 409 nêu tên, chỉ hiện trong đúng thẻ khóa
//   G5  R18-03 Khóa học: Tab tới từng thẻ khóa học, Enter để chọn
//   G6  R18-05 Studio > Cài đặt: thông báo "Đã lưu…" hiện ra và trang không bị dựng lại
//   G7  R18-10 khách ở các tab dành cho thành viên thấy lời mời đăng nhập, không có 401, quay lại đúng trang sau khi đăng nhập
//   G8  R18-09 mất mạng -> thông báo tiếng Việt thay cho "Failed to fetch"
//   G9  R18-02 15 lần POST /auth/refresh liên tiếp (trải qua > 60s) đều < 1s
//   G10 không có hộp thoại/lỗi console/CSP bất thường
const path = require('path');
const {
  BASE, SHOTS_ROOT, log, newRunId, launchBrowser, newCtx, createRunner, settle, register, login, loadState, unexpectedEvents,
} = require('./lib');

const S = loadState();
if (!S.classId) { console.error('Thiếu classId (shots/last-run.json hoặc E2E_CLASS_ID).'); process.exit(2); }
const RUN = newRunId();
const { step, shot, events, summarize, instrument } = createRunner(path.join(SHOTS_ROOT, `r18-${RUN}`));

const STAFF = { name: `E2E Staff ${RUN}`, email: `e2e.staff.${RUN}@example.com` };
const COURSES = ['A', 'B', 'C'].map((k) => `R18 Course ${k} ${RUN}`);
const studio = (p) => `${BASE}/studio/classes/${S.classId}/${p}`;
const classUrl = (p) => `${BASE}/classes/${S.classSlug}/${p}`;
const NETWORK_MESSAGE = 'Không thể kết nối tới máy chủ. Vui lòng kiểm tra mạng và thử lại.';

/** Is the element wholly inside the viewport, and is it what a real mouse click at its centre would hit? */
async function mouseReach(page, locator) {
  const box = await locator.boundingBox();
  if (!box) return { ok: false, why: 'no bounding box (not rendered)' };
  const vp = page.viewportSize();
  const cx = box.x + box.width / 2;
  const cy = box.y + box.height / 2;
  const inView = box.x >= 0 && box.y >= 0 && box.x + box.width <= vp.width + 0.5 && box.y + box.height <= vp.height + 0.5;
  const hit = await locator.evaluate((el, [x, y]) => {
    const target = document.elementFromPoint(x, y);
    return !!target && (target === el || el.contains(target));
  }, [cx, cy]);
  return { ok: inView && hit, inView, hit, cx, cy, box, vp };
}

const dialog = (page) => page.locator('[role=dialog],[role=alertdialog]').first();
const overlay = (page) => page.locator('[data-modal-overlay]').first();

/** Course titles in list order (Studio courses page: every course card has an h3). */
const courseTitles = async (page) => (await page.locator('main h3').allInnerTexts()).map((t) => t.trim());
const cardOf = (page, title) => page.locator('article', { has: page.locator('h3', { hasText: title }) }).first();

(async () => {
  const browser = await launchBrowser();
  log('browser', browser.version(), 'class', S.classSlug, 'base', BASE);

  const ownerCtx = await newCtx(browser, { viewport: { width: 1366, height: 768 } });
  const owner = await ownerCtx.newPage();
  instrument(owner, 'owner');
  const staffCtx = await newCtx(browser);
  const staff = await staffCtx.newPage();
  instrument(staff, 'staff');

  // ---------------- G0: chuẩn bị ----------------
  await step('G0', 'owner logs in', owner, async () => { await login(owner, S.owner); });
  await step('G0', 'a new staff-to-be registers and joins the class', staff, async () => {
    await register(staff, STAFF);
    await staff.goto(classUrl('feed'));
    await staff.getByRole('button', { name: 'Tham gia lớp ngay' }).click();
    await staff.getByPlaceholder('Tiêu đề bài viết...').waitFor({ timeout: 10000 });
  });
  await step('G0', 'owner creates three more courses (they must come out in creation order)', owner, async () => {
    await owner.goto(studio('courses'));
    for (const title of COURSES) {
      await owner.getByRole('button', { name: 'Tạo khóa học mới' }).click();
      await owner.getByLabel('Tên khóa học').fill(title);
      await owner.getByRole('button', { name: 'Tạo khóa học', exact: true }).click();
      await owner.locator('h3', { hasText: title }).waitFor({ timeout: 10000 });
    }
    return COURSES.join(' | ');
  });

  // ---------------- G1: hộp thoại phân quyền trợ giảng ----------------
  const staffRow = () => owner.locator('li', { has: owner.locator('span', { hasText: STAFF.name }) }).first();
  // Hộp thoại phân quyền giờ ghi tên khu vực/quyền bằng tiếng Việt (legend của fieldset = tên khu vực, nhãn ô = tên quyền);
  // quyền lưu xuống vẫn là MODULE:ACTION (kiểm ở payload PUT và ở nhãn trong danh sách).
  const MODULE_LEGEND = { STUDIO: 'Tổng quan Xưởng', COURSE: 'Khóa học', FEED: 'Bảng tin' };
  await step('G1', '1366x768: the permission dialog is taller than the window yet scrolls; member picker and "Lưu quyền" are reachable with a mouse', owner, async () => {
    await owner.setViewportSize({ width: 1366, height: 768 });
    await owner.goto(studio('staff'));
    await owner.getByRole('button', { name: 'Thêm trợ giảng mới' }).click();
    const panel = dialog(owner);
    await panel.waitFor({ timeout: 5000 });
    const dims = await panel.evaluate((el) => ({ scrollHeight: el.scrollHeight, clientHeight: el.clientHeight, overflowY: getComputedStyle(el).overflowY }));
    const vpH = owner.viewportSize().height;
    if (!(dims.scrollHeight > vpH)) throw new Error(`dialog content is only ${dims.scrollHeight}px - not taller than the ${vpH}px window, so this step proves nothing`);
    if (dims.clientHeight > vpH) throw new Error(`dialog panel is ${dims.clientHeight}px tall, taller than the ${vpH}px window`);
    if (dims.overflowY !== 'auto') throw new Error(`panel overflow-y is ${dims.overflowY}`);
    const picker = await mouseReach(owner, panel.getByLabel('Thành viên'));
    if (!picker.ok) throw new Error(`member picker unreachable: ${JSON.stringify({ inView: picker.inView, hit: picker.hit })}`);
    const save = await mouseReach(owner, panel.getByRole('button', { name: 'Lưu quyền' }));
    if (!save.ok) throw new Error(`"Lưu quyền" unreachable without scrolling: ${JSON.stringify({ inView: save.inView, hit: save.hit, box: save.box })}`);
    return `content ${dims.scrollHeight}px in a ${dims.clientHeight}px panel`;
  });
  await step('G1', '1366x768: pick a member, tick permissions (incl. a course-scoped one) and save with a real mouse click', owner, async () => {
    const panel = dialog(owner);
    await panel.getByLabel('Thành viên').selectOption({ label: STAFF.name });
    const group = (module) => panel.getByRole('group', { name: MODULE_LEGEND[module], exact: true });
    await group('STUDIO').getByLabel('Xem', { exact: true }).click();
    await group('COURSE').getByLabel('Chạy thử', { exact: true }).click();
    await group('COURSE').getByLabel(/^Sửa · khóa:/).selectOption({ label: COURSES[0] });
    await shot(owner, 'G1-staff-dialog-1366');
    const save = await mouseReach(owner, panel.getByRole('button', { name: 'Lưu quyền' }));
    if (!save.ok) throw new Error('Lưu quyền not reachable after filling the form');
    const [resp] = await Promise.all([
      owner.waitForResponse((r) => r.request().method() === 'PUT' && /\/staff\/[^/]+\/permissions$/.test(r.url())),
      owner.mouse.click(save.cx, save.cy),
    ]);
    if (resp.status() !== 200) throw new Error(`PUT permissions -> ${resp.status()}`);
    const sent = JSON.parse(resp.request().postData() || '[]');
    if (!sent.some((p) => p.module === 'COURSE' && p.action === 'EDIT' && p.scopeCourseId)) throw new Error(`scoped grant missing from ${JSON.stringify(sent)}`);
    for (const [m, a] of [['STUDIO', 'VIEW'], ['COURSE', 'PREVIEW']]) {
      if (!sent.some((p) => p.module === m && p.action === a && !p.scopeCourseId)) throw new Error(`${m}:${a} missing from ${JSON.stringify(sent)}`);
    }
    await panel.waitFor({ state: 'detached', timeout: 5000 });
    return `${sent.length} grants`;
  });
  await step('G1', 'R18-08: the list shows the course a scoped grant is limited to; class-wide grants stay bare', owner, async () => {
    const row = staffRow();
    await row.waitFor({ timeout: 8000 });
    const badges = (await row.locator('span.font-mono').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').trim());
    if (!badges.includes(`COURSE:EDIT · ${COURSES[0]}`)) throw new Error(`no scoped badge: ${badges.join(' | ')}`);
    if (!badges.includes('STUDIO:VIEW') || !badges.includes('COURSE:PREVIEW')) throw new Error(`class-wide badges wrong: ${badges.join(' | ')}`);
    return badges.join(' | ');
  });
  await step('G1', '390x844 phone: edit the grants; the dialog scrolls and "Lưu quyền" stays reachable', owner, async () => {
    await owner.setViewportSize({ width: 390, height: 844 });
    await owner.goto(studio('staff'));
    await staffRow().getByRole('button', { name: 'Chỉnh sửa quyền' }).click();
    const panel = dialog(owner);
    await panel.waitFor({ timeout: 5000 });
    const dims = await panel.evaluate((el) => ({ scrollHeight: el.scrollHeight, clientHeight: el.clientHeight }));
    if (dims.scrollHeight <= dims.clientHeight) throw new Error(`dialog does not need scrolling on a phone (${dims.scrollHeight}/${dims.clientHeight}) - unexpected`);
    // The panel body scrolls (mouse wheel over it), the actions stay put.
    await panel.hover();
    await owner.mouse.wheel(0, 900);
    await owner.waitForTimeout(250);
    const scrolled = await panel.evaluate((el) => el.scrollTop);
    if (scrolled <= 0) throw new Error('wheel over the dialog did not scroll it');
    await panel.getByRole('group', { name: MODULE_LEGEND.FEED, exact: true }).getByLabel('Xem', { exact: true }).click();
    const save = await mouseReach(owner, panel.getByRole('button', { name: 'Lưu quyền' }));
    if (!save.ok) throw new Error(`"Lưu quyền" unreachable on the phone: ${JSON.stringify({ inView: save.inView, hit: save.hit, box: save.box, vp: save.vp })}`);
    await shot(owner, 'G1-staff-dialog-390');
    const [resp] = await Promise.all([
      owner.waitForResponse((r) => r.request().method() === 'PUT' && /\/staff\/[^/]+\/permissions$/.test(r.url())),
      owner.mouse.click(save.cx, save.cy),
    ]);
    if (resp.status() !== 200) throw new Error(`PUT permissions -> ${resp.status()}`);
    await panel.waitFor({ state: 'detached', timeout: 5000 });
    return `scrolled ${scrolled}px, then saved`;
  });
  await step('G1', 'Escape closes the dialog without saving; focus goes back to the opener', owner, async () => {
    await owner.setViewportSize({ width: 1366, height: 768 });
    await owner.goto(studio('staff'));
    const opener = owner.getByRole('button', { name: 'Thêm trợ giảng mới' });
    await opener.click();
    await dialog(owner).waitFor({ timeout: 5000 });
    const puts = [];
    owner.on('request', (r) => { if (r.method() === 'PUT') puts.push(r.url()); });
    await owner.keyboard.press('Escape');
    await dialog(owner).waitFor({ state: 'detached', timeout: 3000 });
    const focusedOpener = await opener.evaluate((el) => el === document.activeElement);
    if (!focusedOpener) throw new Error('focus did not return to "Thêm trợ giảng mới"');
    if (puts.length) throw new Error(`Escape triggered a save: ${puts.join(', ')}`);
  });

  // ---------------- G2: quét mọi hộp thoại ở màn hình thấp ----------------
  const dialogs = [
    ['Studio khóa học', () => studio('courses'), 'Tạo khóa học mới'],
    ['Studio kỳ thi', () => studio('exams'), 'Tạo kỳ thi mới'],
    ['Studio sản phẩm', () => studio('store'), 'Tạo gói sản phẩm mới'],
    ['Studio phân khúc', () => studio('segments'), 'Tạo nhóm phân khúc'],
    ['Studio nhân sự', () => studio('staff'), 'Thêm trợ giảng mới'],
  ];
  for (const [label, url, opener] of dialogs) {
    await step('G2', `1024x420 (short window): "${opener}" dialog scrolls, fits, and its last button is reachable [${label}]`, owner, async () => {
      await owner.setViewportSize({ width: 1024, height: 420 });
      await owner.goto(url());
      await settle(owner, 300);
      await owner.getByRole('button', { name: opener, exact: false }).first().click();
      const panel = dialog(owner);
      await panel.waitFor({ timeout: 5000 });
      const vpH = owner.viewportSize().height;
      const info = await panel.evaluate((el) => ({ h: el.getBoundingClientRect().height, sh: el.scrollHeight, ch: el.clientHeight, ov: getComputedStyle(el).overflowY, modal: el.getAttribute('aria-modal') }));
      const ov = await overlay(owner).evaluate((el) => getComputedStyle(el).overflowY);
      if (info.h > vpH) throw new Error(`panel is ${info.h}px in a ${vpH}px window`);
      if (ov !== 'auto' || info.ov !== 'auto') throw new Error(`overflow-y overlay=${ov} panel=${info.ov}`);
      if (info.modal !== 'true') throw new Error('dialog lacks aria-modal');
      await panel.evaluate((el) => { el.scrollTop = el.scrollHeight; });
      const last = panel.getByRole('button').last();
      const reach = await mouseReach(owner, last);
      if (!reach.ok) throw new Error(`last button unreachable: ${JSON.stringify({ inView: reach.inView, hit: reach.hit, box: reach.box })}`);
      await owner.keyboard.press('Escape');
      await panel.waitFor({ state: 'detached', timeout: 3000 });
      return `panel ${Math.round(info.h)}px, content ${info.sh}px`;
    });
  }

  // Tạo lớp không còn là hộp thoại mà là trang toàn màn hình /classes/new: kiểm tương đương ở cửa sổ thấp - cột form tự cuộn,
  // nút "Tạo lớp học" (chân cột, cố định) bấm được bằng chuột thật mà không cần cuộn, và điều khiển cuối của form (công tắc
  // duyệt thành viên) tới được sau khi cuộn cột form.
  await step('G2', '1024x420 (short window): the /classes/new form column scrolls; "Tạo lớp học" and the last control are reachable', owner, async () => {
    await owner.setViewportSize({ width: 1024, height: 420 });
    await owner.goto(`${BASE}/classes/new`);
    await owner.getByRole('heading', { name: 'Tạo lớp học', level: 1 }).waitFor({ timeout: 10000 });
    const cta = owner.getByRole('button', { name: 'Tạo lớp học', exact: true });
    const ctaReach = await mouseReach(owner, cta);
    if (!ctaReach.ok) throw new Error(`"Tạo lớp học" unreachable: ${JSON.stringify({ inView: ctaReach.inView, hit: ctaReach.hit, box: ctaReach.box })}`);
    const sw = owner.getByRole('switch', { name: 'Duyệt từng người trước khi vào' });
    const scroller = await sw.evaluateHandle((el) => { let p = el.parentElement; while (p && !/(auto|scroll)/.test(getComputedStyle(p).overflowY)) p = p.parentElement; return p; });
    const info = await scroller.evaluate((el) => el && ({ sh: el.scrollHeight, ch: el.clientHeight }));
    if (!info) throw new Error('the form column has no scroll container');
    if (info.sh <= info.ch) throw new Error(`form column does not need scrolling at 420px (${info.sh}/${info.ch}) - this step proves nothing`);
    await scroller.evaluate((el) => { el.scrollTop = el.scrollHeight; });
    const swReach = await mouseReach(owner, sw);
    if (!swReach.ok) throw new Error(`approval switch unreachable after scrolling: ${JSON.stringify({ inView: swReach.inView, hit: swReach.hit, box: swReach.box })}`);
    await owner.mouse.click(swReach.cx, swReach.cy);
    if ((await sw.getAttribute('aria-checked')) !== 'true') throw new Error('mouse click did not toggle the approval switch');
    if (!(await mouseReach(owner, cta)).ok) throw new Error('"Tạo lớp học" no longer reachable after scrolling the form');
    return `form column ${info.ch}px, content ${info.sh}px`;
  });

  // ---------------- G3: thứ tự khóa học ----------------
  const expectedOrder = [S.courseTitle, ...COURSES];
  await step('G3', 'R18-04: courses list in creation order, stable across reloads', owner, async () => {
    await owner.setViewportSize({ width: 1366, height: 768 });
    for (let i = 0; i < 2; i += 1) {
      await owner.goto(studio('courses'));
      await owner.locator('h3', { hasText: COURSES[2] }).waitFor({ timeout: 10000 });
      const titles = (await courseTitles(owner)).filter((t) => expectedOrder.includes(t));
      if (titles.join('|') !== expectedOrder.join('|')) throw new Error(`order ${titles.join(' | ')} != ${expectedOrder.join(' | ')}`);
    }
    return expectedOrder.join(' | ');
  });
  await step('G3', 'R18-04: arrows reorder courses (ends disabled) and the order survives a reload', owner, async () => {
    await owner.goto(studio('courses'));
    await owner.locator('h3', { hasText: COURSES[2] }).waitFor();
    const first = owner.getByRole('button', { name: `Chuyển khóa học "${S.courseTitle}" lên trên` });
    const last = owner.getByRole('button', { name: `Chuyển khóa học "${COURSES[2]}" xuống dưới` });
    if (!(await first.isDisabled()) || !(await last.isDisabled())) throw new Error('the outer arrows should be disabled');
    const [resp] = await Promise.all([
      owner.waitForResponse((r) => r.request().method() === 'PUT' && /\/courses\/reorder$/.test(r.url())),
      owner.getByRole('button', { name: `Chuyển khóa học "${COURSES[2]}" lên trên` }).click(),
    ]);
    if (resp.status() !== 200) throw new Error(`reorder -> ${resp.status()}`);
    const want = [S.courseTitle, COURSES[0], COURSES[2], COURSES[1]];
    await owner.waitForFunction((t) => Array.from(document.querySelectorAll('main h3')).map((h) => h.textContent.trim()).filter((x) => t.includes(x)).join('|') === t.join('|'), want, { timeout: 5000 });
    await owner.reload();
    await owner.locator('h3', { hasText: COURSES[2] }).waitFor();
    const after = (await courseTitles(owner)).filter((t) => want.includes(t));
    if (after.join('|') !== want.join('|')) throw new Error(`after reload ${after.join(' | ')} != ${want.join(' | ')}`);
    return after.join(' | ');
  });
  await step('G3', 'R18-04: course-scoped staff (no class-wide COURSE:EDIT) get no reorder arrows', staff, async () => {
    await staff.goto(studio('courses'));
    await staff.locator('main h3', { hasText: COURSES[0] }).waitFor({ timeout: 10000 });
    await settle(staff, 300);
    const arrows = await staff.getByRole('button', { name: /Chuyển khóa học/ }).count();
    if (arrows) throw new Error(`${arrows} reorder arrows shown to scoped-only staff`);
  });

  // ---------------- G4: xóa khóa học có quyền trợ giảng gắn riêng ----------------
  await step('G4', 'R18-07/R18-06: deleting a course a staff grant is scoped to is a 409 naming the staff, shown only in that course card', owner, async () => {
    await owner.goto(studio('courses'));
    await owner.locator('h3', { hasText: COURSES[0] }).waitFor();
    await cardOf(owner, COURSES[0]).getByRole('button', { name: 'Xóa', exact: true }).click();
    const [resp] = await Promise.all([
      owner.waitForResponse((r) => r.request().method() === 'DELETE' && /\/courses\/[^/]+$/.test(r.url())),
      owner.getByRole('button', { name: 'Xác nhận', exact: true }).click(),
    ]);
    if (resp.status() !== 409) throw new Error(`DELETE -> ${resp.status()}, expected 409`);
    const body = await resp.json();
    if (body.error.code !== 'CONFLICT') throw new Error(`code ${body.error.code}`);
    const message = body.error.message;
    for (const needle of [STAFF.name, 'COURSE:EDIT', 'Studio > Trợ giảng']) {
      if (!message.includes(needle)) throw new Error(`message lacks "${needle}": ${message}`);
    }
    await cardOf(owner, COURSES[0]).getByText(message).waitFor({ timeout: 5000 });
    const shown = await owner.getByText(message, { exact: true }).count();
    if (shown !== 1) throw new Error(`error is rendered ${shown} times, expected exactly once (in the affected card)`);
    for (const other of [S.courseTitle, COURSES[1], COURSES[2]]) {
      if (await cardOf(owner, other).locator('[role=alert]').count()) throw new Error(`the error leaked into "${other}"`);
    }
    return message.slice(0, 90);
  });
  await step('G4', 'a course with no scoped grant is still deletable', owner, async () => {
    await cardOf(owner, COURSES[1]).getByRole('button', { name: 'Xóa', exact: true }).click();
    await owner.getByRole('button', { name: 'Xác nhận', exact: true }).click();
    await owner.locator('h3', { hasText: COURSES[1] }).waitFor({ state: 'detached', timeout: 8000 });
  });

  // ---------------- G5: bàn phím ở tab Khóa học ----------------
  await step('G5', 'owner publishes two of the new courses so learners see several cards', owner, async () => {
    for (const title of [COURSES[0], COURSES[2]]) {
      await cardOf(owner, title).getByRole('button', { name: 'Xuất bản' }).click();
      await cardOf(owner, title).getByText('Đã đăng', { exact: true }).waitFor({ timeout: 8000 });
    }
  });
  const stuCtx = await newCtx(browser);
  const stu = await stuCtx.newPage();
  instrument(stu, 'student');
  await step('G5', 'student logs in', stu, async () => { await login(stu, S.student); });
  await step('G5', 'R18-03: Tab reaches every course card (real buttons) and Enter selects the focused one', stu, async () => {
    await stu.goto(classUrl('learn'));
    // Thẻ khóa học = nút aria-pressed trong mục "Tất cả khóa học" (các chip lọc cũng có aria-pressed nhưng nằm trong role=group).
    const CARD_SEL = 'section[aria-labelledby="all-courses-heading"] .grid > button[aria-pressed]';
    const cards = stu.locator(CARD_SEL);
    await cards.first().waitFor({ timeout: 10000 });
    const total = await cards.count();
    if (total < 3) throw new Error(`only ${total} course cards; expected >= 3`);
    await stu.locator('body').click({ position: { x: 2, y: 2 } });
    const seen = new Set();
    const focusedInfo = () => stu.evaluate((sel) => {
      const el = document.activeElement;
      if (!el || !el.matches(sel)) return null;
      const cs = getComputedStyle(el);
      return { title: el.querySelector('span.truncate.font-semibold')?.textContent?.trim() || el.textContent.trim().slice(0, 40), pressed: el.getAttribute('aria-pressed'), shadow: cs.boxShadow, tag: el.tagName };
    }, CARD_SEL);
    let target = null;
    for (let i = 0; i < 80 && seen.size < total; i += 1) {
      await stu.keyboard.press('Tab');
      const f = await focusedInfo();
      if (f) {
        seen.add(f.title);
        if (f.tag !== 'BUTTON') throw new Error(`card is a <${f.tag}>, not a button`);
        if (!f.shadow || f.shadow === 'none') throw new Error(`focused card "${f.title}" shows no focus ring`);
        if (f.title.includes(COURSES[2]) && !target) target = f.title;
      }
    }
    if (seen.size < total) throw new Error(`Tab reached ${seen.size}/${total} cards: ${[...seen].join(' | ')}`);
    // Tab order is document order, so the focused card is the last one visited: walk back to the target card and press Enter.
    for (let i = 0; i < total + 2; i += 1) {
      const f = await focusedInfo();
      if (f && f.title.includes(COURSES[2])) break;
      await stu.keyboard.press('Shift+Tab');
    }
    const before = await focusedInfo();
    if (!before || !before.title.includes(COURSES[2])) throw new Error(`could not focus the "${COURSES[2]}" card, focused: ${JSON.stringify(before)}`);
    if (before.pressed === 'true') throw new Error('the target card was already selected; pick another for a meaningful check');
    await stu.keyboard.press('Enter');
    // COURSES[2] chưa có bài học nên Enter chọn nó làm khóa nổi bật (khóa có bài sẽ mở thẳng bài học).
    await stu.waitForFunction((t) => { const el = document.activeElement; return el && el.getAttribute('aria-pressed') === 'true' && el.textContent.includes(t); }, COURSES[2], { timeout: 5000 });
    await stu.getByRole('heading', { name: COURSES[2] }).waitFor({ timeout: 5000 });
    await shot(stu, 'G5-learn-keyboard');
    return `${total} cards reachable; Enter selected "${COURSES[2]}"`;
  });

  // ---------------- G6: Studio > Cài đặt ----------------
  await step('G6', 'R18-05: saving class settings shows "Đã lưu cài đặt lớp học." and does not remount the page', owner, async () => {
    await owner.goto(studio('settings'));
    const title = owner.getByLabel('Tên lớp học');
    await title.waitFor({ timeout: 10000 });
    await title.evaluate((el) => { el.setAttribute('data-e2e-mark', 'kept'); });
    const description = owner.getByLabel('Mô tả', { exact: true });
    await description.fill(`Lop kiem thu E2E - cap nhat ${RUN}`);
    // Watch the DOM for the full-page spinner text while the save + refresh happens.
    await owner.evaluate(() => {
      window.__r18Spinner = false;
      new MutationObserver(() => { if (document.body.innerText.includes('Đang kết nối Studio')) window.__r18Spinner = true; })
        .observe(document.body, { childList: true, subtree: true, characterData: true });
    });
    await Promise.all([
      owner.waitForResponse((r) => r.request().method() === 'PUT' && /\/classes\/[^/]+$/.test(r.url())),
      owner.getByRole('button', { name: /Lưu thay đổi/ }).click(),
    ]);
    const status = owner.getByRole('status').filter({ hasText: 'Đã lưu cài đặt lớp học.' });
    await status.waitFor({ timeout: 6000 });
    await owner.waitForTimeout(1500);
    if (!(await status.isVisible())) throw new Error('the confirmation disappeared within 1.5s');
    const mark = await owner.getByLabel('Tên lớp học').getAttribute('data-e2e-mark');
    if (mark !== 'kept') throw new Error('the settings form was remounted (spinner replaced the page)');
    if (await owner.evaluate(() => !!window.__r18Spinner)) throw new Error('the full-page spinner flashed during the refresh');
    await shot(owner, 'G6-settings-saved');
  });

  // ---------------- G7: khách ở tab dành cho thành viên ----------------
  const guestCtx = await newCtx(browser);
  const guest = await guestCtx.newPage();
  instrument(guest, 'guest');
  for (const tab of ['learn', 'exams', 'leaderboard', 'documents', 'members']) {
    await step('G7', `R18-10: guest on /${tab} sees a sign-in prompt - no 401s, no extra refresh, no raw error`, guest, async () => {
      const api = [];
      const onResponse = (r) => { if (r.url().includes('/api/v1/')) api.push({ url: r.url().replace(BASE, ''), status: r.status() }); };
      guest.on('response', onResponse);
      try {
        await guest.goto(classUrl(tab));
        await guest.getByRole('heading', { name: 'Đăng nhập để xem nội dung này' }).waitFor({ timeout: 10000 });
        await settle(guest, 500);
      } finally {
        guest.off('response', onResponse);
      }
      const body = (await guest.locator('body').innerText()).replace(/\s+/g, ' ');
      if (/Chưa đăng nhập hoặc phiên đã hết hạn/.test(body)) throw new Error('raw "Chưa đăng nhập…" error is still shown');
      if (await guest.getByRole('button', { name: 'Thử lại' }).count()) throw new Error('a useless "Thử lại" button is shown');
      const memberOnly401 = api.filter((c) => c.status === 401 && !/\/auth\/refresh/.test(c.url));
      if (memberOnly401.length) throw new Error(`member-only requests fired for a guest: ${memberOnly401.map((c) => c.url).join(', ')}`);
      const refreshes = api.filter((c) => /\/auth\/refresh/.test(c.url)).length;
      if (refreshes > 1) throw new Error(`${refreshes} /auth/refresh calls for one page view (expected the single bootstrap call)`);
      return `api calls: ${api.map((c) => `${c.status} ${c.url.split('?')[0].split('/').slice(-2).join('/')}`).join(', ')}`;
    });
  }
  await step('G7', 'R18-10: the prompt\'s button signs in and returns to the same tab', guest, async () => {
    await guest.goto(classUrl('learn'));
    await guest.locator('main').getByRole('link', { name: 'Đăng nhập', exact: true }).click();
    await guest.waitForURL(/\/login/, { timeout: 8000 });
    await guest.getByLabel('Email').fill(S.student.email);
    await guest.getByLabel('Mật khẩu', { exact: true }).fill(S.password);
    await guest.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
    await guest.waitForURL(new RegExp(`/classes/${S.classSlug}/learn`), { timeout: 15000 });
    await guest.locator('button[aria-pressed]').first().waitFor({ timeout: 10000 });
  });
  await step('G7', 'R18-10 (D-16): the PUBLIC profile option no longer promises what guests cannot do', stu, async () => {
    await stu.goto(`${BASE}/me/profile`);
    const text = (await stu.locator('fieldset').innerText()).replace(/\s+/g, ' ');
    if (/Bất kỳ ai xem hồ sơ/.test(text)) throw new Error('old world-readable wording is back');
    if (!/chỉ dành cho thành viên của lớp/.test(text)) throw new Error(`PUBLIC copy does not say profiles/leaderboard are members-only: ${text.slice(0, 300)}`);
  });

  // ---------------- G8: mất mạng ----------------
  await step('G8', 'R18-09: a failed request shows the Vietnamese connection message, not "Failed to fetch"', stu, async () => {
    await stu.route('**/api/v1/classes/*/courses', (route) => route.abort('failed'));
    try {
      await stu.goto(classUrl('learn'));
      await stu.getByText(NETWORK_MESSAGE).first().waitFor({ timeout: 10000 });
      const body = await stu.locator('body').innerText();
      if (/Failed to fetch|Load failed|NetworkError/i.test(body)) throw new Error('the raw browser error is visible');
    } finally {
      await stu.unroute('**/api/v1/classes/*/courses');
    }
  });

  // ---------------- G9: độ trễ /auth/refresh ----------------
  const spacing = Number(process.env.R18_REFRESH_SPACING_MS || 5000);
  await step('G9', `R18-02: 15 consecutive POST /auth/refresh (spaced ${spacing}ms, spanning the proxy re-resolution interval) are all < 1s`, staff, async () => {
    await staff.goto(`${BASE}/classes`);
    const times = [];
    for (let i = 0; i < 15; i += 1) {
      const t0 = Date.now();
      const resp = await staffCtx.request.post(`${BASE}/api/v1/auth/refresh`, { headers: { 'X-Requested-With': 'XMLHttpRequest' } });
      const ms = Date.now() - t0;
      times.push(ms);
      if (resp.status() !== 200) throw new Error(`refresh #${i + 1} -> ${resp.status()}`);
      if (ms >= 1000) throw new Error(`refresh #${i + 1} took ${ms}ms (all: ${times.join(', ')})`);
      if (i < 14 && spacing > 0) await new Promise((resolve) => setTimeout(resolve, spacing));
    }
    return `max ${Math.max(...times)}ms, all: ${times.join(', ')}`;
  });

  // ---------------- G10: sạch ----------------
  await step('G10', 'no unexpected dialogs, console/CSP errors or 5xx during the run', owner, async () => {
    const problems = unexpectedEvents(events).filter((e) => e.kind === 'console.error' || e.kind === 'pageerror' || e.kind.startsWith('dialog.') || /^http\.5/.test(e.kind) || (e.kind === 'requestfailed' && e.text.includes(BASE)));
    // G8 aborts one request on purpose.
    const real = problems.filter((e) => !/\/classes\/[^/]+\/courses/.test(e.text) && !/Failed to load resource/.test(e.text));
    if (real.length) throw new Error(real.slice(0, 4).map((p) => `${p.label} ${p.kind}: ${p.text}`).join(' | '));
    return `events=${events.length}`;
  });

  await browser.close();
  process.exitCode = summarize('Vòng 18 (e2e3.js)');
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
