import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { ClassesPage, CLASSES_PAGE_SIZE } from '../pages/ClassesPage';
import type { Classroom, ClassEvent } from '../types';

/**
 * Home page (Connecty "Trang chủ"): hero search that asks the server with `q` (debounced), curated rails
 * ("Lớp của bạn" / "Phổ biến" / "Mới mở", <= 6 cards each), CMP-1 class cards, and a "Sự kiện sắp tới" rail fed by
 * GET /events/upcoming that hides itself when the call fails or returns nothing.
 */

vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
  Link: ({ children, to }: { children?: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

let authUser: { id: string; fullName: string } | null = null;
vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: authUser, isLoading: false }),
}));

const make = (id: string, over: Partial<Classroom> = {}): Classroom =>
  ({
    id,
    ownerId: 'owner-1',
    ownerName: 'Cô Lan',
    slug: id,
    title: `Lớp ${id}`,
    description: `Mô tả ${id}`,
    status: 'ACTIVE',
    memberCount: 3,
    visibility: 'PUBLIC',
    accessType: 'FREE',
    createdAt: '2026-01-01T00:00:00Z',
    ...over,
  }) as Classroom;

const event = (id: string, over: Partial<ClassEvent> = {}): ClassEvent => ({
  id,
  classId: 'c1',
  classTitle: 'Lớp Toán',
  classSlug: 'lop-toan',
  title: `Sự kiện ${id}`,
  takeaways: [],
  format: 'ONLINE',
  location: 'Zoom',
  startsAt: '2026-10-09T13:00:00Z',
  endsAt: '2026-10-09T14:30:00Z',
  registeredCount: 12,
  isRegistered: false,
  isFull: false,
  host: { id: 'h1', fullName: 'Thầy Minh' },
  audience: 'PUBLIC',
  status: 'SCHEDULED',
  createdAt: '2026-09-01T00:00:00Z',
  ...over,
});

const ok = (data: unknown) => new Response(JSON.stringify({ success: true, data }), { status: 200 });

type Routes = {
  catalog?: (url: URL) => Classroom[];
  popular?: Classroom[] | 'fail';
  newest?: Classroom[] | 'fail';
  events?: ClassEvent[] | 'fail';
};

function install(routes: Routes) {
  const requested: string[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const raw = input.toString();
    requested.push(raw);
    const url = new URL(raw, 'http://localhost');
    if (url.pathname.endsWith('/events/upcoming')) {
      return routes.events === 'fail' || !routes.events ? new Response('{}', { status: 500 }) : ok(routes.events);
    }
    if (url.pathname.endsWith('/classes')) {
      const sort = url.searchParams.get('sort');
      if (sort === 'popular' || sort === 'newest') {
        const rail = routes[sort];
        return rail === 'fail' ? new Response('{}', { status: 500 }) : ok(rail ?? []);
      }
      return ok(routes.catalog ? routes.catalog(url) : []);
    }
    return new Response('{}', { status: 404 });
  });
  return requested;
}

const section = (name: string) => screen.getByRole('region', { name });

