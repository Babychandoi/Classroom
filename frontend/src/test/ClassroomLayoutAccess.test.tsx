import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, act, within } from '@testing-library/react';
import { ClassroomLayout } from '../pages/ClassroomLayout';
import { resetCheckoutAvailabilityCache } from '../api/payments';
import { formatDate } from '../api/format';
import { MEMBERSHIP_EXPIRED_EVENT } from '../api/errorMessages';
import type { Classroom } from '../types';

/**
 * D-19: the classroom shell for every cell of the access matrix - guest / signed-in / lapsed member x public-free / public-paid /
 * private (404) - plus the paywall -> checkout -> refresh path and the "Sắp hết hạn" chip.
 */

const mockNavigate = vi.fn();
const mockLocation = { pathname: '/classes/demo-class/feed', search: '', hash: '', state: null as unknown, key: 'k' };

vi.mock('react-router-dom', () => ({
  useParams: () => ({ slug: 'demo-class' }),
  useNavigate: () => mockNavigate,
  useLocation: () => mockLocation,
  Outlet: () => <div data-testid="outlet" />,
  NavLink: ({ children, to }: { children?: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
  Link: ({ children, to }: { children?: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

const mockUser = { id: 'u1', fullName: 'Student', email: 'student@test.local', role: 'STUDENT', status: 'ACTIVE' };
let authState: { user: typeof mockUser | null; isLoading: boolean } = { user: mockUser, isLoading: false };
vi.mock('../context/AuthContext', () => ({ useAuth: () => authState }));

const DAY = 24 * 60 * 60 * 1000;
const ACCESS = { id: 'prod-access', price: 199000, currency: 'VND', durationDays: 30, lifetime: false };

const base: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  ownerName: 'Thầy Chủ',
  slug: 'demo-class',
  title: 'Lớp Trả Phí',
  status: 'ACTIVE',
  memberCount: 5,
  isMember: false,
  isOwner: false,
  userRole: 'GUEST',
  memberState: 'NONE',
  visibility: 'PUBLIC',
  accessType: 'PAID',
  accessProduct: ACCESS,
  createdAt: new Date().toISOString(),
} as Classroom;

let current: Classroom;
let orderStatus: 'PENDING' | 'PAID';
let sandbox: 'on' | 'off';
let calls: { method: string; url: string; body: any }[];

const json = (data: unknown, status = 200) => new Response(JSON.stringify({ success: true, data }), { status });
const fail = (status: number, error: Record<string, unknown>) => new Response(JSON.stringify({ success: false, error }), { status });

function installFetch(joinResponse?: () => Response) {
  calls = [];
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = input.toString();
    const method = (init?.method || 'GET').toUpperCase();
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ method, url, body });
    if (url.includes('/classes/slug/demo-class')) return json(current);
    if (url.includes('/payments/sandbox-status')) {
      return sandbox === 'on' ? json({ checkoutAvailable: true }) : fail(404, { code: 'NOT_FOUND', message: 'nope' });
    }
    if (url.endsWith('/orders') && method === 'POST') {
      return json({ id: 'o1', orderNumber: 'ORD-1', status: 'PENDING', totalAmount: 199000, currency: 'VND', buyerId: 'u1', classId: 'class-1', provider: 'MOCK', createdAt: new Date().toISOString() });
    }
    if (url.endsWith('/orders/o1') && method === 'GET') {
      if (orderStatus === 'PAID') current = { ...current, isMember: true, userRole: 'STUDENT', memberState: 'ACTIVE', accessExpiresAt: new Date(Date.now() + 30 * DAY).toISOString() };
      return json({ id: 'o1', orderNumber: 'ORD-1', status: orderStatus, totalAmount: 199000, currency: 'VND', buyerId: 'u1', classId: 'class-1', provider: 'MOCK', createdAt: new Date().toISOString() });
    }
    if (url.endsWith('/classes/class-1/join') && method === 'POST') {
      if (joinResponse) return joinResponse();
      return json({ ...current, isMember: true, userRole: 'STUDENT', memberState: 'ACTIVE' });
    }
    return new Response('{}', { status: 404 });
  });
}

const tabNames = () => screen.queryAllByRole('link').map((a) => a.textContent?.trim());

beforeEach(() => {
  vi.restoreAllMocks();
  resetCheckoutAvailabilityCache();
  mockNavigate.mockReset();
  mockLocation.pathname = '/classes/demo-class/feed';
  authState = { user: mockUser, isLoading: false };
  current = { ...base };
  orderStatus = 'PENDING';
  sandbox = 'on';
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('ClassroomLayout - non-member of a PUBLIC FREE class', () => {
  it('keeps the plain "Tham gia lớp ngay" prompt and no paywall', async () => {
    current = { ...base, accessType: 'FREE', accessProduct: null };
    installFetch();
    render(<ClassroomLayout />);

    expect(await screen.findByRole('button', { name: /Tham gia lớp ngay/ })).toBeInTheDocument();
    expect(screen.queryByTestId('paywall-card')).not.toBeInTheDocument();
    expect(screen.queryByTestId('paywall-banner')).not.toBeInTheDocument();
    expect(screen.getByTestId('outlet')).toBeInTheDocument();
    // a free class makes no payment request at all
    expect(calls.some((c) => c.url.includes('/payments/sandbox-status'))).toBe(false);
  });
});

describe('ClassroomLayout - non-member of a PUBLIC PAID class', () => {
  it('shows the paywall (price, length, what you get) above the public feed, and no free-join button', async () => {
    installFetch();
    render(<ClassroomLayout />);

    const card = await screen.findByTestId('paywall-card');
    expect(within(card).getByTestId('paywall-price')).toHaveTextContent('199.000đ');
    expect(within(card).getByTestId('paywall-duration')).toHaveTextContent('/ 30 ngày');
    expect(within(card).getByText(/Toàn bộ khóa học và bài giảng/)).toBeInTheDocument();
    expect(within(card).getByRole('button', { name: 'Mua để tham gia' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Tham gia lớp ngay/ })).not.toBeInTheDocument();
    // the feed (public posts) stays readable under the card
    expect(screen.getByTestId('outlet')).toBeInTheDocument();
    // header badges
    expect(screen.getByTestId('badge-paid')).toHaveTextContent('Trả phí · 199.000đ / 30 ngày');
  });

  it('says "trọn đời" for a lifetime product', async () => {
    current = { ...base, accessProduct: { ...ACCESS, durationDays: null, lifetime: true } };
    installFetch();
    render(<ClassroomLayout />);

    const card = await screen.findByTestId('paywall-card');
    expect(within(card).getByTestId('paywall-duration')).toHaveTextContent('/ trọn đời');
    expect(within(card).getByText(/Truy cập trọn đời/)).toBeInTheDocument();
  });

  it('a guest is asked to sign in (and sent back to this class), never to a payment request', async () => {
    authState = { user: null, isLoading: false };
    installFetch();
    render(<ClassroomLayout />);

    fireEvent.click(await screen.findByRole('button', { name: 'Đăng nhập để mua' }));
    expect(mockNavigate).toHaveBeenCalledWith('/login', { state: { from: mockLocation } });
    expect(calls.some((c) => c.url.endsWith('/orders'))).toBe(false);
  });

  it('member-only tabs show the paywall instead of the tab (no raw 403), the public tabs keep their content', async () => {
    installFetch();
    mockLocation.pathname = '/classes/demo-class/learn';
    const view = render(<ClassroomLayout />);

    expect(await screen.findByTestId('paywall-card')).toBeInTheDocument();
    expect(screen.queryByTestId('outlet')).not.toBeInTheDocument();
    view.unmount();

    mockLocation.pathname = '/classes/demo-class/store';
    render(<ClassroomLayout />);
    const banner = await screen.findByTestId('paywall-banner');
    expect(banner).toHaveTextContent('199.000đ / 30 ngày');
    expect(screen.queryByTestId('paywall-card')).not.toBeInTheDocument();
    expect(screen.getByTestId('outlet')).toBeInTheDocument();
  });

  it('buys: POST /orders for the class-access product, shows the order, and becomes a member once the order is paid', async () => {
    installFetch();
    render(<ClassroomLayout />);

    const buy = await screen.findByRole('button', { name: 'Mua để tham gia' });
    await act(async () => {
      fireEvent.click(buy);
    });
    const dialog = await screen.findByRole('dialog', { name: 'Trạng thái thanh toán' });
    expect(within(dialog).getByText('Mã đơn: ORD-1')).toBeInTheDocument();
    expect(within(dialog).getByText('Thanh toán đang chờ xác nhận')).toBeInTheDocument();
    expect(within(dialog).getByText('30 ngày')).toBeInTheDocument();

    const order = calls.find((c) => c.method === 'POST' && c.url.endsWith('/orders'))!;
    expect(order.body).toEqual(expect.objectContaining({ classId: 'class-1', productId: 'prod-access' }));
    expect(order.body.inviteCode).toBeUndefined();
    expect(typeof order.body.idempotencyKey).toBe('string');

    // the owner settles the order out of band; the buyer refreshes the status
    orderStatus = 'PAID';
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Làm mới trạng thái đơn hàng' }));
    });

    await waitFor(() => expect(within(dialog).getByText('Đã thanh toán thành công')).toBeInTheDocument());
    // the class was re-read silently: the dialog is still open, the paywall is gone and the person is a member
    await waitFor(() => expect(screen.queryByTestId('paywall-card')).not.toBeInTheDocument());
    expect(screen.getByRole('dialog', { name: 'Trạng thái thanh toán' })).toBeInTheDocument();
    expect(screen.getByTestId('outlet')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Bắt đầu học' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByTestId('outlet')).toBeInTheDocument();
  });

  it('shows "Tạm chưa hỗ trợ thanh toán" (disabled) where the deployment has no payment rail', async () => {
    sandbox = 'off';
    installFetch();
    render(<ClassroomLayout />);

    const button = await screen.findByRole('button', { name: 'Tạm chưa hỗ trợ thanh toán' });
    expect(button).toBeDisabled();
    expect(calls.some((c) => c.url.endsWith('/orders'))).toBe(false);
  });

  it('a definitive rejection of the order is shown inline, in the paywall', async () => {
    installFetch();
    render(<ClassroomLayout />);
    const button = await screen.findByRole('button', { name: 'Mua để tham gia' });
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      if (input.toString().endsWith('/orders') && init?.method === 'POST') {
        return fail(400, { code: 'BAD_REQUEST', message: 'Lớp học đã lưu trữ' });
      }
      return json(current);
    });
    await act(async () => { fireEvent.click(button); });

    expect(await screen.findByText('Lớp học đã lưu trữ')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('ClassroomLayout - lapsed (EXPIRED) member', () => {
  const expiredAt = new Date(Date.now() - 3 * DAY).toISOString();
  const expired = () => ({ ...base, memberState: 'EXPIRED', accessExpiresAt: expiredAt }) as Classroom;

  it('limits the tabs to Giới thiệu / Cửa hàng and shows the renewal prompt instead of member-only content (not a raw 403)', async () => {
    current = expired();
    installFetch();
    render(<ClassroomLayout />);

    const card = await screen.findByTestId('renewal-card');
    expect(card).toHaveTextContent(`hết hạn ngày ${formatDate(expiredAt)}`);
    expect(within(card).getByRole('button', { name: 'Gia hạn' })).toBeInTheDocument();
    expect(screen.queryByTestId('outlet')).not.toBeInTheDocument();
    expect(tabNames()).toEqual(expect.arrayContaining(['Giới thiệu', 'Cửa hàng']));
    for (const hidden of ['Bảng tin', 'Góc học tập', 'Luyện thi', 'Xếp hạng', 'Tài liệu', 'Thành viên']) {
      expect(tabNames()).not.toContain(hidden);
    }
    expect(screen.queryByText(/403|Forbidden|Failed to fetch/i)).not.toBeInTheDocument();
    // a lapsed member is labelled as such, not as a GUEST
    expect(screen.getByTestId('badge-expired')).toHaveTextContent('Hết hạn');
    expect(screen.queryByText('GUEST')).not.toBeInTheDocument();
  });

  it.each(['learn', 'exams', 'leaderboard', 'documents', 'members'])('the %s tab is locked behind the renewal prompt', async (tab) => {
    current = expired();
    mockLocation.pathname = `/classes/demo-class/${tab}`;
    installFetch();
    render(<ClassroomLayout />);
    expect(await screen.findByTestId('renewal-card')).toBeInTheDocument();
    expect(screen.queryByTestId('outlet')).not.toBeInTheDocument();
  });

  it.each(['about', 'store'])('the %s tab stays readable, under the expiry banner "Gói thành viên lớp đã hết hạn ngày dd/MM/yyyy"', async (tab) => {
    current = expired();
    mockLocation.pathname = `/classes/demo-class/${tab}`;
    installFetch();
    render(<ClassroomLayout />);

    const banner = await screen.findByTestId('renewal-banner');
    expect(banner).toHaveTextContent(`Gói thành viên lớp đã hết hạn ngày ${formatDate(expiredAt)}`);
    expect(within(banner).getByRole('button', { name: 'Gia hạn' })).toBeInTheDocument();
    expect(screen.getByTestId('outlet')).toBeInTheDocument();
    expect(screen.queryByTestId('renewal-card')).not.toBeInTheDocument();
  });

  it('renews: the same checkout, then the membership is active again', async () => {
    current = expired();
    installFetch();
    render(<ClassroomLayout />);

    const renew = within(await screen.findByTestId('renewal-card')).getByRole('button', { name: 'Gia hạn' });
    await act(async () => {
      fireEvent.click(renew);
    });
    const dialog = await screen.findByRole('dialog', { name: 'Trạng thái thanh toán' });
    expect(calls.find((c) => c.url.endsWith('/orders'))!.body.productId).toBe('prod-access');

    orderStatus = 'PAID';
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Làm mới trạng thái đơn hàng' }));
    });
    await waitFor(() => expect(screen.queryByTestId('renewal-card')).not.toBeInTheDocument());
    expect(screen.getByTestId('outlet')).toBeInTheDocument();
  });

  it('a lapsed member of a class that has since become FREE is offered "Tham gia lại", not a purchase', async () => {
    current = { ...expired(), accessType: 'FREE', accessProduct: null };
    installFetch();
    render(<ClassroomLayout />);

    const rejoin = await screen.findByRole('button', { name: 'Tham gia lại' });
    expect(screen.queryByTestId('renewal-card')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Gia hạn' })).not.toBeInTheDocument();
    await act(async () => { fireEvent.click(rejoin); });
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Tham gia lại' })).not.toBeInTheDocument());
    expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/classes/class-1/join'))).toBe(true);
  });

  it('re-reads the membership when a tab gets MEMBERSHIP_EXPIRED (access lapsed while the page was open)', async () => {
    current = { ...base, isMember: true, userRole: 'STUDENT', memberState: 'ACTIVE', accessExpiresAt: new Date(Date.now() + DAY).toISOString() };
    installFetch();
    render(<ClassroomLayout />);
    await waitFor(() => expect(screen.getByTestId('outlet')).toBeInTheDocument());

    current = expired();
    await act(async () => { window.dispatchEvent(new CustomEvent(MEMBERSHIP_EXPIRED_EVENT)); });

    expect(await screen.findByTestId('renewal-card')).toBeInTheDocument();
    expect(screen.queryByTestId('outlet')).not.toBeInTheDocument();
  });
});

describe('ClassroomLayout - PRIVATE class', () => {
  it('a private class is a 404 for an outsider: a proper "Không tìm thấy lớp học" page, never a session-expired message', async () => {
    authState = { user: null, isLoading: false };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(fail(404, { code: 'NOT_FOUND', message: 'Không tìm thấy lớp học' }));
    render(<ClassroomLayout />);

    const alert = await screen.findByRole('alert');
    expect(within(alert).getByRole('heading', { name: 'Không tìm thấy lớp học' })).toBeInTheDocument();
    expect(alert).toHaveTextContent(/lớp riêng tư/);
    expect(within(alert).getByRole('link', { name: 'Về danh sách lớp học' })).toHaveAttribute('href', '/classes');
    expect(screen.queryByText(/phiên|đăng nhập lại|hết hạn/i)).not.toBeInTheDocument();
    expect(screen.queryByTestId('outlet')).not.toBeInTheDocument();
  });

  it('a signed-in outsider gets the same 404 page', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(fail(404, { code: 'NOT_FOUND', message: 'Không tìm thấy lớp học' }));
    render(<ClassroomLayout />);
    expect(await screen.findByRole('heading', { name: 'Không tìm thấy lớp học' })).toBeInTheDocument();
  });

  it('a 500 is still the retryable error banner, not the not-found page', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(fail(500, { code: 'INTERNAL', message: 'Lỗi máy chủ' }));
    render(<ClassroomLayout />);
    expect(await screen.findByText('Lỗi máy chủ')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Thử lại' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Không tìm thấy lớp học' })).not.toBeInTheDocument();
  });

  it('an EXPIRED member of a private paid class still sees the class (and the renewal prompt)', async () => {
    current = { ...base, visibility: 'PRIVATE', memberState: 'EXPIRED', accessExpiresAt: new Date(Date.now() - DAY).toISOString() } as Classroom;
    installFetch();
    render(<ClassroomLayout />);
    expect(await screen.findByTestId('renewal-card')).toBeInTheDocument();
    expect(screen.getByTestId('badge-private')).toBeInTheDocument();
  });

  it('a private class that somehow reaches a non-member offers no join button (an invite link is the only way in)', async () => {
    current = { ...base, accessType: 'FREE', accessProduct: null, visibility: 'PRIVATE' } as Classroom;
    installFetch();
    render(<ClassroomLayout />);
    expect(await screen.findByText(/Đây là lớp riêng tư/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Tham gia/ })).not.toBeInTheDocument();
  });
});

