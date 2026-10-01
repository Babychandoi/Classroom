import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, act, waitFor } from '@testing-library/react';
import { FeedTab } from '../pages/classroom/FeedTab';
import type { Classroom, Post } from '../types';

const mockClassroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 2,
  userRole: 'MEMBER',
  isMember: true,
  createdAt: new Date().toISOString(),
};

let outletClassroom: Classroom = mockClassroom;

const mockUser = { id: 'u1', fullName: 'Learner', email: 'learner@test.local', role: 'STUDENT', status: 'ACTIVE' };

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: outletClassroom }),
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: mockUser }),
}));

const ownPost: Post = {
  id: 'post-1',
  classId: 'class-1',
  authorId: 'u1',
  authorName: 'Learner',
  title: 'Bai viet',
  contentMarkdown: 'Noi dung',
  visibility: 'FREE',
  pinned: false,
  status: 'PUBLISHED',
  commentCount: 1,
  createdAt: new Date().toISOString(),
  comments: [
    {
      id: 'comment-1',
      postId: 'post-1',
      authorId: 'u1',
      authorName: 'Learner',
      content: 'Binh luan cua toi',
      createdAt: new Date().toISOString(),
    },
  ],
};

describe('FeedTab', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    window.alert = vi.fn();
    outletClassroom = mockClassroom;
  });

  it('defaults the composer visibility select to FREE, not PUBLIC', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/posts')) {
        return new Response(JSON.stringify({ success: true, data: { posts: [], hasNext: false } }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<FeedTab />);

    await waitFor(() => expect(screen.getByPlaceholderText('Tiêu đề bài viết...')).toBeInTheDocument());

    const select = screen.getByDisplayValue('Thành viên lớp (Free)') as HTMLSelectElement;
    expect(select.value).toBe('FREE');
    // R18-11: the audience select has an accessible name (a real <label>, not just a neighbouring span).
    expect(screen.getByLabelText('Đối tượng xem:')).toBe(select);
    // A plain member (no FEED:CREATE grant) must not be offered PUBLIC or PRO options.
    expect(screen.queryByText('Mọi người (Public)')).not.toBeInTheDocument();
    expect(screen.queryByText('Chỉ hội viên PRO ⭐')).not.toBeInTheDocument();
  });

  it('asks for confirmation before deleting a comment and removes it on confirm', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = init?.method || 'GET';
      if (url.includes('/comments/comment-1') && method === 'DELETE') {
        return new Response(JSON.stringify({ success: true, data: { message: 'ok' } }), { status: 200 });
      }
      if (url.includes('/posts')) {
        return new Response(JSON.stringify({ success: true, data: { posts: [ownPost], hasNext: false } }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);

    render(<FeedTab />);

    await waitFor(() => expect(screen.getByText('Binh luan cua toi')).toBeInTheDocument());

    await act(async () => {
      screen.getByLabelText('Xóa bình luận').click();
    });

    expect(confirmSpy).toHaveBeenCalled();
    await waitFor(() => expect(screen.queryByText('Binh luan cua toi')).not.toBeInTheDocument());
  });

  it('does not delete the comment when the confirmation is declined', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/posts')) {
        return new Response(JSON.stringify({ success: true, data: { posts: [ownPost], hasNext: false } }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    vi.spyOn(window, 'confirm').mockReturnValue(false);

    render(<FeedTab />);

    await waitFor(() => expect(screen.getByText('Binh luan cua toi')).toBeInTheDocument());

    await act(async () => {
      screen.getByLabelText('Xóa bình luận').click();
    });

    expect(screen.getByText('Binh luan cua toi')).toBeInTheDocument();
  });

  it('R4-09: hides the composer and comment box from a non-member viewer', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/posts')) {
        return new Response(JSON.stringify({ success: true, data: { posts: [ownPost], hasNext: false } }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    outletClassroom = { ...mockClassroom, isMember: false, userRole: undefined };

    render(<FeedTab />);

    await waitFor(() => expect(screen.getByText('Bai viet')).toBeInTheDocument());
    expect(screen.queryByPlaceholderText('Tiêu đề bài viết...')).not.toBeInTheDocument();
    expect(screen.queryByPlaceholderText('Viết bình luận của bạn...')).not.toBeInTheDocument();
  });

  it('R5-01: shows "Xem thêm" when hasNext is true and loads the next page via cursor on click', async () => {
    const secondPagePost: Post = { ...ownPost, id: 'post-2', title: 'Bai viet 2', comments: [] };
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('cursor=')) {
        return new Response(JSON.stringify({ success: true, data: { posts: [secondPagePost], nextCursor: null, hasNext: false } }), { status: 200 });
      }
      if (url.includes('/posts')) {
        return new Response(JSON.stringify({ success: true, data: { posts: [ownPost], nextCursor: 'cursor-abc', hasNext: true } }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<FeedTab />);

    await waitFor(() => expect(screen.getByText('Xem thêm')).toBeInTheDocument());

    await act(async () => {
      screen.getByText('Xem thêm').click();
    });

    await waitFor(() => expect(screen.getByText('Bai viet 2')).toBeInTheDocument());
    // Original page's post is still present — loading more appends rather than replaces.
    expect(screen.getByText('Bai viet')).toBeInTheDocument();
    expect(screen.queryByText('Xem thêm')).not.toBeInTheDocument();
  });

  it('R5-01/R5-03: de-dups by id when a "Xem thêm" batch overlaps a post already on screen', async () => {
    // The next batch re-returns ownPost (e.g. it shifted position because of a concurrent insert)
    // alongside a genuinely new post — the de-duped render must show ownPost only once.
    const newPost: Post = { ...ownPost, id: 'post-2', title: 'Bai viet moi', comments: [] };
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('cursor=')) {
        return new Response(JSON.stringify({ success: true, data: { posts: [ownPost, newPost], nextCursor: null, hasNext: false } }), { status: 200 });
      }
      if (url.includes('/posts')) {
        return new Response(JSON.stringify({ success: true, data: { posts: [ownPost], nextCursor: 'cursor-abc', hasNext: true } }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<FeedTab />);

    await waitFor(() => expect(screen.getByText('Xem thêm')).toBeInTheDocument());

    await act(async () => {
      screen.getByText('Xem thêm').click();
    });

    await waitFor(() => expect(screen.getByText('Bai viet moi')).toBeInTheDocument());
    // ownPost's title renders exactly once even though the server returned it twice.
    expect(screen.getAllByText('Bai viet')).toHaveLength(1);
  });

  it('R20-03: shows the total comment count and loads older comments with "Xem thêm bình luận"', async () => {
    const embedded = [17, 18, 19].map((n) => ({
      id: `comment-${n}`, postId: 'post-1', authorId: 'u2', authorName: 'Bạn học', content: `Binh luan ${n}`, createdAt: new Date().toISOString(),
    }));
    const busyPost: Post = { ...ownPost, commentCount: 20, comments: embedded };
    const requested: string[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/posts/post-1/comments')) {
        requested.push(url);
        const older = [14, 15, 16].map((n) => ({
          id: `comment-${n}`, postId: 'post-1', authorId: 'u2', authorName: 'Bạn học', content: `Binh luan ${n}`, createdAt: new Date().toISOString(),
        }));
        return new Response(JSON.stringify({ success: true, data: { comments: older, hasMore: true, nextBefore: 'comment-14' } }), { status: 200 });
      }
      if (url.includes('/classes/class-1/posts')) {
        return new Response(JSON.stringify({ success: true, data: { posts: [busyPost], hasNext: false } }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<FeedTab />);

    // the header shows the real total (20), not the 3 embedded comments
    await waitFor(() => expect(screen.getByText('20 bình luận')).toBeInTheDocument());
    expect(screen.getByText('Binh luan 19')).toBeInTheDocument();
    expect(screen.queryByText('Binh luan 16')).not.toBeInTheDocument();
    // 20 total, 3 on screen -> 17 more to load; distinct from the feed's own "Xem thêm"
    const more = screen.getByText('Xem thêm bình luận (17)');
    expect(screen.queryByText('Xem thêm')).not.toBeInTheDocument();

    await act(async () => {
      more.click();
    });

    await waitFor(() => expect(screen.getByText('Binh luan 16')).toBeInTheDocument());
    // paged backwards from the oldest comment on screen
    expect(requested[0]).toContain('before=comment-17');
    // older comments are placed BEFORE the ones already shown, in order
    const shown = screen.getAllByText(/^Binh luan [0-9]+$/).map((el) => el.textContent);
    expect(shown).toEqual(['Binh luan 14', 'Binh luan 15', 'Binh luan 16', 'Binh luan 17', 'Binh luan 18', 'Binh luan 19']);
    expect(screen.getByText('Xem thêm bình luận (14)')).toBeInTheDocument();
  });

  it('R20-03: no "Xem thêm bình luận" when every comment is already on screen', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (input.toString().includes('/posts')) {
        return new Response(JSON.stringify({ success: true, data: { posts: [ownPost], hasNext: false } }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<FeedTab />);

    await waitFor(() => expect(screen.getByText('Binh luan cua toi')).toBeInTheDocument());
    expect(screen.queryByText(/Xem thêm bình luận/)).not.toBeInTheDocument();
  });
});
