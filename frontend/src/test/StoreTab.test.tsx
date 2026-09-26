import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, act, waitFor } from '@testing-library/react';
import { StoreTab } from '../pages/classroom/StoreTab';
import type { Classroom, Product } from '../types';

const mockNavigate = vi.fn();

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({
    classroom: {
      id: 'class-1',
      ownerId: 'owner-1',
      slug: 'demo-class',
      title: 'Demo Class',
      status: 'ACTIVE',
      memberCount: 1,
      createdAt: new Date().toISOString(),
    } as Classroom,
    refreshClassroom: vi.fn().mockResolvedValue(undefined),
  }),
  useNavigate: () => mockNavigate,
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'u1', fullName: 'Buyer', email: 'buyer@test.local', role: 'STUDENT', status: 'ACTIVE' } }),
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
      if (url.includes('/orders')) {
        callCount += 1;
        const headers = init?.headers as Headers;
        capturedKey = headers.get('Idempotency-Key');
        // Simulate a transient 500 on the first attempt.
        return new Response(JSON.stringify({ error: { code: 'INTERNAL', message: 'boom' } }), { status: 500 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StoreTab />);

    await waitFor(() => expect(screen.getByText('Mua ngay')).toBeInTheDocument());

    await act(async () => {
      screen.getByText('Mua ngay').click();
    });

    await waitFor(() => expect(callCount).toBe(1));
    const firstKey = capturedKey;
    expect(firstKey).toBeTruthy();

    // Retry: the same key must be reused because the previous failure was a 5xx, not a definitive
    // rejection - the original request may already have been accepted server-side.
    await act(async () => {
      screen.getByText('Mua ngay').click();
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
      if (url.includes('/orders')) {
        callCount += 1;
        const headers = init?.headers as Headers;
        capturedKey = headers.get('Idempotency-Key');
        return new Response(JSON.stringify({ error: { code: 'BAD_REQUEST', message: 'invalid' } }), { status: 400 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StoreTab />);

    await waitFor(() => expect(screen.getByText('Mua ngay')).toBeInTheDocument());

    await act(async () => {
      screen.getByText('Mua ngay').click();
    });

    await waitFor(() => expect(callCount).toBe(1));
    const firstKey = capturedKey;
    expect(firstKey).toBeTruthy();

    await act(async () => {
      screen.getByText('Mua ngay').click();
    });

    await waitFor(() => expect(callCount).toBe(2));
    expect(capturedKey).not.toBe(firstKey);
  });
});
