import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { Route, RouterProvider, createMemoryRouter } from 'react-router-dom';
import { AppShell, topbarTitle } from '../App';

/**
 * D-35 app shell: the desktop sidebar ("Điều hướng chính") shows in the home and class areas only; Studio / admin keep their
 * own sidebars, and the focus screens (exam / homework attempt, Question Builder, class creation) drop the whole chrome.
 * The assessment routes are G2's; they are stubbed here so this test pins only the wiring (paths + nesting).
 */

vi.mock('../features/assessment/routes', () => ({
  classHomeworkRoutes: () => (
    <>
      <Route path="homework" element={<p>Trang bài tập</p>} />
      <Route path="homework/:assignmentId/attempt" element={<p>Làm bài tập</p>} />
    </>
  ),
  studioAssessmentRoutes: () => <Route path="assessments" element={<p>Danh sách bài tập &amp; đề</p>} />,
  assessmentTopLevelRoutes: () => <Route path="/studio/classes/:id/assessments/:assessmentId/edit" element={<p>Soạn đề</p>} />,
}));

const mockUser = { id: 'u1', fullName: 'Nguyễn Lan', email: 'lan@test.local', role: 'USER', status: 'ACTIVE' };
let authState: Record<string, unknown> = {};
vi.mock('../context/AuthContext', () => ({
  useAuth: () => authState,
  AuthProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

const classroom = {
  id: 'class-1', ownerId: 'owner-1', slug: 'demo', title: 'Lớp Demo', status: 'ACTIVE', memberCount: 3,
  isMember: true, memberState: 'ACTIVE', userRole: 'STUDENT', createdAt: '2026-10-01T00:00:00Z',
};

function mockFetch() {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = new URL(input.toString(), 'http://localhost');
    const path = url.pathname.replace(/^\/api\/v1/, '');
    const ok = (data: unknown) => new Response(JSON.stringify({ success: true, data }), { status: 200 });
    if (path === '/me/classes') return ok([{ ...classroom, id: 'class-2', slug: 'tieng-anh', title: 'Tiếng Anh 6' }]);
    if (path === '/classes/slug/demo') return ok(classroom);
    if (path.startsWith('/me/') || path.startsWith('/classes')) return ok([]);
    return new Response(JSON.stringify({ success: false, error: { code: 'NOT_FOUND', message: 'Không tìm thấy' } }), { status: 404 });
  });
}

const renderAt = (path: string) => {
  const router = createMemoryRouter([{ path: '*', element: <AppShell /> }], { initialEntries: [path] });
  return render(<RouterProvider router={router} />);
};

describe('AppShell (D-35)', () => {
  beforeEach(() => {
    authState = { user: mockUser, isLoading: false, isReconnecting: false, retryReconnect: () => {}, logout: vi.fn(), quickLogin: vi.fn() };
    mockFetch();
  });
  afterEach(() => vi.restoreAllMocks());

  it('shows the sidebar with the main menu, the joined classes and the account links on /me/classes', async () => {
    renderAt('/me/classes');
    const sidebar = screen.getByTestId('app-sidebar');
    const nav = screen.getByRole('navigation', { name: 'Điều hướng chính' });
    expect(sidebar).toContainElement(nav);
    expect(nav).toHaveTextContent('Khám phá lớp học');
    expect(nav).toHaveTextContent('Sự kiện tham gia');
    expect(nav).toHaveTextContent('Quyền riêng tư & dữ liệu');
    expect(nav).not.toHaveTextContent('Quản trị nền tảng');
    // "Lớp đang học" comes from GET /me/classes.
    expect(await screen.findByRole('link', { name: 'Tiếng Anh 6' })).toHaveAttribute('href', '/classes/tieng-anh');
    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Điều hướng điện thoại' })).toBeInTheDocument();
  });

  it('offers "Quản trị nền tảng" to a platform admin', () => {
    authState = { ...authState, user: { ...mockUser, role: 'PLATFORM_ADMIN' } };
    renderAt('/me/classes');
    const nav = screen.getByRole('navigation', { name: 'Điều hướng chính' });
    expect(nav).toHaveTextContent('Quản trị nền tảng');
  });

  it('keeps the sidebar in a class, and adds the "Bài tập" route under the class layout', async () => {
    renderAt('/classes/demo/homework');
    expect(screen.getByTestId('app-sidebar')).toBeInTheDocument();
    expect(await screen.findByText('Trang bài tập')).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Điều hướng điện thoại' })).not.toBeInTheDocument();
  });

  it('has no app sidebar in Studio (Studio keeps its own nav under the top bar)', async () => {
    renderAt('/studio/classes/x/overview');
    expect(screen.queryByTestId('app-sidebar')).not.toBeInTheDocument();
    expect(screen.getByRole('banner')).toBeInTheDocument();
  });

  it.each([
    ['a homework attempt', '/classes/demo/homework/a-1/attempt', 'Làm bài tập'],
    ['the Question Builder', '/studio/classes/x/assessments/asm-1/edit', 'Soạn đề'],
  ])('drops the whole chrome on %s', async (_label, path, content) => {
    renderAt(path);
    expect(await screen.findByText(content)).toBeInTheDocument();
    expect(screen.queryByTestId('app-sidebar')).not.toBeInTheDocument();
    expect(screen.queryByRole('banner')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Mở menu tài khoản' })).not.toBeInTheDocument();
  });

  it('drops the chrome on an exam attempt too', async () => {
    renderAt('/classes/demo/exams/e-1/attempt');
    await waitFor(() => expect(screen.queryByTestId('app-sidebar')).not.toBeInTheDocument());
    expect(screen.queryByRole('banner')).not.toBeInTheDocument();
  });

  it('titles the top bar after the page / class tab / area', () => {
    expect(topbarTitle('/me/courses')).toBe('Khóa học của tôi');
    expect(topbarTitle('/classes/demo/homework')).toBe('Lớp học · Bài tập');
    expect(topbarTitle('/classes/demo/exams')).toBe('Lớp học · Thi');
    expect(topbarTitle('/studio/classes/x/assessments')).toBe('Xưởng');
    expect(topbarTitle('/admin/users')).toBe('Quản trị nền tảng');
  });
});
