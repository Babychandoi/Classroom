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
});
