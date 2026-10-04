import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { StudioOverview, formatToday } from '../pages/studio/StudioOverview';
import type { Classroom } from '../types';

/** Studio dashboard: the "do this first" card and the counts come from real endpoints; the rebuild needs LEADERBOARD:EDIT. */

let classroom: Classroom;
vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom }),
  Link: ({ children, to, className }: { children?: React.ReactNode; to: string; className?: string }) => <a href={to} className={className}>{children}</a>,
}));
vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'u1', fullName: 'Nguyễn Văn Huân' } }),
}));

const base = {
  id: 'class-1', ownerId: 'u1', slug: 'demo', title: 'Demo', status: 'ACTIVE', memberCount: 2340, userRole: 'OWNER',
  studioPermissions: [], studioScopedPermissions: [], createdAt: '',
} as Classroom;

const ok = (data: unknown) => new Response(JSON.stringify({ success: true, data }), { status: 200 });

describe('StudioOverview', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    classroom = base;
  });

  it('formats today as "Thứ Bảy, 11/07/2026"', () => {
    expect(formatToday(new Date(2026, 6, 11))).toBe('Thứ Bảy, 11/07/2026');
  });

  it('greets by given name and puts the grading queue first, with real counts', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.endsWith('/grading-queue')) return ok([{ id: 'a' }, { id: 'b' }]);
      if (url.endsWith('/assignment-queue')) return ok([{ submissionId: 's' }]);
      if (url.endsWith('/orders')) return ok([{ status: 'PENDING' }, { status: 'PAID' }]);
      if (url.endsWith('/courses')) return ok([{ status: 'PUBLISHED' }]);
      if (url.endsWith('/exams')) return ok([]);
      return new Response('{}', { status: 404 });
    });
    render(<StudioOverview />);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Chào Nguyễn Văn Huân — lớp của bạn hôm nay');
    expect(await screen.findByRole('heading', { name: 'Chấm 3 bài đang chờ' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Mở hàng chấm bài' })).toHaveAttribute('href', '/studio/classes/class-1/grading');
    expect(screen.getByText('1 đơn hàng chưa thanh toán xong')).toBeInTheDocument();
    expect(screen.getByText('2.340 thành viên')).toBeInTheDocument();
    // No placeholder words from the old dashboard.
    expect(screen.queryByText('Sẵn sàng')).not.toBeInTheDocument();
    expect(screen.queryByText('Mở bán')).not.toBeInTheDocument();
  });

  it('does not call endpoints a staff member has no grant for, and hides the rebuild without LEADERBOARD:EDIT', async () => {
    classroom = { ...base, userRole: 'STAFF', studioPermissions: ['STUDIO:VIEW'] } as Classroom;
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => ok([]));
    render(<StudioOverview />);
    expect(await screen.findByText('Không có việc nào đang chờ bạn')).toBeInTheDocument();
    const urls = fetchSpy.mock.calls.map((c) => c[0].toString());
    expect(urls.some((u) => u.endsWith('/grading-queue') || u.endsWith('/assignment-queue') || u.endsWith('/orders'))).toBe(false);
    expect(screen.queryByRole('button', { name: /Tái tạo bảng xếp hạng/ })).not.toBeInTheDocument();
  });

  it('rebuilds the leaderboard for a LEADERBOARD:EDIT holder and reports the result inline', async () => {
    classroom = { ...base, userRole: 'STAFF', studioPermissions: ['STUDIO:VIEW', 'LEADERBOARD:EDIT'] } as Classroom;
    let rebuilt = false;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      if (input.toString().endsWith('/leaderboard/rebuild') && (init?.method || '').toUpperCase() === 'POST') {
        rebuilt = true;
        return ok(null);
      }
      return ok([]);
    });
    render(<StudioOverview />);
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Tái tạo bảng xếp hạng/ }));
    });
    await waitFor(() => expect(rebuilt).toBe(true));
    expect(await screen.findByText('Đã tính lại bảng xếp hạng cho toàn bộ học viên.')).toBeInTheDocument();
  });

  it('puts pending join requests first in "Làm một việc này trước"', async () => {
    classroom = { ...base, pendingRequestCount: 3 } as Classroom;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.endsWith('/grading-queue')) return ok([{ id: 'a' }]);
      return ok([]);
    });
    render(<StudioOverview />);
    expect(await screen.findByRole('heading', { name: 'Duyệt 3 yêu cầu tham gia lớp' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Xem yêu cầu' })).toHaveAttribute('href', '/studio/classes/class-1/members');
    // grading still shows, further down the queue
    expect(screen.getByText('Chấm 1 bài đang chờ')).toBeInTheDocument();
  });

  it('a staff member without MEMBER:VIEW does not get the requests task', async () => {
    classroom = { ...base, userRole: 'STAFF', studioPermissions: ['STUDIO:VIEW'], pendingRequestCount: 3 } as Classroom;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => ok([]));
    render(<StudioOverview />);
    expect(await screen.findByText('Không có việc nào đang chờ bạn')).toBeInTheDocument();
  });
});
