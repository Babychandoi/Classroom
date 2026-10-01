'use strict';
const assert = require('node:assert/strict');
(async () => {
  const email = `multinode.${Date.now()}@example.com`;
  const statuses = [];
  for (let i = 0; i < 6; i++) {
    const port = i % 2 ? 28080 : 8080;
    const res = await fetch(`http://localhost:${port}/api/v1/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password: 'WrongPass123!' }) });
    await res.text(); statuses.push({ port, status: res.status });
    // Let the response-finalization hook persist the failed attempt before the next request.
    await new Promise(resolve => setTimeout(resolve, 150));
  }
  assert.deepEqual(statuses.map(r => r.status), [401, 401, 401, 401, 401, 429]);
  console.log('PASS shared login failure lockout across both live backends', statuses);
})().catch(e => { console.error(e); process.exitCode = 1; });
