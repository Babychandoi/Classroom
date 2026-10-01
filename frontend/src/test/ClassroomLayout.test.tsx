import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, act, waitFor, fireEvent } from '@testing-library/react';
import { ClassroomLayout } from '../pages/ClassroomLayout';
import type { Classroom } from '../types';

const mockNavigate = vi.fn();

const mockLocation = { pathname: '/classes/demo-class/feed', search: '', hash: '', state: null, key: 'k' };

vi.mock('react-router-dom', () => ({
  useParams: () => ({ slug: 'demo-class' }),
  useNavigate: () => mockNavigate,
  useLocation: () => mockLocation,
  Outlet: () => <div data-testid="outlet" />,
  NavLink: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
  Link: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
}));

const mockUser = { id: 'u1', fullName: 'Student', email: 'student@test.local', role: 'STUDENT', status: 'ACTIVE' };

// Mutable so a test can model the session bootstrap (isLoading) and a genuinely signed-out visitor.
let authState: { user: typeof mockUser | null; isLoading: boolean } = { user: mockUser, isLoading: false };

vi.mock('../context/AuthContext', () => ({
  useAuth: () => authState,
}));

const classroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 1,
  createdAt: new Date().toISOString(),
};

describe('ClassroomLayout error banner reset on retry', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    authState = { user: mockUser, isLoading: false };
  });

  it('clears a stale error banner as soon as retry starts, and stays cleared after a successful refetch', async () => {
    let callCount = 0;

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/classes/slug/demo-class')) {
        callCount += 1;
        if (callCount === 1) {
          return new Response(
            JSON.stringify({ error: { code: 'INTERNAL', message: 'Không thể tải thông tin lớp học' } }),
            { status: 500 }
          );
        }
        return new Response(JSON.stringify({ success: true, data: classroom }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<ClassroomLayout />);

    // First fetch fails: error banner shows.
    await waitFor(() => expect(screen.getByText('Không thể tải thông tin lớp học')).toBeInTheDocument());

    const retryButton = screen.getByText('Thử lại');

    // Kick off the retry, but don't let the second fetch's promise resolve yet: assert the
    // banner is gone synchronously at the start of the retry, not just after success.
    let releaseFetch: (() => void) | null = null;
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      () =>
        new Promise((resolve) => {
          releaseFetch = () =>
            resolve(new Response(JSON.stringify({ success: true, data: classroom }), { status: 200 }));
        })
    );

    act(() => {
      retryButton.click();
    });

    // Error banner must be cleared immediately (setError(null) fires synchronously at retry
    // start), well before the in-flight retry request resolves.
    expect(screen.queryByText('Không thể tải thông tin lớp học')).not.toBeInTheDocument();

    await act(async () => {
      releaseFetch?.();
      await Promise.resolve();
    });

    // After the successful retry, the error banner stays gone and classroom content renders.
    await waitFor(() => expect(screen.getByText('Demo Class')).toBeInTheDocument());
    expect(screen.queryByText('Không thể tải thông tin lớp học')).not.toBeInTheDocument();
  });
});

// R16-01: the layout must follow the server-reported membership state, not "a row exists".
describe('ClassroomLayout membership state (R16-01)', () => {
  const asState = (over: Partial<Classroom>): Classroom => ({ ...classroom, isMember: false, isOwner: false, userRole: 'GUEST', ...over });

  const mockClassroom = (data: Classroom) =>
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      if (url.includes('/classes/slug/demo-class')) {
        return new Response(JSON.stringify({ success: true, data }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

  beforeEach(() => {
    vi.restoreAllMocks();
    mockNavigate.mockReset();
    authState = { user: mockUser, isLoading: false };
  });

  it('offers "Tham gia lại" to a REMOVED person and rejoins through the existing join endpoint', async () => {
    const fetchSpy = mockClassroom(asState({ memberState: 'REMOVED' }));
    render(<ClassroomLayout />);

    const rejoin = await screen.findByRole('button', { name: /Tham gia lại/ });
    expect(screen.getByText(/Bạn đã bị xóa khỏi lớp học này/)).toBeInTheDocument();
    // A removed person is not a guest: the generic "join now" prompt is not shown.
    expect(screen.queryByText(/Tham gia lớp ngay/)).not.toBeInTheDocument();
    expect(screen.getByTestId('outlet')).toBeInTheDocument();

    fetchSpy.mockImplementation(async (input, init) => {
      const url = input.toString();
      if (url.endsWith('/classes/class-1/join') && init?.method === 'POST') {
        return new Response(
          JSON.stringify({ success: true, data: { ...classroom, isMember: true, userRole: 'STUDENT', memberState: 'ACTIVE' } }),
          { status: 200 }
        );
      }
      return new Response('{}', { status: 404 });
    });
    fireEvent.click(rejoin);

    await waitFor(() => expect(screen.queryByRole('button', { name: /Tham gia lại/ })).not.toBeInTheDocument());
    expect(fetchSpy.mock.calls.some(([u, i]) => String(u).endsWith('/classes/class-1/join') && i?.method === 'POST')).toBe(true);
  });

  it('shows a BLOCKED person only a notice: no rejoin button, no join prompt, no tabs, no class content', async () => {
    mockClassroom(asState({ memberState: 'BLOCKED' }));
    render(<ClassroomLayout />);

    expect(await screen.findByText('Bạn đã bị chặn khỏi lớp học này')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Tham gia/ })).not.toBeInTheDocument();
    // Member tabs (Bảng tin, Góc học tập, ...) are hidden and the routed content is not rendered.
    expect(screen.queryByText('Bảng tin')).not.toBeInTheDocument();
    expect(screen.queryByText('Góc học tập')).not.toBeInTheDocument();
    expect(screen.queryByTestId('outlet')).not.toBeInTheDocument();
  });

  it('keeps the plain guest prompt for someone who never joined (memberState NONE)', async () => {
    mockClassroom(asState({ memberState: 'NONE' }));
    render(<ClassroomLayout />);

    expect(await screen.findByRole('button', { name: /Tham gia lớp ngay/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Tham gia lại/ })).not.toBeInTheDocument();
    expect(screen.getByTestId('outlet')).toBeInTheDocument();
  });

  it('shows no join/rejoin/blocked UI to an ACTIVE member, and the tabs stay visible', async () => {
    mockClassroom(asState({ isMember: true, userRole: 'STUDENT', memberState: 'ACTIVE' }));
    render(<ClassroomLayout />);

    await waitFor(() => expect(screen.getByTestId('outlet')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /Tham gia/ })).not.toBeInTheDocument();
    expect(screen.queryByText('Bạn đã bị chặn khỏi lớp học này')).not.toBeInTheDocument();
    expect(screen.getByText('Bảng tin')).toBeInTheDocument();
  });

  it('never treats the owner as removed/blocked even if a stale state slipped through', async () => {
    mockClassroom(asState({ isOwner: true, isMember: true, userRole: 'OWNER', memberState: 'ACTIVE' }));
    render(<ClassroomLayout />);

    await waitFor(() => expect(screen.getByTestId('outlet')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /Tham gia/ })).not.toBeInTheDocument();
  });
});

