'use strict';
// Tạo dữ liệu thử qua chính API (đăng ký người dùng, lớp, kỳ thi, bài viết) - dùng chung cho seed.js và smoke.js.
const { API, req, call, data, sleep, log, extraIps, summary } = require('./lib');

const PASSWORD = 'LoadTest#Passw0rd1';
const TOKEN_MAX_AGE_MS = 40 * 60 * 1000; // JWT truy cập sống 1 giờ; làm mới sớm hơn

/** Gọi lại khi bị giới hạn tốc độ xác thực (429) theo Retry-After. */
async function authCall(path, body, ip) {
  for (let attempt = 0; attempt < 12; attempt++) {
    const r = await req('POST', API + path, { body, ip, timeoutMs: 60000 });
    if (r.status !== 429) return r;
    const wait = Math.min(65, Number(r.headers['retry-after']) || 61);
    log(`429 ở ${path} (IP ${ip || 'mặc định'}), chờ ${wait}s theo Retry-After`);
    await sleep(wait * 1000);
  }
  throw new Error(`Bị giới hạn tốc độ liên tục ở ${path}`);
}

/** Chạy fn cho từng phần tử theo lô `batch` để không làm nghẽn bcrypt của backend ngay khi chuẩn bị dữ liệu. */
async function inBatches(items, batch, fn) {
  const out = new Array(items.length);
  for (let i = 0; i < items.length; i += batch) {
    const slice = items.slice(i, i + batch);
    const res = await Promise.all(slice.map((item, k) => fn(item, i + k)));
    res.forEach((v, k) => { out[i + k] = v; });
  }
  return out;
}

/** Đăng ký n người dùng; trải đều trên nhiều IP nguồn nếu container có NET_ADMIN, nếu không thì giãn theo Retry-After. */
async function registerUsers(run, prefix, n) {
  const ips = extraIps(Math.max(1, Math.ceil(n / 5)));
  if (!ips.length && n > 8) {
    log(`CẢNH BÁO: không thêm được IP nguồn phụ (thiếu --cap-add NET_ADMIN?); đăng ký ${n} người dùng đều đi từ MỘT IP (giới hạn mặc định 300 lần/phút/IP, đổi bằng AUTH_RL_REGISTER_PER_IP_PER_MINUTE) và tự giãn theo Retry-After nếu bị 429.`);
  }
  const users = Array.from({ length: n }, (_, i) => ({ email: `lt.${prefix}${i}.${run}@example.com`, fullName: `LoadTest ${prefix}${i}` }));
  const results = await inBatches(users, ips.length ? 100 : 10, async (u, i) => {
    const r = await authCall('/auth/register', { email: u.email, password: PASSWORD, fullName: u.fullName }, ips.length ? ips[i % ips.length] : undefined);
    if (r.status !== 200) throw new Error(`đăng ký ${u.email} -> ${r.status} ${r.err || r.text.slice(0, 200)}`);
    const d = data(r);
    return { ...u, userId: d.userId, token: d.token };
  });
  return results;
}

async function login(user, ip) {
  const r = await authCall('/auth/login', { email: user.email, password: PASSWORD }, ip);
  if (r.status !== 200) throw new Error(`đăng nhập ${user.email} -> ${r.status}`);
  return data(r).token;
}

/** Làm mới token nếu state đã cũ. Trả về state (đã ghi lại token mới). */
async function ensureTokens(state, save) {
  if (state.tokenAt && Date.now() - state.tokenAt < TOKEN_MAX_AGE_MS) return state;
  log('Token trong state đã cũ - đăng nhập lại toàn bộ người dùng thử');
  const ips = extraIps(Math.max(1, Math.ceil((state.students.length + 1) / 5)));
  const all = [state.owner, ...state.students];
  const tokens = await inBatches(all, ips.length ? 100 : 10, (u, i) => login(u, ips.length ? ips[i % ips.length] : undefined));
  all.forEach((u, i) => { u.token = tokens[i]; });
  state.tokenAt = Date.now();
  if (save) save(state);
  return state;
}

