import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, act, within } from '@testing-library/react';
import { StudioMembers } from '../pages/studio/StudioMembers';
import { formatDate } from '../api/format';
import type { ClassInvite, Classroom } from '../types';

/**
 * D-19: "Mời thành viên" on the Studio members page (create -> full link shown ONCE with a copy button -> list with status badges ->
 * revoke with confirmation) and the EXPIRED roster state (filter, badge, "Hết hạn dd/MM/yyyy", remove / block still offered).
 */

const FULL_CODE = 'q8r0vKc2Lw1nZ5uYtH7eXb3GjM9dPaSf';

const ownerClassroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 2,
  userRole: 'OWNER',
  visibility: 'PRIVATE',
  accessType: 'FREE',
  createdAt: new Date().toISOString(),
} as Classroom;

let currentClassroom: Classroom = ownerClassroom;
vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: currentClassroom }),
}));

const DAY = 24 * 60 * 60 * 1000;
const invite = (over: Partial<ClassInvite>): ClassInvite => ({
  id: 'inv-1',
  codeHint: 'PaSf',
  createdAt: new Date().toISOString(),
  expiresAt: null,
  maxUses: null,
  usedCount: 0,
  status: 'ACTIVE',
  ...over,
});

let invites: ClassInvite[];
let sent: { method: string; url: string; body: any }[];
let rosterRequests: string[];
const expiredAt = new Date(Date.now() - 2 * DAY).toISOString();
const roster = [
  { id: 'cm-1', userId: 'u-1', role: 'STUDENT', state: 'ACTIVE', joinedAt: new Date().toISOString(), userFullName: 'Học viên Một', userEmail: 'a@test.local', isPro: false },
  { id: 'cm-2', userId: 'u-2', role: 'STUDENT', state: 'EXPIRED', joinedAt: new Date().toISOString(), accessExpiresAt: expiredAt, userFullName: 'Học viên Hết Hạn', userEmail: 'b@test.local', isPro: false },
];

const json = (data: unknown, status = 200) => new Response(JSON.stringify({ success: true, data }), { status });

function installFetch() {
  sent = [];
  rosterRequests = [];
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = input.toString();
    const method = (init?.method || 'GET').toUpperCase();
    sent.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    if (url.includes('/studio/members') && method === 'GET') {
      rosterRequests.push(url);
      const state = new URL(url, 'http://x').searchParams.get('state');
      const members = state ? roster.filter((m) => m.state === state) : roster;
      return json({ members, total: members.length, page: 0, size: 50, hasNext: false });
    }
    if (url.endsWith('/classes/class-1/invites') && method === 'GET') return json(invites);
    if (url.endsWith('/classes/class-1/invites') && method === 'POST') {
      const created = invite({ id: 'inv-new', code: FULL_CODE, codeHint: 'PaSf', ...sent[sent.length - 1].body, usedCount: 0 });
      invites = [invite({ ...created, code: undefined }), ...invites];
      return json(created);
    }
    const revoke = url.match(/\/classes\/class-1\/invites\/([^/]+)$/);
    if (revoke && method === 'DELETE') {
      invites = invites.map((i) => (i.id === revoke[1] ? { ...i, status: 'REVOKED' } : i));
      return json(invites.find((i) => i.id === revoke[1]));
    }
    return new Response('{}', { status: 404 });
  });
}

const section = () => screen.getByRole('region', { name: 'Mời thành viên' });

beforeEach(() => {
  vi.restoreAllMocks();
  currentClassroom = ownerClassroom;
  invites = [
    invite({ id: 'inv-1', codeHint: 'PaSf', usedCount: 3, maxUses: 50 }),
    invite({ id: 'inv-2', codeHint: 'Zz9q', status: 'REVOKED', usedCount: 1 }),
    invite({ id: 'inv-3', codeHint: 'abcd', status: 'EXPIRED', expiresAt: new Date(Date.now() - DAY).toISOString() }),
    invite({ id: 'inv-4', codeHint: 'wxyz', status: 'EXHAUSTED', usedCount: 5, maxUses: 5 }),
  ];
  installFetch();
});

afterEach(() => {
  vi.restoreAllMocks();
  Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
});

