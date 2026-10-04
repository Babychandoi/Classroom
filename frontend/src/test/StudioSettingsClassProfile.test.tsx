import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, act, within } from '@testing-library/react';
import { StudioSettings, FALLBACK_CATEGORIES } from '../pages/studio/StudioSettings';
import { formatPosition, parsePosition } from '../pages/studio/ImagePositioner';
import type { Classroom } from '../types';

/**
 * API-CREATE-CLASS §1-2 in Studio "Cài đặt": category chips, "Duyệt từng người trước khi vào", class avatar (CLASS_AVATAR) and
 * "Căn ảnh" (coverPosition / avatarPosition). Every PUT carries the saved title/description/coverImageUrl (the current
 * server replaces them) plus only the changed new field.
 */

type ClassroomX = Classroom & {
  category?: string | null; avatarUrl?: string | null; coverPosition?: string | null; avatarPosition?: string | null;
  requireApproval?: boolean; pendingRequestCount?: number;
};
const base: ClassroomX = {
  id: 'class-1', ownerId: 'o', slug: 'demo', title: 'Demo Class', description: 'Mô tả', coverImageUrl: 'https://img.local/c.jpg',
  status: 'ACTIVE', memberCount: 2, userRole: 'OWNER', createdAt: '', category: 'Ôn thi',
} as ClassroomX;

let current: ClassroomX = base;
const refreshClassroom = vi.fn(async () => {});
vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: current, refreshClassroom }),
}));

const ok = (data: unknown) => new Response(JSON.stringify({ success: true, data }), { status: 200 });
const core = { title: 'Demo Class', description: 'Mô tả', coverImageUrl: 'https://img.local/c.jpg' };

function mockApi(opts: { categories?: string[] | 'fail' } = {}) {
  const puts: any[] = [];
  const posts: { url: string; body: any }[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = input.toString();
    const method = (init?.method || 'GET').toUpperCase();
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined;
    if (url.endsWith('/classes/categories')) {
      return opts.categories === 'fail' || !opts.categories ? new Response('{}', { status: 404 }) : ok(opts.categories);
    }
    if (url.endsWith('/classes/class-1') && method === 'PUT') { puts.push(body); return ok({}); }
    if (url.includes('/media/upload-intents')) { posts.push({ url, body }); return ok({ assetId: 'av-1', uploadUrl: 'http://store.local/put-av' }); }
    if (url === 'http://store.local/put-av') return new Response('', { status: 200 });
    if (url.includes('/media/av-1/complete')) return ok({});
    return new Response('{}', { status: 404 });
  });
  return { puts, posts };
}

