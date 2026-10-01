import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { LearnTab } from '../pages/classroom/LearnTab';
import type { Classroom, Course } from '../types';

/**
 * R18-03: the course cards were click-only <div>s, so a keyboard user could never open any course but the
 * auto-selected first one. They are real buttons now (Tab reaches them, Enter/Space activates them natively).
 */

const mockClassroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 1,
  createdAt: new Date().toISOString(),
};

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: mockClassroom }),
  useNavigate: () => vi.fn(),
  Link: (props: { to: string; children?: React.ReactNode }) => React.createElement('a', { href: props.to }, props.children),
}));

// Stable identity: LearnTab refetches whenever the user object changes.
const mockUser = { id: 'u1', fullName: 'Học viên' };
vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: mockUser }),
}));

function course(id: string, title: string): Course {
  return {
    id, classId: 'class-1', title, accessMode: 'FREE', status: 'PUBLISHED', position: 0, canLearn: true,
    totalLessons: 2, completedLessons: 1, progressPercentage: 50, description: `Mô tả ${title}`,
    sections: [{ id: `s-${id}`, courseId: id, title: `Chương của ${title}`, position: 0, lessons: [] }],
  } as Course;
}
const courses = [course('c1', 'Khóa A'), course('c2', 'Khóa B'), course('c3', 'Khóa C')];

describe('LearnTab course cards (R18-03)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    window.alert = vi.fn();
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.endsWith('/classes/class-1/courses')) return new Response(JSON.stringify({ success: true, data: courses }), { status: 200 });
      const detail = courses.find((c) => url.endsWith(`/courses/${c.id}`));
      if (detail) return new Response(JSON.stringify({ success: true, data: detail }), { status: 200 });
      return new Response('{}', { status: 404 });
    });
  });

  it('renders every course as a focusable button, marking the selected one with aria-pressed', async () => {
    render(<LearnTab />);
    await waitFor(() => expect(screen.getByRole('button', { name: /Khóa A/ })).toHaveAttribute('aria-pressed', 'true'));

    const cards = ['Khóa A', 'Khóa B', 'Khóa C'].map((title) => screen.getByRole('button', { name: new RegExp(title) }));
    for (const card of cards) {
      expect(card.tagName).toBe('BUTTON');
      expect(card).toHaveAttribute('type', 'button');
      expect(card.tabIndex).toBeGreaterThanOrEqual(0);
      expect(card.className).toContain('focus-visible:ring-2');
    }
    expect(cards[1]).toHaveAttribute('aria-pressed', 'false');
    expect(cards[2]).toHaveAttribute('aria-pressed', 'false');
  });

  it('activating a card selects it and loads its curriculum', async () => {
    render(<LearnTab />);
    await waitFor(() => expect(screen.getByText('Chương của Khóa A')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /Khóa C/ }));

    await waitFor(() => expect(screen.getByText('Chương của Khóa C')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /Khóa C/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /Khóa A/ })).toHaveAttribute('aria-pressed', 'false');
  });
});