describe('StudioMembers - invite list', () => {
  it('lists every invite with a status badge, uses, expiry and only the last 4 characters of the code', async () => {
    render(<StudioMembers />);
    const table = await within(await screen.findByRole('region', { name: 'Mời thành viên' })).findByRole('table');

    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(4);
    expect(within(rows[0]).getByText('Đang hiệu lực')).toBeInTheDocument();
    expect(within(rows[0]).getByText('…PaSf')).toBeInTheDocument();
    expect(rows[0]).toHaveTextContent('3 / 50');
    expect(rows[0]).toHaveTextContent('Không hết hạn');
    expect(within(rows[1]).getByText('Đã thu hồi')).toBeInTheDocument();
    expect(rows[1]).toHaveTextContent('1 / không giới hạn');
    expect(within(rows[2]).getByText('Hết hạn')).toBeInTheDocument();
    expect(rows[2]).toHaveTextContent(formatDate(invites[2].expiresAt));
    expect(within(rows[3]).getByText('Hết lượt')).toBeInTheDocument();
    // a revoked invite cannot be revoked again; the others can
    expect(within(rows[1]).queryByRole('button', { name: /Thu hồi/ })).not.toBeInTheDocument();
    expect(within(rows[0]).getByRole('button', { name: 'Thu hồi liên kết mời …PaSf' })).toBeInTheDocument();
    // the server never returns a code in the list and the page never shows one
    expect(document.body.textContent).not.toContain(FULL_CODE);
  });

  it('explains that invites are optional for a PUBLIC class and the only way in for a PRIVATE one', async () => {
    const privateView = render(<StudioMembers />);
    expect(await screen.findByText(/liên kết mời là cách duy nhất/)).toBeInTheDocument();
    privateView.unmount();

    currentClassroom = { ...ownerClassroom, visibility: 'PUBLIC' } as Classroom;
    render(<StudioMembers />);
    expect(await screen.findByText(/chỉ là tùy chọn/)).toBeInTheDocument();
  });

  it('is offered to a MEMBER:EDIT delegate but not to staff who may only view members', async () => {
    currentClassroom = { ...ownerClassroom, userRole: 'STAFF', studioPermissions: ['MEMBER:VIEW', 'MEMBER:EDIT'] } as Classroom;
    const delegate = render(<StudioMembers />);
    expect(await screen.findByRole('region', { name: 'Mời thành viên' })).toBeInTheDocument();
    delegate.unmount();

    sent.length = 0;
    currentClassroom = { ...ownerClassroom, userRole: 'STAFF', studioPermissions: ['MEMBER:VIEW'] } as Classroom;
    render(<StudioMembers />);
    await screen.findByText('Học viên Một');
    expect(screen.queryByRole('region', { name: 'Mời thành viên' })).not.toBeInTheDocument();
    expect(sent.some((s) => s.url.includes('/invites'))).toBe(false);
  });
});

