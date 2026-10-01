import { describe, it, expect, beforeEach, vi } from 'vitest';
import { fetchCheckoutAvailability, resetCheckoutAvailabilityCache } from '../api/payments';

/**
 * R17-06: GET /payments/sandbox-status is a 404 wherever the sandbox payment controller is not running,
 * and every 404 shows up as a console resource error. It is a deployment-wide fact, so it is asked once
 * per page load; only a transient failure is worth asking again.
 */

const statusCalls = (spy: { mock: { calls: unknown[][] } }) =>
  spy.mock.calls.filter(([u]) => String(u).includes('/payments/sandbox-status')).length;

describe('fetchCheckoutAvailability (R17-06)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    resetCheckoutAvailabilityCache();
  });

  it('asks the server once for any number of callers and shares the answer', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
      new Response(JSON.stringify({ success: true, data: { checkoutAvailable: true } }), { status: 200 }));

    const answers = await Promise.all([fetchCheckoutAvailability(), fetchCheckoutAvailability()]);
    answers.push(await fetchCheckoutAvailability());

    expect(answers).toEqual([true, true, true]);
    expect(statusCalls(spy)).toBe(1);
  });

  it('remembers a 404 (no sandbox in this deployment) instead of re-requesting it on every mount', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
      new Response(JSON.stringify({ error: { code: 'NOT_FOUND', message: 'Không tìm thấy' } }), { status: 404 }));

    expect(await fetchCheckoutAvailability()).toBe(false);
    expect(await fetchCheckoutAvailability()).toBe(false);
    expect(await fetchCheckoutAvailability()).toBe(false);

    expect(statusCalls(spy)).toBe(1);
  });

  it('does not remember a transient failure, so the next caller retries', async () => {
    let call = 0;
    const spy = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      call += 1;
      if (call === 1) return new Response(JSON.stringify({ error: { code: 'INTERNAL', message: 'boom' } }), { status: 500 });
      return new Response(JSON.stringify({ success: true, data: { checkoutAvailable: true } }), { status: 200 });
    });

    expect(await fetchCheckoutAvailability()).toBe(false);
    expect(await fetchCheckoutAvailability()).toBe(true);
    expect(statusCalls(spy)).toBe(2);
  });
});