async function createExam(state, { questions, durationMinutes, index }) {
  const owner = state.owner.token;
  const exam = data(await call('POST', `/classes/${state.classId}/exams`, { token: owner, must: true, body: {
    title: `LoadTest exam ${state.run} #${index}`, durationMinutes, attemptLimit: 1, audienceScope: 'ALL', passScore: 50 } }));
  const qids = [];
  for (let i = 0; i < questions; i++) {
    const q = data(await call('POST', `/exams/${exam.id}/questions`, { token: owner, must: true, body: {
      question: { questionText: `Câu ${i + 1}: chọn đáp án đúng`, type: 'MULTIPLE_CHOICE', points: Math.max(1, Math.floor(100 / questions)), position: i, answerKey: 'A' },
      options: ['A', 'B', 'C', 'D'].map((k, p) => ({ optionKey: k, optionText: `Lựa chọn ${k}`, position: p })) } }));
    qids.push(q.id);
  }
  return { examId: exam.id, qids, used: false };
}

/** Quy tắc thưởng cố định (điểm thi -> điểm xếp hạng) dùng cho mọi kỳ thi thử; scenario tính lại điểm mong đợi từ đây. */
const REWARD_RULES = [{ minExamScore: 90, rewardPoints: 100 }, { minExamScore: 50, rewardPoints: 50 }, { minExamScore: 0, rewardPoints: 10 }];
function expectedReward(score) {
  const s = Number(score);
  for (const r of REWARD_RULES) if (s >= r.minExamScore) return r.rewardPoints;
  return 0;
}

async function configureAndPublish(state, examEntries) {
  const owner = state.owner.token;
  // PUT thay TOÀN BỘ cấu hình xếp hạng của lớp: luôn gửi quy tắc cho mọi kỳ thi đã tạo.
  const all = [...(state.exams || []), ...examEntries];
  await call('PUT', `/classes/${state.classId}/leaderboard/configuration`, { token: owner, must: true, body: {
    tiers: [{ tierName: 'Bronze', minPoints: 10, badgeUrl: null, description: 'b' }, { tierName: 'Gold', minPoints: 80, badgeUrl: null, description: 'g' }],
    rewards: all.flatMap((e) => REWARD_RULES.map((r) => ({ examId: e.examId, minExamScore: r.minExamScore, rewardPoints: r.rewardPoints }))) } });
  for (const e of examEntries) await call('POST', `/exams/${e.examId}/publish`, { token: owner, must: true });
}

/** Bài viết + bình luận để đo bảng tin: `posts` bài, mỗi bài `comments` bình luận do các học viên khác nhau viết. */
async function createFeedContent(state, posts, comments) {
  if (!posts) return 0;
  const owner = state.owner.token;
  const students = state.students;
  let made = 0;
  const postIds = [];
  for (let p = 0; p < posts; p++) {
    const d = data(await call('POST', `/classes/${state.classId}/posts`, { token: state.students[p % students.length].token, must: true, body: {
      title: `Bài thảo luận ${p + 1}`, contentMarkdown: `Nội dung bài ${p + 1} - trao đổi bài tập.\n`.repeat(6), visibility: 'FREE' } }));
    postIds.push(d.id);
  }
  const jobs = [];
  for (const postId of postIds) {
    for (let c = 0; c < comments; c++) jobs.push({ postId, u: students[(jobs.length * 7) % students.length] });
  }
  await inBatches(jobs, 25, async (j, i) => {
    const r = await call('POST', `/posts/${j.postId}/comments`, { token: j.u.token, body: { content: `Bình luận ${i}: cảm ơn thầy, em đã hiểu bài.` } });
    if (r.status === 200) made++;
  });
  return { posts: postIds.length, comments: made, owner: owner ? 1 : 0 };
}

async function joinAll(state) {
  const res = await inBatches(state.students, 50, (s) => req('POST', `${API}/classes/${state.classId}/join`, { token: s.token }));
  log('tham gia lớp', JSON.stringify(summary('join', res)));
}

module.exports = {
  PASSWORD, registerUsers, ensureTokens, createExam, configureAndPublish, createFeedContent, joinAll, inBatches,
  REWARD_RULES, expectedReward,
};
