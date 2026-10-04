import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor, fireEvent, within } from '@testing-library/react';
import { BlogTab } from '../pages/classroom/BlogTab';
import { baseClassroom, makePost, mockApi, ok, renderInClass } from './blogEventsHelpers';
import type { Classroom } from '../types';

let mockUser: unknown = { id: 'u1', fullName: 'Học Viên', email: 'hv@test.local', role: 'STUDENT', status: 'ACTIVE' };
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ user: mockUser }) }));

const renderTab = (classroom: Classroom = baseClassroom) =>
  renderInClass(<BlogTab />, { path: '/classes/demo-class/blog', pattern: '/classes/:slug/blog', classroom });

describe('BlogTab', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    mockUser = { id: 'u1', fullName: 'Học Viên', email: 'hv@test.local', role: 'STUDENT', status: 'ACTIVE' };
  });

  it('shows the newest post as the featured card, the rest as cards, categories as chips and a lock chip on locked posts', async () => {
    const calls = mockApi({
      'GET /classes/class-1/blog-categories': () => ok(['Bắt đầu', 'Mẹo học']),
      'GET /classes/class-1/blog-posts': () => ok({
        items: [
          makePost({ id: 'p1', title: 'Bài mới nhất nè' }),
          makePost({ id: 'p2', title: 'Bài dành riêng', audience: 'MEMBERS', locked: true, category: 'Mẹo học', readingMinutes: 7 }),
        ],
        nextCursor: null,
      }),
    });

    renderTab();

    expect(await screen.findByRole('link', { name: 'Bài mới nhất nè' })).toHaveAttribute('href', '/classes/demo-class/blog/p1');
    expect(screen.getByRole('link', { name: 'Đọc bài' })).toHaveAttribute('href', '/classes/demo-class/blog/p1');
    const locked = screen.getByText('Bài dành riêng').closest('a')!;
    expect(locked).toHaveAttribute('href', '/classes/demo-class/blog/p2');
    expect(within(locked).getByText('Thành viên')).toBeInTheDocument();
    expect(within(locked).getByText(/7 phút đọc/)).toBeInTheDocument();
    expect(within(locked).getByText(/08\/07\/2026/)).toBeInTheDocument();
    // Category chips come from the categories endpoint; "Tất cả" is selected.
    expect(screen.getByRole('button', { name: 'Tất cả' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Mẹo học' })).toHaveAttribute('aria-pressed', 'false');
    expect(calls.find((c) => c.path.startsWith('/classes/class-1/blog-posts'))!.path).toBe('/classes/class-1/blog-posts?size=12');
    // A plain member has no BLOG grant: no "write" entry.
    expect(screen.queryByRole('link', { name: /Viết bài/ })).not.toBeInTheDocument();
  });

  it('filters by category and pages with the cursor ("Xem thêm bài viết")', async () => {
    const calls = mockApi({
      'GET /classes/class-1/blog-categories': () => ok(['Mẹo học']),
      'GET /classes/class-1/blog-posts?category=': () => ok({ items: [makePost({ id: 'c1', title: 'Bài trong mục', category: 'Mẹo học' })], nextCursor: null }),
      'GET /classes/class-1/blog-posts?cursor=': () => ok({ items: [makePost({ id: 'p3', title: 'Bài cũ hơn' })], nextCursor: null }),
      'GET /classes/class-1/blog-posts?size=': () => ok({ items: [makePost({ id: 'p1', title: 'Bài một' }), makePost({ id: 'p2', title: 'Bài hai' })], nextCursor: 'CUR2' }),
    });

    renderTab();
    await screen.findByText('Bài hai');

    fireEvent.click(screen.getByRole('button', { name: 'Xem thêm bài viết' }));
    expect(await screen.findByText('Bài cũ hơn')).toBeInTheDocument();
    expect(screen.getByText('Bài hai')).toBeInTheDocument();
    expect(calls.some((c) => c.path === '/classes/class-1/blog-posts?cursor=CUR2&size=12')).toBe(true);
    expect(screen.queryByRole('button', { name: 'Xem thêm bài viết' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Mẹo học' }));
    expect(await screen.findByText('Bài trong mục')).toBeInTheDocument();
    expect(calls.some((c) => c.path === '/classes/class-1/blog-posts?category=M%E1%BA%B9o+h%E1%BB%8Dc&size=12')).toBe(true);
    expect(screen.getByRole('button', { name: 'Mẹo học' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByText('Bài một')).not.toBeInTheDocument();
  });

  it('empty blog: a manager is sent to the Studio to write the first post, a member to Thảo luận', async () => {
    mockApi({
      'GET /classes/class-1/blog-categories': () => ok([]),
      'GET /classes/class-1/blog-posts': () => ok({ items: [], nextCursor: null }),
    });
    const { unmount } = renderTab({ ...baseClassroom, userRole: 'OWNER', isOwner: true });
    expect(await screen.findByRole('link', { name: 'Viết bài đầu tiên' })).toHaveAttribute('href', '/studio/classes/class-1/blog');
    unmount();

    renderTab();
    expect(await screen.findByText('Lớp học chưa có bài viết nào')).toBeInTheDocument();
    const feedLinks = screen.getAllByRole('link', { name: 'Vào Thảo luận' });
    expect(feedLinks.length).toBeGreaterThan(0);
    feedLinks.forEach((link) => expect(link).toHaveAttribute('href', '/classes/demo-class/feed'));
    expect(screen.queryByRole('link', { name: 'Viết bài đầu tiên' })).not.toBeInTheDocument();
  });

  it('shows the server error with a retry', async () => {
    let attempts = 0;
    mockApi({
      'GET /classes/class-1/blog-categories': () => ok([]),
      'GET /classes/class-1/blog-posts': () => {
        attempts += 1;
        return attempts === 1
          ? new Response(JSON.stringify({ success: false, error: { code: 'INTERNAL', message: 'Máy chủ lỗi' } }), { status: 500 })
          : ok({ items: [makePost({ title: 'Đã tải lại' })], nextCursor: null });
      },
    });
    renderTab();
    expect(await screen.findByText('Máy chủ lỗi')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    await waitFor(() => expect(screen.getByRole('link', { name: 'Đã tải lại' })).toBeInTheDocument());
  });
});
