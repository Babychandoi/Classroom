import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, act, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ExamAttemptPage } from '../pages/classroom/ExamAttemptPage';
import { ExamResultPage } from '../pages/classroom/ExamResultPage';

/**
 * R8-02: ExamAttemptPage must never auto-create an attempt on mount. It first tries to RESUME an
 * existing IN_PROGRESS attempt (resumeOnly=true, which the backend guarantees never creates one);
 * if the backend answers 404 (nothing to resume — first visit, or reload after a previous submit),
 * the page must show an explicit "Bắt đầu làm bài" confirmation instead of silently burning a new
 * attempt. And once a submit completes, the page must navigate away to the result route so a
 * follow-up reload lands on a page that only ever reads, never creates, an attempt.
 */
describe('ExamAttemptPage — resume-only mount and post-submit navigation (R8-02)', () => {
  let calls: Array<{ method: string; url: string }>;

  const renderApp = (initialEntry = '/classes/lop-1/exams/exam-1/attempt') =>
    render(
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/classes/:slug/exams/:examId/attempt" element={<ExamAttemptPage />} />
          <Route path="/classes/:slug/exams/:examId/result" element={<ExamResultPage />} />
        </Routes>
      </MemoryRouter>
    );

  beforeEach(() => {
    calls = [];
    vi.restoreAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('shows an explicit start confirmation instead of creating an attempt when nothing is in progress', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      calls.push({ method, url });

      if (url.includes('/exams/exam-1/attempts') && url.includes('resumeOnly=true')) {
        return new Response(
          JSON.stringify({ success: false, error: { code: 'NOT_FOUND', message: 'Không có lượt làm bài đang diễn ra để tiếp tục' } }),
          { status: 404, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    renderApp();

    await waitFor(() => expect(screen.getByText('Bắt đầu làm bài')).toBeInTheDocument());

    // The mount-time check must have used resumeOnly=true and never created an attempt.
    const resumeCall = calls.find((c) => c.url.includes('resumeOnly=true'));
    expect(resumeCall).toBeDefined();
    expect(resumeCall!.method).toBe('POST');
    const plainStartCall = calls.find((c) => c.url.includes('/exams/exam-1/attempts') && !c.url.includes('resumeOnly'));
    expect(plainStartCall).toBeUndefined();
  });

  it('resumes an existing IN_PROGRESS attempt directly without showing the confirmation', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      calls.push({ method, url });

      if (url.includes('/exams/exam-1/attempts') && url.includes('resumeOnly=true')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: {
              id: 'att-1',
              examId: 'exam-1',
              examTitle: 'Kiểm tra giữa kỳ',
              userId: 'student-1',
              status: 'IN_PROGRESS',
              endsAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
              answers: [],
              questions: [{ id: 'q-1', questionText: 'Thủ đô Việt Nam?', type: 'ESSAY', points: 10, options: [] }],
            },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    renderApp();

    await waitFor(() => expect(screen.getByText('Nộp bài thi')).toBeInTheDocument());
    expect(screen.queryByText('Bắt đầu làm bài')).not.toBeInTheDocument();
  });

  it('explicit "Bắt đầu làm bài" click creates a new attempt via the plain (non-resumeOnly) endpoint', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      calls.push({ method, url });

      if (url.includes('/exams/exam-1/attempts') && url.includes('resumeOnly=true')) {
        return new Response(JSON.stringify({ success: false, error: { code: 'NOT_FOUND', message: 'none' } }), { status: 404 });
      }
      if (url.includes('/exams/exam-1/attempts')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: {
              id: 'att-2',
              examId: 'exam-1',
              examTitle: 'Kiểm tra giữa kỳ',
              userId: 'student-1',
              status: 'IN_PROGRESS',
              endsAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
              answers: [],
              questions: [{ id: 'q-1', questionText: 'Thủ đô Việt Nam?', type: 'ESSAY', points: 10, options: [] }],
            },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    renderApp();
    await waitFor(() => expect(screen.getByText('Bắt đầu làm bài')).toBeInTheDocument());

    await act(async () => {
      fireEvent.click(screen.getByText('Bắt đầu làm bài'));
    });

    await waitFor(() => expect(screen.getByText('Nộp bài thi')).toBeInTheDocument());
    const createCall = calls.find((c) => c.method === 'POST' && c.url.includes('/exams/exam-1/attempts') && !c.url.includes('resumeOnly'));
    expect(createCall).toBeDefined();
  });

  it('navigates to the result route after a successful submit, so a reload never re-creates an attempt', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      calls.push({ method, url });

      if (url.includes('/exams/exam-1/attempts') && url.includes('resumeOnly=true')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: {
              id: 'att-1',
              examId: 'exam-1',
              examTitle: 'Kiểm tra giữa kỳ',
              userId: 'student-1',
              status: 'IN_PROGRESS',
              endsAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
              answers: [],
              questions: [{ id: 'q-1', questionText: 'Thủ đô Việt Nam?', type: 'ESSAY', points: 10, options: [] }],
            },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url.includes('/attempts/att-1/submit')) {
        return new Response(
          JSON.stringify({ success: true, data: { id: 'att-1', examTitle: 'Kiểm tra giữa kỳ', status: 'GRADING' } }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url.includes('/exams/exam-1/my-attempts')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: [{ id: 'att-1', examId: 'exam-1', examTitle: 'Kiểm tra giữa kỳ', status: 'GRADING' }],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    renderApp();
    await waitFor(() => expect(screen.getByText('Nộp bài thi')).toBeInTheDocument());

    await act(async () => {
      fireEvent.click(screen.getByText('Nộp bài thi'));
    });

    // Landing on ExamResultPage's "Kết quả bài thi" heading confirms the navigate(replace) fired.
    await waitFor(() => expect(screen.getByText('Kết quả bài thi')).toBeInTheDocument());
  });
});
