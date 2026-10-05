#!/usr/bin/env node
// Dựng MỘT lớp học "trưng bày" đầy đủ dữ liệu ở MỌI tab để bấm thử giao diện (thiết kế Connecty) bằng tay.
//
//   Lớp: lop-demo-day-du - "Luyện thi Toán vào 10 chuyên — Lớp Demo đầy đủ" (PUBLIC, miễn phí, danh mục "Ôn thi").
//   Phủ: Thảo luận (bảng tin), Blog, Khóa học (video/đọc/tài liệu/bài tập, Q&A, nộp bài, tiến độ), Thi (trắc nghiệm,
//   tự luận + hàng đợi chấm, PRO, hẹn giờ, đã đóng), Bảng xếp hạng (bậc + thưởng), Sự kiện, Tài liệu, Thành viên (kể cả
//   một người bị gỡ và một người bị chặn), Cửa hàng + đơn hàng (sandbox), Giới thiệu, Phân khúc, mã mời.
//
// Mọi thứ đi qua API HTTP THẬT bằng token của chủ lớp / trợ giảng / học viên đúng như giao diện sẽ làm (tôn trọng phân quyền);
// không đụng vào mã nguồn backend/frontend, không dùng docker, không xóa bất cứ thứ gì. Không phụ thuộc gói ngoài (Node 20+,
// fetch toàn cục, ảnh PNG/PDF tự sinh bằng node:zlib - không tải gì từ Internet).
//
// Cách chạy (stack demo đang chạy: backend 8080, MinIO 9000, web 13000):
//   node infra/scripts/seed-showcase.mjs                       # lần đầu; nếu lớp đã tồn tại thì TỪ CHỐI (không ghi đè)
//   node infra/scripts/seed-showcase.mjs --force-new-suffix    # tạo một bản mới, slug/tiêu đề có hậu tố ngẫu nhiên
//   node infra/scripts/seed-showcase.mjs --resume              # chạy tiếp/bổ sung vào lớp đã có (bỏ qua phần đã tạo)
//
// Biến môi trường: BASE_URL (API, mặc định http://localhost:8080), WEB_URL (chỉ để in liên kết, mặc định http://localhost:13000),
// SHOWCASE_PASSWORD (mật khẩu các tài khoản demo, mặc định Password123!).
// An toàn: từ chối mọi BASE_URL không phải localhost/127.x.x.x/::1 (cùng kiểu với assertSafeTarget của e2e/lib.js) và
// KHÔNG có cờ nào để bỏ qua kiểm tra này - script tạo người dùng, lớp học và tệp thật.
//
// Tài khoản (mật khẩu Password123!): owner@classroom.local (chủ lớp), staff@classroom.local (trợ giảng), student.free@ và
// student.pro@classroom.local, và demo.hocvien01..15@example.com.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

// ------------------------------------------------------------------------------------------------------------------
// Cấu hình + chốt an toàn
// ------------------------------------------------------------------------------------------------------------------
const ARGS = new Set(process.argv.slice(2));
const BASE = (process.env.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const WEB = (process.env.WEB_URL || 'http://localhost:13000').replace(/\/+$/, '');
const API = `${BASE}/api/v1`;
const PASSWORD = process.env.SHOWCASE_PASSWORD || 'Password123!';
const LOCAL_HOST = /^(localhost|127(\.\d{1,3}){3}|\[?::1\]?|[^.]+\.localhost)$/i;
const HERE = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES = path.resolve(HERE, '../../e2e/fixtures');
const BASE_SLUG = 'lop-demo-day-du';
const BASE_TITLE = 'Luyện thi Toán vào 10 chuyên — Lớp Demo đầy đủ';

function assertSafeTarget() {
  for (const url of [BASE, WEB]) {
    let host;
    try { host = new URL(url).hostname; } catch { host = ''; }
    if (!LOCAL_HOST.test(host)) {
      console.error(
        `Từ chối chạy: ${url} không phải máy cục bộ.\n` +
        'Script này tạo người dùng, lớp học và tệp thật trên hệ thống đích và KHÔNG BAO GIỜ được trỏ vào production.',
      );
      process.exit(2);
    }
  }
}
assertSafeTarget();
if (ARGS.has('--help') || ARGS.has('-h')) {
  console.log('Dùng: node infra/scripts/seed-showcase.mjs [--force-new-suffix | --resume]');
  process.exit(0);
}
const FORCE_SUFFIX = ARGS.has('--force-new-suffix');
const RESUME = ARGS.has('--resume');

// ------------------------------------------------------------------------------------------------------------------
// Tiện ích chung
// ------------------------------------------------------------------------------------------------------------------
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const t0 = Date.now();
const log = (...args) => console.log(`[${String(Math.round((Date.now() - t0) / 1000)).padStart(4)}s]`, ...args);
const section = (title) => console.log(`\n=== ${title} ===`);
const warnings = [];
const warn = (message) => { warnings.push(message); console.warn(`  ! ${message}`); };
/** Nội dung bài học được giao diện hiển thị dạng chữ thuần (không dựng markdown): bỏ ký hiệu ##, **, `, >. */
const plain = (md) => md.replace(/^#{1,6}\s*/gm, '').replace(/\*\*(.+?)\*\*/g, '$1').replace(/`([^`\n]*)`/g, '$1').replace(/^>\s?/gm, '').replace(/\*([^*\n]+)\*/g, '$1');
const fold = (s) => s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'D');

class ApiError extends Error {
  constructor(status, code, message, route) {
    super(`${route} -> ${status} ${code || ''} ${message || ''}`.trim());
    this.status = status; this.code = code; this.route = route;
  }
}

async function request(method, route, { token, body, headers = {} } = {}) {
  for (let attempt = 0; ; attempt++) {
    let res;
    try {
      res = await fetch(`${API}${route}`, {
        method,
        headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch (error) {
      if (attempt < 3) { await sleep(1500); continue; }
      throw error;
    }
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { json = null; }
    if (res.status === 429 && attempt < 10) {
      const wait = (Number(res.headers.get('retry-after')) || 5) + 1;
      log(`429 ${method} ${route} - chờ ${wait}s`);
      await sleep(wait * 1000);
      continue;
    }
    if ((res.status === 502 || res.status === 503) && attempt < 4) { await sleep(2000); continue; }
    if (!res.ok || (json && json.success === false)) {
      const error = (json && json.error) || {};
      throw new ApiError(res.status, error.code, error.message || text.slice(0, 200), `${method} ${route}`);
    }
    return json ? json.data : null;
  }
}

/** Một phiên đăng nhập: tự đăng nhập lại khi token hết hạn (script chạy vài phút). */
class Session {
  constructor(key, email, name) { this.key = key; this.email = email; this.name = name; this.token = null; this.id = null; }
  async login() {
    const data = await request('POST', '/auth/login', { body: { email: this.email, password: PASSWORD } });
    this.token = data.token; this.id = data.userId; this.name = data.fullName || this.name;
    return this;
  }
  async call(method, route, body, headers) {
    try {
      return await request(method, route, { token: this.token, body, headers });
    } catch (error) {
      if (error instanceof ApiError && error.status === 401 && this.token) { await this.login(); return request(method, route, { token: this.token, body, headers }); }
      throw error;
    }
  }
  get(route) { return this.call('GET', route); }
  post(route, body) { return this.call('POST', route, body); }
  put(route, body) { return this.call('PUT', route, body); }
  del(route) { return this.call('DELETE', route); }
  /** Thử gọi, trả về null thay vì ném lỗi khi trạng thái nằm trong `tolerate`. */
  async tryCall(tolerate, method, route, body) {
    try { return await this.call(method, route, body); } catch (error) {
      if (error instanceof ApiError && tolerate.includes(error.status)) return null;
      throw error;
    }
  }
}

// ------------------------------------------------------------------------------------------------------------------
// Bộ mã hóa PNG (node:zlib) - ảnh gradient/họa tiết toán học tự sinh
// ------------------------------------------------------------------------------------------------------------------
const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; table[n] = c >>> 0; }
  return table;
})();
function crc32(buf) { let c = 0xffffffff; for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; }
function pngChunk(type, data) {
  const length = Buffer.alloc(4); length.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}
function encodePng(width, height, rgb) {
  const stride = width * 3;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    const row = y * (stride + 1);
    raw[row] = 1; // bộ lọc "Sub": gradient mượt nén rất tốt
    for (let x = 0; x < stride; x++) raw[row + 1 + x] = (rgb[y * stride + x] - (x >= 3 ? rgb[y * stride + x - 3] : 0)) & 0xff;
  }
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0); header.writeUInt32BE(height, 4); header[8] = 8; header[9] = 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', header), pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })), pngChunk('IEND', Buffer.alloc(0)),
  ]);
}
const hex = (h) => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
const smooth = (t) => { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); };
const mix = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
/** Mulberry32: ngẫu nhiên có hạt giống để ảnh/điểm thi luôn giống nhau giữa các lần chạy. */
function rng(seed) { let a = seed >>> 0; return () => { a = (a + 0x6d2b79f5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }

/** Ảnh "giấy kẻ ô + đường tròn + sóng sin" trên nền gradient chéo. theme = { a, b, c, seed, kind } */
function makeImage(width, height, theme) {
  const rand = rng(theme.seed);
  const a = hex(theme.a), b = hex(theme.b), c = hex(theme.c);
  const rings = Array.from({ length: 5 }, () => ({ x: rand() * width, y: rand() * height, r: (0.18 + rand() * 0.45) * Math.min(width, height), w: 1.5 + rand() * 3, alpha: 0.18 + rand() * 0.25 }));
  const discs = Array.from({ length: 3 }, () => ({ x: rand() * width, y: rand() * height, r: (0.12 + rand() * 0.28) * Math.min(width, height), alpha: 0.08 + rand() * 0.12 }));
  const waves = Array.from({ length: 3 }, (_, i) => ({ base: height * (0.3 + 0.22 * i), amp: height * (0.08 + rand() * 0.07), freq: (2 + rand() * 3) * Math.PI / width, phase: rand() * 6.28, alpha: 0.55 - i * 0.12 }));
  const grid = Math.max(24, Math.round(Math.min(width, height) / 8));
  const out = Buffer.alloc(width * height * 3);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const diag = (x / width) * 0.7 + (y / height) * 0.3;
      let col = mix(mix(a, b, smooth(diag)), c, smooth(1 - Math.hypot(x - width * 0.85, y - height * 0.2) / (width * 0.6)) * 0.55);
      for (const d of discs) col = mix(col, [255, 255, 255], smooth(1 - (Math.hypot(x - d.x, y - d.y) - d.r) / 1.5) * d.alpha);
      for (const r of rings) col = mix(col, [255, 255, 255], smooth(1 - Math.abs(Math.hypot(x - r.x, y - r.y) - r.r) / r.w) * r.alpha);
      if (x % grid < 1.2 || y % grid < 1.2) col = mix(col, [255, 255, 255], 0.07);
      for (const w of waves) col = mix(col, [255, 255, 255], smooth(1 - Math.abs(y - (w.base + w.amp * Math.sin(x * w.freq + w.phase))) / 2.2) * w.alpha);
      if (theme.kind === 'avatar') { // hình tam giác nội tiếp đường tròn ở giữa
        const cx = width / 2, cy = height / 2, R = width * 0.3;
        col = mix(col, [255, 255, 255], smooth(1 - Math.abs(Math.hypot(x - cx, y - cy) - R) / 3) * 0.9);
        const pts = [0, 1, 2].map((k) => [cx + R * Math.cos(-Math.PI / 2 + k * 2.0944), cy + R * Math.sin(-Math.PI / 2 + k * 2.0944)]);
        for (let k = 0; k < 3; k++) {
          const [x1, y1] = pts[k], [x2, y2] = pts[(k + 1) % 3];
          const t = Math.max(0, Math.min(1, ((x - x1) * (x2 - x1) + (y - y1) * (y2 - y1)) / ((x2 - x1) ** 2 + (y2 - y1) ** 2)));
          col = mix(col, [255, 255, 255], smooth(1 - Math.hypot(x - (x1 + t * (x2 - x1)), y - (y1 + t * (y2 - y1))) / 3) * 0.9);
        }
      }
      const i = (y * width + x) * 3;
      out[i] = col[0]; out[i + 1] = col[1]; out[i + 2] = col[2];
    }
  }
  return encodePng(width, height, out);
}
const PALETTES = [
  { a: '#1f3a93', b: '#4f8cff', c: '#7ce0d3' }, { a: '#6a1b9a', b: '#ec6ead', c: '#ffd36e' }, { a: '#0b6e4f', b: '#3ddc97', c: '#e6f08a' },
  { a: '#b23a48', b: '#ff8c61', c: '#ffe29a' }, { a: '#0d3b66', b: '#2a9d8f', c: '#f4d35e' }, { a: '#3d348b', b: '#7678ed', c: '#f7b801' },
  { a: '#14213d', b: '#fca311', c: '#e5e5e5' }, { a: '#264653', b: '#e76f51', c: '#f4a261' },
];
const image = (w, h, i, kind) => makeImage(w, h, { ...PALETTES[i % PALETTES.length], seed: 1000 + i * 7919 + w, kind });

