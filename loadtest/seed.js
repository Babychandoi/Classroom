'use strict';
// Chuẩn bị dữ liệu thử trên stack drill/dev: N học viên, 1 chủ lớp, 1 lớp, K kỳ thi (đã công bố, có quy tắc thưởng xếp hạng).
//   node seed.js --users 200 --questions 20 --exams 3 --duration 30 [--posts 30 --comments 20] [--reuse]
// --reuse: giữ lớp/người dùng đã có trong .state/state.json, chỉ thêm kỳ thi/bài viết mới.
const {
  API, assertSafeTarget, args, num, log, call, data, saveState, loadState, STATE_FILE,
} = require('./lib');
const {
  registerUsers, ensureTokens, createExam, configureAndPublish, createFeedContent, joinAll,
} = require('./fixtures');

(async () => {
  assertSafeTarget();
  const a = args();
  const users = num(a.users, 200);
  const questions = num(a.questions, 20);
  const exams = num(a.exams, 3);
  const duration = num(a.duration, 30);
  const posts = num(a.posts, 0);
  const comments = num(a.comments, 20);

  let state;
  if (a.reuse) {
    state = loadState();
    await ensureTokens(state, saveState);
  } else {
    const run = Date.now().toString(36);
    log(`Tạo dữ liệu thử trên ${API}: ${users} học viên, ${exams} kỳ thi x ${questions} câu (run ${run})`);
    const [owner] = await registerUsers(run, 'owner', 1);
    const students = await registerUsers(run, 's', users);
    const cls = data(await call('POST', '/classes', { token: owner.token, must: true, body: {
      title: `LoadTest Class ${run}`, slug: `loadtest-${run}`, description: 'Lớp dữ liệu thử cho kiểm thử tải - có thể xóa cùng drill' } }));
    state = { run, apiBase: API, tokenAt: Date.now(), owner, students, classId: cls.id, slug: cls.slug, exams: [] };
    await joinAll(state);
  }

  const created = [];
  for (let i = 0; i < exams; i++) {
    created.push(await createExam(state, { questions, durationMinutes: duration, index: state.exams.length + created.length + 1 }));
  }
  if (created.length) {
    await configureAndPublish(state, created);
    state.exams.push(...created);
  }
  if (posts) {
    const feed = await createFeedContent(state, posts, comments);
    state.feed = feed;
    log('nội dung bảng tin', JSON.stringify(feed));
  }
  saveState(state);
  log(`Xong: lớp ${state.classId} (${state.slug}), ${state.students.length} học viên, ${state.exams.length} kỳ thi -> ${STATE_FILE}`);
})().catch((e) => { console.error('seed lỗi:', e.message); process.exit(1); });
