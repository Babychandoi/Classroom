// Kịch bản tải tệp: P luồng song song {xin URL tải lên -> PUT SIZE MB thẳng vào MinIO -> /media/{id}/complete -> URL tải xuống -> GET},
// đo p95 của bước "complete" (server stat + copy + kiểm tra magic byte) và readiness trong lúc đó.
// CHẠY TỪ MÁY CHỦ (host) bằng Node cục bộ: URL đã ký trỏ tới MINIO_EXTERNAL_ENDPOINT (drill: http://localhost:19000), không truy cập được từ trong mạng Docker.
//   node scenarios/media-upload.mjs --parallel 50 --size 20
// Ngưỡng: --max-complete-p95 3000 --max-probe-p99 1000
import { createRequire } from 'node:module';
import fs from 'node:fs';
const require = createRequire(import.meta.url);
const { API, assertSafeTarget, args, num, pct, loadState, saveState, checkThresholds } = require('../lib');
const { ensureTokens } = require('../fixtures');

assertSafeTarget();
const a = args();
const P = num(a.parallel, 50);
const SIZE = num(a.size, 20) * 1024 * 1024;
const thresholds = { completeP95: num(a['max-complete-p95'], 3000), probeP99: num(a['max-probe-p99'], 1000) };
const state = loadState();
await ensureTokens(state, saveState);
const H = { Authorization: `Bearer ${state.owner.token}`, 'Content-Type': 'application/json' };

const sum = (label, rs) => {
  const ms = rs.map((r) => r.ms);
  const st = {};
  rs.forEach((r) => { st[r.status] = (st[r.status] || 0) + 1; });
  return { label, n: rs.length, p50: pct(ms, 50), p95: pct(ms, 95), p99: pct(ms, 99), max: ms.length ? Math.round(Math.max(...ms)) : null, byStatus: st };
};
async function timed(fn) {
  const t0 = performance.now();
  try { return { ...(await fn()), ms: performance.now() - t0 }; } catch (e) { return { status: 0, err: String(e.cause?.code || e.message), ms: performance.now() - t0 }; }
}

const body = Buffer.alloc(SIZE, 0x20);
body.write('%PDF-1.4\n', 0);

let probeDone = false;
const probe = [];
const control = []; // /health does not touch the database: the same network path, so it separates "DB pool" from "host network saturated by 1 GB of uploads"
const probeLoop = (async () => {
  while (!probeDone) {
    probe.push(await timed(async () => { const r = await fetch(`${API}/health/readiness`); await r.arrayBuffer(); return { status: r.status }; }));
    control.push(await timed(async () => { const r = await fetch(`${API}/health`); await r.arrayBuffer(); return { status: r.status }; }));
    await new Promise((r) => setTimeout(r, 200));
  }
})();

const flows = await Promise.all(Array.from({ length: P }, async (_, i) => {
  const intent = await timed(async () => {
    const r = await fetch(`${API}/classes/${state.classId}/media/upload-intents`, { method: 'POST', headers: H, body: JSON.stringify({ purpose: 'MEDIA', filename: `loadtest-${i}.pdf`, mimeType: 'application/pdf', sizeBytes: SIZE }) });
    return { status: r.status, j: await r.json() };
  });
  if (intent.status !== 200) return { intent };
  const d = intent.j.data;
  const put = await timed(async () => { const r = await fetch(d.uploadUrl, { method: 'PUT', headers: { 'Content-Type': 'application/pdf' }, body }); await r.arrayBuffer(); return { status: r.status }; });
  const complete = await timed(async () => { const r = await fetch(`${API}/media/${d.assetId}/complete`, { method: 'POST', headers: H }); return { status: r.status, j: await r.json() }; });
  return { intent, put, complete };
}));
probeDone = true;
await probeLoop;

const completes = flows.filter((f) => f.complete).map((f) => f.complete);
const out = {
  parallel: P, sizeMB: SIZE / 1048576, thresholds,
  intent: sum('upload-intent', flows.map((f) => f.intent)),
  put: sum('PUT presigned', flows.filter((f) => f.put).map((f) => f.put)),
  complete: sum('complete (stat+copy+magic)', completes),
  probe: sum('readiness trong lúc tải tệp', probe),
  control: sum('/health (không dùng DB) cùng lúc - độ trễ mạng nền', control),
};
console.log(JSON.stringify(out, null, 2));
if (a.json) fs.writeFileSync(a.json, JSON.stringify(out, null, 2) + '\n');
const failures = checkThresholds([
  { name: 'intent: số thành công', value: flows.filter((f) => f.intent.status === 200).length, equals: P },
  { name: 'PUT: số thành công', value: flows.filter((f) => f.put?.status === 200).length, equals: P },
  { name: 'complete: số kết quả', value: completes.length, equals: P },
  { name: 'complete: số lỗi (khác 200)', value: completes.filter((c) => c.status !== 200).length, equals: 0 },
  { name: 'complete: p95 (ms)', value: out.complete.p95, max: thresholds.completeP95 },
  { name: 'readiness: số lần không 200', value: probe.filter((r) => r.status !== 200).length, equals: 0 },
  // readiness may be no slower than the DB-free control probe (same network path) plus 300 ms, or under the absolute threshold
  { name: 'readiness: p99 (ms) (<= max(ngưỡng, /health p99 + 300))', value: out.probe.p99, max: Math.max(thresholds.probeP99, out.control.p99 + 300) },
]);
process.exit(failures.length ? 1 : 0);
