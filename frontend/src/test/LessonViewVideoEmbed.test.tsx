import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { LessonViewPage } from '../pages/classroom/LessonViewPage';

vi.mock('react-router-dom', () => ({
  useParams: () => ({ slug: 'demo', lessonId: 'l1' }),
  useNavigate: () => vi.fn(),
  Link: (props: { to: string; children?: React.ReactNode }) => React.createElement('a', { href: props.to }, props.children),
}));
const mockUser = { id: 'u1', fullName: 'Học viên' };
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ user: mockUser }) }));

const lesson = (over: Record<string, unknown>) => ({
  id: 'l1', sectionId: 's1', courseId: 'c1', title: 'Bài video ngoài', type: 'VIDEO', position: 1, durationMinutes: 5, completed: false,
  ...over,
});

function serve(l: Record<string, unknown>) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = input.toString();
    const ok = (data: unknown) => new Response(JSON.stringify({ success: true, data }), { status: 200 });
    if (url.endsWith('/lessons/l1')) return ok(l);
    if (url.endsWith('/lessons/l1/questions')) return ok([]);
    if (url.endsWith('/lessons/l1/submissions/mine')) return ok([]);
    return new Response('{}', { status: 404 });
  });
}

describe('LessonViewPage - external video (YouTube / Google Drive)', () => {
  beforeEach(() => { vi.restoreAllMocks(); });

  it('renders a titled, sandboxed iframe from embedUrl with an "open in a new tab" fallback', async () => {
    serve(lesson({
      videoProvider: 'YOUTUBE', videoUrl: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ', embedUrl: 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ',
    }));
    render(<LessonViewPage />);
    const frame = await screen.findByTitle('Video bài học: Bài video ngoài');
    expect(frame.tagName).toBe('IFRAME');
    expect(frame).toHaveAttribute('src', 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ');
    expect(frame).toHaveAttribute('sandbox', 'allow-scripts allow-same-origin allow-presentation allow-popups');
    expect(frame).toHaveAttribute('allow', 'autoplay; encrypted-media; picture-in-picture; fullscreen');
    expect(frame).toHaveAttribute('referrerpolicy', 'strict-origin-when-cross-origin');
    const link = screen.getByRole('link', { name: /Mở trong tab mới/ });
    expect(link).toHaveAttribute('href', 'https://www.youtube.com/watch?v=dQw4w9WgXcQ');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link.getAttribute('rel')).toContain('noopener');
    expect(document.querySelector('video')).toBeNull();
  });

  it('has no iframe for an uploaded video', async () => {
    serve(lesson({ videoProvider: 'UPLOAD', mediaAssetId: 'm1', mediaDownloadUrl: 'http://minio/x.mp4' }));
    render(<LessonViewPage />);
    await waitFor(() => expect(document.querySelector('video')).not.toBeNull());
    expect(document.querySelector('iframe')).toBeNull();
  });
});
