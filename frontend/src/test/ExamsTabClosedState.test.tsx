import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { ExamsTab } from '../pages/classroom/ExamsTab';
import type { Classroom, Exam } from '../types';

/**
 * R14-05 / R14-13: after an exam is closed (or the class archived) new attempts are refused, but a
 * learner who already has a running attempt still gets canEnter=true and must see "Vào thi ngay"
 * (to resume). Everyone else must see a clear reason instead of the generic audience message.
 */

let mockClassroom: Classroom;

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: mockClassroom }),
  Link: (props: { to: string; children?: React.ReactNode }) => React.createElement('a', { href: props.to }, props.children),
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'u1', fullName: 'HV', email: 'hv@test.local', role: 'STUDENT', status: 'ACTIVE' } }),
}));

function exam(overrides: Partial<Exam>): Exam {
  return {
    id: 'exam-1',
    classId: 'class-1',
    title: 'Kỳ thi cuối kỳ',
    durationMinutes: 45,
    attemptLimit: 1,
    audienceScope: 'ALL',
    status: 'PUBLISHED',
    passScore: 50,
    canEnter: false,
    userAttemptsCount: 0,
    questionCount: 3,
    createdAt: new Date().toISOString(),
    ...overrides,
  } as Exam;
}

function mockExams(exams: Exam[]) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
    new Response(JSON.stringify({ success: true, data: exams }), { status: 200 }));
}

describe('ExamsTab — closed exam / archived class (R14-05, R14-13)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    mockClassroom = {
      id: 'class-1', ownerId: 'owner-1', slug: 'demo-class', title: 'Demo', status: 'ACTIVE',
      memberCount: 1, createdAt: new Date().toISOString(),
    } as Classroom;
  });

  it('shows a clear "closed" reason for a CLOSED exam the learner cannot enter', async () => {
    mockExams([exam({ status: 'CLOSED', canEnter: false })]);
    render(<ExamsTab />);
    await waitFor(() => expect(screen.getByText('Kỳ thi đã đóng, không nhận lượt làm bài mới')).toBeInTheDocument());
    expect(screen.queryByText('Vào thi ngay')).not.toBeInTheDocument();
  });

  it('still offers "Vào thi ngay" on a CLOSED exam when the learner has a running attempt to resume', async () => {
    mockExams([exam({ status: 'CLOSED', canEnter: true, userAttemptsCount: 1 })]);
    render(<ExamsTab />);
    await waitFor(() => expect(screen.getByText('Vào thi ngay')).toBeInTheDocument());
  });

  it('explains that an ARCHIVED class starts no new attempts', async () => {
    mockClassroom = { ...mockClassroom, status: 'ARCHIVED' } as Classroom;
    mockExams([exam({ status: 'PUBLISHED', canEnter: false })]);
    render(<ExamsTab />);
    await waitFor(() =>
      expect(screen.getByText('Lớp học đã được lưu trữ; không thể bắt đầu lượt làm bài mới')).toBeInTheDocument());
  });
});