describe('ClassroomLayout - joining a class that turned out to be paid', () => {
  it('a REMOVED person who presses "Tham gia lại" on a PAID class is sent to checkout with the product from the 402', async () => {
    current = { ...base, memberState: 'REMOVED', accessProduct: null } as Classroom;
    installFetch(() =>
      fail(402, {
        code: 'PAYMENT_REQUIRED',
        message: 'Lớp học trả phí; cần thanh toán để tham gia',
        details: { classId: 'class-1', accessType: 'PAID', accessProduct: { id: 'prod-from-402', price: 99000, currency: 'VND', durationDays: null, lifetime: true } },
      }));
    render(<ClassroomLayout />);

    const rejoin = await screen.findByRole('button', { name: 'Tham gia lại' });
    await act(async () => { fireEvent.click(rejoin); });

    await screen.findByRole('dialog', { name: 'Trạng thái thanh toán' });
    expect(calls.find((c) => c.url.endsWith('/orders'))!.body.productId).toBe('prod-from-402');
  });

  it('INVITE_REQUIRED on join reads as a friendly sentence in the page', async () => {
    current = { ...base, accessType: 'FREE', accessProduct: null } as Classroom;
    installFetch(() => fail(403, { code: 'INVITE_REQUIRED', message: 'Lớp riêng tư, cần mã mời' }));
    render(<ClassroomLayout />);

    const join = await screen.findByRole('button', { name: /Tham gia lớp ngay/ });
    await act(async () => { fireEvent.click(join); });
    expect(await screen.findByRole('alert')).toHaveTextContent('Đây là lớp riêng tư. Bạn cần liên kết mời của chủ lớp để tham gia.');
  });
});

