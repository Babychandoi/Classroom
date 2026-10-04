import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Navigate, Route, Routes } from 'react-router-dom';
import { RequireLogin } from '../App';
import { AdminLayout } from '../pages/admin/AdminLayout';
import { AdminOverview } from '../pages/admin/AdminOverview';
import { adminUser, mockApi, plainUser } from './adminTestUtils';

// "Quản trị nền tảng" guard: guests go to /login, signed-in non-admins get a ForbiddenState (and no admin request is
// made), PLATFORM_ADMIN gets the grouped sidebar and the overview.

let authState: { user: typeof adminUser | null; isLoading: boolean; isReconnecting: boolean; retryReconnect: () => void } = {
  user: adminUser, isLoading: false, isReconnecting: false, retryReconnect: () => {},
};

vi.mock('../context/AuthContext', () => ({
  useAuth: () => authState,
  AuthProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

const overview = {
  users: { total: 120, active: 110, banned: 3, deleted: 7, admins: 2, newLast7Days: 9, newLast30Days: 31 },
  classes: { total: 14, active: 11, archived: 2, suspended: 1, public: 10, private: 4, paid: 5, newLast7Days: 2 },
  members: { activeMemberships: 340, pendingRequests: 6 },
  content: { courses: 22, publishedExams: 8, blogPostsPublished: 17, upcomingEvents: 3 },
  commerce: { paidOrdersLast30Days: 12, revenueLast30Days: 1234000, currency: 'VND', pendingOrders: 2 },
  privacy: { openRequests: 1 },
  outbox: { pending: 0, deadLetter: 0 },
  signupsByDay: [
    { date: '2026-09-29', count: 0 },
    { date: '2026-09-30', count: 4 },
    { date: '2026-10-01', count: 2 },
  ],
};

const renderAt = (path: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/login" element={<p>Trang đăng nhập</p>} />
        <Route path="/admin" element={<RequireLogin><AdminLayout /></RequireLogin>}>
          <Route index element={<Navigate to="overview" replace />} />
          <Route path="overview" element={<AdminOverview />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );

describe('AdminLayout guard', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    authState = { user: adminUser, isLoading: false, isReconnecting: false, retryReconnect: () => {} };
  });

  it('sends a guest to /login', async () => {
    const { calls } = mockApi(() => undefined);
    authState = { ...authState, user: null };
    renderAt('/admin/overview');
    expect(await screen.findByText('Trang đăng nhập')).toBeInTheDocument();
    expect(calls).toEqual([]);
  });

  it('shows a ForbiddenState to a signed-in account without PLATFORM_ADMIN and calls no admin endpoint', async () => {
    const { calls } = mockApi(() => undefined);
    authState = { ...authState, user: plainUser };
    renderAt('/admin/overview');
    expect(await screen.findByText('Khu vực dành cho quản trị nền tảng')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Về danh sách lớp' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Điều hướng quản trị nền tảng' })).not.toBeInTheDocument();
    expect(calls.filter((c) => c.path.startsWith('/admin'))).toEqual([]);
  });

  it('gives a PLATFORM_ADMIN the grouped sidebar and redirects /admin to the overview', async () => {
    mockApi((method, path) => (method === 'GET' && path === '/admin/overview' ? { data: overview } : undefined));
    renderAt('/admin');

    const nav = screen.getByRole('navigation', { name: 'Điều hướng quản trị nền tảng' });
    expect(screen.getByText('QUẢN TRỊ NỀN TẢNG')).toBeInTheDocument();
    for (const label of ['Tổng quan', 'Người dùng', 'Lớp học', 'Yêu cầu dữ liệu', 'Nhật ký']) {
      expect(within(nav).getByText(label)).toBeInTheDocument();
    }
    expect(within(nav).getByText('Tổng quan').closest('a')).toHaveAttribute('aria-current', 'page');
    expect(await screen.findByRole('heading', { name: 'Tổng quan nền tảng' })).toBeInTheDocument();
    // The phone menu toggle exists (collapsed by default).
    expect(screen.getByRole('button', { name: /Menu/ })).toHaveAttribute('aria-expanded', 'false');
  });
});

describe('AdminOverview', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    authState = { user: adminUser, isLoading: false, isReconnecting: false, retryReconnect: () => {} };
  });

  it('shows the KPI tiles, the revenue in dong and no outbox warning when the queue is empty', async () => {
    mockApi((method, path) => (path === '/admin/overview' ? { data: overview } : undefined));
    renderAt('/admin/overview');

    expect(await screen.findByText('Tổng tài khoản')).toBeInTheDocument();
    expect(screen.getByText('120')).toBeInTheDocument();
    expect(screen.getByText('+9 trong 7 ngày')).toBeInTheDocument();
    expect(screen.getByText('1.234.000đ')).toBeInTheDocument();
    expect(screen.getByText('Lớp đang tạm khóa').closest('a')).toHaveAttribute('href', '/admin/classes?status=SUSPENDED');
    expect(screen.queryByText(/outbox\) đang bị dồn/)).not.toBeInTheDocument();
  });

  it('warns when the outbox has pending events or dead letters', async () => {
    mockApi((method, path) => (path === '/admin/overview' ? { data: { ...overview, outbox: { pending: 42, deadLetter: 3 } } } : undefined));
    renderAt('/admin/overview');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Hàng đợi sự kiện (outbox) đang bị dồn');
    expect(alert).toHaveTextContent('42 sự kiện chờ gửi · 3 sự kiện lỗi (dead letter)');
  });

  it('draws one bar per day with an accessible label and offers the same numbers as a table', async () => {
    mockApi((method, path) => (path === '/admin/overview' ? { data: overview } : undefined));
    renderAt('/admin/overview');

    expect(await screen.findByLabelText('30/09/2026: 4 người đăng ký')).toBeInTheDocument();
    expect(screen.getByLabelText('29/09/2026: 0 người đăng ký')).toBeInTheDocument();
    expect(screen.getByText(/6 tài khoản mới · nhiều nhất 4 vào 30\/09\/2026/)).toBeInTheDocument();

    screen.getByRole('button', { name: 'Xem dạng bảng' }).click();
    const table = await screen.findByRole('table');
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(4); // header + 3 days
    expect(rows[2]).toHaveTextContent('30/09/2026');
    expect(rows[2]).toHaveTextContent('4');
  });

  it('shows the server error with a retry instead of a blank page', async () => {
    mockApi(() => ({ status: 403, error: { code: 'FORBIDDEN', message: 'Bạn không có quyền thực hiện thao tác này' } }));
    renderAt('/admin/overview');
    expect(await screen.findByText('Bạn không có quyền thực hiện thao tác này')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('Thử lại')).toBeInTheDocument());
  });
});
