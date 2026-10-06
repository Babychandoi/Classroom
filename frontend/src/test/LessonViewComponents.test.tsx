import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { LessonViewPage } from '../pages/classroom/LessonViewPage';

vi.mock('react-router-dom', () => ({
  useParams: () => ({ slug: 'demo', lessonId: 'l1' }),
  useNavigate: () => vi.fn(),
  Link: (props: { to: string; children?: React.ReactNode }) => React.createElement('a', { href: props.to }, props.children),
}));
const mockUser = { id: 'u1', fullName: 'Học viên' };
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ user: mockUser }) }));

const base = { id: 'l1', sectionId: 's1', courseId: 'c1', title: 'Bài nhiều thành phần', position: 1, durationMinutes: 8, completed: false };
const none = { video: false, videoProvider: null, content: false, attachments: 0, assignment: false };

function serve(lesson: Record<string, unknown>) {
  const calls: string[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = input.toString().replace(/^https?:\/\/[^/]+/, '');
    calls.push(url);
    const ok = (data: unknown) => new Response(JSON.stringify({ success: true, data }), { status: 200 });
    if (url.endsWith('/lessons/l1')) return ok(lesson);
    if (url.endsWith('/lessons/l1/questions')) return ok([]);
    if (url.endsWith('/lessons/l1/submissions/mine')) return ok([]);
    if (/\/media\/m-[a-z0-9]+\/download-url$/.test(url)) return ok({ downloadUrl: 'http://minio.test/file.pdf' });
    return new Response('{}', { status: 404 });
  });
  return calls;
}
const tabNames = () => within(screen.getByRole('tablist', { name: 'Nội dung bài học' })).getAllByRole('tab').map((t) => t.textContent);

describe('LessonViewPage - a lesson is a bundle of components', () => {
  beforeEach(() => { vi.restoreAllMocks(); });

  it('shows the video on top and a tab for each component the lesson has', async () => {
    serve({
      ...base, contentText: 'Nội dung **chính**', hasAssignment: true, assignmentInstructions: 'Viết 100 chữ',
      videoProvider: 'YOUTUBE', videoUrl: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ', embedUrl: 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ',
      attachments: [{ id: 'a1', mediaAssetId: 'm-a1', title: 'Slide', sizeBytes: 2048, position: 1 }, { id: 'a2', mediaAssetId: 'm-a2', title: 'Đề cương', position: 2 }],
      components: { video: true, videoProvider: 'YOUTUBE', content: true, attachments: 2, assignment: true },
    });
    render(<LessonViewPage />);
    await screen.findByTitle('Video bài học: Bài nhiều thành phần');
    await waitFor(() => expect(tabNames()).toEqual(['Nội dung', 'Tài liệu(2)', 'Bài tập', 'Hỏi đáp0']));
    expect(screen.getByText('chính').tagName).toBe('STRONG');

    fireEvent.click(screen.getByRole('tab', { name: /Bài tập/ }));
    expect(await screen.findByText('Viết 100 chữ')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Nộp bài' })).toBeInTheDocument();
  });

  it('lists the documents and downloads each through a fresh presigned URL', async () => {
    const calls = serve({
      ...base, contentText: 'x', components: { ...none, content: true, attachments: 2 },
      attachments: [{ id: 'a1', mediaAssetId: 'm-a1', title: 'Slide', sizeBytes: 2048, position: 1 }, { id: 'a2', mediaAssetId: 'm-a2', title: 'Đề cương', position: 2 }],
    });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    render(<LessonViewPage />);
    fireEvent.click(await screen.findByRole('tab', { name: /Tài liệu/ }));
    expect(screen.getByText('Slide')).toBeInTheDocument();
    expect(screen.getByText('2 KB')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Tải tài liệu Slide' }));
    await waitFor(() => expect(calls.some((u) => u.endsWith('/media/m-a1/download-url'))).toBe(true));
    await waitFor(() => expect(click).toHaveBeenCalled());
  });

  it('a content-only lesson has neither a documents nor an assignment tab', async () => {
    serve({ ...base, contentText: 'Chỉ có chữ', components: { ...none, content: true } });
    render(<LessonViewPage />);
    await screen.findByText('Chỉ có chữ');
    expect(tabNames()).toEqual(['Nội dung', 'Hỏi đáp0']);
    expect(document.querySelector('video, iframe')).toBeNull();
  });

  it('a documents-only lesson opens on the documents tab', async () => {
    serve({ ...base, components: { ...none, attachments: 1 }, attachments: [{ id: 'a1', mediaAssetId: 'm-a1', title: 'Giáo trình', position: 1 }] });
    render(<LessonViewPage />);
    expect(await screen.findByText('Giáo trình')).toBeInTheDocument();
    expect(tabNames()).toEqual(['Tài liệu(1)', 'Hỏi đáp0']);
  });

  it('a legacy payload with only a type still renders (assignment lesson)', async () => {
    serve({ ...base, type: 'ASSIGNMENT', contentText: 'Làm bài tập này' });
    render(<LessonViewPage />);
    await screen.findByRole('tab', { name: /Bài tập/ });
  });
});
