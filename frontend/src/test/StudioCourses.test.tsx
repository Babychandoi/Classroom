import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { StudioCourses } from '../pages/studio/StudioCourses';
import type { Classroom, Course } from '../types';

/**
 * R6-01: a course-scoped-only staff member (studioScopedPermissions: COURSE:EDIT on course-X,
 * no class-wide studioPermissions) must NOT see "Tạo khóa học mới" (class-wide COURSE:CREATE)
 * but MUST see the per-course "Thêm chương" affordance on course-X and NOT on course-Y.
 */

const mockClassroom: Classroom = {
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

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: mockClassroom }),
}));

const courseX: Course = {
  id: 'course-X',
  classId: 'class-1',
  title: 'Course X',
  status: 'DRAFT',
  accessMode: 'FREE',
} as Course;

const courseY: Course = {
  id: 'course-Y',
  classId: 'class-1',
  title: 'Course Y',
  status: 'DRAFT',
  accessMode: 'FREE',
} as Course;

describe('StudioCourses — course-scoped-only staff gating (R6-01)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('hides the class-wide "create course" button but shows per-course edit only for the granted course', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/courses')) {
        return new Response(JSON.stringify({ success: true, data: [courseX, courseY] }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioCourses />);

    await waitFor(() => expect(screen.getByText('Course X')).toBeInTheDocument());
    expect(screen.getByText('Course Y')).toBeInTheDocument();

    // Class-wide action: not granted class-wide, so the button must not render.
    expect(screen.queryByText('Tạo khóa học mới')).not.toBeInTheDocument();

    // Per-course action: "Thêm chương" ("Add section") should appear once, scoped to course-X.
    const addSectionButtons = screen.getAllByText('Thêm chương');
    expect(addSectionButtons).toHaveLength(1);
  });
});

