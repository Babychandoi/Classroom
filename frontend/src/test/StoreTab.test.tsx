import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, act, waitFor } from '@testing-library/react';
import { StoreTab } from '../pages/classroom/StoreTab';
import { resetCheckoutAvailabilityCache } from '../api/payments';
import type { Classroom, Product } from '../types';

const mockNavigate = vi.fn();
const mockRefreshClassroom = vi.fn().mockResolvedValue(undefined);

// Stable object identities across renders: StoreTab's data-fetch effect depends on
// classroom.id/user, so a mock that returns a fresh object literal on every render would make
// the effect re-fire (and, transitively, the component re-render) forever.
const mockClassroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 1,
  createdAt: new Date().toISOString(),
};

const mockUser = { id: 'u1', fullName: 'Buyer', email: 'buyer@test.local', role: 'STUDENT', status: 'ACTIVE' };

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({
    classroom: mockClassroom,
    refreshClassroom: mockRefreshClassroom,
  }),
  useNavigate: () => mockNavigate,
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: mockUser }),
}));

const product: Product = {
  id: 'prod-1',
  classId: 'class-1',
  title: 'PRO Package',
  status: 'ACTIVE',
  price: 100000,
  currency: 'VND',
  durationDays: 30,
};

describe('StoreTab idempotency key handling', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    window.alert = vi.fn();
  });

  it('keeps the idempotency key after a network/5xx error so a retry reuses it', async () => {
    let capturedKey: string | null = null;
    let callCount = 0;

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      if (url.includes('/products')) {
        return new Response(JSON.stringify({ success: true, data: [product] }), { status: 200 });
      }
      if (url.includes('/payments/sandbox-status')) {
        return new Response(JSON.stringify({ success: true, data: { checkoutAvailable: true } }), { status: 200 });
      }
      // R13-06: StoreTab also fetches "Đơn hàng của tôi" (GET /me/orders) — match it before the
      // general /orders check below (order creation), which only counts POST /orders.
      if (url.includes('/me/orders')) {
        return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
      }
      if (url.includes('/orders') && init?.method === 'POST') {
        callCount += 1;
        const headers = init?.headers as Headers;
        capturedKey = headers.get('Idempotency-Key');
        // Simulate a transient 500 on the first attempt.
        return new Response(JSON.stringify({ error: { code: 'INTERNAL', message: 'boom' } }), { status: 500 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StoreTab />);

    await waitFor(() => expect(screen.getByText('Nhận quyền lợi')).toBeInTheDocument());

    await act(async () => {
      screen.getByText('Nhận quyền lợi').click();
    });

    await waitFor(() => expect(callCount).toBe(1));
    const firstKey = capturedKey;
    expect(firstKey).toBeTruthy();

    // Retry: the same key must be reused because the previous failure was a 5xx, not a definitive
    // rejection - the original request may already have been accepted server-side.
    await act(async () => {
      screen.getByText('Nhận quyền lợi').click();
    });

    await waitFor(() => expect(callCount).toBe(2));
    expect(capturedKey).toBe(firstKey);
  });

  it('clears the idempotency key after a definitive 4xx so a retry mints a new one', async () => {
    let capturedKey: string | null = null;
    let callCount = 0;

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      if (url.includes('/products')) {
        return new Response(JSON.stringify({ success: true, data: [product] }), { status: 200 });
      }
      if (url.includes('/payments/sandbox-status')) {
        return new Response(JSON.stringify({ success: true, data: { checkoutAvailable: true } }), { status: 200 });
      }
      if (url.includes('/me/orders')) {
        return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
      }
      if (url.includes('/orders') && init?.method === 'POST') {
        callCount += 1;
        const headers = init?.headers as Headers;
        capturedKey = headers.get('Idempotency-Key');
        return new Response(JSON.stringify({ error: { code: 'BAD_REQUEST', message: 'invalid' } }), { status: 400 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StoreTab />);

    await waitFor(() => expect(screen.getByText('Nhận quyền lợi')).toBeInTheDocument());

    await act(async () => {
      screen.getByText('Nhận quyền lợi').click();
    });

    await waitFor(() => expect(callCount).toBe(1));
    const firstKey = capturedKey;
    expect(firstKey).toBeTruthy();

    await act(async () => {
      screen.getByText('Nhận quyền lợi').click();
    });

    await waitFor(() => expect(callCount).toBe(2));
    expect(capturedKey).not.toBe(firstKey);
  });
});

// R17-06: in a deployment without the sandbox payment controller GET /payments/sandbox-status is a 404 the
// browser logs as a console error; it used to be requested on every (re)load of the tab, up to 3 times.
describe('StoreTab sandbox-status lookup (R17-06)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    resetCheckoutAvailabilityCache();
    window.alert = vi.fn();
  });

  it('asks once per page load even when the tab is mounted again, and shows checkout as unavailable on a 404', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/products')) {
        return new Response(JSON.stringify({ success: true, data: [product] }), { status: 200 });
      }
      if (url.includes('/payments/sandbox-status')) {
        return new Response(JSON.stringify({ error: { code: 'NOT_FOUND', message: 'Không tìm thấy' } }), { status: 404 });
      }
      if (url.includes('/me/orders')) {
        return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });
    const sandboxCalls = () => spy.mock.calls.filter(([u]) => String(u).includes('/payments/sandbox-status')).length;

    const first = render(<StoreTab />);
    await waitFor(() => expect(screen.getByText('Tạm chưa hỗ trợ thanh toán')).toBeInTheDocument());
    first.unmount();

    render(<StoreTab />);
    await waitFor(() => expect(screen.getByText('Tạm chưa hỗ trợ thanh toán')).toBeInTheDocument());

    expect(sandboxCalls()).toBe(1);
  });
});
