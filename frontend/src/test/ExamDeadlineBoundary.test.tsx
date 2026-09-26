import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, act, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ExamAttemptPage } from '../pages/classroom/ExamAttemptPage';

/**
 * The server refuses request-body answers at or after the attempt deadline and grades only the
 * answers autosave has persisted. These tests pin the behaviours that keep a last-second answer
 * from being silently lost: the countdown is derived from the server's `endsAt`, and a pending
 * answer is flushed to the server before any submit — including the one the deadline triggers.
 */
describe('Exam attempt deadline boundary', () => {
  const ATTEMPT_ID = 'att-1';
  let calls: Array<{ method: string; url: string; body: any }>;

  const renderPage = () =>
    render(
      <MemoryRouter initialEntries={['/classes/lop-1/exams/exam-1/attempt']}>
        <Routes>
          <Route path="/classes/:slug/exams/:examId/attempt" element={<ExamAttemptPage />} />
        </Routes>
      </MemoryRouter>
    );

  /** Advances fake time and lets the resulting promise chains settle. */
  const tick = async (ms: number) => {
    await act(async () => {
      vi.advanceTimersByTime(ms);
    });
    for (let i = 0; i < 5; i++) {
      await act(async () => {
        await Promise.resolve();
      });
    }
  };

  const mockApi = (endsAt: string) => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : null });

      if (url.includes('/exams/exam-1/attempts') && method === 'POST') {
        return new Response(
          JSON.stringify({
            success: true,
            data: {
              id: ATTEMPT_ID,
              examId: 'exam-1',
              examTitle: 'Kiểm tra giữa kỳ',
              userId: 'student-1',
              status: 'IN_PROGRESS',
              endsAt,
              answers: [],
              questions: [
                { id: 'q-1', questionText: 'Thủ đô Việt Nam?', type: 'ESSAY', points: 10, options: [] },
              ],
            },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url.includes(`/attempts/${ATTEMPT_ID}/answers`)) {
        return new Response(JSON.stringify({ success: true, data: {} }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      if (url.includes(`/attempts/${ATTEMPT_ID}/submit`)) {
        return new Response(
          JSON.stringify({
            success: true,
            data: { id: ATTEMPT_ID, examTitle: 'Kiểm tra giữa kỳ', status: 'GRADING', endsAt },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });
  };

  const startAttempt = async (endsAt: string) => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] });
    vi.setSystemTime(new Date('2026-01-01T10:00:00.000Z'));
    mockApi(endsAt);
    renderPage();
    await tick(0);
  };

  beforeEach(() => {
    calls = [];
    localStorage.clear();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    vi.useRealTimers();
    localStorage.clear();
  });

  it('shows the time remaining until the server deadline and counts it down', async () => {
    await startAttempt('2026-01-01T10:02:00.000Z');

    expect(screen.getByText('02:00')).toBeInTheDocument();

    await tick(90_000);

    expect(screen.getByText('00:30')).toBeInTheDocument();
  });

  it('flushes a pending answer to the server before the deadline-triggered submit', async () => {
    // One second of exam left, so the deadline tick lands before a debounce started at t=800ms
    // (which would only be due at t=1300ms).
    await startAttempt('2026-01-01T10:00:01.000Z');
    await tick(800);

    const textarea = screen.getByPlaceholderText('Nhập câu trả lời tự luận của bạn...');
    fireEvent.change(textarea, { target: { value: 'Hà Nội' } });

    // Until it is saved, the student is told the answer is not yet on the server.
    expect(screen.getByRole('alert')).toBeInTheDocument();

    // The deadline arrives before the debounce would have fired.
    await tick(300);

    const saveIndex = calls.findIndex((c) => c.url.includes('/answers'));
    const submitIndex = calls.findIndex((c) => c.url.includes('/submit'));
    // The answer reached the server as a persisted autosave, which is the only form the server
    // will grade once the deadline has passed.
    expect(saveIndex).toBeGreaterThanOrEqual(0);
    expect(submitIndex).toBeGreaterThanOrEqual(0);
    expect(saveIndex).toBeLessThan(submitIndex);
    expect(calls[saveIndex].body.answers['q-1']).toBe('Hà Nội');
  });

  it('flushes a pending answer before a manual submit as well', async () => {
    await startAttempt('2026-01-01T10:30:00.000Z');

    const textarea = screen.getByPlaceholderText('Nhập câu trả lời tự luận của bạn...');
    fireEvent.change(textarea, { target: { value: 'Hà Nội' } });

    // Submit immediately, while the 500 ms autosave debounce is still pending.
    await act(async () => {
      fireEvent.click(screen.getByText('Nộp bài thi'));
    });
    await tick(0);

    const saveIndex = calls.findIndex((c) => c.url.includes('/answers'));
    const submitIndex = calls.findIndex((c) => c.url.includes('/submit'));
    expect(saveIndex).toBeGreaterThanOrEqual(0);
    expect(submitIndex).toBeGreaterThanOrEqual(0);
    expect(saveIndex).toBeLessThan(submitIndex);
  });

  it('keeps an edit made during an in-flight autosave dirty and flushes its newer revision', async () => {
    await startAttempt('2026-01-01T10:30:00.000Z');
    let finishFirstSave!: () => void;
    let firstSaveStarted!: () => void;
    const firstSaveStartedPromise = new Promise<void>((resolve) => { firstSaveStarted = resolve; });
    let saveCount = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : null });
      if (url.includes(`/attempts/${ATTEMPT_ID}/answers`)) {
        saveCount += 1;
        if (saveCount === 1) {
          firstSaveStarted();
          await new Promise<void>((resolve) => { finishFirstSave = resolve; });
        }
        return new Response(JSON.stringify({ success: true, data: {} }), {
          status: 200, headers: { 'Content-Type': 'application/json' },
        });
      }
      if (url.includes(`/attempts/${ATTEMPT_ID}/submit`)) {
        return new Response(JSON.stringify({ success: true, data: { id: ATTEMPT_ID, status: 'GRADING' } }), {
          status: 200, headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response('{}', { status: 404 });
    });

    const textarea = screen.getByPlaceholderText('Nhập câu trả lời tự luận của bạn...');
    fireEvent.change(textarea, { target: { value: 'Trả lời lần một' } });
    await tick(500);
    await firstSaveStartedPromise;
    fireEvent.change(textarea, { target: { value: 'Trả lời mới nhất' } });

    let submitPromise!: Promise<void>;
    await act(async () => {
      fireEvent.click(screen.getByText('Nộp bài thi'));
      await Promise.resolve();
    });
    finishFirstSave();
    await tick(0);
    submitPromise = Promise.resolve();
    await submitPromise;

    const saves = calls.filter((call) => call.url.includes('/answers'));
    expect(saves).toHaveLength(2);
    expect(saves[0].body.answers['q-1']).toBe('Trả lời lần một');
    expect(saves[1].body.answers['q-1']).toBe('Trả lời mới nhất');
    expect(calls.findIndex((call) => call.url.includes('/submit'))).toBeGreaterThan(calls.indexOf(saves[1]));
  });

  it('stops manual submission and displays warning if autosave flush fails at deadline', async () => {
    await startAttempt('2026-01-01T10:30:00.000Z');
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : null });

      if (url.includes(`/attempts/${ATTEMPT_ID}/answers`)) {
        return new Response(JSON.stringify({ success: false, error: { message: 'Thời gian làm bài thi đã kết thúc' } }), {
          status: 400, headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response('{}', { status: 404 });
    });

    const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});

    const textarea = screen.getByPlaceholderText('Nhập câu trả lời tự luận của bạn...');
    fireEvent.change(textarea, { target: { value: 'Câu trả lời sát giờ' } });

    await act(async () => {
      fireEvent.click(screen.getByText('Nộp bài thi'));
    });
    await tick(0);

    // The submit call must NOT proceed when flush fails
    const submitIndex = calls.findIndex((c) => c.url.includes('/submit'));
    expect(submitIndex).toBe(-1);
    expect(alertSpy).toHaveBeenCalled();
  });
});
