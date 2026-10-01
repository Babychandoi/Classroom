import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { StudioExams } from '../pages/studio/StudioExams';
import type { Classroom, Exam, Question } from '../types';

/**
 * R17-03: adding a question refreshed only the exam list (question count), so the authoring panel -
 * which renders the questions from the per-exam detail - did not list the new question until the panel
 * was closed and reopened. It must reload the detail, and the new question must go after the existing
 * ones (the position used to be the constant 0).
 */

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
  useNavigate: () => vi.fn(),
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
  questionCount: 0,
  createdAt: new Date().toISOString(),
} as Exam;

describe('StudioExams — added question appears in the authoring panel (R17-03)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('reloads the exam detail after adding, so the new question is listed, and appends it after existing ones', async () => {
    const questions: Question[] = [
      { id: 'q-1', examId: 'exam-1', questionText: 'Câu hỏi có sẵn', type: 'ESSAY', points: 10, position: 1, answerKey: null, options: [] },
    ];
    const posts: any[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = init?.method ?? 'GET';
      const ok = (data: unknown) => new Response(JSON.stringify({ success: true, data }), { status: 200 });
      if (method === 'POST' && url.endsWith('/exams/exam-1/questions')) {
        const body = JSON.parse(String(init?.body));
        posts.push(body);
        questions.push({ id: `q-${questions.length + 1}`, examId: 'exam-1', questionText: body.question.questionText, type: body.question.type, points: 10, position: body.question.position, answerKey: null, options: [] });
        return ok({});
      }
      if (url.endsWith('/exams/exam-1')) return ok({ ...draftExam, questionCount: questions.length, questions });
      if (url.includes('/classes/class-1/exams')) return ok([{ ...draftExam, questionCount: questions.length }]);
      if (url.includes('/courses') || url.includes('/segments')) return ok([]);
      return new Response('{}', { status: 404 });
    });

    render(<StudioExams />);
    await waitFor(() => expect(screen.getByText('Kỳ thi giữa kỳ')).toBeInTheDocument());
    fireEvent.click(screen.getByText('Soạn câu hỏi và công bố'));
    await waitFor(() => expect(screen.getByText(/1\. Câu hỏi có sẵn/)).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Nội dung câu hỏi'), { target: { value: 'Tự luận mới' } });
    fireEvent.change(screen.getByLabelText('Loại câu hỏi'), { target: { value: 'ESSAY' } });
    fireEvent.click(screen.getByText('Thêm câu hỏi'));

    await waitFor(() => expect(screen.getByText(/2\. Tự luận mới/)).toBeInTheDocument());
    expect(posts).toHaveLength(1);
    expect(posts[0].question.position).toBe(2);
    // The form is reset for the next question.
    expect((screen.getByLabelText('Nội dung câu hỏi') as HTMLTextAreaElement).value).toBe('');
  });
});
