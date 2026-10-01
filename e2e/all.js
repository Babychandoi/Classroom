'use strict';
// Chạy lần lượt toàn bộ bộ E2E (e2e -> race -> e2e2 -> netwatch -> mobnav -> e2e3 -> e2e4 -> origin -> e2e5 -> a11y) và in bảng tổng kết.
// Một script lỗi không chặn các script sau; mã thoát khác 0 nếu có bất kỳ script nào lỗi.
const { spawnSync } = require('child_process');
const { assertSafeTarget } = require('./lib');

assertSafeTarget();

const SUITE = [
  ['e2e.js', 'Hành trình chính J1-J6'],
  ['race.js', 'Race khởi tạo phiên'],
  ['e2e2.js', 'Kiểm tra bổ sung'],
  ['netwatch.js', 'Theo dõi mạng / CSP'],
  ['mobnav.js', 'Điều hướng điện thoại'],
  // e2e3.js đổi vai trò/khóa học của lớp E2E (thêm trợ giảng, xóa một khóa) nên chạy sau các script còn lại.
  ['e2e3.js', 'Vòng 18: hộp thoại, thứ tự khóa, bàn phím, khách, mạng'],
  // e2e4.js (R20-07): tự lưu bài làm qua lỗi 502/mạng (page.route, không restart backend). Tạo thêm một kỳ thi riêng qua API.
  ['e2e4.js', 'Vòng 20: tự lưu bài làm qua 502/mất mạng (R20-07)'],
  // origin.js (R19-02): đăng ký/đăng nhập cùng nguồn tại đúng BASE_URL + Origin lạ vẫn bị 403. Tự đăng ký người dùng mới.
  ['origin.js', 'Vòng 19: đăng nhập theo origin (CORS sau nginx)'],
  // e2e5.js (D-19): lớp riêng tư / trả phí / mã mời / hết hạn. Cần stack CÓ LỚP PHỦ DEMO (hạt giống + sandbox); trên stack thường tự
  // bỏ qua (SKIP, mã thoát 0) - đặt E2E_DEMO=1 để coi việc thiếu lớp phủ demo là lỗi. Tự đăng ký người dùng/lớp riêng, không cần last-run.json.
  ['e2e5.js', 'Vòng 22: lớp riêng tư / trả phí / mã mời / hết hạn (D-19, cần lớp phủ demo)'],
  // a11y.js: axe-core (tùy chọn - tự bỏ qua nếu chưa cài axe-core); chỉ HỎNG khi có lỗi critical.
  ['a11y.js', 'Trợ năng axe-core'],
];

const outcome = [];
for (const [script, title] of SUITE) {
  console.log(`\n########## ${script} - ${title} ##########`);
  const r = spawnSync(process.execPath, [script], { cwd: __dirname, stdio: 'inherit', env: process.env });
  outcome.push({ script, title, code: r.status === null ? 2 : r.status });
}

console.log('\n================ TỔNG KẾT ================');
for (const o of outcome) console.log(`${o.code === 0 ? 'PASS' : 'FAIL'}  ${o.script.padEnd(12)} ${o.title}${o.code === 0 ? '' : ` (mã thoát ${o.code})`}`);
process.exit(outcome.every((o) => o.code === 0) ? 0 : 1);