// R17-01: while the silent session restore is in flight `user` is null even for a signed-in person, so
// the layout must not decide guest-vs-member (nor let the tabs fetch anonymously) until it settles.
describe('ClassroomLayout waits for the auth bootstrap (R17-01)', () => {
  const guestClassroom: Classroom = { ...classroom, isMember: false, isOwner: false, userRole: 'GUEST', memberState: 'NONE' };

  const classFetches = (spy: { mock: { calls: unknown[][] } }) =>
    spy.mock.calls.filter(([u]) => String(u).includes('/classes/slug/demo-class'));

  const mockClassroomFetch = () =>
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      if (url.includes('/classes/slug/demo-class')) {
        return new Response(JSON.stringify({ success: true, data: guestClassroom }), { status: 200 });
      }
      if (url.endsWith('/classes/class-1/join') && init?.method === 'POST') {
        return new Response(JSON.stringify({ success: true, data: { ...guestClassroom, isMember: true, userRole: 'STUDENT', memberState: 'ACTIVE' } }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

  beforeEach(() => {
    vi.restoreAllMocks();
    mockNavigate.mockReset();
    authState = { user: mockUser, isLoading: false };
  });

  it('shows the spinner and mounts neither the tabs nor a guest CTA (and fetches nothing) while auth is loading', async () => {
    authState = { user: null, isLoading: true };
    const fetchSpy = mockClassroomFetch();
    render(<ClassroomLayout />);

    expect(screen.getByText('Đang tải dữ liệu lớp học...')).toBeInTheDocument();
    expect(screen.queryByTestId('outlet')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Tham gia/ })).not.toBeInTheDocument();
    await act(async () => { await Promise.resolve(); });
    expect(classFetches(fetchSpy)).toHaveLength(0);
  });

  it('fetches the class exactly once, as the signed-in user, after the bootstrap restores the session', async () => {
    authState = { user: null, isLoading: true };
    const fetchSpy = mockClassroomFetch();
    const view = render(<ClassroomLayout />);
    expect(classFetches(fetchSpy)).toHaveLength(0);

    // The bootstrap settles with the restored user in a single update (isLoading false + user set).
    authState = { user: mockUser, isLoading: false };
    view.rerender(<ClassroomLayout />);

    await waitFor(() => expect(screen.getByTestId('outlet')).toBeInTheDocument());
    expect(classFetches(fetchSpy)).toHaveLength(1);
  });

  it('a signed-in user who joins right after the bootstrap is not sent to /login', async () => {
    authState = { user: null, isLoading: true };
    const fetchSpy = mockClassroomFetch();
    const view = render(<ClassroomLayout />);
    authState = { user: mockUser, isLoading: false };
    view.rerender(<ClassroomLayout />);

    fireEvent.click(await screen.findByRole('button', { name: /Tham gia lớp ngay/ }));

    await waitFor(() => expect(screen.queryByRole('button', { name: /Tham gia lớp ngay/ })).not.toBeInTheDocument());
    expect(fetchSpy.mock.calls.some(([u, i]) => String(u).endsWith('/classes/class-1/join') && i?.method === 'POST')).toBe(true);
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('sends a truly signed-out visitor to /login and remembers the class page to return to', async () => {
    authState = { user: null, isLoading: false };
    mockClassroomFetch();
    render(<ClassroomLayout />);

    fireEvent.click(await screen.findByRole('button', { name: /Tham gia lớp ngay/ }));

    expect(mockNavigate).toHaveBeenCalledWith('/login', { state: { from: mockLocation } });
  });
});