describe('ClassroomLayout - "Sắp hết hạn" chip for a member whose access ends soon', () => {
  const member = (inDays: number | null): Classroom =>
    ({
      ...base,
      isMember: true,
      userRole: 'STUDENT',
      memberState: 'ACTIVE',
      accessExpiresAt: inDays === null ? null : new Date(Date.now() + inDays * DAY - 60_000).toISOString(),
    }) as Classroom;

  it('shows a gentle chip with a renew link when access ends within 7 days', async () => {
    current = member(3);
    installFetch();
    render(<ClassroomLayout />);

    const chip = await screen.findByTestId('expiry-chip');
    expect(chip).toHaveTextContent('Sắp hết hạn');
    expect(chip).toHaveTextContent('còn 3 ngày');
    expect(within(chip).getByRole('link', { name: 'Gia hạn' })).toHaveAttribute('href', '/classes/demo-class/store');
    // a member is not gated
    expect(screen.getByTestId('outlet')).toBeInTheDocument();
    expect(screen.queryByTestId('paywall-card')).not.toBeInTheDocument();
  });

  it.each([[30], [null]])('shows nothing when access ends in %s days / never', async (days) => {
    current = member(days as number | null);
    installFetch();
    render(<ClassroomLayout />);
    await waitFor(() => expect(screen.getByTestId('outlet')).toBeInTheDocument());
    expect(screen.queryByTestId('expiry-chip')).not.toBeInTheDocument();
  });

  it('never for the owner', async () => {
    current = { ...member(2), isOwner: true, userRole: 'OWNER' };
    installFetch();
    render(<ClassroomLayout />);
    await waitFor(() => expect(screen.getByTestId('outlet')).toBeInTheDocument());
    expect(screen.queryByTestId('expiry-chip')).not.toBeInTheDocument();
  });
});

describe('ClassroomLayout - BLOCKED / REMOVED keep their earlier behaviour in a paid class', () => {
  it('BLOCKED sees only the notice, no paywall and no purchase', async () => {
    current = { ...base, memberState: 'BLOCKED' } as Classroom;
    installFetch();
    render(<ClassroomLayout />);
    expect(await screen.findByText('Bạn đã bị chặn khỏi lớp học này')).toBeInTheDocument();
    expect(screen.queryByTestId('paywall-card')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Mua|Gia hạn|Tham gia/ })).not.toBeInTheDocument();
  });

  it('REMOVED keeps "Tham gia lại" and is not shown the paywall', async () => {
    current = { ...base, memberState: 'REMOVED' } as Classroom;
    installFetch();
    render(<ClassroomLayout />);
    expect(await screen.findByRole('button', { name: 'Tham gia lại' })).toBeInTheDocument();
    expect(screen.queryByTestId('paywall-card')).not.toBeInTheDocument();
  });
});
