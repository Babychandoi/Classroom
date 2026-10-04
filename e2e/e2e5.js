'use strict';
// Vòng 22 (D-19) trên trình duyệt thật: lớp riêng tư, lớp trả phí, mã mời, thành viên hết hạn. Chạy trên stack có LỚP PHỦ DEMO
// (infra/compose.demo.yaml: hạt giống demo + cổng thanh toán sandbox). Trên stack không có lớp phủ demo script này TỰ BỎ QUA
// (in lý do, mã thoát 0); đặt E2E_DEMO=1 nếu muốn coi việc thiếu lớp phủ demo là lỗi (mã thoát 2).
//   P1  khách: danh sách khám phá (lớp riêng tư KHÔNG có, lớp trả phí có nhãn "Trả phí · 199.000đ / 30 ngày", bộ lọc Miễn phí/Trả phí),
//       URL lớp riêng tư -> trang "Không tìm thấy lớp học" (404), tường phí của lớp trả phí, đăng nhập để mua quay lại đúng lớp
//   P2  mã mời: khách thấy thẻ lớp + meta referrer no-referrer (gỡ khi rời trang), đăng nhập student.free -> tham gia -> vào bảng tin,
//       nút Back không quay lại trang chứa mã, vào lại bằng URL lớp không cần mã, mã sai/hỏng -> "Mã mời không hợp lệ hoặc đã hết hạn"
//   P3  mua lớp trả phí (người dùng mới): tường phí -> thanh toán sandbox -> chủ lớp xác nhận trong Studio -> thành viên (hạn ~30 ngày)
//   P4  hết hạn không cần sửa mã: chủ lớp hoàn tiền trong Studio -> EXPIRED: banner "Gói thành viên lớp đã hết hạn ngày dd/MM/yyyy",
//       chỉ còn tab Giới thiệu/Shop, tab thành viên hiện lời nhắc gia hạn (API 403 MEMBERSHIP_EXPIRED), bộ lọc "Đã hết hạn" của Studio -> gia hạn
//   P5  Studio trên một lớp riêng mới tạo: tạo lớp riêng tư bằng hộp thoại, đổi hiển thị (bàn phím) và thu phí có xác nhận, tạo liên kết mời
//       (hiện đúng MỘT lần, sao chép, đóng bằng Escape), tham gia miễn phí bằng liên kết, lớp đổi sang trả phí (thành viên cũ được giữ),
//       người mới mua qua liên kết mời của lớp RIÊNG TƯ + TRẢ PHÍ, thu hồi liên kết -> liên kết báo không hợp lệ và không mua vòng được
//   P6  axe-core (0 lỗi critical) trên tường phí, trang mời, phần Cài đặt, trình quản lý mã mời, thẻ gia hạn; bàn phím đi được tới nút chính
//   P7  giao diện 390px: trang mời, tường phí, thẻ gia hạn, danh sách lớp không tràn ngang, nút chính nằm trọn trong màn hình
//   P8  không có lỗi console / CSP / 5xx ngoài các phản hồi 402/404 có chủ đích
const fs = require('fs');
const path = require('path');
const {
  BASE, PASSWORD, SHOTS_ROOT, log, newRunId, launchBrowser, newCtx, createRunner, settle, register, createClassViaUI, overflowCheck, unexpectedEvents,
} = require('./lib');

const API = `${BASE}/api/v1`;
const DEMO_PASSWORD = 'Password123!';
const DEMO_INVITE = 'demo-invite-lop-rieng-tu-2026';
const PAID_SLUG = 'lop-tra-phi';
const PRIVATE_SLUG = 'lop-rieng-tu-ma-moi';
const PRIVATE_TITLE = 'Lớp Riêng Tư (mã mời)';
const PAID_TITLE = 'Lớp Trả Phí';
const OWNER = 'owner@classroom.local';
const STUDENT_FREE = 'student.free@classroom.local';

