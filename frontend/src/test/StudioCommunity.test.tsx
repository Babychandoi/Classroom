import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { StudioAbout, StudioDocuments, StudioFeed } from '../pages/studio/StudioCommunity';
import type { Classroom } from '../types';

/** Bảng tin / Tài liệu / Giới thiệu: wildcard-aware gating, and no form at all for a viewer who may not submit it. */

let classroom: Classroom;
vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom }),
  useParams: () => ({ id: 'class-1' }),
}));

const staff = (grants: string[]) =>
  ({ id: 'class-1', ownerId: 'o', slug: 'demo', title: 'Demo', status: 'ACTIVE', memberCount: 1, userRole: 'STAFF', studioPermissions: grants, createdAt: '' }) as Classroom;

describe('StudioCommunity pages', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('StudioFeed: a FEED:* wildcard grant can post', async () => {
    classroom = staff(['FEED:*']);
    let body: any = null;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      if (input.toString().endsWith('/classes/class-1/posts')) {
        body = JSON.parse(String(init?.body));
        return new Response(JSON.stringify({ success: true, data: {} }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });
    render(<StudioFeed />);
    fireEvent.change(screen.getByLabelText('Tiêu đề'), { target: { value: 'Lịch học' } });
    fireEvent.change(screen.getByLabelText(/Nội dung/), { target: { value: 'Thứ Ba 20:00' } });
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Đăng bài' })); });
    await waitFor(() => expect(body).toEqual({ title: 'Lịch học', contentMarkdown: 'Thứ Ba 20:00', visibility: 'PUBLIC', pinned: false }));
    expect(screen.getByRole('status')).toHaveTextContent('Đã đăng bài.');
  });

  it('StudioFeed: without FEED:CREATE there is no form', () => {
    classroom = staff(['FEED:VIEW']);
    render(<StudioFeed />);
    expect(screen.queryByLabelText('Tiêu đề')).not.toBeInTheDocument();
    expect(screen.getByText(/FEED:CREATE/)).toBeInTheDocument();
  });

  it('StudioDocuments: "*:CREATE" shows the form, DOCUMENT:VIEW alone does not', () => {
    classroom = staff(['*:CREATE']);
    const { unmount } = render(<StudioDocuments />);
    expect(screen.getByLabelText('Tên tài liệu')).toBeInTheDocument();
    unmount();
    classroom = staff(['DOCUMENT:VIEW']);
    render(<StudioDocuments />);
    expect(screen.queryByLabelText('Tên tài liệu')).not.toBeInTheDocument();
    expect(document.querySelector('input[type="file"]')).toBeNull();
  });

  it('StudioAbout: ABOUT:* loads and shows the editor; without ABOUT:EDIT nothing is requested', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ success: true, data: { contentMarkdown: 'Xin chào', rulesMarkdown: '', sections: [], publishedVersion: 3 } }), { status: 200 }),
    );
    classroom = staff(['ABOUT:*']);
    const { unmount } = render(<StudioAbout />);
    await waitFor(() => expect(screen.getByDisplayValue('Xin chào')).toBeInTheDocument());
    unmount();

    fetchSpy.mockClear();
    classroom = staff(['CLASS:VIEW']);
    render(<StudioAbout />);
    expect(screen.queryByRole('button', { name: 'Lưu' })).not.toBeInTheDocument();
    expect(screen.getByText(/ABOUT:EDIT/)).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});