// ------------------------------------------------------------------------------------------------------------------
// PDF tối giản (chữ Helvetica, bỏ dấu tiếng Việt vì phông chuẩn không có) + phụ đề WebVTT
// ------------------------------------------------------------------------------------------------------------------
function makePdf(title, lines) {
  const esc = (s) => fold(s).replace(/[^\x20-\x7e]/g, '?').replace(/([\\()])/g, '\\$1');
  const content = ['BT', '/F1 20 Tf', '56 780 Td', `(${esc(title).slice(0, 70)}) Tj`, '/F1 12 Tf', '0 -34 Td', '16 TL'];
  for (const line of lines.slice(0, 40)) content.push(`(${esc(line).slice(0, 95)}) Tj T*`);
  content.push('ET');
  const stream = content.join('\n');
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  let pdf = '%PDF-1.4\n';
  const offsets = [];
  objects.forEach((o, i) => { offsets.push(Buffer.byteLength(pdf)); pdf += `${i + 1} 0 obj\n${o}\nendobj\n`; });
  const xref = Buffer.byteLength(pdf);
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n${offsets.map((o) => `${String(o).padStart(10, '0')} 00000 n \n`).join('')}`;
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(pdf, 'latin1');
}
function makeVtt(sentences, secondsEach = 4) {
  const stamp = (s) => `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}.000`;
  return `WEBVTT\n\n${sentences.map((text, i) => `${stamp(i * secondsEach)} --> ${stamp((i + 1) * secondsEach - 1)}\n${text}\n`).join('\n')}`;
}

// ------------------------------------------------------------------------------------------------------------------
// Thời gian (múi giờ Việt Nam UTC+7)
// ------------------------------------------------------------------------------------------------------------------
/** ISO của "N ngày kể từ hôm nay, lúc HH:MM giờ Việt Nam". */
function vnTime(dayOffset, hour, minute = 0) {
  const vnNow = new Date(Date.now() + 7 * 3600e3);
  return new Date(Date.UTC(vnNow.getUTCFullYear(), vnNow.getUTCMonth(), vnNow.getUTCDate() + dayOffset, hour - 7, minute)).toISOString();
}

// ------------------------------------------------------------------------------------------------------------------
// Dữ liệu người dùng
// ------------------------------------------------------------------------------------------------------------------
const STUDENTS = [
  ['01', 'Nguyễn Minh Anh', 'PUBLIC', 'Lớp 9A1 THCS Nguyễn Tất Thành. Mục tiêu: chuyên Toán Hà Nội - Amsterdam. Thích hình học và các bài bất đẳng thức "lắt léo".'],
  ['02', 'Trần Quốc Bảo', 'PUBLIC', 'Học sinh lớp 9, thích giải đề lúc 5 giờ sáng. Đang luyện phần số học.'],
  ['03', 'Lê Thị Cẩm Tú', 'CLASS', 'Mỗi ngày làm 1 bài hình khó rồi viết lại lời giải vào sổ tay.'],
  ['04', 'Phạm Gia Huy', 'PUBLIC', 'Thi vào chuyên Toán Chu Văn An. Điểm yếu: bất đẳng thức, đang cải thiện từng tuần.'],
  ['05', 'Hoàng Thu Hà', 'CLASS', 'Thích chia sẻ mẹo trình bày lời giải cho gọn và đủ ý.'],
  ['06', 'Vũ Đức Anh', 'PUBLIC', 'Cựu học sinh giỏi cấp quận. Đang ôn tổ hợp - xác suất cơ bản.'],
  ['07', 'Đặng Khánh Linh', 'PRIVATE', ''],
  ['08', 'Bùi Tuấn Kiệt', 'CLASS', 'Chăm chỉ nhưng hay sai vặt vì tính nhầm. Đang tập thói quen kiểm tra lại từng bước.'],
  ['09', 'Ngô Thanh Mai', 'PUBLIC', 'Học online cùng bạn bè, lập nhóm giải đề mỗi tối thứ Bảy.'],
  ['10', 'Dương Hải Đăng', 'CLASS', 'Mới tham gia lớp từ tháng này, đang làm quen với đề chuyên.'],
  ['11', 'Đỗ Phương Thảo', 'PUBLIC', 'Thích các bài toán thực tế và đố vui logic.'],
  ['12', 'Lý Gia Bảo', 'PRIVATE', ''],
  ['13', 'Trịnh Ngọc Diệp', 'CLASS', 'Đang ôn song song Toán và Tiếng Anh cho kỳ thi vào 10.'],
  ['14', 'Phan Việt Hoàng', 'CLASS', 'Tạm dừng học vì chuyển sang ôn khối khác.'],
  ['15', 'Mai Anh Tuấn', 'PRIVATE', ''],
].map(([n, fullName, visibility, bio]) => ({ key: `h${n}`, email: `demo.hocvien${n}@example.com`, fullName, visibility, bio }));

const sessions = {};
async function ensureStudent(spec) {
  const session = new Session(spec.key, spec.email, spec.fullName);
  try {
    const data = await request('POST', '/auth/register', { body: { email: spec.email, password: PASSWORD, fullName: spec.fullName } });
    session.token = data.token; session.id = data.userId;
  } catch (error) {
    if (!(error instanceof ApiError) || error.status === 429 || error.status >= 500) throw error;
    await session.login();
  }
  await session.put('/users/profile', { fullName: spec.fullName, bio: spec.bio, profileVisibility: spec.visibility });
  return session;
}

// ------------------------------------------------------------------------------------------------------------------
// Tải tệp qua luồng media (upload-intent -> PUT -> complete)
// ------------------------------------------------------------------------------------------------------------------
async function upload(session, classId, { filename, mimeType, bytes, purpose, scopeCourseId }) {
  const intent = await session.post(`/classes/${classId}/media/upload-intents`, { filename, mimeType, sizeBytes: bytes.length, purpose, scopeCourseId });
  let put;
  for (let attempt = 0; attempt < 3; attempt++) {
    put = await fetch(intent.uploadUrl, { method: intent.method || 'PUT', headers: { 'Content-Type': mimeType }, body: bytes });
    if (put.ok) break;
    await sleep(1000);
  }
  if (!put.ok) throw new Error(`PUT tới kho tệp thất bại (${put.status}) cho ${filename}`);
  await session.post(`/media/${intent.assetId}/complete`);
  return intent.assetId;
}

// ------------------------------------------------------------------------------------------------------------------
// NỘI DUNG
// ------------------------------------------------------------------------------------------------------------------
const CLASS_DESCRIPTION =
  'Lớp luyện thi Toán vào lớp 10 chuyên dành cho học sinh lớp 9: lộ trình 6 tháng bám sát cấu trúc đề của các trường chuyên ' +
  '(Hà Nội - Amsterdam, Chu Văn An, Trần Đại Nghĩa, Lê Hồng Phong...). Mỗi tuần có bài giảng video, phiếu bài tập, một đề kiểm ' +
  'tra có chấm điểm, buổi livestream chữa đề và hỏi đáp trực tiếp cùng thầy cô. Tham gia hoàn toàn miễn phí; gói PRO mở thêm đề ' +
  'chuyên sâu và lời giải chi tiết.';

const ABOUT = {
  contentMarkdown:
    '## Chào mừng đến với lớp Luyện thi Toán vào 10 chuyên\n\n' +
    'Đây là không gian học tập chung của gần 20 bạn học sinh lớp 9 cùng ước mơ bước vào các trường chuyên. ' +
    'Chúng mình học **chắc nền tảng trước, luyện đề sau**, và luôn giải thích *vì sao* chứ không chỉ *làm thế nào*.\n\n' +
    '> "Toán không phải môn để học thuộc, mà là môn để hiểu và thấy đẹp." — Thầy Nguyễn Hoàng, chủ nhiệm lớp\n\n' +
    '### Bạn sẽ nhận được gì?\n\n- Lộ trình 6 tháng chia theo chuyên đề, mỗi chuyên đề có video, tài liệu và bài tập nộp chấm.\n' +
    '- Đề kiểm tra định kỳ có xếp hạng, tích điểm và lên bậc (Tân thủ → Kim cương).\n' +
    '- Buổi livestream chữa đề mỗi tuần, hỏi đáp trực tiếp với thầy cô và trợ giảng.\n' +
    '- Cộng đồng bạn học thân thiện: thảo luận bài khó, chia sẻ kinh nghiệm thi.\n',
  rulesMarkdown:
    '## Nội quy lớp học\n\n1. **Tôn trọng** thầy cô và bạn học; không công kích cá nhân dưới bất kỳ hình thức nào.\n' +
    '2. **Không đăng đáp án** của đề đang mở thi lên bảng tin trước khi đề đóng.\n' +
    '3. Làm bài kiểm tra **trung thực**: không nhờ người khác làm hộ, không dùng công cụ giải tự động khi thi.\n' +
    '4. Đặt câu hỏi rõ ràng: ghi đề bài, em đã thử gì và vướng ở bước nào.\n' +
    '5. Không quảng cáo, không chia sẻ liên kết ngoài lớp khi chưa được trợ giảng cho phép.\n\n' +
    '> Vi phạm nhiều lần có thể bị gỡ khỏi lớp hoặc chặn tham gia.\n',
  sections: [
    ['Giới thiệu lớp', 'Lớp Luyện thi Toán vào 10 chuyên ra đời từ nhu cầu có một nơi luyện đề bài bản, có người chấm và chữa kỹ. Năm nay lớp có hơn 15 học viên đang hoạt động, hơn 30 bài giảng và 5 đề kiểm tra cùng hàng chục tài liệu tham khảo.\n\nChúng mình tin rằng một học sinh chăm chỉ, có phương pháp đúng và được góp ý kịp thời hoàn toàn có thể chạm tới cánh cửa trường chuyên.', 'Bìa giới thiệu lớp Toán chuyên'],
    ['Lộ trình 6 tháng', 'Tháng 1-2: Nền tảng Đại số. Phương trình, hệ phương trình, hàm số, biểu thức chứa căn.\n\nTháng 3: Hình học phẳng. Tam giác đồng dạng, đường tròn, tứ giác nội tiếp.\n\nTháng 4: Số học & Tổ hợp. Chia hết, đồng dư cơ bản, đếm và nguyên lý Dirichlet.\n\nTháng 5: Bất đẳng thức & Cực trị. AM-GM, Cauchy-Schwarz và kỹ thuật tách ghép.\n\nTháng 6: Tổng ôn & thi thử theo cấu trúc đề thật, chấm và chữa chi tiết.', 'Sơ đồ lộ trình ôn thi 6 tháng'],
    ['Đội ngũ giảng dạy', 'Thầy Nguyễn Hoàng — giáo viên Toán 12 năm kinh nghiệm bồi dưỡng học sinh giỏi, chủ nhiệm lớp.\n\nCô Lê Thu Trang (trợ giảng) — cựu học sinh chuyên Toán, phụ trách chấm bài tự luận, trả lời câu hỏi trong bài học và sinh hoạt thành viên.', 'Đội ngũ thầy cô'],
    ['Cách học hiệu quả', 'Mỗi tuần hãy theo nhịp: xem bài giảng (2 buổi) → làm phiếu bài tập → nộp bài tập chấm → làm đề kiểm tra → tham gia livestream chữa đề.\n\nSau mỗi đề, hãy viết lại những câu sai vào "sổ lỗi sai" và tự giải lại sau 3 ngày. Cách này giúp các bạn khóa trước tăng trung bình 1,5 điểm.', 'Nhịp học mỗi tuần'],
    ['Hệ thống xếp hạng & thưởng', 'Mỗi đề kiểm tra có luật thưởng điểm riêng: điểm càng cao, điểm thưởng càng lớn. Tổng điểm thưởng quyết định bậc của bạn trên bảng xếp hạng: Tân thủ → Đồng → Bạc → Vàng → Kim cương.\n\nChỉ kết quả tốt nhất của mỗi đề được tính, nên bạn có thể thi lại để cải thiện.', 'Bảng xếp hạng và các bậc'],
  ],
};

const COURSE_PDF_LINES = ['Phieu bai tap Phuong trinh quy ve bac hai', '', 'Bai 1. Giai phuong trinh: x^4 - 5x^2 + 4 = 0.', 'Bai 2. Giai phuong trinh: (x^2 + x)^2 - 2(x^2 + x) - 3 = 0.',
  'Bai 3. Tim m de phuong trinh x^2 - 2(m+1)x + m^2 + 2 = 0 co hai nghiem phan biet.', 'Bai 4. Cho phuong trinh x^2 - 7x + 10 = 0 co hai nghiem x1, x2.', '        Khong giai phuong trinh, tinh x1^2 + x2^2 va 1/x1 + 1/x2.', '', 'Goi y: dat an phu, dung dinh ly Viete.'];

const BLOGS = [
  { title: 'Thông báo nghỉ lễ và lịch học bù', category: 'Thông báo', audience: 'MEMBERS', cover: null, author: 'owner',
    excerpt: 'Lớp nghỉ một buổi livestream trong tuần lễ; lịch học bù và cách nhận tài liệu thay thế.',
    body: '## Lịch nghỉ\n\nNhân dịp nghỉ lễ, **buổi livestream thứ Bảy tuần này sẽ được dời sang 20:00 Chủ nhật**. Các bài giảng video và bài tập vẫn mở bình thường.\n\n## Học bù thế nào?\n\n- Xem lại bản ghi buổi livestream trước (đăng ở mục Sự kiện sau khi kết thúc).\n- Làm phiếu bài tập trong khóa *Nền tảng Đại số*.\n- Gửi câu hỏi dưới mỗi bài học — trợ giảng sẽ trả lời trong 24 giờ.\n\nChúc các bạn một kỳ nghỉ thật vui và nạp đủ năng lượng cho tuần học sau!' },
  { title: 'Lộ trình ôn thi Toán vào lớp 10 chuyên trong 6 tháng', category: 'Phương pháp học', audience: 'PUBLIC', cover: 0, author: 'owner',
    excerpt: 'Chia 6 tháng thành 5 giai đoạn rõ ràng: nền tảng, hình học, số học - tổ hợp, bất đẳng thức và tổng ôn. Kèm lịch học mẫu mỗi tuần.',
    body: '## Vì sao cần một lộ trình?\n\nPhần lớn học sinh lớp 9 bắt đầu ôn chuyên bằng cách *làm thật nhiều đề*. Cách này nghe có vẻ chăm chỉ nhưng thường dẫn đến **học dàn trải, quên nhanh và hổng kiến thức nền**. Một lộ trình tốt giúp bạn biết hôm nay cần học gì, đã học đến đâu và còn thiếu gì.\n\n## 5 giai đoạn trong 6 tháng\n\n1. **Tháng 1-2 - Nền tảng Đại số:** phương trình bậc hai, hệ phương trình, hàm số bậc nhất, biểu thức chứa căn.\n2. **Tháng 3 - Hình học phẳng:** tam giác đồng dạng, hệ thức lượng, đường tròn, tứ giác nội tiếp.\n3. **Tháng 4 - Số học & Tổ hợp:** chia hết, số chính phương, nguyên lý Dirichlet.\n4. **Tháng 5 - Bất đẳng thức & Cực trị:** AM-GM, Cauchy-Schwarz, kỹ thuật tách ghép.\n5. **Tháng 6 - Tổng ôn & thi thử:** làm đề đủ thời gian, chấm kỹ và chữa lại.\n\n## Lịch học mẫu mỗi tuần\n\n| Ngày | Nội dung | Thời lượng |\n|---|---|---|\n| Thứ 2, 4 | Xem bài giảng + ghi chép | 90 phút |\n| Thứ 3, 5 | Làm phiếu bài tập | 60 phút |\n| Thứ 6 | Nộp bài tập chấm | 45 phút |\n| Thứ 7 | Đề kiểm tra tuần | 90 phút |\n| Chủ nhật | Livestream chữa đề + sổ lỗi sai | 120 phút |\n\n> **Mẹo nhỏ:** hãy dành 15 phút cuối mỗi buổi học để *viết lại bằng lời của mình* điều vừa học. Nếu bạn giải thích được cho một người khác, bạn đã hiểu thật sự.\n\n## Bắt đầu từ đâu?\n\nHãy làm bài [khảo sát đầu vào](/classes/lop-demo-day-du/exams) để biết mình đang ở đâu, sau đó xem khóa [Nền tảng Đại số](/classes/lop-demo-day-du/learn). Chúc các bạn một hành trình vừa vững vàng vừa vui!' },
  { title: '5 sai lầm thường gặp khi giải bài hình học phẳng', category: 'Phương pháp học', audience: 'PUBLIC', cover: 1, author: 'owner',
    excerpt: 'Vẽ hình thiếu chính xác, quên giả thiết, lạm dụng "hiển nhiên"... Điểm lại 5 sai lầm khiến bạn mất điểm oan ở câu hình.',
    body: '## Câu hình là câu dễ lấy điểm nhất — và cũng dễ mất điểm oan nhất\n\nSau khi chấm hơn 300 bài thi thử, thầy cô nhận thấy học sinh mất điểm ở phần hình học chủ yếu vì **trình bày**, chứ không phải vì không biết làm. Dưới đây là 5 sai lầm hay gặp.\n\n### 1. Vẽ hình thiếu chính xác\nHình vẽ lệch khiến bạn "nhìn ra" những tính chất không có thật. Hãy dùng compa, thước và luôn vẽ **hình tổng quát**, tránh trường hợp đặc biệt (tam giác cân, đều) nếu đề không yêu cầu.\n\n### 2. Quên khai thác hết giả thiết\nMỗi dữ kiện của đề đều có lý do. Hãy gạch chân và tự hỏi: *"Dữ kiện này cho mình biết điều gì?"*\n\n### 3. Dùng điều phải chứng minh để chứng minh\nVí dụ: chứng minh tứ giác nội tiếp **bằng cách** giả sử nó đã nội tiếp. Đây là lỗi lập luận vòng tròn bị trừ gần hết điểm.\n\n### 4. Viết "hiển nhiên" thay cho lập luận\n- Không viết: *"Dễ thấy AH ⟂ BC"*.\n- Hãy viết: *"Vì tam giác ABC cân tại A có AM là trung tuyến nên AM cũng là đường cao, suy ra AM ⟂ BC."*\n\n### 5. Không kiểm tra lại các trường hợp\nKhi hình có nhiều vị trí (điểm nằm trong, ngoài đường tròn), hãy xét đủ các trường hợp hoặc nói rõ giả thiết bố trí.\n\n## Checklist 30 giây trước khi nộp bài hình\n- [ ] Hình vẽ rõ, ký hiệu đủ\n- [ ] Mỗi bước có căn cứ (định lý, giả thiết)\n- [ ] Kết luận khớp yêu cầu đề' },
  { title: 'Quản lý thời gian trong phòng thi 150 phút', category: 'Kinh nghiệm thi', audience: 'MEMBERS', cover: 2, author: 'staff',
    excerpt: 'Chia 150 phút thế nào để không "cháy" ở câu cuối? Bảng phân bổ thời gian và 4 nguyên tắc đã giúp học viên khóa trước làm đủ bài.',
    body: '## Bài toán thời gian\n\nĐề chuyên Toán thường có **5-6 câu tự luận trong 150 phút**. Nhiều bạn dành quá nhiều thời gian cho câu đầu dễ rồi hốt hoảng ở câu cuối. Dưới đây là cách chị (trợ giảng) đã dùng khi thi vào chuyên.\n\n## Bảng phân bổ thời gian gợi ý\n\n| Phần | Thời gian | Ghi chú |\n|---|---|---|\n| Đọc đề, đánh dấu câu dễ | 5-7 phút | Chưa viết gì |\n| Câu 1-2 (Đại số cơ bản) | 35 phút | Không sai vặt |\n| Câu 3 (Phương trình/Hệ) | 25 phút | Kiểm tra nghiệm |\n| Câu 4 (Hình học) | 35 phút | Vẽ hình chuẩn |\n| Câu 5 (Bất đẳng thức) | 30 phút | Thử 2 hướng rồi chuyển |\n| Rà soát | 10-15 phút | Dấu, đơn vị, kết luận |\n\n## 4 nguyên tắc vàng\n\n1. **Dễ trước, khó sau** nhưng *ghi dấu* câu bỏ dở để quay lại.\n2. **Quy tắc 10 phút:** nếu 10 phút không có hướng mới, chuyển câu khác.\n3. **Viết sạch ngay từ đầu** — tẩy xóa tốn thời gian hơn bạn nghĩ.\n4. **Luôn chừa 10 phút cuối** để rà soát tính toán và kết luận.\n\n> *"Điểm 8 bằng chắc 6 câu làm đúng và đủ, tốt hơn 7 câu làm dở."*\n\nHãy luyện cách chia thời gian này ở các [đề kiểm tra](/classes/lop-demo-day-du/exams) của lớp, bật đồng hồ như thi thật.' },
  { title: 'Thông báo: Lịch khai giảng và kiểm tra định kỳ tháng 10', category: 'Thông báo', audience: 'PUBLIC', cover: null, author: 'owner',
    excerpt: 'Lịch học, lịch livestream và các mốc kiểm tra trong tháng 10 - các bạn lưu lại để không bỏ lỡ nhé.',
    body: '## Lịch tháng 10\n\nChào các bạn, dưới đây là các mốc quan trọng trong tháng 10:\n\n- **Thứ Bảy hàng tuần, 20:00:** livestream chữa đề trên nền tảng của lớp.\n- **Đề kiểm tra giữa kỳ - Đại số:** mở đến hết tháng, mỗi bạn làm tối đa **2 lượt**.\n- **Đề thi thử số 1 (có tự luận):** nộp trước 23:59 Chủ nhật; trợ giảng sẽ chấm trong vòng 3 ngày.\n- **Đề thi thử lần 2:** mở vào cuối tuần sau, thời gian làm bài 120 phút.\n\n## Lưu ý\n\n1. Mỗi bài tự luận chỉ được chấm sau khi nộp, hãy kiểm tra lại kỹ trước khi bấm nộp.\n2. Học viên PRO có thêm **đề chuyên sâu Hình học nâng cao** và lời giải chi tiết.\n3. Có thắc mắc cứ hỏi ở mục Thảo luận hoặc dưới từng bài giảng.\n\nChúc cả lớp một tháng học tập thật hiệu quả!' },
  { title: 'Chuyên đề bất đẳng thức: từ AM-GM đến Cauchy-Schwarz', category: 'Phương pháp học', audience: 'MEMBERS', cover: 3, author: 'owner',
    excerpt: 'Hệ thống hóa các bất đẳng thức cơ bản, điều kiện xảy ra dấu bằng và 4 kỹ thuật tách ghép hay dùng nhất.',
    body: '## 1. Hai bất đẳng thức "xương sống"\n\n**Bất đẳng thức AM-GM (Cô-si):** với mọi a, b ≥ 0 ta có\n\n`a + b ≥ 2√(ab)`, dấu "=" xảy ra khi và chỉ khi **a = b**.\n\n**Bất đẳng thức Cauchy-Schwarz (dạng Bunyakovsky):**\n\n`(a² + b²)(x² + y²) ≥ (ax + by)²`, dấu "=" xảy ra khi `a/x = b/y`.\n\n## 2. Bốn kỹ thuật tách ghép hay dùng\n\n1. **Thêm bớt hằng số** để dấu "=" xảy ra đúng tại điểm mong muốn.\n2. **Ghép cặp đối xứng** — ví dụ `a/b + b/a ≥ 2`.\n3. **Đặt ẩn phụ** để đưa về biểu thức quen thuộc.\n4. **Dùng điều kiện ràng buộc** (a + b + c = 3, abc = 1) để thay thế biến.\n\n## 3. Ví dụ mẫu\n\n**Đề bài.** Cho a, b, c > 0. Chứng minh `a/b + b/c + c/a ≥ 3`.\n\n**Lời giải.** Áp dụng AM-GM cho ba số dương:\n\n`a/b + b/c + c/a ≥ 3·³√((a/b)(b/c)(c/a)) = 3`.\n\nDấu "=" xảy ra khi `a/b = b/c = c/a`, tức a = b = c. ∎\n\n## 4. Sai lầm cần tránh\n\n- Quên **kiểm tra điều kiện** các biến không âm.\n- Quên **chỉ ra khi nào dấu bằng xảy ra**.\n- Áp dụng AM-GM cho số âm.\n\nBài tập áp dụng nằm trong khóa [Nền tảng Đại số](/classes/lop-demo-day-du/learn), Chương 2.' },
  { title: 'Tổng hợp kinh nghiệm thi của các anh chị khóa trước', category: 'Kinh nghiệm thi', audience: 'PUBLIC', cover: 4, author: 'staff',
    excerpt: 'Ba anh chị đã đỗ chuyên Toán chia sẻ cách ôn, cách giữ tâm lý và những điều họ ước mình biết sớm hơn.',
    body: '## "Mình ước biết sớm hơn..."\n\nChúng mình đã hỏi ba anh chị khóa trước (đều đỗ chuyên Toán) về điều họ rút ra được. Các câu trả lời khá giống nhau:\n\n- **"Học nền tảng thật chắc."** Câu 1-2 trong đề chiếm gần 40% điểm và ai cũng làm được — đừng sai ở đó.\n- **"Giải đề bằng bút, trên giấy, bấm giờ."** Làm trên máy không tạo cảm giác áp lực như phòng thi thật.\n- **"Lập sổ lỗi sai."** Cuối kỳ, cuốn sổ này là tài liệu ôn quý nhất.\n- **"Ngủ đủ."** Hai tuần cuối đừng thức khuya; đầu óc tỉnh táo giúp bạn khỏi sai vặt.\n\n## Lịch ôn nước rút (4 tuần cuối)\n\n1. **Tuần 1:** làm lại toàn bộ đề đã sai, mỗi ngày 1 đề đủ thời gian.\n2. **Tuần 2:** tập trung vào chuyên đề yếu nhất.\n3. **Tuần 3:** thi thử 2 đề/tuần, chấm nghiêm khắc.\n4. **Tuần 4:** ôn công thức, giữ nhịp sinh hoạt, không học thêm kiến thức mới.\n\n> **Lời nhắn:** "Đỗ hay không đỗ không quyết định giá trị của bạn, nhưng quá trình nỗ lực chắc chắn làm bạn lớn lên." — một anh khóa trước.\n\nBạn có kinh nghiệm riêng? Chia sẻ trong mục [Thảo luận](/classes/lop-demo-day-du/feed) để cả lớp cùng học nhé.' },
  { title: 'Dự thảo: Quy chế chấm điểm thi thử học kỳ 2', category: 'Thông báo', audience: 'MEMBERS', cover: null, author: 'owner', draft: true,
    excerpt: 'Bản nháp quy chế chấm điểm các đề thi thử học kỳ 2 (chưa công bố).',
    body: '## Dự thảo quy chế chấm điểm\n\n*Bản nháp - chưa công bố.*\n\n1. Mỗi bài tự luận được chấm bởi **hai người**; điểm cuối là trung bình cộng.\n2. Điểm thưởng được tính theo kết quả **tốt nhất** của mỗi đề.\n3. Học viên có quyền yêu cầu phúc khảo trong vòng 48 giờ.\n\n> Cần thống nhất với trợ giảng trước khi đăng.' },
];

const FEED = [
  { key: 'welcome', author: 'owner', visibility: 'FREE', pinned: true, title: 'Chào mừng bạn đến với lớp! Đọc trước khi bắt đầu',
    body: 'Chào cả nhà!\n\nChào mừng các bạn đến với lớp **Luyện thi Toán vào 10 chuyên**. Để học hiệu quả, các bạn nhớ:\n\n1. Đọc **Nội quy** ở tab *Giới thiệu*.\n2. Làm **đề khảo sát đầu vào** để biết điểm xuất phát.\n3. Theo lộ trình trong khóa *Nền tảng Đại số*.\n4. Đặt câu hỏi dưới từng bài học, trợ giảng sẽ trả lời trong 24 giờ.\n\nBạn nào mới vào hãy **giới thiệu bản thân** ở phần bình luận bên dưới nhé: trường, mục tiêu và chuyên đề bạn thích nhất.',
    comments: [['h01', 'Em chào thầy cô và cả lớp ạ! Em học lớp 9A1 THCS Nguyễn Tất Thành, mục tiêu chuyên Toán Hà Nội - Amsterdam. Em thích hình học nhất.'], ['h02', 'Chào mọi người, mình là Bảo. Mình đang yếu phần bất đẳng thức, mong được mọi người giúp đỡ!'], ['staff', 'Chào các em! Cô là Trang, trợ giảng của lớp. Có gì thắc mắc cứ hỏi dưới bài giảng nhé.'], ['h03', 'Mình là Tú, mỗi ngày mình làm 1 bài hình khó. Mình muốn lập nhóm học cùng.'], ['h05', 'Chào cả lớp! Mình thích chia sẻ mẹo trình bày lời giải. Rất vui được làm quen.'], ['h06', 'Mình là Đức Anh, đang ôn thêm tổ hợp. Có bạn nào cùng ôn không?'], ['owner', 'Rất vui vì thấy lớp sôi nổi như vậy. Các bạn nhớ làm đề khảo sát đầu vào trong tuần này nhé!'], ['h08', 'Dạ em chào thầy cô và các bạn. Em hay sai vặt khi tính toán, mong thầy cô góp ý.'], ['h09', 'Chào cả lớp, mình là Mai. Tối thứ Bảy nhóm mình giải đề online, bạn nào muốn tham gia thì nhắn nhé!'], ['h10', 'Em mới vào lớp, mong mọi người giúp đỡ ạ.'], ['h14', 'Chào cả lớp, mình xin phép tham gia học thử ạ.']] },
  { key: 'public-schedule', author: 'owner', visibility: 'PUBLIC', title: 'Lịch livestream chữa đề trong tháng này',
    body: 'Thông báo công khai: **livestream chữa đề mỗi thứ Bảy lúc 20:00**. Bạn chưa là thành viên vẫn có thể xem thông tin tại tab *Sự kiện* và đăng ký tham dự các buổi dành cho mọi người.\n\nĐể tham gia đầy đủ bài giảng, đề thi và bảng xếp hạng, hãy bấm **Tham gia lớp** (miễn phí).',
    comments: [['h04', 'Em đã đăng ký buổi livestream tối thứ Bảy rồi ạ!'], ['h11', 'Buổi chữa đề có ghi hình không ạ? Em có thể bận một vài buổi.'], ['owner', 'Có em nhé, bản ghi sẽ đăng ở mục Sự kiện sau khi kết thúc.']] },
  { key: 'tips-staff', author: 'staff', visibility: 'FREE', title: 'Mẹo đọc đề: gạch chân 3 thứ trước khi làm',
    body: 'Mẹo nhỏ cô hay nhắc các bạn trước khi bắt tay vào làm một bài toán:\n\n- **Gạch chân giả thiết** (cho gì?).\n- **Khoanh tròn yêu cầu** (cần tìm/chứng minh gì?).\n- **Ghi chú điều kiện ẩn** (biến dương? nguyên? khác 0?).\n\nChỉ 30 giây thôi nhưng giúp tránh được rất nhiều lỗi mất điểm oan. Các bạn thử áp dụng ở đề kiểm tra tuần này nhé!',
    comments: [['h01', 'Cảm ơn cô! Em hay quên điều kiện của biến nên mất điểm oan nhiều lần rồi ạ.'], ['h06', 'Mẹo hay quá, mình sẽ áp dụng luôn cho đề giữa kỳ.']] },
  { key: 'pro-solution', author: 'owner', visibility: 'PRO', title: '[PRO] Lời giải chi tiết đề Hình học nâng cao và hướng mở rộng',
    body: 'Dành riêng cho học viên **PRO**: phân tích lời giải câu hình khó nhất của đề *Hình học nâng cao*.\n\n**Ý tưởng chính:** nhận ra tứ giác nội tiếp ẩn trong hình, từ đó chuyển bài toán về hai tam giác đồng dạng.\n\n1. Chứng minh `∠ABD = ∠ACD` nên A, B, C, D cùng thuộc một đường tròn.\n2. Suy ra `AB·CD = AC·BD` (hệ thức Ptolemy).\n3. Dùng hệ thức này để tính tỉ số cần tìm.\n\nCuối bài có hai hướng mở rộng cho bạn nào còn dư sức. Bạn thử trước rồi mới xem gợi ý nhé!',
    comments: [['pro', 'Lời giải rất gọn ạ. Em chưa nghĩ ra việc dùng Ptolemy ở bước 2.'], ['h01', 'Em thấy hướng mở rộng thứ hai khá hay, cảm ơn thầy!']] },
  { key: 'product-owner', author: 'owner', visibility: 'PRODUCT_OWNER', product: 'docpack', title: 'Quà cho người sở hữu "Bộ 50 đề thi thử": lời giải mẫu 3 đề đầu',
    body: 'Cảm ơn các bạn đã mua **Bộ 50 đề thi thử chuyên Toán**. Bài đăng này chỉ hiển thị với người sở hữu gói.\n\nThầy đính kèm lời giải mẫu của 3 đề đầu để các bạn biết cách trình bày chuẩn barem. Mỗi tuần thầy sẽ đăng thêm lời giải 2 đề tiếp theo.',
    comments: [['h08', 'Em cảm ơn thầy ạ! Tài liệu rất hữu ích.']] },
  { key: 'segment', author: 'owner', visibility: 'SEGMENT', segment: 'diligent', title: 'Dành cho học viên chăm chỉ: thử thách cuối tuần',
    body: 'Chào các bạn đã hoàn thành từ 5 bài học trở lên! Thử thách tuần này: **chứng minh bất đẳng thức Nesbitt** `a/(b+c) + b/(c+a) + c/(a+b) ≥ 3/2` bằng ít nhất hai cách khác nhau. Bạn nào làm được hãy đăng lời giải ở phần bình luận.',
    comments: [['h01', 'Cách 1: quy đồng và dùng AM-GM; cách 2: Cauchy-Schwarz dạng Engel. Em sẽ đăng bản đầy đủ tối nay.']] },
  { key: 'q-geometry', author: 'h03', visibility: 'FREE', title: 'Nhờ mọi người gợi ý bài hình về đường tròn nội tiếp',
    body: 'Cho tam giác ABC có đường tròn nội tiếp (I) tiếp xúc BC, CA, AB lần lượt tại D, E, F. Chứng minh rằng AD, BE, CF đồng quy.\n\nMình thử dùng định lý Ceva nhưng chưa biết lập tỉ số các đoạn tiếp tuyến thế nào. Mọi người gợi ý giúp mình với!',
    comments: [['h01', 'Gợi ý: AE = AF, BD = BF, CD = CE (tính chất hai tiếp tuyến cắt nhau). Thay vào tích tỉ số của Ceva sẽ ra 1.'], ['h05', 'Đúng rồi, đặt AE = AF = x, BF = BD = y, CD = CE = z cho dễ nhìn nhé.'], ['h03', 'À mình hiểu rồi, tích (AF/FB)(BD/DC)(CE/EA) = (x/y)(y/z)(z/x) = 1. Cảm ơn các bạn!'], ['staff', 'Làm tốt lắm Tú! Với bài này, em có thể tổng quát cho điểm Gergonne luôn nhé.'], ['h04', 'Bạn nào cần nhóm học hình thì ghép chung với nhóm mình tối thứ Ba nhé.'], ['h08', 'Mình cũng đang vướng bài tương tự, cảm ơn bạn Tú đã hỏi!'], ['h09', 'Mình thêm ý: điểm đồng quy gọi là điểm Gergonne của tam giác.'], ['h13', 'Cảm ơn mọi người, mình vừa học được thêm một cách chứng minh đẹp.'], ['owner', 'Cả lớp thảo luận rất tốt. Thầy sẽ chữa bài này trong livestream thứ Bảy.']] },
  { key: 'share-cheatsheet', author: 'h05', visibility: 'FREE', title: 'Chia sẻ sổ tay "lỗi sai thường gặp" của mình',
    body: 'Mình tổng hợp 10 lỗi sai hay gặp nhất của mình sau 4 tuần học:\n\n1. Quên điều kiện xác định khi giải phương trình chứa căn.\n2. Chia hai vế cho biểu thức có thể bằng 0.\n3. Nhầm dấu khi chuyển vế.\n4. Quên kiểm tra nghiệm sau khi bình phương hai vế.\n5. Viết thiếu kết luận cuối bài.\n\nHy vọng giúp ích cho các bạn! Ai có thêm lỗi nào thì bổ sung dưới bình luận nhé.',
    comments: [['h06', 'Lỗi số 4 mình bị hoài luôn, cảm ơn bạn!'], ['h10', 'Hay quá, mình sẽ làm theo cách này.'], ['staff', 'Cô rất thích cách em tự tổng hợp. Mọi người nhớ làm sổ lỗi sai nhé!'], ['h01', 'Mình bổ sung: quên xét trường hợp a = 0 khi giải phương trình bậc hai tham số.'], ['h02', 'Mình thêm lỗi: tính nhầm biệt thức Δ khi hệ số b chẵn.']] },
  { key: 'study-group', author: 'h09', visibility: 'FREE', title: 'Lập nhóm giải đề online tối thứ Bảy 19:00',
    body: 'Nhóm mình giải đề online mỗi tối thứ Bảy lúc 19:00 (trước giờ livestream). Mỗi người làm một đề riêng rồi cùng đối chiếu và hỏi nhau. Bạn nào muốn tham gia thì bình luận để mình thêm vào nhóm nhé!',
    comments: [['h04', 'Cho mình vào nhóm với!'], ['h11', 'Mình cũng tham gia nhé.'], ['h13', 'Mình đăng ký luôn ạ.']] },
  { key: 'exam-result', author: 'h10', visibility: 'FREE', title: 'Mình vừa làm đề giữa kỳ, xin lời khuyên cải thiện',
    body: 'Lần đầu mình được 41,67 điểm, lần hai lên 66,67 điểm nhưng vẫn muốn cao hơn. Theo mọi người nên ôn lại phần nào trước: phương trình bậc hai hay bất đẳng thức?',
    comments: [['staff', 'Em tiến bộ rất nhanh! Cô nghĩ em nên củng cố phương trình bậc hai và hệ thức Viète trước vì chúng xuất hiện ở hầu hết các câu đầu của đề thi.'], ['h01', 'Mình cũng thấy vậy, làm tốt câu 1-2 là đã chắc 4 điểm rồi.']] },
];

const SEGMENTS = [
  { name: 'Học viên PRO', description: 'Các bạn đã sở hữu ít nhất một gói trả phí còn hiệu lực.', logicOperator: 'AND', rules: [{ criterion: 'IS_PRO', operator: 'EQUALS', value: 'true' }] },
  { key: 'diligent', name: 'Học viên chăm chỉ', description: 'Hoàn thành từ 5 bài học trở lên hoặc điểm trung bình các đề từ 70.', logicOperator: 'OR', rules: [{ criterion: 'COMPLETED_LESSONS_COUNT', operator: 'GREATER_THAN_OR_EQUAL', value: '5' }, { criterion: 'AVG_EXAM_SCORE', operator: 'GREATER_THAN_OR_EQUAL', value: '70' }] },
];

// ---- Khóa học ----
const COURSE_FREE = {
  title: 'Nền tảng Đại số — Ôn thi vào 10 chuyên',
  description: 'Khóa nền tảng miễn phí cho mọi thành viên: phương trình, hệ phương trình, bất đẳng thức và cực trị. Mỗi chương có bài giảng video, tóm tắt lý thuyết, phiếu bài tập và bài tập nộp chấm.',
  sections: [
    { title: 'Chương 1: Phương trình và hệ phương trình', lessons: [
      { key: 'l11', type: 'VIDEO', title: 'Bài 1.1 — Phương trình bậc hai và định lý Viète', minutes: 18, video: true,
        transcript: 'Trong bài này, thầy nhắc lại công thức nghiệm của phương trình bậc hai và đi sâu vào định lý Viète: tổng và tích hai nghiệm. Thầy minh họa bằng ba ví dụ, từ cơ bản đến tình huống tham số và cách xét điều kiện để phương trình có hai nghiệm phân biệt.',
        cues: ['Chào các em, hôm nay chúng ta ôn phương trình bậc hai.', 'Công thức nghiệm: Δ = b² − 4ac.', 'Nếu Δ > 0, phương trình có hai nghiệm phân biệt.', 'Định lý Viète: x₁ + x₂ = −b/a và x₁·x₂ = c/a.', 'Ví dụ 1: x² − 5x + 6 = 0 có hai nghiệm 2 và 3.', 'Ví dụ 2: tìm m để phương trình có hai nghiệm phân biệt.', 'Nhớ kiểm tra hệ số a khác 0 trước khi dùng công thức.', 'Hẹn gặp lại các em ở bài tiếp theo!'] },
      { key: 'l12', type: 'TEXT', title: 'Bài 1.2 — Tóm tắt lý thuyết hệ phương trình', minutes: 10,
        content: '## Hệ phương trình bậc nhất hai ẩn\n\n**Phương pháp thế.** Từ một phương trình rút một ẩn theo ẩn kia rồi thế vào phương trình còn lại.\n\n**Phương pháp cộng đại số.** Nhân hai vế để các hệ số của một ẩn đối nhau hoặc bằng nhau rồi cộng/trừ.\n\n## Hệ đối xứng loại I\n\nĐặt S = x + y, P = xy để đưa về phương trình bậc hai với ẩn t: `t² − St + P = 0`.\n\n### Lưu ý\n- Điều kiện để tồn tại nghiệm thực: `S² − 4P ≥ 0`.\n- Luôn **thử lại nghiệm** vào hệ ban đầu.\n\n> Hệ như `x + y = 5, xy = 6` giải rất nhanh bằng cách đặt ẩn phụ.' },
      { key: 'l13', type: 'DOCUMENT', title: 'Bài 1.3 — Phiếu bài tập: phương trình quy về bậc hai', minutes: 25, pdf: true,
        content: 'Tải phiếu bài tập (PDF) và tự làm trong 25 phút, sau đó đối chiếu với gợi ý ở bài giảng.' },
      { key: 'l14', type: 'ASSIGNMENT', title: 'Bài 1.4 — Bài tập nộp: hệ phương trình có tham số', minutes: 30,
        content: '## Đề bài\n\nCho hệ phương trình `x + my = 3` và `mx − y = 2` (m là tham số).\n\n1. Giải hệ khi **m = 1**.\n2. Tìm m để hệ có nghiệm duy nhất (x; y) thỏa mãn `x² + y² = 5`.\n\n### Yêu cầu nộp\nTrình bày lời giải đầy đủ từng bước (có thể gõ công thức dạng văn bản). Trợ giảng sẽ chấm và nhận xét trong 3 ngày.' },
    ] },
    { title: 'Chương 2: Bất đẳng thức và cực trị', lessons: [
      { key: 'l21', type: 'VIDEO', title: 'Bài 2.1 — Bất đẳng thức AM-GM và điều kiện dấu bằng', minutes: 22, video: true,
        transcript: 'Bài giảng giới thiệu bất đẳng thức AM-GM cho hai và ba số không âm, cách chỉ ra điều kiện xảy ra dấu bằng và ba ví dụ chứng minh bất đẳng thức đơn giản. Cuối bài là một số lỗi thường gặp khi áp dụng.',
        cues: ['Hôm nay ta học bất đẳng thức AM-GM.', 'Với a, b không âm: a + b ≥ 2√(ab).', 'Dấu bằng xảy ra khi và chỉ khi a = b.', 'Với ba số: a + b + c ≥ 3·³√(abc).', 'Ví dụ: chứng minh a/b + b/c + c/a ≥ 3.', 'Hãy nhớ nêu rõ khi nào dấu bằng xảy ra.', 'Không áp dụng cho các số âm nhé!'] },
      { key: 'l22', type: 'TEXT', title: 'Bài 2.2 — Các kỹ thuật tách ghép thường dùng', minutes: 12,
        content: '## Bốn kỹ thuật tách ghép\n\n1. **Thêm bớt hằng số** sao cho dấu "=" xảy ra tại điểm mong muốn.\n2. **Ghép cặp đối xứng:** `a/b + b/a ≥ 2`.\n3. **Đặt ẩn phụ** đưa về dạng quen thuộc.\n4. **Dùng điều kiện ràng buộc** như `a + b + c = 3`.\n\n### Ví dụ\nCho x > 0, tìm GTNN của `A = x + 4/x`.\n\nÁp dụng AM-GM: `x + 4/x ≥ 2√4 = 4`, dấu bằng khi `x = 2`.' },
      { key: 'l23', type: 'VIDEO', title: 'Bài 2.3 — Bất đẳng thức Cauchy-Schwarz và ứng dụng', minutes: 20, video: true,
        transcript: 'Bài giảng trình bày bất đẳng thức Cauchy-Schwarz dạng thường và dạng Engel (Titu), kèm hai ví dụ tìm giá trị lớn nhất, nhỏ nhất của biểu thức có điều kiện ràng buộc.',
        cues: ['Bất đẳng thức Cauchy-Schwarz là công cụ rất mạnh.', '(a² + b²)(x² + y²) ≥ (ax + by)².', 'Dấu bằng khi a/x = b/y.', 'Dạng Engel: a²/x + b²/y ≥ (a + b)²/(x + y).', 'Ví dụ: tìm GTNN của 1/a + 1/b khi a + b = 1.'] },
      { key: 'l24', type: 'ASSIGNMENT', title: 'Bài 2.4 — Bài tập nộp: chứng minh bất đẳng thức', minutes: 30,
        content: '## Đề bài\n\nCho a, b, c là các số thực dương. Chứng minh rằng:\n\n`a/(b+c) + b/(c+a) + c/(a+b) ≥ 3/2`  (bất đẳng thức Nesbitt)\n\nGợi ý: có thể dùng Cauchy-Schwarz dạng Engel hoặc đặt `x = b + c`, ... Hãy nêu rõ **điều kiện xảy ra dấu bằng**.' },
    ] },
  ],
};
const COURSE_PAID = {
  title: 'Hình học nâng cao: đường tròn và tứ giác nội tiếp',
  description: 'Khóa trả phí chuyên sâu: tứ giác nội tiếp, hệ thức Ptolemy, bài toán quỹ tích và các câu hình "phân loại" trong đề chuyên. 3 bài học kèm bài tập nộp chấm.',
  sections: [{ title: 'Chương 1: Tứ giác nội tiếp và ứng dụng', lessons: [
    { key: 'p11', type: 'VIDEO', title: 'Bài 1 — Dấu hiệu nhận biết tứ giác nội tiếp', minutes: 25, video: true,
      transcript: 'Thầy hệ thống năm dấu hiệu nhận biết tứ giác nội tiếp, kèm ví dụ trong các đề chuyên Toán những năm gần đây.',
      cues: ['Chúng ta bắt đầu với dấu hiệu tổng hai góc đối bằng 180 độ.', 'Dấu hiệu thứ hai: hai góc cùng nhìn một cạnh dưới các góc bằng nhau.', 'Dấu hiệu thứ ba: góc ngoài bằng góc trong đối diện.', 'Hãy luôn vẽ đường tròn ngoại tiếp để kiểm tra trực quan.'] },
    { key: 'p12', type: 'TEXT', title: 'Bài 2 — Hệ thức Ptolemy và bài toán tỉ số', minutes: 15,
      content: '## Hệ thức Ptolemy\n\nTứ giác ABCD nội tiếp khi và chỉ khi `AC·BD = AB·CD + AD·BC`.\n\n### Ứng dụng\n- Tính độ dài đoạn thẳng khi biết ba cạnh.\n- Chứng minh các hệ thức giữa các đoạn thẳng trong hình có tứ giác nội tiếp.\n\n> Mẹo: gặp tổng hai tích các đoạn thẳng, hãy nghĩ đến Ptolemy!' },
    { key: 'p13', type: 'ASSIGNMENT', title: 'Bài 3 — Bài tập nộp: tứ giác nội tiếp ẩn', minutes: 40,
      content: '## Đề bài\n\nCho tam giác nhọn ABC nội tiếp (O). Các đường cao BE, CF cắt nhau tại H. Chứng minh:\n\n1. Tứ giác BCEF nội tiếp.\n2. AH vuông góc BC.\n3. `AE·AC = AF·AB`.' },
  ] }],
};
const COURSE_DRAFT = {
  title: 'Số học và Tổ hợp cho kỳ thi chuyên (đang biên soạn)',
  description: 'Khóa học đang được biên soạn: chia hết, đồng dư, đếm và nguyên lý Dirichlet. Dự kiến mở vào tháng sau.',
  sections: [{ title: 'Chương 1: Chia hết và số nguyên tố', lessons: [
    { key: 'd11', type: 'TEXT', title: 'Bài 1 — Các dấu hiệu chia hết quan trọng', minutes: 8, content: '## Dấu hiệu chia hết\n\n- Chia hết cho 3, 9: tổng các chữ số.\n- Chia hết cho 11: hiệu tổng các chữ số ở vị trí chẵn và lẻ.\n\n*Bản nháp, sẽ bổ sung ví dụ.*' },
    { key: 'd12', type: 'TEXT', title: 'Bài 2 — Số nguyên tố và định lý cơ bản của số học', minutes: 8, content: '## Số nguyên tố\n\nMọi số tự nhiên lớn hơn 1 đều phân tích được thành tích các số nguyên tố, và cách phân tích là duy nhất (không kể thứ tự).\n\n*Bản nháp.*' },
  ] }],
};

const LESSON_QA = [
  { lesson: 'l11', asker: 'h02', q: 'Thầy ơi, khi nào thì dùng công thức nghiệm thu gọn Δ\' ạ? Em hay phân vân giữa Δ và Δ\'.', answer: ['owner', 'Khi hệ số b là số chẵn (b = 2b\') thì dùng Δ\' = b\'² − ac cho nhanh em nhé. Nghiệm khi đó là x = (−b\' ± √Δ\')/a. Kết quả vẫn tương đương, chỉ khác ở độ gọn của phép tính.'] },
  { lesson: 'l11', asker: 'h05', q: 'Ở ví dụ 2, nếu đề không nói "hai nghiệm phân biệt" mà chỉ nói "có nghiệm" thì điều kiện là Δ ≥ 0 đúng không ạ?', answer: ['staff', 'Đúng rồi em! "Có nghiệm" nghĩa là Δ ≥ 0 (bao gồm nghiệm kép), còn "hai nghiệm phân biệt" mới là Δ > 0. Em cũng nhớ xét trường hợp a = 0 nếu a chứa tham số nhé.'] },
  { lesson: 'l11', asker: 'h08', q: 'Em chưa hiểu vì sao tổng hai nghiệm là −b/a ạ, có cách nào nhớ không?', answer: null },
  { lesson: 'l21', asker: 'h04', q: 'Khi áp dụng AM-GM, em nên thêm bớt hằng số thế nào để dấu bằng xảy ra đúng chỗ ạ?', answer: ['owner', 'Em hãy xác định trước điểm xảy ra dấu bằng (dự đoán), rồi chọn hằng số sao cho hai số hạng bằng nhau tại điểm đó. Ví dụ x + 4/x: dự đoán x = 2 thì hai số hạng là 2 và 2. Thầy sẽ làm thêm ví dụ trong livestream.'] },
  { lesson: 'l21', asker: 'h06', q: 'AM-GM có dùng được khi một biến bằng 0 không ạ?', answer: null },
  { lesson: 'l14', asker: 'h01', q: 'Ở câu 2, em có cần xét riêng trường hợp m = 0 không ạ?', answer: ['staff', 'Có em nhé: khi m = 0, hệ trở thành x = 3 và −y = 2, vẫn có nghiệm duy nhất nhưng em cần kiểm tra điều kiện x² + y² = 5 riêng. Cách chung là tính định thức D = −m² − 1 ≠ 0 với mọi m.'] },
];

// Tiến độ: số bài hoàn thành (đếm từ đầu khóa miễn phí, 8 bài) của từng người
const PROGRESS_FREE = { h01: 8, h02: 7, pro: 6, h03: 5, h04: 5, h05: 4, h06: 3, h08: 3, h09: 2, free: 2, h10: 1, h11: 1 };
const PROGRESS_PAID = { pro: 3, h01: 2 };

// Bài nộp: lesson key -> [{ user, text, grade? }] (grade: [điểm /10, nhận xét])
const SUBMISSIONS = [
  { lesson: 'l14', user: 'h01', text: 'Câu 1 (m = 1): hệ x + y = 3, x − y = 2 ⇒ cộng hai vế được 2x = 5 ⇒ x = 5/2, y = 1/2.\nCâu 2: định thức D = −m² − 1 ≠ 0 nên hệ luôn có nghiệm duy nhất x = (3 + 2m)/(m² + 1), y = (3m − 2)/(m² + 1). Thế vào x² + y² = 5 và rút gọn được (9 + 4 + 12m − 12m + 4m²... ) ⇒ 13/(m² + 1) = 5 ⇒ m² = 8/5.', grade: [9.5, 'Làm rất tốt! Cách dùng định thức gọn và đúng. Chỉ cần trình bày rõ hơn bước rút gọn x² + y² để người chấm theo dõi dễ hơn.'] },
  { lesson: 'l14', user: 'h02', text: 'Khi m = 1: cộng hai phương trình được 2x = 5 nên x = 2,5 và y = 0,5. Câu 2 em chưa làm được phần thế vào điều kiện x² + y² = 5 ạ.', grade: [6.5, 'Câu 1 đúng. Câu 2 em đã tìm đúng nghiệm theo m nhưng chưa thế vào điều kiện nên chưa hoàn thành. Hãy thử thế x, y theo m rồi quy đồng nhé.'] },
  { lesson: 'l14', user: 'h03', text: 'Câu 1: x = 5/2, y = 1/2. Câu 2: dùng phương pháp thế y = (3 − x)/m... nhưng em bị bế tắc khi m = 0.', grade: [7.0, 'Em nhớ xét riêng m = 0 trước khi chia cho m. Hướng đi đúng, cố gắng hoàn thiện thêm nhé!'] },
  { lesson: 'l14', user: 'h05', text: 'Với m = 1 thì x = 2,5; y = 0,5. Câu 2: em tìm được m² = 8/5 nhưng em chưa kiểm tra điều kiện hệ có nghiệm duy nhất.', grade: null },
  { lesson: 'l14', user: 'h06', text: 'Em giải câu 1 được x = 5/2, y = 1/2. Câu 2 em đang thử cách đặt ẩn phụ, em sẽ bổ sung nếu kịp ạ.', grade: null },
  { lesson: 'l24', user: 'h01', text: 'Đặt x = b + c, y = c + a, z = a + b. Khi đó a = (y + z − x)/2... Biểu thức cần chứng minh trở thành (1/2)[(y + z)/x + (z + x)/y + (x + y)/z − 3] ≥ 3/2 vì mỗi cặp t/s + s/t ≥ 2 theo AM-GM. Dấu bằng khi x = y = z tức a = b = c.', grade: [10, 'Hoàn hảo! Cách đặt ẩn phụ gọn và chỉ rõ dấu bằng. Em có thể thử thêm cách Cauchy-Schwarz dạng Engel để so sánh.'] },
  { lesson: 'l24', user: 'h04', text: 'Em dùng Cauchy-Schwarz dạng Engel: tổng a²/(a(b+c)) ≥ (a+b+c)²/(2(ab+bc+ca)) ≥ 3/2 vì (a+b+c)² ≥ 3(ab+bc+ca). Dấu bằng khi a = b = c.', grade: null },
  { lesson: 'l24', user: 'pro', text: 'Em chứng minh bằng cách nhân chéo rồi khai triển, đưa về (a−b)²(a+b−c) + ... ≥ 0 nhưng bước cuối em chưa chắc chắn vì dấu của a + b − c.', grade: [7.5, 'Cách nhân chéo dễ sai dấu như em thấy. Hãy thử dùng Schur hoặc AM-GM theo cặp để tránh phải xét dấu a + b − c.'] },
  { lesson: 'p13', user: 'pro', text: '1) Ta có ∠BEC = ∠BFC = 90° nên E, F cùng nhìn BC dưới góc vuông ⇒ BCEF nội tiếp đường tròn đường kính BC. 2) H là trực tâm nên AH ⟂ BC. 3) Tam giác AEF ~ tam giác ABC (g.g) nên AE/AB = AF/AC ⇒ AE·AC = AF·AB.', grade: [9.0, 'Lập luận chính xác và đủ ý. Câu 3 nên nói rõ cặp góc bằng nhau để tam giác đồng dạng theo trường hợp g.g.'] },
];

// ---- Cửa hàng ----
const PRODUCTS = {
  pro: { title: 'Gói PRO 3 tháng — Luyện thi Toán chuyên', description: 'Mở khóa đề chuyên sâu dành riêng cho PRO, lời giải chi tiết, bài đăng PRO trên bảng tin và tài liệu PRO. Hiệu lực 90 ngày kể từ khi thanh toán.', price: 299000, durationDays: 90, publish: true },
  course: { title: 'Khóa học Hình học nâng cao (trọn bộ)', description: 'Truy cập đầy đủ khóa "Hình học nâng cao: đường tròn và tứ giác nội tiếp" trong 365 ngày, kèm bài tập nộp chấm.', price: 499000, durationDays: 365, publish: true, course: true },
  docpack: { title: 'Bộ 50 đề thi thử chuyên Toán (PDF + lời giải)', description: 'Trọn bộ 50 đề thi thử bám sát đề chuyên các năm gần đây, có lời giải mẫu và barem chấm. Truy cập 365 ngày.', price: 149000, durationDays: 365, publish: true },
  session: { title: 'Buổi kèm 1-1 cùng thầy cô (60 phút)', description: 'Một buổi kèm riêng 60 phút để chữa bài và định hướng ôn thi. Hiện đã ngừng nhận đăng ký (hết suất tháng này).', price: 350000, durationDays: 30, publish: true, archive: true },
  draft: { title: 'Combo luyện đề mùa hè (sắp ra mắt)', description: 'Gói luyện đề cường độ cao cho mùa hè, đang hoàn thiện nội dung và chưa mở bán.', price: 899000, durationDays: 90, publish: false },
};
// Đơn hàng mẫu: [mã, người mua, sản phẩm, trạng thái cuối]
const ORDERS = [
  ['o1', 'pro', 'pro', 'PAID'], ['o2', 'pro', 'course', 'PAID'], ['o3', 'h01', 'course', 'PAID'], ['o4', 'h06', 'pro', 'PAID'],
  ['o5', 'h02', 'docpack', 'REFUNDED'], ['o6', 'h04', 'pro', 'CANCELLED'], ['o7', 'h03', 'session', 'PENDING'], ['o8', 'h05', 'docpack', 'PENDING'],
  ['o9', 'h08', 'docpack', 'PAID'],
];

// ---- Tài liệu ----
const DOCUMENTS = [
  { title: 'Đề thi vào lớp 10 chuyên Toán Hà Nội - Amsterdam (kèm đáp án)', description: 'Đề chính thức các năm gần đây kèm đáp án tham khảo của giáo viên.', visibility: 'FREE', lines: ['De thi vao lop 10 chuyen Toan - Ha Noi Amsterdam', '', 'Cau 1 (2,0 diem). Giai phuong trinh x^2 - 5x + 6 = 0 va he x + y = 5, x - y = 1.', 'Cau 2 (2,0 diem). Cho bieu thuc A = x^2 - 4x + 7. Tim gia tri nho nhat cua A.', 'Cau 3 (2,0 diem). Giai he phuong trinh x^2 + y^2 = 5, x + y = 3.', 'Cau 4 (3,0 diem). Hinh hoc: duong tron noi tiep va tu giac noi tiep.', 'Cau 5 (1,0 diem). Bat dang thuc: a/b + b/c + c/a >= 3.'] },
  { title: 'Bảng công thức Đại số cần nhớ (1 trang)', description: 'Cheat sheet 1 trang: nghiệm phương trình bậc hai, Viète, hằng đẳng thức, AM-GM.', visibility: 'FREE', lines: ['Bang cong thuc Dai so can nho', '', '1. Phuong trinh bac hai: Delta = b^2 - 4ac; x = (-b +- sqrt(Delta)) / 2a.', '2. Viete: x1 + x2 = -b/a, x1.x2 = c/a.', '3. (a + b)^2 = a^2 + 2ab + b^2; a^2 - b^2 = (a - b)(a + b).', '4. AM-GM: a + b >= 2 sqrt(ab) voi a, b >= 0.', '5. Cauchy-Schwarz: (a^2 + b^2)(x^2 + y^2) >= (ax + by)^2.'] },
  { title: 'Tổng hợp 30 bài bất đẳng thức kinh điển', description: 'Ba mươi bài bất đẳng thức chọn lọc kèm gợi ý hướng giải.', visibility: 'FREE', lines: ['Tong hop 30 bai bat dang thuc kinh dien', '', 'Bai 1. Cho a, b > 0. Chung minh a/b + b/a >= 2.', 'Bai 2. Cho a, b, c > 0. Chung minh (a + b + c)(1/a + 1/b + 1/c) >= 9.', 'Bai 3. Nesbitt: a/(b + c) + b/(c + a) + c/(a + b) >= 3/2.', 'Bai 4. Cho a + b = 1. Tim GTNN cua 1/a + 1/b.', '... (con 26 bai trong ban day du)'] },
  { title: 'Cheat sheet Số học và Tổ hợp', description: 'Dấu hiệu chia hết, đồng dư cơ bản, quy tắc đếm và nguyên lý Dirichlet.', visibility: 'FREE', lines: ['Cheat sheet So hoc va To hop', '', '1. Chia het cho 3, 9: tong cac chu so.', '2. Dong du: a = b (mod m) khi m chia het a - b.', '3. Quy tac cong, quy tac nhan, hoan vi, chinh hop, to hop.', '4. Nguyen ly Dirichlet: nhot n+1 thu vao n chuong thi co chuong chua it nhat 2 thu.'] },
  { title: 'Đề thi thử số 3 — có lời giải chi tiết', description: 'Dành cho học viên PRO: đề thi thử số 3 kèm lời giải chi tiết từng câu.', visibility: 'PRO', lines: ['De thi thu so 3 (danh cho hoi vien PRO)', '', 'Cau 1. Giai he phuong trinh co tham so m va bien luan.', 'Cau 2. Bat dang thuc 3 bien voi dieu kien abc = 1.', 'Cau 3. Hinh hoc: duong tron noi tiep, tiep tuyen, tu giac noi tiep.', 'Cau 4. Phuong trinh nghiem nguyen.', '', 'Loi giai chi tiet o trang sau.'] },
  { title: 'Chuyên đề Hình học phẳng: các bài toán thường gặp', description: 'Dành cho học viên PRO: 12 dạng toán hình thường gặp trong đề chuyên.', visibility: 'PRO', lines: ['Chuyen de Hinh hoc phang (PRO)', '', 'Dang 1. Chung minh tu giac noi tiep.', 'Dang 2. Chung minh ba diem thang hang.', 'Dang 3. Chung minh ba duong dong quy.', 'Dang 4. Bai toan quy tich co dinh.', 'Dang 5. Tinh ty so va he thuc luong.'] },
  { title: 'Bộ 50 đề thi thử chuyên Toán', description: 'Dành cho người sở hữu gói "Bộ 50 đề thi thử": toàn bộ 50 đề dạng PDF.', visibility: 'PRODUCT_OWNER', product: 'docpack', lines: ['Bo 50 de thi thu chuyen Toan', '', 'De 01 - De 10: Dai so va he phuong trinh.', 'De 11 - De 20: Hinh hoc phang.', 'De 21 - De 30: So hoc va to hop.', 'De 31 - De 40: Bat dang thuc va cuc tri.', 'De 41 - De 50: De tong hop theo cau truc de that.'] },
  { title: 'Phiếu bài tập khóa Hình học nâng cao', description: 'Dành cho học viên đã mua khóa Hình học nâng cao: phiếu bài tập bổ trợ 20 bài.', visibility: 'PRODUCT_OWNER', course: 'paid', lines: ['Phieu bai tap khoa Hinh hoc nang cao', '', 'Bai 1. Chung minh tu giac BCEF noi tiep.', 'Bai 2. Ap dung he thuc Ptolemy tinh ty so.', 'Bai 3. Bai toan quy tich diem di dong tren duong tron.'] },
];

// ---- Kỳ thi ----
const optionsOf = (texts) => texts.map((text, i) => ({ optionKey: 'ABCD'[i], optionText: text, position: i }));
const mcq = (text, texts, correct, points) => ({ type: 'MULTIPLE_CHOICE', text, options: optionsOf(texts), correct, points });
const tf = (text, correct, points) => ({ type: 'TRUE_FALSE', text, options: [{ optionKey: 'TRUE', optionText: 'Đúng', position: 0 }, { optionKey: 'FALSE', optionText: 'Sai', position: 1 }], correct: correct ? 'TRUE' : 'FALSE', points });
const essay = (text, rubric, points) => ({ type: 'ESSAY', text, options: [], correct: rubric, points });

const EXAMS = {
  mid: { title: 'Kiểm tra giữa kỳ — Đại số (trắc nghiệm)', description: 'Đề trắc nghiệm 12 câu (8 nhiều lựa chọn + 4 đúng/sai) về phương trình, hệ phương trình và bất đẳng thức. Làm tối đa 2 lượt, tính điểm lượt tốt nhất.', durationMinutes: 45, attemptLimit: 2, audienceScope: 'ALL', passScore: 50, window: [-2, 40],
    questions: [
      mcq('Tập nghiệm của phương trình x² − 5x + 6 = 0 là:', ['{−2; −3}', '{1; 6}', '{2; 3}', '{−1; −6}'], 'C', 10),
      mcq('Với giá trị nào của m thì phương trình x² − 2x + m = 0 có hai nghiệm phân biệt?', ['m > 1', 'm < 1', 'm ≥ 1', 'm ≤ 1'], 'B', 10),
      mcq('Hệ phương trình x + y = 5 và x − y = 1 có nghiệm (x; y) là:', ['(2; 3)', '(4; 1)', '(1; 4)', '(3; 2)'], 'D', 10),
      mcq('Giá trị nhỏ nhất của biểu thức A = x² − 4x + 7 là:', ['3', '7', '4', '−3'], 'A', 10),
      mcq('Cho a, b > 0. Bất đẳng thức nào sau đây luôn đúng?', ['a² + b² ≤ 2ab', '(a + b)² ≤ 4ab', 'a + b ≤ 2√(ab)', 'a + b ≥ 2√(ab)'], 'D', 10),
      mcq('Rút gọn √12 + √27 − √75 ta được:', ['2√3', '5√3', '0', '−√3'], 'C', 10),
      mcq('Nếu x₁, x₂ là hai nghiệm của x² − 7x + 10 = 0 thì x₁² + x₂² bằng:', ['39', '29', '19', '49'], 'B', 10),
      mcq('Đường thẳng y = 2x − 3 cắt trục tung tại điểm có tung độ:', ['3', '2', '−3', '−2'], 'C', 10),
      tf('Phương trình x² + 1 = 0 có nghiệm thực.', false, 5),
      tf('Với mọi số thực x, ta luôn có (x − 1)² ≥ 0.', true, 5),
      tf('Tổng hai nghiệm của phương trình 2x² − 6x + 1 = 0 bằng 3.', true, 5),
      tf('Hàm số y = −3x + 2 đồng biến trên ℝ.', false, 5),
    ] },
  essay: { title: 'Đề thi thử số 1 — có phần tự luận', description: 'Đề thi thử 90 phút: 4 câu trắc nghiệm và 3 câu tự luận. Phần tự luận được trợ giảng chấm tay trong vòng 3 ngày.', durationMinutes: 90, attemptLimit: 1, audienceScope: 'ALL', passScore: 50, window: [-1, 30],
    questions: [
      mcq('Cho tam giác ABC vuông tại A, đường cao AH. Hệ thức nào sau đây đúng?', ['AH² = BH·CH', 'AH = BH + CH', 'AB² = BH·CH', 'AH² = AB·AC'], 'A', 10),
      mcq('Tứ giác ABCD nội tiếp được đường tròn khi và chỉ khi:', ['∠A = ∠C', '∠A + ∠C = 180°', '∠A + ∠B = 180°', 'AB = CD'], 'B', 10),
      mcq('Diện tích hình tròn bán kính R là:', ['2πR', 'πR²', 'πR', '2πR²'], 'B', 10),
      mcq('Số đo góc nội tiếp chắn nửa đường tròn là:', ['60°', '180°', '45°', '90°'], 'D', 10),
      essay('Giải hệ phương trình: x² + y² = 5 và x + y = 3 (trình bày đầy đủ các bước).', 'Đặt S = x + y = 3, P = xy; x²+y² = S² − 2P = 5 ⇒ P = 2; nghiệm là hoán vị của (1; 2). Thang điểm: đặt ẩn 5, tìm P 5, kết luận nghiệm 10.', 20),
      essay('Cho đường tròn (O) và điểm A nằm ngoài (O). Kẻ hai tiếp tuyến AB, AC (B, C là các tiếp điểm). Chứng minh tứ giác ABOC nội tiếp và AO ⟂ BC.', 'Góc B và C vuông ⇒ tổng bằng 180°; AB = AC, OB = OC ⇒ AO là trung trực BC. Mỗi ý 10 điểm.', 20),
      essay('Chứng minh rằng với mọi số thực dương a, b, c ta có a/b + b/c + c/a ≥ 3.', 'AM-GM ba số: tích bằng 1; nêu dấu bằng a = b = c. Đúng cách 20 điểm.', 20),
    ] },
  pro: { title: 'Đề chuyên sâu PRO — Hình học nâng cao', description: 'Dành riêng cho học viên PRO: 6 câu trắc nghiệm hình học ở mức vận dụng cao, có đáp án và giải thích sau khi nộp bài.', durationMinutes: 40, attemptLimit: 2, audienceScope: 'PRO', passScore: 60, window: [-1, 60],
    questions: [
      mcq('Tứ giác ABCD nội tiếp. Hệ thức Ptolemy phát biểu:', ['AC·BD = AB·CD + AD·BC', 'AC + BD = AB + CD', 'AC·BD = AB·BC + CD·DA − 1', 'AC² = AB² + BC²'], 'A', 10),
      mcq('Cho tam giác ABC nhọn, H là trực tâm. Tứ giác nào sau đây luôn nội tiếp?', ['ABHC', 'BHCE với E là chân đường cao từ B và F là chân đường cao từ C', 'BCEF (E, F là chân hai đường cao)', 'AHBC'], 'C', 10),
      mcq('Đường tròn Euler đi qua bao nhiêu điểm đặc biệt của tam giác?', ['3', '6', '9', '12'], 'C', 10),
      mcq('Cho đường tròn (O; R) và dây AB = R√3. Số đo cung nhỏ AB là:', ['60°', '90°', '120°', '150°'], 'C', 10),
      mcq('Hai đường tròn tiếp xúc ngoài có bán kính 3 và 5 thì khoảng cách hai tâm bằng:', ['2', '8', '15', '4'], 'B', 10),
      mcq('Trong tam giác ABC, đường tròn nội tiếp tiếp xúc BC tại D. Nếu AB = 7, AC = 8, BC = 9 thì BD bằng:', ['4', '5', '6', '3'], 'A', 10),
    ] },
  future: { title: 'Thi thử lần 2 — mở vào cuối tuần sau', description: 'Kỳ thi thử đầy đủ cấu trúc đề chuyên, mở trong khung giờ cố định. Chưa đến giờ mở nên chưa thể vào làm.', durationMinutes: 120, attemptLimit: 1, audienceScope: 'ALL', passScore: 50, future: true,
    questions: [
      mcq('Giải phương trình |x − 2| = 3, nghiệm là:', ['x = 5', 'x = −1', 'x = 5 hoặc x = −1', 'vô nghiệm'], 'C', 10),
      mcq('Số nghiệm nguyên dương của bất phương trình x² ≤ 10 là:', ['3', '4', '5', '6'], 'A', 10),
      mcq('Giá trị lớn nhất của y = −x² + 4x + 1 là:', ['4', '5', '1', '6'], 'B', 10),
      mcq('Cho hình chữ nhật có chu vi 20. Diện tích lớn nhất của nó là:', ['25', '24', '20', '16'], 'A', 10),
      mcq('Số cách xếp 4 bạn vào 4 ghế thành một hàng là:', ['16', '24', '12', '8'], 'B', 10),
    ] },
  closed: { title: 'Khảo sát đầu vào (đã đóng)', description: 'Đề khảo sát đầu vào đã đóng nhận bài. Bạn vẫn có thể xem lại kết quả của mình.', durationMinutes: 30, attemptLimit: 1, audienceScope: 'ALL', passScore: 40, window: [-30, 20], close: true,
    questions: [
      mcq('Kết quả của phép tính (−3)² − 2·(−3) là:', ['3', '15', '−3', '9'], 'B', 10),
      mcq('Nghiệm của phương trình 3x − 7 = 11 là:', ['x = 6', 'x = 4', 'x = −6', 'x = 18'], 'A', 10),
      mcq('Hệ số góc của đường thẳng y = −4x + 1 là:', ['1', '4', '−4', '−1'], 'C', 10),
      mcq('Rút gọn biểu thức (x + 2)² − (x − 2)² ta được:', ['8x', '4x', '8', '2x²'], 'A', 10),
      mcq('Số đo mỗi góc của tam giác đều là:', ['45°', '90°', '30°', '60°'], 'D', 10),
      mcq('Diện tích tam giác có đáy 8 và chiều cao 5 là:', ['40', '20', '13', '80'], 'B', 10),
      tf('Số 0 là số chính phương.', true, 5),
      tf('Mọi số nguyên tố đều là số lẻ.', false, 5),
    ] },
};
// Số câu đúng (tính trên tổng số câu trắc nghiệm/đúng-sai) của từng người; lượt thứ hai (nếu có) là phần tử thứ hai.
const ATTEMPTS = {
  mid: { h01: [12], h02: [11], h03: [11], h04: [10], pro: [10], h05: [9], h06: [9], h07: [8], h08: [7], h09: [6], h10: [5, 8], free: [4, 6], h11: [3], h13: [2] },
  pro: { pro: [5, 6], h01: [4], h06: [3] },
  closed: { h01: [8], h02: [7], h03: [7], h04: [6], h05: [5], h06: [5], h08: [4], free: [3], h09: [2] },
};
// Đề tự luận: số câu trắc nghiệm đúng (0-4) + điểm 3 câu tự luận (tối đa 20) + nhận xét; ai không có điểm thì nằm chờ chấm.
const ESSAY_ATTEMPTS = {
  h01: { mcq: 4, grade: [[19, 'Trình bày rất rõ ràng, đúng và đủ ý.'], [18, 'Chứng minh chặt chẽ; nên nêu rõ tính chất hai tiếp tuyến cắt nhau.'], [20, 'Hoàn hảo, nêu đúng điều kiện dấu bằng.']] },
  h02: { mcq: 3, grade: [[16, 'Đặt ẩn đúng nhưng tính P bị nhầm dấu ở một bước, sau đó vẫn đi đúng hướng.'], [12, 'Chứng minh nội tiếp tốt nhưng phần AO ⟂ BC còn thiếu lập luận.'], [14, 'Có hướng dùng AM-GM nhưng thiếu điều kiện dấu bằng.']] },
  h04: { mcq: 4, grade: [[20, 'Chính xác tuyệt đối.'], [15, 'Hướng đúng, phần kết luận AO vuông góc BC chưa đầy đủ.'], [17, 'Đúng ý, trình bày hơi tắt ở bước áp dụng AM-GM.']] },
  h03: { mcq: 3, grade: null },
  h05: { mcq: 2, grade: null },
  h06: { mcq: 4, grade: null },
  h09: { mcq: 1, grade: null },
};
const ESSAY_TEXTS = [
  'Đặt S = x + y = 3 và P = xy. Ta có x² + y² = S² − 2P = 9 − 2P = 5 nên P = 2. Khi đó x, y là hai nghiệm của t² − 3t + 2 = 0, suy ra t = 1 hoặc t = 2. Vậy (x; y) = (1; 2) hoặc (2; 1).',
  'Vì AB, AC là hai tiếp tuyến nên ∠ABO = ∠ACO = 90°, do đó ∠ABO + ∠ACO = 180° ⇒ tứ giác ABOC nội tiếp. Mặt khác AB = AC (tính chất hai tiếp tuyến cắt nhau) và OB = OC nên AO là đường trung trực của BC, suy ra AO ⟂ BC.',
  'Áp dụng bất đẳng thức AM-GM cho ba số dương a/b, b/c, c/a: a/b + b/c + c/a ≥ 3·³√((a/b)(b/c)(c/a)) = 3. Dấu "=" xảy ra khi a/b = b/c = c/a, tức a = b = c.',
];

const LEADERBOARD = {
  tiers: [
    { tierName: 'Tân thủ', minPoints: 0, description: 'Mới bắt đầu hành trình luyện thi.' },
    { tierName: 'Đồng', minPoints: 40, description: 'Đã có nền tảng và tích lũy điểm đầu tiên.' },
    { tierName: 'Bạc', minPoints: 100, description: 'Làm bài đều đặn, kết quả ổn định.' },
    { tierName: 'Vàng', minPoints: 180, description: 'Kết quả xuất sắc ở nhiều đề.' },
    { tierName: 'Kim cương', minPoints: 250, description: 'Nhóm dẫn đầu của lớp.' },
  ],
  rewards: { mid: [[50, 20], [70, 50], [90, 100]], essay: [[50, 30], [70, 60], [90, 120]], pro: [[60, 40], [85, 90]], closed: [[50, 10], [80, 30]] },
};

// ---- Sự kiện ----
const EVENTS = [
  { key: 'live', title: 'Livestream: Chữa đề thi thử số 2 và giải đáp thắc mắc', format: 'ONLINE', audience: 'PUBLIC', start: [3, 20, 0], minutes: 120, capacity: 100, cover: 0, host: 'owner',
    meetingUrl: 'https://meet.google.com/abc-defg-hij', location: 'Google Meet',
    description: 'Buổi livestream hằng tuần: thầy chữa chi tiết đề thi thử số 2, phân tích lỗi sai phổ biến và trả lời câu hỏi trực tiếp của học viên.\n\n**Cách tham gia:** đăng ký ở trang này, liên kết phòng họp sẽ hiện ra cho người đã đăng ký.',
    forWhom: 'Học viên đã làm đề thi thử số 2 hoặc muốn nghe chữa đề.', takeaways: ['Lời giải chuẩn barem của cả 7 câu', 'Cách tránh 5 lỗi sai phổ biến', 'Giải đáp trực tiếp thắc mắc của bạn'],
    regs: ['free', 'h01', 'h02', 'h03', 'h04', 'h05', 'h06', 'h09'] },
  { key: 'offline', title: 'Gặp mặt & kiểm tra trực tiếp tại Hà Nội', format: 'OFFLINE', audience: 'PUBLIC', start: [10, 14, 0], minutes: 180, capacity: 30, cover: 1, host: 'staff',
    location: 'Phòng 301, Nhà A5, Đại học Sư phạm Hà Nội, 136 Xuân Thủy, Cầu Giấy, Hà Nội',
    description: 'Buổi gặp mặt trực tiếp đầu tiên của lớp: làm bài kiểm tra 90 phút trong điều kiện như thi thật, sau đó thầy cô chữa nhanh và giao lưu cùng các bạn.\n\nMang theo bút, thước, compa và giấy nháp. Phòng thi sẽ yên tĩnh, các bạn nhớ đến trước 15 phút.',
    forWhom: 'Học viên ở Hà Nội và các tỉnh lân cận muốn trải nghiệm phòng thi thật.', takeaways: ['Trải nghiệm làm đề trong phòng thi thật', 'Nhận xét trực tiếp từ thầy cô', 'Kết nối với các bạn cùng lớp'],
    regs: ['free', 'h01', 'h03', 'h04', 'h08', 'h09', 'h11', 'h13'] },
  { key: 'workshop', title: 'Workshop: Chiến lược làm bài hình học khó', format: 'ONLINE', audience: 'PUBLIC', start: [5, 19, 30], minutes: 90, capacity: 12, cover: 2, host: 'owner',
    meetingUrl: 'https://zoom.us/j/9876543210', location: 'Zoom',
    description: 'Workshop nhóm nhỏ (chỉ 12 chỗ) để cùng thầy giải các câu hình "phân loại" từ đề chuyên: dựng hình phụ, tìm tứ giác nội tiếp ẩn và quản lý thời gian cho câu hình.',
    forWhom: 'Học viên muốn nâng điểm câu hình ở mức 8+.', takeaways: ['5 cách dựng hình phụ hay dùng', 'Quy trình 4 bước tìm tứ giác nội tiếp ẩn'],
    regs: ['h01', 'h02', 'h03', 'h04', 'h05', 'h06', 'h08', 'h09', 'h10', 'h11', 'h13'] },
  { key: 'full', title: 'Tư vấn 1-1: chọn trường chuyên và lộ trình ôn', format: 'OFFLINE', audience: 'PUBLIC', start: [7, 9, 0], minutes: 120, capacity: 5, cover: null, host: 'staff',
    location: 'Cà phê Sách, 25 Lê Thánh Tông, Hoàn Kiếm, Hà Nội',
    description: 'Buổi tư vấn nhóm nhỏ về cách chọn trường chuyên, nguyện vọng và xây dựng lộ trình ôn thi phù hợp từng bạn. Số chỗ rất giới hạn.',
    forWhom: 'Học viên và phụ huynh đang phân vân khi chọn trường.', takeaways: ['Hiểu cấu trúc đề từng trường', 'Lộ trình ôn cá nhân hóa'],
    regs: ['h01', 'h02', 'h04', 'h05', 'h06'] },
  { key: 'members', title: 'Sinh hoạt thành viên: chia sẻ kinh nghiệm ôn thi', format: 'ONLINE', audience: 'MEMBERS', start: [2, 20, 30], minutes: 60, capacity: null, cover: 3, host: 'staff',
    meetingUrl: 'https://meet.google.com/xyz-uvwx-rst', location: 'Google Meet',
    description: 'Buổi sinh hoạt riêng cho thành viên của lớp: các bạn chia sẻ cách học, góc học tập, và những lỗi sai đáng nhớ. Không áp lực, chỉ là trò chuyện!',
    forWhom: 'Thành viên của lớp.', takeaways: ['Học hỏi cách học của bạn bè', 'Tìm bạn học chung nhóm'],
    regs: ['staff', 'free', 'h02', 'h05', 'h08', 'h10'] },
  { key: 'cancelled', title: 'Buổi ôn tập chuyên đề Số học', format: 'ONLINE', audience: 'PUBLIC', start: [4, 20, 0], minutes: 90, capacity: 40, cover: null, host: 'owner', cancel: true,
    meetingUrl: 'https://meet.google.com/old-link-xyz', location: 'Google Meet',
    description: 'Buổi ôn tập chuyên đề Số học (chia hết, đồng dư). **Buổi này đã bị hủy do trùng lịch** - thầy sẽ thông báo lịch mới.',
    forWhom: 'Học viên quan tâm chuyên đề Số học.', takeaways: ['Hệ thống hóa dấu hiệu chia hết'], regs: ['h03', 'h06', 'h13'] },
  { key: 'past1', title: 'Livestream: Hướng dẫn lập kế hoạch ôn thi 6 tháng', format: 'ONLINE', audience: 'PUBLIC', start: [-14, 20, 0], minutes: 90, capacity: 100, cover: 4, host: 'owner', past: true,
    meetingUrl: 'https://meet.google.com/plan-6mo-abc', location: 'Google Meet',
    description: 'Buổi livestream đầu tiên của lớp: cách lập kế hoạch ôn thi 6 tháng, cách chia thời gian mỗi tuần và cách dùng nền tảng của lớp. Đã kết thúc - bản ghi được đăng ở bảng tin.',
    forWhom: 'Tất cả học viên mới.', takeaways: ['Mẫu kế hoạch 6 tháng', 'Cách dùng sổ lỗi sai'], regs: ['free', 'pro', 'h01', 'h02', 'h03', 'h04', 'h05', 'h06', 'h08'] },
  { key: 'past2', title: 'Thi thử đầu vào trực tiếp tại trung tâm', format: 'OFFLINE', audience: 'PUBLIC', start: [-5, 8, 0], minutes: 150, capacity: 30, cover: null, host: 'staff', past: true,
    location: 'Trung tâm Học liệu, 54 Triều Khúc, Thanh Xuân, Hà Nội',
    description: 'Buổi thi thử đầu vào trực tiếp để nắm mặt bằng chung của lớp. Đã kết thúc; kết quả được công bố trong tab Thi.',
    forWhom: 'Học viên khu vực Hà Nội.', takeaways: ['Trải nghiệm thi trực tiếp'], regs: ['h01', 'h04', 'h06', 'h09', 'h11', 'h13'] },
];

// ------------------------------------------------------------------------------------------------------------------
// MAIN
// ------------------------------------------------------------------------------------------------------------------
const ctx = {}; // trạng thái chạy: lớp, người dùng, id các đối tượng đã tạo
const inventory = {};

async function main() {
  section(`Kiểm tra hệ thống ${BASE}`);
  const health = await fetch(`${BASE}/api/v1/health`).then((r) => r.json()).catch(() => null);
  if (!health || health.status !== 'UP') throw new Error(`Backend ${BASE} không phản hồi (health != UP). Hãy bật stack demo trước.`);
  const sandbox = await request('GET', '/payments/sandbox-status').catch(() => null);
  if (!sandbox || !sandbox.checkoutAvailable) warn('Cổng thanh toán sandbox KHÔNG bật: đơn hàng sẽ chỉ ở trạng thái PENDING.');

  await step1Accounts();
  await step2Class();
  await step3Members();
  await step4AboutAndImages();
  await step5Courses();
  await step6Shop();
  await step6bProgress();
  await step7Feed();
  await step8Blog();
  await step9Documents();
  await step10Exams();
  await step11Events();
  await step12Learning();
  await step13Studio();
  await summary();
}

// ---- 1. Tài khoản ----
async function step1Accounts() {
  section('1. Tài khoản');
  const owner = await new Session('owner', 'owner@classroom.local', 'Chủ lớp').login();
  const staff = await new Session('staff', 'staff@classroom.local', 'Trợ giảng').login();
  const free = await new Session('free', 'student.free@classroom.local', 'Học viên Free').login();
  const pro = await new Session('pro', 'student.pro@classroom.local', 'Học viên Pro').login();
  Object.assign(sessions, { owner, staff, free, pro });
  // Chủ lớp/trợ giảng demo mặc định có hồ sơ PRIVATE: khách sẽ thấy "Người dùng ẩn danh" ở bài đăng công khai - đặt PUBLIC cho đẹp.
  await owner.put('/users/profile', { fullName: owner.name, bio: 'Giáo viên Toán 12 năm kinh nghiệm bồi dưỡng học sinh giỏi, chủ nhiệm lớp luyện thi vào 10 chuyên.', profileVisibility: 'PUBLIC' });
  await staff.put('/users/profile', { fullName: staff.name, bio: 'Cựu học sinh chuyên Toán, trợ giảng chấm bài và giải đáp thắc mắc cho lớp.', profileVisibility: 'PUBLIC' });
  log(`đăng nhập owner (${owner.name}), staff (${staff.name}), free (${free.name}), pro (${pro.name})`);
  for (const spec of STUDENTS) { sessions[spec.key] = await ensureStudent(spec); }
  log(`${STUDENTS.length} học viên demo.hocvienXX@example.com sẵn sàng (hồ sơ: tiểu sử + chế độ hiển thị PUBLIC/CLASS/PRIVATE)`);
  ctx.byId = Object.fromEntries(Object.values(sessions).map((s) => [s.id, s]));
}

// ---- 2. Lớp ----
async function step2Class() {
  section('2. Lớp học');
  const { owner } = sessions;
  let slug = BASE_SLUG, title = BASE_TITLE;
  const existing = await owner.tryCall([404], 'GET', `/classes/slug/${BASE_SLUG}`);
  if (existing) {
    if (FORCE_SUFFIX) {
      const suffix = Math.random().toString(36).slice(2, 6);
      slug = `${BASE_SLUG}-${suffix}`; title = `${BASE_TITLE} (${suffix})`;
      log(`lớp ${BASE_SLUG} đã tồn tại -> tạo bản mới ${slug}`);
    } else if (!RESUME) {
      console.error(`Từ chối: lớp "${BASE_SLUG}" đã tồn tại. Dùng --resume để bổ sung vào lớp đó hoặc --force-new-suffix để tạo một bản mới (script không bao giờ xóa/ghi đè).`);
      process.exit(3);
    } else {
      ctx.classroom = existing;
      log(`--resume: dùng lớp có sẵn ${existing.slug} (${existing.id})`);
    }
  }
  if (!ctx.classroom) {
    ctx.classroom = await owner.post('/classes', { title, slug, description: CLASS_DESCRIPTION, visibility: 'PUBLIC', category: 'Ôn thi', requireApproval: false, coverPosition: '50% 40%', avatarPosition: '50% 50%' });
    log(`đã tạo lớp ${ctx.classroom.slug} (${ctx.classroom.id})`);
  }
  ctx.classId = ctx.classroom.id; ctx.slug = ctx.classroom.slug;
}

// ---- 3. Thành viên + trợ giảng ----
async function step3Members() {
  section('3. Thành viên, trợ giảng, mã mời');
  const { owner, staff, free, pro } = sessions;
  const joiners = ['staff', 'free', 'pro', ...STUDENTS.map((s) => s.key)];
  // Chỉ cho vào lớp những ai CHƯA có dòng trong danh sách: người đã bị gỡ/chặn (lần chạy trước) phải giữ nguyên trạng thái.
  const known = new Set(((await owner.get(`/classes/${ctx.classId}/studio/members?size=200`)).members || []).map((m) => m.userId));
  let joined = 0;
  for (const key of joiners) {
    if (known.has(sessions[key].id)) continue;
    await sessions[key].post(`/classes/${ctx.classId}/join`); joined++;
  }
  log(`${joined} người mới vào lớp (miễn phí, không cần duyệt); ${joiners.length - joined} đã ở trong danh sách`);

  const perms = [];
  const grant = (module, actions) => actions.forEach((action) => perms.push({ module, action }));
  grant('BLOG', ['VIEW', 'CREATE', 'EDIT', 'PUBLISH', 'DELETE']);
  grant('EVENT', ['VIEW', 'CREATE', 'EDIT', 'DELETE']);
  grant('EXAM', ['VIEW', 'CREATE', 'EDIT', 'GRADE', 'PREVIEW']);
  grant('COURSE', ['VIEW', 'EDIT', 'GRADE']);
  grant('FEED', ['VIEW', 'CREATE']);
  grant('DOCUMENT', ['VIEW', 'CREATE']);
  grant('MEMBER', ['VIEW']);
  grant('MEDIA', ['CREATE']);
  grant('ABOUT', ['EDIT']);
  grant('STORE', ['VIEW']);
  grant('SEGMENT', ['VIEW']);
  grant('LEADERBOARD', ['EDIT']);
  grant('AUDIT', ['VIEW']);
  await owner.put(`/classes/${ctx.classId}/staff/${staff.id}/permissions`, perms);
  log(`trợ giảng ${staff.email} có ${perms.length} quyền (BLOG/EVENT/EXAM:GRADE/COURSE:GRADE/FEED/DOCUMENT/...)`);

  const invites = await owner.get(`/classes/${ctx.classId}/invites`);
  if (!invites.length) {
    const invite = await owner.post(`/classes/${ctx.classId}/invites`, { maxUses: 50 });
    ctx.inviteCode = invite.code;
    log(`mã mời (chỉ hiện một lần): ${invite.code}  -> ${WEB}/join/${invite.code}`);
  } else log(`đã có ${invites.length} mã mời (mã đầy đủ chỉ hiện đúng một lần lúc tạo)`);
}

// ---- 4. Ảnh bìa/avatar + Giới thiệu ----
async function step4AboutAndImages() {
  section('4. Ảnh lớp và trang Giới thiệu');
  const { owner } = sessions;
  const klass = await owner.get(`/classes/${ctx.classId}`);
  if (!klass.coverMediaId || !klass.avatarMediaId) {
    const coverId = await upload(owner, ctx.classId, { filename: 'cover.png', mimeType: 'image/png', bytes: image(1200, 300, 0), purpose: 'CLASS_COVER' });
    const avatarId = await upload(owner, ctx.classId, { filename: 'avatar.png', mimeType: 'image/png', bytes: image(400, 400, 5, 'avatar'), purpose: 'CLASS_AVATAR' });
    await owner.put(`/classes/${ctx.classId}`, { title: klass.title, description: CLASS_DESCRIPTION, visibility: 'PUBLIC', category: 'Ôn thi', requireApproval: false, coverMediaId: coverId, avatarMediaId: avatarId, coverPosition: '50% 40%', avatarPosition: '50% 50%' });
    log('đã tải ảnh bìa 1200x300 + avatar 400x400 và đặt vị trí ảnh');
  } else log('ảnh bìa/avatar đã có');

  const about = await owner.get(`/classes/${ctx.classId}/about`);
  const sameAbout = (about.sections || []).length === ABOUT.sections.length && ABOUT.sections.every(([t, c], i) => about.sections[i].title === t && about.sections[i].contentMarkdown === c && about.sections[i].imageUrl);
  if (!sameAbout) {
    const sections = [];
    for (let i = 0; i < ABOUT.sections.length; i++) {
      const [sectionTitle, contentMarkdown, imageAlt] = ABOUT.sections[i];
      const mediaAssetId = await upload(owner, ctx.classId, { filename: `about-${i + 1}.png`, mimeType: 'image/png', bytes: image(900, 500, i + 1), purpose: 'ABOUT' });
      sections.push({ title: sectionTitle, contentMarkdown, imageAlt, mediaAssetId });
    }
    await owner.put(`/classes/${ctx.classId}/about`, { contentMarkdown: ABOUT.contentMarkdown, rulesMarkdown: ABOUT.rulesMarkdown, sections, publishedVersion: about.publishedVersion });
    log(`trang Giới thiệu: ${sections.length} mục có ảnh + nội quy`);
  } else log('trang Giới thiệu đã có');
}

// ---- 5. Khóa học ----
async function ensureCourse(spec, { publish, accessMode = 'FREE' }) {
  const { owner } = sessions;
  const courses = await owner.get(`/classes/${ctx.classId}/courses`);
  let course = courses.find((c) => c.title === spec.title);
  if (!course) {
    course = await owner.post(`/classes/${ctx.classId}/courses`, { title: spec.title, description: spec.description, accessMode });
    log(`khóa học: ${spec.title}`);
  }
  let detail = await owner.get(`/courses/${course.id}`);
  const lessonIds = {};
  for (const sectionSpec of spec.sections) {
    let sec = (detail.sections || []).find((s) => s.title === sectionSpec.title);
    if (!sec) sec = await owner.post(`/courses/${course.id}/sections`, { title: sectionSpec.title, position: spec.sections.indexOf(sectionSpec) });
    const existingLessons = ((detail.sections || []).find((s) => s.id === sec.id) || {}).lessons || [];
    let position = 0;
    for (const l of sectionSpec.lessons) {
      let lesson = existingLessons.find((x) => x.title === l.title);
      if (!lesson) {
        const body = { title: l.title, type: l.type, durationMinutes: l.minutes, position, contentText: plain(l.type === 'VIDEO' ? l.transcript : l.content) };
        if (l.video) {
          body.mediaAssetId = await upload(owner, ctx.classId, { filename: `${l.key}.mp4`, mimeType: 'video/mp4', bytes: fs.readFileSync(path.join(FIXTURES, 'tiny.mp4')), purpose: 'LESSON', scopeCourseId: course.id });
          body.captionsVtt = makeVtt(l.cues);
        } else if (l.pdf) {
          body.mediaAssetId = await upload(owner, ctx.classId, { filename: `${l.key}.pdf`, mimeType: 'application/pdf', bytes: makePdf('Phieu bai tap: phuong trinh quy ve bac hai', COURSE_PDF_LINES), purpose: 'LESSON', scopeCourseId: course.id });
        }
        lesson = await owner.post(`/sections/${sec.id}/lessons`, body);
      }
      else {
        const wanted = plain(l.type === 'VIDEO' ? l.transcript : l.content); // --resume: đồng bộ nội dung chữ nếu script đã đổi
        // Gửi đủ title/type: PUT /lessons/{id} dùng thực thể Lesson có mặc định type=VIDEO nên thiếu type sẽ bị đổi thành VIDEO.
        if ((lesson.contentText || '') !== wanted || lesson.type !== l.type) await owner.put(`/lessons/${lesson.id}`, { title: l.title, type: l.type, contentText: wanted, durationMinutes: l.minutes });
      }
      lessonIds[l.key] = lesson.id;
      position++;
    }
  }
  if (publish && course.status !== 'PUBLISHED') course = await owner.post(`/courses/${course.id}/publish`);
  return { course, lessonIds };
}

async function step5Courses() {
  section('5. Khóa học');
  ctx.free = await ensureCourse(COURSE_FREE, { publish: true });
  ctx.paid = await ensureCourse(COURSE_PAID, { publish: true });
  ctx.draft = await ensureCourse(COURSE_DRAFT, { publish: false });
  ctx.lessons = { ...ctx.free.lessonIds, ...ctx.paid.lessonIds, ...ctx.draft.lessonIds };
  log(`3 khóa học (miễn phí PUBLISHED, trả phí PUBLISHED, nháp DRAFT) với ${Object.keys(ctx.lessons).length} bài học`);
}

// ---- 6. Cửa hàng + đơn hàng ----
async function step6Shop() {
  section('6. Cửa hàng và đơn hàng (sandbox)');
  const { owner } = sessions;
  const list = await owner.get(`/classes/${ctx.classId}/studio/products`);
  ctx.products = {};
  for (const [key, spec] of Object.entries(PRODUCTS)) {
    let product = list.find((p) => p.title === spec.title);
    if (!product) {
      product = await owner.post(`/classes/${ctx.classId}/products`, { title: spec.title, description: spec.description, price: spec.price, durationDays: spec.durationDays, targetCourseId: spec.course ? ctx.paid.course.id : undefined });
    }
    if (spec.publish && product.status === 'DRAFT') product = await owner.post(`/products/${product.id}/publish`);
    ctx.products[key] = product;
  }
  log(`${Object.keys(ctx.products).length} sản phẩm (đã tạo/công bố); khóa Hình học nâng cao gắn với sản phẩm "course"`);

  ctx.orders = {};
  for (const [tag, buyer, productKey, finalStatus] of ORDERS) {
    const session = sessions[buyer];
    const product = ctx.products[productKey];
    const order = await session.post('/orders', { classId: ctx.classId, productId: product.id, idempotencyKey: `showcase-${ctx.slug}-${tag}` });
    ctx.orders[tag] = order;
    if (finalStatus === 'PENDING' || order.status === finalStatus) continue;
    if (order.status === 'PENDING' && finalStatus === 'CANCELLED') { await session.post(`/orders/${order.id}/cancel`); continue; }
    if (order.status === 'PENDING') await owner.post('/payments/mock/simulate', { orderNumber: order.orderNumber, eventType: 'PAYMENT_SUCCESS' });
    if (finalStatus === 'REFUNDED') await owner.post('/payments/mock/simulate', { orderNumber: order.orderNumber, eventType: 'PAYMENT_REFUNDED' });
  }
  for (const [key, spec] of Object.entries(PRODUCTS)) {
    if (spec.archive && ctx.products[key].status !== 'ARCHIVED') ctx.products[key] = await owner.post(`/products/${ctx.products[key].id}/archive`);
  }
  log(`${ORDERS.length} đơn hàng: PAID x4, REFUNDED x1, CANCELLED x1, PENDING x2 (và 1 sản phẩm ARCHIVED)`);
}

// ---- 7. Thảo luận (bảng tin) ----
async function listAllFeed(session) {
  const posts = [];
  let cursor = '';
  for (let i = 0; i < 10; i++) {
    const page = await session.get(`/classes/${ctx.classId}/posts?size=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`);
    posts.push(...page.posts);
    if (!page.hasNext) break;
    cursor = page.nextCursor;
  }
  return posts;
}
async function step7Feed() {
  section('7. Thảo luận (bảng tin)');
  const { owner } = sessions;
  const segments = await owner.get(`/classes/${ctx.classId}/segments`);
  ctx.segments = {};
  for (const spec of SEGMENTS) {
    let seg = segments.find((s) => s.name === spec.name);
    if (!seg) seg = await owner.post(`/classes/${ctx.classId}/segments`, { name: spec.name, description: spec.description, logicOperator: spec.logicOperator, rules: spec.rules });
    ctx.segments[spec.key || 'pro'] = seg;
  }
  log(`${SEGMENTS.length} phân khúc (Segments)`);

  const existing = await listAllFeed(owner);
  let created = 0, commentsAdded = 0;
  for (const spec of FEED) {
    let post = existing.find((p) => p.title === spec.title);
    if (!post) {
      post = await sessions[spec.author].post(`/classes/${ctx.classId}/posts`, {
        title: spec.title, contentMarkdown: spec.body, visibility: spec.visibility, pinned: !!spec.pinned,
        targetProductId: spec.product ? ctx.products[spec.product].id : undefined, targetSegmentId: spec.segment ? ctx.segments[spec.segment].id : undefined,
      });
      created++;
    }
    const have = Number(post.commentCount || 0);
    for (let i = have; i < spec.comments.length; i++) {
      const [who, content] = spec.comments[i];
      await sessions[who].post(`/posts/${post.id}/comments`, { content });
      commentsAdded++;
    }
  }
  log(`${FEED.length} bài thảo luận (mới ${created}), thêm ${commentsAdded} bình luận; 1 bài ghim, đủ kiểu hiển thị FREE/PUBLIC/PRO/PRODUCT_OWNER/SEGMENT`);
}

// ---- 8. Blog ----
async function step8Blog() {
  section('8. Blog');
  const { owner } = sessions;
  const existing = (await owner.get(`/classes/${ctx.classId}/blog-posts?status=ALL&size=50`)).items;
  let created = 0;
  for (let i = 0; i < BLOGS.length; i++) {
    const spec = BLOGS[i];
    let post = existing.find((p) => p.title === spec.title);
    const author = sessions[spec.author];
    if (!post) {
      const coverMediaId = spec.cover === null ? undefined : await upload(author, ctx.classId, { filename: `blog-${i + 1}.png`, mimeType: 'image/png', bytes: image(800, 450, spec.cover + 2), purpose: 'BLOG' });
      post = await author.post(`/classes/${ctx.classId}/blog-posts`, { title: spec.title, excerpt: spec.excerpt, category: spec.category, contentMarkdown: spec.body, coverMediaId, audience: spec.audience });
      created++;
    }
    else if (post.id) { // --resume: đồng bộ lại nội dung nếu script đã được chỉnh sửa sau lần chạy trước
      const current = await owner.get(`/blog-posts/${post.id}`);
      if (current.contentMarkdown !== spec.body || current.excerpt !== spec.excerpt) await author.put(`/blog-posts/${post.id}`, { contentMarkdown: spec.body, excerpt: spec.excerpt });
    }
    if (!spec.draft && post.status !== 'PUBLISHED') await author.post(`/blog-posts/${post.id}/publish`);
  }
  log(`${BLOGS.length} bài blog (mới ${created}): 7 xuất bản (PUBLIC + MEMBERS, 5 có ảnh bìa, 3 chuyên mục) + 1 nháp`);
}

// ---- 9. Tài liệu ----
async function step9Documents() {
  section('9. Tài liệu');
  const { owner } = sessions;
  const docs = await owner.get(`/classes/${ctx.classId}/documents`);
  let created = 0;
  for (const spec of DOCUMENTS) {
    if (docs.find((d) => d.title === spec.title)) continue;
    const mediaAssetId = await upload(owner, ctx.classId, { filename: `${fold(spec.title).toLowerCase().replace(/[^a-z0-9]+/g, '-').slice(0, 40)}.pdf`, mimeType: 'application/pdf', bytes: makePdf(spec.title, spec.lines), purpose: 'DOCUMENT' });
    await owner.post(`/classes/${ctx.classId}/documents`, {
      title: spec.title, description: spec.description, mediaAssetId, visibility: spec.visibility,
      targetProductId: spec.product ? ctx.products[spec.product].id : undefined, targetCourseId: spec.course ? ctx[spec.course].course.id : undefined,
    });
    created++;
  }
  log(`${DOCUMENTS.length} tài liệu PDF (mới ${created}): FREE x4, PRO x2, PRODUCT_OWNER x2`);
}

// ---- 10. Thi + bảng xếp hạng ----
async function ensureExam(key, spec) {
  const { owner } = sessions;
  const exams = await owner.get(`/classes/${ctx.classId}/exams`);
  let exam = exams.find((e) => e.title === spec.title);
  if (!exam) {
    const body = { title: spec.title, description: spec.description, durationMinutes: spec.durationMinutes, attemptLimit: spec.attemptLimit, audienceScope: spec.audienceScope, passScore: spec.passScore };
    if (spec.future) { body.scheduleStart = vnTime(9, 19, 0); body.scheduleEnd = vnTime(9, 22, 0); }
    else if (spec.window) { body.scheduleStart = vnTime(spec.window[0], 0, 0); body.scheduleEnd = vnTime(spec.window[1], 23, 59); }
    exam = await owner.post(`/classes/${ctx.classId}/exams`, body);
    for (let i = 0; i < spec.questions.length; i++) {
      const q = spec.questions[i];
      await owner.post(`/exams/${exam.id}/questions`, { question: { questionText: q.text, type: q.type, points: q.points, position: i, answerKey: q.type === 'ESSAY' ? q.correct : q.correct }, options: q.options });
    }
    exam = await owner.post(`/exams/${exam.id}/publish`);
    log(`đề: ${spec.title} (${spec.questions.length} câu)`);
  }
  return exam;
}

/** Chọn các câu sẽ trả lời đúng một cách ổn định theo người làm (sai nhiều ở câu cuối hơn). */
function pickWrong(total, wrongCount, seed) {
  const rand = rng(seed);
  const weights = Array.from({ length: total }, (_, i) => i + 1 + rand() * total * 0.6).map((w, i) => [w, i]).sort((a, b) => b[0] - a[0]);
  return new Set(weights.slice(0, wrongCount).map(([, i]) => i));
}
function wrongAnswer(q, rand) {
  const keys = q.options.map((o) => o.optionKey).filter((k) => k !== q.correct);
  return keys[Math.floor(rand() * keys.length)];
}

async function takeAttempt(session, exam, spec, { correct, essayTexts, seedBase }) {
  const attempt = await session.post(`/exams/${exam.id}/attempts`);
  const objective = spec.questions.filter((q) => q.type !== 'ESSAY');
  const wrongSet = pickWrong(objective.length, objective.length - correct, seedBase);
  const rand = rng(seedBase + 17);
  const answers = {};
  for (const q of attempt.questions) {
    const def = spec.questions.find((d) => d.text === q.questionText);
    if (!def) continue;
    if (def.type === 'ESSAY') { answers[q.id] = essayTexts[spec.questions.filter((d) => d.type === 'ESSAY').indexOf(def)]; continue; }
    const idx = objective.indexOf(def);
    answers[q.id] = wrongSet.has(idx) ? wrongAnswer(def, rand) : def.correct;
  }
  return session.post(`/attempts/${attempt.id}/submit`, { answers });
}

async function step10Exams() {
  section('10. Kỳ thi, thưởng điểm và bảng xếp hạng');
  const { owner, staff } = sessions;
  ctx.exams = {};
  for (const [key, spec] of Object.entries(EXAMS)) ctx.exams[key] = await ensureExam(key, spec);

  // Bậc + luật thưởng PHẢI có trước khi nộp bài (điểm thưởng được chốt lúc bài được công bố).
  await owner.put(`/classes/${ctx.classId}/leaderboard/configuration`, {
    tiers: LEADERBOARD.tiers.map((t) => ({ tierName: t.tierName, minPoints: t.minPoints, description: t.description })),
    rewards: Object.entries(LEADERBOARD.rewards).flatMap(([key, rules]) => rules.map(([minExamScore, rewardPoints]) => ({ examId: ctx.exams[key].id, minExamScore, rewardPoints }))),
  });
  log(`bảng xếp hạng: ${LEADERBOARD.tiers.length} bậc + luật thưởng cho 4 đề`);

  let attempts = 0;
  const existingCount = async (session, examId) => (await session.get(`/exams/${examId}/my-attempts`)).filter((a) => !a.isPreview && a.status !== 'CANCELLED').length;
  for (const [key, plan] of Object.entries(ATTEMPTS)) {
    for (const [who, runs] of Object.entries(plan)) {
      const session = sessions[who];
      let done = await existingCount(session, ctx.exams[key].id);
      for (let r = done; r < runs.length; r++) {
        const res = await takeAttempt(session, ctx.exams[key], EXAMS[key], { correct: runs[r], essayTexts: [], seedBase: 31 * (STUDENTS.findIndex((s) => s.key === who) + 3) + r * 101 + key.length });
        attempts++;
        if (res.status !== 'PUBLISHED') warn(`bài ${key}/${who} có trạng thái ${res.status}`);
      }
    }
  }
  // Đề tự luận
  const essaySpec = EXAMS.essay;
  for (const [who, plan] of Object.entries(ESSAY_ATTEMPTS)) {
    const session = sessions[who];
    if (await existingCount(session, ctx.exams.essay.id)) continue;
    const res = await takeAttempt(session, ctx.exams.essay, essaySpec, { correct: plan.mcq, essayTexts: ESSAY_TEXTS, seedBase: 777 + who.length * 13 + who.charCodeAt(who.length - 1) });
    attempts++;
    if (plan.grade) {
      const detail = await staff.get(`/attempts/${res.id}/grading`);
      const scores = {}, feedback = {};
      detail.questions.filter((q) => q.type === 'ESSAY').forEach((q, i) => { scores[q.id] = plan.grade[i][0]; feedback[q.id] = plan.grade[i][1]; });
      await staff.post(`/attempts/${res.id}/grade`, { scores, feedback });
    }
  }
  // Đóng đề đã đóng
  for (const [key, spec] of Object.entries(EXAMS)) {
    if (spec.close && ctx.exams[key].status !== 'CLOSED') ctx.exams[key] = await owner.post(`/exams/${ctx.exams[key].id}/close`);
  }
  await sleep(1500);
  await owner.post(`/classes/${ctx.classId}/leaderboard/rebuild`);
  log(`đã nộp ${attempts} bài mới; hàng đợi chấm tay có bài của h03/h05/h06/h09; đã chạy lại bảng xếp hạng`);
}

// ---- 11. Sự kiện ----
async function step11Events() {
  section('11. Sự kiện');
  const { owner, staff } = sessions;
  const existing = await owner.get(`/classes/${ctx.classId}/events?scope=all`);
  let created = 0;
  for (let i = 0; i < EVENTS.length; i++) {
    const spec = EVENTS[i];
    let ev = existing.find((e) => e.title === spec.title);
    const creator = spec.host === 'staff' ? staff : owner;
    if (!ev) {
      const coverMediaId = spec.cover === null ? undefined : await upload(creator, ctx.classId, { filename: `event-${i + 1}.png`, mimeType: 'image/png', bytes: image(800, 400, spec.cover + 4), purpose: 'EVENT' });
      const startsAt = spec.past ? vnTime(1, 20, 0) : vnTime(...spec.start);
      const endsAt = spec.past ? vnTime(1, 22, 0) : new Date(new Date(startsAt).getTime() + spec.minutes * 60000).toISOString();
      ev = await creator.post(`/classes/${ctx.classId}/events`, {
        title: spec.title, description: spec.description, forWhom: spec.forWhom, takeaways: spec.takeaways, format: spec.format, location: spec.location,
        meetingUrl: spec.meetingUrl, startsAt, endsAt, capacity: spec.capacity ?? undefined, hostUserId: creator.id, coverMediaId, audience: spec.audience,
      });
      for (const who of spec.regs) await sessions[who].post(`/events/${ev.id}/registrations`);
      if (spec.cancel) await creator.post(`/events/${ev.id}/cancel`);
      if (spec.past) {
        const start = new Date(vnTime(spec.start[0], spec.start[1], spec.start[2]));
        await creator.put(`/events/${ev.id}`, { startsAt: start.toISOString(), endsAt: new Date(start.getTime() + spec.minutes * 60000).toISOString() });
      }
      created++;
    }
  }
  log(`${EVENTS.length} sự kiện (mới ${created}): 5 sắp tới (online/offline, gần đầy, đã đầy, chỉ thành viên), 1 đã hủy, 2 đã diễn ra`);
}

// ---- 6b. Tiến độ học (làm trước bảng tin để phân khúc "chăm chỉ" đã có người) ----
async function step6bProgress() {
  section('6b. Tiến độ học');
  const orderFree = ['l11', 'l12', 'l13', 'l14', 'l21', 'l22', 'l23', 'l24'];
  const orderPaid = ['p11', 'p12', 'p13'];
  let progress = 0;
  for (const [who, n] of Object.entries(PROGRESS_FREE)) for (const key of orderFree.slice(0, n)) { await sessions[who].put(`/lessons/${ctx.lessons[key]}/progress`, { completed: true }); progress++; }
  for (const [who, n] of Object.entries(PROGRESS_PAID)) for (const key of orderPaid.slice(0, n)) { await sessions[who].put(`/lessons/${ctx.lessons[key]}/progress`, { completed: true }); progress++; }
  log(`${progress} lượt hoàn thành bài học cho ${Object.keys(PROGRESS_FREE).length} học viên ở các mức khác nhau`);
}

// ---- 12. Học tập: hỏi đáp, nộp bài ----
async function step12Learning() {
  section('12. Học tập: hỏi đáp, bài nộp');
  const { owner, staff } = sessions;
  let questions = 0;
  for (const qa of LESSON_QA) {
    const lessonId = ctx.lessons[qa.lesson];
    const have = await owner.get(`/lessons/${lessonId}/questions`);
    let item = have.find((q) => q.questionText === qa.q);
    if (!item) { item = await sessions[qa.asker].post(`/lessons/${lessonId}/questions`, { questionText: qa.q }); questions++; }
    if (qa.answer && !(item.answers && item.answers.length)) await sessions[qa.answer[0]].post(`/questions/${item.id}/answers`, { answerText: qa.answer[1] });
  }
  log(`${LESSON_QA.length} câu hỏi trong bài (${LESSON_QA.filter((q) => q.answer).length} đã được trả lời, ${LESSON_QA.filter((q) => !q.answer).length} chờ trả lời)`);

  let graded = 0, pending = 0;
  for (const sub of SUBMISSIONS) {
    const lessonId = ctx.lessons[sub.lesson];
    const mine = await sessions[sub.user].get(`/lessons/${lessonId}/submissions/mine`);
    let row = mine[0];
    if (!row) row = await sessions[sub.user].post(`/lessons/${lessonId}/submissions`, { submissionText: sub.text });
    if (sub.grade) {
      if (row.status !== 'GRADED') { await staff.put(`/assignment-submissions/${row.id}/grade`, { score: sub.grade[0], feedback: sub.grade[1] }); }
      graded++;
    } else pending++;
  }
  log(`${SUBMISSIONS.length} bài nộp: ${graded} đã chấm (có nhận xét), ${pending} chờ chấm`);
}

// ---- 13. Studio: gỡ/chặn thành viên ----
async function step13Studio() {
  section('13. Studio: thành viên bị gỡ / bị chặn');
  const { owner } = sessions;
  const roster = (await owner.get(`/classes/${ctx.classId}/studio/members?size=200`)).members;
  const stateOf = (key) => (roster.find((m) => m.userId === sessions[key].id) || {}).state;
  if (stateOf('h14') === 'ACTIVE') await owner.post(`/classes/${ctx.classId}/studio/members/${sessions.h14.id}/remove`);
  if (stateOf('h15') === 'ACTIVE') await owner.post(`/classes/${ctx.classId}/studio/members/${sessions.h15.id}/block`);
  log(`${STUDENTS[13].fullName}: REMOVED; ${STUDENTS[14].fullName}: BLOCKED`);
}

// ---- Tổng kết ----
async function summary() {
  const { owner, pro, free } = sessions;
  const cid = ctx.classId;
  const roster = (await owner.get(`/classes/${cid}/studio/members?size=200`)).members;
  const byState = roster.reduce((acc, m) => { acc[m.state] = (acc[m.state] || 0) + 1; return acc; }, {});
  const blog = (await owner.get(`/classes/${cid}/blog-posts?status=ALL&size=50`)).items;
  const feed = await listAllFeed(owner);
  const courses = await owner.get(`/classes/${cid}/courses`);
  const exams = await owner.get(`/classes/${cid}/exams`);
  const events = await owner.get(`/classes/${cid}/events?scope=all`);
  const docs = await owner.get(`/classes/${cid}/documents`);
  const products = await owner.get(`/classes/${cid}/studio/products`);
  const orders = await owner.get(`/classes/${cid}/orders`);
  const board = await pro.get(`/classes/${cid}/leaderboard`);
  const about = await owner.get(`/classes/${cid}/about`);
  const segments = await owner.get(`/classes/${cid}/segments`);
  const grading = await owner.get(`/classes/${cid}/grading-queue`);
  const assignQueue = await owner.get(`/classes/${cid}/assignment-queue`);
  const countBy = (list, f) => list.reduce((acc, x) => { const k = f(x); acc[k] = (acc[k] || 0) + 1; return acc; }, {});
  const fmt = (o) => Object.entries(o).map(([k, v]) => `${k}=${v}`).join(', ');
  const url = (p) => `${WEB}/classes/${ctx.slug}${p}`;

  console.log(`\n${'='.repeat(100)}\nSHOWCASE SẴN SÀNG: ${ctx.classroom.title}\n${'='.repeat(100)}`);
  console.log('\nMở thử (web):');
  for (const tab of ['feed', 'blog', 'learn', 'exams', 'leaderboard', 'events', 'documents', 'members', 'about', 'store']) console.log(`  ${url('/' + tab)}`);
  console.log(`  Studio: ${WEB}/studio/classes/${cid}/overview  (members, staff, grading, leaderboard, store, audit, segments, blog, events...)`);
  if (ctx.inviteCode) console.log(`  Mã mời (hiện một lần): ${ctx.inviteCode}  ->  ${WEB}/join/${ctx.inviteCode}`);

  console.log(`\nTài khoản (mật khẩu: ${PASSWORD}):`);
  console.log('  owner@classroom.local            chủ lớp: toàn quyền, Studio, hàng đợi chấm bài, đơn hàng, mô phỏng thanh toán');
  console.log('  staff@classroom.local            trợ giảng: BLOG/EVENT/EXAM(GRADE)/COURSE(GRADE)/FEED/DOCUMENT/MEMBER:VIEW/AUDIT:VIEW... (không có STAFF/STORE:EDIT)');
  console.log('  student.pro@classroom.local      thành viên PRO (đã mua gói PRO + khóa Hình học): thấy đề/bài PRO, tài liệu PRO, khóa trả phí');
  console.log('  student.free@classroom.local     thành viên thường: bài/đề/tài liệu PRO bị khóa, đã đăng ký 2 sự kiện (thử hủy/đăng ký lại)');
  console.log('  demo.hocvien01..15@example.com   học viên (01: PRO + điểm cao; 06: PRO; 14: bị gỡ; 15: bị chặn; 07/12/15: hồ sơ PRIVATE)');
  console.log('  (khách chưa đăng nhập: xem được Thảo luận PUBLIC, Blog PUBLIC (bài MEMBERS bị khóa), Sự kiện, Giới thiệu, Cửa hàng)');

  console.log('\nThống kê (đếm từ API):');
  console.log(`  Thành viên      : ${roster.length} dòng danh sách (${fmt(byState)}); vai trò ${fmt(countBy(roster, (m) => m.role))}`);
  console.log(`  Thảo luận       : ${feed.length} bài, ${feed.reduce((s, p) => s + Number(p.commentCount || 0), 0)} bình luận, ${feed.filter((p) => p.pinned).length} bài ghim; hiển thị ${fmt(countBy(feed, (p) => p.visibility))}`);
  console.log(`  Blog            : ${blog.length} bài (${fmt(countBy(blog, (p) => p.status))}); ${blog.filter((p) => p.coverMediaId).length} có ảnh bìa; chuyên mục ${fmt(countBy(blog, (p) => p.category))}`);
  console.log(`  Khóa học        : ${courses.length} (${fmt(countBy(courses, (c) => `${c.status}/${c.accessMode}`))}); ${Object.keys(ctx.lessons).length} bài học`);
  console.log(`  Thi             : ${exams.length} đề (${fmt(countBy(exams, (e) => `${e.status}/${e.audienceScope}`))}); hàng đợi chấm tay ${grading.length} bài; hàng đợi chấm bài tập ${assignQueue.length}`);
  console.log(`  Xếp hạng        : ${board.length} người; bậc ${fmt(countBy(board, (e) => e.currentTier))}`);
  console.log(`  Sự kiện         : ${events.length} (${fmt(countBy(events, (e) => e.status))})`);
  console.log(`  Tài liệu        : ${docs.length} (${fmt(countBy(docs, (d) => d.visibility))})`);
  console.log(`  Cửa hàng        : ${products.length} sản phẩm (${fmt(countBy(products, (p) => p.status))}); ${orders.length} đơn (${fmt(countBy(orders, (o) => o.status))})`);
  console.log(`  Giới thiệu      : ${(about.sections || []).length} mục có ảnh + nội quy; Phân khúc: ${segments.length}`);
  console.log(`  Ghi chú         : bài ${free.email} chỉ là thành viên thường nên các nội dung PRO hiện khóa với tài khoản này.`);
  if (warnings.length) console.log(`\nCảnh báo (${warnings.length}):\n${warnings.map((w) => `  ! ${w}`).join('\n')}`);
  console.log('');
}

main().catch((error) => {
  console.error(`\nLỖI: ${error && error.stack ? error.stack : error}`);
  process.exit(1);
});
