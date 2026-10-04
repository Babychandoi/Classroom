import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ExamResultPage } from '../pages/classroom/ExamResultPage';

/**
 * The published-result review follows the exam's question order: a skipped question is listed as "(Chưa trả lời)" and
 * still counts in "đúng x/y"; correctness comes from pointsAwarded vs. points; TRUE/FALSE keys are not shown as prefixes.
 */
describe('ExamResultPage — per-question review', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('lists skipped questions and counts every question', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/my-attempts')) {
        return new Response(JSON.stringify({ success: true, data: [{
          id: 'att-1', examId: 'exam-1', examTitle: 'Giữa kỳ', status: 'PUBLISHED', score: 50, totalPoints: 20,
          answers: [
            { questionId: 'q1', studentAnswer: 'B', pointsAwarded: 10 },
            { questionId: 'q3', studentAnswer: 'FALSE', pointsAwarded: 0 },
          ],
        }] }), { status: 200 });
      }
      if (url.includes('/exams/exam-1')) {
        return new Response(JSON.stringify({ success: true, data: { id: 'exam-1', questions: [
          { id: 'q1', questionText: 'Câu một', type: 'MULTIPLE_CHOICE', points: 10, options: [{ optionKey: 'A', optionText: 'x = 1' }, { optionKey: 'B', optionText: 'x = 3' }] },
          { id: 'q2', questionText: 'Câu hai', type: 'ESSAY', points: 10, options: [] },
          { id: 'q3', questionText: 'Câu ba', type: 'TRUE_FALSE', points: 10, options: [{ optionKey: 'TRUE', optionText: 'Đúng' }, { optionKey: 'FALSE', optionText: 'Sai' }] },
        ] } }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(
      <MemoryRouter initialEntries={['/classes/lop-1/exams/exam-1/result']}>
        <Routes><Route path="/classes/:slug/exams/:examId/result" element={<ExamResultPage />} /></Routes>
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText('Câu hai')).toBeInTheDocument());
    expect(screen.getByText('(Chưa trả lời)')).toBeInTheDocument();
    expect(screen.getByText('B. x = 3')).toBeInTheDocument();
    expect(screen.getByText('Sai')).toBeInTheDocument();
    expect(screen.getByText('Đúng', { selector: 'span' })).toBeInTheDocument();
    expect(screen.getByText('Chưa đúng')).toBeInTheDocument();
    expect(screen.getByText('1/3')).toBeInTheDocument();
  });
});
