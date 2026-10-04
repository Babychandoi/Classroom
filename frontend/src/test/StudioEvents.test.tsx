import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor, fireEvent, within } from '@testing-library/react';
import { StudioEvents } from '../pages/studio/StudioEvents';
import { fromDatetimeLocalValue } from '../api/datetime';
import { baseClassroom, makeEvent, mockApi, ok, renderInClass } from './blogEventsHelpers';
import type { Classroom } from '../types';

vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ user: { id: 'owner-1', fullName: 'Chủ Lớp' } }) }));

const owner: Classroom = { ...baseClassroom, userRole: 'OWNER', isOwner: true };
const ev = makeEvent({ id: 'ev-1', title: 'Hỏi đáp tuần', meetingUrl: 'https://meet.example/x' });

const renderStudio = (classroom: Classroom) =>
  renderInClass(<StudioEvents />, { path: '/studio/classes/class-1/events', pattern: '/studio/classes/:id/events', classroom });

const fill = (dialog: HTMLElement, label: string, value: string) => fireEvent.change(within(dialog).getByLabelText(label), { target: { value } });

describe('StudioEvents', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('EVENT:VIEW only: sees the list and the registrants, but cannot create / edit / cancel / delete', async () => {
    mockApi({
      'GET /classes/class-1/events?scope=upcoming': () => ok([ev]),
      'GET /events/ev-1/registrations': () => ok([{ user: { id: 'u9', fullName: 'Lan Hương', avatarUrl: null }, registeredAt: '2026-07-01T05:00:00Z' }]),
    });
    renderStudio({ ...baseClassroom, userRole: 'STAFF', studioPermissions: ['EVENT:VIEW'] });
    expect(await screen.findByText('Hỏi đáp tuần')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Tạo sự kiện/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Sửa sự kiện/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Hủy sự kiện/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Xóa sự kiện/ })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Người đăng ký Hỏi đáp tuần' }));
    const dialog = screen.getByRole('dialog', { name: 'Người đăng ký' });
    expect(await within(dialog).findByText('Lan Hương')).toBeInTheDocument();
  });

  it('switches to past events with scope=past', async () => {
    const calls = mockApi({
      'GET /classes/class-1/events?scope=upcoming': () => ok([]),
      'GET /classes/class-1/events?scope=past': () => ok([makeEvent({ id: 'old', title: 'Buổi cũ' })]),
    });
    renderStudio(owner);
    await screen.findByText('Chưa có sự kiện sắp tới');
    fireEvent.click(screen.getByRole('button', { name: 'Đã qua' }));
    expect(await screen.findByText('Buổi cũ')).toBeInTheDocument();
    expect(calls.map((c) => c.path)).toContain('/classes/class-1/events?scope=past');
  });

  it('creates an event with the contract payload (UTC instants, takeaways, capacity, cover purpose EVENT)', async () => {
    const calls = mockApi({
      'GET /classes/class-1/events?scope=upcoming': () => ok([]),
      'POST /classes/class-1/media/upload-intents': () => ok({ assetId: 'cov-1', uploadUrl: 'https://store.local/put/cov-1' }),
      'PUT https://store.local/put/cov-1': () => new Response(null, { status: 200 }),
      'POST /media/cov-1/complete': () => ok({}),
      'POST /classes/class-1/events': () => ok(ev),
    });
    renderStudio(owner);
    fireEvent.click(await screen.findByRole('button', { name: /Tạo sự kiện$/ }));
    const dialog = screen.getByRole('dialog', { name: 'Tạo sự kiện' });

    fill(dialog, 'Tên sự kiện', 'Workshop kịch bản');
    fill(dialog, 'Mô tả', 'Cùng viết kịch bản');
    fill(dialog, 'Dành cho ai', 'Người mới');
    fill(dialog, 'Điều mang về 1', 'Ba khung kịch bản');
    fireEvent.click(within(dialog).getByRole('button', { name: /Thêm một điều/ }));
    fill(dialog, 'Điều mang về 2', 'Mẫu điền sẵn');
    fill(dialog, 'Hình thức', 'OFFLINE');
    fill(dialog, 'Địa điểm', '12 Lý Thường Kiệt');
    fill(dialog, 'Link phòng họp', 'https://meet.example/room');
    fill(dialog, 'Bắt đầu', '2026-11-12T20:00');
    fill(dialog, 'Kết thúc', '2026-11-12T21:30');
    fill(dialog, 'Số chỗ', '40');
    fill(dialog, 'Ai được đăng ký', 'PUBLIC');
    fireEvent.change(within(dialog).getByLabelText('Tải ảnh bìa'), { target: { files: [new File(['x'], 'bia.jpg', { type: 'image/jpeg' })] } });
    await waitFor(() => expect(within(dialog).getByText('Đổi ảnh bìa')).toBeInTheDocument());

    fireEvent.click(within(dialog).getByRole('button', { name: 'Tạo sự kiện' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    expect(calls.find((c) => c.path.endsWith('/upload-intents'))!.body.purpose).toBe('EVENT');
    const create = calls.find((c) => c.method === 'POST' && c.path === '/classes/class-1/events')!;
    expect(create.body).toEqual({
      title: 'Workshop kịch bản',
      description: 'Cùng viết kịch bản',
      forWhom: 'Người mới',
      takeaways: ['Ba khung kịch bản', 'Mẫu điền sẵn'],
      format: 'OFFLINE',
      location: '12 Lý Thường Kiệt',
      meetingUrl: 'https://meet.example/room',
      startsAt: fromDatetimeLocalValue('2026-11-12T20:00'),
      endsAt: fromDatetimeLocalValue('2026-11-12T21:30'),
      capacity: 40,
      audience: 'PUBLIC',
      coverMediaId: 'cov-1',
    });
  });

  it('validates the schedule and the meeting link before sending', async () => {
    const calls = mockApi({ 'GET /classes/class-1/events?scope=upcoming': () => ok([]) });
    renderStudio(owner);
    fireEvent.click(await screen.findByRole('button', { name: /Tạo sự kiện$/ }));
    const dialog = screen.getByRole('dialog', { name: 'Tạo sự kiện' });
    fill(dialog, 'Tên sự kiện', 'Buổi lỗi');
    fill(dialog, 'Bắt đầu', '2026-11-12T20:00');
    fill(dialog, 'Kết thúc', '2026-11-12T19:00');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Tạo sự kiện' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Thời gian kết thúc phải sau thời gian bắt đầu.');

    fill(dialog, 'Kết thúc', '2026-11-12T21:00');
    fill(dialog, 'Link phòng họp', 'javascript:alert(1)');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Tạo sự kiện' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Link phòng họp phải bắt đầu bằng http');
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('caps the takeaways editor at 8 items', async () => {
    mockApi({ 'GET /classes/class-1/events?scope=upcoming': () => ok([]) });
    renderStudio(owner);
    fireEvent.click(await screen.findByRole('button', { name: /Tạo sự kiện$/ }));
    const dialog = screen.getByRole('dialog', { name: 'Tạo sự kiện' });
    const add = within(dialog).getByRole('button', { name: /Thêm một điều/ });
    for (let i = 0; i < 10; i++) fireEvent.click(add);
    expect(within(dialog).getAllByLabelText(/^Điều mang về \d+$/)).toHaveLength(8);
    expect(add).toBeDisabled();
  });

  it('edits with the existing values pre-filled (local wall clock) and PUTs them', async () => {
    const calls = mockApi({
      'GET /classes/class-1/events?scope=upcoming': () => ok([ev]),
      'PUT /events/ev-1': () => ok(ev),
    });
    renderStudio(owner);
    fireEvent.click(await screen.findByRole('button', { name: 'Sửa sự kiện Hỏi đáp tuần' }));
    const dialog = screen.getByRole('dialog', { name: 'Sửa sự kiện' });
    expect(within(dialog).getByLabelText('Link phòng họp')).toHaveValue('https://meet.example/x');
    fill(dialog, 'Tên sự kiện', 'Hỏi đáp tuần (đổi giờ)');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Lưu thay đổi' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true));
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.path).toBe('/events/ev-1');
    expect(put.body.title).toBe('Hỏi đáp tuần (đổi giờ)');
    // An untouched schedule round-trips exactly (R16-04).
    expect(put.body.startsAt).toBe(new Date(ev.startsAt).toISOString());
    expect(put.body.endsAt).toBe(new Date(ev.endsAt).toISOString());
    expect(put.body.capacity).toBe(50);
  });

  it('cancels and deletes only after confirmation', async () => {
    const calls = mockApi({
      'GET /classes/class-1/events?scope=upcoming': () => ok([ev]),
      'POST /events/ev-1/cancel': () => ok({ ...ev, status: 'CANCELLED' }),
      'DELETE /events/ev-1': () => ok({}),
    });
    renderStudio(owner);
    fireEvent.click(await screen.findByRole('button', { name: 'Hủy sự kiện Hỏi đáp tuần' }));
    fireEvent.click(within(screen.getByRole('alertdialog', { name: 'Hủy sự kiện?' })).getByRole('button', { name: 'Hủy sự kiện' }));
    expect(await screen.findByText('Đã hủy')).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'POST' && c.path === '/events/ev-1/cancel')).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: 'Xóa sự kiện Hỏi đáp tuần' }));
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);
    fireEvent.click(within(screen.getByRole('alertdialog', { name: 'Xóa sự kiện?' })).getByRole('button', { name: 'Xóa sự kiện' }));
    await waitFor(() => expect(screen.queryByText('Hỏi đáp tuần')).not.toBeInTheDocument());
    expect(calls.some((c) => c.method === 'DELETE' && c.path === '/events/ev-1')).toBe(true);
  });
});
