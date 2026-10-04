import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import { BlogPostPage } from '../pages/classroom/BlogPostPage';
import { baseClassroom, fail, makePost, mockApi, ok, renderInClass } from './blogEventsHelpers';
import type { Classroom } from '../types';

let mockUser: unknown = null;
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ user: mockUser }) }));

const member = { id: 'u1', fullName: 'Học Viên', email: 'hv@test.local', role: 'STUDENT', status: 'ACTIVE' };
const outsider: Classroom = { ...baseClassroom, isMember: false, userRole: 'GUEST', memberState: 'NONE' };

const renderPost = (classroom: Classroom = baseClassroom, refreshClassroom = vi.fn().mockResolvedValue(undefined)) =>
  renderInClass(<BlogPostPage />, { path: '/classes/demo-class/blog/p2', pattern: '/classes/:slug/blog/:postId', classroom, refreshClassroom });

describe('BlogPostPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    mockUser = member;
    window.scrollTo = vi.fn() as any;
  });

  it('renders the markdown body as safe text (headings, lists, bold, http(s) links only) with a reading progress bar and older/newer links', async () => {
    const content = [
      '# Phần một',
      'Đoạn có **chữ đậm** và [liên kết](https://example.com) và [xấu](javascript:alert(1)).',
      '',
      '- Ý thứ nhất',
      '- Ý thứ hai',
      '',
      '<script>alert("x")</script>',
    ].join('\n');
    mockApi({
      'GET /blog-posts/p2': () => ok(makePost({ id: 'p2', title: 'Bài giữa', contentMarkdown: content })),
      'GET /classes/class-1/blog-posts': () => ok({
        items: [makePost({ id: 'p1', title: 'Bài mới' }), makePost({ id: 'p2', title: 'Bài giữa' }), makePost({ id: 'p3', title: 'Bài cũ' })],
        nextCursor: null,
      }),
    });

    const { container } = renderPost();

    expect(await screen.findByRole('heading', { level: 1, name: 'Bài giữa' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Phần một' })).toBeInTheDocument();
    expect(screen.getByText('chữ đậm').tagName).toBe('STRONG');
    expect(screen.getByRole('link', { name: 'liên kết' })).toHaveAttribute('href', 'https://example.com');
    expect(screen.queryByRole('link', { name: 'xấu' })).not.toBeInTheDocument();
    expect(screen.getByText(/\[xấu\]\(javascript:alert\(1\)\)/)).toBeInTheDocument();
    expect(screen.getAllByRole('listitem').map((li) => li.textContent)).toEqual(['Ý thứ nhất', 'Ý thứ hai']);
    // Raw HTML stays text: nothing is injected.
    expect(screen.getByText('<script>alert("x")</script>')).toBeInTheDocument();
    expect(container.querySelector('script')).toBeNull();
    expect(screen.getByRole('progressbar', { name: 'Tiến độ đọc' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /^Blog$/ })).toHaveAttribute('href', '/classes/demo-class/blog');

    await waitFor(() => expect(screen.getByRole('link', { name: /Bài mới hơn/ })).toHaveAttribute('href', '/classes/demo-class/blog/p1'));
    expect(screen.getByRole('link', { name: /Bài cũ hơn/ })).toHaveAttribute('href', '/classes/demo-class/blog/p3');
  });

  it('locked post for a guest: teaser only, no body, and a sign-in link back to this page', async () => {
    mockUser = null;
    mockApi({
      'GET /blog-posts/p2': () => ok(makePost({ id: 'p2', title: 'Bài thành viên', audience: 'MEMBERS', locked: true, excerpt: 'Phần mở đầu' })),
      'GET /classes/class-1/blog-posts': () => ok({ items: [], nextCursor: null }),
    });
    renderPost(outsider);

    expect(await screen.findByRole('heading', { name: 'Bài viết dành cho thành viên lớp' })).toBeInTheDocument();
    expect(screen.getByText('Phần mở đầu')).toBeInTheDocument();
    expect(screen.queryByRole('progressbar', { name: 'Tiến độ đọc' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('link', { name: 'Đăng nhập để đọc tiếp' }));
    expect(await screen.findByText('Trang đăng nhập')).toBeInTheDocument();
  });

  it('locked post for a signed-in outsider of a free class: joining posts /join, refreshes the class', async () => {
    const refresh = vi.fn().mockResolvedValue(undefined);
    const calls = mockApi({
      'GET /blog-posts/p2': () => ok(makePost({ id: 'p2', audience: 'MEMBERS', locked: true })),
      'GET /classes/class-1/blog-posts': () => ok({ items: [], nextCursor: null }),
      'POST /classes/class-1/join': () => ok({ ...outsider, isMember: true }),
    });
    renderPost(outsider, refresh);

    fireEvent.click(await screen.findByRole('button', { name: 'Tham gia lớp để đọc tiếp' }));
    await waitFor(() => expect(refresh).toHaveBeenCalled());
    expect(calls.some((c) => c.method === 'POST' && c.path === '/classes/class-1/join')).toBe(true);
  });

  it('locked post of a paid class sends the outsider to the Shop tab', async () => {
    mockApi({
      'GET /blog-posts/p2': () => ok(makePost({ id: 'p2', audience: 'MEMBERS', locked: true })),
      'GET /classes/class-1/blog-posts': () => ok({ items: [], nextCursor: null }),
    });
    renderPost({ ...outsider, accessType: 'PAID' });
    expect(await screen.findByRole('link', { name: 'Tham gia lớp để đọc tiếp' })).toHaveAttribute('href', '/classes/demo-class/store');
  });

  it('a missing post shows a friendly not-found state with the way back', async () => {
    mockApi({ 'GET /blog-posts/p2': () => fail(404, 'NOT_FOUND', 'Không tìm thấy') });
    renderPost();
    expect(await screen.findByRole('heading', { name: 'Không tìm thấy bài viết' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Về Blog' })).toHaveAttribute('href', '/classes/demo-class/blog');
  });
});
