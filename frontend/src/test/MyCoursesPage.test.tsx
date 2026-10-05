import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MyCoursesPage } from '../pages/MyCoursesPage';
import { fail, mockApi, ok } from './blogEventsHelpers';
import type { MyCourse } from '../types';

const course = (id: string, over: Partial<MyCourse> = {}): MyCourse => ({
  id, classId: 'cl', classTitle: 'Lớp Demo', classSlug: 'demo', title: `Khóa ${id}`, accessMode: 'FREE',
  totalLessons: 12, completedLessons: 0, progressPercent: 0, nextLessonId: `l-${id}`, started: false, ...over,
});
const renderPage = () => render(<MemoryRouter><MyCoursesPage /></MemoryRouter>);

describe('MyCoursesPage', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('splits started and not-started courses, with progress and a single primary "Học tiếp"', async () => {
    mockApi({
      'GET /me/courses': () => ok([
        course('1', { started: true, completedLessons: 3, progressPercent: 25, lastActivityAt: '2026-10-01T03:00:00Z' }),
        course('2', { started: true, completedLessons: 12, progressPercent: 100, nextLessonId: null }),
        course('3'),
      ]),
    });
    renderPage();
    expect(screen.getByRole('status')).toBeInTheDocument();

    const started = (await screen.findByRole('heading', { name: 'Đang học' })).closest('section')!;
    const first = within(started).getByRole('heading', { name: 'Khóa 1' }).closest('article')!;
    expect(within(first).getByText('3/12 bài')).toBeInTheDocument();
    expect(within(first).getByText('25%')).toBeInTheDocument();
    expect(within(first).getByRole('progressbar')).toHaveAttribute('aria-valuenow', '25');
    const resume = within(first).getByRole('link', { name: /Học tiếp/ });
    expect(resume).toHaveAttribute('href', '/classes/demo/learn/lessons/l-1');
    expect(resume.className).toContain('bg-blue-600');

    const second = within(started).getByRole('heading', { name: 'Khóa 2' }).closest('article')!;
    const review = within(second).getByRole('link', { name: /Xem lại/ });
    expect(review).toHaveAttribute('href', '/classes/demo/learn');
    expect(review.className).not.toContain('bg-blue-600');

    const fresh = screen.getByRole('heading', { name: 'Chưa bắt đầu' }).closest('section')!;
    const third = within(fresh).getByRole('link', { name: /Bắt đầu học/ });
    expect(third).toHaveAttribute('href', '/classes/demo/learn/lessons/l-3');
    expect(third.className).not.toContain('bg-blue-600');
    expect(screen.getAllByRole('link').filter((l) => l.className.includes('bg-blue-600'))).toHaveLength(1);
  });

  it('omits the "Đang học" section when nothing is started', async () => {
    mockApi({ 'GET /me/courses': () => ok([course('3')]) });
    renderPage();
    await screen.findByRole('heading', { name: 'Chưa bắt đầu' });
    expect(screen.queryByRole('heading', { name: 'Đang học' })).not.toBeInTheDocument();
  });

  it('pages with "Xem thêm khóa học"', async () => {
    const calls = mockApi({
      'GET /me/courses?page=0': () => ok(Array.from({ length: 20 }, (_, i) => course(`p${i}`))),
      'GET /me/courses?page=1': () => ok([course('z')]),
    });
    renderPage();
    await screen.findByRole('heading', { name: 'Khóa p0' });
    expect(calls[0].path).toBe('/me/courses?page=0&size=20');
    fireEvent.click(screen.getByRole('button', { name: 'Xem thêm khóa học' }));
    expect(await screen.findByRole('heading', { name: 'Khóa z' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Xem thêm khóa học' })).not.toBeInTheDocument();
  });

  it('shows the empty state with next steps', async () => {
    mockApi({ 'GET /me/courses': () => ok([]) });
    renderPage();
    expect(await screen.findByText('Bạn chưa có khóa học nào để học')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Khám phá lớp học' })).toHaveAttribute('href', '/classes');
    expect(screen.getByRole('link', { name: 'Lớp học của tôi' })).toHaveAttribute('href', '/me/classes');
  });

  it('shows an error that can be retried', async () => {
    let fails = true;
    mockApi({ 'GET /me/courses': () => (fails ? fail(500, 'INTERNAL', 'Lỗi tải') : ok([course('3')])) });
    renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('Lỗi tải');
    fails = false;
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByRole('heading', { name: 'Khóa 3' })).toBeInTheDocument();
  });
});
