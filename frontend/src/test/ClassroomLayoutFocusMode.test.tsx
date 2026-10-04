import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { ClassroomLayout } from '../pages/ClassroomLayout';
import type { Classroom } from '../types';

/**
 * Focus mode: a lesson being studied and an exam attempt being taken hide the class masthead and tab strip for someone inside
 * the class; every other page (and every visitor) keeps the full header with its gating.
 */

const mockLocation = { pathname: '/classes/demo-class/feed', search: '', hash: '', state: null, key: 'k' };

vi.mock('react-router-dom', () => ({
  useParams: () => ({ slug: 'demo-class' }),
  useNavigate: () => vi.fn(),
  useLocation: () => mockLocation,
  Outlet: () => <div data-testid="outlet" />,
  NavLink: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
  Link: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
}));

const mockAuth = { user: { id: 'u1', fullName: 'Student', email: 's@test.local', role: 'STUDENT', status: 'ACTIVE' }, isLoading: false };
vi.mock('../context/AuthContext', () => ({ useAuth: () => mockAuth }));

const base: Classroom = {
  id: 'class-1', ownerId: 'owner-1', slug: 'demo-class', title: 'Demo Class', status: 'ACTIVE', memberCount: 1,
  createdAt: new Date().toISOString(),
};

const renderAt = async (pathname: string, classroom: Partial<Classroom>) => {
  mockLocation.pathname = pathname;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) =>
    input.toString().includes('/classes/slug/demo-class')
      ? new Response(JSON.stringify({ success: true, data: { ...base, ...classroom } }), { status: 200 })
      : new Response(JSON.stringify({ success: true, data: [] }), { status: 200 }));
  render(<ClassroomLayout />);
  await waitFor(() => expect(screen.getByTestId('outlet')).toBeInTheDocument());
};

const member = { isMember: true, memberState: 'ACTIVE', userRole: 'STUDENT' } as Partial<Classroom>;

describe('ClassroomLayout — focus mode', () => {
  beforeEach(() => vi.restoreAllMocks());

  it.each([
    '/classes/demo-class/learn/lessons/l-1',
    '/classes/demo-class/exams/e-1/attempt',
  ])('hides the masthead and tab strip for a member on %s', async (path) => {
    await renderAt(path, member);
    expect(screen.queryByRole('navigation', { name: 'Các khu vực trong lớp học' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Thông tin lớp học' })).not.toBeInTheDocument();
  });

  it.each([
    '/classes/demo-class/learn',
    '/classes/demo-class/exams/e-1/result',
  ])('keeps the full header on %s', async (path) => {
    await renderAt(path, member);
    expect(screen.getByRole('navigation', { name: 'Các khu vực trong lớp học' })).toBeInTheDocument();
  });

  it('keeps the full header (and its gating) for a visitor on a lesson route', async () => {
    await renderAt('/classes/demo-class/learn/lessons/l-1', { isMember: false, accessType: 'FREE', visibility: 'PUBLIC' } as Partial<Classroom>);
    expect(screen.getByRole('navigation', { name: 'Các khu vực trong lớp học' })).toBeInTheDocument();
    expect(screen.getByText('Tham gia lớp ngay')).toBeInTheDocument();
  });
});
