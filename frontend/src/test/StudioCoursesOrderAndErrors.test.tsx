import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { StudioCourses } from '../pages/studio/StudioCourses';
import type { Classroom, Course } from '../types';

/**
 * R18-04: course order can be changed with Lên/Xuống arrows (class-wide COURSE:EDIT only - the endpoint takes the
 * whole class's ordered id list). R18-06: an action error is shown only in the course it happened in.
 */

let currentClassroom: Classroom;
vi.mock('react-router-dom', () => ({
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

const cardOf = (title: string) => screen.getByRole('heading', { name: title }).closest('article') as HTMLElement;
const titlesInOrder = () => screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent);

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

describe('StudioCourses — reorder courses (R18-04)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    currentClassroom = owner;
  });

  it('moves a course up and sends the whole ordered id list to the reorder endpoint', async () => {
    const calls = mockApi();
    render(<StudioCourses />);
    await waitFor(() => expect(screen.getByText('Course C')).toBeInTheDocument());
    expect(titlesInOrder()).toEqual(['Course A', 'Course B', 'Course C']);

    // The ends cannot move further out.
    expect(screen.getByRole('button', { name: 'Chuyển khóa học "Course A" lên trên' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Chuyển khóa học "Course C" xuống dưới' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'Chuyển khóa học "Course C" lên trên' }));

    await waitFor(() => expect(titlesInOrder()).toEqual(['Course A', 'Course C', 'Course B']));
    const put = calls.find((c) => c.method === 'PUT');
    expect(put?.url).toContain('/classes/class-1/courses/reorder');
    expect(put?.body).toEqual(['a', 'c', 'b']);
  });

  it('moves a course down', async () => {
    const calls = mockApi();
    render(<StudioCourses />);
    await waitFor(() => expect(screen.getByText('Course A')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'Chuyển khóa học "Course A" xuống dưới' }));

    await waitFor(() => expect(titlesInOrder()).toEqual(['Course B', 'Course A', 'Course C']));
    expect(calls.find((c) => c.method === 'PUT')?.body).toEqual(['b', 'a', 'c']);
  });

  it('shows the arrows to staff holding class-wide COURSE:EDIT', async () => {
    currentClassroom = classWideStaff;
    mockApi();
    render(<StudioCourses />);
    await waitFor(() => expect(screen.getByText('Course B')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Chuyển khóa học "Course B" lên trên' })).toBeInTheDocument();
  });

  it('hides the arrows from course-scoped-only staff (reordering is class-wide)', async () => {
    currentClassroom = scopedStaff;
    mockApi();
    render(<StudioCourses />);
    await waitFor(() => expect(screen.getByText('Course B')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /Chuyển khóa học/ })).not.toBeInTheDocument();
  });

  it('keeps the old order and shows a page-level error when the server refuses', async () => {
    mockApi((url, method) => {
      if (method === 'PUT' && url.endsWith('/courses/reorder')) {
        return new Response(JSON.stringify({ success: false, error: { code: 'BAD_REQUEST', message: 'Danh sách thứ tự khóa học không hợp lệ' } }), { status: 400 });
      }
      return undefined;
    });
    render(<StudioCourses />);
    await waitFor(() => expect(screen.getByText('Course B')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'Chuyển khóa học "Course B" lên trên' }));

    const banner = await screen.findByText('Danh sách thứ tự khóa học không hợp lệ');
    expect(banner.closest('[role="alert"]')).not.toBeNull();
    // Unscoped: it is not inside any single course's card.
    for (const title of ['Course A', 'Course B', 'Course C']) {
      expect(within(cardOf(title)).queryByText('Danh sách thứ tự khóa học không hợp lệ')).not.toBeInTheDocument();
    }
    expect(titlesInOrder()).toEqual(['Course A', 'Course B', 'Course C']);
  });
});

describe('StudioCourses — action errors are scoped to their course (R18-06)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    currentClassroom = owner;
  });

  it('shows a failed archive only inside the affected course, not in every card', async () => {
    mockApi((url, method) => {
      if (method === 'POST' && url.endsWith('/courses/b/archive')) {
        return new Response(JSON.stringify({ success: false, error: { code: 'CONFLICT', message: 'Khóa B đang bị chặn' } }), { status: 409 });
      }
      return undefined;
    });
    render(<StudioCourses />);
    await waitFor(() => expect(screen.getByText('Course B')).toBeInTheDocument());

    fireEvent.click(within(cardOf('Course B')).getByRole('button', { name: /Lưu trữ/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Xác nhận' }));

    await waitFor(() => expect(within(cardOf('Course B')).getByText('Khóa B đang bị chặn')).toBeInTheDocument());
    expect(screen.getAllByText('Khóa B đang bị chặn')).toHaveLength(1);
    expect(within(cardOf('Course A')).queryByText('Khóa B đang bị chặn')).not.toBeInTheDocument();
    expect(within(cardOf('Course C')).queryByText('Khóa B đang bị chặn')).not.toBeInTheDocument();
  });

  it('R18-07: a delete refused with a 409 explains itself in the affected course', async () => {
    const message = 'Không thể xóa khóa học vì đang có quyền trợ giảng gắn riêng với khóa này: Lan (COURSE:EDIT).';
    mockApi((url, method) => {
      if (method === 'DELETE' && url.endsWith('/courses/a')) {
        return new Response(JSON.stringify({ success: false, error: { code: 'CONFLICT', message } }), { status: 409 });
      }
      return undefined;
    });
    render(<StudioCourses />);
    await waitFor(() => expect(screen.getByText('Course A')).toBeInTheDocument());

    fireEvent.click(within(cardOf('Course A')).getByRole('button', { name: /Xóa/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Xác nhận' }));

    await waitFor(() => expect(within(cardOf('Course A')).getByText(message)).toBeInTheDocument());
    expect(within(cardOf('Course B')).queryByText(message)).not.toBeInTheDocument();
  });
});
