import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { StoreTab } from '../pages/classroom/StoreTab';
import { resetCheckoutAvailabilityCache } from '../api/payments';
import type { Classroom, Product } from '../types';

const mockNavigate = vi.fn();
const mockRefreshClassroom = vi.fn().mockResolvedValue(undefined);

const mockClassroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 1,
  createdAt: new Date().toISOString(),
};

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: mockClassroom, refreshClassroom: mockRefreshClassroom }),
  useNavigate: () => mockNavigate,
}));

// A guest: the public store page must work without a session.
vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: null }),
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

describe('StoreTab as a guest (not signed in)', () => {
  let requestedUrls: string[];

  beforeEach(() => {
    vi.restoreAllMocks();
    mockNavigate.mockClear();
    resetCheckoutAvailabilityCache();
    requestedUrls = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      requestedUrls.push(url);
      if (url.includes('/products')) {
        return new Response(JSON.stringify({ success: true, data: [product] }), { status: 200 });
      }
      if (url.includes('/payments/sandbox-status')) {
        return new Response(JSON.stringify({ success: true, data: { checkoutAvailable: true } }), { status: 200 });
      }
      // What the real API does for a guest: 401. The page must not even ask.
      return new Response(
        JSON.stringify({ success: false, error: { code: 'UNAUTHORIZED', message: 'Chưa đăng nhập hoặc phiên đã hết hạn' } }),
        { status: 401 },
      );
    });
  });

  it('does not request the personal order list and shows no red error', async () => {
    render(<StoreTab />);

    await waitFor(() => expect(screen.getByText('PRO Package')).toBeInTheDocument());

    expect(requestedUrls.some((u) => u.includes('/me/orders'))).toBe(false);
    expect(screen.queryByText(/Chưa đăng nhập hoặc phiên đã hết hạn/)).toBeNull();
    expect(screen.queryByText('Thử lại')).toBeNull();
  });

  it('invites the guest to sign in and returns them to the store afterwards', async () => {
    render(<StoreTab />);

    await waitFor(() => expect(screen.getByText(/Đăng nhập để xem và quản lý các đơn hàng/)).toBeInTheDocument());

    // Two "Đăng nhập" call-to-actions may exist (store cards/buy button); use the one in the orders panel.
    const prompt = screen.getByText(/Đăng nhập để xem và quản lý các đơn hàng/);
    const button = prompt.parentElement!.querySelector('button')!;
    fireEvent.click(button);

    expect(mockNavigate).toHaveBeenCalledWith('/login', {
      state: { from: { pathname: '/classes/demo-class/store' } },
    });
  });

  it('does not show the empty-orders text or a spinner for a guest', async () => {
    render(<StoreTab />);
    await waitFor(() => expect(screen.getByText('PRO Package')).toBeInTheDocument());

    expect(screen.queryByText(/Bạn chưa có đơn hàng nào/)).toBeNull();
    expect(screen.queryByText(/Đang tải đơn hàng của bạn/)).toBeNull();
  });
});