describe('StudioCourses — R13-03 archive/delete lifecycle actions', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('archives a DRAFT course after in-page confirmation and calls the archive endpoint', async () => {
    let archiveCalled = false;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      if (url.endsWith('/courses/course-X/archive') && method === 'POST') {
        archiveCalled = true;
        return new Response(JSON.stringify({ success: true, data: { ...courseX, status: 'ARCHIVED' } }), { status: 200 });
      }
      if (url.includes('/courses') && method === 'GET') {
        return new Response(JSON.stringify({ success: true, data: [courseX] }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioCourses />);

    await waitFor(() => expect(screen.getByText('Course X')).toBeInTheDocument());

    fireEvent.click(screen.getByText('Lưu trữ'));
    expect(screen.getByText(/Bạn có chắc chắn muốn/)).toBeInTheDocument();
    fireEvent.click(screen.getByText('Xác nhận'));

    await waitFor(() => expect(archiveCalled).toBe(true));
  });
});

/**
 * R17-02: after creating a section/lesson the expanded course must show it straight away. The handlers
 * used to refetch only the course list, leaving the expanded detail stale until it was collapsed and
 * re-expanded. New sections/lessons must also be appended after the existing ones (the section
 * position used to be the constant 1).
 */
describe('StudioCourses — R17-02 expanded course refresh after create', () => {
  type SectionRow = { id: string; courseId: string; title: string; position: number; lessons: Array<Record<string, unknown>> };

  const setupServer = (initialSections: SectionRow[]) => {
    const sections = [...initialSections];
    const posts: Array<{ url: string; body: any }> = [];
    let detailGets = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      const ok = (data: unknown) => new Response(JSON.stringify({ success: true, data }), { status: 200 });
      if (url.endsWith('/courses/course-X') && method === 'GET') {
        detailGets += 1;
        return ok({ ...courseX, sections });
      }
      if (url.endsWith('/courses/course-X/sections') && method === 'POST') {
        const body = JSON.parse(String(init?.body));
        posts.push({ url, body });
        const created = { id: `section-${sections.length + 1}`, courseId: 'course-X', title: body.title, position: body.position, lessons: [] };
        sections.push(created);
        return ok(created);
      }
      const lessonPost = url.match(/\/sections\/([^/]+)\/lessons$/);
      if (lessonPost && method === 'POST') {
        const body = JSON.parse(String(init?.body));
        posts.push({ url, body });
        const target = sections.find((s) => s.id === lessonPost[1])!;
        const created = { id: `lesson-${target.lessons.length + 1}`, sectionId: target.id, courseId: 'course-X', title: body.title, type: body.type, position: body.position, durationMinutes: 0, completed: false };
        target.lessons.push(created);
        return ok(created);
      }
      if (url.includes('/classes/class-1/courses') && method === 'GET') return ok([courseX]);
      return new Response('{}', { status: 404 });
    });
    return { posts, detailGets: () => detailGets };
  };

  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('shows a new section in the already-expanded course and appends it after the existing ones', async () => {
    const server = setupServer([
      { id: 'section-1', courseId: 'course-X', title: 'Chương một', position: 1, lessons: [] },
      { id: 'section-2', courseId: 'course-X', title: 'Chương hai', position: 2, lessons: [] },
    ]);
    render(<StudioCourses />);
    await waitFor(() => expect(screen.getByText('Course X')).toBeInTheDocument());

    fireEvent.click(screen.getByText('Xem bài học'));
    await waitFor(() => expect(screen.getByText('Chương hai')).toBeInTheDocument());

    fireEvent.click(screen.getByText('Thêm chương'));
    fireEvent.change(screen.getByPlaceholderText(/Tên chương học mới/), { target: { value: 'Chương ba' } });
    fireEvent.click(screen.getByText('Lưu chương'));

    await waitFor(() => expect(screen.getByText('Chương ba')).toBeInTheDocument());
    expect(server.posts[0].body).toMatchObject({ title: 'Chương ba', position: 3 });
  });

  it('reads the existing sections first to pick the position when the course is collapsed', async () => {
    const server = setupServer([{ id: 'section-1', courseId: 'course-X', title: 'Chương một', position: 1, lessons: [] }]);
    render(<StudioCourses />);
    await waitFor(() => expect(screen.getByText('Course X')).toBeInTheDocument());

    fireEvent.click(screen.getByText('Thêm chương'));
    fireEvent.change(screen.getByPlaceholderText(/Tên chương học mới/), { target: { value: 'Chương hai' } });
    fireEvent.click(screen.getByText('Lưu chương'));

    await waitFor(() => expect(server.posts).toHaveLength(1));
    expect(server.posts[0].body.position).toBe(2);
    // Still collapsed: nothing is expanded behind the user's back.
    expect(screen.queryByText('Chương hai')).not.toBeInTheDocument();
  });

  it('shows a new lesson in the expanded course without collapsing and re-expanding', async () => {
    const server = setupServer([
      {
        id: 'section-1', courseId: 'course-X', title: 'Chương một', position: 1,
        lessons: [{ id: 'lesson-0', sectionId: 'section-1', courseId: 'course-X', title: 'Bài đầu', type: 'TEXT', position: 1, durationMinutes: 0, completed: false }],
      },
    ]);
    render(<StudioCourses />);
    await waitFor(() => expect(screen.getByText('Course X')).toBeInTheDocument());

    fireEvent.click(screen.getByText('Xem bài học'));
    await waitFor(() => expect(screen.getByText(/Bài đầu/)).toBeInTheDocument());

    fireEvent.click(screen.getByText('Thêm bài học'));
    fireEvent.change(screen.getByLabelText('Tên bài học'), { target: { value: 'Bài mới' } });
    fireEvent.change(screen.getByLabelText('Loại bài học'), { target: { value: 'TEXT' } });
    fireEvent.click(screen.getByText('Tạo bài'));

    await waitFor(() => expect(screen.getByText(/Bài mới · TEXT/)).toBeInTheDocument());
    const lessonPost = server.posts.find((p) => p.url.endsWith('/sections/section-1/lessons'))!;
    expect(lessonPost.body).toMatchObject({ title: 'Bài mới', position: 2 });
    // The expanded detail was re-read (initial expand + refresh after create).
    expect(server.detailGets()).toBeGreaterThanOrEqual(2);
  });
});
