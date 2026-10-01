import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MembersTab } from '../pages/classroom/MembersTab';
import type { Classroom } from '../types';

/**
 * R8-07: an anonymised member has no userId. The row must not link to
 * /classes/:slug/members/null (a broken profile route) — it must render as a non-interactive row.
 */

const mockClassroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 2,
  userRole: 'STUDENT',
  createdAt: new Date().toISOString(),
} as Classroom;

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: mockClassroom }),
  Link: ({ children, to }: { children?: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

describe('MembersTab — null userId gating (R8-07)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('does not link an anonymised member (no userId) to /members/null', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/members')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: [
              { id: 'm-1', userId: null, role: 'STUDENT', state: 'ACTIVE', joinedAt: new Date().toISOString(), userFullName: null },
              { id: 'm-2', userId: 'user-2', role: 'STUDENT', state: 'ACTIVE', joinedAt: new Date().toISOString(), userFullName: 'Nguyen Van B' },
            ],
          }),
          { status: 200 }
        );
      }
      return new Response('{}', { status: 404 });
    });

    render(<MembersTab />);

    await waitFor(() => expect(screen.getByText('Nguyen Van B')).toBeInTheDocument());

    // The visible member links normally.
    expect(screen.getByText('Nguyen Van B').closest('a')).toHaveAttribute('href', '/classes/demo-class/members/user-2');

    // The anonymised member must render (with a fallback label) but never as a link to .../null.
    expect(screen.getByText('Thành viên ẩn danh')).toBeInTheDocument();
    expect(screen.queryByText('/classes/demo-class/members/null')).not.toBeInTheDocument();
    const links = screen.queryAllByRole('link');
    for (const link of links) {
      expect(link.getAttribute('href')).not.toContain('/members/null');
    }
  });
});

describe('MembersTab — headline count is ACTIVE members only (R15-04)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('does not count BLOCKED/REMOVED rows (visible to class administrators only) as members', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (input.toString().includes('/members')) {
        const joinedAt = new Date().toISOString();
        return new Response(
          JSON.stringify({
            success: true,
            data: [
              { id: 'm-1', userId: 'user-1', role: 'STUDENT', state: 'ACTIVE', joinedAt, userFullName: 'Học viên Một' },
              { id: 'm-2', userId: 'user-2', role: 'STUDENT', state: 'BLOCKED', joinedAt, userFullName: 'Học viên Hai' },
              { id: 'm-3', userId: 'user-3', role: 'STUDENT', state: 'REMOVED', joinedAt, userFullName: 'Học viên Ba' },
            ],
          }),
          { status: 200 }
        );
      }
      return new Response('{}', { status: 404 });
    });

    render(<MembersTab />);

    await waitFor(() => expect(screen.getByText('Học viên Hai')).toBeInTheDocument());
    expect(screen.getByText('1 thành viên')).toBeInTheDocument();
    expect(screen.queryByText('3 thành viên')).not.toBeInTheDocument();
    // The administrator still sees the state of each row.
    expect(screen.getByText('BLOCKED')).toBeInTheDocument();
    expect(screen.getByText('REMOVED')).toBeInTheDocument();
  });
});

describe('MembersTab — administrators see who REMOVED/BLOCKED members are (R16-07)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders the real name of a removed/blocked row (not the anonymous fallback) and marks its state', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (input.toString().includes('/members')) {
        const joinedAt = new Date().toISOString();
        return new Response(
          JSON.stringify({
            success: true,
            data: [
              { id: 'm-1', userId: 'user-1', role: 'STUDENT', state: 'ACTIVE', joinedAt, userFullName: 'Học viên Một' },
              { id: 'm-2', userId: 'user-2', role: 'STUDENT', state: 'BLOCKED', joinedAt, userFullName: 'Học viên Bị Chặn' },
              { id: 'm-3', userId: 'user-3', role: 'STUDENT', state: 'REMOVED', joinedAt, userFullName: 'Học viên Đã Xóa' },
            ],
          }),
          { status: 200 }
        );
      }
      return new Response('{}', { status: 404 });
    });

    render(<MembersTab />);

    await waitFor(() => expect(screen.getByText('Học viên Bị Chặn')).toBeInTheDocument());
    expect(screen.getByText('Học viên Đã Xóa')).toBeInTheDocument();
    expect(screen.queryByText('Thành viên ẩn danh')).not.toBeInTheDocument();
    // The administrator can still open their profile.
    expect(screen.getByText('Học viên Bị Chặn').closest('a')).toHaveAttribute('href', '/classes/demo-class/members/user-2');
    // The state stays visible and is colour-coded so it is not mistaken for an active member.
    expect(screen.getByText('BLOCKED')).toHaveClass('text-rose-600');
    expect(screen.getByText('REMOVED')).toHaveClass('text-amber-700');
    expect(screen.getByText('ACTIVE')).not.toHaveClass('text-rose-600');
  });
});