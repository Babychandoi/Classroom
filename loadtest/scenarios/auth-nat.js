'use strict';
// Kịch bản xác thực sau NAT của trường (R20-02): CẢ LỚP ngồi sau MỘT địa chỉ IP nguồn duy nhất.
//   node scenarios/auth-nat.js --users 200 --window 30 [--json out.json]
// Chạy từ MỘT địa chỉ (không dùng IP nguồn phụ): cả ba pha 1-3 phải thành công gần như tuyệt đối, còn pha 4-5 (vét cạn mật khẩu,
// nhồi thông tin đăng nhập) vẫn phải bị chặn. Bộ giới hạn cũ tính 10 lần/phút/IP nên 190/200 lượt bị 429.
//   pha 1  N học viên ĐĂNG NHẬP trải đều trong --window giây từ 1 IP          -> >= 99% 200
//   pha 2  N người dùng mới ĐĂNG KÝ trải đều trong --window giây từ 1 IP      -> >= 99% 200
//   pha 3  N phiên LÀM MỚI (refresh cookie) trải đều trong --window giây      -> >= 99% 200
//   pha 4  20 mật khẩu sai cho MỘT tài khoản: chỉ 5 lượt đầu được kiểm tra, các lượt sau 429 + Retry-After; học viên khác vẫn đăng nhập được
//   pha 5  nhồi thông tin đăng nhập: 400 lượt sai trên 400 tài khoản khác nhau -> trần 200 lượt sai/phút/IP chặn phần còn lại
// Cần dữ liệu từ seed.js (state.json) với ít nhất N học viên. Ngưỡng đổi bằng cờ: --min-ok-pct 99 --max-stuffing-evaluated 215
const {
  API, assertSafeTarget, args, num, log, req, call, summary, sleep, saveState, loadState, checkThresholds,
} = require('../lib');
const { ensureTokens, PASSWORD } = require('../fixtures');

function refreshCookie(res) {
  const sc = res.headers && res.headers['set-cookie'];
  if (!sc) return null;
  const c = (Array.isArray(sc) ? sc : [sc]).find((x) => x.startsWith('refresh_token='));
  return c ? c.split(';')[0] : null;
}

/** Chạy fn(i) cho i = 0..n-1, bắt đầu trải đều trong windowMs (như cả lớp bấm đăng nhập trong nửa phút). */
async function spread(n, windowMs, fn) {
  const out = new Array(n);
  await Promise.all(Array.from({ length: n }, async (_, i) => {
    await sleep(Math.floor((i * windowMs) / n));
    out[i] = await fn(i);
  }));
  return out;
}

const okPct = (results) => +((results.filter((r) => r.status === 200).length / results.length) * 100).toFixed(2);

