import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { StudioSettings } from '../pages/studio/StudioSettings';
import type { Classroom } from '../types';

/**
 * R13-02: Studio "Cài đặt lớp" page — edit form (title/description/coverImageUrl) and the
 * archive/unarchive danger zone with its in-page confirmation. The danger zone must only ever
 * render for the class OWNER (archive is OWNER-only server-side — see
 * ClassroomService#updateClassroomStatus).
 */

const baseClassroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  description: 'Original description',
  coverImageUrl: '',
  status: 'ACTIVE',
  memberCount: 2,
  userRole: 'OWNER',
  createdAt: new Date().toISOString(),
} as Classroom;

let currentClassroom = baseClassroom;
const refreshClassroom = vi.fn(async () => {});

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: currentClassroom, refreshClassroom }),
}));

describe('StudioSettings (R13-02)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    refreshClassroom.mockClear();
    currentClassroom = baseClassroom;
  });

  it('submits the edited title/description/coverImageUrl to PUT /classes/{id}', async () => {
    let capturedBody: any = null;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      if (url.endsWith('/classes/class-1') && method === 'PUT') {
        capturedBody = JSON.parse(String(init?.body));
        return new Response(JSON.stringify({ success: true, data: {} }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioSettings />);

    const titleInput = screen.getByLabelText('Tên lớp học') as HTMLInputElement;
    fireEvent.change(titleInput, { target: { value: 'Updated Title' } });

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Lưu thay đổi/ }));
    });

    await waitFor(() => expect(capturedBody).not.toBeNull());
    expect(capturedBody.title).toBe('Updated Title');
    expect(refreshClassroom).toHaveBeenCalled();
  });

  it('shows the archive danger zone for the OWNER and archives after confirmation', async () => {
    let statusBody: any = null;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      if (url.endsWith('/classes/class-1/status') && method === 'PUT') {
        statusBody = JSON.parse(String(init?.body));
        return new Response(JSON.stringify({ success: true, data: {} }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioSettings />);

    fireEvent.click(screen.getByText('Lưu trữ (đóng) lớp học'));
    await waitFor(() => expect(screen.getByText(/Xác nhận lưu trữ/)).toBeInTheDocument());

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Xác nhận' }));
    });

    await waitFor(() => expect(statusBody).toEqual({ status: 'ARCHIVED' }));
  });

  it('hides the archive danger zone entirely for a non-owner (e.g. staff with CLASS:EDIT)', () => {
    currentClassroom = { ...baseClassroom, userRole: 'STAFF', studioPermissions: ['CLASS:EDIT'] } as Classroom;

    render(<StudioSettings />);

    expect(screen.queryByText('Vùng nguy hiểm')).not.toBeInTheDocument();
  });

  it('disables the edit form for a staff member without CLASS:EDIT', () => {
    currentClassroom = { ...baseClassroom, userRole: 'STAFF', studioPermissions: [] } as Classroom;

    render(<StudioSettings />);

    const titleInput = screen.getByLabelText('Tên lớp học') as HTMLInputElement;
    expect(titleInput).toBeDisabled();
    expect(screen.queryByRole('button', { name: /Lưu thay đổi/ })).not.toBeInTheDocument();
  });

  describe('class cover uploader', () => {
    const image = () => new File(['png-bytes'], 'bia.png', { type: 'image/png' });

    it('uploads through intent -> object store -> complete, then saves coverMediaId with the saved fields', async () => {
      const calls: { method: string; url: string; body: any }[] = [];
      vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
        const url = input.toString();
        const method = (init?.method || 'GET').toUpperCase();
        const body = typeof init?.body === 'string' ? JSON.parse(init.body) : init?.body;
        calls.push({ method, url, body });
        if (url.includes('/classes/class-1/media/upload-intents')) {
          return new Response(JSON.stringify({ success: true, data: { assetId: 'cover-1', uploadUrl: 'http://store.local/put-cover' } }), { status: 200 });
        }
        if (url === 'http://store.local/put-cover') return new Response('', { status: 200 });
        if (url.includes('/media/cover-1/complete')) return new Response(JSON.stringify({ success: true, data: {} }), { status: 200 });
        if (url.endsWith('/classes/class-1') && method === 'PUT') return new Response(JSON.stringify({ success: true, data: {} }), { status: 200 });
        return new Response('{}', { status: 404 });
      });

      render(<StudioSettings />);
      // A half-edited title must not ride along with the cover save.
      fireEvent.change(screen.getByLabelText('Tên lớp học'), { target: { value: 'Unsaved title' } });
      fireEvent.change(screen.getByLabelText('Tải ảnh bìa lên'), { target: { files: [image()] } });

      await screen.findByText('Đã cập nhật ảnh bìa của lớp.');
      const intent = calls.find((c) => c.url.includes('/upload-intents'))!;
      expect(intent.body).toMatchObject({ filename: 'bia.png', mimeType: 'image/png', purpose: 'CLASS_COVER' });
      const order = calls.map((c) => c.url);
      expect(order.indexOf('http://store.local/put-cover')).toBeGreaterThan(order.indexOf(intent.url));
      expect(calls.find((c) => c.url === 'http://store.local/put-cover')!.method).toBe('PUT');
      const put = calls.find((c) => c.url.endsWith('/classes/class-1') && c.method === 'PUT')!;
      expect(put.body).toEqual({ title: 'Demo Class', description: 'Original description', coverImageUrl: '', coverMediaId: 'cover-1' });
      expect(order.indexOf(put.url)).toBeGreaterThan(order.findIndex((u) => u.includes('/media/cover-1/complete')));
      expect(refreshClassroom).toHaveBeenCalled();
    });

    it('rejects a non-image and a file over 5 MB without any request', async () => {
      const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}', { status: 404 }));
      render(<StudioSettings />);
      const input = screen.getByLabelText('Tải ảnh bìa lên');

      fireEvent.change(input, { target: { files: [new File(['%PDF'], 'a.pdf', { type: 'application/pdf' })] } });
      expect(await screen.findByRole('alert')).toHaveTextContent('phải là tệp ảnh');

      const big = new File(['x'], 'big.jpg', { type: 'image/jpeg' });
      Object.defineProperty(big, 'size', { value: 5 * 1024 * 1024 + 1 });
      fireEvent.change(input, { target: { files: [big] } });
      expect(await screen.findByRole('alert')).toHaveTextContent('tối đa 5 MB');
      // Only the category list may be fetched (on mount) - nothing about the upload.
      expect(fetchSpy.mock.calls.map((c) => c[0].toString()).filter((u) => !u.includes('/classes/categories'))).toEqual([]);
    });

    it('shows the uploaded cover and removes it with an empty coverMediaId', async () => {
      currentClassroom = { ...baseClassroom, coverUrl: 'http://store.local/get-cover' } as Classroom;
      let putBody: any = null;
      vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
        if (input.toString().endsWith('/classes/class-1') && (init?.method || '').toUpperCase() === 'PUT') {
          putBody = JSON.parse(String(init?.body));
          return new Response(JSON.stringify({ success: true, data: {} }), { status: 200 });
        }
        return new Response('{}', { status: 404 });
      });
      render(<StudioSettings />);
      expect(screen.getByAltText('Ảnh bìa của lớp Demo Class')).toHaveAttribute('src', 'http://store.local/get-cover');
      expect(screen.getByLabelText('Đổi ảnh bìa')).toBeInTheDocument();

      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: /Gỡ ảnh bìa/ }));
      });
      await waitFor(() => expect(putBody).not.toBeNull());
      expect(putBody.coverMediaId).toBe('');
    });

    it('offers no uploader to staff without CLASS:EDIT', () => {
      currentClassroom = { ...baseClassroom, userRole: 'STAFF', studioPermissions: ['CLASS:VIEW'] } as Classroom;
      render(<StudioSettings />);
      expect(screen.queryByLabelText('Tải ảnh bìa lên')).not.toBeInTheDocument();
      expect(screen.getByText(/để đổi ảnh bìa/)).toBeInTheDocument();
    });
  });
});
