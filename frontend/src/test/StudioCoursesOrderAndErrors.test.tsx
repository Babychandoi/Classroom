import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { StudioCourses } from '../pages/studio/StudioCourses';
import type { Classroom, Course } from '../types';

/**
 * R18-04: course order can be changed from each course's "⋯" menu (class-wide COURSE:EDIT only - the endpoint takes the
 * whole class's ordered id list). The ordering errors are page-level (they concern the whole list).
 */

let currentClassroom: Classroom;
vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router-dom')>()),
  useOutletContext: () => ({ classroom: currentClassroom }),
}));

const base = {
  id: 'class-1', ownerId: 'owner-1', slug: 'demo-class', title: 'Demo Class', status: 'ACTIVE', memberCount: 3,
  createdAt: new Date().toISOString(),
};
const owner = { ...base, userRole: 'OWNER' } as Classroom;
const classWideStaff = { ...base, userRole: 'STAFF', studioPermissions: ['COURSE:EDIT'], studioScopedPermissions: [] } as Classroom;
const scopedStaff = {
  ...base, userRole: 'STAFF', studioPermissions: [],
  studioScopedPermissions: [{ module: 'COURSE', action: 'EDIT', courseId: 'a' }],
} as Classroom;

const course = (id: string, title: string): Course => ({ id, classId: 'class-1', title, status: 'DRAFT', accessMode: 'FREE', position: 0 }) as Course;
const courses = [course('a', 'Course A'), course('b', 'Course B'), course('c', 'Course C')];

const titlesInOrder = () => screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent);
const openMenu = (title: string) => fireEvent.click(screen.getByRole('button', { name: `Thao tác khác cho khóa học "${title}"` }));

function mockApi(overrides: (url: string, method: string, body: unknown) => Response | undefined = () => undefined) {
  const calls: { url: string; method: string; body: unknown }[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = input.toString();
    const method = (init?.method || 'GET').toUpperCase();
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ url, method, body });
    const custom = overrides(url, method, body);
    if (custom) return custom;
    if (method === 'GET' && url.endsWith('/classes/class-1/courses')) return new Response(JSON.stringify({ success: true, data: courses }), { status: 200 });
    if (method === 'PUT' && url.endsWith('/classes/class-1/courses/reorder')) return new Response(JSON.stringify({ success: true, data: null }), { status: 200 });
    return new Response('{}', { status: 404 });
  });
  return calls;
}
const renderList = () => render(<MemoryRouter><StudioCourses /></MemoryRouter>);

describe('StudioCourses - reorder courses (R18-04)', () => {
  beforeEach(() => { vi.restoreAllMocks(); currentClassroom = owner; });

  it('moves a course up and sends the whole ordered id list to the reorder endpoint', async () => {
    const calls = mockApi();
    renderList();
    await waitFor(() => expect(screen.getByText('Course C')).toBeInTheDocument());
    expect(titlesInOrder()).toEqual(['Course A', 'Course B', 'Course C']);

    // The ends cannot move further out.
    openMenu('Course A');
    expect(screen.getByRole('menuitem', { name: 'Chuyển khóa học "Course A" lên trên' })).toBeDisabled();
    fireEvent.keyDown(document, { key: 'Escape' });
    openMenu('Course C');
    expect(screen.getByRole('menuitem', { name: 'Chuyển khóa học "Course C" xuống dưới' })).toBeDisabled();

    fireEvent.click(screen.getByRole('menuitem', { name: 'Chuyển khóa học "Course C" lên trên' }));

    await waitFor(() => expect(titlesInOrder()).toEqual(['Course A', 'Course C', 'Course B']));
    const put = calls.find((c) => c.method === 'PUT');
    expect(put?.url).toContain('/classes/class-1/courses/reorder');
    expect(put?.body).toEqual(['a', 'c', 'b']);
  });

  it('moves a course down', async () => {
    const calls = mockApi();
    renderList();
    await waitFor(() => expect(screen.getByText('Course A')).toBeInTheDocument());
    openMenu('Course A');
    fireEvent.click(screen.getByRole('menuitem', { name: 'Chuyển khóa học "Course A" xuống dưới' }));

    await waitFor(() => expect(titlesInOrder()).toEqual(['Course B', 'Course A', 'Course C']));
    expect(calls.find((c) => c.method === 'PUT')?.body).toEqual(['b', 'a', 'c']);
  });

  it('shows the move items to staff holding class-wide COURSE:EDIT', async () => {
    currentClassroom = classWideStaff;
    mockApi();
    renderList();
    await waitFor(() => expect(screen.getByText('Course B')).toBeInTheDocument());
    openMenu('Course B');
    expect(screen.getByRole('menuitem', { name: 'Chuyển khóa học "Course B" lên trên' })).toBeInTheDocument();
  });

  it('hides the move items from course-scoped-only staff (reordering is class-wide)', async () => {
    currentClassroom = scopedStaff;
    mockApi();
    renderList();
    await waitFor(() => expect(screen.getByText('Course B')).toBeInTheDocument());
    openMenu('Course B');
    expect(screen.queryByRole('menuitem', { name: /Chuyển khóa học/ })).not.toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /Xem như học viên/ })).toBeInTheDocument();
  });

  it('keeps the old order and shows a page-level error when the server refuses', async () => {
    mockApi((url, method) => {
      if (method === 'PUT' && url.endsWith('/courses/reorder')) {
        return new Response(JSON.stringify({ success: false, error: { code: 'BAD_REQUEST', message: 'Danh sách thứ tự khóa học không hợp lệ' } }), { status: 400 });
      }
      return undefined;
    });
    renderList();
    await waitFor(() => expect(screen.getByText('Course B')).toBeInTheDocument());
    openMenu('Course B');
    fireEvent.click(screen.getByRole('menuitem', { name: 'Chuyển khóa học "Course B" lên trên' }));

    const banner = await screen.findByText('Danh sách thứ tự khóa học không hợp lệ');
    expect(banner.closest('[role="alert"]')).not.toBeNull();
    expect(titlesInOrder()).toEqual(['Course A', 'Course B', 'Course C']);
  });
});