async function run(opts = {}) {
  assertSafeTarget();
  const a = { ...args(), ...opts };
  const N = num(a.users, 200);
  const windowMs = num(a.window, 30) * 1000;
  const minOkPct = num(a['min-ok-pct'], 99);
  const maxStuffingEvaluated = num(a['max-stuffing-evaluated'], 215);
  const state = loadState();
  await ensureTokens(state, saveState);
  if (state.students.length < N) throw new Error(`state chỉ có ${state.students.length} học viên, cần ${N}. Chạy lại seed với --users ${N}.`);
  const students = state.students.slice(0, N);
  const run = Date.now().toString(36);
  const report = { N, windowSeconds: windowMs / 1000, source: 'một địa chỉ IP duy nhất (không dùng IP nguồn phụ)' };

  // pha 1: đăng nhập
  let res = await spread(N, windowMs, (i) => req('POST', `${API}/auth/login`, { body: { email: students[i].email, password: PASSWORD } }));
  report.login = summary('đăng nhập, 1 IP', res);
  report.loginOkPct = okPct(res);
  const cookies = res.map((r) => refreshCookie(r));
  log('đăng nhập', JSON.stringify(report.login));

  // pha 2: đăng ký
  res = await spread(N, windowMs, (i) => req('POST', `${API}/auth/register`, {
    body: { email: `lt.nat${i}.${run}@example.com`, password: PASSWORD, fullName: `NAT ${i}` } }));
  report.register = summary('đăng ký, 1 IP', res);
  report.registerOkPct = okPct(res);
  log('đăng ký', JSON.stringify(report.register));

  // pha 3: làm mới phiên
  res = await spread(N, windowMs, (i) => (cookies[i]
    ? req('POST', `${API}/auth/refresh`, { headers: { Cookie: cookies[i], 'X-Requested-With': 'XMLHttpRequest' } })
    : Promise.resolve({ status: -1, ms: 0, headers: {} })));
  report.refresh = summary('làm mới phiên, 1 IP', res);
  report.refreshOkPct = okPct(res);
  log('làm mới', JSON.stringify(report.refresh));

  // pha 4: vét cạn một tài khoản (tài khoản riêng để không khóa học viên của các kịch bản khác)
  const victim = { email: `lt.bf.${run}@example.com`, password: PASSWORD };
  await call('POST', '/auth/register', { body: { ...victim, fullName: 'Brute force target' }, must: true });
  const guesses = [];
  for (let i = 0; i < 20; i++) guesses.push(await req('POST', `${API}/auth/login`, { body: { email: victim.email, password: `sai-${i}` } }));
  const evaluated = guesses.filter((r) => r.status === 401).length;
  const throttled = guesses.filter((r) => r.status === 429);
  report.bruteForce = {
    attempts: 20, evaluated, throttled: throttled.length,
    retryAfter: [...new Set(throttled.map((r) => r.headers['retry-after']))],
    correctPasswordDuringLock: (await req('POST', `${API}/auth/login`, { body: victim })).status,
  };
  const classmates = await Promise.all(students.slice(0, 10).map((s) => req('POST', `${API}/auth/login`, { body: { email: s.email, password: PASSWORD } })));
  report.bruteForce.classmatesOk = classmates.filter((r) => r.status === 200).length;
  log('vét cạn', JSON.stringify(report.bruteForce));

  // pha 5: nhồi thông tin đăng nhập (nhiều tài khoản, mỗi tài khoản 1 lượt sai). Bắn theo đợt 40 yêu cầu đồng thời: trần lượt sai được tính khi
  // yêu cầu HOÀN TẤT nên một đợt bùng nổ có thể vượt trần tối đa bằng số yêu cầu đang chạy cùng lúc (đợt 40 -> sai lệch <= 40).
  const stuffing = [];
  for (let wave = 0; wave < 10; wave++) {
    stuffing.push(...await Promise.all(Array.from({ length: 40 }, (_, k) =>
      req('POST', `${API}/auth/login`, { body: { email: `lt.stuff${wave * 40 + k}.${run}@example.com`, password: 'Password1' } }))));
  }
  report.stuffing = {
    attempts: stuffing.length, evaluated: stuffing.filter((r) => r.status === 401).length, throttled: stuffing.filter((r) => r.status === 429).length,
    other: stuffing.filter((r) => r.status !== 401 && r.status !== 429).length,
  };
  log('nhồi thông tin', JSON.stringify(report.stuffing));

  const failures = checkThresholds([
    { name: 'đăng nhập từ 1 IP: tỷ lệ KHÔNG thành công (%)', value: +(100 - report.loginOkPct).toFixed(2), max: 100 - minOkPct },
    { name: 'đăng ký từ 1 IP: tỷ lệ KHÔNG thành công (%)', value: +(100 - report.registerOkPct).toFixed(2), max: 100 - minOkPct },
    { name: 'làm mới phiên từ 1 IP: tỷ lệ KHÔNG thành công (%)', value: +(100 - report.refreshOkPct).toFixed(2), max: 100 - minOkPct },
    { name: 'vét cạn: số lượt sai được kiểm tra mật khẩu', value: report.bruteForce.evaluated, max: 5 },
    { name: 'vét cạn: số lượt bị chặn 429 (>= 15)', value: 20 - report.bruteForce.throttled, max: 5 },
    { name: 'vét cạn: mật khẩu ĐÚNG trong lúc khóa vẫn bị chặn (mã HTTP)', value: report.bruteForce.correctPasswordDuringLock, equals: 429 },
    { name: 'vét cạn: học viên khác vẫn đăng nhập được (trên 10)', value: 10 - report.bruteForce.classmatesOk, max: 0 },
    { name: 'nhồi thông tin: số lượt sai được kiểm tra', value: report.stuffing.evaluated, max: maxStuffingEvaluated },
    { name: 'nhồi thông tin: số lượt bị chặn 429 (>= 150)', value: Math.max(0, 150 - report.stuffing.throttled), max: 0 },
    { name: 'không có lỗi 5xx/mạng ở các pha 1-3', value: Math.max(report.login.errorRate, report.register.errorRate, report.refresh.errorRate), max: 0 },
  ]);
  report.failures = failures;
  if (a.json && a.json !== true) require('fs').writeFileSync(a.json, JSON.stringify(report, null, 2));
  return report;
}

if (require.main === module) {
  run().then((r) => {
    if (r.failures.length) { console.error(`auth-nat: KHÔNG ĐẠT (${r.failures.length} vi phạm ngưỡng)`); process.exit(1); }
    log('auth-nat: ĐẠT');
  }).catch((e) => { console.error('auth-nat lỗi:', e.message); process.exit(1); });
}

module.exports = { run };
