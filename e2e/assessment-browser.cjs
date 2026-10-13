'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require('playwright');
const BASE=process.env.BASE_URL||'http://127.0.0.1:13000';
if(!['localhost','127.0.0.1'].includes(new URL(BASE).hostname))throw Error('Local target required');
const state=JSON.parse(fs.readFileSync(path.join(__dirname,'../.artifacts/assessment-qa-state.json'),'utf8'));
const shots=path.join(__dirname,'../.artifacts/assessment-browser');fs.mkdirSync(shots,{recursive:true});
const axeSource=fs.readFileSync(require.resolve('axe-core/axe.min.js'),'utf8');
const audits=[];
async function accessibility(page,name){
 await page.evaluate(axeSource);
 const result=await page.evaluate(async()=>window.axe.run(document,{runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21a','wcag21aa','wcag22aa']}}));
 audits.push({name,url:page.url(),violations:result.violations});
 fs.writeFileSync(path.join(shots,'axe-report.json'),JSON.stringify(audits,null,2));
 console.log('AXE '+name+': '+result.violations.length+' violations');
}
async function login(page,email){await page.goto(BASE+'/login');await page.getByLabel('Email').fill(email);await page.getByLabel('Mật khẩu',{exact:true}).fill(state.password);await page.getByRole('button',{name:'Đăng nhập',exact:true}).click();await page.waitForURL(/\/classes$/);}
async function overflow(page){
 const fits=await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1);
 if(!fits){const details=await page.evaluate(()=>Array.from(document.querySelectorAll('body *')).map(e=>({tag:e.tagName,class:String(e.className),right:e.getBoundingClientRect().right})).filter(e=>e.right>innerWidth+1).slice(0,8));throw Error('Horizontal page overflow: '+JSON.stringify(details));}
}
async function main(){
 const browser=await chromium.launch({channel:'chrome',headless:true,args:['--use-fake-ui-for-media-stream','--use-fake-device-for-media-stream']});
 const errors=[];const context=await browser.newContext({viewport:{width:1440,height:1000},locale:'vi-VN',acceptDownloads:true});const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
 try{
  await login(page,state.ownerEmail);
  const response=await context.request.post(BASE+'/api/v1/auth/login',{data:{email:state.ownerEmail,password:state.password}});const auth=(await response.json()).data;
  const headers={Authorization:'Bearer '+auth.token};
  const newDoc=await context.request.post(BASE+`/api/v1/classes/${state.classId}/assessments`,{headers,data:{title:'Browser interaction coverage',kind:'QUIZ'}});assert(newDoc.ok());const author=(await newDoc.json()).data;
  await page.goto(BASE+`/studio/classes/${state.classId}/assessments/${author.id}/edit`);await page.getByRole('heading',{name:'Thông tin đề'}).waitFor();
  for(let n=1;n<=28;n++){
   const code='Q'+String(n).padStart(2,'0');console.log('CHECK authoring '+code);await page.getByRole('button',{name:'+ Câu hỏi / nhóm',exact:true}).first().click();const dialog=page.getByRole('dialog',{name:'Chọn dạng câu hỏi'});await dialog.getByRole('button').filter({has:page.getByText(code,{exact:true})}).click();
   if(n===13 || n===21)await page.getByLabel('Tên nhóm',{exact:true}).fill('Nhóm kiểm tra '+code);
   else await page.getByLabel('Đề bài',{exact:true}).fill('Nội dung thử '+code+([7,8,9].includes(n)?' ___':''));
  }
  await page.getByText('Đã lưu nháp',{exact:false}).first().waitFor({timeout:20000});assert(errors.length===0,errors.join('\n'));console.log('PASS Every Q01–Q28 authoring form renders and autosaves');
  await page.getByRole('button',{name:'Thông tin đề',exact:false}).click();await overflow(page);await page.screenshot({path:path.join(shots,'builder-desktop.png'),fullPage:true});await accessibility(page,'builder-desktop');
  await page.getByRole('button',{name:'Xem trước',exact:true}).click();await page.getByRole('dialog',{name:'Xem trước như học viên'}).waitFor();await page.screenshot({path:path.join(shots,'preview-desktop.png'),fullPage:true});await accessibility(page,'preview-desktop');
  await page.getByRole('button',{name:'Đóng',exact:true}).click();
  await page.setViewportSize({width:390,height:844});await overflow(page);await page.screenshot({path:path.join(shots,'builder-mobile.png'),fullPage:true});await accessibility(page,'builder-mobile');
  await page.getByRole('radio',{name:'Cấu trúc',exact:true}).click();await overflow(page);await page.getByRole('button',{name:'Ngân hàng câu hỏi',exact:true}).click();await page.getByRole('dialog',{name:'Ngân hàng câu hỏi'}).waitFor();await page.getByLabel('Tìm nội dung / yêu cầu').fill('1+2');await page.getByRole('button',{name:'Tìm câu hỏi',exact:true}).click();await page.getByRole('button',{name:'Đóng',exact:true}).click();console.log('PASS Mobile tree and question-bank controls');
  await page.setViewportSize({width:1440,height:1000});await page.goto(BASE+`/studio/classes/${state.classId}/assessments`);await page.getByRole('heading',{name:'Bài tập & đề'}).waitFor();await page.getByRole('button',{name:'Import Word / Excel'}).click();await page.getByRole('dialog',{name:'Import câu hỏi'}).waitFor();await page.getByLabel('Tệp câu hỏi cần import').setInputFiles({name:'questions.csv',mimeType:'text/csv',buffer:Buffer.from('prompt,options,answer\n2+3?,4 | 5,B\n')});await page.getByRole('heading',{name:'Đề import'}).waitFor();await page.screenshot({path:path.join(shots,'import-desktop.png'),fullPage:true});await accessibility(page,'import-desktop');await page.getByRole('button',{name:'Đóng',exact:true}).click();
  await page.getByRole('button',{name:'Tạo theo ma trận'}).click();await page.getByLabel('Số câu',{exact:true}).fill('1');await page.getByRole('button',{name:'Xem trước ma trận'}).click();await page.getByRole('heading',{name:'Đề từ ma trận'}).waitFor();await page.getByRole('button',{name:'Tạo bản nháp từ kết quả xem trước'}).click();await page.waitForURL(/\/edit$/);console.log('PASS Import preview and matrix create');
  await page.goto(BASE+`/studio/classes/${state.classId}/assessments/results/${state.assignmentId}`);await page.getByRole('heading',{name:'Kết quả & chấm bài'}).waitFor();await page.getByRole('radio',{name:'Phân tích & kỹ năng'}).click();await page.getByRole('heading',{name:'Kỹ năng từng học viên · lượt mới nhất'}).waitFor();await page.screenshot({path:path.join(shots,'report-desktop.png'),fullPage:true});await accessibility(page,'report-desktop');
  const studentContext=await browser.newContext({viewport:{width:390,height:844},locale:'vi-VN'});const student=await studentContext.newPage();student.on('pageerror',e=>errors.push(e.message));await login(student,state.studentEmail);await student.goto(BASE+`/classes/${state.slug}/homework`);await student.getByRole('heading',{name:'Bài tập',exact:true}).waitFor();await overflow(student);await student.screenshot({path:path.join(shots,'homework-mobile.png'),fullPage:true});await accessibility(student,'homework-mobile');await student.goto(BASE+`/classes/${state.slug}/homework/${state.assignmentId}/result`);await student.getByRole('main').getByRole('heading',{name:/Assessment QA/}).waitFor();await overflow(student);await student.screenshot({path:path.join(shots,'result-mobile.png'),fullPage:true});await accessibility(student,'result-mobile');console.log('PASS Student homework and result on mobile');
  const media=JSON.parse(fs.readFileSync(path.join(__dirname,'../.artifacts/assessment-media-qa-state.json'),'utf8'));
  await studentContext.grantPermissions(['microphone'],{origin:BASE});
  await student.goto(BASE+`/classes/${state.slug}/homework/${media.assignmentId}/attempt`);await student.getByRole('heading',{name:/Audio QA/}).waitFor();await overflow(student);
  await student.getByRole('button',{name:'Phát tệp nghe',exact:true}).first().click();await student.waitForFunction(()=>Array.from(document.querySelectorAll('audio')).some(a=>a.currentTime>0));
  const speaking=student.locator('#question-speaking');await speaking.getByRole('button',{name:'Bắt đầu ghi âm',exact:true}).click();await speaking.getByRole('button',{name:'Dừng ghi âm',exact:true}).waitFor();await speaking.getByText('Đã ghi âm · đã lưu',{exact:true}).waitFor({timeout:30000});await speaking.getByRole('button',{name:'Nghe lại bản ghi',exact:true}).click();await speaking.locator('audio').waitFor();
  await student.reload();await speaking.getByRole('button',{name:'Nghe lại bản ghi',exact:true}).waitFor();await overflow(student);await student.screenshot({path:path.join(shots,'speaking-mobile.png'),fullPage:true});await accessibility(student,'speaking-mobile');await speaking.screenshot({path:path.join(shots,'speaking-control-mobile.png')});console.log('PASS Real Chrome audio playback, microphone recording, upload and reload');
  const denied=await browser.newContext({viewport:{width:390,height:844},locale:'vi-VN'});const deniedPage=await denied.newPage();await login(deniedPage,state.studentEmail);await deniedPage.addInitScript(()=>{navigator.mediaDevices.getUserMedia=async()=>{throw new DOMException('Microphone denied for QA','NotAllowedError');};});await deniedPage.goto(BASE+`/classes/${state.slug}/homework/${media.assignmentId}/attempt`);await deniedPage.locator('#question-too-long').getByRole('button',{name:'Bắt đầu ghi âm',exact:true}).click();await deniedPage.getByText('Trình duyệt chưa cho dùng microphone',{exact:true}).waitFor();await deniedPage.getByRole('button',{name:'Tải tệp ghi âm thay thế',exact:true}).waitFor();console.log('PASS Simulated microphone denial offers instructions and file fallback');await denied.close();
  const violations=audits.flatMap(a=>a.violations.map(v=>a.name+': '+v.id+' ('+v.nodes.map(n=>n.target.join(' ')).join(', ')+')'));assert(violations.length===0,violations.join('\n'));
  assert(errors.length===0,errors.join('\n'));console.log('PASS Browser verification, screenshots and accessibility reports saved');
 }catch(error){await page.screenshot({path:path.join(shots,'failure.png'),fullPage:true});console.log((await page.locator('main').innerText()).slice(0,2000));throw error;}finally{await browser.close();}
}
main().catch(e=>{console.error(e.message);process.exitCode=1;});
