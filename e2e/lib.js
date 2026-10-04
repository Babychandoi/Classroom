'use strict';
// Shared helpers for the real-browser E2E suite (xem README.md).
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const BASE = (process.env.BASE_URL || 'http://localhost:3000').replace(/\/+$/, '');
const PASSWORD = 'E2ePassw0rd!';
const FIXTURES = path.join(__dirname, 'fixtures');
const SHOTS_ROOT = path.join(__dirname, 'shots');
// e2e.js writes the identities/ids it created here; the follow-up scripts read them back.
const STATE_FILE = path.join(SHOTS_ROOT, 'last-run.json');

const LOCAL_HOST = /^(localhost|127(\.\d{1,3}){3}|\[?::1\]?|[^.]+\.localhost|[^.]+\.local)$/i;

/** The suite registers users, creates classes and uploads files: refuse anything but a local stack. */
function assertSafeTarget() {
  const host = new URL(BASE).hostname;
  if (!LOCAL_HOST.test(host) && process.env.E2E_ALLOW_REMOTE !== '1') {
    console.error(
      `Từ chối chạy: BASE_URL=${BASE} không phải máy cục bộ.\n` +
      'Bộ E2E tạo người dùng/lớp học/tệp thật trên hệ thống đích và KHÔNG BAO GIỜ được trỏ vào production.\n' +
      'Nếu đây là một môi trường thử nghiệm riêng, đặt E2E_ALLOW_REMOTE=1 để bỏ qua kiểm tra này.',
    );
    process.exit(2);
  }
}

function log(...args) {
  console.log(new Date().toISOString().slice(11, 19), ...args);
}

/** Run id used in every created e-mail/class name, e.g. e2e.owner.<runId>@example.com. */
function newRunId() {
  return Date.now().toString(36);
}

async function launchBrowser() {
  assertSafeTarget();
  // E2E_CHANNEL: chrome (default, the system Chrome) | msedge | chromium (Playwright's own download).
  const channel = process.env.E2E_CHANNEL === undefined ? 'chrome' : process.env.E2E_CHANNEL;
  const options = { headless: process.env.HEADED !== '1' };
  if (channel && channel !== 'chromium') options.channel = channel;
  return chromium.launch(options);
}

async function newCtx(browser, opts = {}) {
  const ctx = await browser.newContext({
    viewport: { width: 1366, height: 900 },
    acceptDownloads: true,
    locale: 'vi-VN',
    timezoneId: 'Asia/Ho_Chi_Minh',
    // Test-only switch for disposable contexts using the server's internal CA.
    ignoreHTTPSErrors: process.env.E2E_IGNORE_HTTPS_ERRORS === '1',
    ...opts,
  });
  // Surface CSP violations as console errors so they are caught like any other browser error.
  await ctx.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (e) => {
      console.error(`[securitypolicyviolation] ${e.violatedDirective} blocked ${e.blockedURI}`);
    });
  });
  return ctx;
}

/** Collect console/page/network problems of a page into `events`. */
function instrument(page, label, events) {
  page.on('console', (msg) => {
    const type = msg.type();
    const text = msg.text();
    if (type === 'error' || type === 'warning' || /Content Security Policy|securitypolicyviolation/i.test(text)) {
      events.push({ label, kind: `console.${type}`, text: text.slice(0, 500), url: page.url() });
    }
  });
  page.on('pageerror', (err) => events.push({ label, kind: 'pageerror', text: String((err && err.stack) || err).slice(0, 800), url: page.url() }));
  page.on('requestfailed', (req) => {
    const failure = req.failure();
    events.push({ label, kind: 'requestfailed', text: `${req.method()} ${req.url().slice(0, 200)} :: ${failure && failure.errorText}`, url: page.url() });
  });
  page.on('response', (res) => {
    if (res.status() >= 400) {
      events.push({ label, kind: `http.${res.status()}`, text: `${res.request().method()} ${res.url().replace(/X-Amz-[^&]+&?/g, '').slice(0, 200)}`, url: page.url() });
    }
  });
  page.on('dialog', async (dialog) => {
    events.push({ label, kind: `dialog.${dialog.type()}`, text: dialog.message().slice(0, 300), url: page.url() });
    try { await dialog.accept(); } catch { /* ignore */ }
  });
}

