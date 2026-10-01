import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { StudioGrading } from '../pages/studio/StudioGrading';
import type { Classroom } from '../types';

/**
 * R11-02: StudioGrading used to fan out one /lessons/{id}/submissions request per ASSIGNMENT
 * lesson in every course (via Promise.all), so a single per-course 403 (COURSE:GRADE scoped to a
 * different course) rejected the whole batch and blanked the assignments section with an error -
 * even though the server-side /classes/{classId}/assignment-queue endpoint already filters
 * submissions down to the courses the caller can manage. It also always called both the exam
 * grading queue and the assignment queue regardless of which grant (EXAM:GRADE / COURSE:GRADE)
 * the caller actually held, generating spurious 403s and error banners for a section the caller
 * was never meant to see.
 */

let currentClassroom: Classroom;

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: currentClassroom }),
}));

const baseClassroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 3,
  userRole: 'STAFF',
  studioPermissions: [],
  studioScopedPermissions: [],
  createdAt: new Date().toISOString(),
} as Classroom;

describe('StudioGrading — per-section permission gating (R11-02)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('a COURSE:GRADE-scoped-to-course-A user sees only course A submissions via the class assignment-queue endpoint, with no exam-queue call', async () => {
    currentClassroom = {
      ...baseClassroom,
      studioScopedPermissions: [{ module: 'COURSE', action: 'GRADE', courseId: 'course-A' }],
    };

    let gradingQueueCalled = false;
    let assignmentQueueCalled = false;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/grading-queue')) {
        gradingQueueCalled = true;
        return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
      }
      if (url.includes('/assignment-queue')) {
        assignmentQueueCalled = true;
        return new Response(
          JSON.stringify({
            success: true,
            data: [
              {
                submissionId: 'sub-1', lessonId: 'lesson-1', lessonTitle: 'Bai tap 1', courseId: 'course-A', courseTitle: 'Khoa hoc A',
                learner: { userId: 'student-1', displayName: 'Nguyen Van A' },
                submissionText: 'Bai lam A', attemptNumber: 1, status: 'SUBMITTED', score: null, feedback: null, submittedAt: new Date().toISOString(),
              },
            ],
          }),
          { status: 200 }
        );
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioGrading />);

    await waitFor(() => expect(screen.getByText('Bai lam A')).toBeInTheDocument());
    expect(assignmentQueueCalled).toBe(true);
    expect(gradingQueueCalled).toBe(false);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('a COURSE:GRADE-only user sees the assignments section but never calls the exam grading queue', async () => {
    currentClassroom = {
      ...baseClassroom,
      studioPermissions: ['COURSE:GRADE'],
    };

    let gradingQueueCalled = false;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/grading-queue')) {
        gradingQueueCalled = true;
        return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
      }
      if (url.includes('/assignment-queue')) {
        return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioGrading />);

    await waitFor(() => expect(screen.getByText('Bài tập')).toBeInTheDocument());
    expect(screen.queryByText('Hàng đợi')).not.toBeInTheDocument();
    expect(gradingQueueCalled).toBe(false);
  });

  it('an EXAM:GRADE-only user sees the exam queue but never calls the assignment queue', async () => {
    currentClassroom = {
      ...baseClassroom,
      studioPermissions: ['EXAM:GRADE'],
    };

    let assignmentQueueCalled = false;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/grading-queue')) {
        return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
      }
      if (url.includes('/assignment-queue')) {
        assignmentQueueCalled = true;
        return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioGrading />);

    await waitFor(() => expect(screen.getByText('Hàng đợi')).toBeInTheDocument());
    expect(screen.queryByText('Bài tập')).not.toBeInTheDocument();
    expect(assignmentQueueCalled).toBe(false);
  });

  it('each section shows its own error independently when one endpoint fails', async () => {
    currentClassroom = {
      ...baseClassroom,
      studioPermissions: ['EXAM:GRADE', 'COURSE:GRADE'],
    };

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/grading-queue')) {
        return new Response(JSON.stringify({ error: { code: 'FORBIDDEN', message: 'Không có quyền' } }), { status: 403 });
      }
      if (url.includes('/assignment-queue')) {
        return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioGrading />);

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    // The assignments section still renders its own (empty) state, unaffected by the exam-queue error.
    expect(screen.getByText('Chưa có bài nộp cần chấm.')).toBeInTheDocument();
  });
});
