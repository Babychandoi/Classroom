import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor, fireEvent, within } from '@testing-library/react';
import { StudioBlog } from '../pages/studio/StudioBlog';
import { baseClassroom, makePost, mockApi, ok, renderInClass } from './blogEventsHelpers';
import type { Classroom } from '../types';

vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ user: { id: 'owner-1', fullName: 'Chủ Lớp' } }) }));

const owner: Classroom = { ...baseClassroom, userRole: 'OWNER', isOwner: true };
const viewer: Classroom = { ...baseClassroom, userRole: 'STAFF', studioPermissions: ['BLOG:VIEW'] };

const draft = makePost({ id: 'd1', title: 'Bản nháp A', status: 'DRAFT', publishedAt: null });
const published = makePost({ id: 'p1', title: 'Bài đã đăng B' });

const renderStudio = (classroom: Classroom) =>
  renderInClass(<StudioBlog />, { path: '/studio/classes/class-1/blog', pattern: '/studio/classes/:id/blog', classroom });

describe('StudioBlog', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('BLOG:VIEW only: lists drafts too but offers no create / edit / publish / delete', async () => {
    const calls = mockApi({ 'GET /classes/class-1/blog-posts': () => ok({ items: [draft, published], nextCursor: null }) });
    renderStudio(viewer);
    expect(await screen.findByText('Bản nháp A')).toBeInTheDocument();
    expect(calls[0].path).toBe('/classes/class-1/blog-posts?size=20&status=ALL');
    expect(screen.queryByRole('button', { name: /Viết bài mới/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Sửa bài/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Đăng bài' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Xóa bài/ })).not.toBeInTheDocument();
  });

  it('a staff member with only BLOG:DELETE may list drafts too and delete, but not edit or publish', async () => {
    const calls = mockApi({ 'GET /classes/class-1/blog-posts': () => ok({ items: [draft, published], nextCursor: null }) });
    renderStudio({ ...baseClassroom, userRole: 'STAFF', studioPermissions: ['BLOG:DELETE'] });
    expect(await screen.findByText('Bản nháp A')).toBeInTheDocument();
    expect(calls[0].path).toBe('/classes/class-1/blog-posts?size=20&status=ALL');
    expect(screen.getByRole('button', { name: 'Xóa bài Bài đã đăng B' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Sửa bài/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Đăng bài' })).not.toBeInTheDocument();
  });

  it('a member without any BLOG grant only asks for published posts (status=DRAFT|ALL would be 403)', async () => {
    const calls = mockApi({ 'GET /classes/class-1/blog-posts': () => ok({ items: [published], nextCursor: null }) });
    renderStudio({ ...baseClassroom, userRole: 'STAFF', studioPermissions: ['COURSE:VIEW'] });
    expect(await screen.findByText('Bài đã đăng B')).toBeInTheDocument();
    expect(calls[0].path).toBe('/classes/class-1/blog-posts?size=20');
    expect(screen.queryByRole('group', { name: 'Lọc theo trạng thái' })).not.toBeInTheDocument();
  });

  it('a draft may be saved without content (contentMarkdown omitted)', async () => {
    const calls = mockApi({
      'GET /classes/class-1/blog-posts': () => ok({ items: [], nextCursor: null }),
      'POST /classes/class-1/blog-posts': () => ok(makePost({ id: 'n', status: 'DRAFT' })),
    });
    renderStudio(owner);
    fireEvent.click(await screen.findByRole('button', { name: /Viết bài mới/ }));
    fireEvent.change(screen.getByLabelText('Tiêu đề'), { target: { value: 'Ý tưởng' } });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu bản nháp' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true));
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ title: 'Ý tưởng', audience: 'PUBLIC' });
  });

  it('status filter asks the server for drafts only', async () => {
    const calls = mockApi({ 'GET /classes/class-1/blog-posts': () => ok({ items: [draft], nextCursor: null }) });
    renderStudio(owner);
    await screen.findByText('Bản nháp A');
    fireEvent.click(screen.getByRole('button', { name: 'Nháp' }));
    await waitFor(() => expect(calls.some((c) => c.path === '/classes/class-1/blog-posts?size=20&status=DRAFT')).toBe(true));
  });

  it('creates a draft with the contract payload, including an uploaded cover (purpose BLOG)', async () => {
    const calls = mockApi({
      'GET /classes/class-1/blog-posts': () => ok({ items: [], nextCursor: null }),
      'POST /classes/class-1/media/upload-intents': () => ok({ assetId: 'asset-9', uploadUrl: 'https://store.local/put/asset-9' }),
      'PUT https://store.local/put/asset-9': () => new Response(null, { status: 200 }),
      'POST /media/asset-9/complete': () => ok({}),
      'POST /classes/class-1/blog-posts': (call) => ok(makePost({ id: 'new', title: call.body.title, status: 'DRAFT' })),
    });
    renderStudio(owner);
    fireEvent.click(await screen.findByRole('button', { name: /Viết bài mới/ }));
    const dialog = screen.getByRole('dialog', { name: 'Viết bài mới' });

    fireEvent.change(within(dialog).getByLabelText('Tiêu đề'), { target: { value: '  Bài mới  ' } });
    fireEvent.change(within(dialog).getByLabelText('Tóm tắt'), { target: { value: 'Một câu' } });
    fireEvent.change(within(dialog).getByLabelText('Chuyên mục'), { target: { value: 'Mẹo học' } });
    fireEvent.change(within(dialog).getByLabelText('Ai được đọc'), { target: { value: 'MEMBERS' } });
    fireEvent.change(within(dialog).getByLabelText('Nội dung'), { target: { value: '# Xin chào\nNội dung' } });
    const file = new File(['x'], 'bia.png', { type: 'image/png' });
    fireEvent.change(within(dialog).getByLabelText('Tải ảnh bìa'), { target: { files: [file] } });
    await waitFor(() => expect(within(dialog).getByText('Đổi ảnh bìa')).toBeInTheDocument());

    fireEvent.click(within(dialog).getByRole('button', { name: 'Lưu bản nháp' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    const intent = calls.find((c) => c.path === '/classes/class-1/media/upload-intents')!;
    expect(intent.body).toEqual({ filename: 'bia.png', mimeType: 'image/png', sizeBytes: 1, purpose: 'BLOG' });
    expect(calls.some((c) => c.method === 'POST' && c.path === '/media/asset-9/complete')).toBe(true);
    const create = calls.find((c) => c.method === 'POST' && c.path === '/classes/class-1/blog-posts')!;
    expect(create.body).toEqual({
      title: 'Bài mới',
      excerpt: 'Một câu',
      category: 'Mẹo học',
      contentMarkdown: '# Xin chào\nNội dung',
      coverMediaId: 'asset-9',
      audience: 'MEMBERS',
    });
    expect(screen.getByRole('status')).toHaveTextContent('Đã lưu bản nháp');
  });

  it('rejects a cover larger than 5 MB before any request', async () => {
    const calls = mockApi({ 'GET /classes/class-1/blog-posts': () => ok({ items: [], nextCursor: null }) });
    renderStudio(owner);
    fireEvent.click(await screen.findByRole('button', { name: /Viết bài mới/ }));
    const big = new File(['x'], 'to.png', { type: 'image/png' });
    Object.defineProperty(big, 'size', { value: 6 * 1024 * 1024 });
    fireEvent.change(screen.getByLabelText('Tải ảnh bìa'), { target: { files: [big] } });
    expect(await screen.findByRole('alert')).toHaveTextContent('Ảnh bìa tối đa 5 MB.');
    expect(calls.some((c) => c.path.includes('upload-intents'))).toBe(false);
  });

  it('edits: loads the full post first, then PUTs the edited fields', async () => {
    const calls = mockApi({
      'GET /classes/class-1/blog-posts': () => ok({ items: [published], nextCursor: null }),
      'GET /blog-posts/p1': () => ok({ ...published, contentMarkdown: 'Nội dung cũ' }),
      'PUT /blog-posts/p1': (call) => ok({ ...published, title: call.body.title }),
    });
    renderStudio(owner);
    fireEvent.click(await screen.findByRole('button', { name: 'Sửa bài Bài đã đăng B' }));
    const dialog = screen.getByRole('dialog', { name: 'Sửa bài viết' });
    await waitFor(() => expect(within(dialog).getByLabelText('Nội dung')).toHaveValue('Nội dung cũ'));
    fireEvent.change(within(dialog).getByLabelText('Tiêu đề'), { target: { value: 'Tiêu đề mới' } });
    fireEvent.change(within(dialog).getByLabelText('Tóm tắt'), { target: { value: '' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Lưu thay đổi' }));

    await waitFor(() => expect(screen.getByText('Tiêu đề mới')).toBeInTheDocument());
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.path).toBe('/blog-posts/p1');
    expect(put.body).toEqual({ title: 'Tiêu đề mới', excerpt: '', category: 'Bắt đầu', contentMarkdown: 'Nội dung cũ', audience: 'PUBLIC' });
  });

  it('publishes a draft and unpublishes a published post', async () => {
    const calls = mockApi({
      'GET /classes/class-1/blog-posts': () => ok({ items: [draft, published], nextCursor: null }),
      'POST /blog-posts/d1/publish': () => ok({ ...draft, status: 'PUBLISHED', publishedAt: '2026-07-09T00:00:00Z' }),
      'POST /blog-posts/p1/unpublish': () => ok({ ...published, status: 'DRAFT' }),
    });
    renderStudio(owner);
    await screen.findByText('Bản nháp A');
    fireEvent.click(screen.getByRole('button', { name: 'Đăng bài' }));
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Gỡ xuống' })).toHaveLength(2));
    fireEvent.click(screen.getAllByRole('button', { name: 'Gỡ xuống' })[1]);
    await waitFor(() => expect(calls.some((c) => c.path === '/blog-posts/p1/unpublish')).toBe(true));
    expect(calls.some((c) => c.method === 'POST' && c.path === '/blog-posts/d1/publish')).toBe(true);
  });

  it('deletes only after confirming', async () => {
    const calls = mockApi({
      'GET /classes/class-1/blog-posts': () => ok({ items: [published], nextCursor: null }),
      'DELETE /blog-posts/p1': () => ok({}),
    });
    renderStudio(owner);
    fireEvent.click(await screen.findByRole('button', { name: 'Xóa bài Bài đã đăng B' }));
    const confirm = screen.getByRole('alertdialog', { name: 'Xóa bài viết?' });
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);
    fireEvent.click(within(confirm).getByRole('button', { name: 'Xóa bài viết' }));
    await waitFor(() => expect(screen.queryByText('Bài đã đăng B')).not.toBeInTheDocument());
    expect(calls.some((c) => c.method === 'DELETE' && c.path === '/blog-posts/p1')).toBe(true);
  });
});