// Browser noise that is expected and not a bug:
//  - 401 from POST /auth/refresh when an anonymous visitor (or a fresh tab after logout) has no session;
//  - 404 from GET /payments/sandbox-status where the sandbox payment rail is not enabled (the Store tab asks
//    once per page load; Chrome logs every failed request as a console error, one per 404 response).
const SANDBOX_STATUS = /\/payments\/sandbox-status/;

/** Events from `events.slice(from)` that are NOT the expected noise above (everything else is a real problem). */
function unexpectedEvents(events, from = 0) {
  const slice = events.slice(from);
  let sandbox404Budget = slice.filter((e) => e.kind === 'http.404' && SANDBOX_STATUS.test(e.text)).length;
  return slice.filter((e) => {
    if (e.kind === 'http.401') return false;
    if (e.kind === 'http.404') return !SANDBOX_STATUS.test(e.text);
    if (e.kind === 'console.error' && /status of 401/.test(e.text)) return false;
    if (e.kind === 'console.error' && /status of 404/.test(e.text) && sandbox404Budget > 0) {
      sandbox404Budget -= 1;
      return false;
    }
    return true;
  });
}

/**
 * Step runner: `step(journey, name, page, fn)` records PASS/FAIL, takes a screenshot on failure and
 * never throws, so one broken step does not hide the rest of the journey.
 */
function createRunner(shotsDir) {
  fs.mkdirSync(shotsDir, { recursive: true });
  const results = [];
  const events = [];

  async function shot(page, name) {
    const file = path.join(shotsDir, `${name}.png`);
    try { await page.screenshot({ path: file, fullPage: true }); } catch (e) { log('shot failed', name, e.message); }
    return file;
  }

  async function step(journey, name, page, fn) {
    const started = Date.now();
    try {
      const note = await fn();
      results.push({ journey, name, ok: true, note: note || '', ms: Date.now() - started });
      log('PASS', journey, name, note || '');
      return true;
    } catch (e) {
      const file = page ? await shot(page, `FAIL-${journey}-${name.replace(/[^a-z0-9]+/gi, '_').slice(0, 50)}`) : '';
      const note = String((e && e.message) || e).split('\n').slice(0, 4).join(' | ');
      results.push({ journey, name, ok: false, note, shot: file, ms: Date.now() - started });
      log('FAIL', journey, name, note.split(' | ')[0]);
      return false;
    }
  }

  /** Print the per-journey table and return the process exit code (0 = everything passed). */
  function summarize(title) {
    const byJourney = new Map();
    for (const r of results) {
      const j = byJourney.get(r.journey) || { pass: 0, fail: 0 };
      if (r.ok) j.pass += 1; else j.fail += 1;
      byJourney.set(r.journey, j);
    }
    console.log(`\n=== ${title} ===`);
    for (const [journey, j] of byJourney) {
      console.log(`${j.fail === 0 ? 'PASS' : 'FAIL'}  ${journey}  (${j.pass}/${j.pass + j.fail} bước đạt)`);
    }
    const failed = results.filter((r) => !r.ok);
    for (const f of failed) console.log(`  x ${f.journey} ${f.name} :: ${f.note}`);
    console.log(`Tổng: ${results.length - failed.length}/${results.length} đạt; sự kiện trình duyệt=${events.length}`);
    return failed.length === 0 ? 0 : 1;
  }

  return { results, events, shot, step, summarize, instrument: (page, label) => instrument(page, label, events) };
}

/** Wait for the network to go quiet (best effort) plus a small grace period. */
async function settle(page, ms = 600) {
  try { await page.waitForLoadState('networkidle', { timeout: 8000 }); } catch { /* ignore */ }
  await page.waitForTimeout(ms);
}