async function api(method, url, { token, body } = {}) {
  const res = await fetch(`${API}${url}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const json = await res.json().catch(() => null);
  return { status: res.status, ok: res.ok, data: json && json.data, error: json && json.error };
}
const must = async (method, url, opts) => {
  const r = await api(method, url, opts);
  if (!r.ok) throw new Error(`${method} ${url} -> ${r.status} ${JSON.stringify(r.error)}`);
  return r.data;
};
const apiLogin = async (email, password) => (await must('POST', '/auth/login', { body: { email, password } })).token;

/** Demo deployment = the demo seed classes exist AND the sandbox payment rail answers. */
async function detectDemo() {
  try {
    const list = await api('GET', '/classes?page=0&size=100');
    const sandbox = await api('GET', '/payments/sandbox-status');
    return list.ok && Array.isArray(list.data) && list.data.some((c) => c.slug === PAID_SLUG) && sandbox.ok && sandbox.data && sandbox.data.checkoutAvailable === true;
  } catch {
    return false;
  }
}

let AXE_SOURCE = null;
try { AXE_SOURCE = fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8'); } catch { /* axe is optional */ }

/** dd/MM/yyyy của hôm nay theo múi giờ mà trình duyệt E2E dùng (Asia/Ho_Chi_Minh, xem lib.newCtx) - không phụ thuộc múi giờ của máy chạy Node. */
const ddmmyyyy = (d) => d.toLocaleDateString('en-GB', { timeZone: 'Asia/Ho_Chi_Minh', day: '2-digit', month: '2-digit', year: 'numeric' });
const DAY = 24 * 60 * 60 * 1000;

(async () => {
  if (!(await detectDemo())) {
    const why = `BASE_URL=${BASE} không có lớp phủ demo (thiếu lớp "${PAID_SLUG}" hoặc cổng thanh toán sandbox).`;
    if (process.env.E2E_DEMO === '1') {
      console.error(`Thiếu lớp phủ demo nhưng E2E_DEMO=1: ${why}\nChạy stack với: docker compose -f infra/compose.yaml -f infra/compose.demo.yaml up -d --build`);
      process.exitCode = 2; return; // không gọi process.exit() ngay sau fetch: trên Windows Node có thể dính lỗi libuv (exit 127)
    }
    console.log(`SKIP e2e5.js: ${why}\nKịch bản D-19 cần lớp phủ demo (hạt giống "Lớp Trả Phí"/"Lớp Riêng Tư (mã mời)" + sandbox). Đặt E2E_DEMO=1 để coi việc này là lỗi.`);
    process.exitCode = 0; return;
  }

  const RUN = newRunId();
  const { step, shot, events, summarize, instrument } = createRunner(path.join(SHOTS_ROOT, `d19-${RUN}`));
  const browser = await launchBrowser();
  log('browser', browser.version(), 'base', BASE, 'run', RUN);

  const contexts = [];
  /** A fresh browser context + page (own cookies, so every person is a separate session). */
  async function person(label, { width = 1366, height = 900 } = {}) {
    const ctx = await newCtx(browser, { viewport: { width, height } });
    await ctx.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: BASE }).catch(() => {});
    const page = await ctx.newPage();
    instrument(page, label);
    contexts.push(ctx);
    return { ctx, page };
  }
  async function formLogin(page, email, password) {
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Mật khẩu', { exact: true }).fill(password);
    await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  }
  async function demoLogin(page, email) {
    await page.goto(`${BASE}/login`);
    await formLogin(page, email, DEMO_PASSWORD);
    await page.waitForURL(/\/classes$/, { timeout: 15000 });
    await page.getByRole('button', { name: 'Mở menu tài khoản' }).waitFor({ timeout: 10000 });
  }
  async function newUser(label, opts) {
    const p = await person(label, opts);
    const user = { name: `E2E ${label} ${RUN}`, email: `e2e.${label}.${RUN}@example.com` };
    await register(p.page, user);
    return { ...p, user };
  }
  async function axe(page, label) {
    if (!AXE_SOURCE) return 'axe-core chưa cài (bỏ qua)';
    await page.evaluate(AXE_SOURCE);
    const violations = await page.evaluate(async () => {
      // eslint-disable-next-line no-undef
      const res = await axe.run(document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] } });
      return res.violations.map((v) => ({ id: v.id, impact: v.impact, count: v.nodes.length, targets: v.nodes.slice(0, 3).map((n) => n.target.join(' ')) }));
    });
    const critical = violations.filter((v) => v.impact === 'critical');
    const serious = violations.filter((v) => v.impact === 'serious');
    for (const v of [...critical, ...serious]) log(`  [${v.impact}] ${label}: ${v.id} x${v.count} :: ${v.targets.join(' ; ')}`);
    if (critical.length) throw new Error(`critical: ${critical.map((v) => `${v.id}(${v.count})`).join(', ')}`);
    if (process.env.A11Y_STRICT === '1' && serious.length) throw new Error(`serious: ${serious.map(v => `${v.id}(${v.count})`).join(', ')}`);
    return `0 critical / ${serious.length} serious`;
  }
  /** Press Tab until the focused element satisfies `test` (evaluated in the page), at most `max` times. */
  async function tabTo(page, test, max = 80) {
    for (let i = 0; i < max; i += 1) {
      await page.keyboard.press('Tab');
      if (await page.evaluate(test)) return i + 1;
    }
    throw new Error(`Tab không tới được phần tử mong muốn sau ${max} lần`);
  }
  const bodyText = (page) => page.evaluate(() => document.body.innerText);
  /** The dialog shows "MÃ ĐƠN: ORD-XXXXXXXX" (upper-cased by CSS) in one <span>; textContent keeps the real order number. */
  const orderNumberOf = async (dialog) => {
    const text = (await dialog.getByText(/Mã đơn:/i).first().textContent()) || '';
    const m = text.match(/ORD-[A-Za-z0-9-]+/);
    if (!m) throw new Error(`không thấy mã đơn trong: ${text.slice(0, 160)}`);
    return m[0];
  };
  // Thẻ lớp (CMP-1) là <article> có tiêu đề lớp. Trang chủ /classes là các rail (một lớp có thể nằm ở nhiều rail) nên lấy thẻ đầu;
  // danh mục đầy đủ (mỗi lớp đúng một thẻ, có chip lọc học phí) mở bằng "Xem tất cả lớp học".
  const classCards = (page, title) => page.locator('article', { has: page.getByRole('heading', { name: title, exact: true }) });
  const classCard = (page, title) => classCards(page, title).first();
  // Trang chủ chỉ hiện các rail chọn lọc (tối đa 6 thẻ mỗi rail: lớp của bạn / phổ biến / mới mở), nên một lớp cụ thể KHÔNG chắc
  // có mặt ở đó (vd. chủ lớp demo đã có nhiều lớp e2e5-* mới hơn) - chỉ chờ trang chủ dựng xong rail, còn lớp cụ thể tìm trong danh mục.
  const homeReady = (page) => page.locator('section[aria-labelledby^="rail-"] article').first().waitFor({ timeout: 15000 });
  const openCatalog = async (page) => {
    await page.getByRole('button', { name: 'Xem tất cả lớp học' }).click();
    await page.getByRole('heading', { name: 'Tất cả lớp học' }).waitFor({ timeout: 10000 });
  };

  // ----- tokens / ids from the seeded demo deployment -----
  const ownerToken = await apiLogin(OWNER, DEMO_PASSWORD);
  const PAID_ID = (await must('GET', `/classes/slug/${PAID_SLUG}`)).id;
  const settleOrder = (orderNumber) => must('POST', '/payments/mock/simulate', { token: ownerToken, body: { orderNumber, eventType: 'PAYMENT_SUCCESS' } });

  // =========================== P1: khách ===========================
  const guest = await person('guest');
  await step('P1', 'khách: lớp riêng tư KHÔNG có trong danh sách, lớp trả phí có nhãn giá, bộ lọc Miễn phí/Trả phí', guest.page, async () => {
    const g = guest.page;
    await g.goto(`${BASE}/classes`);
    await homeReady(g);
    if ((await bodyText(g)).includes(PRIVATE_TITLE)) throw new Error('lớp riêng tư xuất hiện trên trang chủ khám phá của khách');
    await openCatalog(g);
    await classCard(g, PAID_TITLE).waitFor({ timeout: 15000 });
    if ((await classCards(g, PAID_TITLE).count()) !== 1) throw new Error('danh mục đầy đủ lặp thẻ lớp trả phí');
    const text = await bodyText(g);
    if (text.includes(PRIVATE_TITLE)) throw new Error('lớp riêng tư xuất hiện trong danh sách khám phá của khách');
    const badge = await classCard(g, PAID_TITLE).innerText();
    if (!/Trả phí · 199\.000đ \/ 30 ngày/.test(badge)) throw new Error(`thiếu nhãn giá: ${badge.replace(/\n/g, ' | ')}`);
    const chips = g.getByRole('group', { name: 'Lọc theo học phí' });
    await chips.getByRole('button', { name: 'Trả phí' }).click();
    if ((await chips.getByRole('button', { name: 'Trả phí' }).getAttribute('aria-pressed')) !== 'true') throw new Error('chip Trả phí không được bật');
    if (!(await classCard(g, PAID_TITLE).isVisible())) throw new Error('lọc Trả phí làm mất lớp trả phí');
    const afterPaid = await bodyText(g);
    if (/Miễn phí\s*\n?\s*\/LOP-TOAN|Lớp Học Toán Nâng Cao/.test(afterPaid)) throw new Error('lọc Trả phí vẫn còn lớp miễn phí');
    await chips.getByRole('button', { name: 'Miễn phí' }).click();
    if (await classCards(g, PAID_TITLE).count()) throw new Error('lọc Miễn phí vẫn còn lớp trả phí');
    await chips.getByRole('button', { name: 'Tất cả' }).click();
    await shot(g, 'P1-explore-guest');
    return 'không lộ lớp riêng tư; nhãn giá + 3 chip lọc hoạt động';
  });

  await step('P1', 'khách mở thẳng URL lớp riêng tư -> "Không tìm thấy lớp học" (404), không nói "phiên hết hạn"', guest.page, async () => {
    const g = guest.page;
    const r = await api('GET', `/classes/slug/${PRIVATE_SLUG}`);
    if (r.status !== 404) throw new Error(`API trả ${r.status} thay vì 404`);
    await g.goto(`${BASE}/classes/${PRIVATE_SLUG}`);
    await g.getByRole('heading', { name: 'Không tìm thấy lớp học' }).waitFor({ timeout: 10000 });
    const text = await bodyText(g);
    if (/phiên|hết hạn/i.test(text)) throw new Error(`trang 404 nhắc tới phiên/hết hạn: ${text.slice(0, 200)}`);
    if (!(await g.getByRole('link', { name: 'Về danh sách lớp học' }).isVisible())) throw new Error('thiếu liên kết về danh sách');
    await shot(g, 'P1-private-404');
    return '404 thân thiện';
  });

  await step('P1', 'khách ở lớp trả phí: tường phí (giá, 30 ngày, quyền lợi), tab thành viên cũng hiện tường phí; "Đăng nhập để mua" quay lại đúng lớp', guest.page, async () => {
    const g = guest.page;
    await g.goto(`${BASE}/classes/${PAID_SLUG}/feed`);
    const card = g.getByTestId('paywall-card');
    await card.waitFor({ timeout: 15000 });
    const t = await card.innerText();
    if (!/199\.000đ/.test(t) || !/30 ngày/.test(t) || !/Bạn nhận được/i.test(t)) throw new Error(`tường phí thiếu nội dung: ${t.replace(/\n/g, ' | ')}`);
    await shot(g, 'P1-paywall-guest');
    await g.goto(`${BASE}/classes/${PAID_SLUG}/learn`);
    await g.getByTestId('paywall-card').waitFor({ timeout: 10000 });
    if (await g.locator('[role=alert]').count()) throw new Error('tab Khóa học của khách hiện [role=alert] thay vì tường phí');
    await g.goto(`${BASE}/classes/${PAID_SLUG}/feed`);
    await g.getByRole('button', { name: 'Đăng nhập để mua' }).click();
    await g.waitForURL(/\/login$/);
    await formLogin(g, STUDENT_FREE, DEMO_PASSWORD);
    await g.waitForURL(new RegExp(`/classes/${PAID_SLUG}/feed$`), { timeout: 15000 });
    await g.getByRole('button', { name: 'Mua để tham gia' }).waitFor({ timeout: 10000 });
    return 'đăng nhập xong quay lại đúng lớp, nút "Mua để tham gia"';
  });

  // =========================== P2: mã mời (demo) ===========================
  const guest2 = await person('guest-invite');
  await step('P2', 'khách mở /join/<mã demo>: thẻ lớp + meta referrer no-referrer; rời trang thì meta biến mất; mã không nằm trong tiêu đề', guest2.page, async () => {
    const g = guest2.page;
    await g.goto(`${BASE}/join/${DEMO_INVITE}`);
    await g.getByRole('heading', { name: PRIVATE_TITLE }).waitFor({ timeout: 15000 });
    const text = await bodyText(g);
    if (!text.includes('Thầy Nguyễn Chủ Nhiệm') || !text.includes('Miễn phí')) throw new Error('thẻ lớp thiếu chủ lớp / nhãn Miễn phí');
    if (!(await g.getByRole('link', { name: 'Đăng nhập để tham gia' }).isVisible())) throw new Error('thiếu "Đăng nhập để tham gia"');
    const referrer = await g.evaluate(() => Array.from(document.querySelectorAll('meta[name="referrer"]')).map((m) => m.content));
    if (!referrer.includes('no-referrer')) throw new Error(`meta referrer = ${JSON.stringify(referrer)}`);
    if ((await g.title()).includes(DEMO_INVITE)) throw new Error('mã nằm trong document.title');
    await shot(g, 'P2-invite-guest');
    await g.getByRole('link', { name: 'Khám phá lớp học' }).first().click();
    await g.waitForURL(/\/classes$/);
    // URL đổi ngay khi bấm, còn trang mời gỡ thẻ meta khi React gỡ trang (sau đó một nhịp render): chờ tối đa 3 s.
    const gone = await g.waitForFunction(() => document.querySelectorAll('meta[name="referrer"]').length === 0, null, { timeout: 3000 }).then(() => true, () => false);
    if (!gone) {
      const after = await g.evaluate(() => document.querySelectorAll('meta[name="referrer"]').length);
      throw new Error(`meta referrer còn ${after} thẻ sau khi rời trang mời`);
    }
    return 'referrer no-referrer khi ở trang mời, gỡ khi rời đi';
  });

  const free = await person('student-free');
  await step('P2', 'student.free đăng nhập từ trang mời -> Tham gia lớp -> vào bảng tin; Back không quay lại trang chứa mã', free.page, async () => {
    const s = free.page;
    await s.goto(`${BASE}/join/${DEMO_INVITE}`);
    await s.getByRole('link', { name: 'Đăng nhập để tham gia' }).click();
    await s.waitForURL(/\/login$/);
    await formLogin(s, STUDENT_FREE, DEMO_PASSWORD);
    await s.waitForURL(new RegExp(`/join/${DEMO_INVITE}$`), { timeout: 15000 });
    await s.getByRole('button', { name: 'Tham gia lớp' }).click();
    await s.waitForURL(new RegExp(`/classes/${PRIVATE_SLUG}/feed$`), { timeout: 15000 });
    await s.getByRole('heading', { name: PRIVATE_TITLE, level: 1 }).waitFor({ timeout: 10000 });
    if (!(await s.getByTestId('badge-private').isVisible())) throw new Error('thiếu nhãn Riêng tư trong lớp');
    await shot(s, 'P2-joined-private');
    await s.goBack();
    await s.waitForTimeout(500);
    if (/\/join\//.test(s.url())) throw new Error(`Back quay lại trang chứa mã: ${s.url().replace(DEMO_INVITE, '<mã>')}`);
    return 'URL có mã đã rời lịch sử (replace)';
  });

  await step('P2', 'đã là thành viên: mở lớp bằng URL thường không cần mã; mở lại trang mời vẫn vào thẳng lớp (idempotent)', free.page, async () => {
    const s = free.page;
    await s.goto(`${BASE}/classes/${PRIVATE_SLUG}/feed`);
    await s.getByRole('heading', { name: PRIVATE_TITLE, level: 1 }).waitFor({ timeout: 10000 });
    if (await s.getByRole('button', { name: /Tham gia/ }).count()) throw new Error('thành viên vẫn thấy nút tham gia');
    await s.goto(`${BASE}/join/${DEMO_INVITE}`);
    await s.getByRole('button', { name: 'Tham gia lớp' }).click();
    await s.waitForURL(new RegExp(`/classes/${PRIVATE_SLUG}/feed$`), { timeout: 15000 });
    const me = await apiLogin(STUDENT_FREE, DEMO_PASSWORD);
    const cls = await must('GET', `/classes/slug/${PRIVATE_SLUG}`, { token: me });
    if (cls.memberState !== 'ACTIVE') throw new Error(`memberState=${cls.memberState}`);
    return 'ACTIVE';
  });

  await step('P2', 'mã sai / mã hỏng -> "Mã mời không hợp lệ hoặc đã hết hạn" + liên kết về /classes (mã hỏng không gọi máy chủ)', guest2.page, async () => {
    const g = guest2.page;
    const requested = [];
    g.on('request', (r) => { if (/\/classes\/invites\//.test(r.url())) requested.push(r.url()); });
    await g.goto(`${BASE}/join/${'x'.repeat(32)}`);
    await g.getByRole('heading', { name: 'Mã mời không hợp lệ hoặc đã hết hạn' }).waitFor({ timeout: 10000 });
    if (!(await g.getByRole('link', { name: /Xem các lớp học công khai/ }).isVisible())) throw new Error('thiếu liên kết về /classes');
    await shot(g, 'P2-invite-invalid');
    const before = requested.length;
    await g.goto(`${BASE}/join/abc`);
    await g.getByRole('heading', { name: 'Mã mời không hợp lệ hoặc đã hết hạn' }).waitFor({ timeout: 10000 });
    if (requested.length !== before) throw new Error('mã hỏng vẫn gọi máy chủ');
    return `${requested.length} lượt gọi (chỉ mã đúng định dạng)`;
  });

  // =========================== P3: mua lớp trả phí ===========================
  const buyer = await newUser('buyer');
  let buyerOrder = null;
  await step('P3', 'người dùng mới: tường phí -> "Mua để tham gia" -> hộp thoại thanh toán chờ xác nhận', buyer.page, async () => {
    const b = buyer.page;
    await b.goto(`${BASE}/classes/${PAID_SLUG}/feed`);
    await b.getByTestId('paywall-card').waitFor({ timeout: 15000 });
    await shot(b, 'P3-paywall-user');
    await b.getByRole('button', { name: 'Mua để tham gia' }).click();
    const dialog = b.getByRole('dialog', { name: 'Trạng thái thanh toán' });
    await dialog.waitFor({ timeout: 10000 });
    const t = await dialog.innerText();
    if (!/chờ xác nhận/i.test(t)) throw new Error(`đơn không ở trạng thái chờ xác nhận: ${t.replace(/\n/g, ' | ')}`);
    buyerOrder = await orderNumberOf(dialog);
    await shot(b, 'P3-checkout-pending');
    return `đơn ${buyerOrder}`;
  });

  const ownerSession = await person('owner');
  const o = ownerSession.page;
  await step('P3', 'chủ lớp (owner@classroom.local) xác nhận thanh toán sandbox trong Studio > Shop & đơn hàng', o, async () => {
    await demoLogin(o, OWNER);
    await o.goto(`${BASE}/studio/classes/${PAID_ID}/store`);
    // Mỗi đơn là một <li> trong "Lịch sử đơn hàng"; trạng thái hiện bằng nhãn tiếng Việt (mã gốc nằm ở title).
    const row = o.getByRole('region', { name: /Lịch sử đơn hàng/ }).locator('li', { hasText: buyerOrder });
    await row.first().waitFor({ timeout: 15000 });
    await row.first().getByRole('button', { name: 'Xác nhận thanh toán sandbox' }).click();
    await row.first().getByText('Đã thanh toán', { exact: true }).waitFor({ timeout: 10000 });
    if ((await row.first().getByText('Đã thanh toán', { exact: true }).getAttribute('title')) !== 'PAID') throw new Error('nhãn trạng thái không ứng với PAID');
    return 'đơn PAID';
  });

  await step('P3', 'người mua làm mới trạng thái -> "Đã thanh toán thành công" -> Bắt đầu học: thành viên, hạn ~30 ngày, tab thành viên mở được', buyer.page, async () => {
    const b = buyer.page;
    const dialog = b.getByRole('dialog', { name: 'Trạng thái thanh toán' });
    await dialog.getByRole('button', { name: 'Làm mới trạng thái đơn hàng' }).click();
    await dialog.getByText('Đã thanh toán thành công').waitFor({ timeout: 10000 });
    await b.getByTestId('paywall-card').waitFor({ state: 'detached', timeout: 10000 });
    await shot(b, 'P3-checkout-paid');
    await dialog.getByRole('button', { name: 'Bắt đầu học' }).click();
    const token = await apiLogin(buyer.user.email, PASSWORD);
    const cls = await must('GET', `/classes/slug/${PAID_SLUG}`, { token });
    if (cls.memberState !== 'ACTIVE' || !cls.isMember) throw new Error(`memberState=${cls.memberState}`);
    const days = (Date.parse(cls.accessExpiresAt) - Date.now()) / DAY;
    if (days < 29 || days > 30.1) throw new Error(`hạn truy cập còn ${days.toFixed(2)} ngày`);
    await b.goto(`${BASE}/classes/${PAID_SLUG}/members`);
    await b.getByRole('heading', { name: /Thành viên/ }).first().waitFor({ timeout: 10000 });
    if (await b.getByTestId('paywall-card').count()) throw new Error('thành viên vẫn thấy tường phí');
    buyer.token = token;
    return `hạn còn ${days.toFixed(1)} ngày`;
  });

  // =========================== P4: hết hạn (hoàn tiền) + gia hạn ===========================
  await step('P4', 'chủ lớp hoàn tiền đơn (Studio) -> người mua thành EXPIRED', o, async () => {
    await o.goto(`${BASE}/studio/classes/${PAID_ID}/store`);
    const row = o.getByRole('region', { name: /Lịch sử đơn hàng/ }).locator('li', { hasText: buyerOrder }).first();
    await row.waitFor({ timeout: 15000 });
    await row.getByRole('button', { name: 'Hoàn tiền sandbox' }).click();
    // Xác nhận giờ là hộp thoại trong trang (thay cho window.confirm), nêu mã đơn.
    const confirm = o.getByRole('alertdialog', { name: 'Hoàn tiền đơn sandbox?' });
    await confirm.getByText(buyerOrder).waitFor({ timeout: 5000 });
    await confirm.getByRole('button', { name: 'Hoàn tiền', exact: true }).click();
    await row.getByText('Đã hoàn tiền', { exact: true }).waitFor({ timeout: 10000 });
    const cls = await must('GET', `/classes/slug/${PAID_SLUG}`, { token: buyer.token });
    if (cls.memberState !== 'EXPIRED') throw new Error(`memberState=${cls.memberState}`);
    return 'EXPIRED';
  });

  await step('P4', 'người mua: banner hết hạn ngày dd/MM/yyyy, chỉ còn tab Giới thiệu/Shop, tab thành viên hiện lời nhắc gia hạn (không 403 thô)', buyer.page, async () => {
    const b = buyer.page;
    await b.goto(`${BASE}/classes/${PAID_SLUG}/about`);
    const banner = b.getByTestId('renewal-banner');
    await banner.waitFor({ timeout: 15000 });
    const today = ddmmyyyy(new Date());
    const tb = await banner.innerText();
    if (!tb.includes(`Gói thành viên lớp đã hết hạn ngày ${today}`)) throw new Error(`banner: ${tb.replace(/\n/g, ' | ')} (mong ${today})`);
    const tabs = (await b.locator('a[href^="/classes/lop-tra-phi/"]').allInnerTexts()).map((t) => t.trim());
    for (const want of ['Giới thiệu', 'Shop']) if (!tabs.includes(want)) throw new Error(`thiếu tab ${want}: ${tabs}`);
    for (const nope of ['Blog', 'Thảo luận', 'Khóa học', 'Thi', 'Sự kiện', 'Bảng xếp hạng', 'Tài liệu', 'Thành viên']) if (tabs.includes(nope)) throw new Error(`vẫn còn tab ${nope}`);
    await shot(b, 'P4-expired-banner');
    for (const t of ['feed', 'learn', 'exams', 'members']) {
      await b.goto(`${BASE}/classes/${PAID_SLUG}/${t}`);
      await b.getByTestId('renewal-card').waitFor({ timeout: 10000 });
      const txt = await bodyText(b);
      if (/403|Forbidden|Failed to fetch|Request failed/i.test(txt)) throw new Error(`tab ${t} lộ lỗi thô: ${txt.slice(0, 160)}`);
    }
    await shot(b, 'P4-expired-card');
    // máy chủ thật sự trả 403 MEMBERSHIP_EXPIRED cho nội dung thành viên
    const posts = await api('GET', `/classes/${PAID_ID}/members`, { token: buyer.token });
    if (posts.status !== 403 || posts.error.code !== 'MEMBERSHIP_EXPIRED') throw new Error(`API: ${posts.status} ${JSON.stringify(posts.error)}`);
    return `banner ${today}; 403 MEMBERSHIP_EXPIRED ở API`;
  });

  await step('P4', 'Studio > Thành viên: bộ lọc "Đã hết hạn" liệt kê người mua với "Hết hạn dd/MM/yyyy" và nút xóa/chặn', o, async () => {
    await o.goto(`${BASE}/studio/classes/${PAID_ID}/members`);
    await o.getByLabel('Lọc theo trạng thái').selectOption('EXPIRED');
    const row = o.locator('div.p-5', { hasText: buyer.user.name });
    await row.waitFor({ timeout: 10000 });
    const t = await row.innerText();
    if (!t.includes('Đã hết hạn') || !t.includes(`Hết hạn ${ddmmyyyy(new Date())}`)) throw new Error(`hàng thành viên: ${t.replace(/\n/g, ' | ')}`);
    if (!(await row.getByLabel(`Xóa ${buyer.user.name} khỏi lớp`).isVisible())) throw new Error('thiếu nút xóa cho thành viên hết hạn');
    if (!(await row.getByLabel(`Chặn ${buyer.user.name}`).isVisible())) throw new Error('thiếu nút chặn cho thành viên hết hạn');
    await shot(o, 'P4-studio-expired-filter');
    return 'EXPIRED hiện đủ';
  });

  await step('P4', 'gia hạn: "Gia hạn" -> đơn mới -> chủ lớp xác nhận -> người mua vào lại, hạn ~30 ngày', buyer.page, async () => {
    const b = buyer.page;
    await b.goto(`${BASE}/classes/${PAID_SLUG}/feed`);
    await b.getByTestId('renewal-card').getByRole('button', { name: 'Gia hạn' }).click();
    const dialog = b.getByRole('dialog', { name: 'Trạng thái thanh toán' });
    const order = await orderNumberOf(dialog);
    if (order === buyerOrder) throw new Error('gia hạn dùng lại đơn cũ');
    await settleOrder(order);
    await dialog.getByRole('button', { name: 'Làm mới trạng thái đơn hàng' }).click();
    await dialog.getByText('Đã thanh toán thành công').waitFor({ timeout: 10000 });
    await dialog.getByRole('button', { name: 'Bắt đầu học' }).click();
    await b.getByTestId('renewal-card').waitFor({ state: 'detached', timeout: 10000 });
    const cls = await must('GET', `/classes/slug/${PAID_SLUG}`, { token: buyer.token });
    const days = (Date.parse(cls.accessExpiresAt) - Date.now()) / DAY;
    if (cls.memberState !== 'ACTIVE' || days < 29 || days > 30.1) throw new Error(`${cls.memberState} còn ${days.toFixed(2)} ngày`);
    return `ACTIVE, còn ${days.toFixed(1)} ngày`;
  });

  // =========================== P5: Studio trên lớp riêng mới tạo ===========================
  let SLUG = null; // máy chủ sinh slug từ tên lớp (bỏ dấu): đọc lại sau khi tạo
  const TITLE = `E2E5 Lớp ${RUN}`;
  let CLASS_ID = null;
  let linkA = null;
  let codeA = null;

  await step('P5', 'chủ lớp tạo lớp RIÊNG TƯ ở trang /classes/new (chọn "Riêng tư"): khách không thấy trong danh sách / URL', o, async () => {
    await o.goto(`${BASE}/classes/new`);
    const visibility = o.getByRole('group', { name: 'Ai thấy lớp' });
    await visibility.waitFor({ timeout: 10000 });
    if ((await visibility.getByRole('button', { name: /^Công khai/ }).getAttribute('aria-pressed')) !== 'true') throw new Error('mặc định phải là Công khai');
    const created = await createClassViaUI(o, { title: TITLE, category: 'Ôn thi', visibility: 'PRIVATE' });
    SLUG = created.slug;
    if (SLUG !== `e2e5-lop-${RUN}`) throw new Error(`slug ${SLUG} (mong đợi e2e5-lop-${RUN})`);
    await o.getByTestId('badge-private').waitFor({ timeout: 10000 });
    CLASS_ID = (await must('GET', `/classes/slug/${SLUG}`, { token: ownerToken })).id;
    if (CLASS_ID !== created.classId) throw new Error(`classId ${created.classId} != ${CLASS_ID}`);
    const anon = await api('GET', `/classes/slug/${SLUG}`);
    if (anon.status !== 404) throw new Error(`khách xem được lớp riêng: ${anon.status}`);
    const list = await api('GET', '/classes?page=0&size=100');
    if (list.data.some((c) => c.slug === SLUG)) throw new Error('lớp riêng tư xuất hiện trong danh sách công khai');
    return `lớp ${CLASS_ID}`;
  });

  await step('P5', 'Cài đặt: đổi Riêng tư -> Công khai BẰNG BÀN PHÍM (có xác nhận), lớp hiện trong khám phá; rồi lại Riêng tư', o, async () => {
    await o.goto(`${BASE}/studio/classes/${CLASS_ID}/settings`);
    const section = o.getByRole('region', { name: /Hiển thị & tham gia/ });
    await section.waitFor({ timeout: 15000 });
    // Chỉ dùng bàn phím: Tab tới nhóm radio (rơi vào radio đang chọn "Riêng tư"), mũi tên lên chọn "Công khai", Tab tới nút xác nhận, Enter
    await o.evaluate(() => document.activeElement && document.activeElement.blur());
    await tabTo(o, () => {
      const a = document.activeElement;
      return !!a && a.tagName === 'INPUT' && a.type === 'radio' && !!a.closest('section') && /Hiển thị & tham gia/.test(a.closest('section').innerText);
    });
    await o.keyboard.press('ArrowUp');
    const confirm = section.getByRole('group', { name: 'Xác nhận đổi chế độ hiển thị' });
    await confirm.waitFor({ timeout: 5000 });
    const ct = await confirm.innerText();
    if (!/hiện trong danh sách khám phá/.test(ct) || !/ai cũng có thể tham gia/.test(ct)) throw new Error(`thiếu giải thích hệ quả: ${ct}`);
    await shot(o, 'P5-visibility-confirm');
    await tabTo(o, () => /Xác nhận đổi chế độ/.test((document.activeElement && document.activeElement.innerText) || ''));
    await o.keyboard.press('Enter');
    await section.getByTestId('badge-public').waitFor({ timeout: 10000 });
    const list = await api('GET', '/classes?page=0&size=100');
    if (!list.data.some((c) => c.slug === SLUG)) throw new Error('lớp công khai không có trong danh sách');
    // trở lại Riêng tư bằng chuột
    await section.getByRole('radio', { name: /Riêng tư/ }).check();
    await section.getByRole('button', { name: 'Xác nhận đổi chế độ' }).click();
    await section.getByTestId('badge-private').waitFor({ timeout: 10000 });
    const list2 = await api('GET', '/classes?page=0&size=100');
    if (list2.data.some((c) => c.slug === SLUG)) throw new Error('lớp riêng tư vẫn nằm trong danh sách');
    return 'hiện/ẩn đúng';
  });

  const joiner1 = await newUser('joiner1');
  await step('P5', 'Thành viên > Mời thành viên: tạo liên kết (7 ngày, tối đa 3 lượt) -> hiện MỘT lần, cảnh báo, Sao chép, đóng', o, async () => {
    await o.goto(`${BASE}/studio/classes/${CLASS_ID}/members`);
    const section = o.getByRole('region', { name: 'Mời thành viên' });
    await section.waitFor({ timeout: 15000 });
    if (!/liên kết mời là cách duy nhất/.test(await section.innerText())) throw new Error('thiếu giải thích lớp riêng tư');
    await section.getByLabel('Hạn dùng').selectOption('7');
    await section.getByLabel('Số lượt dùng tối đa').fill('3');
    await section.getByRole('button', { name: 'Tạo liên kết mời' }).click();
    const dialog = o.getByRole('dialog', { name: 'Liên kết mời đã được tạo' });
    await dialog.waitFor({ timeout: 10000 });
    linkA = await dialog.getByLabel('Liên kết mời').inputValue();
    if (!new RegExp(`^${BASE.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}/join/[A-Za-z0-9_-]{32}$`).test(linkA)) throw new Error(`liên kết lạ: ${linkA}`);
    codeA = linkA.split('/join/')[1];
    if (!/Chỉ hiển thị một lần/.test(await dialog.innerText())) throw new Error('thiếu cảnh báo một lần');
    await shot(o, 'P5-invite-created');
    await dialog.getByRole('button', { name: 'Sao chép liên kết' }).click();
    await dialog.getByText('Đã sao chép liên kết vào bộ nhớ tạm.').waitFor({ timeout: 5000 });
    const clip = await o.evaluate(() => navigator.clipboard.readText());
    if (clip !== linkA) throw new Error('nội dung bộ nhớ tạm khác liên kết');
    await dialog.getByRole('button', { name: 'Đã lưu, đóng' }).click();
    await dialog.waitFor({ state: 'detached' });
    await o.reload();
    await o.getByRole('region', { name: 'Mời thành viên' }).getByRole('table').waitFor({ timeout: 10000 });
    if ((await bodyText(o)).includes(codeA)) throw new Error('mã đầy đủ còn hiện trên trang sau khi đóng/tải lại');
    const row = o.getByRole('region', { name: 'Mời thành viên' }).getByRole('row').nth(1);
    const rt = await row.innerText();
    if (!rt.includes('Đang hiệu lực') || !rt.includes(`…${codeA.slice(-4)}`) || !/0 \/ 3/.test(rt)) throw new Error(`hàng mã mời: ${rt.replace(/\s+/g, ' ')}`);
    return `…${codeA.slice(-4)}`;
  });

  await step('P5', 'Escape đóng hộp thoại liên kết và trả tiêu điểm về nút "Tạo liên kết mời"', o, async () => {
    const section = o.getByRole('region', { name: 'Mời thành viên' });
    await section.getByRole('button', { name: 'Tạo liên kết mời' }).click();
    const dialog = o.getByRole('dialog', { name: 'Liên kết mời đã được tạo' });
    await dialog.waitFor({ timeout: 10000 });
    await o.keyboard.press('Escape');
    await dialog.waitFor({ state: 'detached' });
    const focused = await o.evaluate(() => (document.activeElement && document.activeElement.innerText) || '');
    if (!/Tạo liên kết mời/.test(focused)) throw new Error(`tiêu điểm đang ở "${focused}"`);
    return 'Escape + trả tiêu điểm';
  });

  await step('P5', 'joiner1 mở liên kết (lớp riêng tư miễn phí) -> Tham gia lớp -> vào lớp; lượt dùng 1/3', joiner1.page, async () => {
    const j = joiner1.page;
    await j.goto(linkA);
    await j.getByRole('heading', { name: TITLE }).waitFor({ timeout: 15000 });
    await j.getByRole('button', { name: 'Tham gia lớp' }).click();
    await j.waitForURL(new RegExp(`/classes/${SLUG}/feed$`), { timeout: 15000 });
    joiner1.token = await apiLogin(joiner1.user.email, PASSWORD);
    const cls = await must('GET', `/classes/slug/${SLUG}`, { token: joiner1.token });
    if (cls.memberState !== 'ACTIVE' || cls.accessExpiresAt) throw new Error(`${cls.memberState} ${cls.accessExpiresAt}`);
    await o.goto(`${BASE}/studio/classes/${CLASS_ID}/members`);
    const table = o.getByRole('region', { name: 'Mời thành viên' }).getByRole('table');
    await table.waitFor({ timeout: 10000 });
    if (!/1 \/ 3/.test(await table.innerText())) throw new Error('usedCount không tăng lên 1/3');
    return '1/3';
  });

  await step('P5', 'Cài đặt > Hình thức vào lớp: Miễn phí -> Trả phí 50.000đ/7 ngày (xác nhận nêu "giữ quyền miễn phí trọn đời"); thành viên cũ không bị đụng', o, async () => {
    await o.goto(`${BASE}/studio/classes/${CLASS_ID}/settings`);
    const section = o.getByRole('region', { name: /Hình thức vào lớp/ });
    await section.waitFor({ timeout: 15000 });
    await section.getByRole('radio', { name: /Trả phí/ }).check();
    // giá 0 bị chặn trước khi gọi máy chủ
    await section.getByLabel('Giá vào lớp (VND)').fill('0');
    await section.getByRole('button', { name: 'Lưu hình thức thu phí' }).click();
    await section.getByText('Giá vào lớp phải là số nguyên đồng lớn hơn 0.').waitFor({ timeout: 5000 });
    await section.getByLabel('Giá vào lớp (VND)').fill('50000');
    await section.getByLabel('Thời hạn (ngày)').fill('7');
    await section.getByRole('button', { name: 'Lưu hình thức thu phí' }).click();
    const confirm = section.getByRole('group', { name: 'Xác nhận đổi hình thức thu phí' });
    await confirm.waitFor({ timeout: 5000 });
    const ct = await confirm.innerText();
    if (!/giữ quyền truy cập miễn phí trọn đời/.test(ct) || !/50\.000đ \/ 7 ngày/.test(ct)) throw new Error(`xác nhận: ${ct}`);
    await shot(o, 'P5-access-confirm');
    await confirm.getByRole('button', { name: 'Xác nhận thay đổi' }).click();
    await section.getByTestId('access-summary').getByText('50.000đ / 7 ngày').waitFor({ timeout: 10000 });
    await o.getByTestId('badge-paid').first().waitFor({ timeout: 10000 });
    const cls = await must('GET', `/classes/slug/${SLUG}`, { token: joiner1.token });
    if (cls.memberState !== 'ACTIVE' || cls.accessExpiresAt) throw new Error(`thành viên cũ bị đụng: ${cls.memberState} ${cls.accessExpiresAt}`);
    await shot(o, 'P5-settings-paid');
    return 'thành viên cũ giữ quyền không hạn';
  });

  const joiner2 = await newUser('joiner2');
  await step('P5', 'joiner2 mở CÙNG liên kết của lớp RIÊNG TƯ + TRẢ PHÍ: thẻ có giá -> "Mua để tham gia" (mã mời đi kèm đơn) -> chủ lớp xác nhận -> vào lớp', joiner2.page, async () => {
    const j = joiner2.page;
    await j.goto(linkA);
    await j.getByRole('heading', { name: TITLE }).waitFor({ timeout: 15000 });
    const price = await j.getByTestId('invite-price').innerText();
    if (!/50\.000đ \/ 7 ngày/.test(price)) throw new Error(`giá trên thẻ: ${price}`);
    await shot(j, 'P5-invite-paid');
    const token = await apiLogin(joiner2.user.email, PASSWORD);
    await j.getByRole('button', { name: 'Mua để tham gia' }).click();
    const dialog = j.getByRole('dialog', { name: 'Trạng thái thanh toán' });
    await dialog.waitFor({ timeout: 10000 });
    const order = await orderNumberOf(dialog);
    await settleOrder(order);
    await dialog.getByRole('button', { name: 'Làm mới trạng thái đơn hàng' }).click();
    await dialog.getByText('Đã thanh toán thành công').waitFor({ timeout: 10000 });
    await dialog.getByRole('button', { name: 'Vào lớp học' }).click();
    await j.waitForURL(new RegExp(`/classes/${SLUG}/feed$`), { timeout: 15000 });
    joiner2.token = token;
    const cls = await must('GET', `/classes/slug/${SLUG}`, { token });
    const days = (Date.parse(cls.accessExpiresAt) - Date.now()) / DAY;
    if (cls.memberState !== 'ACTIVE' || days < 6 || days > 7.1) throw new Error(`${cls.memberState} ${days}`);
    // hạn còn <= 7 ngày: chip "Sắp hết hạn" dịu kèm liên kết Gia hạn (tới tab Shop, nơi "Gia hạn thêm" dùng được)
    const chip = j.getByTestId('expiry-chip');
    await chip.waitFor({ timeout: 10000 });
    if (!/Sắp hết hạn/.test(await chip.innerText())) throw new Error('chip không ghi Sắp hết hạn');
    await shot(j, 'P5-expiry-chip');
    await chip.getByRole('link', { name: 'Gia hạn' }).click();
    await j.waitForURL(new RegExp(`/classes/${SLUG}/store$`), { timeout: 10000 });
    await j.getByTestId('class-access-product').getByRole('button', { name: 'Gia hạn thêm' }).waitFor({ timeout: 10000 });
    await j.goBack();
    await j.goBack();
    if (/\/join\//.test(j.url())) throw new Error('Back quay lại trang chứa mã');
    return `ACTIVE ${days.toFixed(1)} ngày`;
  });

  const joiner3 = await newUser('joiner3');
  await step('P5', 'thu hồi liên kết trong Studio (có xác nhận) -> trang mời báo không hợp lệ; đặt hàng vòng bằng mã đã thu hồi bị 404', o, async () => {
    await o.goto(`${BASE}/studio/classes/${CLASS_ID}/members`);
    const section = o.getByRole('region', { name: 'Mời thành viên' });
    await section.getByRole('table').waitFor({ timeout: 10000 });
    await section.getByRole('button', { name: `Thu hồi liên kết mời …${codeA.slice(-4)}` }).click();
    const dialog = o.getByRole('alertdialog', { name: 'Thu hồi liên kết mời?' });
    await dialog.waitFor({ timeout: 5000 });
    await shot(o, 'P5-revoke-confirm');
    await dialog.getByRole('button', { name: 'Thu hồi liên kết' }).click();
    await dialog.waitFor({ state: 'detached' });
    await section.getByText('Đã thu hồi').waitFor({ timeout: 10000 });
    const j = joiner3.page;
    await j.goto(linkA);
    await j.getByRole('heading', { name: 'Mã mời không hợp lệ hoặc đã hết hạn' }).waitFor({ timeout: 10000 });
    const product = (await must('GET', `/classes/slug/${SLUG}`, { token: joiner2.token })).accessProduct;
    const token3 = await apiLogin(joiner3.user.email, PASSWORD);
    const order = await api('POST', '/orders', { token: token3, body: { classId: CLASS_ID, productId: product.id, idempotencyKey: `e2e5-${RUN}-revoked`, inviteCode: codeA } });
    if (order.status !== 404) throw new Error(`đặt hàng bằng mã đã thu hồi trả ${order.status}`);
    const join = await api('POST', `/classes/invites/${codeA}/join`, { token: token3 });
    if (join.status !== 404) throw new Error(`tham gia bằng mã đã thu hồi trả ${join.status}`);
    await shot(j, 'P5-invite-revoked');
    return 'mã đã thu hồi: 404 ở trang, đơn và tham gia';
  });

  // =========================== P6: trợ năng + bàn phím ===========================
  await step('P6', 'axe: tường phí của khách, trang mời của khách và của người đã đăng nhập, thẻ gia hạn', guest.page, async () => {
    const a = await person('axe-guest');
    const results = [];
    await a.page.goto(`${BASE}/classes/${PAID_SLUG}/feed`);
    await a.page.getByTestId('paywall-card').waitFor({ timeout: 15000 });
    results.push(`paywall ${await axe(a.page, 'paywall-guest')}`);
    await a.page.goto(`${BASE}/join/${DEMO_INVITE}`);
    await a.page.getByRole('heading', { name: PRIVATE_TITLE }).waitFor({ timeout: 15000 });
    results.push(`invite-guest ${await axe(a.page, 'invite-guest')}`);
    await a.page.goto(`${BASE}/join/${'y'.repeat(32)}`);
    await a.page.getByRole('heading', { name: 'Mã mời không hợp lệ hoặc đã hết hạn' }).waitFor({ timeout: 15000 });
    results.push(`invite-invalid ${await axe(a.page, 'invite-invalid')}`);
    await a.page.goto(`${BASE}/classes/${PRIVATE_SLUG}`);
    await a.page.getByRole('heading', { name: 'Không tìm thấy lớp học' }).waitFor({ timeout: 15000 });
    results.push(`404 ${await axe(a.page, 'class-404')}`);
    await joiner3.page.goto(`${BASE}/classes/${PAID_SLUG}/feed`);
    await joiner3.page.getByTestId('paywall-card').waitFor({ timeout: 15000 });
    results.push(`paywall-user ${await axe(joiner3.page, 'paywall-user')}`);
    return results.join('; ');
  });

  await step('P6', 'axe: khám phá với chip lọc + hộp thoại tạo lớp, Studio Cài đặt (xác nhận mở), Thành viên (mã mời + hộp thoại liên kết + hộp thoại thu hồi)', o, async () => {
    const results = [];
    await o.goto(`${BASE}/classes`);
    await homeReady(o);
    results.push(`explore ${await axe(o, 'explore-owner')}`);
    await openCatalog(o);
    await classCard(o, PAID_TITLE).waitFor({ timeout: 15000 });
    await o.getByRole('group', { name: 'Lọc theo học phí' }).waitFor();
    results.push(`catalog ${await axe(o, 'explore-catalog-chips')}`);
    await o.getByRole('button', { name: 'Về trang chủ' }).click();
    await o.getByRole('link', { name: 'Tạo lớp học mới' }).click();
    await o.waitForURL(/\/classes\/new$/);
    await o.getByRole('heading', { name: 'Tạo lớp học', level: 1 }).waitFor();
    results.push(`create-page ${await axe(o, 'create-class-page')}`);
    await o.getByRole('group', { name: 'Học phí' }).getByRole('button', { name: /^Có phí/ }).click();
    await o.getByLabel('Phí mỗi tháng · bắt buộc').waitFor();
    await o.waitForTimeout(400); // ô phí hiện bằng hiệu ứng mờ dần 0.25 s - đo độ tương phản sau khi hiệu ứng xong
    results.push(`create-page-paid ${await axe(o, 'create-class-page-paid')}`);
    await o.getByRole('button', { name: 'Xem trên điện thoại' }).click();
    await o.getByTestId('preview-mobile').waitFor();
    results.push(`create-page-phone-preview ${await axe(o, 'create-class-page-phone-preview')}`);
    await o.goto(`${BASE}/studio/classes/${CLASS_ID}/settings`);
    await o.getByRole('region', { name: /Hiển thị & tham gia/ }).waitFor({ timeout: 15000 });
    await o.getByRole('region', { name: /Hiển thị & tham gia/ }).getByRole('radio', { name: /Công khai/ }).check();
    await o.getByRole('group', { name: 'Xác nhận đổi chế độ hiển thị' }).waitFor();
    await o.getByRole('region', { name: /Hình thức vào lớp/ }).getByRole('radio', { name: /Miễn phí/ }).check();
    await o.getByRole('region', { name: /Hình thức vào lớp/ }).getByRole('button', { name: 'Lưu hình thức thu phí' }).click();
    await o.getByRole('group', { name: 'Xác nhận đổi hình thức thu phí' }).waitFor();
    await shot(o, 'P6-settings-both-confirm');
    results.push(`settings ${await axe(o, 'studio-settings-confirmations')}`);
    await o.goto(`${BASE}/studio/classes/${CLASS_ID}/members`);
    const section = o.getByRole('region', { name: 'Mời thành viên' });
    await section.getByRole('table').waitFor({ timeout: 10000 });
    results.push(`invites ${await axe(o, 'studio-members-invites')}`);
    await section.getByRole('button', { name: 'Tạo liên kết mời' }).click();
    await o.getByRole('dialog', { name: 'Liên kết mời đã được tạo' }).waitFor();
    results.push(`invite-dialog ${await axe(o, 'invite-created-dialog')}`);
    await o.keyboard.press('Escape');
    await section.getByRole('button', { name: /Thu hồi liên kết mời/ }).first().click();
    await o.getByRole('alertdialog', { name: 'Thu hồi liên kết mời?' }).waitFor();
    results.push(`revoke-dialog ${await axe(o, 'invite-revoke-dialog')}`);
    await o.keyboard.press('Escape');
    return results.join('; ');
  });

  await step('P6', 'bàn phím: Tab tới "Đăng nhập để mua" (tường phí) và "Đăng nhập để tham gia" (trang mời) rồi Enter', guest.page, async () => {
    const k = await person('keyboard');
    await k.page.goto(`${BASE}/classes/${PAID_SLUG}/feed`);
    await k.page.getByTestId('paywall-card').waitFor({ timeout: 15000 });
    await tabTo(k.page, () => /Đăng nhập để mua/.test((document.activeElement && document.activeElement.innerText) || ''));
    await k.page.keyboard.press('Enter');
    await k.page.waitForURL(/\/login$/, { timeout: 10000 });
    await k.page.goto(`${BASE}/join/${DEMO_INVITE}`);
    await k.page.getByRole('heading', { name: PRIVATE_TITLE }).waitFor({ timeout: 15000 });
    await tabTo(k.page, () => /Đăng nhập để tham gia/.test((document.activeElement && document.activeElement.innerText) || ''));
    await k.page.keyboard.press('Enter');
    await k.page.waitForURL(/\/login$/, { timeout: 10000 });
    return 'Tab + Enter tới được nút chính ở tường phí và trang mời';
  });

  // =========================== P7: 390px ===========================
  await step('P7', 'điện thoại 390px: trang mời, tường phí, danh sách lớp, Studio mã mời không tràn ngang; nút chính nằm trọn trong màn hình', null, async () => {
    const m = await person('mobile', { width: 390, height: 844 });
    const notes = [];
    const check = async (label, url, ready, cta) => {
      await m.page.goto(url);
      await ready(m.page);
      await settle(m.page, 400);
      const ov = await overflowCheck(m.page);
      if (ov.over > 1) throw new Error(`${label}: tràn ngang ${ov.over}px (${ov.offenders.join(' ; ')})`);
      if (cta) {
        const box = await cta(m.page).boundingBox();
        if (!box || box.x < 0 || box.x + box.width > 391) throw new Error(`${label}: nút chính ngoài màn hình ${JSON.stringify(box)}`);
      }
      await shot(m.page, `P7-${label}`);
      notes.push(label);
    };
    await check('invite', `${BASE}/join/${DEMO_INVITE}`, (p) => p.getByRole('heading', { name: PRIVATE_TITLE }).waitFor({ timeout: 15000 }), (p) => p.getByRole('link', { name: 'Đăng nhập để tham gia' }));
    await check('invite-invalid', `${BASE}/join/${'z'.repeat(32)}`, (p) => p.getByRole('heading', { name: 'Mã mời không hợp lệ hoặc đã hết hạn' }).waitFor({ timeout: 15000 }));
    await check('paywall', `${BASE}/classes/${PAID_SLUG}/feed`, (p) => p.getByTestId('paywall-card').waitFor({ timeout: 15000 }), (p) => p.getByRole('button', { name: 'Đăng nhập để mua' }));
    await check('not-found', `${BASE}/classes/${PRIVATE_SLUG}`, (p) => p.getByRole('heading', { name: 'Không tìm thấy lớp học' }).waitFor({ timeout: 15000 }));
    await check('explore', `${BASE}/classes`, (p) => homeReady(p));
    // trang tạo lớp toàn màn hình: ở 390px cột xem trước ẩn, ô ảnh nằm trong form; nút "Tạo lớp học" nằm trọn trong màn hình
    await demoLogin(m.page, OWNER);
    await check('create-class', `${BASE}/classes/new`, async (p) => {
      await p.getByRole('heading', { name: 'Tạo lớp học', level: 1 }).waitFor({ timeout: 15000 });
      await p.getByTestId('narrow-media').waitFor({ timeout: 5000 });
    }, (p) => p.getByRole('button', { name: 'Tạo lớp học', exact: true }));
    await check('explore-catalog', `${BASE}/classes`, async (p) => { await openCatalog(p); await classCard(p, PAID_TITLE).waitFor({ timeout: 15000 }); });
    // người đã đăng nhập: thẻ gia hạn + studio
    const mo = await person('mobile-owner', { width: 390, height: 844 });
    await demoLogin(mo.page, OWNER);
    await mo.page.goto(`${BASE}/studio/classes/${CLASS_ID}/members`);
    await mo.page.getByRole('region', { name: 'Mời thành viên' }).getByRole('table').waitFor({ timeout: 15000 });
    const ov = await overflowCheck(mo.page);
    if (ov.over > 1) throw new Error(`studio-members: tràn ngang ${ov.over}px (${ov.offenders.join(' ; ')})`);
    await shot(mo.page, 'P7-studio-invites');
    await mo.page.goto(`${BASE}/studio/classes/${CLASS_ID}/settings`);
    await mo.page.getByRole('region', { name: /Hiển thị & tham gia/ }).waitFor({ timeout: 15000 });
    const ov2 = await overflowCheck(mo.page);
    if (ov2.over > 1) throw new Error(`studio-settings: tràn ngang ${ov2.over}px (${ov2.offenders.join(' ; ')})`);
    await shot(mo.page, 'P7-studio-settings');
    notes.push('studio-members', 'studio-settings');
    return notes.join(', ');
  });

  await step('P7', 'điện thoại 390px: thẻ gia hạn (thành viên hết hạn) không tràn ngang', null, async () => {
    // người mua ở P3/P4 đã gia hạn lại; hoàn tiền một lần nữa để có trạng thái EXPIRED dùng chụp ảnh điện thoại
    const orders = await must('GET', `/me/orders?classId=${PAID_ID}`, { token: buyer.token });
    const paid = orders.find((x) => x.status === 'PAID');
    if (!paid) throw new Error('không có đơn PAID để hoàn tiền');
    await must('POST', '/payments/mock/simulate', { token: ownerToken, body: { orderNumber: paid.orderNumber, eventType: 'PAYMENT_REFUNDED' } });
    const m = await person('mobile-expired', { width: 390, height: 844 });
    await m.page.goto(`${BASE}/login`);
    await formLogin(m.page, buyer.user.email, PASSWORD);
    await m.page.waitForURL(/\/classes$/, { timeout: 15000 });
    await m.page.goto(`${BASE}/classes/${PAID_SLUG}/feed`);
    await m.page.getByTestId('renewal-card').waitFor({ timeout: 15000 });
    await settle(m.page, 400);
    const ov = await overflowCheck(m.page);
    if (ov.over > 1) throw new Error(`renewal: tràn ngang ${ov.over}px (${ov.offenders.join(' ; ')})`);
    const box = await m.page.getByTestId('renewal-card').getByRole('button', { name: 'Gia hạn' }).boundingBox();
    if (!box || box.x < 0 || box.x + box.width > 391) throw new Error(`nút Gia hạn ngoài màn hình ${JSON.stringify(box)}`);
    await shot(m.page, 'P7-renewal-mobile');
    const axeResult = await axe(m.page, 'renewal-card');
    await m.page.goto(`${BASE}/classes/${PAID_SLUG}/about`);
    await m.page.getByTestId('renewal-banner').waitFor({ timeout: 15000 });
    const ov2 = await overflowCheck(m.page);
    if (ov2.over > 1) throw new Error(`renewal-banner: tràn ngang ${ov2.over}px`);
    await shot(m.page, 'P7-renewal-banner-mobile');
    return `axe ${axeResult}`;
  });

  // =========================== P9: duyệt thành viên (lớp hạt giống lop-duyet-thanh-vien) ===========================
  // Dùng một người dùng MỚI mỗi lần chạy (student.free sẽ thành thành viên sau lần chạy đầu, nên không lặp lại được).
  const APPROVAL_SLUG = 'lop-duyet-thanh-vien';
  const APPROVAL_TITLE = 'Lớp Duyệt Thành Viên';
  let applicant = null;
  let APPROVAL_ID = null;
  await step('P9', 'lớp cần duyệt: "Xin tham gia" -> trạng thái "Chờ duyệt" (vẫn chưa là thành viên, tab thành viên còn khóa, F5 giữ nguyên)', null, async () => {
    applicant = await newUser('applicant');
    const a = applicant.page;
    await a.goto(`${BASE}/classes/${APPROVAL_SLUG}/feed`);
    const join = a.getByRole('region', { name: 'Tham gia lớp học' });
    await join.waitFor({ timeout: 15000 });
    await join.getByText('Cần duyệt', { exact: true }).waitFor({ timeout: 5000 });
    const [resp] = await Promise.all([
      a.waitForResponse((r) => r.request().method() === 'POST' && /\/classes\/[^/]+\/join$/.test(r.url()), { timeout: 10000 }),
      join.getByRole('button', { name: 'Xin tham gia' }).click(),
    ]);
    if (resp.status() !== 200) throw new Error(`POST join -> ${resp.status()}`);
    const pending = a.getByTestId('join-pending');
    await pending.waitFor({ timeout: 10000 });
    await pending.getByText(/chờ người dẫn dắt duyệt/).waitFor();
    await a.getByTestId('badge-pending').filter({ hasText: 'Chờ duyệt' }).waitFor({ timeout: 5000 });
    if (await a.getByRole('link', { name: 'Thi', exact: true }).count()) throw new Error('tab Thi mở được khi đang chờ duyệt');
    await a.reload();
    await a.getByTestId('join-pending').waitFor({ timeout: 10000 });
    applicant.token = await apiLogin(applicant.user.email, PASSWORD);
    const cls = await must('GET', `/classes/slug/${APPROVAL_SLUG}`, { token: applicant.token });
    if (cls.memberState !== 'PENDING' || cls.isMember) throw new Error(`memberState=${cls.memberState} isMember=${cls.isMember}`);
    APPROVAL_ID = cls.id;
    const members = await api('GET', `/classes/${APPROVAL_ID}/members`, { token: applicant.token });
    if (members.status !== 403) throw new Error(`người chờ duyệt đọc được danh sách thành viên: ${members.status}`);
    await shot(a, 'P9-pending');
    return 'PENDING';
  });
  await step('P9', 'chủ lớp duyệt trong Studio > Thành viên (mục "Chờ duyệt") -> người xin vào thành thành viên, tab mở khóa', o, async () => {
    await o.setViewportSize({ width: 1366, height: 900 });
    await o.goto(`${BASE}/studio/classes/${APPROVAL_ID}/members`);
    const requests = o.getByRole('list', { name: 'Yêu cầu tham gia' });
    const row = requests.locator('li', { hasText: applicant.user.name });
    await row.waitFor({ timeout: 15000 });
    await shot(o, 'P9-studio-requests');
    const [resp] = await Promise.all([
      o.waitForResponse((r) => r.request().method() !== 'GET' && /approve/.test(r.url()), { timeout: 10000 }),
      row.getByRole('button', { name: `Duyệt ${applicant.user.name}` }).click(),
    ]);
    if (resp.status() !== 200) throw new Error(`approve -> ${resp.status()}`);
    await row.waitFor({ state: 'detached', timeout: 10000 });
    const a = applicant.page;
    await a.reload();
    await a.getByRole('heading', { name: APPROVAL_TITLE, level: 1 }).waitFor({ timeout: 10000 });
    await a.getByRole('link', { name: 'Thi', exact: true }).waitFor({ timeout: 10000 });
    if (await a.getByTestId('join-pending').count()) throw new Error('vẫn hiện "Chờ duyệt" sau khi được duyệt');
    if (await a.getByTestId('badge-pending').count()) throw new Error('nhãn "Chờ duyệt" vẫn còn trên ảnh bìa');
    const cls = await must('GET', `/classes/slug/${APPROVAL_SLUG}`, { token: applicant.token });
    if (cls.memberState !== 'ACTIVE' || !cls.isMember) throw new Error(`memberState=${cls.memberState} isMember=${cls.isMember}`);
    await a.goto(`${BASE}/classes/${APPROVAL_SLUG}/members`);
    await a.getByRole('heading', { name: /Thành viên/ }).first().waitFor({ timeout: 10000 });
    return `ACTIVE (${resp.url().replace(/^.*\/api\/v1/, '')})`;
  });

  // =========================== P8: sự kiện trình duyệt ===========================
  await step('P8', 'không có lỗi console / CSP / 5xx / hộp thoại lạ ngoài các phản hồi 402/404 có chủ đích', null, async () => {
    const rest = unexpectedEvents(events);
    const allowedHttp = (e) =>
      (e.kind === 'http.404' && (/\/classes\/slug\/lop-rieng-tu-ma-moi/.test(e.text) || /\/classes\/invites\//.test(e.text))) ||
      (e.kind === 'http.402' && /POST .*\/classes\/invites\/[^/]+\/join/.test(e.text));
    const httpAllowed = rest.filter(allowedHttp);
    let budget = httpAllowed.length;
    const problems = rest.filter((e) => {
      if (allowedHttp(e)) return false;
      if (e.kind === 'console.error' && /status of (402|404)/.test(e.text) && budget > 0) { budget -= 1; return false; }
      if (e.kind === 'dialog.confirm' && /Hoàn tiền đơn/.test(e.text)) return false;
      return true;
    });
    if (problems.length) {
      throw new Error(`${problems.length} sự kiện bất thường: ${problems.slice(0, 4).map((e) => `${e.label}:${e.kind}:${e.text.slice(0, 120)}`).join(' | ')}`);
    }
    if (events.some((e) => /^http\.5/.test(e.kind))) throw new Error('có phản hồi 5xx');
    return `${httpAllowed.length} phản hồi 402/404 có chủ đích, còn lại sạch`;
  });

  for (const ctx of contexts) await ctx.close().catch(() => {});
  await browser.close();
  fs.writeFileSync(path.join(SHOTS_ROOT, `d19-${RUN}`, 'events.json'), JSON.stringify(events, null, 2));
  process.exitCode = summarize('Vòng 22: lớp riêng tư / trả phí / mã mời / hết hạn (e2e5.js)');
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
