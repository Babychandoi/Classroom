import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, act, within } from '@testing-library/react';
import { JoinByInvitePage } from '../pages/JoinByInvitePage';
import { resetCheckoutAvailabilityCache } from '../api/payments';
import type { InvitePreview } from '../types';

/**
 * D-19: /join/:code - the invite landing page. The code is a bearer secret in the URL: the page must say no-referrer while it is
 * open, send the code only to our own API, never log it, keep it out of document.title, and leave history with `replace`.
 */

const CODE = 'q8r0vKc2Lw1nZ5uYtH7eXb3GjM9dPaSf';
const mockNavigate = vi.fn();
const mockLocation = { pathname: `/join/${CODE}`, search: '', hash: '', state: null as unknown, key: 'k' };
let routeCode = CODE;

vi.mock('react-router-dom', () => ({
  useParams: () => ({ code: routeCode }),
  useNavigate: () => mockNavigate,
  useLocation: () => mockLocation,
  Link: ({ children, to, replace, state }: { children?: React.ReactNode; to: string; replace?: boolean; state?: unknown }) => (
    <a href={to} data-replace={String(!!replace)} data-from={(state as { from?: { pathname?: string } } | undefined)?.from?.pathname}>{children}</a>
  ),
}));

const mockUser = { id: 'u1', fullName: 'Student', email: 'student@test.local', role: 'STUDENT', status: 'ACTIVE' };
let authState: { user: typeof mockUser | null; isLoading: boolean } = { user: mockUser, isLoading: false };
vi.mock('../context/AuthContext', () => ({ useAuth: () => authState }));

const freePreview: InvitePreview = {
  classId: 'class-1',
  slug: 'lop-rieng-tu-ma-moi',
  title: 'Lớp Riêng Tư (mã mời)',
  description: 'Chỉ vào được bằng mã mời.',
  coverImageUrl: null,
  accessType: 'FREE',
  ownerName: 'Thầy Nguyễn Chủ Nhiệm',
};
const paidPreview: InvitePreview = {
  ...freePreview,
  title: 'Lớp Kín Có Phí',
  accessType: 'PAID',
  price: 199000,
  currency: 'VND',
  durationDays: 30,
  lifetime: false,
};

const json = (data: unknown, status = 200) => new Response(JSON.stringify({ success: true, data }), { status });
const fail = (status: number, error: Record<string, unknown>) => new Response(JSON.stringify({ success: false, error }), { status });

type Handler = (url: string, method: string, body: any) => Response | undefined;
let calls: { method: string; url: string; body: any; headers?: Headers }[];

function installFetch(handler: Handler) {
  calls = [];
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = input.toString();
    const method = (init?.method || 'GET').toUpperCase();
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ method, url, body, headers: init?.headers as Headers });
    return handler(url, method, body) ?? new Response('{}', { status: 404 });
  });
}

const previewUrl = `/classes/invites/${CODE}`;
const joinUrl = `/classes/invites/${CODE}/join`;