describe('StudioMembers - create an invite', () => {
  it('sends the chosen expiry and use limit, then shows the full link ONCE with a warning and a copy button', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    render(<StudioMembers />);
    await within(await screen.findByRole('region', { name: 'Mời thành viên' })).findByRole('table');

    fireEvent.change(within(section()).getByLabelText('Hạn dùng'), { target: { value: '7' } });
    fireEvent.change(within(section()).getByLabelText('Số lượt dùng tối đa'), { target: { value: '5' } });
    const before = Date.now();
    await act(async () => { fireEvent.click(within(section()).getByRole('button', { name: 'Tạo liên kết mời' })); });

    const post = sent.find((s) => s.method === 'POST' && s.url.endsWith('/invites'))!;
    expect(post.body.maxUses).toBe(5);
    expect(Date.parse(post.body.expiresAt)).toBeGreaterThan(before + 7 * DAY - 60_000);
    expect(Date.parse(post.body.expiresAt)).toBeLessThan(Date.now() + 7 * DAY + 60_000);

    const dialog = await screen.findByRole('dialog', { name: 'Liên kết mời đã được tạo' });
    const link = `${window.location.origin}/join/${FULL_CODE}`;
    expect(within(dialog).getByLabelText('Liên kết mời')).toHaveValue(link);
    expect(dialog).toHaveTextContent('Chỉ hiển thị một lần');

    await act(async () => { fireEvent.click(within(dialog).getByRole('button', { name: 'Sao chép liên kết' })); });
    expect(writeText).toHaveBeenCalledWith(link);
    expect(await within(dialog).findByText('Đã sao chép liên kết vào bộ nhớ tạm.')).toBeInTheDocument();

    // closing the dialog drops the code for good: the list only has the hint
    fireEvent.click(within(dialog).getByRole('button', { name: 'Đã lưu, đóng' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain(FULL_CODE);
    await waitFor(() => expect(within(section()).getAllByText('…PaSf').length).toBe(2));
  });

  it('puts keyboard focus back on "Tạo liên kết mời" when the link dialog closes (Escape or the close button)', async () => {
    render(<StudioMembers />);
    await within(await screen.findByRole('region', { name: 'Mời thành viên' })).findByRole('table');
    const create = within(section()).getByRole('button', { name: 'Tạo liên kết mời' });

    await act(async () => { fireEvent.click(create); });
    await screen.findByRole('dialog', { name: 'Liên kết mời đã được tạo' });
    await act(async () => { fireEvent.keyDown(document, { key: 'Escape' }); });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(create).toHaveFocus();
  });

  it('with no expiry and no limit the body is empty (never expires, unlimited)', async () => {
    render(<StudioMembers />);
    await within(await screen.findByRole('region', { name: 'Mời thành viên' })).findByRole('table');
    await act(async () => { fireEvent.click(within(section()).getByRole('button', { name: 'Tạo liên kết mời' })); });
    await screen.findByRole('dialog', { name: 'Liên kết mời đã được tạo' });
    expect(sent.find((s) => s.method === 'POST' && s.url.endsWith('/invites'))!.body).toEqual({});
  });

  it('falls back to select-and-execCommand where the Clipboard API is missing', async () => {
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
    const exec = vi.fn().mockReturnValue(true);
    (document as unknown as { execCommand: unknown }).execCommand = exec;
    try {
      render(<StudioMembers />);
      await within(await screen.findByRole('region', { name: 'Mời thành viên' })).findByRole('table');
      await act(async () => { fireEvent.click(within(section()).getByRole('button', { name: 'Tạo liên kết mời' })); });
      const dialog = await screen.findByRole('dialog', { name: 'Liên kết mời đã được tạo' });
      await act(async () => { fireEvent.click(within(dialog).getByRole('button', { name: 'Sao chép liên kết' })); });

      expect(exec).toHaveBeenCalledWith('copy');
      expect(await within(dialog).findByText('Đã sao chép liên kết vào bộ nhớ tạm.')).toBeInTheDocument();
    } finally {
      delete (document as unknown as { execCommand?: unknown }).execCommand;
    }
  });

  it('when nothing can copy it says so and leaves the link selected for Ctrl+C', async () => {
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) }, configurable: true });
    render(<StudioMembers />);
    await within(await screen.findByRole('region', { name: 'Mời thành viên' })).findByRole('table');
    await act(async () => { fireEvent.click(within(section()).getByRole('button', { name: 'Tạo liên kết mời' })); });
    const dialog = await screen.findByRole('dialog', { name: 'Liên kết mời đã được tạo' });
    await act(async () => { fireEvent.click(within(dialog).getByRole('button', { name: 'Sao chép liên kết' })); });
    expect(await within(dialog).findByText(/Không thể tự sao chép/)).toBeInTheDocument();
  });

  it('rejects a use limit outside 1..100000 before any request', async () => {
    render(<StudioMembers />);
    await within(await screen.findByRole('region', { name: 'Mời thành viên' })).findByRole('table');
    sent.length = 0;
    fireEvent.change(within(section()).getByLabelText('Số lượt dùng tối đa'), { target: { value: '0' } });
    await act(async () => { fireEvent.click(within(section()).getByRole('button', { name: 'Tạo liên kết mời' })); });

    expect(await within(section()).findByText(/Số lượt dùng phải là số nguyên từ 1 đến 100\.000/)).toBeInTheDocument();
    expect(sent.some((s) => s.method === 'POST')).toBe(false);
  });

  it('shows the server message when creating fails (e.g. an archived class) and opens no dialog', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      if (url.includes('/studio/members')) return json({ members: [], total: 0, page: 0, size: 50, hasNext: false });
      if (url.endsWith('/invites') && init?.method === 'POST') {
        return new Response(JSON.stringify({ success: false, error: { code: 'BAD_REQUEST', message: 'Lớp học đã được lưu trữ; không tạo mã mới' } }), { status: 400 });
      }
      return json([]);
    });
    render(<StudioMembers />);
    await screen.findByText('Chưa có liên kết mời nào.');
    await act(async () => { fireEvent.click(within(section()).getByRole('button', { name: 'Tạo liên kết mời' })); });
    expect(await within(section()).findByText('Lớp học đã được lưu trữ; không tạo mã mới')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('StudioMembers - revoke an invite', () => {
  it('asks for confirmation, DELETEs the invite and shows it as revoked', async () => {
    render(<StudioMembers />);
    await within(await screen.findByRole('region', { name: 'Mời thành viên' })).findByRole('table');

    fireEvent.click(screen.getByRole('button', { name: 'Thu hồi liên kết mời …PaSf' }));
    const dialog = await screen.findByRole('alertdialog', { name: 'Thu hồi liên kết mời?' });
    expect(dialog).toHaveTextContent('sẽ ngừng dùng được ngay');
    expect(sent.some((s) => s.method === 'DELETE')).toBe(false);

    await act(async () => { fireEvent.click(within(dialog).getByRole('button', { name: 'Thu hồi liên kết' })); });

    await waitFor(() => expect(sent.some((s) => s.method === 'DELETE' && s.url.endsWith('/classes/class-1/invites/inv-1'))).toBe(true));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
    const rows = within(await within(section()).findByRole('table')).getAllByRole('row').slice(1);
    expect(within(rows[0]).getByText('Đã thu hồi')).toBeInTheDocument();
    expect(within(rows[0]).queryByRole('button', { name: /Thu hồi/ })).not.toBeInTheDocument();
  });

  it('"Hủy" closes the confirmation without revoking', async () => {
    render(<StudioMembers />);
    await within(await screen.findByRole('region', { name: 'Mời thành viên' })).findByRole('table');
    fireEvent.click(screen.getByRole('button', { name: 'Thu hồi liên kết mời …PaSf' }));
    const dialog = await screen.findByRole('alertdialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Hủy' }));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(sent.some((s) => s.method === 'DELETE')).toBe(false);
  });
});

