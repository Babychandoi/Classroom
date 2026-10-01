import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { StudioLayout } from '../pages/studio/StudioLayout';
import type { Classroom } from '../types';

/**
 * R6-01: a course-scoped-only staff member (no class-wide studioPermissions, only a
 * studioScopedPermissions grant for COURSE:EDIT on one course) must still see the "Khóa học &
 * Bài giảng" nav item and be authorized to land on /studio/classes/:id/courses — previously
 * StudioLayout gated nav/routes purely off studioPermissions (class-wide only after R5-07),
 * which locked this kind of staff out of Studio entirely.
 */

const mockNavigate = vi.fn();

let currentPath = '/studio/classes/class-1/courses';

vi.mock('react-router-dom', () => ({
  useParams: () => ({ id: 'class-1' }),
  useNavigate: () => mockNavigate,
  useLocation: () => ({ pathname: currentPath }),
  Outlet: () => <div data-testid="studio-outlet">outlet</div>,
  NavLink: ({ children, to }: { children?: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
  Link: ({ children, to }: { children?: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
  Navigate: ({ to }: { to: string }) => <div data-testid="studio-navigate" data-to={to}>navigate</div>,
}));

const mockUser = { id: 'staff-1', fullName: 'Staff', email: 'staff@test.local', role: 'STAFF', status: 'ACTIVE' };

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: mockUser }),
}));

const courseScopedOnlyClassroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 3,
  userRole: 'STAFF',
  studioPermissions: [],
  studioScopedPermissions: [{ module: 'COURSE', action: 'EDIT', courseId: 'course-X' }],
  createdAt: new Date().toISOString(),
};

describe('StudioLayout — course-scoped-only staff (R6-01)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    currentPath = '/studio/classes/class-1/courses';
  });

  it('shows the Courses nav item and authorizes the /courses route for course-scoped-only staff', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/classes/class-1')) {
        return new Response(JSON.stringify({ success: true, data: courseScopedOnlyClassroom }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioLayout />);

    await waitFor(() => expect(screen.getByText('Khóa học & Bài giảng')).toBeInTheDocument());
    // Nav item present means hasAnyStudioPermission granted it via the scoped grant.
    expect(screen.getByText('Khóa học & Bài giảng')).toBeInTheDocument();
    // The route itself must render its Outlet, not the "insufficient permission" banner.
    await waitFor(() => expect(screen.getByTestId('studio-outlet')).toBeInTheDocument());
    expect(screen.queryByText('Không đủ quyền truy cập')).not.toBeInTheDocument();
  });

  it('hides nav items and blocks routes the staff member has no grant for at all', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/classes/class-1')) {
        return new Response(JSON.stringify({ success: true, data: courseScopedOnlyClassroom }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });
    currentPath = '/studio/classes/class-1/store';

    render(<StudioLayout />);

    await waitFor(() => expect(screen.getByText('Không đủ quyền truy cập')).toBeInTheDocument());
    expect(screen.queryByText('Sản phẩm & Đơn hàng')).not.toBeInTheDocument();
    // R7-01: the fallback button must send this staff member somewhere they can actually reach
    // (their scoped COURSE:EDIT grant authorizes /courses), not back to /overview which requires
    // class-wide STUDIO:VIEW they don't have.
    expect(screen.getByText(/Về Khóa học & Bài giảng/).closest('a')).toHaveAttribute('href', '/studio/classes/class-1/courses');
  });

  // R7-01: a staff member with only a scoped grant (no class-wide STUDIO:VIEW) must not be routed
  // to /overview by the Studio index route — that used to hardcode Navigate("overview") and
  // every such staff member landed on "insufficient permission" immediately.
  it('redirects the bare Studio index route to the first authorized page, not /overview', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/classes/class-1')) {
        return new Response(JSON.stringify({ success: true, data: courseScopedOnlyClassroom }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });
    currentPath = '/studio/classes/class-1';

    render(<StudioLayout />);

    await waitFor(() => expect(screen.getByTestId('studio-navigate')).toBeInTheDocument());
    expect(screen.getByTestId('studio-navigate')).toHaveAttribute('data-to', '/studio/classes/class-1/courses');
  });

  // R8-10: a staff member authorized for literally nothing in Studio must not be sent to
  // /overview by the "insufficient permission" fallback button — that page also requires
  // class-wide STUDIO:VIEW they don't have, making it a dead end. The fallback must instead point
  // back to the classroom itself, which every member (including this staff member) can reach.
  it('sends a staff member with zero Studio grants back to the classroom, not /overview', async () => {
    const noGrantsClassroom = {
      ...courseScopedOnlyClassroom,
      studioScopedPermissions: [],
    };
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/classes/class-1')) {
        return new Response(JSON.stringify({ success: true, data: noGrantsClassroom }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });
    currentPath = '/studio/classes/class-1/courses';

    render(<StudioLayout />);

    await waitFor(() => expect(screen.getByText('Không đủ quyền truy cập')).toBeInTheDocument());
    expect(screen.getByText('Về trang lớp học').closest('a')).toHaveAttribute('href', '/classes/demo-class/feed');
  });

  // R11-02: the Grading nav item covers both exam grading (EXAM:GRADE) and assignment grading
  // (COURSE:GRADE, course-scopable) — a staff member holding only a course-scoped COURSE:GRADE
  // grant (no EXAM:GRADE at all) must still see and be able to reach it.
  it('shows the Grading nav item for a staff member with only a scoped COURSE:GRADE grant', async () => {
    const courseGradeOnlyClassroom: Classroom = {
      ...courseScopedOnlyClassroom,
      studioScopedPermissions: [{ module: 'COURSE', action: 'GRADE', courseId: 'course-X' }],
    };
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/classes/class-1')) {
        return new Response(JSON.stringify({ success: true, data: courseGradeOnlyClassroom }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });
    currentPath = '/studio/classes/class-1/grading';

    render(<StudioLayout />);

    await waitFor(() => expect(screen.getByText('Chấm bài')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('studio-outlet')).toBeInTheDocument());
    expect(screen.queryByText('Không đủ quyền truy cập')).not.toBeInTheDocument();
  });
});

// R17-05: below md the sidebar nav is a collapsible menu so it no longer pushes the page content down to
// y~800 on a phone (the CSS breakpoint itself cannot be exercised in jsdom; the toggle contract can).
describe('StudioLayout — collapsible navigation on phones (R17-05)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    currentPath = '/studio/classes/class-1/courses';
  });

  it('keeps the nav collapsed until the menu button is pressed, and names the current page on the button', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/classes/class-1')) {
        return new Response(JSON.stringify({ success: true, data: courseScopedOnlyClassroom }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioLayout />);

    const toggle = await screen.findByRole('button', { name: /Menu Studio/ });
    expect(toggle).toHaveTextContent('Menu Studio · Khóa học & Bài giảng');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    const nav = document.getElementById(toggle.getAttribute('aria-controls')!)!;
    expect(nav).toHaveClass('hidden');
    expect(nav).toHaveClass('md:block'); // always expanded from md up

    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(nav).toHaveClass('block');
    expect(nav).not.toHaveClass('hidden');

    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(nav).toHaveClass('hidden');
  });
});
