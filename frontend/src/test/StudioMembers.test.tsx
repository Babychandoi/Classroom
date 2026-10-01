import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { StudioMembers } from '../pages/studio/StudioMembers';
import type { Classroom } from '../types';

/**
 * R13-02: Studio "Thành viên" page — render + action tests (remove/block/unblock with
 * confirmation), and the MEMBER:VIEW gate that hides the whole page from an unauthorized staff
 * member the same way MemberService.getStudioMembers does server-side.
 */

const ownerClassroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 2,
  userRole: 'OWNER',
  createdAt: new Date().toISOString(),
} as Classroom;

const unauthorizedStaffClassroom: Classroom = {
  ...ownerClassroom,
  userRole: 'STAFF',
  studioPermissions: [],
} as Classroom;

let currentClassroom = ownerClassroom;

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: currentClassroom }),
}));

const sampleMembers = [
  {
    id: 'cm-1',
    userId: 'student-1',
    role: 'STUDENT',
    state: 'ACTIVE',
    joinedAt: new Date().toISOString(),
    userFullName: 'Học viên Một',
    userEmail: 'student1@test.local',
    isPro: false,
  },
  {
    id: 'cm-2',
    userId: 'student-2',
    role: 'STUDENT',
    state: 'BLOCKED',
    joinedAt: new Date().toISOString(),
    userFullName: 'Học viên Hai',
    userEmail: 'student2@test.local',
    isPro: true,
  },
];

