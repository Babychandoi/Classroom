'use strict';
// R17-05: điều hướng ở màn hình điện thoại (390x844), kèm ảnh chụp để xem bằng mắt:
//   - thanh Navbar: tên thương hiệu 1 dòng, không tràn khỏi thanh cao 64px, không có link "Khám phá lớp học" trùng lặp;
//   - thanh tab của lớp: cuộn ngang được, có vùng mờ ở mép còn tab bị ẩn, tab đang mở (giữa dải / tab cuối) được cuộn vào tầm nhìn;
//   - Studio: menu điều hướng thu gọn (nút "Menu Studio"), nội dung bắt đầu gần đầu trang chứ không bị đẩy xuống ~800px.
const path = require('path');
const { BASE, SHOTS_ROOT, log, newRunId, launchBrowser, newCtx, createRunner, login, settle, overflowCheck, loadState } = require('./lib');

const S = loadState();
const SHOTS = path.join(SHOTS_ROOT, `mobnav-${newRunId()}`);
const { step, shot, summarize, instrument } = createRunner(SHOTS);
const PHONE = { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 };

(async () => {
  const browser = await launchBrowser();
  log('browser', browser.version(), 'base', BASE);

  // ---- ẩn danh: Navbar ----
  const anonCtx = await newCtx(browser, PHONE);
  const anon = await anonCtx.newPage();
  instrument(anon, 'mobnav-anon');
  await step('M1', 'navbar (signed out): brand on one line inside the 64px bar (+1px border)', anon, async () => {
    await anon.goto(`${BASE}/classes`);
    await settle(anon, 500);
    const m = await anon.evaluate(() => {
      const nav = document.querySelector('header');
      // số dòng chữ = chiều cao nội dung (trừ padding) / line-height
      const textLines = (el) => {
        const cs = getComputedStyle(el);
        const inner = el.getBoundingClientRect().height - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom);
        return Math.round(inner / parseFloat(cs.lineHeight));
      };
      const brand = Array.from(nav.querySelectorAll('span')).find((s) => s.textContent.includes('Lớp Học Trực Tuyến'));
      const n = nav.getBoundingClientRect();
      const b = brand.getBoundingClientRect();
      const explore = Array.from(nav.querySelectorAll('a')).find((a) => a.textContent.trim() === 'Khám phá lớp học');
      const login = Array.from(nav.querySelectorAll('a')).find((a) => a.textContent.trim() === 'Đăng nhập');
      const l = login.getBoundingClientRect();
      return {
        navHeight: Math.round(n.height), brandLines: textLines(brand), brandTop: Math.round(b.top), brandBottom: Math.round(b.bottom),
        navTop: Math.round(n.top), navBottom: Math.round(n.bottom), exploreVisible: !!explore && explore.getBoundingClientRect().width > 0,
        loginLines: textLines(login), loginRight: Math.round(l.right),
      };
    });
    await anon.screenshot({ path: path.join(SHOTS, 'M1-navbar-signed-out.png') });
    const problems = [];
    if (m.navHeight > 65) problems.push(`navbar cao ${m.navHeight}px (mong đợi 64 + viền 1px)`);
    if (m.brandLines !== 1) problems.push(`tên thương hiệu ${m.brandLines} dòng`);
    if (m.brandTop < m.navTop || m.brandBottom > m.navBottom) problems.push(`tên thương hiệu tràn khỏi navbar (${m.brandTop}-${m.brandBottom} vs ${m.navTop}-${m.navBottom})`);
    if (m.exploreVisible) problems.push('"Khám phá lớp học" vẫn hiện trên điện thoại');
    if (m.loginLines !== 1) problems.push(`nút Đăng nhập ${m.loginLines} dòng`);
    if (m.loginRight > 390) problems.push(`nút Đăng nhập tràn phải (${m.loginRight}px)`);
    if (problems.length) throw new Error(problems.join('; '));
    return JSON.stringify(m);
  });

  // ---- chủ lớp: navbar khi đã đăng nhập, thanh tab, Studio ----
  const ctx = await newCtx(browser, PHONE);
  const page = await ctx.newPage();
  instrument(page, 'mobnav-owner');
  await step('M0', 'login owner on the phone viewport', page, async () => { await login(page, S.owner); });

  await step('M1', 'navbar (signed in): brand on one line, account buttons inside the viewport', page, async () => {
    await page.goto(`${BASE}/classes`);
    await settle(page, 500);
    const m = await page.evaluate(() => {
      const nav = document.querySelector('header');
      const brand = Array.from(nav.querySelectorAll('span')).find((s) => s.textContent.includes('Lớp Học Trực Tuyến'));
      // Đăng xuất nằm trong menu tài khoản; nút mở menu (ảnh đại diện) là phần tử ngoài cùng bên phải khi đã đăng nhập.
      const out = nav.querySelector('button[aria-label="Mở menu tài khoản"]');
      return {
        navHeight: Math.round(nav.getBoundingClientRect().height),
        brandLines: Math.round(brand.getBoundingClientRect().height / parseFloat(getComputedStyle(brand).lineHeight)),
        accountRight: Math.round(out.getBoundingClientRect().right),
      };
    });
    await page.screenshot({ path: path.join(SHOTS, 'M1-navbar-signed-in.png') });
    if (m.navHeight > 65 || m.brandLines !== 1 || m.accountRight > 390) throw new Error(JSON.stringify(m));
    return JSON.stringify(m);
  });

  await step('M2', 'class tab strip: scrollable, edge fade shown, active tab scrolled into view', page, async () => {
    await page.goto(`${BASE}/classes/${S.classSlug}/store`);
    await settle(page, 600);
    const info = await page.evaluate(() => {
      const active = document.querySelector('[aria-current="page"]');
      const strip = active.parentElement;
      const a = active.getBoundingClientRect();
      const s = strip.getBoundingClientRect();
      return {
        scrollable: strip.scrollWidth > strip.clientWidth + 4,
        scrollLeft: Math.round(strip.scrollLeft),
        activeInView: a.left >= s.left - 1 && a.right <= s.right + 1,
        activeLabel: active.textContent.trim(),
        fadeLeft: !!document.querySelector('[data-testid="tabs-fade-left"]'),
        fadeRight: !!document.querySelector('[data-testid="tabs-fade-right"]'),
      };
    });
    await page.screenshot({ path: path.join(SHOTS, 'M2-class-tabs-store-active.png'), clip: { x: 0, y: 0, width: 390, height: 460 } });
    const problems = [];
    if (!info.scrollable) problems.push('thanh tab không cuộn ngang được ở 390px (kiểm tra lại số tab)');
    if (!info.activeInView) problems.push(`tab đang mở "${info.activeLabel}" nằm ngoài tầm nhìn`);
    if (info.activeLabel !== 'Shop') problems.push(`tab đang mở là "${info.activeLabel}" (mong đợi Shop)`);
    // Shop giờ nằm giữa dải 10 tab (sau nó còn Bảng xếp hạng, Giới thiệu): đã cuộn sang nên có mờ trái,
    // và vẫn còn tab ẩn bên phải nên có mờ phải. Trường hợp "tab cuối" được kiểm ở bước kế tiếp (Giới thiệu).
    if (!info.fadeLeft) problems.push('không có vùng mờ bên trái dù tab đã được cuộn sang');
    if (!info.fadeRight) problems.push('không có vùng mờ bên phải dù còn tab ẩn sau Shop');
    if (problems.length) throw new Error(`${problems.join('; ')} ${JSON.stringify(info)}`);
    return JSON.stringify(info);
  });
  await step('M2', 'class tab strip at the last tab: left-edge fade only, last tab in view', page, async () => {
    await page.goto(`${BASE}/classes/${S.classSlug}/about`);
    await settle(page, 600);
    const info = await page.evaluate(() => {
      const active = document.querySelector('[aria-current="page"]');
      const strip = active.parentElement;
      const a = active.getBoundingClientRect();
      const s = strip.getBoundingClientRect();
      return {
        isLast: strip.lastElementChild === active,
        activeInView: a.left >= s.left - 1 && a.right <= s.right + 1,
        activeLabel: active.textContent.trim(),
        fadeLeft: !!document.querySelector('[data-testid="tabs-fade-left"]'),
        fadeRight: !!document.querySelector('[data-testid="tabs-fade-right"]'),
      };
    });
    await page.screenshot({ path: path.join(SHOTS, 'M2-class-tabs-about-active.png'), clip: { x: 0, y: 0, width: 390, height: 460 } });
    const problems = [];
    if (!info.isLast) problems.push(`"${info.activeLabel}" không phải tab cuối`);
    if (!info.activeInView) problems.push(`tab cuối "${info.activeLabel}" nằm ngoài tầm nhìn`);
    if (!info.fadeLeft) problems.push('không có vùng mờ bên trái dù đang ở tab cuối');
    if (info.fadeRight) problems.push('vẫn còn vùng mờ bên phải dù đã ở tab cuối');
    if (problems.length) throw new Error(`${problems.join('; ')} ${JSON.stringify(info)}`);
    return JSON.stringify(info);
  });
  await step('M2', 'class tab strip at the first tab: right-edge fade only', page, async () => {
    await page.goto(`${BASE}/classes/${S.classSlug}/feed`);
    await settle(page, 600);
    const info = await page.evaluate(() => ({
      fadeLeft: !!document.querySelector('[data-testid="tabs-fade-left"]'),
      fadeRight: !!document.querySelector('[data-testid="tabs-fade-right"]'),
    }));
    await page.screenshot({ path: path.join(SHOTS, 'M2-class-tabs-feed-active.png'), clip: { x: 0, y: 0, width: 390, height: 460 } });
    if (info.fadeLeft || !info.fadeRight) throw new Error(JSON.stringify(info));
    // cuộn thử bằng thao tác cảm ứng/wheel: vùng mờ trái phải xuất hiện
    await page.evaluate(() => { const s = document.querySelector('[aria-current="page"]').parentElement; s.scrollLeft = 120; });
    await page.waitForFunction(() => !!document.querySelector('[data-testid="tabs-fade-left"]'), null, { timeout: 3000 });
  });

  await step('M3', 'studio: nav collapsed behind the menu button, content near the top', page, async () => {
    await page.goto(`${BASE}/studio/classes/${S.classId}/courses`);
    await settle(page, 600);
    const toggle = page.getByRole('button', { name: /Menu Studio/ });
    await toggle.waitFor({ timeout: 5000 });
    const nav = page.getByRole('navigation', { name: 'Điều hướng Studio' });
    if (await nav.isVisible()) throw new Error('nav should be collapsed by default on a phone');
    const mainTop = await page.locator('main').last().evaluate((el) => Math.round(el.getBoundingClientRect().top + window.scrollY));
    await page.screenshot({ path: path.join(SHOTS, 'M3-studio-collapsed.png') });
    if (mainTop > 350) throw new Error(`content starts at y=${mainTop}px (mong đợi < 350)`);
    const o = await overflowCheck(page);
    if (o.over > 1) throw new Error(`horizontal overflow ${o.over}px`);
    return `main starts at y=${mainTop}px`;
  });
  await step('M3', 'studio: menu opens, lists the pages, and closes after choosing one', page, async () => {
    const toggle = page.getByRole('button', { name: /Menu Studio/ });
    await toggle.click();
    const nav = page.getByRole('navigation', { name: 'Điều hướng Studio' });
    await nav.waitFor({ state: 'visible' });
    if ((await toggle.getAttribute('aria-expanded')) !== 'true') throw new Error('aria-expanded is not true');
    await page.screenshot({ path: path.join(SHOTS, 'M3-studio-menu-open.png') });
    await nav.getByRole('link', { name: 'Thi', exact: true }).click();
    await page.waitForURL(/\/studio\/classes\/[^/]+\/exams$/, { timeout: 8000 });
    await nav.waitFor({ state: 'hidden', timeout: 3000 });
    const label = await page.getByRole('button', { name: /Menu Studio/ }).innerText();
    if (!label.trim().endsWith('· Thi')) throw new Error(`menu button does not name the current page: ${label}`);
    return label;
  });

  await browser.close();
  console.log(`\nẢnh chụp (hãy xem bằng mắt): ${SHOTS}`);
  process.exitCode = summarize('Điều hướng điện thoại (mobnav.js)');
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
