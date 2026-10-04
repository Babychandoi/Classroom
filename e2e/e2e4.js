'use strict';
// Vòng 20 (R20-07) trên trình duyệt thật: tự lưu bài làm phải sống sót qua lỗi máy chủ/mạng tạm thời. Đọc dữ liệu của lần chạy
// e2e.js gần nhất (shots/last-run.json: lớp, chủ lớp, học viên) và tạo thêm MỘT kỳ thi riêng cho vòng này (qua API, chủ lớp).
// KHÔNG khởi động lại backend (stack thật không được phép): thay vào đó page.route() làm PUT /attempts/*/answers trả 502 (rồi
// hủy kết nối) trong vài giây rồi cho đi tiếp, đúng như một lần restart backend nhìn từ trình duyệt.
//   K0  chuẩn bị: chủ lớp tạo + công bố kỳ thi 2 câu (1 trắc nghiệm, 1 tự luận); học viên đăng nhập, bắt đầu làm bài
//   K1  502 kéo dài vài giây: trạng thái "Đang lưu lại…" dịu (không cảnh báo đỏ), trình duyệt tự thử lại theo backoff mà học viên KHÔNG gõ thêm
//   K2  hết lỗi: bài làm tự lưu xong ("Đã tự động lưu"); API xác nhận cả hai câu đã nằm trên máy chủ; F5 khôi phục đúng
//   K3  mất kết nối hẳn (route.abort) rồi sự kiện 'online' -> lưu ngay, không chờ hết backoff
//   K4  nộp bài: kết quả đúng, lượt thi chỉ tính 1
//   K5  không có hộp thoại/lỗi console/CSP bất thường (ngoài các lỗi 502/abort cố ý)
const path = require('path');
const {
  BASE, PASSWORD, SHOTS_ROOT, log, newRunId, launchBrowser, newCtx, createRunner, settle, login, loadState, unexpectedEvents,
} = require('./lib');

const S = loadState();
if (!S.classId) { console.error('Thiếu classId (shots/last-run.json hoặc E2E_CLASS_ID).'); process.exit(2); }
const RUN = newRunId();
const { step, shot, events, summarize, instrument } = createRunner(path.join(SHOTS_ROOT, `r20-${RUN}`));
const EXAM = `R20 Exam ${RUN}`;
const classUrl = (p) => `${BASE}/classes/${S.classSlug}/${p}`;
const API = `${BASE}/api/v1`;

