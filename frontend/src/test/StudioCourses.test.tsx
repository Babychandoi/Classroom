import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { StudioCourses } from '../pages/studio/StudioCourses';
import type { Classroom, Course } from '../types';

/**
 * The course list is a plain list: one card per course, a primary "Chỉnh sửa" link into the wizard (course-scoped staff only
 * get it on the courses they may edit) and no per-course management buttons any more (those live in the wizard's step 4).
 * R6-01: a course-scoped-only staff member must NOT see "Tạo khóa học" (class-wide COURSE:CREATE).
 */

let currentClassroom: Classroom;
vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router-dom')>()),
  useOutletContext: () => ({ classroom: currentClassroom }),
}));

const base = { id: 'class-1', ownerId: 'owner-1', slug: 'demo-class', title: 'Demo Class', status: 'ACTIVE', memberCount: 3, createdAt: new Date().toISOString() };
const scopedStaff = {
  ...base, userRole: 'STAFF', studioPermissions: [], studioScopedPermissions: [{ module: 'COURSE', action: 'EDIT', courseId: 'course-X' }],
} as Classroom;
const owner = { ...base, userRole: 'OWNER' } as Classroom;

const course = (over: Partial<Course>): Course =>
  ({ id: 'course-X', classId: 'class-1', title: 'Course X', status: 'DRAFT', accessMode: 'FREE', totalLessons: 4, createdAt: '2026-03-04T01:00:00Z', ...over }) as Course;

function mockApi(courses: Course[], products: unknown[] = []) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = input.toString();
    const ok = (data: unknown) => new Response(JSON.stringify({ success: true, data }), { status: 200 });
    if (url.endsWith('/classes/class-1/courses')) return ok(courses);
    if (url.endsWith('/classes/class-1/studio/products')) return ok(products);
    return new Response('{}', { status: 404 });
  });
}
const renderList = () => render(<MemoryRouter><StudioCourses /></MemoryRouter>);
const cardOf = (title: string) => screen.getByRole('heading', { name: title }).closest('article') as HTMLElement;

describe('StudioCourses - plain list', () => {
  beforeEach(() => { vi.restoreAllMocks(); currentClassroom = scopedStaff; });

  it('hides class-wide creation and offers "Chỉnh sửa" only on the course the staff member may edit (R6-01)', async () => {
    mockApi([course({}), course({ id: 'course-Y', title: 'Course Y' })]);
    renderList();
    await waitFor(() => expect(screen.getByText('Course X')).toBeInTheDocument());

    expect(screen.queryByRole('link', { name: /Tạo khóa học/ })).not.toBeInTheDocument();
    expect(within(cardOf('Course X')).getByRole('link', { name: /Chỉnh sửa/ })).toHaveAttribute('href', '/studio/classes/class-1/courses/course-X/edit');
    expect(within(cardOf('Course Y')).queryByRole('link', { name: /Chỉnh sửa/ })).not.toBeInTheDocument();
  });

  it('carries no per-course management buttons any more', async () => {
    currentClassroom = owner;
    mockApi([course({})]);
    renderList();
    await waitFor(() => expect(screen.getByText('Course X')).toBeInTheDocument());
    for (const name of [/Lưu trữ/, /Xóa/, /Thêm chương/, /Xem bài học/, /Xuất bản/, /^Sửa$/]) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
    }
    expect(screen.getByRole('link', { name: /Tạo khóa học/ })).toHaveAttribute('href', '/studio/classes/class-1/courses/new');
  });

  it('shows cover, badges, lesson count, price of a paid course and where each course opens in the wizard', async () => {
    currentClassroom = owner;
    mockApi(
      [
        course({}),
        course({ id: 'p', title: 'Khóa trả phí', status: 'PUBLISHED', accessMode: 'PURCHASE_REQUIRED', productId: 'prod-1', totalLessons: 9 }),
        course({ id: 'a', title: 'Khóa cũ', status: 'ARCHIVED' }),
      ],
      [{ id: 'prod-1', classId: 'class-1', status: 'PUBLISHED', price: 299000, durationDays: 90, currency: 'VND', title: 'x' }],
    );
    renderList();
    await waitFor(() => expect(screen.getByText('Khóa trả phí')).toBeInTheDocument());

    expect(within(cardOf('Course X')).getByText('4 bài học')).toBeInTheDocument();
    expect(within(cardOf('Course X')).getByText('Miễn phí')).toBeInTheDocument();
    await waitFor(() => expect(within(cardOf('Khóa trả phí')).getByText('299.000đ / 90 ngày')).toBeInTheDocument());
    expect(within(cardOf('Khóa trả phí')).getByText('9 bài học')).toBeInTheDocument();
    // Drafts reopen at their furthest step (no ?step), published ones at step 1, archived ones at the management step.
    expect(within(cardOf('Course X')).getByRole('link', { name: /Chỉnh sửa/ })).toHaveAttribute('href', '/studio/classes/class-1/courses/course-X/edit');
    expect(within(cardOf('Khóa trả phí')).getByRole('link', { name: /Chỉnh sửa/ })).toHaveAttribute('href', '/studio/classes/class-1/courses/p/edit?step=1');
    expect(within(cardOf('Khóa cũ')).getByRole('link', { name: /Quản lý/ })).toHaveAttribute('href', '/studio/classes/class-1/courses/a/edit?step=4');
  });

  it('has a "⋯" menu with "Xem như học viên"', async () => {
    currentClassroom = owner;
    mockApi([course({})]);
    renderList();
    await waitFor(() => expect(screen.getByText('Course X')).toBeInTheDocument());
    screen.getByRole('button', { name: /Thao tác khác cho khóa học "Course X"/ }).click();
    expect(await screen.findByRole('menuitem', { name: /Xem như học viên/ })).toHaveAttribute('href', '/classes/demo-class/learn');
  });
});