describe('StudioSettings — class profile (category, approval, avatar, positions)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    refreshClassroom.mockClear();
    current = base;
  });

  it('position helpers read and write "x% y%" (centre for anything malformed)', () => {
    expect(parsePosition('30% 70%')).toEqual({ x: 30, y: 70 });
    expect(parsePosition(null)).toEqual({ x: 50, y: 50 });
    expect(parsePosition('left top')).toEqual({ x: 50, y: 50 });
    expect(parsePosition('150% 20%')).toEqual({ x: 100, y: 20 });
    expect(formatPosition({ x: 12.6, y: -4 })).toBe('13% 0%');
  });

  it('shows the categories from GET /classes/categories, and saves only a changed category with the info form', async () => {
    const { puts } = mockApi({ categories: ['Nấu ăn', 'Ôn thi', 'AI'] });
    render(<StudioSettings />);
    const group = screen.getByRole('group', { name: 'Chủ đề lớp' });
    await waitFor(() => expect(within(group).getAllByRole('button')).toHaveLength(3));
    expect(within(group).getByRole('button', { name: 'Ôn thi' })).toHaveAttribute('aria-pressed', 'true');

    // unchanged category: not sent
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Lưu thay đổi/ })); });
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).not.toHaveProperty('category');

    fireEvent.click(within(group).getByRole('button', { name: 'AI' }));
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Lưu thay đổi/ })); });
    await waitFor(() => expect(puts).toHaveLength(2));
    expect(puts[1]).toMatchObject({ ...core, category: 'AI' });
  });

  it('falls back to the contract category list when the endpoint is unavailable', async () => {
    mockApi({ categories: 'fail' });
    render(<StudioSettings />);
    const group = screen.getByRole('group', { name: 'Chủ đề lớp' });
    expect(within(group).getAllByRole('button').map((b) => b.textContent)).toEqual(FALLBACK_CATEGORIES);
  });

  it('the approval switch saves requireApproval right away with the core fields only', async () => {
    const { puts } = mockApi();
    render(<StudioSettings />);
    const toggle = screen.getByRole('switch', { name: 'Duyệt từng người trước khi vào' });
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    await act(async () => { fireEvent.click(toggle); });
    await waitFor(() => expect(puts).toEqual([{ ...core, requireApproval: true }]));
    expect(refreshClassroom).toHaveBeenCalled();
    expect(await screen.findByText(/Đã bật duyệt thành viên/)).toBeInTheDocument();
  });

  it('warns that turning approval off leaves pending requests waiting', () => {
    current = { ...base, requireApproval: true, pendingRequestCount: 3 };
    mockApi();
    render(<StudioSettings />);
    expect(screen.getByRole('switch', { name: 'Duyệt từng người trước khi vào' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByTestId('approval-row')).toHaveTextContent('Đang có 3 yêu cầu chờ duyệt');
  });

  it('uploads a class avatar with purpose CLASS_AVATAR and saves avatarMediaId', async () => {
    const { puts, posts } = mockApi();
    render(<StudioSettings />);
    fireEvent.change(screen.getByLabelText('Tải ảnh đại diện lên'), { target: { files: [new File(['x'], 'logo.png', { type: 'image/png' })] } });
    await screen.findByText('Đã cập nhật ảnh đại diện của lớp.');
    expect(posts[0].body).toMatchObject({ filename: 'logo.png', purpose: 'CLASS_AVATAR' });
    expect(puts).toEqual([{ ...core, avatarMediaId: 'av-1' }]);
  });

  it('shows the square avatar at its saved position, and removes it with an empty avatarMediaId', async () => {
    current = { ...base, avatarUrl: 'http://store.local/get-av', avatarPosition: '20% 80%' };
    const { puts } = mockApi();
    render(<StudioSettings />);
    const img = screen.getByAltText('Ảnh đại diện của lớp Demo Class');
    expect(img).toHaveAttribute('src', 'http://store.local/get-av');
    expect(img.style.objectPosition).toBe('20% 80%');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Gỡ ảnh đại diện/ })); });
    await waitFor(() => expect(puts).toEqual([{ ...core, avatarMediaId: '' }]));
  });

  it('"Căn ảnh bìa": sliders move the preview and "Lưu vị trí" saves coverPosition', async () => {
    current = { ...base, coverPosition: '50% 30%' };
    const { puts } = mockApi();
    render(<StudioSettings />);
    expect(screen.getByAltText('Ảnh bìa của lớp Demo Class').style.objectPosition).toBe('50% 30%');

    fireEvent.click(screen.getByRole('button', { name: /Căn ảnh bìa/ }));
    const editor = screen.getByRole('group', { name: 'Căn ảnh bìa' });
    fireEvent.change(within(editor).getByLabelText(/Ngang/), { target: { value: '20' } });
    fireEvent.change(within(editor).getByLabelText(/Dọc/), { target: { value: '75' } });
    expect((within(editor).getByTestId('position-preview').querySelector('img') as HTMLImageElement).style.objectPosition).toBe('20% 75%');

    await act(async () => { fireEvent.click(within(editor).getByRole('button', { name: 'Lưu vị trí' })); });
    await waitFor(() => expect(puts).toEqual([{ ...core, coverPosition: '20% 75%' }]));
    expect(await screen.findByText('Đã lưu vị trí ảnh bìa.')).toBeInTheDocument();
  });

  it('arrow keys on the preview nudge the position; Hủy closes without saving', () => {
    const { puts } = mockApi();
    render(<StudioSettings />);
    fireEvent.click(screen.getByRole('button', { name: /Căn ảnh bìa/ }));
    const preview = screen.getByTestId('position-preview');
    fireEvent.keyDown(preview, { key: 'ArrowRight' });
    fireEvent.keyDown(preview, { key: 'ArrowUp', shiftKey: true });
    expect((preview.querySelector('img') as HTMLImageElement).style.objectPosition).toBe('52% 40%');
    fireEvent.click(within(screen.getByRole('group', { name: 'Căn ảnh bìa' })).getByRole('button', { name: 'Hủy' }));
    expect(screen.queryByRole('group', { name: 'Căn ảnh bìa' })).not.toBeInTheDocument();
    expect(puts).toEqual([]);
  });

  it('staff without CLASS:EDIT gets no avatar uploader and a disabled approval switch', () => {
    current = { ...base, userRole: 'STAFF', studioPermissions: ['CLASS:VIEW'] } as ClassroomX;
    mockApi();
    render(<StudioSettings />);
    expect(screen.queryByLabelText('Tải ảnh đại diện lên')).not.toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Duyệt từng người trước khi vào' })).toBeDisabled();
  });
});
