const { chromium } = require('playwright');
(async () => {
  const base='http://frontend:80';
  const b=await chromium.launch(); const p=await b.newPage();
  const errs=[]; p.on('pageerror',e=>errs.push(e.message)); p.on('console',m=>{if(m.type()==='error')errs.push(m.text())});
  await p.goto(base+'/login',{waitUntil:'networkidle'});
  await p.fill('input[type=email]',process.env.QA_EMAIL);
  await p.fill('input[type=password]',process.env.QA_PASS);
  await p.click('button[type=submit]'); await p.waitForTimeout(2500);
  await p.goto(base+'/classes/'+process.env.QA_SLUG+'/feed',{waitUntil:'networkidle'});
  await p.waitForTimeout(1500);
  const navs = await p.locator('nav a, [role=tab], a[href*="/classes/"]').allInnerTexts();
  console.log('NAV_LABELS:', JSON.stringify([...new Set(navs.map(s=>s.trim()).filter(Boolean))]));
  for (const seg of ['feed','learn','exams','leaderboard','documents','members','store','about']) {
    await p.goto(`${base}/classes/${process.env.QA_SLUG}/${seg}`,{waitUntil:'networkidle'});
    await p.waitForTimeout(900);
    const txt=(await p.locator('#root').innerText()).trim();
    console.log(`TAB ${seg.padEnd(12)} url=${p.url().split('/').pop().padEnd(12)} chars=${String(txt.length).padEnd(5)} blank=${txt.length<50}`);
  }
  await p.screenshot({path:'class.png',fullPage:true});
  console.log('ERRORS:',errs.length); errs.slice(0,6).forEach(e=>console.log(' ',e.slice(0,160)));
  await b.close();
})();
