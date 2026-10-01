'use strict';
// Kịch bản kỳ thi: N học viên cùng bấm "Bắt đầu" -> tự lưu ~1 lần/giây trong D giây -> cùng nộp bài ở cùng một thời điểm.
// Đo: p50/p95/p99 từng pha, tỷ lệ lỗi 5xx, sức khỏe readiness (dùng 1 kết nối DB) trong lúc bắn tải, mất/lệch/nhân đôi câu trả lời,
// và thời gian để bảng xếp hạng nhất quán với điểm thưởng mong đợi.
//   node scenarios/exam-burst.js --users 200 --duration 20 [--exam-index 0] [--json out.json]
// Ngưỡng (đổi bằng cờ): --max-start-p95 1500 --max-save-p95 300 --max-submit-p95 5000 --max-probe-p99 1000 --max-lb-lag 15000
const {
  API, assertSafeTarget, args, num, log, req, call, data, summary, sleep, saveState, loadState, checkThresholds,
  startReadinessProbe,
} = require('../lib');
const { ensureTokens, expectedReward } = require('../fixtures');

const OPTS = ['A', 'B', 'C', 'D'];

async function run(opts = {}) {
  const a = { ...args(), ...opts };
  const N = num(a.users, 200);
  const DURATION_S = num(a.duration, 20);
  const thresholds = {
    startP95: num(a['max-start-p95'], 1500), saveP95: num(a['max-save-p95'], 300), submitP95: num(a['max-submit-p95'], 5000),
    probeP99: num(a['max-probe-p99'], 1000), lbLag: num(a['max-lb-lag'], 15000),
  };
  const state = loadState();
  await ensureTokens(state, saveState);
  const exam = a['exam-index'] !== undefined ? state.exams[num(a['exam-index'], 0)] : state.exams.find((e) => !e.used);
  if (!exam) throw new Error('Không còn kỳ thi chưa dùng (attemptLimit=1). Chạy `node seed.js --reuse --exams 1` để thêm.');
  const sessions = state.students.slice(0, N);
  if (sessions.length < N) throw new Error(`state chỉ có ${sessions.length} học viên, cần ${N}. Chạy lại seed với --users ${N}.`);
  const { examId, qids } = exam;
  const report = { N, examId, questions: qids.length, thresholds };
  const probe = startReadinessProbe(100);

  // 1) Tất cả bấm "Bắt đầu làm bài" cùng lúc
  let t0 = Date.now();
  const starts = await Promise.all(sessions.map((s) => req('POST', `${API}/exams/${examId}/attempts`, { token: s.token })));
  report.startWallMs = Date.now() - t0;
  report.start = summary('bắt đầu lượt thi (đồng thời)', starts);
  const attempts = starts.map((r) => (r.status === 200 ? r.json.data.id : null));
  report.startFailures = starts.filter((r) => r.status !== 200).slice(0, 3).map((r) => `${r.status} ${r.err || r.text.slice(0, 200)}`);
  log('bắt đầu', JSON.stringify(report.start), 'wall', report.startWallMs);
  exam.used = true;
  saveState(state);

  // 2) Vòng tự lưu: mỗi học viên đổi/thêm một câu mỗi ~1 giây và PUT toàn bộ map (như ExamAttemptPage)
  const answers = sessions.map(() => ({}));
  const saves = [];
  const endAt = Date.now() + DURATION_S * 1000;
  await Promise.all(sessions.map(async (s, i) => {
    if (!attempts[i]) return;
    await sleep(Math.random() * 1000);
    let tick = 0;
    while (Date.now() < endAt) {
      const q = tick % qids.length;
      answers[i][qids[q]] = OPTS[(i + q + (tick >= qids.length * 2 ? 0 : tick)) % 4];
      const r = await req('PUT', `${API}/attempts/${attempts[i]}/answers`, { token: s.token, body: { answers: { ...answers[i] } } });
      saves.push(r);
      tick++;
      await sleep(Math.max(0, 1000 - r.ms));
    }
  }));
  if (saves.length) {
    report.autosave = summary('tự lưu (~1/giây/học viên)', saves);
    report.autosaveFailures = saves.filter((r) => r.status !== 200).slice(0, 3).map((r) => `${r.status} ${r.err || r.text.slice(0, 200)}`);
    log('tự lưu', JSON.stringify(report.autosave));
  }

  // Ảnh chụp bảng xếp hạng ngay trước khi nộp: lớp có thể còn điểm từ các kỳ thi khác, nên đối chiếu theo mức TĂNG.
  const leaderboardTotals = async () => {
    const r = await call('GET', `/classes/${state.classId}/leaderboard`, { token: state.owner.token });
    return new Map((((r.json && r.json.data) || [])).map((row) => [row.userId, row.totalPoints]));
  };
  const before = await leaderboardTotals();

  // 3) Tất cả nộp bài cùng lúc, kèm câu trả lời mới nhất trong body (như handleSubmit sau flushAutosave)
  t0 = Date.now();
  const subs = await Promise.all(sessions.map((s, i) => (attempts[i]
    ? req('POST', `${API}/attempts/${attempts[i]}/submit`, { token: s.token, body: { answers: answers[i] } })
    : Promise.resolve({ status: -1, ms: 0 }))));
  report.submitWallMs = Date.now() - t0;
  report.submit = summary('nộp bài (đồng thời)', subs);
  report.submitFailures = subs.filter((r) => r.status !== 200).slice(0, 3).map((r) => `${r.status} ${r.err || r.text.slice(0, 200)}`);
  log('nộp bài', JSON.stringify(report.submit), 'wall', report.submitWallMs);

  // Readiness trong lúc bắn tải (probe chạy suốt các pha 1-3)
  const probeResults = await probe.stop();
  report.probe = summary('readiness trong lúc bắn tải', probeResults);
  log('readiness', JSON.stringify(report.probe));

  // 4) Không mất / sai / nhân đôi câu trả lời: đối chiếu câu trả lời server trả về khi nộp với bản client đã gửi
  let lost = 0, wrong = 0, extra = 0, dup = 0, okAttempts = 0, published = 0;
  const expected = [];
  subs.forEach((r, i) => {
    if (r.status !== 200 || !r.json || !r.json.data) return;
    const d = r.json.data;
    if (String(d.status).toUpperCase() === 'PUBLISHED') published++;
    const got = new Map();
    for (const ans of d.answers || []) {
      if (got.has(ans.questionId)) dup++;
      got.set(ans.questionId, ans.studentAnswer);
    }
    let bad = false;
    for (const [q, v] of Object.entries(answers[i])) {
      if (!got.has(q)) { lost++; bad = true; } else if (got.get(q) !== v) { wrong++; bad = true; }
    }
    for (const q of got.keys()) if (answers[i][q] === undefined) { extra++; bad = true; }
    if (!bad) okAttempts++;
    expected.push({ userId: sessions[i].userId, score: d.score });
  });
  report.answers = { attempts: subs.filter((r) => r.status === 200).length, okAttempts, published, lost, wrong, extra, duplicateRows: dup };
  log('câu trả lời', JSON.stringify(report.answers));

  // 5) Bảng xếp hạng nhất quán (eventual consistency của tác vụ tính lại nền)
  const wantByUser = new Map(expected.map((e) => [e.userId, expectedReward(e.score)]));
  const lbStart = Date.now();
  let lag = null;
  let mismatched = wantByUser.size;
  while (Date.now() - lbStart < num(a['lb-timeout'], 90) * 1000) {
    const gotByUser = await leaderboardTotals();
    mismatched = 0;
    for (const [uid, want] of wantByUser) if ((gotByUser.get(uid) || 0) - (before.get(uid) || 0) !== want) mismatched++;
    if (mismatched === 0) { lag = Date.now() - lbStart; break; }
    await sleep(500);
  }
  report.leaderboard = { students: wantByUser.size, mismatchedAtEnd: mismatched, consistentAfterMs: lag };
  log('xếp hạng', JSON.stringify(report.leaderboard));

  const failures = checkThresholds([
    { name: 'bắt đầu: số lỗi (khác 200)', value: starts.filter((r) => r.status !== 200).length, equals: 0 },
    { name: 'bắt đầu: p95 (ms)', value: report.start.p95, max: thresholds.startP95 },
    ...(report.autosave ? [
      { name: 'tự lưu: số lỗi (khác 200)', value: saves.filter((r) => r.status !== 200).length, equals: 0 },
      { name: 'tự lưu: p95 (ms)', value: report.autosave.p95, max: thresholds.saveP95 },
    ] : []),
    { name: 'nộp bài: số lỗi (khác 200)', value: subs.filter((r) => r.status !== 200).length, equals: 0 },
    { name: 'nộp bài: p95 (ms)', value: report.submit.p95, max: thresholds.submitP95 },
    { name: 'readiness: số lần không 200', value: probeResults.filter((r) => r.status !== 200).length, equals: 0 },
    { name: 'readiness: p99 (ms)', value: report.probe.p99, max: thresholds.probeP99 },
    { name: 'câu trả lời mất+sai+thừa+nhân đôi', value: lost + wrong + extra + dup, equals: 0 },
    { name: 'xếp hạng nhất quán sau (ms)', value: lag, max: thresholds.lbLag },
  ]);
  report.failures = failures;
  if (a.json && typeof a.json === 'string') require('fs').writeFileSync(a.json, JSON.stringify(report, null, 2));
  return report;
}

module.exports = { run };

if (require.main === module) {
  assertSafeTarget();
  run().then((r) => { process.exit(r.failures.length ? 1 : 0); })
    .catch((e) => { console.error('exam-burst lỗi:', e.message); process.exit(1); });
}
