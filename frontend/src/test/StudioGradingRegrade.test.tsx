import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { StudioGrading } from '../pages/studio/StudioGrading';
import type { Classroom } from '../types';

/**
 * R13-07: correcting an already-PUBLISHED exam result requires a mandatory, non-blank "reason",
 * surfaced through StudioGrading's "Kết quả đã công bố" section -> "Sửa điểm" action, which opens
 * the same grading form with a required reason textarea and posts `reason` in the grade request.
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
  userRole: 'OWNER',
  studioPermissions: ['EXAM:GRADE'],
  studioScopedPermissions: [],
  createdAt: new Date().toISOString(),
} as Classroom;

describe('StudioGrading — score correction on a PUBLISHED result (R13-07)', () => {
  beforeEach(() => {
    currentClassroom = { ...baseClassroom };
    vi.restoreAllMocks();
  });

  it('lists published results for an exam and requires a reason before submitting a correction', async () => {
    let gradeBody: any = null;

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();

      if (url.includes('/grading-queue')) {
        return new Response(JSON.stringify({ success: true, data: [
          { id: 'att-other', examId: 'exam-1', examTitle: 'Kiểm tra giữa kỳ', userId: 'student-2', learnerDisplayName: 'Học viên B', status: 'SUBMITTED', score: null },
        ] }), { status: 200 });
      }
      if (url.includes('/assignment-queue')) {
        return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
      }
      if (url.includes('/exams/exam-1/published-attempts')) {
        return new Response(JSON.stringify({ success: true, data: [
          { id: 'att-published-1', examId: 'exam-1', examTitle: 'Kiểm tra giữa kỳ', userId: 'student-1', learnerDisplayName: 'Học viên A', status: 'PUBLISHED', score: 80 },
        ] }), { status: 200 });
      }
      if (url.includes('/attempts/att-published-1/grading')) {
        return new Response(JSON.stringify({ success: true, data: {
          id: 'att-published-1', examId: 'exam-1', examTitle: 'Kiểm tra giữa kỳ', userId: 'student-1', learnerDisplayName: 'Học viên A',
          status: 'PUBLISHED', score: 80,
          questions: [{ id: 'q-essay', questionText: 'Trình bày', type: 'ESSAY', points: 20 }],
          answers: [{ questionId: 'q-essay', studentAnswer: 'Bài làm', pointsAwarded: 16, teacherFeedback: null }],
        } }), { status: 200 });
      }
      if (url.includes('/attempts/att-published-1/grade') && method === 'POST') {
        gradeBody = JSON.parse((init?.body as string) ?? '{}');
        return new Response(JSON.stringify({ success: true, data: {
          id: 'att-published-1', examId: 'exam-1', examTitle: 'Kiểm tra giữa kỳ', status: 'PUBLISHED', score: 95,
        } }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioGrading />);

    await waitFor(() => expect(screen.getByText('Kết quả đã công bố')).toBeInTheDocument());
    fireEvent.click(screen.getByText('Xem kết quả đã công bố'));

    await waitFor(() => expect(screen.getByText(/Học viên A/)).toBeInTheDocument());
    fireEvent.click(screen.getAllByText('Sửa điểm')[0]);

    await waitFor(() => expect(screen.getByText('Trình bày')).toBeInTheDocument());
    // The mandatory reason field appears because the loaded attempt is PUBLISHED.
    expect(screen.getByText(/Lý do sửa điểm/)).toBeInTheDocument();

    // Attempting to submit without filling the reason must not call the grade endpoint (the
    // reason textarea is `required`, and submitGrades additionally guards on it explicitly).
    const scoreInput = screen.getByLabelText(/Điểm/);
    fireEvent.change(scoreInput, { target: { value: '19' } });
    // The list item's "Sửa điểm" button and the form's submit button (also labeled "Sửa điểm"
    // once correctingPublished is true) now both exist; the submit button is the <button> form
    // control specifically.
    const saveButton = screen.getAllByText('Sửa điểm').find((el) => el.tagName === 'BUTTON' && el.getAttribute('type') !== 'button') as HTMLElement;
    fireEvent.click(saveButton);
    expect(gradeBody).toBeNull();

    // Fill the reason and submit again.
    const reasonInput = screen.getByPlaceholderText(/khiếu nại/);
    fireEvent.change(reasonInput, { target: { value: 'Học viên khiếu nại, chấm lại' } });
    fireEvent.click(saveButton);

    await waitFor(() => expect(gradeBody).not.toBeNull());
    expect(gradeBody.reason).toBe('Học viên khiếu nại, chấm lại');
    expect(gradeBody.scores['q-essay']).toBe(19);
  });
});
