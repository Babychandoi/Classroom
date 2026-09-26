const { chromium } = require('playwright');
(async () => {
  const email = process.env.QA_EMAIL, pass = process.env.QA_PASS, base = 'http://frontend:80';
  const b = await chromium.launch();
  const p = await b.newPage();
  const errs = [];
  p.on('console', m => { if (m.type() === 'error') errs.push(m.text()); });
  p.on('pageerror', e => errs.push('PAGEERROR: ' + e.message));

  await p.goto(base + '/login', { waitUntil: 'networkidle' });
  console.log('LOGIN_TITLE:', await p.title());
  console.log('ROOT_NONEMPTY:', (await p.locator('#root').innerHTML()).length > 200);

  await p.fill('input[type=email]', email);
  await p.fill('input[type=password]', pass);
  await Promise.all([p.waitForLoadState('networkidle'), p.click('button[type=submit]')]);
  await p.waitForTimeout(1500);
  console.log('AFTER_LOGIN_URL:', p.url());
  const body = await p.locator('body').innerText();
  console.log('HAS_CLASS_TEXT:', /QA Class|Lớp|lớp/.test(body));

  // navigate into the QA class
  const link = p.locator('a[href*="/classes/"]').first();
  if (await link.count()) {
    await link.click();
    await p.waitForLoadState('networkidle');
    await p.waitForTimeout(1500);
    console.log('CLASS_URL:', p.url());
    const t = await p.locator('body').innerText();
    const tabs = ['Bảng tin','Góc học tập','Luyện thi','Bảng xếp hạng','Tài liệu','Thành viên','Cửa hàng','Giới thiệu'];
    console.log('TABS_PRESENT:', tabs.filter(x => t.includes(x)).length + '/8');
    for (const tab of ['Luyện thi','Cửa hàng','Bảng xếp hạng']) {
      const el = p.locator(`text=${tab}`).first();
      if (await el.count()) { await el.click(); await p.waitForTimeout(1200);
        console.log(`TAB[${tab}] rendered chars:`, (await p.locator('body').innerText()).length); }
    }
  } else console.log('NO_CLASS_LINK');

  console.log('CONSOLE_ERRORS:', errs.length);
  errs.slice(0,8).forEach(e => console.log('  ERR:', e.slice(0,200)));
  await b.close();
})();
