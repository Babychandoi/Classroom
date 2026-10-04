import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, act, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ExamAttemptPage } from '../pages/classroom/ExamAttemptPage';
import { ExamResultPage } from '../pages/classroom/ExamResultPage';

/**
 * R13-04: StudioExams' "Chạy thử" navigates to /classes/:slug/exams/:examId/attempt?preview=1.
 * In preview mode the page must skip the resumeOnly/"Bắt đầu làm bài" confirmation flow entirely
 * (ExamService.startAttempt never consults resumeOnly on the isStaffPreview branch — it always
 * starts a fresh preview attempt), show a clear "Chế độ xem thử" banner, and never call the
 * resumeOnly endpoint.
 */
describe('ExamAttemptPage — preview mode (R13-04)', () => {
  let calls: Array<{ method: string; url: string }>;

  const renderApp = (initialEntry = '/classes/lop-1/exams/exam-1/attempt?preview=1') =>
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

  it('starts a preview attempt directly on mount, skipping the resumeOnly/start-confirmation flow, and shows the preview banner', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      calls.push({ method, url });

      if (url.includes('/exams/exam-1/attempts') && url.includes('preview=true')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: {
              id: 'att-preview-1',
              examId: 'exam-1',
              examTitle: 'Kiểm tra giữa kỳ',
              userId: 'staff-1',
              status: 'IN_PROGRESS',
              isPreview: true,
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
    expect(screen.getByText(/Chế độ xem thử/)).toBeInTheDocument();
    expect(screen.queryByText('Bắt đầu làm bài')).not.toBeInTheDocument();

    // The resumeOnly endpoint must never be called in preview mode.
    const resumeCall = calls.find((c) => c.url.includes('resumeOnly=true'));
    expect(resumeCall).toBeUndefined();
    const previewCall = calls.find((c) => c.url.includes('preview=true') && c.method === 'POST');
    expect(previewCall).toBeDefined();
  });

  it('submitting a preview attempt navigates to the result page with the preview flag, which shows the preview banner', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      calls.push({ method, url });

      if (url.includes('/exams/exam-1/attempts') && url.includes('preview=true')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: {
              id: 'att-preview-2',
              examId: 'exam-1',
              examTitle: 'Kiểm tra giữa kỳ',
              userId: 'staff-1',
              status: 'IN_PROGRESS',
              isPreview: true,
              endsAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
              answers: [],
              questions: [{ id: 'q-1', questionText: 'Thủ đô Việt Nam?', type: 'ESSAY', points: 10, options: [] }],
            },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url.includes('/attempts/att-preview-2/submit')) {
        return new Response(
          JSON.stringify({ success: true, data: { id: 'att-preview-2', examTitle: 'Kiểm tra giữa kỳ', status: 'PUBLISHED', isPreview: true } }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url.includes('/exams/exam-1/my-attempts')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: [{ id: 'att-preview-2', examId: 'exam-1', examTitle: 'Kiểm tra giữa kỳ', status: 'PUBLISHED', isPreview: true, score: 100 }],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    renderApp();
    await waitFor(() => expect(screen.getByText('Nộp bài thi')).toBeInTheDocument());

    // Connecty reskin: "Nộp bài thi" opens a confirmation dialog; confirming it is what submits.
    fireEvent.click(screen.getByText('Nộp bài thi'));
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Xác nhận nộp bài' }));
    });

    await waitFor(() => expect(screen.getByText('Kết quả bài thi')).toBeInTheDocument());
    expect(screen.getByText(/Chế độ xem thử/)).toBeInTheDocument();
  });

  // R19-04: a previewer who may not read the answer key gets the attempt back without score / points; the result
  // page must show the server's notice instead of a percentage (or "Chờ chấm điểm") and must not list points.
  it('a preview result whose scoring was withheld shows the notice instead of a score', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/exams/exam-1/my-attempts')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: [
              {
                id: 'att-preview-3', examId: 'exam-1', examTitle: 'Kiểm tra giữa kỳ', status: 'SUBMITTED', isPreview: true,
                resultHidden: true, notice: 'Chế độ xem thử: không hiển thị điểm/đáp án cho quyền của bạn', totalPoints: 0,
                answers: [{ questionId: 'q-1', studentAnswer: 'A' }],
              },
            ],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    renderApp('/classes/lop-1/exams/exam-1/result?attemptId=att-preview-3&preview=1');

    await waitFor(() => expect(screen.getByText('Kết quả bài thi')).toBeInTheDocument());
    expect(screen.getByText('Chế độ xem thử: không hiển thị điểm/đáp án cho quyền của bạn')).toBeInTheDocument();
    expect(screen.queryByText('Điểm số đạt được')).not.toBeInTheDocument();
    expect(screen.queryByText(/Chờ chấm điểm/)).not.toBeInTheDocument();
    expect(screen.queryByText(/điểm$/)).not.toBeInTheDocument();
  });
});
