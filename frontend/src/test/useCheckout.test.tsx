import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { useCheckout } from '../hooks/useCheckout';
import { resetCheckoutAvailabilityCache } from '../api/payments';

/**
 * The shared purchase flow (extracted from StoreTab): the idempotency key semantics (R3) are kept - the key survives a network error
 * or 5xx so a retry reuses it, and is dropped after a success or a definitive 4xx - and are now per (product, invite code).
 */

const item = { id: 'prod-1', title: 'Gói vào lớp', durationDays: 30 };
const order = (status = 'PENDING') => ({ id: 'o1', orderNumber: 'ORD-1', status, totalAmount: 1, currency: 'VND', buyerId: 'u', classId: 'c', provider: 'MOCK', createdAt: '' });
const ok = (data: unknown) => new Response(JSON.stringify({ success: true, data }), { status: 200 });
const err = (status: number) => new Response(JSON.stringify({ success: false, error: { code: status >= 500 ? 'INTERNAL' : 'BAD_REQUEST', message: 'nope' } }), { status });

let keys: string[];
let bodies: any[];
let queue: Response[];

beforeEach(() => {
  vi.restoreAllMocks();
  resetCheckoutAvailabilityCache();
  keys = [];
  bodies = [];
  queue = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = input.toString();
    if (url.includes('/payments/sandbox-status')) return ok({ checkoutAvailable: true });
    if (url.endsWith('/orders') && init?.method === 'POST') {
      bodies.push(JSON.parse(String(init.body)));
      keys.push((init.headers as Headers).get('Idempotency-Key') as string);
      return queue.shift() ?? ok(order());
    }
    if (url.endsWith('/orders/o1') && (init?.method ?? 'GET') === 'GET') return ok(order('PAID'));
    if (url.endsWith('/orders/o1/cancel')) return ok(order('CANCELLED'));
    return new Response('{}', { status: 404 });
  });
});

describe('useCheckout idempotency key', () => {
  it('reuses the key after a 5xx and after a network error', async () => {
    queue = [err(500)];
    const { result } = renderHook(() => useCheckout({ classId: 'c', checkAvailability: false }));

    let first: any;
    await act(async () => { first = await result.current.buy(item); });
    expect(first.ok).toBe(false);
    expect(first.definitive).toBe(false);
    // the selection is kept so the retry is recognised as the same attempt
    expect(result.current.item?.id).toBe('prod-1');

    (globalThis.fetch as any).mockRejectedValueOnce(new TypeError('Failed to fetch'));
    await act(async () => { await result.current.buy(item); });
    await act(async () => { await result.current.buy(item); });

    expect(keys).toHaveLength(2); // the network failure never reached the mocked route handler
    expect(keys[1]).toBe(keys[0]);
  });

  it('drops the key after a definitive 4xx, so the next attempt mints a new one', async () => {
    queue = [err(400)];
    const { result } = renderHook(() => useCheckout({ classId: 'c', checkAvailability: false }));

    let first: any;
    await act(async () => { first = await result.current.buy(item); });
    expect(first.definitive).toBe(true);
    expect(result.current.item).toBeNull();

    await act(async () => { await result.current.buy(item); });
    expect(keys[1]).not.toBe(keys[0]);
  });

  it('drops the key after a success (the order exists)', async () => {
    const { result } = renderHook(() => useCheckout({ classId: 'c', checkAvailability: false }));
    await act(async () => { await result.current.buy(item); });
    expect(result.current.order?.orderNumber).toBe('ORD-1');
    await act(async () => { await result.current.buy(item); });
    expect(keys[1]).not.toBe(keys[0]);
  });

  it('keys per invite code: same code reuses the key, another code (or none) does not', async () => {
    queue = [err(503), err(503), err(503)];
    const { result } = renderHook(() => useCheckout({ classId: 'c', checkAvailability: false }));

    await act(async () => { await result.current.buy(item, { inviteCode: 'CODE-A-0123456789' }); });
    await act(async () => { await result.current.buy(item, { inviteCode: 'CODE-A-0123456789' }); });
    expect(keys[1]).toBe(keys[0]);

    await act(async () => { await result.current.buy(item, { inviteCode: 'CODE-B-0123456789' }); });
    expect(keys[2]).not.toBe(keys[0]);
    expect(bodies[2].inviteCode).toBe('CODE-B-0123456789');
  });

  it('sends the same key in the body and the Idempotency-Key header, and omits inviteCode when there is none', async () => {
    const { result } = renderHook(() => useCheckout({ classId: 'c', checkAvailability: false }));
    await act(async () => { await result.current.buy(item); });
    expect(bodies[0]).toEqual({ classId: 'c', productId: 'prod-1', idempotencyKey: keys[0] });
    expect('inviteCode' in bodies[0]).toBe(false);
  });
});

describe('useCheckout lifecycle', () => {
  it('reports each change to the caller and keeps a failing follow-up from failing the purchase', async () => {
    const changes: string[] = [];
    const { result } = renderHook(() =>
      useCheckout({
        classId: 'c',
        checkAvailability: false,
        onChanged: async (change) => {
          changes.push(change);
          if (change === 'created') throw new Error('refresh blew up');
        },
      }));

    let bought: any;
    await act(async () => { bought = await result.current.buy(item); });
    expect(bought.ok).toBe(true);
    expect(result.current.order?.status).toBe('PENDING');

    await act(async () => { await result.current.refreshOrderStatus(); });
    expect(result.current.order?.status).toBe('PAID');

    await act(async () => { await result.current.cancelOrder(); });
    expect(changes).toEqual(['created', 'refreshed', 'cancelled']);
  });

  it('close() clears the order and selection (but not while a request is running)', async () => {
    const { result } = renderHook(() => useCheckout({ classId: 'c', checkAvailability: false }));
    await act(async () => { await result.current.buy(item); });
    act(() => result.current.close());
    expect(result.current.order).toBeNull();
    expect(result.current.item).toBeNull();
  });

  it('asks whether checkout exists only when told to (a plain page view makes no payment request)', async () => {
    const quiet = renderHook(() => useCheckout({ classId: 'c', checkAvailability: false }));
    await act(async () => { await Promise.resolve(); });
    expect(quiet.result.current.checkoutAvailable).toBeNull();
    expect((globalThis.fetch as any).mock.calls.some(([u]: [string]) => String(u).includes('sandbox-status'))).toBe(false);

    const asking = renderHook(() => useCheckout({ classId: 'c' }));
    await waitFor(() => expect(asking.result.current.checkoutAvailable).toBe(true));
  });

  it('refuses to buy without a class', async () => {
    const { result } = renderHook(() => useCheckout({ classId: undefined, checkAvailability: false }));
    let res: any;
    await act(async () => { res = await result.current.buy(item); });
    expect(res.ok).toBe(false);
    expect(keys).toHaveLength(0);
  });
});
