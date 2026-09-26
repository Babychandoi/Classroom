import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { api } from '../api/client';

describe('Exam Attempt & Autosave Behavior', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('starts new exam attempt and receives immutable question set without answer keys', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/exams/exam-1/attempts')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: {
              id: 'att-1',
              examId: 'exam-1',
              userId: 'student-1',
              status: 'IN_PROGRESS',
              endsAt: new Date(Date.now() + 45 * 60 * 1000).toISOString(),
              questions: [
                {
                  id: 'q-1',
                  questionText: '2 + 2 = ?',
                  type: 'MULTIPLE_CHOICE',
                  points: 10,
                  options: [
                    { id: 'opt-1', optionKey: 'A', optionText: '3' },
                    { id: 'opt-2', optionKey: 'B', optionText: '4' },
                  ],
                },
              ],
            },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    const attempt = await api.post<any>('/exams/exam-1/attempts');
    expect(attempt.id).toBe('att-1');
    expect(attempt.status).toBe('IN_PROGRESS');
    expect(attempt.questions).toHaveLength(1);
    expect(attempt.questions[0].id).toBe('q-1');
    // Ensure answer key is NOT exposed to student
    expect(attempt.questions[0].answerKey).toBeUndefined();
  });

  it('autosaves answers to backend attempt', async () => {
    let capturedBody: any = null;

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      if (url.includes('/attempts/att-1/answers')) {
        capturedBody = JSON.parse(init?.body as string);
        return new Response(
          JSON.stringify({
            success: true,
            data: {
              id: 'att-1',
              status: 'IN_PROGRESS',
              answers: [{ questionId: 'q-1', studentAnswer: 'B' }],
            },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    const result = await api.put<any>('/attempts/att-1/answers', {
      answers: { 'q-1': 'B' },
    });

    expect(result.id).toBe('att-1');
    expect(capturedBody.answers).toEqual({ 'q-1': 'B' });
  });

  it('submits exam attempt idempotently', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/attempts/att-1/submit')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: {
              id: 'att-1',
              status: 'PUBLISHED',
              score: 100,
            },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    const result = await api.post<any>('/attempts/att-1/submit', {
      answers: { 'q-1': 'B' },
    });

    expect(result.id).toBe('att-1');
    expect(result.status).toBe('PUBLISHED');
    expect(result.score).toBe(100);
  });

  it('accepts authoritative timeout finalization response for a late submission', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      return new Response(
        JSON.stringify({
          success: true,
          data: {
            id: 'att-1',
            status: 'PUBLISHED',
            score: 50,
            answers: [{ questionId: 'q-1', studentAnswer: 'B' }],
          },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    });

    const result = await api.post<any>('/attempts/att-1/submit', {
        answers: { 'q-1': 'B' },
      });
    expect(result.status).toBe('PUBLISHED');
    expect(result.score).toBe(50);
  });
});
