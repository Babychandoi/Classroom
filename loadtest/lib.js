'use strict';
// Thư viện dùng chung cho bộ kiểm thử tải/lỗi (xem README.md). Không có phụ thuộc ngoài: chỉ Node >= 20.
const http = require('http');
const https = require('https');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execSync } = require('child_process');

// BASE_URL là địa chỉ mà TRÌNH DUYỆT dùng (frontend/nginx). Bên trong mạng Docker của drill là http://frontend;
// trên máy chủ (host) là http://127.0.0.1:13000. API_URL mặc định = BASE_URL + /api/v1.
const BASE_URL = (process.env.BASE_URL || 'http://127.0.0.1:13000').replace(/\/+$/, '');
const API = (process.env.API_URL || `${BASE_URL}/api/v1`).replace(/\/+$/, '');
const STATE_DIR = process.env.LOADTEST_STATE_DIR || path.join(__dirname, '.state');
const STATE_FILE = path.join(STATE_DIR, process.env.LOADTEST_STATE || 'state.json');

// Máy cục bộ / mạng nội bộ: localhost, loopback, dải riêng RFC1918, tên dịch vụ Docker (không có dấu chấm), *.local.
const LOCAL_HOST = /^(localhost|host\.docker\.internal|127(\.\d{1,3}){3}|\[?::1\]?|10(\.\d{1,3}){3}|192\.168(\.\d{1,3}){2}|172\.(1[6-9]|2\d|3[01])(\.\d{1,3}){2}|[^.]+|[^.]+\.localhost|[^.]+\.local)$/i;
// Cổng mặc định của stack THẬT (compose.yaml): 3000 (frontend) và 8080 (backend). Drill dùng 13000/18080.
const MAIN_STACK_PORTS = new Set(['3000', '8080']);

/**
 * Bộ kiểm thử này TẠO người dùng/lớp học/lượt thi thật và bắn tải đột biến: chỉ được chạy trên stack drill/dev.
 * Từ chối mọi đích không phải cục bộ (trừ khi LOADTEST_ALLOW_REMOTE=1) và từ chối cổng mặc định của stack thật
 * (trừ khi LOADTEST_ALLOW_MAIN_STACK=1).
 */
function assertSafeTarget() {
  const u = new URL(API);
  if (!LOCAL_HOST.test(u.hostname) && process.env.LOADTEST_ALLOW_REMOTE !== '1') {
    console.error(
      `Từ chối chạy: API=${API} không phải máy cục bộ.\n` +
      'Bộ kiểm thử tải tạo dữ liệu thật và gây tải đột biến; KHÔNG BAO GIỜ trỏ vào production hay môi trường dùng chung.\n' +
      'Nếu đây là môi trường thử nghiệm riêng của bạn, đặt LOADTEST_ALLOW_REMOTE=1 để bỏ qua kiểm tra này.',
    );
    process.exit(2);
  }
  const isLoopback = /^(localhost|127(\.\d{1,3}){3}|\[?::1\]?|host\.docker\.internal)$/i.test(u.hostname);
  if (isLoopback && MAIN_STACK_PORTS.has(u.port) && process.env.LOADTEST_ALLOW_MAIN_STACK !== '1') {
    console.error(
      `Từ chối chạy: ${API} là cổng mặc định của stack THẬT (3000/8080).\n` +
      'Chỉ chạy tải trên stack drill (frontend 13000, backend 18080 - xem docs/RUNBOOK.md mục 5.3 và loadtest/README.md).\n' +
      'Nếu bạn chắc chắn đây là stack dev dùng một lần, đặt LOADTEST_ALLOW_MAIN_STACK=1.',
    );
    process.exit(2);
  }
}

const agents = new Map();
function agentFor(protocol, ip) {
  const key = `${protocol}|${ip || 'default'}`;
  if (!agents.has(key)) {
    const Agent = protocol === 'https:' ? https.Agent : http.Agent;
    agents.set(key, new Agent({ keepAlive: true, maxSockets: 512, localAddress: ip || undefined }));
  }
  return agents.get(key);
}

