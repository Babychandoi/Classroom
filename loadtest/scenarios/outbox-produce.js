'use strict';
// Sinh sự kiện outbox cho kịch bản gián đoạn Mongo/Neo4j (R20-04/R20-05) - chạy bên trong mạng drill qua run-in-drill.sh:
//   node scenarios/outbox-produce.js --joins 30 --submits 10 [--probes 2] [--tag mongodb]
//  - tạo MỘT lớp mới, --joins học viên tham gia lớp đó  => --joins sự kiện MEMBER_JOINED trên CÙNG một aggregate (CLASSROOM)
//  - tạo một kỳ thi mới trong lớp của seed, --submits học viên bắt đầu + nộp bài => EXAM_SUBMITTED/EXAM_PUBLISHED trên NHIỀU aggregate
//  - --probes N (hoặc --probe = 1): thêm N lượt thi BẮT ĐẦU nhưng KHÔNG nộp (in ra attemptId + userId) để driver ép hết hạn bằng SQL và xem tác vụ nền có chốt lượt thi đúng hạn không
// Yêu cầu `node seed.js --users N ...` đã chạy (cần .state/state.json). In một dòng cuối "RESULT {json}" cho driver bash đọc.
const {
  API, assertSafeTarget, args, num, log, req, call, data, summary, saveState, loadState,
} = require('../lib');
const { ensureTokens, createExam, configureAndPublish, inBatches } = require('../fixtures');

(async () => {
  assertSafeTarget();
  const a = args();
  const joins = num(a.joins, 30);
  const submits = num(a.submits, 10);
  const probes = a.probes !== undefined ? num(a.probes, 1) : (a.probe ? 1 : 0);
  const tag = String(a.tag || 'outbox').replace(/[^a-z0-9-]/gi, '');
  const state = loadState();
  await ensureTokens(state, saveState);
  const need = Math.max(joins, submits + probes);
  if (state.students.length < need) throw new Error(`state chỉ có ${state.students.length} học viên, cần ${need} (chạy lại seed với --users ${need})`);
  const run = Date.now().toString(36);
  const result = { tag, run };

  // 1) một lớp mới, `joins` học viên tham gia => MEMBER_JOINED cùng aggregate
  const cls = data(await call('POST', '/classes', { token: state.owner.token, must: true, body: {
    title: `Outbox ${tag} ${run}`, slug: `outbox-${tag}-${run}`.toLowerCase(), description: 'kịch bản gián đoạn outbox (drill)' } }));
  result.classId = cls.id;
  const t0 = Date.now();
  const joined = await inBatches(state.students.slice(0, joins), 10, (s) => req('POST', `${API}/classes/${cls.id}/join`, { token: s.token }));
  result.joins = summary('join', joined);
  result.joinsOk = joined.filter((r) => r.status === 200).length;
  result.joinWallMs = Date.now() - t0;
  log('tham gia lớp', JSON.stringify(result.joins));

  // 2) một kỳ thi mới (attemptLimit=1), `submits` học viên nộp => sự kiện trên nhiều aggregate EXAM
  if (submits > 0 || probes > 0) {
    const exam = await createExam(state, { questions: 3, durationMinutes: 30, index: (state.exams || []).length + 1 });
    await configureAndPublish(state, [exam]);
    state.exams = [...(state.exams || []), exam];
    saveState(state);
    result.examId = exam.examId;
    const who = state.students.slice(0, submits);
    const starts = await inBatches(who, 5, (s) => req('POST', `${API}/exams/${exam.examId}/attempts`, { token: s.token }));
    const attempts = starts.map((r) => (r.status === 200 ? r.json.data.id : null));
    const subs = await inBatches(who, 5, (s, i) => (attempts[i]
      ? req('POST', `${API}/attempts/${attempts[i]}/submit`, { token: s.token, body: { answers: {} } })
      : Promise.resolve({ status: -1, ms: 0 })));
    result.submits = summary('submit', subs);
    result.submitsOk = subs.filter((r) => r.status === 200).length;
    log('nộp bài', JSON.stringify(result.submits));

    result.probes = [];
    for (let i = 0; i < probes; i++) {
      const s = state.students[submits + i];
      const r = await req('POST', `${API}/exams/${exam.examId}/attempts`, { token: s.token });
      if (r.status !== 200) throw new Error(`không bắt đầu được lượt thi thăm dò: ${r.status} ${r.text.slice(0, 200)}`);
      result.probes.push({ attemptId: r.json.data.id, userId: s.userId });
    }
  }
  console.log(`RESULT ${JSON.stringify(result)}`);
})().catch((e) => { console.error('outbox-produce lỗi:', e.message); process.exit(1); });
