import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { StudioExams } from '../pages/studio/StudioExams';
import type { Classroom, Exam } from '../types';

/**
 * R14-15: StudioExams' "Chạy thử" used to POST /exams/:id/attempts?preview=true itself and THEN
 * navigate to the attempt page, which POSTs it again - two preview attempts per click. The button
 * must now only navigate; the attempt page is the single place that starts (or resumes) the one
 * preview attempt.
 */

const mockNavigate = vi.fn();

const ownerClassroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 3,
  userRole: 'OWNER',
  studioPermissions: [],
  studioScopedPermissions: [],
  createdAt: new Date().toISOString(),
} as Classroom;

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: ownerClassroom }),
  useNavigate: () => mockNavigate,
}));

const draftExam: Exam = {
  id: 'exam-1',
  classId: 'class-1',
  title: 'Kỳ thi giữa kỳ',
  durationMinutes: 45,
  attemptLimit: 1,
  audienceScope: 'ALL',
  status: 'DRAFT',
  passScore: 50,
  canEnter: false,
  userAttemptsCount: 0,
  questionCount: 3,
  createdAt: new Date().toISOString(),
} as Exam;

describe('StudioExams — "Chạy thử" only navigates (R14-15)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    mockNavigate.mockReset();
  });

  it('navigates to the attempt page in preview mode without creating a preview attempt itself', async () => {
    const calls: Array<{ url: string; method: string }> = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      calls.push({ url, method: init?.method ?? 'GET' });
      if (url.includes('/classes/class-1/exams')) {
        return new Response(JSON.stringify({ success: true, data: [draftExam] }), { status: 200 });
      }
      if (url.includes('/courses') || url.includes('/segments')) {
        return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioExams />);
    await waitFor(() => expect(screen.getByText('Kỳ thi giữa kỳ')).toBeInTheDocument());

    fireEvent.click(screen.getByText('Chạy thử'));

    expect(mockNavigate).toHaveBeenCalledTimes(1);
    expect(mockNavigate).toHaveBeenCalledWith('/classes/demo-class/exams/exam-1/attempt?preview=1');
    // No attempt was started from Studio: the attempt page owns that single call.
    expect(calls.filter((c) => c.method === 'POST' && c.url.includes('/attempts'))).toHaveLength(0);
  });
});
