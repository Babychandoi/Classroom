import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AdminUsers } from '../pages/admin/AdminUsers';
import { AdminUserDetail } from '../pages/admin/AdminUserDetail';
import { adminUser, mockApi, page } from './adminTestUtils';

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: adminUser }),
}));

const row = (over: Record<string, unknown> = {}) => ({
  id: 'u-2',
  email: 'lan@example.com',
  fullName: 'Nguyễn Thị Lan',
  avatarUrl: null,
  role: 'USER',
  status: 'ACTIVE',
  createdAt: '2026-09-01T03:00:00Z',
  ownedClassCount: 2,
  membershipCount: 5,
  lastLoginAt: null,
  ...over,
});

const detail = (over: Record<string, unknown> = {}) => ({
  ...row(over),
  ownedClasses: [{ id: 'c-1', slug: 'yoga-sang', title: 'Yoga buổi sáng', status: 'ACTIVE' }],
  recentAudit: [
    {
      id: 'a-1',
      createdAt: '2026-10-01T02:00:00Z',
      action: 'ADMIN_USER_ROLE',
      targetType: 'USER',
      targetId: 'u-2',
      actor: { id: 'admin-1', fullName: 'Quản Trị', email: 'admin@classroom.local' },
      details: { reason: 'Hỗ trợ vận hành', role: 'USER' },
    },
  ],
});

const renderAt = (path: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/admin/users" element={<AdminUsers />} />
        <Route path="/admin/users/:id" element={<AdminUserDetail />} />
      </Routes>
    </MemoryRouter>,
  );

describe('AdminUsers list', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('lists accounts and refetches with the status / role chips and the search box', async () => {
    const { calls } = mockApi((method, path) => (path === '/admin/users' ? { data: page([row()], { totalElements: 41, totalPages: 3 }) } : undefined));
    renderAt('/admin/users');

    expect(await screen.findByText('Nguyễn Thị Lan')).toBeInTheDocument();
    expect(screen.getByText('Nguyễn Thị Lan').closest('a')).toHaveAttribute('href', '/admin/users/u-2');
    expect(screen.getByText('Trang 1/3 · 41 người dùng')).toBeInTheDocument();
    expect(calls[0].query.get('size')).toBe('20');
    expect(calls[0].query.get('sort')).toBe('newest');

    fireEvent.click(within(screen.getByRole('group', { name: 'Lọc theo trạng thái' })).getByRole('button', { name: 'Bị khóa' }));
    await waitFor(() => expect(calls[calls.length - 1].query.get('status')).toBe('BANNED'));

    fireEvent.click(within(screen.getByRole('group', { name: 'Lọc theo vai trò' })).getByRole('button', { name: 'Quản trị nền tảng' }));
    await waitFor(() => expect(calls[calls.length - 1].query.get('role')).toBe('PLATFORM_ADMIN'));
    expect(calls[calls.length - 1].query.get('status')).toBe('BANNED');

    fireEvent.change(screen.getByPlaceholderText('Tìm theo email hoặc tên'), { target: { value: '  lan@ ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Tìm' }));
    await waitFor(() => expect(calls[calls.length - 1].query.get('q')).toBe('lan@'));

    fireEvent.click(screen.getByRole('button', { name: 'Trang sau' }));
    await waitFor(() => expect(calls[calls.length - 1].query.get('page')).toBe('1'));
  });
});

