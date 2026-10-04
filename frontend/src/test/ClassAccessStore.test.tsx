import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, within, act } from '@testing-library/react';
import { StudioStore } from '../pages/studio/StudioStore';
import { StoreTab } from '../pages/classroom/StoreTab';
import { resetCheckoutAvailabilityCache } from '../api/payments';
import type { Classroom, Product } from '../types';

/**
 * D-19: the class-access product (kind CLASS_ACCESS) is managed only from Cài đặt lớp - the normal Studio product list, counts and
 * edit / archive flows never see it (a read-only "Gói vào lớp" card links to Settings) - while the learner Store tab shows it as the
 * way to join / renew.
 */

let studioClassroom: Classroom;
let storeClassroom: Classroom;
let storeUser: { id: string } | null = { id: 'u1' };
const mockNavigate = vi.fn();
const mockRefresh = vi.fn().mockResolvedValue(undefined);
let activeView: 'studio' | 'store' = 'studio';

vi.mock('react-router-dom', () => ({
  useOutletContext: () => (activeView === 'studio' ? { classroom: studioClassroom } : { classroom: storeClassroom, refreshClassroom: mockRefresh }),
  useNavigate: () => mockNavigate,
  Link: ({ children, to }: { children?: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: storeUser }),
}));

const standard: Product = {
  id: 'prod-std',
  classId: 'class-1',
  kind: 'STANDARD',
  title: 'Gói PRO 30 ngày',
  status: 'PUBLISHED',
  price: 99000,
  currency: 'VND',
  durationDays: 30,
} as Product;

const accessProduct = (over: Partial<Product> = {}): Product =>
  ({
    id: 'prod-access',
    classId: 'class-1',
    kind: 'CLASS_ACCESS',
    title: 'Gói vào lớp',
    status: 'PUBLISHED',
    price: 199000,
    currency: 'VND',
    durationDays: 30,
    ...over,
  }) as Product;

const json = (data: unknown, status = 200) => new Response(JSON.stringify({ success: true, data }), { status });

const baseClassroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Lớp Trả Phí',
  status: 'ACTIVE',
  memberCount: 3,
  userRole: 'OWNER',
  visibility: 'PUBLIC',
  accessType: 'PAID',
  accessProduct: { id: 'prod-access', price: 199000, currency: 'VND', durationDays: 30, lifetime: false },
  studioPermissions: [],
  studioScopedPermissions: [],
  createdAt: new Date().toISOString(),
} as Classroom;