describe('ClassesPage home', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    authUser = null;
  });

  it('builds CMP-1 cards: cover tile, title, value line, owner, members, upcoming events and a fee badge + one CTA', async () => {
    install({
      catalog: () => [make('a', { memberCount: 2340, upcomingEventCount: 2, accessType: 'PAID', accessProduct: { id: 'p', price: 199000, currency: 'VND', durationDays: 30, lifetime: false } })],
    });
    render(<ClassesPage />);

    const card = (await screen.findByRole('heading', { name: 'Lớp a' })).closest('article') as HTMLElement;
    expect(within(card).getByText('Mô tả a')).toBeInTheDocument();
    expect(card).toHaveTextContent('Dẫn dắt bởi Cô Lan');
    expect(within(card).getByText('2.340 thành viên')).toBeInTheDocument();
    expect(within(card).getByText('2 sự kiện sắp tới')).toBeInTheDocument();
    expect(within(card).getByText('Trả phí · 199.000đ / 30 ngày')).toBeInTheDocument();
    const links = within(card).getAllByRole('link');
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveTextContent('Xem lớp');
    expect(links[0]).toHaveAttribute('href', '/classes/a/feed');
  });

  it('prefers the presigned coverUrl over coverImageUrl', async () => {
    install({ catalog: () => [make('a', { coverUrl: 'https://cdn.test/presigned.png', coverImageUrl: 'https://cdn.test/old.png' })] });
    render(<ClassesPage />);
    const card = (await screen.findByRole('heading', { name: 'Lớp a' })).closest('article') as HTMLElement;
    expect(within(card).getByAltText('Ảnh bìa lớp Lớp a')).toHaveAttribute('src', 'https://cdn.test/presigned.png');
  });

  it('renders the class avatar and cover with their saved positions, and the category', async () => {
    install({
      catalog: () => [make('a', {
        coverUrl: 'https://cdn.test/cover.png', avatarUrl: 'https://cdn.test/avatar.png',
        coverPosition: '30% 70%', avatarPosition: '10% 20%', category: 'Ăn chay',
      } as Partial<Classroom>), make('b', { coverPosition: 'center; background:red' } as Partial<Classroom>)],
    });
    render(<ClassesPage />);
    const card = (await screen.findByRole('heading', { name: 'Lớp a' })).closest('article') as HTMLElement;
    expect(within(card).getByAltText('Ảnh bìa lớp Lớp a').style.objectPosition).toBe('30% 70%');
    const avatar = card.querySelector('img[src="https://cdn.test/avatar.png"]') as HTMLImageElement;
    expect(avatar.style.objectPosition).toBe('10% 20%');
    expect(within(card).getByTestId('card-category')).toHaveTextContent('Ăn chay');
    const other = (await screen.findByRole('heading', { name: 'Lớp b' })).closest('article') as HTMLElement;
    expect(within(other).queryByTestId('card-category')).not.toBeInTheDocument();
  });

  it('shows "Lớp của bạn" to a signed-in member (with "Vào lớp") and the popular rail in member-count order', async () => {
    authUser = { id: 'u1', fullName: 'Nguyễn Minh' };
    const mine = make('mine', { isMember: true, memberCount: 1 });
    const big = make('big', { memberCount: 900 });
    const small = make('small', { memberCount: 5 });
    install({ catalog: () => [mine, big, small], popular: [small, big, mine], newest: [mine, big, small] });
    render(<ClassesPage />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Chào Nguyễn Minh, hôm nay bạn học gì?' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('region', { name: 'Lớp của bạn' })).toBeInTheDocument());
    const mineRail = section('Lớp của bạn');
    expect(within(mineRail).getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(['Lớp mine']);
    expect(within(mineRail).getByRole('link', { name: 'Vào lớp' })).toHaveAttribute('href', '/classes/mine/feed');

    await waitFor(() =>
      expect(within(section('Phổ biến')).getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(['Lớp big', 'Lớp small', 'Lớp mine']),
    );
    // "Mới mở" would repeat exactly the same cards, so it is left out.
    expect(screen.queryByRole('region', { name: 'Mới mở' })).not.toBeInTheDocument();
  });

  it('caps every rail at 6 cards and asks the server for the sorted rails', async () => {
    const many = Array.from({ length: 9 }, (_, i) => make(`c${i}`, { memberCount: i, createdAt: `2026-01-0${9 - i}T00:00:00Z` }));
    const requested = install({ catalog: () => many, popular: many, newest: [...many].reverse() });
    render(<ClassesPage />);

    await waitFor(() => expect(within(section('Phổ biến')).getAllByRole('article')).toHaveLength(6));
    await waitFor(() => expect(within(section('Mới mở')).getAllByRole('article')).toHaveLength(6));
    expect(requested.some((u) => u.includes('sort=popular') && u.includes('size=6'))).toBe(true);
    expect(requested.some((u) => u.includes('sort=newest') && u.includes('size=6'))).toBe(true);
    // a guest has no "Lớp của bạn"
    expect(screen.queryByRole('region', { name: 'Lớp của bạn' })).not.toBeInTheDocument();
  });

  it('searches with the q param after a short pause and shows the results in place of the rails', async () => {
    const requested = install({
      catalog: (url) => (url.searchParams.get('q') === 'toán' ? [make('toan', { title: 'Toán nâng cao' })] : [make('a'), make('b')]),
    });
    render(<ClassesPage />);
    await screen.findByRole('region', { name: 'Phổ biến' });

    fireEvent.change(screen.getByRole('searchbox', { name: 'Tìm lớp học' }), { target: { value: '  toán ' } });
    expect(await screen.findByRole('heading', { name: 'Kết quả cho “toán”' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Toán nâng cao' })).toBeInTheDocument());
    const searchCall = requested.find((u) => u.includes('q='));
    expect(searchCall).toContain(`page=0&size=${CLASSES_PAGE_SIZE}&q=${encodeURIComponent('toán')}`);
    expect(screen.queryByRole('region', { name: 'Phổ biến' })).not.toBeInTheDocument();

    // "Về trang chủ" clears the search and brings the rails back.
    fireEvent.click(screen.getByRole('button', { name: 'Về trang chủ' }));
    expect(await screen.findByRole('region', { name: 'Phổ biến' })).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Tìm lớp học' })).toHaveValue('');
  });

  it('says what to do when a search finds nothing', async () => {
    install({ catalog: (url) => (url.searchParams.get('q') ? [] : [make('a')]) });
    render(<ClassesPage />);
    await screen.findByRole('region', { name: 'Phổ biến' });

    fireEvent.change(screen.getByRole('searchbox', { name: 'Tìm lớp học' }), { target: { value: 'xyz' } });
    fireEvent.submit(screen.getByRole('search'));
    expect(await screen.findByText('Không tìm thấy lớp phù hợp')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Xóa tìm kiếm' }));
    expect(screen.getByRole('searchbox', { name: 'Tìm lớp học' })).toHaveValue('');
  });

  it('shows the upcoming-events rail with host, time, class and a link to the event', async () => {
    const requested = install({ catalog: () => [make('a')], events: [event('e1'), event('e2', { format: 'OFFLINE', location: 'Hà Nội', registeredCount: 1500 })] });
    render(<ClassesPage />);

    const rail = await screen.findByRole('region', { name: 'Sự kiện sắp tới' });
    expect(requested.some((u) => u.includes('/events/upcoming?size=6'))).toBe(true);
    const first = within(rail).getByRole('heading', { name: 'Sự kiện e1' }).closest('article') as HTMLElement;
    expect(first).toHaveTextContent('Chủ trì: Thầy Minh');
    expect(first).toHaveTextContent('Lớp Toán');
    expect(first).toHaveTextContent('Trực tuyến · Zoom');
    expect(first).toHaveTextContent('12 đã đăng ký');
    expect(within(first).getByRole('link', { name: 'Xem & đăng ký' })).toHaveAttribute('href', '/classes/lop-toan/events/e1');
    expect(within(rail).getByText('1.500 đã đăng ký')).toBeInTheDocument();
  });

  it('hides the events rail when the call fails or returns nothing', async () => {
    install({ catalog: () => [make('a')], events: 'fail' });
    const view = render(<ClassesPage />);
    await screen.findByRole('region', { name: 'Phổ biến' });
    expect(screen.queryByRole('region', { name: 'Sự kiện sắp tới' })).not.toBeInTheDocument();
    view.unmount();

    vi.restoreAllMocks();
    install({ catalog: () => [make('a')], events: [] });
    render(<ClassesPage />);
    await screen.findByRole('region', { name: 'Phổ biến' });
    expect(screen.queryByRole('region', { name: 'Sự kiện sắp tới' })).not.toBeInTheDocument();
  });

  it('falls back to the loaded catalog when a rail request fails', async () => {
    install({ catalog: () => [make('x', { memberCount: 1 }), make('y', { memberCount: 50 })], popular: 'fail', newest: 'fail' });
    render(<ClassesPage />);
    await waitFor(() =>
      expect(within(section('Phổ biến')).getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(['Lớp y', 'Lớp x']),
    );
  });

  it('offers a guest the creator band with a sign-in link instead of the create dialog', async () => {
    install({ catalog: () => [make('a')] });
    render(<ClassesPage />);
    const band = await screen.findByRole('region', { name: 'Bạn có kiến thức muốn chia sẻ?' });
    expect(within(band).getByRole('link', { name: 'Đăng nhập để tạo lớp' })).toHaveAttribute('href', '/login');
    expect(screen.queryByRole('button', { name: /Tạo lớp học mới/ })).not.toBeInTheDocument();
  });
});
