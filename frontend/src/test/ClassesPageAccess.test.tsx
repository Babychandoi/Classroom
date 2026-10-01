import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, within, act } from '@testing-library/react';
import { ClassesPage } from '../pages/ClassesPage';
import type { Classroom } from '../types';

/**
 * D-19: explore cards carry "Riêng tư" (only ever on a class the viewer can see) and "Miễn phí" / "Trả phí · 199.000đ / 30 ngày"
 * badges, three filter chips narrow the loaded classes, and the create dialog asks whether the class is public or private.
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

const card = (title: string) => screen.getByRole('heading', { name: title }).closest('div.group') as HTMLElement;

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
    await waitFor(() => expect(screen.getByText('Lớp free-public')).toBeInTheDocument());

    expect(within(card('Lớp free-public')).getByText('Miễn phí')).toBeInTheDocument();
    expect(within(card('Lớp free-public')).queryByText('Riêng tư')).not.toBeInTheDocument();
    expect(within(card('Lớp paid-30')).getByText('Trả phí · 199.000đ / 30 ngày')).toBeInTheDocument();
    expect(within(card('Lớp paid-life')).getByText('Trả phí · 500.000đ / trọn đời')).toBeInTheDocument();
    expect(within(card('Lớp private-free')).getByText('Riêng tư')).toBeInTheDocument();
    expect(within(card('Lớp private-free')).getByText('Miễn phí')).toBeInTheDocument();
  });

  it('never shows "Riêng tư" on a class the server did not mark private (a PRIVATE class the viewer cannot see is not sent at all)', async () => {
    render(<ClassesPage />);
    await waitFor(() => expect(screen.getByText('Lớp free-public')).toBeInTheDocument());
    expect(screen.getAllByText('Riêng tư')).toHaveLength(1);
  });

  it('filters the loaded classes with the Tất cả / Miễn phí / Trả phí chips', async () => {
    render(<ClassesPage />);
    await waitFor(() => expect(screen.getByText('Lớp free-public')).toBeInTheDocument());

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
    await waitFor(() => expect(screen.getByText('Lớp only-free')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'Trả phí' }));
    expect(screen.getByTestId('filter-empty')).toHaveTextContent('Không có lớp nào phù hợp bộ lọc');
    fireEvent.click(screen.getByRole('button', { name: 'Xem tất cả lớp' }));
    expect(screen.getByText('Lớp only-free')).toBeInTheDocument();
  });

  it('create dialog: public by default with a one-line explanation per choice', async () => {
    let posted: any = null;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      if (url.endsWith('/classes') && init?.method === 'POST') {
        posted = JSON.parse(String(init.body));
        return new Response(JSON.stringify({ success: true, data: make('lop-moi', { slug: 'lop-moi' }) }), { status: 200 });
      }
      return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
    });
    render(<ClassesPage />);
    await waitFor(() => expect(screen.getByText('Chưa có lớp học nào')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /Tạo lớp học mới/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Tạo lớp học mới' });

    const publicRadio = within(dialog).getByRole('radio', { name: /Công khai/ });
    const privateRadio = within(dialog).getByRole('radio', { name: /Riêng tư/ });
    expect(publicRadio).toBeChecked();
    expect(privateRadio).not.toBeChecked();
    expect(within(dialog).getByText(/Hiện trong danh sách khám phá/)).toBeInTheDocument();
    expect(within(dialog).getByText(/chỉ người có liên kết mời của bạn mới vào được/)).toBeInTheDocument();

    fireEvent.click(privateRadio);
    expect(privateRadio).toBeChecked();
    fireEvent.change(within(dialog).getByLabelText('Tên lớp học'), { target: { value: 'Lớp Mới' } });
    fireEvent.change(within(dialog).getByLabelText('Đường dẫn slug (URL)'), { target: { value: 'lop-moi' } });
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Xác nhận tạo lớp' }));
    });

    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted).toEqual({ title: 'Lớp Mới', slug: 'lop-moi', description: '', visibility: 'PRIVATE' });
    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/classes/lop-moi/feed'));
  });

  it('create dialog: a failure is shown inline in the dialog, not in an alert box', async () => {
    const alertSpy = vi.fn();
    window.alert = alertSpy;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      if (input.toString().endsWith('/classes') && init?.method === 'POST') {
        return new Response(JSON.stringify({ success: false, error: { code: 'CONFLICT', message: 'Đường dẫn đã được dùng' } }), { status: 409 });
      }
      return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
    });
    render(<ClassesPage />);
    await waitFor(() => expect(screen.getByText('Chưa có lớp học nào')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: /Tạo lớp học mới/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Tên lớp học'), { target: { value: 'A' } });
    fireEvent.change(within(dialog).getByLabelText('Đường dẫn slug (URL)'), { target: { value: 'a' } });
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Xác nhận tạo lớp' }));
    });

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Đường dẫn đã được dùng');
    expect(alertSpy).not.toHaveBeenCalled();
    expect(mockNavigate).not.toHaveBeenCalled();
  });
});