async function register(page, user) {
  await page.goto(`${BASE}/login`);
  // The login card's mode switch (the top bar's "Đăng ký miễn phí" is a link, not a button).
  await page.getByRole('button', { name: 'Đăng ký miễn phí', exact: true }).click();
  await page.getByLabel('Họ và tên').fill(user.name);
  await page.getByLabel('Email').fill(user.email);
  await page.getByLabel('Mật khẩu', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Tạo tài khoản' }).click();
  await page.waitForURL(/\/classes$/, { timeout: 15000 });
  await page.getByRole('button', { name: 'Mở menu tài khoản' }).waitFor({ timeout: 10000 });
}

async function login(page, user) {
  await page.goto(`${BASE}/login`);
  await page.getByLabel('Email').fill(user.email);
  await page.getByLabel('Mật khẩu', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  await page.waitForURL(/\/classes$/, { timeout: 15000 });
  await page.getByRole('button', { name: 'Mở menu tài khoản' }).waitFor({ timeout: 10000 });
}

function isLoggedIn(page) {
  return page.getByRole('button', { name: 'Mở menu tài khoản' }).isVisible();
}

/**
 * Tạo lớp qua trang toàn màn hình /classes/new (thay cho hộp thoại cũ) và vào lớp bằng "Vào lớp học".
 * Máy chủ tự sinh slug từ tên lớp (bỏ dấu, thêm -2/-3 khi trùng) nên slug được đọc từ URL sau khi vào lớp, không tự chọn.
 *   category: một chip lĩnh vực (bắt buộc), visibility: 'PUBLIC' | 'PRIVATE', paid: { price: '50.000' } (phí mỗi tháng),
 *   approval: bật "Duyệt từng người trước khi vào", cover/avatar: đường dẫn tệp ảnh.
 * Hộp thoại "… đã mở" báo việc chưa xong (phí/ảnh) bằng role=alert -> ném lỗi. Trả về { slug, classId }.
 */
async function createClassViaUI(page, {
  title, desc = '', category = 'Ôn thi', visibility = 'PUBLIC', paid = null, approval = false, cover = null, avatar = null,
}) {
  await page.goto(`${BASE}/classes/new`);
  await page.getByRole('heading', { name: 'Tạo lớp học', level: 1 }).waitFor({ timeout: 15000 });
  await page.getByLabel('Tên lớp học · bắt buộc').fill(title);
  await page.getByRole('group', { name: 'Lĩnh vực · bắt buộc' }).getByRole('button', { name: category, exact: true }).click();
  if (desc) await page.getByLabel(/^Mô tả ngắn/).fill(desc);
  const choose = async (group, name) => {
    const btn = page.getByRole('group', { name: group }).getByRole('button', { name: new RegExp(`^${name}`) });
    await btn.click();
    if ((await btn.getAttribute('aria-pressed')) !== 'true') throw new Error(`"${name}" không được chọn`);
  };
  await choose('Ai thấy lớp', visibility === 'PRIVATE' ? 'Riêng tư' : 'Công khai');
  await choose('Học phí', paid ? 'Có phí' : 'Miễn phí');
  if (paid) await page.getByLabel('Phí mỗi tháng · bắt buộc').fill(String(paid.price));
  if (approval) {
    const sw = page.getByRole('switch', { name: 'Duyệt từng người trước khi vào' });
    await sw.click();
    if ((await sw.getAttribute('aria-checked')) !== 'true') throw new Error('công tắc duyệt thành viên không bật');
  }
  // Ô ảnh: cột xem trước (>1000px) hoặc ngay trong form (màn hẹp).
  const wide = (page.viewportSize() || { width: 1366 }).width > 1000;
  if (cover) await page.getByTestId(wide ? 'slot-cover-desktop-input' : 'slot-cover-narrow-input').setInputFiles(cover);
  if (avatar) await page.getByTestId(wide ? 'slot-avatar-desktop-input' : 'slot-avatar-narrow-input').setInputFiles(avatar);
  const [resp] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === 'POST' && /\/api\/v1\/classes$/.test(r.url()), { timeout: 15000 }),
    page.getByRole('button', { name: 'Tạo lớp học', exact: true }).click(),
  ]);
  if (resp.status() !== 200) throw new Error(`POST /classes -> ${resp.status()}`);
  const done = page.getByRole('dialog', { name: `${title} đã mở` });
  await done.waitFor({ timeout: 20000 });
  if (await done.getByRole('alert').count()) throw new Error(`tạo lớp chưa xong: ${(await done.getByRole('alert').innerText()).replace(/\s+/g, ' ')}`);
  await done.getByRole('link', { name: 'Vào lớp học' }).click();
  await page.waitForURL(/\/classes\/[^/]+\/feed$/, { timeout: 15000 });
  const slug = new URL(page.url()).pathname.split('/')[2];
  await page.getByRole('heading', { name: title, level: 1 }).waitFor({ timeout: 10000 });
  const href = await page.getByRole('link', { name: 'Studio quản trị' }).getAttribute('href');
  return { slug, classId: href.split('/').pop() };
}

