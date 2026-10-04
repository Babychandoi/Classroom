import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AdminPrivacy } from '../pages/admin/AdminPrivacy';
import { AdminAudit, dayEndIso, dayStartIso } from '../pages/admin/AdminAudit';
import { DataRightsPanel } from '../components/DataRightsPanel';
import { adminUser, mockApi, page, plainUser } from './adminTestUtils';

let currentUser: typeof adminUser = adminUser;
vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: currentUser }),
}));

const renderAt = (path: string, element: React.ReactElement, routePath: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path={routePath} element={element} />
      </Routes>
    </MemoryRouter>,
  );

const requests = [
  { user_id: 'u-7', id: 'r-1', status: 'PENDING', reason: 'Tôi không dùng nữa', resolution: null, created_at: '2026-10-01T02:00:00Z' },
  { user_id: 'u-8', id: 'r-2', status: 'COMPLETED', reason: null, resolution: 'Đã ẩn danh hóa', created_at: '2026-09-01T02:00:00Z' },
];

describe('AdminPrivacy (queue moved out of DataRightsPanel)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    currentUser = adminUser;
  });

  it('lists open requests and resolves one with PUT /privacy/requests/{userId} {status, resolution}', async () => {
    const { calls } = mockApi((method, path) => {
      if (method === 'GET' && path === '/privacy/requests') return { data: requests };
      if (method === 'PUT' && path === '/privacy/requests/u-7') return { data: { ...requests[0], status: 'ON_HOLD' } };
      return undefined;
    });
    renderAt('/admin/privacy', <AdminPrivacy />, '/admin/privacy');

    const list = await screen.findByRole('list', { name: 'Yêu cầu dữ liệu' });
    expect(within(list).getByText('Người dùng u-7')).toBeInTheDocument();
    expect(within(list).queryByText('Người dùng u-8')).not.toBeInTheDocument(); // completed = not open
    expect(screen.getByText('Tôi không dùng nữa')).toBeInTheDocument();

    const hold = screen.getByRole('button', { name: 'Tạm giữ có căn cứ' });
    expect(hold).toBeDisabled(); // a resolution is required
    fireEvent.change(screen.getByLabelText('Kết quả và căn cứ lưu trữ'), { target: { value: 'Còn đơn hàng chưa đối soát' } });
    fireEvent.click(hold);

    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({ status: 'ON_HOLD', resolution: 'Còn đơn hàng chưa đối soát' }));
    expect(await screen.findByText('Đã ghi nhận kết quả xử lý.')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Tất cả/ }));
    expect(screen.getByText('Người dùng u-8')).toBeInTheDocument();
  });

  it('asks for confirmation before anonymising (COMPLETED)', async () => {
    const { calls } = mockApi((method, path) => {
      if (method === 'GET' && path === '/privacy/requests') return { data: requests };
      if (method === 'PUT') return { data: { ...requests[0], status: 'COMPLETED' } };
      return undefined;
    });
    renderAt('/admin/privacy', <AdminPrivacy />, '/admin/privacy');

    fireEvent.change(await screen.findByLabelText('Kết quả và căn cứ lưu trữ'), { target: { value: 'Không còn nghĩa vụ lưu trữ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Ẩn danh hóa và đóng tài khoản' }));
    expect(calls.some((c) => c.method === 'PUT')).toBe(false);
    const dialog = screen.getByRole('alertdialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Ẩn danh hóa và đóng tài khoản' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({ status: 'COMPLETED', resolution: 'Không còn nghĩa vụ lưu trữ' }));
  });

  it('DataRightsPanel keeps only the own requests and links admins to the queue', async () => {
    const { calls } = mockApi((method, path) => (path === '/privacy/me/requests' ? { data: [] } : undefined));
    renderAt('/me/profile', <DataRightsPanel />, '/me/profile');

    const link = await screen.findByText('Mở hàng đợi trong Quản trị nền tảng');
    expect(link.closest('a')).toHaveAttribute('href', '/admin/privacy');
    await waitFor(() => expect(calls.map((c) => c.path)).toEqual(['/privacy/me/requests']));
    expect(screen.queryByText('Xử lý yêu cầu dữ liệu')).not.toBeInTheDocument();
  });

  it('DataRightsPanel shows no admin link to other accounts', async () => {
    currentUser = plainUser;
    mockApi((method, path) => (path === '/privacy/me/requests' ? { data: [] } : undefined));
    renderAt('/me/profile', <DataRightsPanel />, '/me/profile');
    expect(await screen.findByText('Dữ liệu cá nhân của bạn')).toBeInTheDocument();
    expect(screen.queryByText('Mở hàng đợi trong Quản trị nền tảng')).not.toBeInTheDocument();
  });
});