describe('StudioMembers (R13-02)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    currentClassroom = ownerClassroom;
  });

  it('renders the member list with role/state/PRO badges for an authorized OWNER', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/studio/members')) {
        return new Response(JSON.stringify({ success: true, data: sampleMembers }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioMembers />);

    await waitFor(() => expect(screen.getByText('Học viên Một')).toBeInTheDocument());
    expect(screen.getByText('Học viên Hai')).toBeInTheDocument();
    expect(screen.getByText('PRO')).toBeInTheDocument();
  });

  it('hides the page content for a staff member without MEMBER:VIEW', async () => {
    currentClassroom = unauthorizedStaffClassroom;
    const fetchSpy = vi.spyOn(globalThis, 'fetch');

    render(<StudioMembers />);

    expect(screen.getByText(/không có quyền xem danh sách thành viên/i)).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('removes a member after confirmation and refreshes the list', async () => {
    let removeCalled = false;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      if (url.includes('/studio/members/student-1/remove') && method === 'POST') {
        removeCalled = true;
        return new Response(JSON.stringify({ success: true, data: {} }), { status: 200 });
      }
      if (url.includes('/studio/members')) {
        return new Response(JSON.stringify({ success: true, data: sampleMembers }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioMembers />);
    await waitFor(() => expect(screen.getByText('Học viên Một')).toBeInTheDocument());

    fireEvent.click(screen.getByLabelText('Xóa Học viên Một khỏi lớp'));
    await waitFor(() => expect(screen.getByText(/Xác nhận thao tác/)).toBeInTheDocument());

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Xác nhận' }));
    });

    await waitFor(() => expect(removeCalled).toBe(true));
  });

  it('shows an unblock action for a BLOCKED member and calls the unblock endpoint', async () => {
    let unblockCalled = false;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      if (url.includes('/studio/members/student-2/unblock') && method === 'POST') {
        unblockCalled = true;
        return new Response(JSON.stringify({ success: true, data: {} }), { status: 200 });
      }
      if (url.includes('/studio/members')) {
        return new Response(JSON.stringify({ success: true, data: sampleMembers }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioMembers />);
    await waitFor(() => expect(screen.getByText('Học viên Hai')).toBeInTheDocument());

    fireEvent.click(screen.getByText('Mở khóa'));
    await waitFor(() => expect(screen.getByText(/Xác nhận thao tác/)).toBeInTheDocument());

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Xác nhận' }));
    });

    await waitFor(() => expect(unblockCalled).toBe(true));
  });

  it('R14-01: a MEMBER:EDIT delegate is not offered remove/block on a STAFF row, but the OWNER is', async () => {
    const membersWithStaff = [
      ...sampleMembers,
      {
        id: 'cm-3',
        userId: 'staff-2',
        role: 'STAFF',
        state: 'ACTIVE',
        joinedAt: new Date().toISOString(),
        userFullName: 'Trợ giảng Hai',
        userEmail: 'staff2@test.local',
        isPro: false,
      },
    ];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (input.toString().includes('/studio/members')) {
        return new Response(JSON.stringify({ success: true, data: membersWithStaff }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    currentClassroom = { ...ownerClassroom, userRole: 'STAFF', studioPermissions: ['MEMBER:VIEW', 'MEMBER:EDIT'] } as Classroom;
    const delegateView = render(<StudioMembers />);
    await waitFor(() => expect(screen.getByText('Trợ giảng Hai')).toBeInTheDocument());
    expect(screen.getByLabelText('Xóa Học viên Một khỏi lớp')).toBeInTheDocument();
    expect(screen.queryByLabelText('Xóa Trợ giảng Hai khỏi lớp')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Chặn Trợ giảng Hai')).not.toBeInTheDocument();
    delegateView.unmount();

    currentClassroom = ownerClassroom;
    render(<StudioMembers />);
    await waitFor(() => expect(screen.getByLabelText('Xóa Trợ giảng Hai khỏi lớp')).toBeInTheDocument());
    expect(screen.getByLabelText('Chặn Trợ giảng Hai')).toBeInTheDocument();
  });

  const memberRow = (n: number, extra: Record<string, unknown> = {}) => ({
    id: `cm-${n}`, userId: `user-${n}`, role: 'STUDENT', state: 'ACTIVE', joinedAt: new Date().toISOString(),
    userFullName: `Thành viên ${n}`, userEmail: `m${n}@test.local`, isPro: false, ...extra,
  });

  it('R20-03: reads the paged envelope, shows "Hiển thị x / total" and appends the next page with "Xem thêm"', async () => {
    const urls: string[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/studio/members')) {
        urls.push(url);
        const page = Number(new URL(url, 'http://x').searchParams.get('page') || '0');
        const members = page === 0 ? [memberRow(1), memberRow(2)] : [memberRow(3), memberRow(2)];
        return new Response(JSON.stringify({
          success: true,
          data: { members, total: 3, page, size: 2, hasNext: page === 0 },
        }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioMembers />);
    await waitFor(() => expect(screen.getByText('Thành viên 1')).toBeInTheDocument());
    expect(screen.getByTestId('member-count')).toHaveTextContent('Hiển thị 2 / 3 thành viên');
    expect(urls[0]).toContain('page=0');
    expect(urls[0]).toContain('size=50');

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Xem thêm/ }));
    });

    await waitFor(() => expect(screen.getByText('Thành viên 3')).toBeInTheDocument());
    // page 1 repeated member 2: it is shown once
    expect(screen.getAllByText('Thành viên 2')).toHaveLength(1);
    expect(screen.getByTestId('member-count')).toHaveTextContent('Hiển thị 3 / 3 thành viên');
    expect(urls[1]).toContain('page=1');
    expect(screen.queryByRole('button', { name: /Xem thêm/ })).not.toBeInTheDocument();
  });

  it('R20-03: search and the state filter are applied on the server (q= / state= query parameters)', async () => {
    const urls: string[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/studio/members')) {
        urls.push(url);
        return new Response(JSON.stringify({ success: true, data: { members: [memberRow(1)], total: 1, page: 0, size: 50, hasNext: false } }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioMembers />);
    await waitFor(() => expect(screen.getByText('Thành viên 1')).toBeInTheDocument());
    expect(urls[0]).not.toContain('q=');
    expect(urls[0]).not.toContain('state=');

    // debounced search
    fireEvent.change(screen.getByLabelText('Tìm thành viên'), { target: { value: 'Nguyễn' } });
    await waitFor(() => expect(urls.some((u) => u.includes('q=Nguy'))).toBe(true));

    fireEvent.change(screen.getByLabelText('Lọc theo trạng thái'), { target: { value: 'BLOCKED' } });
    await waitFor(() => expect(urls.some((u) => u.includes('state=BLOCKED') && u.includes('q=Nguy'))).toBe(true));
  });
});
