import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor, fireEvent, within } from '@testing-library/react';
import { EventDetailPage } from '../pages/classroom/EventDetailPage';
import { baseClassroom, fail, makeEvent, mockApi, ok, renderInClass } from './blogEventsHelpers';
import type { ClassEvent, Classroom } from '../types';

let mockUser: unknown = null;
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ user: mockUser }) }));

const member = { id: 'u1', fullName: 'Học Viên', email: 'hv@test.local', role: 'STUDENT', status: 'ACTIVE' };
const outsider: Classroom = { ...baseClassroom, isMember: false, userRole: 'GUEST', memberState: 'NONE' };

const renderPage = (event: ClassEvent, extra: Record<string, any> = {}, classroom: Classroom = baseClassroom, refresh = vi.fn().mockResolvedValue(undefined)) => {
  const calls = mockApi({
    'GET /events/ev-1': () => ok(event),
    'GET /classes/class-1/events?scope=upcoming': () => ok([event, makeEvent({ id: 'ev-next', title: 'Buổi kế tiếp' })]),
    ...extra,
  });
  renderInClass(<EventDetailPage />, { path: '/classes/demo-class/events/ev-1', pattern: '/classes/:slug/events/:eventId', classroom, refreshClassroom: refresh });
  return calls;
};

describe('EventDetailPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    mockUser = member;
  });

  it('shows the event details, host, who-for/takeaways and the next events; no meeting link when the server returns none', async () => {
    renderPage(makeEvent({ id: 'ev-1', title: 'Hỏi đáp tuần', takeaways: ['Lộ trình rõ ràng', 'Checklist'] }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Hỏi đáp tuần' })).toBeInTheDocument();
    expect(screen.getByText('Lộ trình rõ ràng')).toBeInTheDocument();
    expect(screen.getByText('Checklist')).toBeInTheDocument();
    expect(screen.getByText('Dẫn dắt bởi Chủ Lớp')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Vào phòng họp/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Tất cả sự kiện/ })).toHaveAttribute('href', '/classes/demo-class/events');
    expect(await screen.findByRole('link', { name: /Buổi kế tiếp/ })).toHaveAttribute('href', '/classes/demo-class/events/ev-next');
  });

  it('registers through the confirmation dialog, then shows the meeting link the server now returns', async () => {
    const event = makeEvent({ id: 'ev-1' });
    const calls = renderPage(event, {
      'POST /events/ev-1/registrations': () => ok({ ...event, isRegistered: true, registeredCount: 11, meetingUrl: 'https://meet.example/room' }),
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Đăng ký tham dự' }));
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Xác nhận đăng ký sự kiện' })).getByRole('button', { name: 'Đăng ký' }));

    expect(await screen.findByRole('link', { name: /Vào phòng họp/ })).toHaveAttribute('href', 'https://meet.example/room');
    expect(screen.getAllByText('Bạn đã đăng ký').length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: 'Hủy đăng ký' })).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST').map((c) => c.path)).toEqual(['/events/ev-1/registrations']);
  });

  it('cancels a registration (DELETE /registrations/me) after confirming and hides the meeting link again', async () => {
    const event = makeEvent({ id: 'ev-1', isRegistered: true, meetingUrl: 'https://meet.example/room' });
    const calls = renderPage(event, {
      'DELETE /events/ev-1/registrations/me': () => ok({ ...event, isRegistered: false, meetingUrl: null, registeredCount: 9 }),
    });
    expect(await screen.findByRole('link', { name: /Vào phòng họp/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Hủy đăng ký' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Xác nhận hủy đăng ký' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Hủy đăng ký' }));

    expect(await screen.findByRole('button', { name: 'Đăng ký tham dự' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Vào phòng họp/ })).not.toBeInTheDocument();
    expect(calls.some((c) => c.method === 'DELETE' && c.path === '/events/ev-1/registrations/me')).toBe(true);
  });

  it('cancelling still updates the page when the server answers only { id, isRegistered: false }', async () => {
    const event = makeEvent({ id: 'ev-1', isRegistered: true, meetingUrl: 'https://meet.example/room' });
    renderPage(event, {
      'DELETE /events/ev-1/registrations/me': () => ok({ id: 'ev-1', isRegistered: false }),
    });
    expect(await screen.findByRole('link', { name: /Vào phòng họp/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Hủy đăng ký' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Xác nhận hủy đăng ký' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Hủy đăng ký' }));

    expect(await screen.findByRole('button', { name: 'Đăng ký tham dự' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Vào phòng họp/ })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(event.title);
  });

  it('409 full: friendly message and the button turns into a disabled "Đã đủ chỗ"', async () => {
    const event = makeEvent({ id: 'ev-1' });
    let reads = 0;
    renderPage(event, {
      'GET /events/ev-1': () => { reads += 1; return ok(reads === 1 ? event : { ...event, isFull: true, registeredCount: 50 }); },
      'POST /events/ev-1/registrations': () => fail(409, 'CONFLICT', 'Sự kiện đã đủ chỗ'),
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Đăng ký tham dự' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Đăng ký' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Sự kiện đã đủ chỗ.');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Đã đủ chỗ' })).toBeDisabled());
    // The visible "full" state explains it; the 409 sentence stays announced but is not repeated in red.
    expect(screen.getByRole('alert')).toHaveClass('sr-only');
  });

  it('a manager who has not registered sees the meeting link as secondary next to the primary "Đăng ký tham dự"', async () => {
    renderPage(makeEvent({ id: 'ev-1', meetingUrl: 'https://meet.example/room' }), {}, { ...baseClassroom, userRole: 'OWNER', isOwner: true });
    const meeting = await screen.findByRole('link', { name: /Vào phòng họp/ });
    expect(meeting).toHaveAttribute('href', 'https://meet.example/room');
    expect(meeting.className).not.toContain('bg-blue-600');
    expect(screen.getByRole('button', { name: 'Đăng ký tham dự' })).toBeInTheDocument();
  });

  it('409 for an event that already ended reads as such', async () => {
    const event = makeEvent({ id: 'ev-1' });
    renderPage(event, { 'POST /events/ev-1/registrations': () => fail(409, 'CONFLICT', 'Sự kiện đã kết thúc') });
    fireEvent.click(await screen.findByRole('button', { name: 'Đăng ký tham dự' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Đăng ký' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Sự kiện đã kết thúc nên không nhận đăng ký nữa.');
  });

  it('a guest gets a sign-in link instead of the register button', async () => {
    mockUser = null;
    const calls = renderPage(makeEvent({ id: 'ev-1' }), {}, outsider);
    fireEvent.click(await screen.findByRole('link', { name: 'Đăng nhập để đăng ký' }));
    expect(await screen.findByText('Trang đăng nhập')).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('a signed-in non-member on a members-only event is offered to join the class first', async () => {
    const refresh = vi.fn().mockResolvedValue(undefined);
    const calls = renderPage(makeEvent({ id: 'ev-1', audience: 'MEMBERS' }), {
      'POST /classes/class-1/join': () => ok({ ...outsider, isMember: true }),
    }, outsider, refresh);
    expect(screen.queryByRole('button', { name: 'Đăng ký tham dự' })).not.toBeInTheDocument();
    fireEvent.click(await screen.findByRole('button', { name: 'Tham gia lớp để đăng ký' }));
    await waitFor(() => expect(refresh).toHaveBeenCalled());
    expect(calls.some((c) => c.method === 'POST' && c.path === '/classes/class-1/join')).toBe(true);
    expect(calls.some((c) => c.path.includes('/registrations'))).toBe(false);
  });

  it('a cancelled event shows the badge and no registration action', async () => {
    renderPage(makeEvent({ id: 'ev-1', status: 'CANCELLED' }));
    expect(await screen.findByText('Đã hủy')).toBeInTheDocument();
    expect(screen.getByText(/Sự kiện này đã bị hủy/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Đăng ký tham dự' })).not.toBeInTheDocument();
  });
});
