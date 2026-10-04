import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { ClassroomLayout } from '../pages/ClassroomLayout';
import { StudioLayout } from '../pages/studio/StudioLayout';
import type { Classroom } from '../types';

// A class SUSPENDED by a platform admin: its owner (the only person the server still shows it to) sees a calm,
// read-only banner with the admin's reason and a "Tạm khóa" chip - in the class itself and in the Studio.

let currentPath = '/classes/demo-class/feed';

vi.mock('react-router-dom', () => ({
  useParams: () => ({ slug: 'demo-class', id: 'class-1' }),
  useNavigate: () => vi.fn(),
  useLocation: () => ({ pathname: currentPath, search: '', hash: '', state: null, key: 'k' }),
  Outlet: () => <div data-testid="outlet" />,
  NavLink: ({ children, to }: { children?: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
  Link: ({ children, to }: { children?: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
  Navigate: ({ to }: { to: string }) => <div data-testid="navigate" data-to={to} />,
}));

const owner = { id: 'owner-1', fullName: 'Chủ Lớp', email: 'owner@test.local', role: 'USER', status: 'ACTIVE' };
vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: owner, isLoading: false }),
}));

const base: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 4,
  isOwner: true,
  isMember: true,
  userRole: 'OWNER',
  memberState: 'ACTIVE',
  createdAt: '2026-01-01T00:00:00Z',
};
const suspended: Classroom = { ...base, status: 'SUSPENDED', suspendedReason: 'Nội dung vi phạm bản quyền', suspendedAt: '2026-10-02T01:00:00Z' };

const serve = (data: Classroom) =>
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = input.toString();
    if (url.includes('/classes/slug/demo-class') || url.endsWith('/classes/class-1')) {
      return new Response(JSON.stringify({ success: true, data }), { status: 200 });
    }
    return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
  });

const BANNER = 'Lớp đang bị tạm khóa bởi quản trị nền tảng: Nội dung vi phạm bản quyền';

describe('suspended class, seen by its owner', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('ClassroomLayout + header show the read-only banner with the reason and a "Tạm khóa" chip', async () => {
    currentPath = '/classes/demo-class/feed';
    serve(suspended);
    render(<ClassroomLayout />);

    expect(await screen.findByText(BANNER)).toBeInTheDocument();
    expect(screen.getByTestId('suspended-notice')).toHaveAttribute('role', 'status');
    expect(screen.getByTestId('suspended-notice')).toHaveTextContent('Từ 02/10/2026');
    expect(screen.getByTestId('badge-suspended')).toHaveTextContent('Tạm khóa');
    expect(screen.getByTestId('outlet')).toBeInTheDocument();
  });

  it('shows neither banner nor chip for an ACTIVE class', async () => {
    currentPath = '/classes/demo-class/feed';
    serve(base);
    render(<ClassroomLayout />);
    await waitFor(() => expect(screen.getByTestId('outlet')).toBeInTheDocument());
    expect(screen.queryByTestId('suspended-notice')).not.toBeInTheDocument();
    expect(screen.queryByTestId('badge-suspended')).not.toBeInTheDocument();
  });

  it('StudioLayout shows the banner above the page and the chip in the sidebar', async () => {
    currentPath = '/studio/classes/class-1/overview';
    serve(suspended);
    render(<StudioLayout />);

    expect(await screen.findByText(BANNER)).toBeInTheDocument();
    expect(screen.getByTestId('badge-suspended')).toBeInTheDocument();
    expect(screen.getByTestId('outlet')).toBeInTheDocument();
  });
});
