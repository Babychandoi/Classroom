import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, within, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MyEventsPage } from '../pages/MyEventsPage';
import { fail, makeEvent, mockApi, ok, pastDays } from './blogEventsHelpers';

const ev = (id: string, over = {}) => makeEvent({ id, title: `Buổi ${id}`, isRegistered: true, classTitle: 'Lớp Demo', classSlug: 'demo', ...over });
const renderPage = () => render(<MemoryRouter><MyEventsPage /></MemoryRouter>);

describe('MyEventsPage', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('lists upcoming events with class link, 24h time, meeting link only when present, cancelled badge and detail link', async () => {
    const calls = mockApi({
      'GET /me/events': () => ok([
        ev('1', { meetingUrl: 'https://meet.example/x' }),
        ev('2', { status: 'CANCELLED' }),
      ]),
    });
    renderPage();
    expect(screen.getByRole('status')).toBeInTheDocument();

    const one = (await screen.findByRole('link', { name: 'Buổi 1' })).closest('article')!;
    expect(calls[0].path).toBe('/me/events?scope=upcoming&page=0&size=20');
    expect(within(one).getByRole('link', { name: 'Lớp Demo' })).toHaveAttribute('href', '/classes/demo/feed');
    expect(within(one).getByText(/13:00–14:00/)).toBeInTheDocument();
    expect(within(one).getByRole('link', { name: /Vào phòng họp/ })).toHaveAttribute('href', 'https://meet.example/x');
    expect(within(one).getByRole('link', { name: 'Xem chi tiết Buổi 1' })).toHaveAttribute('href', '/classes/demo/events/1');

    const two = screen.getByRole('link', { name: 'Buổi 2' }).closest('article')!;
    expect(within(two).getByText('Đã hủy')).toBeInTheDocument();
    expect(within(two).queryByRole('link', { name: /Vào phòng họp/ })).not.toBeInTheDocument();
    expect(within(two).queryByRole('button', { name: /Hủy đăng ký/ })).not.toBeInTheDocument();
  });

  it('switches scope tabs and reloads; past events have no cancel action', async () => {
    const calls = mockApi({
      'GET /me/events?scope=upcoming': () => ok([ev('1')]),
      'GET /me/events?scope=past': () => ok([ev('old', { startsAt: pastDays(5, 9), endsAt: pastDays(5, 10) })]),
      'GET /me/events?scope=all': () => ok([ev('1'), ev('old', { startsAt: pastDays(5, 9), endsAt: pastDays(5, 10) })]),
    });
    renderPage();
    await screen.findByRole('link', { name: 'Buổi 1' });
    fireEvent.click(screen.getByRole('button', { name: 'Đã qua' }));
    const old = (await screen.findByRole('link', { name: 'Buổi old' })).closest('article')!;
    expect(screen.queryByRole('link', { name: 'Buổi 1' })).not.toBeInTheDocument();
    expect(within(old).queryByRole('button', { name: /Hủy đăng ký/ })).not.toBeInTheDocument();
    expect(calls[calls.length - 1].path).toBe('/me/events?scope=past&page=0&size=20');
    fireEvent.click(screen.getByRole('button', { name: 'Tất cả' }));
    await waitFor(() => expect(screen.getByRole('link', { name: 'Buổi 1' })).toBeInTheDocument());
    expect(screen.getByRole('link', { name: 'Buổi old' })).toBeInTheDocument();
  });

  it('cancels a registration through the confirm dialog and drops the event', async () => {
    const calls = mockApi({
      'GET /me/events': () => ok([ev('1'), ev('2')]),
      'DELETE /events/1/registrations': () => ok({ ...ev('1'), isRegistered: false }),
    });
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Hủy đăng ký Buổi 1' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Xác nhận hủy đăng ký' });
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Hủy đăng ký' }));
    await waitFor(() => expect(screen.queryByRole('link', { name: 'Buổi 1' })).not.toBeInTheDocument());
    expect(screen.getByRole('link', { name: 'Buổi 2' })).toBeInTheDocument();
  });

  it('keeps the event and shows the reason when cancelling fails', async () => {
    mockApi({
      'GET /me/events': () => ok([ev('1')]),
      'DELETE /events/1/registrations': () => fail(409, 'CONFLICT', 'Sự kiện đã kết thúc'),
    });
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Hủy đăng ký Buổi 1' }));
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Hủy đăng ký' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Sự kiện đã kết thúc');
    expect(screen.getByRole('link', { name: 'Buổi 1' })).toBeInTheDocument();
  });

  it('pages with "Xem thêm sự kiện"', async () => {
    mockApi({
      'GET /me/events?scope=upcoming&page=0': () => ok(Array.from({ length: 20 }, (_, i) => ev(`p${i}`))),
      'GET /me/events?scope=upcoming&page=1': () => ok([ev('z')]),
    });
    renderPage();
    await screen.findByRole('link', { name: 'Buổi p0' });
    fireEvent.click(screen.getByRole('button', { name: 'Xem thêm sự kiện' }));
    expect(await screen.findByRole('link', { name: 'Buổi z' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Xem thêm sự kiện' })).not.toBeInTheDocument();
  });

  it('shows an empty state per scope and an error with retry', async () => {
    let fails = true;
    mockApi({ 'GET /me/events': () => (fails ? fail(500, 'INTERNAL', 'Lỗi tải') : ok([])) });
    renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('Lỗi tải');
    fails = false;
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByText('Bạn chưa đăng ký sự kiện nào sắp tới')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Xem lớp học của tôi' })).toHaveAttribute('href', '/me/classes');
    fireEvent.click(screen.getByRole('button', { name: 'Đã qua' }));
    expect(await screen.findByText('Chưa có sự kiện đã qua')).toBeInTheDocument();
  });
});
