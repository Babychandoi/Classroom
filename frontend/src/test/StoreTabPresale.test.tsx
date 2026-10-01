import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { StoreTab } from '../pages/classroom/StoreTab';
import { resetCheckoutAvailabilityCache } from '../api/payments';
import type { Classroom, Product } from '../types';

/**
 * R19-06: a buyer whose only entitlement starts in the future (pre-sale purchase) already owns the product. The
 * store must say so ("Đã mua — bắt đầu từ dd/MM/yyyy") instead of showing "Mua ngay" as if nothing was bought;
 * a further purchase is a renewal ("Gia hạn thêm"), which the backend stacks after the paid period.
 */

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
const mockRefreshClassroom = vi.fn().mockResolvedValue(undefined);

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: mockClassroom, refreshClassroom: mockRefreshClassroom }),
  useNavigate: () => vi.fn(),
}));
vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: mockUser }),
}));

const base: Product = {
  id: 'prod-1',
  classId: 'class-1',
  title: 'Khóa mở bán sớm',
  status: 'PUBLISHED',
  price: 100000,
  currency: 'VND',
  durationDays: 30,
};

function mockProducts(products: Product[]) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = input.toString();
    if (url.includes('/payments/sandbox-status')) {
      return new Response(JSON.stringify({ success: true, data: { checkoutAvailable: true } }), { status: 200 });
    }
    if (url.includes('/me/orders')) {
      return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
    }
    if (url.includes('/products')) {
      return new Response(JSON.stringify({ success: true, data: products }), { status: 200 });
    }
    return new Response('{}', { status: 404 });
  });
}

describe('StoreTab — pre-sale purchase (R19-06)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    resetCheckoutAvailabilityCache();
    window.alert = vi.fn();
  });

  it('shows "Đã mua — bắt đầu từ dd/MM/yyyy" and a renewal CTA, not "Mua ngay", for an owned-upcoming product', async () => {
    mockProducts([{ ...base, userOwnsUpcoming: true, entitlementStartsAt: '2026-10-15T12:00:00Z', entitlementExpiresAt: '2026-11-14T12:00:00Z' }]);

    render(<StoreTab />);

    await waitFor(() => expect(screen.getByText('Đã mua — bắt đầu từ 15/10/2026')).toBeInTheDocument());
    expect(screen.getByText('Gia hạn thêm')).toBeInTheDocument();
    expect(screen.queryByText('Mua ngay')).not.toBeInTheDocument();
    expect(screen.queryByText('Đang sở hữu')).not.toBeInTheDocument();
    expect(screen.getByText('Hết hạn: 14/11/2026')).toBeInTheDocument();
  });

  it('keeps "Đang sở hữu" for a running entitlement and "Mua ngay" for a product the viewer does not own', async () => {
    mockProducts([
      { ...base, id: 'prod-active', title: 'Đang chạy', userHasActiveEntitlement: true, entitlementExpiresAt: '2026-12-01T12:00:00Z' },
      { ...base, id: 'prod-free', title: 'Chưa mua' },
    ]);

    render(<StoreTab />);

    await waitFor(() => expect(screen.getByText('Đang sở hữu')).toBeInTheDocument());
    expect(screen.getAllByText('Mua ngay')).toHaveLength(1);
    expect(screen.getAllByText('Gia hạn thêm')).toHaveLength(1);
    expect(screen.queryByText(/Đã mua — bắt đầu từ/)).not.toBeInTheDocument();
  });
});
