import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { StudioStore } from '../pages/studio/StudioStore';
import type { Classroom, Order } from '../types';

/**
 * The sandbox refund action, like the sandbox settle action, only exists while the server reports the mock
 * checkout as available - a PAID MOCK order on a server without the sandbox must not offer "Hoàn tiền sandbox".
 */

let sandboxAvailable = false;
vi.mock('../api/payments', () => ({
  fetchCheckoutAvailability: () => Promise.resolve(sandboxAvailable),
}));

let classroom: Classroom;
vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom }),
  Link: ({ children, to }: { children?: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

const baseClassroom = {
  id: 'class-1', ownerId: 'owner-1', slug: 'demo-class', title: 'Demo Class', status: 'ACTIVE', memberCount: 3,
  userRole: 'OWNER', studioPermissions: [], studioScopedPermissions: [], createdAt: new Date().toISOString(),
} as Classroom;

const paidOrder = { id: 'o1', orderNumber: 'ORD-PAID-1', status: 'PAID', provider: 'MOCK', totalAmount: 199000, createdAt: '2026-10-01T08:00:00Z' } as Order;
const pendingOrder = { id: 'o2', orderNumber: 'ORD-PEND-2', status: 'PENDING', provider: 'MOCK', totalAmount: 99000, createdAt: '2026-10-02T08:00:00Z' } as Order;

const mockServer = (onSimulate?: (body: any) => void) =>
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = input.toString();
    if (url.includes('/studio/products')) return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
    if (url.includes('/courses')) return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
    if (url.includes('/orders')) return new Response(JSON.stringify({ success: true, data: [paidOrder, pendingOrder] }), { status: 200 });
    if (url.includes('/payments/mock/simulate')) {
      onSimulate?.(JSON.parse(String(init?.body)));
      return new Response(JSON.stringify({ success: true, data: {} }), { status: 200 });
    }
    return new Response('{}', { status: 404 });
  });

describe('StudioStore — sandbox refund gating', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    classroom = baseClassroom;
  });

  it('hides both sandbox actions when the sandbox is disabled', async () => {
    sandboxAvailable = false;
    mockServer();
    render(<StudioStore />);
    await screen.findByText('ORD-PAID-1');
    expect(screen.queryByRole('button', { name: 'Hoàn tiền sandbox' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Xác nhận thanh toán sandbox' })).not.toBeInTheDocument();
  });

  it('offers the refund when the sandbox is enabled, asks first, then simulates PAYMENT_REFUNDED', async () => {
    sandboxAvailable = true;
    let simulated: any = null;
    mockServer((body) => { simulated = body; });
    render(<StudioStore />);

    fireEvent.click(await screen.findByRole('button', { name: 'Hoàn tiền sandbox' }));
    const dialog = await screen.findByRole('alertdialog');
    expect(dialog).toHaveTextContent('ORD-PAID-1');
    expect(simulated).toBeNull();

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Hoàn tiền' }));
    });
    await waitFor(() => expect(simulated).toEqual({ orderNumber: 'ORD-PAID-1', eventType: 'PAYMENT_REFUNDED' }));
    expect(screen.getByRole('button', { name: 'Xác nhận thanh toán sandbox' })).toBeInTheDocument();
  });

  it('does not offer the refund to staff without STORE:EDIT even with the sandbox on', async () => {
    sandboxAvailable = true;
    classroom = { ...baseClassroom, userRole: 'STAFF', studioPermissions: ['STORE:VIEW'] } as Classroom;
    mockServer();
    render(<StudioStore />);
    await screen.findByText('ORD-PAID-1');
    expect(screen.queryByRole('button', { name: 'Hoàn tiền sandbox' })).not.toBeInTheDocument();
  });
});
