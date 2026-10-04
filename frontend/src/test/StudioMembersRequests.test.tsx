import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, act, within } from '@testing-library/react';
import { StudioMembers } from '../pages/studio/StudioMembers';
import type { Classroom } from '../types';

/**
 * API-CREATE-CLASS §3: join requests (state=PENDING) in Studio "Thành viên" - listed with requestedAt, "Duyệt" right away,
 * "Từ chối" only after confirming, decisions gated on MEMBER:EDIT, and the class refreshed so the counts follow.
 */

type ClassroomX = Classroom & { pendingRequestCount?: number; requireApproval?: boolean };
const owner: ClassroomX = {
  id: 'class-1', ownerId: 'o', slug: 'demo', title: 'Demo', status: 'ACTIVE', memberCount: 2, userRole: 'OWNER',
  createdAt: '', requireApproval: true, pendingRequestCount: 2,
} as ClassroomX;

let current: ClassroomX = owner;
const refreshClassroom = vi.fn(async () => {});
vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: current, refreshClassroom }),
}));

const requested = '2026-10-02T09:00:00Z';
const pendingRows = [
  { id: 'r1', userId: 'u-pro', role: 'STUDENT', state: 'PENDING', joinedAt: null, requestedAt: requested, userFullName: 'Học Viên Pro', userEmail: 'pro@x' },
  { id: 'r2', userId: 'u-two', role: 'STUDENT', state: 'PENDING', joinedAt: null, requestedAt: requested, userFullName: 'Người Thứ Hai', userEmail: 'two@x' },
];
const activeRows = [{ id: 'm1', userId: 'u-a', role: 'STUDENT', state: 'ACTIVE', joinedAt: requested, userFullName: 'Thành Viên A' }];
const page = (members: unknown[]) => ({ success: true, data: { members, total: members.length, page: 0, size: 50, hasNext: false } });

function mockApi(calls: { method: string; url: string }[] = []) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = input.toString();
    const method = (init?.method || 'GET').toUpperCase();
    calls.push({ method, url });
    if (method === 'POST' && /\/studio\/members\/[^/]+\/(approve|reject)$/.test(url)) return new Response(JSON.stringify({ success: true, data: null }), { status: 200 });
    if (url.includes('/studio/members') && url.includes('state=PENDING')) return new Response(JSON.stringify(page(pendingRows)), { status: 200 });
    if (url.includes('/studio/members')) return new Response(JSON.stringify(page(activeRows)), { status: 200 });
    return new Response('{}', { status: 404 });
  });
  return calls;
}

describe('StudioMembers — join requests', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    refreshClassroom.mockClear();
    current = owner;
  });

  it('lists the PENDING requests with their request date and the count badge', async () => {
    mockApi();
    render(<StudioMembers />);
    const section = await screen.findByRole('region', { name: 'Chờ duyệt' });
    await within(section).findByText('Học Viên Pro');
    expect(within(section).getByText('Người Thứ Hai')).toBeInTheDocument();
    expect(within(section).getAllByTestId('requested-at')[0]).toHaveTextContent('Gửi yêu cầu: 02/10/2026');
    expect(screen.getByTestId('pending-count')).toHaveTextContent('2 yêu cầu');
    // the roster filter offers "Chờ duyệt" too
    expect(screen.getByRole('option', { name: 'Chờ duyệt (2)' })).toHaveValue('PENDING');
  });

  it('"Duyệt" approves right away, then reloads the lists and the class', async () => {
    const calls = mockApi();
    render(<StudioMembers />);
    const section = await screen.findByRole('region', { name: 'Chờ duyệt' });
    await within(section).findByText('Học Viên Pro');
    await act(async () => {
      fireEvent.click(within(section).getByRole('button', { name: 'Duyệt Học Viên Pro' }));
    });
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/classes/class-1/studio/members/u-pro/approve'))).toBe(true));
    await waitFor(() => expect(refreshClassroom).toHaveBeenCalled());
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
  });

  it('"Từ chối" asks first; Hủy sends nothing, confirming posts reject', async () => {
    const calls = mockApi();
    render(<StudioMembers />);
    const section = await screen.findByRole('region', { name: 'Chờ duyệt' });
    await within(section).findByText('Người Thứ Hai');

    fireEvent.click(within(section).getByRole('button', { name: 'Từ chối Người Thứ Hai' }));
    let dialog = await screen.findByRole('alertdialog');
    expect(dialog).toHaveTextContent('từ chối yêu cầu tham gia của Người Thứ Hai');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Hủy' }));
    expect(calls.some((c) => c.url.endsWith('/reject'))).toBe(false);

    fireEvent.click(within(section).getByRole('button', { name: 'Từ chối Người Thứ Hai' }));
    dialog = await screen.findByRole('alertdialog');
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Từ chối' }));
    });
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/studio/members/u-two/reject'))).toBe(true));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
  });

  it('staff with MEMBER:VIEW only sees the requests but gets no decision buttons', async () => {
    current = { ...owner, userRole: 'STAFF', studioPermissions: ['MEMBER:VIEW'] } as ClassroomX;
    mockApi();
    render(<StudioMembers />);
    const section = await screen.findByRole('region', { name: 'Chờ duyệt' });
    await within(section).findByText('Học Viên Pro');
    expect(within(section).queryByRole('button', { name: /Duyệt|Từ chối/ })).not.toBeInTheDocument();
    expect(section).toHaveTextContent('MEMBER:EDIT');
  });

  it('does not ask for requests when approval is off and nothing is pending', async () => {
    current = { ...owner, requireApproval: false, pendingRequestCount: 0 } as ClassroomX;
    const calls = mockApi();
    render(<StudioMembers />);
    await screen.findByText('Thành Viên A');
    expect(calls.some((c) => c.url.includes('state=PENDING'))).toBe(false);
    expect(screen.queryByRole('region', { name: 'Chờ duyệt' })).not.toBeInTheDocument();
  });
});
