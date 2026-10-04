import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor, fireEvent, within } from '@testing-library/react';
import { EventsTab } from '../pages/classroom/EventsTab';
import { baseClassroom, fail, makeEvent, mockApi, ok, pastDays, renderInClass } from './blogEventsHelpers';

let mockUser: unknown = null;
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ user: mockUser }) }));

const member = { id: 'u1', fullName: 'Học Viên', email: 'hv@test.local', role: 'STUDENT', status: 'ACTIVE' };

const renderTab = (classroom = baseClassroom) =>
  renderInClass(<EventsTab />, { path: '/classes/demo-class/events', pattern: '/classes/:slug/events', classroom });

const mine = makeEvent({ id: 'ev-mine', title: 'Buổi tôi đăng ký', isRegistered: true, meetingUrl: 'https://meet.example/abc' });
const featured = makeEvent({ id: 'ev-feat', title: 'Buổi nổi bật' });
const second = makeEvent({ id: 'ev-2', title: 'Buổi thứ hai', takeaways: ['Khung kịch bản', 'Mẫu điền sẵn'], forWhom: 'Người mới làm' });
const cancelled = makeEvent({ id: 'ev-x', title: 'Buổi bị hủy', status: 'CANCELLED' });
const past = makeEvent({ id: 'ev-old', title: 'Buổi đã qua', startsAt: pastDays(10, 13), endsAt: pastDays(10, 14), registeredCount: 40 });

describe('EventsTab', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    mockUser = member;
  });

  it('lists registered events first, a featured banner, the upcoming grid with cancelled badge, and past events', async () => {
    mockApi({
      'GET /classes/class-1/events?scope=upcoming': () => ok([featured, mine, second, cancelled]),
      'GET /classes/class-1/events?scope=past': () => ok([past]),
    });
    renderTab();

    const mineSection = (await screen.findByRole('heading', { name: 'Bạn đã đăng ký' })).closest('section')!;
    expect(within(mineSection).getByRole('link', { name: 'Buổi tôi đăng ký' })).toHaveAttribute('href', '/classes/demo-class/events/ev-mine');
    expect(within(mineSection).getByRole('link', { name: /Vào phòng họp/ })).toHaveAttribute('href', 'https://meet.example/abc');

    const banner = screen.getByRole('region', { name: 'Sự kiện sắp tới nổi bật' });
    expect(within(banner).getByText('Buổi nổi bật')).toBeInTheDocument();
    expect(within(banner).getByRole('link', { name: 'Xem & đăng ký' })).toHaveAttribute('href', '/classes/demo-class/events/ev-feat');

    const upcoming = screen.getByRole('heading', { name: 'Sắp diễn ra' }).closest('section')!;
    const card = within(upcoming).getByRole('link', { name: 'Buổi thứ hai' }).closest('article')!;
    expect(within(card).getByText('Chủ Lớp')).toBeInTheDocument();
    expect(within(card).getByText('Người mới làm')).toBeInTheDocument();
    expect(within(card).getByText('Khung kịch bản')).toBeInTheDocument();
    expect(within(card).getByText('10/50 đã đăng ký')).toBeInTheDocument();
    const cancelledCard = within(upcoming).getByRole('link', { name: 'Buổi bị hủy' }).closest('article')!;
    expect(within(cancelledCard).getByText('Đã hủy')).toBeInTheDocument();
    expect(within(cancelledCard).queryByRole('button', { name: /Đăng ký/ })).not.toBeInTheDocument();

    const pastSection = screen.getByRole('heading', { name: 'Đã diễn ra' }).closest('section')!;
    expect(within(pastSection).getByText('Buổi đã qua')).toBeInTheDocument();
    // No countdowns anywhere (design rule).
    expect(screen.queryByText(/còn \d+ (ngày|giờ|phút)/i)).not.toBeInTheDocument();
  });

  it('registers after the confirmation dialog and flips the card to "Đã đăng ký"', async () => {
    const calls = mockApi({
      'GET /classes/class-1/events?scope=upcoming': () => ok([featured, second]),
      'GET /classes/class-1/events?scope=past': () => ok([]),
      'POST /events/ev-2/registrations': () => ok({ ...second, isRegistered: true, registeredCount: 11 }),
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Đăng ký Buổi thứ hai' }));
    const dialog = screen.getByRole('dialog', { name: 'Xác nhận đăng ký sự kiện' });
    expect(within(dialog).getByText('Buổi thứ hai')).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Đăng ký' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.filter((c) => c.method === 'POST').map((c) => c.path)).toEqual(['/events/ev-2/registrations']);
    // Now registered: it moves to "Bạn đã đăng ký".
    const mineSection = screen.getByRole('heading', { name: 'Bạn đã đăng ký' }).closest('section')!;
    expect(within(mineSection).getByRole('link', { name: 'Buổi thứ hai' })).toBeInTheDocument();
  });

  it('a 409 "full" answer shows a friendly message and re-reads the event', async () => {
    const calls = mockApi({
      'GET /classes/class-1/events?scope=upcoming': () => ok([featured, second]),
      'GET /classes/class-1/events?scope=past': () => ok([]),
      'POST /events/ev-2/registrations': () => fail(409, 'CONFLICT', 'Sự kiện đã đủ chỗ'),
      'GET /events/ev-2': () => ok({ ...second, isFull: true, registeredCount: 50 }),
    });
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: 'Đăng ký Buổi thứ hai' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Đăng ký' }));

    expect(await screen.findByText(/Sự kiện đã đủ chỗ\. Bạn có thể theo dõi/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Đã đủ chỗ' })).toBeDisabled());
    expect(calls.some((c) => c.method === 'GET' && c.path === '/events/ev-2')).toBe(true);
  });

  it('a guest is sent to sign in instead of registering', async () => {
    mockUser = null;
    const calls = mockApi({
      'GET /classes/class-1/events?scope=upcoming': () => ok([featured, second]),
      'GET /classes/class-1/events?scope=past': () => ok([]),
    });
    renderTab({ ...baseClassroom, isMember: false, userRole: 'GUEST' });
    fireEvent.click(await screen.findByRole('button', { name: 'Đăng ký Buổi thứ hai' }));
    expect(await screen.findByText('Trang đăng nhập')).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('empty: managers get "Tạo sự kiện đầu tiên" into the Studio', async () => {
    mockApi({
      'GET /classes/class-1/events?scope=upcoming': () => ok([]),
      'GET /classes/class-1/events?scope=past': () => ok([]),
    });
    renderTab({ ...baseClassroom, userRole: 'OWNER', isOwner: true });
    expect(await screen.findByRole('link', { name: 'Tạo sự kiện đầu tiên' })).toHaveAttribute('href', '/studio/classes/class-1/events');
  });
});
