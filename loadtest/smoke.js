'use strict';
// Kiểm tra nhanh cho CI (`npm run smoke`): dựng 40 học viên + 1 kỳ thi 10 câu, rồi chạy kịch bản kỳ thi với 40 lượt NỘP ĐỒNG THỜI
// (regression của R20-01: deadlock pool kết nối khi nhiều học viên nộp cùng lúc). Mã thoát 0 = đạt mọi ngưỡng.
//   USERS=40 npm run smoke        (đổi số học viên)
process.env.LOADTEST_STATE = process.env.LOADTEST_STATE || 'smoke.json';
const { spawnSync } = require('child_process');
const path = require('path');
const { assertSafeTarget, log } = require('./lib');

assertSafeTarget();
const USERS = Number(process.env.USERS || 40);

log(`smoke: chuẩn bị ${USERS} học viên...`);
const seed = spawnSync(process.execPath, [path.join(__dirname, 'seed.js'), '--users', String(USERS), '--questions', '10', '--exams', '1', '--duration', '20'],
  { stdio: 'inherit', env: process.env });
if (seed.status !== 0) { console.error('smoke: chuẩn bị dữ liệu thất bại'); process.exit(seed.status || 1); }

require('./scenarios/exam-burst').run({ users: USERS, duration: 3 })
  .then((r) => {
    if (r.failures.length) { console.error(`smoke: KHÔNG ĐẠT (${r.failures.length} vi phạm ngưỡng)`); process.exit(1); }
    log(`smoke: ĐẠT - ${USERS} lượt nộp đồng thời, p95 nộp ${r.submit.p95} ms, xếp hạng nhất quán sau ${r.leaderboard.consistentAfterMs} ms`);
  })
  .catch((e) => { console.error('smoke lỗi:', e.message); process.exit(1); });
