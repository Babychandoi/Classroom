import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, act, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import {
  ExamAttemptPage,
  isTransientSaveError,
  AUTOSAVE_RETRY_BASE_MS,
  AUTOSAVE_RETRY_MAX_MS,
} from '../pages/classroom/ExamAttemptPage';
import { ApiException } from '../api/client';

/**
 * R20-07: an autosave that fails because the backend is unreachable or restarting (network error, 502/503/504, any 5xx) must NOT
 * stay unsaved until the student types again. It is retried with exponential backoff (1 s -> 30 s cap) while the answers stay
 * dirty, immediately on 'online' / tab-visible, and pushed again shortly BEFORE the deadline; the status is a calm
 * "Đang lưu lại…" (role=status), not the alarming unsaved banner (role=alert). Everything here runs on fake timers.
 */
describe('Exam attempt autosave retry (R20-07)', () => {
  const ATTEMPT_ID = 'att-1';
  const START = '2026-01-01T10:00:00.000Z';
  type Call = { method: string; url: string; body: any; at: number };
  let calls: Call[];
  /** What the answers endpoint does right now: 'ok' | 'fail-502' | 'fail-503' | 'network' | 'fail-400'. */
  let saveMode: string;

  const renderPage = () =>
    render(
      <MemoryRouter initialEntries={['/classes/lop-1/exams/exam-1/attempt']}>
        <Routes>
          <Route path="/classes/:slug/exams/:examId/attempt" element={<ExamAttemptPage />} />
        </Routes>
      </MemoryRouter>
    );

  const tick = async (ms: number) => {
    await act(async () => {
      vi.advanceTimersByTime(ms);
    });
    for (let i = 0; i < 6; i++) {
      await act(async () => {
        await Promise.resolve();
      });
    }
  };

  /** Advances time in small steps so the promise chain of each request settles before the next timer is due. */
  const advance = async (ms: number, step = 100) => {
    for (let elapsed = 0; elapsed < ms; elapsed += step) {
      await tick(Math.min(step, ms - elapsed));
    }
  };

  const json = (data: unknown, status = 200) =>
    new Response(JSON.stringify({ success: true, data }), { status, headers: { 'Content-Type': 'application/json' } });

  const mockApi = (endsAt: string) => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : null, at: Date.now() });
      if (url.includes('/exams/exam-1/attempts') && method === 'POST') {
        return json({
          id: ATTEMPT_ID,
          examId: 'exam-1',
          examTitle: 'Kiểm tra giữa kỳ',
          userId: 'student-1',
          status: 'IN_PROGRESS',
          endsAt,
          answers: [],
          questions: [
            { id: 'q-1', questionText: 'Thủ đô Việt Nam?', type: 'ESSAY', points: 5, options: [] },
            { id: 'q-2', questionText: 'Thủ đô Nhật Bản?', type: 'ESSAY', points: 5, options: [] },
          ],
        });
      }
      if (url.includes(`/attempts/${ATTEMPT_ID}/answers`)) {
        if (saveMode === 'network') throw new TypeError('Failed to fetch');
        if (saveMode === 'fail-502') return new Response('Bad Gateway', { status: 502 });
        if (saveMode === 'fail-503') return new Response('Service Unavailable', { status: 503 });
        if (saveMode === 'fail-400') {
          return new Response(
            JSON.stringify({ success: false, error: { code: 'BAD_REQUEST', message: 'Thời gian làm bài thi đã kết thúc' } }),
            { status: 400, headers: { 'Content-Type': 'application/json' } }
          );
        }
        return json({});
      }
      if (url.includes(`/attempts/${ATTEMPT_ID}/submit`)) {
        return json({ id: ATTEMPT_ID, examTitle: 'Kiểm tra giữa kỳ', status: 'GRADING', endsAt });
      }
      return new Response('{}', { status: 404 });
    });
  };

  const startAttempt = async (endsAt: string) => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] });
    vi.setSystemTime(new Date(START));
    mockApi(endsAt);
    renderPage();
    await tick(0);
  };

  const type = (question: number, value: string) => {
    const boxes = screen.getAllByPlaceholderText('Nhập câu trả lời tự luận của bạn...');
    fireEvent.change(boxes[question], { target: { value } });
  };

  const saves = () => calls.filter((c) => c.url.includes('/answers'));

  beforeEach(() => {
    calls = [];
    saveMode = 'ok';
    localStorage.clear();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    vi.useRealTimers();
    localStorage.clear();
  });

  it('classifies which failures are worth retrying', () => {
    expect(isTransientSaveError(new ApiException('NETWORK_ERROR', 'x', undefined, 503))).toBe(true);
    expect(isTransientSaveError(new ApiException('HTTP_ERROR', 'x', undefined, 502))).toBe(true);
    expect(isTransientSaveError(new ApiException('HTTP_ERROR', 'x', undefined, 504))).toBe(true);
    expect(isTransientSaveError(new ApiException('INTERNAL_SERVER_ERROR', 'x', undefined, 500))).toBe(true);
    expect(isTransientSaveError(new ApiException('RATE_LIMITED', 'x', undefined, 429))).toBe(true);
    expect(isTransientSaveError(new ApiException('BAD_REQUEST', 'x', undefined, 400))).toBe(false);
    expect(isTransientSaveError(new ApiException('FORBIDDEN', 'x', undefined, 403))).toBe(false);
    expect(isTransientSaveError(new ApiException('NOT_FOUND', 'x', undefined, 404))).toBe(false);
    expect(isTransientSaveError(new TypeError('Failed to fetch'))).toBe(true);
  });

  it('keeps retrying a save that fails with 502 (backend restart), shows a calm status, and finally saves every answer', async () => {
    await startAttempt('2026-01-01T10:30:00.000Z');
    saveMode = 'fail-502';

    type(0, 'Hà Nội');
    await tick(500); // debounce -> first attempt fails
    expect(saves()).toHaveLength(1);

    // Calm copy, not the alarming banner
    expect(screen.getAllByText(/Đang lưu lại…/).length).toBeGreaterThan(0);
    expect(screen.getByRole('status', { hidden: false })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText(/chưa được ghi nhận/)).not.toBeInTheDocument();

    // the student keeps working while the backend is down
    type(1, 'Tokyo');
    await tick(500);
    const afterSecondEdit = saves().length;
    expect(afterSecondEdit).toBeGreaterThanOrEqual(2);

    // the backend comes back: the next backoff attempt (no further typing!) delivers BOTH answers
    saveMode = 'ok';
    await advance(AUTOSAVE_RETRY_MAX_MS);
    const last = saves()[saves().length - 1];
    expect(last.body.answers).toEqual({ 'q-1': 'Hà Nội', 'q-2': 'Tokyo' });
    expect(screen.getByText('Đã tự động lưu')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();

    // and it stops retrying once everything is saved
    const total = saves().length;
    await advance(60_000, 1000);
    expect(saves()).toHaveLength(total);
  });

  it('backs off exponentially: 1 s, 2 s, 4 s, 8 s, 16 s, then capped at 30 s', async () => {
    await startAttempt('2026-01-01T11:00:00.000Z');
    saveMode = 'fail-503';
    type(0, 'Hà Nội');
    await tick(500);
    const first = saves()[0].at;

    // let the loop run through 7 failures
    await advance(1_000 + 2_000 + 4_000 + 8_000 + 16_000 + 30_000 + 30_000);
    const stamps = saves().map((c) => c.at);
    const gaps = stamps.slice(1).map((t, i) => t - stamps[i]);
    expect(stamps[0]).toBe(first);
    expect(gaps.slice(0, 7)).toEqual([1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000]);
    expect(AUTOSAVE_RETRY_BASE_MS).toBe(1_000);
  });

  it('retries a network failure (fetch rejects) the same way', async () => {
    await startAttempt('2026-01-01T10:30:00.000Z');
    saveMode = 'network';
    type(0, 'Hà Nội');
    await tick(500);
    expect(saves()).toHaveLength(1);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    saveMode = 'ok';
    await tick(1_000);
    expect(saves()).toHaveLength(2);
    expect(screen.getByText('Đã tự động lưu')).toBeInTheDocument();
  });

  it('does NOT retry a definitive refusal (400): it keeps the explicit failure message', async () => {
    await startAttempt('2026-01-01T10:30:00.000Z');
    saveMode = 'fail-400';
    type(0, 'Hà Nội');
    await tick(500);
    expect(saves()).toHaveLength(1);
    await advance(60_000, 1000);
    expect(saves()).toHaveLength(1);
    expect(screen.getByText(/Lưu tự động thất bại/)).toBeInTheDocument();
  });

  it('retries immediately when the browser comes back online, without waiting for the backoff', async () => {
    await startAttempt('2026-01-01T10:30:00.000Z');
    saveMode = 'network';
    type(0, 'Hà Nội');
    await tick(500);
    await advance(1_000 + 2_000); // failures at +0.5 s, +1.5 s, +3.5 s -> the next backoff wait is 4 s
    const before = saves().length;
    expect(before).toBeGreaterThanOrEqual(3);

    saveMode = 'ok';
    await act(async () => {
      window.dispatchEvent(new Event('online'));
    });
    await tick(0);

    expect(saves()).toHaveLength(before + 1);
    expect(saves()[before].body.answers['q-1']).toBe('Hà Nội');
    expect(screen.getByText('Đã tự động lưu')).toBeInTheDocument();
  });

  it('retries immediately when the tab becomes visible again', async () => {
    await startAttempt('2026-01-01T10:30:00.000Z');
    saveMode = 'fail-502';
    type(0, 'Hà Nội');
    await tick(500);
    await advance(1_000 + 2_000);
    const before = saves().length;

    saveMode = 'ok';
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await tick(0);

    expect(saves()).toHaveLength(before + 1);
    expect(screen.getByText('Đã tự động lưu')).toBeInTheDocument();
  });

  it('pushes a stuck answer 10 seconds before the deadline, ahead of the next (longer) backoff timer', async () => {
    // 25 s of exam. Failures at t=0.5, 1.5, 3.5, 7.5 s leave the next backoff retry at t=15.5 s.
    await startAttempt('2026-01-01T10:00:25.000Z');
    saveMode = 'fail-502';
    type(0, 'Hà Nội');
    await tick(500);
    await advance(7_000);
    expect(screen.getAllByText(/Đang lưu lại…/).length).toBeGreaterThan(0);

    // the backend is back before T-10 s (= t 15 s) but before the backoff timer (t 15.5 s)
    await advance(7_400); // t = 14.9 s
    saveMode = 'ok';
    const before = saves().length;
    await advance(200); // t = 15.1 s: the T-10 s flush has fired, the backoff timer (15.5 s) has not
    expect(saves()).toHaveLength(before + 1);
    expect(screen.getByText('Đã tự động lưu')).toBeInTheDocument();
  });

  it('the deadline submit carries every current answer even if the backend never accepted an autosave', async () => {
    await startAttempt('2026-01-01T10:00:20.000Z');
    saveMode = 'network';
    type(0, 'Hà Nội');
    await tick(500);
    type(1, 'Tokyo');
    await tick(500);

    await advance(19_500); // the deadline
    await advance(2_000);

    const submit = calls.find((c) => c.url.includes(`/attempts/${ATTEMPT_ID}/submit`));
    expect(submit).toBeDefined();
    expect(submit!.body.answers).toEqual({ 'q-1': 'Hà Nội', 'q-2': 'Tokyo' });
  });
});