/** Horizontal-overflow probe: which elements stick out past the viewport (ignoring inner scrollers). */
function overflowCheck(page) {
  return page.evaluate(() => {
    const de = document.documentElement;
    const over = de.scrollWidth - de.clientWidth;
    const offenders = [];
    if (over > 1) {
      for (const el of Array.from(document.querySelectorAll('body *'))) {
        const r = el.getBoundingClientRect();
        if (r.right > de.clientWidth + 1 && r.width > 0) {
          let p = el.parentElement;
          let inScroller = false;
          while (p) {
            const s = getComputedStyle(p);
            if (/(auto|scroll|hidden)/.test(s.overflowX) && p !== document.body && p !== de) { inScroller = true; break; }
            p = p.parentElement;
          }
          if (!inScroller) offenders.push(`${el.tagName.toLowerCase()}.${String(el.className).slice(0, 60)} right=${Math.round(r.right)} text=${(el.textContent || '').trim().slice(0, 40)}`);
          if (offenders.length > 6) break;
        }
      }
    }
    return { scrollWidth: de.scrollWidth, clientWidth: de.clientWidth, over, offenders };
  });
}

function saveState(state) {
  fs.mkdirSync(SHOTS_ROOT, { recursive: true });
  fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2));
}

/** Identities/ids of the latest e2e.js run (or the E2E_RUN / E2E_CLASS_SLUG / E2E_CLASS_ID overrides). */
function loadState() {
  let state = {};
  try { state = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')); } catch { /* no previous run */ }
  const run = process.env.E2E_RUN || state.run;
  if (!run && !process.env.E2E_CLASS_SLUG) {
    console.error('Chưa có dữ liệu từ lần chạy e2e.js (thiếu shots/last-run.json). Hãy chạy `npm run e2e` trước, hoặc đặt E2E_RUN.');
    process.exit(2);
  }
  return {
    run,
    password: PASSWORD,
    owner: state.owner || { name: `E2E Owner ${run}`, email: `e2e.owner.${run}@example.com` },
    student: state.student || { name: `E2E Student ${run}`, email: `e2e.student.${run}@example.com` },
    classSlug: process.env.E2E_CLASS_SLUG || state.classSlug || `e2e-class-${run}`,
    classId: process.env.E2E_CLASS_ID || state.classId,
    examTitle: state.examTitle || `E2E Exam ${run}`,
    courseTitle: state.courseTitle || `E2E Course ${run}`,
  };
}

module.exports = {
  BASE, PASSWORD, FIXTURES, SHOTS_ROOT, STATE_FILE,
  assertSafeTarget, unexpectedEvents, log, newRunId, launchBrowser, newCtx, instrument, createRunner,
  settle, register, login, isLoggedIn, createClassViaUI, overflowCheck, saveState, loadState,
};