describe('StudioStore - the class-access product is not a normal product', () => {
  let urls: { method: string; url: string }[];

  const install = (products: Product[]) => {
    urls = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      urls.push({ method: (init?.method || 'GET').toUpperCase(), url });
      if (url.includes('/studio/products')) return json(products);
      if (url.includes('/courses')) return json([]);
      if (url.includes('/payments/sandbox-status')) return json({ checkoutAvailable: false });
      if (url.includes('/orders')) return json([]);
      return new Response('{}', { status: 404 });
    });
  };

  beforeEach(() => {
    vi.restoreAllMocks();
    activeView = 'studio';
    studioClassroom = baseClassroom;
  });

  it('keeps it out of the product list and the count, with no edit / archive / publish actions for it', async () => {
    install([accessProduct(), standard, accessProduct({ id: 'prod-access-old', status: 'ARCHIVED' })]);
    render(<StudioStore />);

    await waitFor(() => expect(screen.getByText('Gói PRO 30 ngày')).toBeInTheDocument());
    expect(screen.getByRole('heading', { name: 'Sản phẩm (1)' })).toBeInTheDocument();
    // only the standard product has product actions
    expect(screen.getAllByRole('button', { name: /Sửa/ })).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: 'Gỡ bán' })).toHaveLength(1);
    expect(screen.queryByRole('button', { name: 'Khôi phục' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Xuất bản' })).not.toBeInTheDocument();
  });

  it('shows a read-only "Gói vào lớp" card that links to Cài đặt lớp', async () => {
    install([accessProduct(), standard]);
    render(<StudioStore />);

    const card = await screen.findByTestId('class-access-card');
    expect(within(card).getByText('Gói vào lớp')).toBeInTheDocument();
    expect(card).toHaveTextContent('199.000đ / 30 ngày');
    const link = within(card).getByRole('link', { name: 'Quản lý ở Cài đặt lớp' });
    expect(link).toHaveAttribute('href', '/studio/classes/class-1/settings');
    expect(within(card).queryByRole('button')).not.toBeInTheDocument();
  });

  it('says so when the class has gone back to FREE (the product is archived, kept for history)', async () => {
    studioClassroom = { ...baseClassroom, accessType: 'FREE', accessProduct: null } as Classroom;
    install([accessProduct({ status: 'ARCHIVED' })]);
    render(<StudioStore />);

    const card = await screen.findByTestId('class-access-card');
    expect(card).toHaveTextContent('Lớp đang miễn phí');
    expect(screen.getByRole('heading', { name: 'Sản phẩm (0)' })).toBeInTheDocument();
  });

  it('shows no card for a class that never charged, and lifetime reads "trọn đời"', async () => {
    install([standard]);
    const plain = render(<StudioStore />);
    await waitFor(() => expect(screen.getByText('Gói PRO 30 ngày')).toBeInTheDocument());
    expect(screen.queryByTestId('class-access-card')).not.toBeInTheDocument();
    plain.unmount();

    install([accessProduct({ durationDays: 0 })]);
    render(<StudioStore />);
    expect(await screen.findByTestId('class-access-card')).toHaveTextContent('199.000đ / trọn đời');
  });
});