/** req(method, url, {body, token, ip, headers, timeoutMs}) -> {status, ms, json, text, headers, err}. Không bao giờ ném lỗi. */
function req(method, url, opts = {}) {
  const u = new URL(url);
  const lib = u.protocol === 'https:' ? https : http;
  const headers = Object.assign({ Accept: 'application/json' }, opts.headers || {});
  let payload = null;
  if (opts.body !== undefined) {
    payload = Buffer.from(typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body));
    headers['Content-Type'] = headers['Content-Type'] || 'application/json';
    headers['Content-Length'] = payload.length;
  }
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`;
  const t0 = process.hrtime.bigint();
  return new Promise((resolve) => {
    const r = lib.request({
      method, hostname: u.hostname, port: u.port || (u.protocol === 'https:' ? 443 : 80), path: u.pathname + u.search, headers,
      agent: agentFor(u.protocol, opts.ip), timeout: opts.timeoutMs || 120000,
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const ms = Number(process.hrtime.bigint() - t0) / 1e6;
        const text = Buffer.concat(chunks).toString('utf8');
        let json = null;
        try { json = JSON.parse(text); } catch (e) { /* không phải JSON */ }
        resolve({ status: res.statusCode, ms, json, text, headers: res.headers });
      });
    });
    r.on('timeout', () => r.destroy(new Error('timeout')));
    r.on('error', (e) => {
      const ms = Number(process.hrtime.bigint() - t0) / 1e6;
      resolve({ status: 0, ms, err: e.code || e.message, json: null, text: '', headers: {} });
    });
    if (payload) r.write(payload);
    r.end();
  });
}

/** Gọi API (đường dẫn tương đối /classes/...). Ném lỗi khi khác 2xx nếu {must: true}. */
async function call(method, apiPath, opts = {}) {
  const r = await req(method, API + apiPath, opts);
  if (opts.must && (r.status < 200 || r.status >= 300)) {
    throw new Error(`${method} ${apiPath} -> ${r.status} ${r.err || (r.text || '').slice(0, 300)}`);
  }
  return r;
}
const data = (r) => (r.json && r.json.data !== undefined ? r.json.data : null);

function pct(arr, p) {
  if (!arr.length) return null;
  const s = [...arr].sort((a, b) => a - b);
  const i = Math.min(s.length - 1, Math.max(0, Math.ceil((p / 100) * s.length) - 1));
  return Math.round(s[i]);
}

function summary(label, results) {
  const ms = results.map((r) => r.ms);
  const byStatus = {};
  for (const r of results) byStatus[r.status] = (byStatus[r.status] || 0) + 1;
  const errs = results.filter((r) => r.status === 0 || r.status >= 500).length;
  return {
    label, n: results.length, p50: pct(ms, 50), p95: pct(ms, 95), p99: pct(ms, 99),
    max: ms.length ? Math.round(Math.max(...ms)) : null, byStatus,
    errorRate: results.length ? +((errs / results.length) * 100).toFixed(2) : 0,
  };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function log(...a) { console.log(new Date().toISOString().slice(11, 23), ...a); }

function saveState(state, file = STATE_FILE) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(state, null, 2));
}
function loadState(file = STATE_FILE) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch (e) {
    console.error(`Chưa có dữ liệu chuẩn bị (${file}). Chạy \`npm run seed\` trước.`);
    process.exit(2);
    return null;
  }
}

/**
 * Thêm địa chỉ IP nguồn phụ vào eth0 của container (cần --cap-add NET_ADMIN) để mô phỏng nhiều máy khác nhau:
 * bộ giới hạn xác thực (R20-02: theo cặp IP+tài khoản, trần đăng ký 300 lần/phút/IP). Trả về danh sách IP; rỗng nếu không thêm được
 * (khi đó người gọi phải tự giãn tốc độ). Không bao giờ ném lỗi.
 */
function extraIps(count) {
  const ips = [];
  let nic = null;
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    const v4 = (list || []).find((a) => a.family === 'IPv4' && !a.internal);
    if (v4) { nic = { name, ...v4 }; break; }
  }
  if (!nic || process.platform !== 'linux') return ips;
  const octets = nic.address.split('.').map(Number);
  const prefixLen = nic.netmask.split('.').reduce((n, o) => n + Number(o).toString(2).replace(/0/g, '').length, 0);
  for (let i = 1; i <= count; i++) {
    let ip;
    if (prefixLen <= 16) ip = `${octets[0]}.${octets[1]}.${200 + Math.floor(i / 250)}.${1 + (i % 250)}`;
    else ip = `${octets[0]}.${octets[1]}.${octets[2]}.${100 + (i % 150)}`;
    try {
      execSync(`ip addr add ${ip}/${prefixLen} dev ${nic.name} 2>/dev/null`, { stdio: 'ignore' });
      ips.push(ip);
    } catch (e) {
      try { execSync(`ip addr show dev ${nic.name} | grep -q " ${ip}/"`, { stdio: 'ignore' }); ips.push(ip); } catch (e2) { /* không thêm được */ }
    }
  }
  return ips;
}

/** So sánh một chỉ số với ngưỡng; trả về mảng vi phạm (rỗng = đạt). */
function checkThresholds(checks) {
  const failures = [];
  for (const c of checks) {
    const ok = c.value !== null && c.value !== undefined && (c.max !== undefined ? c.value <= c.max : c.value === c.equals);
    const limit = c.max !== undefined ? `<= ${c.max}` : `= ${c.equals}`;
    log(`${ok ? 'PASS' : 'FAIL'}  ${c.name}: ${c.value} (ngưỡng ${limit})`);
    if (!ok) failures.push(`${c.name}: ${c.value} (ngưỡng ${limit})`);
  }
  return failures;
}

/** Đọc tham số dạng --ten giatri / --co-bat. */
function args(argv = process.argv.slice(2)) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    if (!argv[i].startsWith('--')) continue;
    const key = argv[i].slice(2);
    const next = argv[i + 1];
    if (next === undefined || next.startsWith('--')) out[key] = true;
    else { out[key] = next; i++; }
  }
  return out;
}
const num = (v, d) => (v === undefined || v === true || Number.isNaN(Number(v)) ? d : Number(v));

/** Thăm dò readiness (mỗi lần lấy 1 kết nối DB của pool) song song với tải để thấy pool có bị nghẽn không. */
function startReadinessProbe(everyMs = 100) {
  const flag = { done: false };
  const results = [];
  const loop = (async () => {
    while (!flag.done) {
      results.push(await req('GET', `${API}/health/readiness`, { timeoutMs: 30000 }));
      await sleep(everyMs);
    }
  })();
  return { stop: async () => { flag.done = true; await loop; return results; } };
}

module.exports = {
  BASE_URL, API, STATE_FILE, assertSafeTarget, req, call, data, pct, summary, sleep, log, saveState, loadState,
  extraIps, checkThresholds, args, num, startReadinessProbe,
};