describe('StudioMembers - EXPIRED members', () => {
  it('offers an "Đã hết hạn" filter that asks the server for state=EXPIRED', async () => {
    render(<StudioMembers />);
    await screen.findByText('Học viên Một');

    const select = screen.getByLabelText('Lọc theo trạng thái');
    expect(within(select).getByRole('option', { name: 'Đã hết hạn' })).toHaveValue('EXPIRED');
    fireEvent.change(select, { target: { value: 'EXPIRED' } });

    await waitFor(() => expect(rosterRequests.some((u) => u.includes('state=EXPIRED'))).toBe(true));
    await waitFor(() => expect(screen.queryByText('Học viên Một')).not.toBeInTheDocument());
    expect(screen.getByText('Học viên Hết Hạn')).toBeInTheDocument();
  });

  it('badges the row and shows "Hết hạn dd/MM/yyyy"', async () => {
    render(<StudioMembers />);
    const row = (await screen.findByText('Học viên Hết Hạn')).closest('div.p-5') as HTMLElement;
    expect(within(row).getByText('EXPIRED')).toBeInTheDocument();
    expect(within(row).getByTestId('member-expiry')).toHaveTextContent(`Hết hạn ${formatDate(expiredAt)}`);
    // an ACTIVE member with no expiry shows no expiry line
    const active = screen.getByText('Học viên Một').closest('div.p-5') as HTMLElement;
    expect(within(active).queryByTestId('member-expiry')).not.toBeInTheDocument();
  });

  it('an EXPIRED member can be removed or blocked like an active one', async () => {
    render(<StudioMembers />);
    await screen.findByText('Học viên Hết Hạn');

    expect(screen.getByLabelText('Chặn Học viên Hết Hạn')).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('Xóa Học viên Hết Hạn khỏi lớp'));
    const dialog = await screen.findByRole('alertdialog', { name: 'Xác nhận thao tác' });
    await act(async () => { fireEvent.click(within(dialog).getByRole('button', { name: 'Xác nhận' })); });

    await waitFor(() => expect(sent.some((s) => s.method === 'POST' && s.url.endsWith('/studio/members/u-2/remove'))).toBe(true));
  });
});