beforeEach(() => {
  vi.restoreAllMocks();
  resetCheckoutAvailabilityCache();
  mockNavigate.mockReset();
  routeCode = CODE;
  authState = { user: mockUser, isLoading: false };
  document.title = 'Nền Tảng Lớp Học Trực Tuyến';
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('JoinByInvitePage - valid FREE invite', () => {
  it('shows the class card (title, owner, description, free) and joins, then leaves history with replace', async () => {
    installFetch((url, method) => {
      if (url.endsWith(previewUrl) && method === 'GET') return json(freePreview);
      if (url.endsWith(joinUrl) && method === 'POST') return json({ id: 'class-1', slug: 'lop-rieng-tu-ma-moi', memberState: 'ACTIVE', isMember: true });
      return undefined;
    });
    render(<JoinByInvitePage />);

    expect(await screen.findByRole('heading', { name: 'Lớp Riêng Tư (mã mời)' })).toBeInTheDocument();
    expect(screen.getByText('Thầy Nguyễn Chủ Nhiệm')).toBeInTheDocument();
    expect(screen.getByText('Chỉ vào được bằng mã mời.')).toBeInTheDocument();
    expect(screen.getByText('Miễn phí')).toBeInTheDocument();

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Tham gia lớp' })); });

    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/classes/lop-rieng-tu-ma-moi/feed', { replace: true }));
    expect(calls.some((c) => c.method === 'POST' && c.url.endsWith(joinUrl))).toBe(true);
    // never a second history entry for the class
    expect(mockNavigate).toHaveBeenCalledTimes(1);
  });

  it('an existing member is simply let in (the join is idempotent) - straight to the class', async () => {
    installFetch((url, method) => {
      if (url.endsWith(previewUrl)) return json(freePreview);
      if (url.endsWith(joinUrl) && method === 'POST') return json({ id: 'class-1', slug: 'slug-from-server', memberState: 'ACTIVE', isMember: true });
      return undefined;
    });
    render(<JoinByInvitePage />);
    const clickTarget1 = await screen.findByRole('button', { name: 'Tham gia lớp' });
    await act(async () => { fireEvent.click(clickTarget1); });
    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/classes/slug-from-server/feed', { replace: true }));
  });
});

describe('JoinByInvitePage - guest', () => {
  it('asks a guest to sign in with a replacing link that returns to this page, and makes no join request', async () => {
    authState = { user: null, isLoading: false };
    installFetch((url) => (url.endsWith(previewUrl) ? json(freePreview) : undefined));
    render(<JoinByInvitePage />);

    const link = await screen.findByRole('link', { name: 'Đăng nhập để tham gia' });
    expect(link).toHaveAttribute('href', '/login');
    expect(link).toHaveAttribute('data-replace', 'true');
    expect(link).toHaveAttribute('data-from', `/join/${CODE}`);
    expect(screen.queryByRole('button', { name: 'Tham gia lớp' })).not.toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
  });

  it('shows no action while the session is still being restored', async () => {
    authState = { user: null, isLoading: true };
    installFetch((url) => (url.endsWith(previewUrl) ? json(freePreview) : undefined));
    render(<JoinByInvitePage />);

    const button = await screen.findByRole('button', { name: 'Đang xác thực...' });
    expect(button).toBeDisabled();
    expect(screen.queryByRole('link', { name: 'Đăng nhập để tham gia' })).not.toBeInTheDocument();
  });
});

describe('JoinByInvitePage - invalid codes', () => {
  it('a 404 is the one friendly "Mã mời không hợp lệ hoặc đã hết hạn" page with a way back to /classes', async () => {
    installFetch(() => fail(404, { code: 'NOT_FOUND', message: 'Không tìm thấy lớp học' }));
    render(<JoinByInvitePage />);

    const alert = await screen.findByRole('alert');
    expect(within(alert).getByRole('heading', { name: 'Mã mời không hợp lệ hoặc đã hết hạn' })).toBeInTheDocument();
    expect(within(alert).getByRole('link', { name: /Xem các lớp học công khai/ })).toHaveAttribute('href', '/classes');
    expect(screen.queryByRole('button', { name: /Tham gia|Mua/ })).not.toBeInTheDocument();
  });

  it('a malformed code is invalid without asking the server at all', async () => {
    routeCode = 'abc';
    const spy = installFetch(() => undefined);
    render(<JoinByInvitePage />);

    expect(await screen.findByRole('heading', { name: 'Mã mời không hợp lệ hoặc đã hết hạn' })).toBeInTheDocument();
    expect(spy).not.toHaveBeenCalled();
  });

  it('a rate-limited lookup says to wait and can be retried', async () => {
    let first = true;
    installFetch((url) => {
      if (!url.endsWith(previewUrl)) return undefined;
      if (first) { first = false; return fail(429, { code: 'RATE_LIMITED', message: 'Too many' }); }
      return json(freePreview);
    });
    render(<JoinByInvitePage />);

    expect(await screen.findByText(/thử quá nhiều lần/)).toBeInTheDocument();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Thử lại' })); });
    expect(await screen.findByRole('heading', { name: 'Lớp Riêng Tư (mã mời)' })).toBeInTheDocument();
  });

  it('the code being revoked between preview and click ends on the invalid page', async () => {
    installFetch((url, method) => {
      if (url.endsWith(previewUrl)) return json(freePreview);
      if (url.endsWith(joinUrl) && method === 'POST') return fail(404, { code: 'NOT_FOUND', message: 'Không tìm thấy lớp học' });
      return undefined;
    });
    render(<JoinByInvitePage />);
    const clickTarget2 = await screen.findByRole('button', { name: 'Tham gia lớp' });
    await act(async () => { fireEvent.click(clickTarget2); });

    expect(await screen.findByRole('heading', { name: 'Mã mời không hợp lệ hoặc đã hết hạn' })).toBeInTheDocument();
    expect(mockNavigate).not.toHaveBeenCalled();
  });
});

describe('JoinByInvitePage - blocked', () => {
  it('a blocked person gets the "bị chặn" notice and no further action', async () => {
    installFetch((url, method) => {
      if (url.endsWith(previewUrl)) return json(freePreview);
      if (url.endsWith(joinUrl) && method === 'POST') return fail(403, { code: 'FORBIDDEN', message: 'Bạn đã bị chặn khỏi lớp học này' });
      return undefined;
    });
    render(<JoinByInvitePage />);
    const clickTarget3 = await screen.findByRole('button', { name: 'Tham gia lớp' });
    await act(async () => { fireEvent.click(clickTarget3); });

    const notice = await screen.findByRole('alert');
    expect(within(notice).getByRole('heading', { name: 'Bạn đã bị chặn khỏi lớp học này' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Tham gia lớp' })).not.toBeInTheDocument();
    expect(mockNavigate).not.toHaveBeenCalled();
  });
});

describe('JoinByInvitePage - valid PAID invite', () => {
  const paidHandler = (orderStatus: () => 'PENDING' | 'PAID'): Handler => (url, method) => {
    if (url.endsWith(previewUrl)) return json(paidPreview);
    if (url.includes('/payments/sandbox-status')) return json({ checkoutAvailable: true });
    if (url.endsWith(joinUrl) && method === 'POST') {
      return fail(402, {
        code: 'PAYMENT_REQUIRED',
        message: 'Lớp học trả phí; cần thanh toán để tham gia',
        details: { classId: 'class-1', accessType: 'PAID', accessProduct: { id: 'prod-access', price: 199000, currency: 'VND', durationDays: 30, lifetime: false } },
      });
    }
    if (url.endsWith('/orders') && method === 'POST') {
      return json({ id: 'o1', orderNumber: 'ORD-9', status: 'PENDING', totalAmount: 199000, currency: 'VND', buyerId: 'u1', classId: 'class-1', provider: 'MOCK', createdAt: new Date().toISOString() });
    }
    if (url.endsWith('/orders/o1') && method === 'GET') {
      return json({ id: 'o1', orderNumber: 'ORD-9', status: orderStatus(), totalAmount: 199000, currency: 'VND', buyerId: 'u1', classId: 'class-1', provider: 'MOCK', createdAt: new Date().toISOString() });
    }
    return undefined;
  };

  it('shows the price and length, then buys with the invite code and enters the class once paid', async () => {
    let status: 'PENDING' | 'PAID' = 'PENDING';
    installFetch(paidHandler(() => status));
    render(<JoinByInvitePage />);

    expect(await screen.findByRole('heading', { name: 'Lớp Kín Có Phí' })).toBeInTheDocument();
    expect(screen.getByTestId('invite-price')).toHaveTextContent('199.000đ / 30 ngày');
    expect(screen.getAllByText(/Trả phí/).length).toBeGreaterThan(0);

    const clickTarget4 = await screen.findByRole('button', { name: 'Mua để tham gia' });
    await act(async () => { fireEvent.click(clickTarget4); });

    const dialog = await screen.findByRole('dialog', { name: 'Trạng thái thanh toán' });
    const order = calls.find((c) => c.method === 'POST' && c.url.endsWith('/orders'))!;
    // the invite code rides along so a PRIVATE paid class lets the buyer order; the idempotency key is sent too
    expect(order.body).toEqual({ classId: 'class-1', productId: 'prod-access', idempotencyKey: expect.any(String), inviteCode: CODE });
    expect(mockNavigate).not.toHaveBeenCalled();

    status = 'PAID';
    await act(async () => { fireEvent.click(within(dialog).getByRole('button', { name: 'Làm mới trạng thái đơn hàng' })); });
    await waitFor(() => expect(within(dialog).getByText('Đã thanh toán thành công')).toBeInTheDocument());

    fireEvent.click(within(dialog).getByRole('button', { name: 'Vào lớp học' }));
    expect(mockNavigate).toHaveBeenCalledWith('/classes/lop-rieng-tu-ma-moi/feed', { replace: true });
  });

  it('keeps the idempotency key after a 5xx so a retry reuses it, and sends the code every time', async () => {
    const keys: string[] = [];
    let orderAttempts = 0;
    installFetch((url, method, body) => {
      if (url.endsWith('/orders') && method === 'POST') {
        orderAttempts += 1;
        keys.push(body.idempotencyKey);
        return fail(500, { code: 'INTERNAL', message: 'boom' });
      }
      return paidHandler(() => 'PENDING')(url, method, body);
    });
    render(<JoinByInvitePage />);

    const buy = await screen.findByRole('button', { name: 'Mua để tham gia' });
    await act(async () => { fireEvent.click(buy); });
    await waitFor(() => expect(orderAttempts).toBe(1));
    expect(await screen.findByText('boom')).toBeInTheDocument();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Mua để tham gia' })); });
    await waitFor(() => expect(orderAttempts).toBe(2));
    expect(keys[1]).toBe(keys[0]);
    expect(calls.filter((c) => c.url.endsWith('/orders')).every((c) => c.body.inviteCode === CODE)).toBe(true);
  });

  it('says "Tạm chưa hỗ trợ thanh toán" (disabled) where there is no payment rail', async () => {
    installFetch((url, method, body) => {
      if (url.includes('/payments/sandbox-status')) return fail(404, { code: 'NOT_FOUND', message: 'nope' });
      return paidHandler(() => 'PENDING')(url, method, body);
    });
    render(<JoinByInvitePage />);
    const button = await screen.findByRole('button', { name: 'Tạm chưa hỗ trợ thanh toán' });
    expect(button).toBeDisabled();
  });

  it('an invite that stops working at the order step ends on the invalid page', async () => {
    installFetch((url, method, body) => {
      if (url.endsWith('/orders') && method === 'POST') return fail(404, { code: 'NOT_FOUND', message: 'Không tìm thấy lớp học' });
      return paidHandler(() => 'PENDING')(url, method, body);
    });
    render(<JoinByInvitePage />);
    const clickTarget5 = await screen.findByRole('button', { name: 'Mua để tham gia' });
    await act(async () => { fireEvent.click(clickTarget5); });
    expect(await screen.findByRole('heading', { name: 'Mã mời không hợp lệ hoặc đã hết hạn' })).toBeInTheDocument();
  });
});

describe('JoinByInvitePage - the invite code is a secret', () => {
  it('sets <meta name="referrer" content="no-referrer"> while mounted and removes it on unmount', async () => {
    installFetch((url) => (url.endsWith(previewUrl) ? json(freePreview) : undefined));
    const before = document.querySelectorAll('meta[name="referrer"]').length;
    const view = render(<JoinByInvitePage />);

    const meta = document.querySelector('meta[name="referrer"]');
    expect(meta).not.toBeNull();
    expect(meta?.getAttribute('content')).toBe('no-referrer');
    expect(document.querySelectorAll('meta[name="referrer"]').length).toBe(before + 1);
    await screen.findByRole('heading', { name: 'Lớp Riêng Tư (mã mời)' });

    view.unmount();
    expect(document.querySelectorAll('meta[name="referrer"]').length).toBe(before);
    expect(document.querySelector('meta[name="referrer"][content="no-referrer"]')).toBeNull();
  });

  it('never logs the code, never puts it in the title and only ever sends it to its own API', async () => {
    const consoleSpies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((m) => vi.spyOn(console, m).mockImplementation(() => {}));
    installFetch((url, method) => {
      if (url.endsWith(previewUrl)) return json({ ...freePreview, coverImageUrl: 'https://cdn.example.test/cover.png' });
      if (url.endsWith(joinUrl) && method === 'POST') return json({ id: 'class-1', slug: 'lop-rieng-tu-ma-moi' });
      return undefined;
    });
    render(<JoinByInvitePage />);
    const clickTarget6 = await screen.findByRole('button', { name: 'Tham gia lớp' });
    await act(async () => { fireEvent.click(clickTarget6); });
    await waitFor(() => expect(mockNavigate).toHaveBeenCalled());

    for (const spy of consoleSpies) {
      expect(JSON.stringify(spy.mock.calls)).not.toContain(CODE);
    }
    expect(document.title).not.toContain(CODE);
    // every request that carries the code is a same-origin call to /api/v1 (no third-party host ever sees it)
    const carrying = calls.filter((c) => c.url.includes(CODE));
    expect(carrying.length).toBeGreaterThan(0);
    expect(carrying.every((c) => c.url.startsWith('/api/v1/'))).toBe(true);
    // the one external resource (the cover image) is fetched without a Referer
    const cover = document.querySelector('img');
    expect(cover?.getAttribute('referrerpolicy')).toBe('no-referrer');
    expect(cover?.getAttribute('src')).not.toContain(CODE);
  });

  it('puts the code in the URL-encoded path only (no query string, no body)', async () => {
    installFetch((url, method) => {
      if (url.endsWith(previewUrl)) return json(freePreview);
      if (url.endsWith(joinUrl) && method === 'POST') return json({ id: 'class-1', slug: 'x' });
      return undefined;
    });
    render(<JoinByInvitePage />);
    const clickTarget7 = await screen.findByRole('button', { name: 'Tham gia lớp' });
    await act(async () => { fireEvent.click(clickTarget7); });
    await waitFor(() => expect(mockNavigate).toHaveBeenCalled());

    for (const c of calls.filter((x) => x.url.includes(CODE))) {
      expect(c.url).not.toContain('?');
      expect(JSON.stringify(c.body ?? {})).not.toContain(CODE);
    }
  });
});
