import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { LearnTab } from '../pages/classroom/LearnTab';
import type { Classroom, Course, Lesson } from '../types';

/**
 * Connecty "Khóa học": clicking a course goes straight into learning (the first lesson not completed yet), with no
 * separate overview screen; a course the learner cannot open stays on the tab with its access conditions. The
 * Miễn phí / Trả phí chips filter the grid.
 */

const mockClassroom: Classroom = {
  id: 'class-1', ownerId: 'owner-1', ownerName: 'Cô Lan', slug: 'demo-class', title: 'Demo Class', status: 'ACTIVE',
  memberCount: 1, createdAt: new Date().toISOString(),
};

const mockNavigate = vi.fn();
vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: mockClassroom }),
  useNavigate: () => mockNavigate,
  Link: (props: { to: string; children?: React.ReactNode }) => React.createElement('a', { href: props.to }, props.children),
}));

const mockUser = { id: 'u1', fullName: 'Học viên' };
vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: mockUser }),
}));

const lesson = (id: string, completed: boolean): Lesson =>
  ({ id, sectionId: 's', courseId: 'c', title: `Bài ${id}`, type: 'VIDEO', durationMinutes: 5, position: 0, completed }) as Lesson;

const free: Course = {
  id: 'c-free', classId: 'class-1', title: 'Khóa miễn phí', accessMode: 'FREE', status: 'PUBLISHED', position: 0, canLearn: true,
  totalLessons: 3, completedLessons: 1, progressPercentage: 33,
  sections: [{ id: 's1', courseId: 'c-free', title: 'Chương 1', position: 0, lessons: [lesson('l1', true), lesson('l2', false), lesson('l3', false)] }],
} as Course;
const paid: Course = {
  id: 'c-paid', classId: 'class-1', title: 'Khóa trả phí', accessMode: 'PURCHASE_REQUIRED', status: 'PUBLISHED', position: 1, canLearn: false,
  accessReason: 'NOT_PURCHASED', totalLessons: 2, completedLessons: 0, progressPercentage: 0,
  sections: [{ id: 's2', courseId: 'c-paid', title: 'Chương trả phí', position: 0, lessons: [lesson('p1', false), lesson('p2', false)] }],
} as Course;

describe('LearnTab — open a course straight into learning', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    mockNavigate.mockReset();
    window.alert = vi.fn();
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.endsWith('/classes/class-1/courses')) return new Response(JSON.stringify({ success: true, data: [paid, free] }), { status: 200 });
      const detail = [free, paid].find((c) => url.endsWith(`/courses/${c.id}`));
      if (detail) return new Response(JSON.stringify({ success: true, data: detail }), { status: 200 });
      return new Response('{}', { status: 404 });
    });
  });

  it('features the course in progress and resumes it at the first unfinished lesson', async () => {
    render(<LearnTab />);
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Học tiếp' })).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /Khóa miễn phí/ })).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(screen.getByRole('button', { name: 'Học tiếp' }));
    expect(mockNavigate).toHaveBeenCalledWith('/classes/demo-class/learn/lessons/l2');
  });

  it('clicking a learnable course card navigates into its lesson; a locked course stays with its access conditions', async () => {
    render(<LearnTab />);
    await waitFor(() => expect(screen.getByRole('button', { name: /Khóa miễn phí/ })).toHaveAttribute('aria-pressed', 'true'));

    fireEvent.click(screen.getByRole('button', { name: /Khóa trả phí/ }));
    await waitFor(() => expect(screen.getByText('Mua khóa học tại Cửa hàng')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /Khóa trả phí/ })).toHaveAttribute('aria-pressed', 'true');
    expect(mockNavigate).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: /Khóa miễn phí/ }));
    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/classes/demo-class/learn/lessons/l2'));
  });

  it('filters the grid with the Miễn phí / Trả phí chips', async () => {
    render(<LearnTab />);
    await waitFor(() => expect(screen.getByRole('button', { name: /Khóa trả phí/ })).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /Miễn phí · 1/ }));
    expect(screen.queryByRole('button', { name: /Khóa trả phí/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Khóa miễn phí/ })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Trả phí · 1/ }));
    expect(screen.queryByRole('button', { name: /Khóa miễn phí/ })).not.toBeInTheDocument();
  });
});
