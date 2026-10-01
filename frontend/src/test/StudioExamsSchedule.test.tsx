import React from 'react';
import { describe, it, expect, beforeEach, afterAll, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { StudioExams } from '../pages/studio/StudioExams';
import { fromDatetimeLocalValue, toDatetimeLocalValue } from '../api/datetime';
import type { Classroom, Exam } from '../types';

/**
 * R16-04: the exam edit form pre-filled its datetime-local inputs with the UTC wall clock
 * (`toISOString().slice(0, 16)`) but saved by parsing them as LOCAL time, so every save of an untouched
 * form moved the schedule by the browser's UTC offset. These tests run under non-UTC zones (a
 * positive offset without DST, and a negative offset with DST) so a UTC-only run cannot hide it.
 */

// tsconfig has no Node typings; the test runner is Node, so reach process.env through globalThis.
const env = (globalThis as unknown as { process: { env: Record<string, string | undefined> } }).process.env;
const ORIGINAL_TZ = env.TZ;

function useTimeZone(tz: string, expectedOffsetMinutes: number) {
  env.TZ = tz;
  // getTimezoneOffset() is minutes *behind* UTC: -420 for UTC+7, 420 for UTC-7 (PDT).
  expect(new Date('2026-10-01T08:00:00Z').getTimezoneOffset()).toBe(expectedOffsetMinutes);
}

afterAll(() => {
  if (ORIGINAL_TZ === undefined) delete env.TZ;
  else env.TZ = ORIGINAL_TZ;
});

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

const scheduledExam = (over: Partial<Exam> = {}): Exam =>
  ({
    id: 'exam-1',
    classId: 'class-1',
    title: 'Kỳ thi có lịch',
    durationMinutes: 45,
    attemptLimit: 1,
    audienceScope: 'ALL',
    status: 'DRAFT',
    passScore: 50,
    canEnter: false,
    userAttemptsCount: 0,
    questionCount: 1,
    scheduleStart: '2026-10-01T08:00:00Z',
    scheduleEnd: '2026-10-01T10:30:00Z',
    createdAt: new Date().toISOString(),
    ...over,
  }) as Exam;

interface PutCall { url: string; body: any }

function mockBackend(exam: Exam) {
  const puts: PutCall[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = input.toString();
    const method = init?.method ?? 'GET';
    if (method === 'PUT' && url.endsWith('/exams/exam-1')) {
      puts.push({ url, body: JSON.parse(String(init?.body)) });
      return new Response(JSON.stringify({ success: true, data: exam }), { status: 200 });
    }
    if (url.endsWith('/exams/exam-1')) {
      return new Response(JSON.stringify({ success: true, data: { ...exam, questions: [] } }), { status: 200 });
    }
    if (url.includes('/classes/class-1/exams')) {
      return new Response(JSON.stringify({ success: true, data: [exam] }), { status: 200 });
    }
    if (url.includes('/courses') || url.includes('/segments')) {
      return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
    }
    return new Response('{}', { status: 404 });
  });
  return puts;
}

async function openEditForm() {
  render(<StudioExams />);
  await waitFor(() => expect(screen.getByText('Kỳ thi có lịch')).toBeInTheDocument());
  fireEvent.click(screen.getByText('Sửa cấu hình'));
  await waitFor(() => expect(screen.getByText('Sửa cấu hình kỳ thi')).toBeInTheDocument());
}

describe('datetime-local helpers are exact inverses (R16-04)', () => {
  it.each([
    ['Asia/Ho_Chi_Minh', -420, '2026-10-01T15:00'],
    ['America/Los_Angeles', 420, '2026-10-01T01:00'],
  ])('%s: pre-fills the LOCAL wall clock and round-trips to the same instant', (tz, offset, wallClock) => {
    useTimeZone(tz, offset);

    expect(toDatetimeLocalValue('2026-10-01T08:00:00Z')).toBe(wallClock);
    expect(fromDatetimeLocalValue(toDatetimeLocalValue('2026-10-01T08:00:00Z'))).toBe('2026-10-01T08:00:00.000Z');
  });

  it('handles empty / invalid values without throwing', () => {
    expect(toDatetimeLocalValue(null)).toBe('');
    expect(toDatetimeLocalValue(undefined)).toBe('');
    expect(toDatetimeLocalValue('not-a-date')).toBe('');
    expect(fromDatetimeLocalValue('')).toBeNull();
    expect(fromDatetimeLocalValue(null)).toBeNull();
    expect(fromDatetimeLocalValue('garbage')).toBeNull();
  });
});

describe('StudioExams edit form keeps the schedule stable (R16-04)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('UTC+7: shows the schedule in local time and saves an untouched form without shifting it', async () => {
    useTimeZone('Asia/Ho_Chi_Minh', -420);
    const puts = mockBackend(scheduledExam());
    await openEditForm();

    // 08:00Z is 15:00 in Vietnam - not the UTC wall clock 08:00 the old pre-fill showed.
    expect((screen.getByLabelText(/Bắt đầu mở đề/) as HTMLInputElement).value).toBe('2026-10-01T15:00');
    expect((screen.getByLabelText(/Đóng đề thi/) as HTMLInputElement).value).toBe('2026-10-01T17:30');

    fireEvent.click(screen.getByText('Lưu thay đổi'));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].body.scheduleStart).toBe('2026-10-01T08:00:00.000Z');
    expect(puts[0].body.scheduleEnd).toBe('2026-10-01T10:30:00.000Z');
  });

  it('UTC-7 (DST): a save/reopen/save cycle is a fixed point - the schedule does not drift', async () => {
    useTimeZone('America/Los_Angeles', 420);
    const puts = mockBackend(scheduledExam());
    await openEditForm();

    expect((screen.getByLabelText(/Bắt đầu mở đề/) as HTMLInputElement).value).toBe('2026-10-01T01:00');
    fireEvent.click(screen.getByText('Lưu thay đổi'));
    await waitFor(() => expect(puts).toHaveLength(1));

    expect(puts[0].body.scheduleStart).toBe('2026-10-01T08:00:00.000Z');
    expect(puts[0].body.scheduleEnd).toBe('2026-10-01T10:30:00.000Z');
  });

  it('a schedule the author clears is sent as null so the server clears it too (R16-06)', async () => {
    useTimeZone('Asia/Ho_Chi_Minh', -420);
    const puts = mockBackend(scheduledExam());
    await openEditForm();

    fireEvent.change(screen.getByLabelText(/Bắt đầu mở đề/), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText(/Đóng đề thi/), { target: { value: '' } });
    fireEvent.click(screen.getByText('Lưu thay đổi'));

    await waitFor(() => expect(puts).toHaveLength(1));
    // Both fields are always present in the body (null = "unscheduled").
    expect(puts[0].body).toHaveProperty('scheduleStart', null);
    expect(puts[0].body).toHaveProperty('scheduleEnd', null);
  });

  it('an exam without a schedule opens with empty inputs and stays unscheduled', async () => {
    useTimeZone('Asia/Ho_Chi_Minh', -420);
    const puts = mockBackend(scheduledExam({ scheduleStart: undefined, scheduleEnd: undefined }));
    await openEditForm();

    expect((screen.getByLabelText(/Bắt đầu mở đề/) as HTMLInputElement).value).toBe('');
    fireEvent.click(screen.getByText('Lưu thay đổi'));
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].body.scheduleStart).toBeNull();
    expect(puts[0].body.scheduleEnd).toBeNull();
  });
});