describe('AdminAudit filters', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    currentUser = adminUser;
  });

  const audit = {
    id: 'a-1',
    createdAt: '2026-10-02T03:04:00Z',
    action: 'ADMIN_CLASS_SUSPEND',
    targetType: 'CLASS',
    targetId: 'c-1',
    classId: 'c-1',
    classTitle: 'Yoga buổi sáng',
    actor: { id: 'admin-1', fullName: 'Quản Trị', email: 'admin@classroom.local' },
    details: { reason: '<img src=x onerror=alert(1)>', previousStatus: 'ACTIVE', extra: { a: 1 } },
  };

  it('sends action / class / date range and renders details as plain key-value text', async () => {
    const { calls } = mockApi((method, path) => (path === '/admin/audit' ? { data: page([audit], { size: 50 }) } : undefined));
    const { container } = renderAt('/admin/audit?actorId=admin-1', <AdminAudit />, '/admin/audit');

    expect(await screen.findByText('Tạm khóa lớp')).toBeInTheDocument();
    expect(calls[0].query.get('size')).toBe('50');
    expect(calls[0].query.get('actorId')).toBe('admin-1');
    expect(screen.getByText('Yoga buổi sáng').closest('a')).toHaveAttribute('href', '/admin/classes/c-1');
    // Never parsed as HTML.
    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
    expect(container.querySelector('img[src="x"]')).toBeNull();
    expect(screen.getByText('Trạng thái trước')).toBeInTheDocument();
    expect(screen.getByText('{"a":1}')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Thao tác'), { target: { value: 'admin_user_ban' } });
    fireEvent.change(screen.getByLabelText('Mã lớp'), { target: { value: 'c-9' } });
    fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2026-10-01' } });
    fireEvent.change(screen.getByLabelText('Đến ngày'), { target: { value: '2026-10-03' } });
    fireEvent.click(screen.getByRole('button', { name: 'Lọc nhật ký' }));

    await waitFor(() => expect(calls[calls.length - 1].query.get('action')).toBe('ADMIN_USER_BAN'));
    const last = calls[calls.length - 1].query;
    expect(last.get('classId')).toBe('c-9');
    expect(last.get('actorId')).toBe('admin-1');
    expect(last.get('from')).toBe(dayStartIso('2026-10-01'));
    expect(last.get('to')).toBe(dayEndIso('2026-10-03'));
    expect(new Date(last.get('to')!).getTime() - new Date(last.get('from')!).getTime()).toBe(3 * 24 * 3600 * 1000 - 1);
  });

  it('refuses an inverted date range without calling the API', async () => {
    const { calls } = mockApi((method, path) => (path === '/admin/audit' ? { data: page([]) } : undefined));
    renderAt('/admin/audit', <AdminAudit />, '/admin/audit');
    expect(await screen.findByText('Chưa có mục nhật ký nào.')).toBeInTheDocument();
    const before = calls.length;

    fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2026-10-05' } });
    fireEvent.change(screen.getByLabelText('Đến ngày'), { target: { value: '2026-10-01' } });
    fireEvent.click(screen.getByRole('button', { name: 'Lọc nhật ký' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.');
    expect(calls.length).toBe(before);
  });
});
