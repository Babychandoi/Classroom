import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, within, act } from '@testing-library/react';
import { ClassesPage } from '../pages/ClassesPage';
import type { Classroom } from '../types';

/**
 * D-19: explore cards carry "Riêng tư" (only ever on a class the viewer can see) and "Miễn phí" / "Trả phí · 199.000đ / 30 ngày"
 * badges, three filter chips narrow the loaded classes, and every create CTA opens /classes/new.
 */

const mockNavigate = vi.fn();
let authUser: { id: string } | null = { id: 'u1' };

vi.mock('react-router-dom', () => ({
  useNavigate: () => mockNavigate,
  Link: ({ children, to }: { children?: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: authUser, isLoading: false }),
}));

const make = (id: string, over: Partial<Classroom> = {}): Classroom =>
  ({
    id,
    ownerId: 'owner-1',
    slug: id,
    title: `Lớp ${id}`,
    status: 'ACTIVE',
    memberCount: 3,
    visibility: 'PUBLIC',
    accessType: 'FREE',
    createdAt: new Date().toISOString(),
    ...over,
  }) as Classroom;

const classes: Classroom[] = [
  make('free-public'),
  make('paid-30', {
    accessType: 'PAID',
    accessProduct: { id: 'p1', price: 199000, currency: 'VND', durationDays: 30, lifetime: false },
  }),
  make('paid-life', {
    accessType: 'PAID',
    accessProduct: { id: 'p2', price: 500000, currency: 'VND', durationDays: null, lifetime: true },
  }),
  make('private-free', { visibility: 'PRIVATE', userRole: 'OWNER' }),
];

const card = (title: string) => screen.getByRole('heading', { name: title }).closest('article') as HTMLElement;

// The home page shows curated rails; the full catalog (fee filter + paging) opens from "Xem tất cả lớp học".
const openCatalog = async () => {
  fireEvent.click(await screen.findByRole('button', { name: 'Xem tất cả lớp học' }));
  await screen.findByRole('group', { name: 'Lọc theo học phí' });
};

describe('ClassesPage badges and fee filter (D-19)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    mockNavigate.mockReset();
    authUser = { id: 'u1' };
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (input.toString().includes('/classes?')) {
        return new Response(JSON.stringify({ success: true, data: classes }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });
  });

  it('badges each card with its visibility and fee', async () => {
    render(<ClassesPage />);
    await waitFor(() => expect(screen.getAllByText('Lớp free-public').length).toBeGreaterThan(0));
    await openCatalog();

    expect(within(card('Lớp free-public')).getByText('Miễn phí')).toBeInTheDocument();
    expect(within(card('Lớp free-public')).queryByText('Riêng tư')).not.toBeInTheDocument();
    expect(within(card('Lớp paid-30')).getByText('Trả phí · 199.000đ / 30 ngày')).toBeInTheDocument();
    expect(within(card('Lớp paid-life')).getByText('Trả phí · 500.000đ / trọn đời')).toBeInTheDocument();
    expect(within(card('Lớp private-free')).getByText('Riêng tư')).toBeInTheDocument();
    expect(within(card('Lớp private-free')).getByText('Miễn phí')).toBeInTheDocument();
  });

  it('never shows "Riêng tư" on a class the server did not mark private (a PRIVATE class the viewer cannot see is not sent at all)', async () => {
    render(<ClassesPage />);
    await waitFor(() => expect(screen.getAllByText('Lớp free-public').length).toBeGreaterThan(0));
    await openCatalog();
    expect(screen.getAllByText('Riêng tư')).toHaveLength(1);
  });

  it('filters the loaded classes with the Tất cả / Miễn phí / Trả phí chips', async () => {
    render(<ClassesPage />);
    await waitFor(() => expect(screen.getAllByText('Lớp free-public').length).toBeGreaterThan(0));
    await openCatalog();

    const group = screen.getByRole('group', { name: 'Lọc theo học phí' });
    const all = within(group).getByRole('button', { name: 'Tất cả' });
    const free = within(group).getByRole('button', { name: 'Miễn phí' });
    const paid = within(group).getByRole('button', { name: 'Trả phí' });
    expect(all).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(paid);
    expect(paid).toHaveAttribute('aria-pressed', 'true');
    expect(all).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByText('Lớp paid-30')).toBeInTheDocument();
    expect(screen.getByText('Lớp paid-life')).toBeInTheDocument();
    expect(screen.queryByText('Lớp free-public')).not.toBeInTheDocument();
    expect(screen.queryByText('Lớp private-free')).not.toBeInTheDocument();

    fireEvent.click(free);
    expect(screen.getByText('Lớp free-public')).toBeInTheDocument();
    expect(screen.getByText('Lớp private-free')).toBeInTheDocument();
    expect(screen.queryByText('Lớp paid-30')).not.toBeInTheDocument();

    fireEvent.click(all);
    expect(screen.getAllByRole('heading', { level: 3 }).length).toBe(4);
  });

  it('says so (and offers a way back) when the filter matches nothing among the loaded classes', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
      new Response(JSON.stringify({ success: true, data: [make('only-free')] }), { status: 200 }));
    render(<ClassesPage />);
    await waitFor(() => expect(screen.getAllByText('Lớp only-free').length).toBeGreaterThan(0));
    await openCatalog();

    fireEvent.click(screen.getByRole('button', { name: 'Trả phí' }));
    expect(screen.getByTestId('filter-empty')).toHaveTextContent('Không có lớp nào phù hợp bộ lọc');
    fireEvent.click(screen.getByRole('button', { name: 'Xem tất cả lớp' }));
    expect(screen.getByText('Lớp only-free')).toBeInTheDocument();
  });

  it('create: every create CTA leads to the full-screen /classes/new page (no dialog any more)', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(JSON.stringify({ success: true, data: [] }), { status: 200 }));
    render(<ClassesPage />);
    await waitFor(() => expect(screen.getByText('Chưa có lớp học nào')).toBeInTheDocument());

    expect(screen.getByRole('link', { name: /Tạo lớp học mới/ })).toHaveAttribute('href', '/classes/new');
    fireEvent.click(screen.getByRole('button', { name: 'Tạo lớp học' }));
    expect(mockNavigate).toHaveBeenCalledWith('/classes/new');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
