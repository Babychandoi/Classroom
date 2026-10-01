import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { StudioExams } from '../pages/studio/StudioExams';
import type { Classroom, Exam, Question } from '../types';

/**
 * R15-02: openEditQuestion used to reset the edit form's answer key to the first option ('A'), so
 * pressing "Lưu câu hỏi" without touching the key silently changed a stored 'C' answer to 'A'.
 * The edit form must start from the stored key and send it back unchanged.
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
  questionCount: 3,
  createdAt: new Date().toISOString(),
} as Exam;

const options = (questionId: string) =>
  ['A', 'B', 'C', 'D'].map((key, position) => ({
    id: `${questionId}-${key}`,
    questionId,
    optionKey: key,
    optionText: `Lựa chọn số ${key}`,
    position,
  }));

const mcqWithKeyC: Question = {
  id: 'q-mcq',
  examId: 'exam-1',
  questionText: 'Thủ đô của Việt Nam là gì?',
  type: 'MULTIPLE_CHOICE',
  points: 10,
  position: 1,
  answerKey: 'C',
  options: options('q-mcq'),
};

const essay: Question = {
  id: 'q-essay',
  examId: 'exam-1',
  questionText: 'Trình bày quan điểm của bạn',
  type: 'ESSAY',
  points: 10,
  position: 2,
  answerKey: null,
  options: [],
};

// An editor allowed to edit questions but not to read answer keys (server omits answerKey).
const mcqKeyHidden: Question = {
  id: 'q-hidden',
  examId: 'exam-1',
  questionText: 'Đáp án bị ẩn',
  type: 'MULTIPLE_CHOICE',
  points: 10,
  position: 1,
  options: options('q-hidden'),
};

interface PutCall { url: string; body: any }

function mockBackend(questions: Question[]) {
  const puts: PutCall[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = input.toString();
    const method = init?.method ?? 'GET';
    if (method === 'PUT' && url.includes('/questions/')) {
      puts.push({ url, body: JSON.parse(String(init?.body)) });
      return new Response(JSON.stringify({ success: true, data: {} }), { status: 200 });
    }
    if (url.endsWith('/exams/exam-1')) {
      return new Response(JSON.stringify({ success: true, data: { ...draftExam, questions } }), { status: 200 });
    }
    if (url.includes('/classes/class-1/exams')) {
      return new Response(JSON.stringify({ success: true, data: [draftExam] }), { status: 200 });
    }
    if (url.includes('/courses') || url.includes('/segments')) {
      return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
    }
    return new Response('{}', { status: 404 });
  });
  return puts;
}

async function openAuthoring() {
  render(<StudioExams />);
  await waitFor(() => expect(screen.getByText('Kỳ thi giữa kỳ')).toBeInTheDocument());
  fireEvent.click(screen.getByText('Soạn câu hỏi và công bố'));
}

describe('StudioExams — editing a question keeps its stored answer key (R15-02)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('opens an MCQ whose key is C with C selected and saves it back as C when untouched', async () => {
    const puts = mockBackend([mcqWithKeyC]);
    await openAuthoring();
    await waitFor(() => expect(screen.getByText(/Thủ đô của Việt Nam/)).toBeInTheDocument());

    fireEvent.click(screen.getByText('Sửa'));

    const keySelect = screen.getByLabelText('Đáp án đúng (sửa)') as HTMLSelectElement;
    expect(keySelect.value).toBe('C');

    fireEvent.click(screen.getByText('Lưu câu hỏi'));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].url).toContain('/questions/q-mcq');
    expect(puts[0].body.question.answerKey).toBe('C');
    expect(puts[0].body.question.type).toBe('MULTIPLE_CHOICE');
    expect(puts[0].body.options.map((o: { optionKey: string }) => o.optionKey)).toEqual(['A', 'B', 'C', 'D']);
  });

  it('still lets the author change the key explicitly', async () => {
    const puts = mockBackend([mcqWithKeyC]);
    await openAuthoring();
    await waitFor(() => expect(screen.getByText(/Thủ đô của Việt Nam/)).toBeInTheDocument());

    fireEvent.click(screen.getByText('Sửa'));
    fireEvent.change(screen.getByLabelText('Đáp án đúng (sửa)'), { target: { value: 'D' } });
    fireEvent.click(screen.getByText('Lưu câu hỏi'));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].body.question.answerKey).toBe('D');
  });

  it('leaves essay questions unaffected: no key selector and a null answerKey in the payload', async () => {
    const puts = mockBackend([essay]);
    await openAuthoring();
    await waitFor(() => expect(screen.getByText(/Trình bày quan điểm của bạn/)).toBeInTheDocument());

    fireEvent.click(screen.getByText('Sửa'));

    expect(screen.queryByLabelText('Đáp án đúng (sửa)')).not.toBeInTheDocument();
    fireEvent.click(screen.getByText('Lưu câu hỏi'));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].body.question.type).toBe('ESSAY');
    expect(puts[0].body.question.answerKey).toBeNull();
    expect(puts[0].body.options).toEqual([]);
  });

  it('never silently rewrites a withheld key to "A": the author must pick one before saving', async () => {
    const puts = mockBackend([mcqKeyHidden]);
    await openAuthoring();
    await waitFor(() => expect(screen.getByText(/Đáp án bị ẩn/)).toBeInTheDocument());

    fireEvent.click(screen.getByText('Sửa'));

    const keySelect = screen.getByLabelText('Đáp án đúng (sửa)') as HTMLSelectElement;
    expect(keySelect.value).toBe('');

    fireEvent.click(screen.getByText('Lưu câu hỏi'));
    await waitFor(() => expect(screen.getByText(/đáp án đúng phải nằm trong các lựa chọn/)).toBeInTheDocument());
    expect(puts).toHaveLength(0);

    fireEvent.change(keySelect, { target: { value: 'B' } });
    fireEvent.click(screen.getByText('Lưu câu hỏi'));
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].body.question.answerKey).toBe('B');
  });
});
