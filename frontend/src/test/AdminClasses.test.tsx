import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AdminClasses } from '../pages/admin/AdminClasses';
import { AdminClassDetail } from '../pages/admin/AdminClassDetail';
import { adminUser, mockApi, page } from './adminTestUtils';

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: adminUser }),
}));

const classRow = (over: Record<string, unknown> = {}) => ({
  id: 'c-1',
  slug: 'yoga-sang',
  title: 'Yoga buổi sáng',
  owner: { id: 'u-2', fullName: 'Nguyễn Thị Lan', email: 'lan@example.com' },
  status: 'ACTIVE',
  visibility: 'PRIVATE',
  accessType: 'PAID',
  category: 'Sức khoẻ',
  memberCount: 128,
  pendingRequestCount: 3,
  createdAt: '2026-08-10T03:00:00Z',
  coverUrl: null,
  avatarUrl: 'https://cdn.test/avatar.png',
  suspendedReason: null,
  suspendedAt: null,
  ...over,
});

const classDetail = (over: Record<string, unknown> = {}) => ({
  ...classRow(over),
  counts: { courses: 4, exams: 2, blogPosts: 9, events: 1, products: 3, paidOrders: 57 },
  recentAudit: [],
});

const renderAt = (path: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/admin/classes" element={<AdminClasses />} />
        <Route path="/admin/classes/:id" element={<AdminClassDetail />} />
      </Routes>
    </MemoryRouter>,
  );

describe('AdminClasses list', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('shows thumbnail, owner, members and pending requests, and filters by status / visibility / fee / category', async () => {
    const { calls } = mockApi((method, path) => {
      if (path === '/classes/categories') return { data: ['Sức khoẻ', 'Kinh doanh'] };
      if (path === '/admin/classes') return { data: page([classRow(), classRow({ id: 'c-2', title: 'Không ảnh', avatarUrl: null, coverUrl: null })]) };
      return undefined;
    });
    const { container } = renderAt('/admin/classes');

    expect(await screen.findByText('Yoga buổi sáng')).toBeInTheDocument();
    expect(screen.getByText('Yoga buổi sáng').closest('a')).toHaveAttribute('href', '/admin/classes/c-1');
    expect(screen.getAllByText('Nguyễn Thị Lan')[0].closest('a')).toHaveAttribute('href', '/admin/users/u-2');
    expect(screen.getAllByText('128')).toHaveLength(2);
    expect(screen.getAllByText('128 thành viên')).toHaveLength(2); // phone sub-line of the first column
    expect(container.querySelector('img[src="https://cdn.test/avatar.png"]')).not.toBeNull();
    expect(screen.getByText('K')).toBeInTheDocument(); // letter tile fallback of "Không ảnh"

    fireEvent.click(within(screen.getByRole('group', { name: 'Lọc theo trạng thái' })).getByRole('button', { name: 'Tạm khóa' }));
    await waitFor(() => expect(calls[calls.length - 1].query.get('status')).toBe('SUSPENDED'));

    fireEvent.change(screen.getByLabelText('Hiển thị'), { target: { value: 'PRIVATE' } });
    await waitFor(() => expect(calls[calls.length - 1].query.get('visibility')).toBe('PRIVATE'));
    fireEvent.change(screen.getByLabelText('Học phí'), { target: { value: 'PAID' } });
    await waitFor(() => expect(calls[calls.length - 1].query.get('accessType')).toBe('PAID'));

    // Categories come from GET /classes/categories.
    await waitFor(() => expect(within(screen.getByLabelText('Chủ đề')).getByRole('option', { name: 'Kinh doanh' })).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText('Chủ đề'), { target: { value: 'Kinh doanh' } });
    await waitFor(() => expect(calls[calls.length - 1].query.get('category')).toBe('Kinh doanh'));
    expect(calls[calls.length - 1].query.get('status')).toBe('SUSPENDED');
  });
});

describe('AdminClassDetail suspend / restore', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('shows counts and the class link, and suspends only with a reason', async () => {
    let status = 'ACTIVE';
    const { calls } = mockApi((method, path) => {
      if (method === 'GET' && path === '/admin/classes/c-1') {
        return { data: classDetail(status === 'SUSPENDED' ? { status, suspendedReason: 'Nội dung vi phạm bản quyền', suspendedAt: '2026-10-02T01:00:00Z' } : {}) };
      }
      if (method === 'POST' && path === '/admin/classes/c-1/suspend') {
        status = 'SUSPENDED';
        return { data: classRow({ status }) };
      }
      return undefined;
    });
    renderAt('/admin/classes/c-1');

    expect(await screen.findByRole('heading', { name: 'Yoga buổi sáng' })).toBeInTheDocument();
    expect(screen.getByText('Mở trang lớp').closest('a')).toHaveAttribute('href', '/classes/yoga-sang');
    expect(screen.getByText('Đơn đã thanh toán').nextSibling).toHaveTextContent('57');

    fireEvent.click(screen.getByRole('button', { name: 'Tạm khóa lớp' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Tạm khóa lớp học này?' });
    const confirm = within(dialog).getByRole('button', { name: 'Tạm khóa lớp' });
    expect(confirm).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText('Lý do (bắt buộc)'), { target: { value: 'Nội dung vi phạm bản quyền' } });
    fireEvent.click(confirm);

    await waitFor(() => expect(calls.find((c) => c.path === '/admin/classes/c-1/suspend')?.body).toEqual({ reason: 'Nội dung vi phạm bản quyền' }));
    expect(await screen.findByRole('button', { name: 'Mở khóa lớp' })).toBeInTheDocument();
    expect(screen.getByText('Lý do: Nội dung vi phạm bản quyền')).toBeInTheDocument();
  });

  it('restores a suspended class with a reason and shows a server error inline', async () => {
    let fail = true;
    const { calls } = mockApi((method, path) => {
      if (method === 'GET' && path === '/admin/classes/c-1') return { data: classDetail({ status: 'SUSPENDED', suspendedReason: 'Spam' }) };
      if (method === 'POST' && path === '/admin/classes/c-1/restore') {
        if (fail) {
          fail = false;
          return { status: 409, error: { code: 'CONFLICT', message: 'Lớp không ở trạng thái tạm khóa.' } };
        }
        return { data: classRow() };
      }
      return undefined;
    });
    renderAt('/admin/classes/c-1');

    fireEvent.click(await screen.findByRole('button', { name: 'Mở khóa lớp' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Mở khóa lớp học này?' });
    fireEvent.change(within(dialog).getByLabelText('Lý do (bắt buộc)'), { target: { value: 'Đã gỡ nội dung' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Mở khóa lớp' }));
    expect(await within(dialog).findByText('Lớp không ở trạng thái tạm khóa.')).toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole('button', { name: 'Mở khóa lớp' }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
    expect(calls.filter((c) => c.path === '/admin/classes/c-1/restore').map((c) => c.body)).toEqual([
      { reason: 'Đã gỡ nội dung' },
      { reason: 'Đã gỡ nội dung' },
    ]);
  });
});
