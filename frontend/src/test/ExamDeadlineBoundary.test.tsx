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

  it('R10-05: a manual submit started before the deadline, whose flush fails after the deadline elapses in flight, still submits and navigates to the result page instead of alerting a hard error', async () => {
    // Plenty of time left when the submit button is clicked (atDeadline computed as false at call
    // start), but the deadline elapses while flushAutosave is awaiting the in-flight save below -
    // exercising the stale-atDeadline scenario that previously rethrew as a manual-submit error.
    await startAttempt('2026-01-01T10:00:02.000Z');

    const textarea = screen.getByPlaceholderText('Nhập câu trả lời tự luận của bạn...');
    fireEvent.change(textarea, { target: { value: 'Câu trả lời trước hạn' } });

    let resolveSave!: (value: Response) => void;
    let submitCalled = false;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : null });

      if (url.includes(`/attempts/${ATTEMPT_ID}/answers`)) {
        // Resolved only after the test advances fake time past endsAt, then fails - simulating the
        // server rejecting a request-body answer once the deadline has passed while this request
        // was in flight.
        return new Promise<Response>((resolve) => {
          resolveSave = () => resolve(
            new Response(JSON.stringify({ success: false, error: { message: 'Thời gian làm bài thi đã kết thúc' } }), {
              status: 400, headers: { 'Content-Type': 'application/json' },
            })
          );
        });
      }
      if (url.includes(`/attempts/${ATTEMPT_ID}/submit`)) {
        submitCalled = true;
        return new Response(
          JSON.stringify({ success: true, data: { id: ATTEMPT_ID, examTitle: 'Kiểm tra giữa kỳ', status: 'GRADING' } }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    // Click submit while time still remains (atDeadline starts false) and let the debounced
    // autosave's flush begin.
    await act(async () => {
      fireEvent.click(screen.getByText('Nộp bài thi'));
    });
    await tick(500);

    // The deadline (10:00:02) elapses while the save is still in flight.
    await tick(2000);

    // Now let the in-flight save resolve with a failure - this is the moment atDeadline must be
    // re-evaluated fresh rather than trusting the stale value from submit's call start.
    await act(async () => {
      resolveSave(undefined as any);
      await Promise.resolve();
    });
    await tick(0);

    expect(submitCalled).toBe(true);
    expect(screen.queryByText('Nộp bài thi')).not.toBeInTheDocument();
  });

  it('R9-02: at the deadline, a flush failure still submits (using the last persisted autosave) and navigates to the result page instead of leaving the student stuck at 00:00', async () => {
    // One second of exam left: the countdown tick fires the deadline auto-submit almost
    // immediately, exercising the exact stale-closure scenario (timeLeft could still read 1 in the
    // tick that triggers this, not 0) that previously caused the flush-failure path to be
    // misclassified as a mid-exam manual submit and rethrow instead of still submitting.
    await startAttempt('2026-01-01T10:00:01.000Z');
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : null });

      if (url.includes(`/attempts/${ATTEMPT_ID}/answers`)) {
        // The server refuses request-body answers at/after the deadline.
        return new Response(JSON.stringify({ success: false, error: { message: 'Thời gian làm bài thi đã kết thúc' } }), {
          status: 400, headers: { 'Content-Type': 'application/json' },
        });
      }
      if (url.includes(`/attempts/${ATTEMPT_ID}/submit`)) {
        return new Response(
          JSON.stringify({ success: true, data: { id: ATTEMPT_ID, examTitle: 'Kiểm tra giữa kỳ', status: 'GRADING' } }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    const textarea = screen.getByPlaceholderText('Nhập câu trả lời tự luận của bạn...');
    fireEvent.change(textarea, { target: { value: 'Câu trả lời sát giờ' } });

    // Let the 1-second deadline elapse - the countdown effect fires handleSubmitRef.current(true).
    await tick(1200);

    // Despite the flush failing, submit must still have been called (the server grades the last
    // persisted autosave / the timeout sweeper finalizes it) and the page must navigate away to
    // the result view instead of getting stuck at 00:00 with just an alert.
    const submitIndex = calls.findIndex((c) => c.url.includes('/submit'));
    expect(submitIndex).toBeGreaterThanOrEqual(0);
    expect(screen.queryByText('Nộp bài thi')).not.toBeInTheDocument();
  });
});
