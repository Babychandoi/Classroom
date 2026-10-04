import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { StudioStaff } from '../pages/studio/StudioStaff';
import type { Classroom } from '../types';

/**
 * R18-01: the permission dialog is taller than a laptop viewport - it must live in the shared scrollable Modal with
 * its Hủy/Lưu quyền actions in a sticky footer. R18-08: a grant scoped to a course names that course in the list.
 */

const classroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 3,
  userRole: 'OWNER',
  createdAt: new Date().toISOString(),
} as Classroom;

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom }),
}));

const staff = [{
  id: 'a1',
  classId: 'class-1',
  userId: 'staff-1',
  userFullName: 'Trợ Giảng Một',
  userEmail: 'tg1@test.local',
  status: 'ACTIVE',
  assignedAt: '2026-09-01T00:00:00Z',
  permissions: [
    { module: 'COURSE', action: 'EDIT', scopeCourseId: 'course-A' },
    { module: 'COURSE', action: 'PREVIEW', scopeCourseId: 'course-gone' },
    { module: 'FEED', action: 'VIEW' },
  ],
}];
const courses = [
  { id: 'course-A', classId: 'class-1', title: 'Khóa A' },
  { id: 'course-B', classId: 'class-1', title: 'Khóa B' },
];
const members = [
  { id: 'm1', userId: 'student-1', role: 'STUDENT', state: 'ACTIVE', joinedAt: '2026-09-01T00:00:00Z', userFullName: 'Học Viên Một' },
  { id: 'm2', userId: 'staff-1', role: 'STAFF', state: 'ACTIVE', joinedAt: '2026-09-01T00:00:00Z', userFullName: 'Trợ Giảng Một' },
];

function mockApi(capture: { put?: { url: string; body: unknown } } = {}) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = input.toString();
    const method = (init?.method || 'GET').toUpperCase();
    if (method === 'PUT' && url.includes('/staff/')) {
      capture.put = { url, body: JSON.parse(String(init?.body)) };
      return new Response(JSON.stringify({ success: true, data: {} }), { status: 200 });
    }
    if (url.endsWith('/classes/class-1/staff')) return new Response(JSON.stringify({ success: true, data: staff }), { status: 200 });
    if (url.endsWith('/classes/class-1/courses')) return new Response(JSON.stringify({ success: true, data: courses }), { status: 200 });
    if (url.endsWith('/classes/class-1/members')) return new Response(JSON.stringify({ success: true, data: members }), { status: 200 });
    return new Response('{}', { status: 404 });
  });
}

describe('StudioStaff', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('R18-08: a course-scoped grant shows its course title; class-wide grants stay bare', async () => {
    mockApi();
    render(<StudioStaff />);

    await waitFor(() => expect(screen.getByText('Trợ Giảng Một')).toBeInTheDocument());
    expect(screen.getByText('COURSE:EDIT · Khóa A')).toBeInTheDocument();
    // A course this viewer can no longer list is still marked as scoped, not shown as if class-wide.
    expect(screen.getByText('COURSE:PREVIEW · Khóa không còn hiển thị')).toBeInTheDocument();
    expect(screen.getByText('FEED:VIEW')).toBeInTheDocument();
  });

  it('R18-01: the editor is a scrollable dialog whose Lưu quyền button submits the chosen permissions', async () => {
    const capture: { put?: { url: string; body: unknown } } = {};
    mockApi(capture);
    render(<StudioStaff />);
    await waitFor(() => expect(screen.getByText('Trợ Giảng Một')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /Thêm trợ giảng mới/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Phân quyền Trợ giảng' });
    expect(dialog.className).toContain('max-h-[90vh]');
    expect(dialog.className).toContain('overflow-y-auto');

    // The actions sit in a footer that sticks to the bottom of the scrolling panel.
    const save = within(dialog).getByRole('button', { name: 'Lưu quyền' });
    expect(save.parentElement?.className).toContain('sticky');
    expect(save.parentElement?.className).toContain('bottom-0');

    fireEvent.change(within(dialog).getByLabelText('Thành viên'), { target: { value: 'student-1' } });
    fireEvent.click(within(dialog).getAllByRole('checkbox')[0]); // STUDIO:VIEW
    fireEvent.click(save);

    await waitFor(() => expect(capture.put).toBeDefined());
    expect(capture.put!.url).toContain('/classes/class-1/staff/student-1/permissions');
    expect(capture.put!.body).toEqual([{ module: 'STUDIO', action: 'VIEW' }]);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('R18-01: Escape and Hủy close the editor without saving', async () => {
    const capture: { put?: { url: string; body: unknown } } = {};
    mockApi(capture);
    render(<StudioStaff />);
    await waitFor(() => expect(screen.getByText('Trợ Giảng Một')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /Thêm trợ giảng mới/ }));
    await screen.findByRole('dialog');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Thêm trợ giảng mới/ }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Hủy' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(capture.put).toBeUndefined();
  });

  it('offers the Blog and Sự kiện modules with their actions and saves e.g. BLOG:PUBLISH / EVENT:DELETE', async () => {
    const capture: { put?: { url: string; body: unknown } } = {};
    mockApi(capture);
    render(<StudioStaff />);
    await waitFor(() => expect(screen.getByText('Trợ Giảng Một')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /Thêm trợ giảng mới/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Phân quyền Trợ giảng' });
    const blog = within(dialog).getByRole('group', { name: 'Blog' });
    const events = within(dialog).getByRole('group', { name: 'Sự kiện' });
    expect(within(blog).getAllByRole('checkbox')).toHaveLength(5);
    expect(within(events).getAllByRole('checkbox')).toHaveLength(4);
    ['Xem', 'Tạo', 'Sửa', 'Xuất bản', 'Xóa'].forEach((label) => expect(within(blog).getByLabelText(label)).toBeInTheDocument());
    expect(within(events).queryByLabelText('Xuất bản')).not.toBeInTheDocument();

    fireEvent.change(within(dialog).getByLabelText('Thành viên'), { target: { value: 'student-1' } });
    fireEvent.click(within(blog).getByLabelText('Xuất bản'));
    fireEvent.click(within(events).getByLabelText('Xóa'));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Lưu quyền' }));

    await waitFor(() => expect(capture.put).toBeDefined());
    expect(capture.put!.body).toEqual([{ module: 'BLOG', action: 'PUBLISH' }, { module: 'EVENT', action: 'DELETE' }]);
  });
});