describe('AdminUserDetail actions', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('bans only after a reason is entered and sends that reason', async () => {
    let status = 'ACTIVE';
    const { calls } = mockApi((method, path, _q, body) => {
      if (method === 'GET' && path === '/admin/users/u-2') return { data: detail({ status }) };
      if (method === 'POST' && path === '/admin/users/u-2/ban') {
        status = 'BANNED';
        return { data: row({ status: 'BANNED' }) };
      }
      return undefined;
    });
    renderAt('/admin/users/u-2');

    expect(await screen.findByRole('heading', { name: 'Nguyễn Thị Lan' })).toBeInTheDocument();
    expect(screen.getByText('Yoga buổi sáng').closest('a')).toHaveAttribute('href', '/admin/classes/c-1');
    // Audit details render as key / value text.
    expect(screen.getByText('Lý do')).toBeInTheDocument();
    expect(screen.getByText('Hỗ trợ vận hành')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Khóa tài khoản' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Khóa tài khoản này?' });
    const confirm = within(dialog).getByRole('button', { name: 'Khóa tài khoản' });
    expect(confirm).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText('Lý do (bắt buộc)'), { target: { value: '   ' } });
    expect(confirm).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText('Lý do (bắt buộc)'), { target: { value: ' Spam lặp lại ' } });
    expect(confirm).toBeEnabled();
    fireEvent.click(confirm);

    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
    const ban = calls.find((c) => c.method === 'POST' && c.path === '/admin/users/u-2/ban');
    expect(ban?.body).toEqual({ reason: 'Spam lặp lại' });
    expect(await screen.findByText(/Đã khóa tài khoản/)).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Mở khóa' })).toBeInTheDocument();
  });

  it('unbans a banned account with a reason', async () => {
    const { calls } = mockApi((method, path) => {
      if (method === 'GET' && path === '/admin/users/u-2') return { data: detail({ status: 'BANNED' }) };
      if (method === 'POST' && path === '/admin/users/u-2/unban') return { data: row() };
      return undefined;
    });
    renderAt('/admin/users/u-2');

    fireEvent.click(await screen.findByRole('button', { name: 'Mở khóa' }));
    const dialog = screen.getByRole('alertdialog');
    fireEvent.change(within(dialog).getByLabelText('Lý do (bắt buộc)'), { target: { value: 'Đã xác minh' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Mở khóa' }));
    await waitFor(() => expect(calls.some((c) => c.path === '/admin/users/u-2/unban' && c.body?.reason === 'Đã xác minh')).toBe(true));
  });

  it('changes the role with PUT {role, reason} and shows a 409 from the server inline, keeping the dialog open', async () => {
    const { calls } = mockApi((method, path) => {
      if (method === 'GET' && path === '/admin/users/u-2') return { data: detail({ role: 'PLATFORM_ADMIN' }) };
      if (method === 'PUT' && path === '/admin/users/u-2/role') {
        return { status: 409, error: { code: 'CONFLICT', message: 'Không thể hạ quyền quản trị viên cuối cùng.' } };
      }
      return undefined;
    });
    renderAt('/admin/users/u-2');

    fireEvent.click(await screen.findByRole('button', { name: 'Đổi vai trò' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Đổi vai trò' });
    expect(within(dialog).getByLabelText('Vai trò mới')).toHaveValue('USER');
    fireEvent.change(within(dialog).getByLabelText('Lý do (bắt buộc)'), { target: { value: 'Rời nhóm vận hành' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Đổi vai trò' }));

    expect(await within(dialog).findByText('Không thể hạ quyền quản trị viên cuối cùng.')).toBeInTheDocument();
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
    expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({ role: 'USER', reason: 'Rời nhóm vận hành' });
  });

  it('does not offer self-ban or self-demotion', async () => {
    mockApi((method, path) =>
      method === 'GET' && path === '/admin/users/admin-1'
        ? { data: detail({ id: 'admin-1', fullName: 'Quản Trị', email: 'admin@classroom.local', role: 'PLATFORM_ADMIN' }) }
        : undefined,
    );
    renderAt('/admin/users/admin-1');

    expect(await screen.findByRole('button', { name: 'Khóa tài khoản' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Đổi vai trò' })).toBeDisabled();
    expect(screen.getByText(/không thể tự khóa hay tự hạ quyền/)).toBeInTheDocument();
  });

  it('shows a 400 from the server inline (e.g. a rule the UI could not know)', async () => {
    mockApi((method, path) => {
      if (method === 'GET' && path === '/admin/users/u-2') return { data: detail() };
      if (method === 'POST') return { status: 400, error: { code: 'VALIDATION_ERROR', message: 'Lý do không được để trống' } };
      return undefined;
    });
    renderAt('/admin/users/u-2');

    fireEvent.click(await screen.findByRole('button', { name: 'Khóa tài khoản' }));
    const dialog = screen.getByRole('alertdialog');
    fireEvent.change(within(dialog).getByLabelText('Lý do (bắt buộc)'), { target: { value: 'x' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Khóa tài khoản' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Lý do không được để trống');
  });
});
