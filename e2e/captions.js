'use strict';
const fs = require('node:fs');
const assert = require('node:assert/strict');
const { BASE, PASSWORD, FIXTURES, loadState, launchBrowser, newCtx, login } = require('./lib');
const state = loadState();
let browser;
async function api(method, route, token, body) {
  const r = await fetch(`${BASE}/api/v1${route}`, { method, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, body: body ? JSON.stringify(body) : undefined });
  const json = await r.json(); assert.equal(r.status, 200, `${method} ${route}`); return json.data;
}
(async () => {
  const auth = await api('POST', '/auth/login', '', { email: state.owner.email, password: PASSWORD });
  const course = await api('POST', `/classes/${state.classId}/courses`, auth.token, { title: 'Kiểm thử phụ đề', accessMode: 'FREE', position: 100 });
  const section = await api('POST', `/courses/${course.id}/sections`, auth.token, { title: 'Phụ đề', position: 0 });
  const file = fs.readFileSync(`${FIXTURES}/tiny.mp4`);
  const intent = await api('POST', `/classes/${state.classId}/media/upload-intents`, auth.token, { filename: 'captions.mp4', mimeType: 'video/mp4', sizeBytes: file.length, purpose: 'LESSON', scopeCourseId: course.id });
  assert.ok((await fetch(intent.uploadUrl, { method: 'PUT', headers: { 'Content-Type': 'video/mp4' }, body: file })).ok);
  await api('POST', `/media/${intent.assetId}/complete`, auth.token);
  const vtt = 'WEBVTT\n\n00:00.000 --> 00:10.000\nLời giảng kiểm thử\n';
  const lesson = await api('POST', `/sections/${section.id}/lessons`, auth.token, { title: 'Video có phụ đề', type: 'VIDEO', mediaAssetId: intent.assetId, contentText: 'Bản chép lời: Lời giảng kiểm thử. Hình ảnh: video minh họa.', captionsVtt: vtt, position: 0 });
  await api('POST', `/courses/${course.id}/publish`, auth.token);
  browser = await launchBrowser(); const ctx = await newCtx(browser); const page = await ctx.newPage();
  const policyErrors = []; page.on('console', m => { if (/securitypolicyviolation/.test(m.text())) policyErrors.push(m.text()); });
  await login(page, state.student); await page.goto(`${BASE}/classes/${state.classSlug}/learn/lessons/${lesson.id}`);
  await page.getByRole('heading', { name: 'Bản chép lời và mô tả video' }).waitFor();
  await page.waitForFunction(() => { const video = document.querySelector('video'); return video && video.textTracks.length === 1 && video.textTracks[0].cues?.length === 1; });
  const text = await page.locator('video').evaluate(video => video.textTracks[0].cues[0].text); assert.equal(text, 'Lời giảng kiểm thử'); assert.equal(policyErrors.length, 0);
  await api('PUT', `/lessons/${lesson.id}`, auth.token, { captionsVtt: 'WEBVTT\n\n00:00.000 --> 00:10.000\nPhụ đề đã sửa\n' });
  await page.reload(); await page.waitForFunction(() => document.querySelector('video')?.textTracks[0]?.cues?.[0]?.text === 'Phụ đề đã sửa');
  await browser.close(); console.log('PASS persisted WebVTT, student captions, transcript, edit and CSP in Chrome');
})().catch(async e => { console.error(e.message); if (browser) await browser.close(); process.exitCode = 1; });
