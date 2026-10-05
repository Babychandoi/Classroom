import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MyClassesPage } from '../pages/MyClassesPage';
import { baseClassroom, fail, mockApi, ok } from './blogEventsHelpers';
import type { Classroom } from '../types';

const make = (id: string, over: Partial<Classroom> = {}): Classroom => ({
  ...baseClassroom, id, slug: `lop-${id}`, title: `Lớp ${id}`, ...over,
});
const renderPage = () => render(<MemoryRouter><MyClassesPage /></MemoryRouter>);

const owned = make('a', { isOwner: true, userRole: 'OWNER', upcomingEventCount: 2, category: 'Ngoại ngữ' });
const staff = make('b', { userRole: 'STAFF' });
const member = make('c', { userRole: 'MEMBER' });
const priv = make('d', { visibility: 'PRIVATE', userRole: 'MEMBER' });
const expired = make('e', { memberState: 'EXPIRED', isMember: false, userRole: 'GUEST', accessType: 'PAID', accessExpiresAt: '2026-09-01T00:00:00Z' });
const pending = make('f', { memberState: 'PENDING', isMember: false, userRole: 'GUEST' });

describe('MyClassesPage', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('shows a loading state, then a card per class with role, state chips and Studio links for leaders only', async () => {
    mockApi({ 'GET /me/classes': () => ok([owned, staff, member, priv, expired, pending]) });
    renderPage();
    expect(screen.getByRole('status')).toHaveTextContent('Đang tải');

    const first = (await screen.findByRole('heading', { name: 'Lớp a' })).closest('article')!;
    expect(within(first).getByText('Chủ lớp')).toBeInTheDocument();
    expect(within(first).getByTestId('card-category')).toHaveTextContent('Ngoại ngữ');
    expect(within(first).getByText('2 sự kiện sắp tới')).toBeInTheDocument();
    expect(within(first).getByRole('link', { name: 'Studio của Lớp a' })).toHaveAttribute('href', '/studio/classes/a');
    expect(within(first).getByRole('link', { name: 'Vào lớp' })).toHaveAttribute('href', '/classes/lop-a/feed');

    const b = screen.getByRole('heading', { name: 'Lớp b' }).closest('article')!;
    expect(within(b).getByText('Trợ giảng')).toBeInTheDocument();
    expect(within(b).getByRole('link', { name: /Studio/ })).toBeInTheDocument();

    const c = screen.getByRole('heading', { name: 'Lớp c' }).closest('article')!;
    expect(within(c).getByText('Thành viên')).toBeInTheDocument();
    expect(within(c).queryByRole('link', { name: /Studio/ })).not.toBeInTheDocument();

    const d = screen.getByRole('heading', { name: 'Lớp d' }).closest('article')!;
    expect(within(d).getByText('Riêng tư')).toBeInTheDocument();

    const e = screen.getByRole('heading', { name: 'Lớp e' }).closest('article')!;
    expect(within(e).getByText('Hết hạn')).toBeInTheDocument();
    expect(within(e).getByText(/hết hạn ngày 01\/09\/2026/)).toBeInTheDocument();
    expect(within(e).getByRole('link', { name: 'Gia hạn' })).toHaveAttribute('href', '/classes/lop-e/feed');

    const f = screen.getByRole('heading', { name: 'Lớp f' }).closest('article')!;
    expect(within(f).getByText('Chờ duyệt')).toBeInTheDocument();
    expect(within(f).queryByText('Thành viên')).not.toBeInTheDocument();
  });

  it('filters by Tôi dẫn dắt / Tôi tham gia / Riêng tư and offers "Xem tất cả" when a filter is empty', async () => {
    mockApi({ 'GET /me/classes': () => ok([owned, staff, member, priv]) });
    renderPage();
    await screen.findByRole('heading', { name: 'Lớp a' });

    fireEvent.click(screen.getByRole('button', { name: 'Tôi dẫn dắt' }));
    expect(screen.getAllByRole('article').map((a) => within(a).getByRole('heading').textContent)).toEqual(['Lớp a', 'Lớp b']);
    fireEvent.click(screen.getByRole('button', { name: 'Tôi tham gia' }));
    expect(screen.getAllByRole('article').map((a) => within(a).getByRole('heading').textContent)).toEqual(['Lớp c', 'Lớp d']);
    fireEvent.click(screen.getByRole('button', { name: 'Riêng tư' }));
    expect(screen.getAllByRole('article').map((a) => within(a).getByRole('heading').textContent)).toEqual(['Lớp d']);
    expect(screen.getByRole('button', { name: 'Riêng tư' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('shows the filter empty state with a way back', async () => {
    mockApi({ 'GET /me/classes': () => ok([member]) });
    renderPage();
    await screen.findByRole('heading', { name: 'Lớp c' });
    fireEvent.click(screen.getByRole('button', { name: 'Tôi dẫn dắt' }));
    expect(screen.getByText('Chưa có lớp nào trong mục này')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Xem tất cả' }));
    expect(screen.getByRole('heading', { name: 'Lớp c' })).toBeInTheDocument();
  });

  it('pages with "Xem thêm lớp học" until a short page arrives', async () => {
    const full = Array.from({ length: 20 }, (_, i) => make(`p${i}`));
    const calls = mockApi({
      'GET /me/classes?page=0': () => ok(full),
      'GET /me/classes?page=1': () => ok([make('last')]),
    });
    renderPage();
    await screen.findByRole('heading', { name: 'Lớp p0' });
    expect(calls[0].path).toBe('/me/classes?page=0&size=20');
    fireEvent.click(screen.getByRole('button', { name: 'Xem thêm lớp học' }));
    expect(await screen.findByRole('heading', { name: 'Lớp last' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Lớp p0' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Xem thêm lớp học' })).not.toBeInTheDocument();
  });

  it('keeps the list and reports a failed "Xem thêm"', async () => {
    mockApi({
      'GET /me/classes?page=0': () => ok(Array.from({ length: 20 }, (_, i) => make(`p${i}`))),
      'GET /me/classes?page=1': () => fail(500, 'INTERNAL', 'Máy chủ bận'),
    });
    renderPage();
    await screen.findByRole('heading', { name: 'Lớp p0' });
    fireEvent.click(screen.getByRole('button', { name: 'Xem thêm lớp học' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Máy chủ bận');
    expect(screen.getByRole('heading', { name: 'Lớp p0' })).toBeInTheDocument();
  });

  it('shows the empty state with Khám phá lớp học and Tạo lớp học', async () => {
    mockApi({ 'GET /me/classes': () => ok([]) });
    renderPage();
    expect(await screen.findByText('Bạn chưa tham gia lớp học nào')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Khám phá lớp học' })).toHaveAttribute('href', '/classes');
    const create = screen.getAllByRole('link', { name: /Tạo lớp học/ });
    expect(create.every((l) => l.getAttribute('href') === '/classes/new')).toBe(true);
    expect(screen.queryByRole('button', { name: 'Tất cả' })).not.toBeInTheDocument();
  });

  it('shows an error with a retry that reloads', async () => {
    let fails = true;
    mockApi({ 'GET /me/classes': () => (fails ? fail(500, 'INTERNAL', 'Lỗi tải') : ok([member])) });
    renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('Lỗi tải');
    fails = false;
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByRole('heading', { name: 'Lớp c' })).toBeInTheDocument();
  });
});