async function api(method, url, { token, body } = {}) {
  const res = await fetch(`${API}${url}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const json = await res.json().catch(() => null);
  if (!res.ok) throw new Error(`${method} ${url} -> ${res.status} ${JSON.stringify(json && json.error)}`);
  return json && json.data;
}
const loginApi = async (user) => (await api('POST', '/auth/login', { body: { email: user.email, password: PASSWORD } })).token;

(async () => {
  const browser = await launchBrowser();
  log('browser', browser.version(), 'class', S.classSlug, 'base', BASE);
  const stuCtx = await newCtx(browser);
  const stu = await stuCtx.newPage();
  instrument(stu, 'student');

  let examId = null;
  // Điều khiển lỗi được bơm vào PUT /attempts/*/answers: 'ok' | 'bad-gateway' | 'abort'
  let mode = 'ok';
  const blocked = { badGateway: 0, aborted: 0, passed: 0 };
  const answersBodies = [];
  await stu.route('**/api/v1/attempts/*/answers', async (route) => {
    if (route.request().method() !== 'PUT') return route.continue();
    if (mode === 'bad-gateway') {
      blocked.badGateway += 1;
      return route.fulfill({ status: 502, contentType: 'text/html', body: '<html><body>502 Bad Gateway</body></html>' });
    }
    if (mode === 'abort') {
      blocked.aborted += 1;
      return route.abort('internetdisconnected');
    }
    blocked.passed += 1;
    try { answersBodies.push(JSON.parse(route.request().postData() || '{}')); } catch { /* ignore */ }
    return route.continue();
  });

  // ---------------- K0: chuẩn bị ----------------
  await step('K0', 'owner creates and publishes a 2-question exam (API)', null, async () => {
    const owner = await loginApi(S.owner);
    const exam = await api('POST', `/classes/${S.classId}/exams`, {
      token: owner, body: { title: EXAM, durationMinutes: 30, attemptLimit: 1, audienceScope: 'ALL', passScore: 50 },
    });
    examId = exam.id;
    await api('POST', `/exams/${examId}/questions`, { token: owner, body: {
      question: { questionText: 'R20: 2 + 3 = ?', type: 'MULTIPLE_CHOICE', points: 5, position: 0, answerKey: 'B' },
      options: [['A', '4'], ['B', '5'], ['C', '6']].map(([k, t], i) => ({ optionKey: k, optionText: t, position: i })) } });
    await api('POST', `/exams/${examId}/questions`, { token: owner, body: {
      question: { questionText: 'R20: Mô tả ngắn gọn về autosave', type: 'ESSAY', points: 5, position: 1 }, options: [] } });
    await api('POST', `/exams/${examId}/publish`, { token: owner });
    return `exam ${examId}`;
  });
  await step('K0', 'student logs in and starts the attempt', stu, async () => {
    await login(stu, S.student);
    await stu.goto(classUrl('exams'));
    await stu.getByText(EXAM).first().waitFor({ timeout: 10000 });
    // straight to this exam's attempt page (the list may hold the exams of other E2E rounds too)
    await stu.goto(classUrl(`exams/${examId}/attempt`));
    await stu.getByRole('button', { name: 'Bắt đầu làm bài' }).click();
    await stu.getByText('R20: 2 + 3 = ?').waitFor({ timeout: 10000 });
  });

  // ---------------- K1: 502 kéo dài ----------------
  await step('K1', 'while the backend answers 502, the student sees a calm "Đang lưu lại…" (no red alert) and keeps working', stu, async () => {
    mode = 'bad-gateway';
    await stu.locator('label', { hasText: '5' }).filter({ has: stu.locator('input[type=radio]') }).first().click();
    await stu.getByLabel('Câu trả lời tự luận cho câu hỏi 2').fill('Autosave phải sống sót qua lần restart backend - E2E R20');
    await stu.getByText('Đang lưu lại…').first().waitFor({ timeout: 8000 });
    const alert = await stu.getByRole('alert').count();
    if (alert > 0) throw new Error(`the alarming unsaved banner (role=alert) is shown while retrying (${alert})`);
    if (await stu.getByText(/chưa được ghi nhận/).count()) throw new Error('the "not recorded" failure copy is shown for a transient 502');
    const status = await stu.getByRole('status').filter({ hasText: 'Đang lưu lại…' }).count();
    if (status < 1) throw new Error('no role=status "Đang lưu lại…" message');
    await shot(stu, 'K1-retrying');
  });
  await step('K1', 'with NO further typing the browser keeps retrying with exponential backoff (>= 3 attempts in ~6 s)', stu, async () => {
    const before = blocked.badGateway;
    await stu.waitForTimeout(6500);
    const retries = blocked.badGateway - before;
    // backoff 1 s, 2 s, 4 s: at least two more attempts must have happened within 6.5 s after the first failures
    if (blocked.badGateway < 3) throw new Error(`only ${blocked.badGateway} attempts reached the (502) route in total`);
    return `502 attempts total=${blocked.badGateway}, during the wait=${retries}`;
  });

  // ---------------- K2: hết lỗi ----------------
  await step('K2', 'when the backend recovers the answers are saved by themselves ("Đã tự động lưu")', stu, async () => {
    mode = 'ok';
    // at most one backoff period (<= 8 s here: 1+2+4 s elapsed, the next wait is 8 s)
    await stu.getByText('Đã tự động lưu').waitFor({ timeout: 15000 });
    if (await stu.getByText('Đang lưu lại…').count()) throw new Error('still says "Đang lưu lại…" after a successful save');
    const last = answersBodies[answersBodies.length - 1];
    if (!last || Object.keys(last.answers || {}).length !== 2) throw new Error(`the successful save did not carry both answers: ${JSON.stringify(last)}`);
    return `passed=${blocked.passed}`;
  });
  await step('K2', 'the API confirms both answers are on the server (independent of the UI)', null, async () => {
    const token = await loginApi(S.student);
    const attempt = await api('POST', `/exams/${examId}/attempts?resumeOnly=true`, { token });
    const answers = Object.fromEntries((attempt.answers || []).map((a) => [a.questionId, a.studentAnswer]));
    const values = Object.values(answers);
    if (!values.includes('B')) throw new Error(`multiple-choice answer missing on the server: ${JSON.stringify(answers)}`);
    if (!values.some((v) => String(v).includes('E2E R20'))) throw new Error(`essay answer missing on the server: ${JSON.stringify(answers)}`);
    return `${values.length} answers persisted`;
  });
  await step('K2', 'F5 restores both answers from the server', stu, async () => {
    await stu.reload();
    await stu.getByText('R20: 2 + 3 = ?').waitFor({ timeout: 10000 });
    const checked = await stu.locator('input[type=radio]:checked').getAttribute('value');
    const essay = await stu.getByLabel('Câu trả lời tự luận cho câu hỏi 2').inputValue();
    if (checked !== 'B' || !essay.includes('E2E R20')) throw new Error(`answers not restored radio=${checked} essay=${essay}`);
  });

  // ---------------- K3: mất mạng hẳn rồi 'online' ----------------
  await step('K3', 'connection lost (requests aborted): calm status; the browser "online" event saves at once, without waiting for the backoff', stu, async () => {
    mode = 'abort';
    await stu.locator('label', { hasText: '4' }).filter({ has: stu.locator('input[type=radio]') }).first().click();
    await stu.getByText('Đang lưu lại…').first().waitFor({ timeout: 8000 });
    if (await stu.getByRole('alert').count()) throw new Error('alarming banner shown while offline');
    // let the backoff grow so that a timer-only recovery would be slow
    await stu.waitForTimeout(3500);
    mode = 'ok';
    const t0 = Date.now();
    await stu.evaluate(() => window.dispatchEvent(new Event('online')));
    await stu.getByText('Đã tự động lưu').waitFor({ timeout: 2500 });
    return `saved ${Date.now() - t0}ms after the online event`;
  });

  // ---------------- K4: nộp bài ----------------
  await step('K4', 'submit: result page, the attempt is counted once and the latest answer (A) was graded', stu, async () => {
    await stu.getByRole('button', { name: 'Hoàn tất & nộp bài' }).click();
    // Nộp bài qua hộp thoại xác nhận (cả hai câu đã trả lời).
    const confirm = stu.getByRole('alertdialog', { name: 'Nộp bài và kết thúc lượt làm?' });
    await confirm.getByText('2/2').waitFor({ timeout: 5000 });
    await confirm.getByRole('button', { name: 'Xác nhận nộp bài' }).click();
    await stu.waitForURL(/\/result\?attemptId=/, { timeout: 15000 });
    await settle(stu);
    await shot(stu, 'K4-result');
    const token = await loginApi(S.student);
    const attempts = await api('GET', `/exams/${examId}/my-attempts`, { token });
    if (attempts.length !== 1) throw new Error(`expected exactly 1 attempt, got ${attempts.length}`);
    const answers = Object.values(Object.fromEntries((attempts[0].answers || []).map((a) => [a.questionId, a.studentAnswer])));
    if (!answers.includes('A')) throw new Error(`the last answer (A) was not the one stored: ${JSON.stringify(answers)}`);
    return `status=${attempts[0].status}`;
  });

  // ---------------- K5: sạch ----------------
  await step('K5', 'no unexpected dialogs or console/CSP errors (the injected 502 / aborted PUTs are expected)', stu, async () => {
    const problems = unexpectedEvents(events).filter((e) => {
      if (e.kind.startsWith('dialog.')) return true;
      if (e.kind === 'pageerror') return true;
      const injected = /\/attempts\/[^/]+\/answers/.test(e.text) || /status of 502/.test(e.text) || /ERR_INTERNET_DISCONNECTED/.test(e.text);
      if (injected) return false;
      if (e.kind === 'console.error') return !/Failed to load resource/.test(e.text);
      return /^http\.5/.test(e.kind);
    });
    if (problems.length) throw new Error(problems.slice(0, 4).map((p) => `${p.label} ${p.kind}: ${p.text}`).join(' | '));
    return `events=${events.length}, injected 502=${blocked.badGateway}, aborted=${blocked.aborted}`;
  });

  await browser.close();
  process.exitCode = summarize('Vòng 20 (e2e4.js)');
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