describe('StoreTab - the class-access product for learners', () => {
  const mount = async (classroom: Classroom, products: Product[] = [accessProduct(), standard], sandbox = true) => {
    activeView = 'store';
    storeClassroom = classroom;
    const posted: { url: string; body: any }[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      if (url.includes('/classes/class-1/products')) return json(products);
      if (url.includes('/payments/sandbox-status')) return sandbox ? json({ checkoutAvailable: true }) : new Response('{}', { status: 404 });
      if (url.includes('/me/orders')) return json([]);
      if (url.endsWith('/orders') && method === 'POST') {
        posted.push({ url, body: JSON.parse(String(init?.body)) });
        return json({ id: 'o1', orderNumber: 'ORD-7', status: 'PENDING', totalAmount: 199000, currency: 'VND', buyerId: 'u1', classId: 'class-1', provider: 'MOCK', createdAt: new Date().toISOString() });
      }
      return new Response('{}', { status: 404 });
    });
    render(<StoreTab />);
    await screen.findByText('Gói PRO 30 ngày');
    return posted;
  };

  beforeEach(() => {
    vi.restoreAllMocks();
    resetCheckoutAvailabilityCache();
    mockNavigate.mockReset();
    mockRefresh.mockClear();
    storeUser = { id: 'u1' };
    window.alert = vi.fn();
  });

  const nonMember = { ...baseClassroom, userRole: 'GUEST', isMember: false, isOwner: false, memberState: 'NONE' } as Classroom;

  it('offers a non-member "Mua để tham gia" on the class-access card (badge "Gói vào lớp") and orders it', async () => {
    const posted = await mount(nonMember);
    const card = screen.getByTestId('class-access-product');
    expect(within(card).getByText('Gói vào lớp', { selector: 'span span' })).toBeInTheDocument();
    expect(within(card).getByText('30 ngày')).toBeInTheDocument();

    await act(async () => { fireEvent.click(within(card).getByRole('button', { name: 'Mua để tham gia' })); });
    await waitFor(() => expect(posted).toHaveLength(1));
    expect(posted[0].body).toEqual(expect.objectContaining({ classId: 'class-1', productId: 'prod-access' }));
    expect(posted[0].body.inviteCode).toBeUndefined();
    await screen.findByRole('dialog', { name: 'Trạng thái thanh toán' });
    expect(mockRefresh).toHaveBeenCalled();
  });

  it('a lifetime product reads "trọn đời"', async () => {
    await mount(nonMember, [accessProduct({ durationDays: 0 }), standard]);
    expect(within(screen.getByTestId('class-access-product')).getByText('trọn đời')).toBeInTheDocument();
  });

  it('a lapsed member sees the expiry date and "Gia hạn"', async () => {
    const expiredAt = new Date(Date.now() - 86_400_000).toISOString();
    await mount({ ...nonMember, memberState: 'EXPIRED', accessExpiresAt: expiredAt } as Classroom);
    const card = screen.getByTestId('class-access-product');
    expect(within(card).getByRole('button', { name: 'Gia hạn' })).toBeEnabled();
    expect(card).toHaveTextContent('Gói thành viên đã hết hạn ngày');
  });

  it('an active paid member can extend ("Gia hạn thêm"); a member without an expiry cannot buy it at all', async () => {
    const soon = new Date(Date.now() + 10 * 86_400_000).toISOString();
    const member = { ...nonMember, isMember: true, userRole: 'STUDENT', memberState: 'ACTIVE' } as Classroom;
    await mount({ ...member, accessExpiresAt: soon } as Classroom);
    expect(within(screen.getByTestId('class-access-product')).getByRole('button', { name: 'Gia hạn thêm' })).toBeEnabled();
  });

  it('a member with no expiry (grandfathered / lifetime) is not offered a pointless purchase', async () => {
    await mount({ ...nonMember, isMember: true, userRole: 'STUDENT', memberState: 'ACTIVE', accessExpiresAt: null } as Classroom);
    const card = screen.getByTestId('class-access-product');
    expect(within(card).getByRole('button', { name: 'Đang có quyền không hạn' })).toBeDisabled();
  });

  it('the owner and staff never pay for the class access', async () => {
    await mount(baseClassroom); // OWNER
    expect(within(screen.getByTestId('class-access-product')).getByRole('button', { name: 'Không cần mua' })).toBeDisabled();
  });

  it('in a paid class a person who is not a member cannot buy the other products yet', async () => {
    await mount(nonMember);
    const standardCard = screen.getByText('Gói PRO 30 ngày').closest('[data-testid="store-product"]') as HTMLElement;
    const button = within(standardCard).getByRole('button', { name: 'Cần là thành viên' });
    expect(button).toBeDisabled();
    expect(standardCard).toHaveTextContent('Hãy mua gói vào lớp trước');
  });

  it('a FREE class keeps the plain "Nhận quyền lợi" (formerly "Mua ngay") on ordinary products', async () => {
    await mount({ id: 'class-1', ownerId: 'o', slug: 's', title: 't', status: 'ACTIVE', memberCount: 1, createdAt: '' } as Classroom, [standard]);
    expect(screen.getByRole('button', { name: 'Nhận quyền lợi' })).toBeEnabled();
  });

  it('says "Tạm chưa hỗ trợ thanh toán" on the class-access card where there is no payment rail', async () => {
    await mount(nonMember, [accessProduct(), standard], false);
    await waitFor(() => expect(within(screen.getByTestId('class-access-product')).getByRole('button', { name: 'Tạm chưa hỗ trợ thanh toán' })).toBeDisabled());
  });

  it('a guest is sent to sign in and back to the store', async () => {
    storeUser = null;
    await mount(nonMember);
    fireEvent.click(within(screen.getByTestId('class-access-product')).getByRole('button', { name: 'Mua để tham gia' }));
    expect(mockNavigate).toHaveBeenCalledWith('/login', { state: { from: { pathname: '/classes/demo-class/store' } } });
  });
});
