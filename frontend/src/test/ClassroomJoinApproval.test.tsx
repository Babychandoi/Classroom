import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, act, within } from '@testing-library/react';
import { ClassroomLayout } from '../pages/ClassroomLayout';
import { ClassroomHeader } from '../components/ClassroomHeader';
import { AboutTab } from '../pages/classroom/AboutTab';
import { resetCheckoutAvailabilityCache } from '../api/payments';
import type { Classroom } from '../types';

/**
 * Join approval (docs/API-CREATE-CLASS.md §3): a class with requireApproval offers "Xin tham gia"; the join answers with
 * memberState PENDING, which shows a calm "chờ duyệt" status (member-only tabs stay locked) and a confirmable "Rút yêu cầu"
 * (DELETE /classes/{id}/join-request). Plus the class avatar / cover position and the category chip.
 */

const mockLocation = { pathname: '/classes/demo-class/feed', search: '', hash: '', state: null as unknown, key: 'k' };
let outletClassroom: Classroom;

vi.mock('react-router-dom', () => ({
  useParams: () => ({ slug: 'demo-class' }),
  useNavigate: () => vi.fn(),
  useLocation: () => mockLocation,
  useOutletContext: () => ({ classroom: outletClassroom, refreshClassroom: async () => {} }),
  Outlet: () => <div data-testid="outlet" />,
  NavLink: ({ children, to }: { children?: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
  Link: ({ children, to }: { children?: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

const mockUser = { id: 'u1', fullName: 'Học Viên', email: 'hv@test.local', role: 'USER', status: 'ACTIVE' };
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ user: mockUser, isLoading: false }) }));

const base: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  ownerName: 'Thầy Chủ',
  slug: 'demo-class',
  title: 'Lớp Duyệt Thành Viên',
  status: 'ACTIVE',
  memberCount: 3,
  isMember: false,
  isOwner: false,
  userRole: 'GUEST',
  memberState: 'NONE',
  visibility: 'PUBLIC',
  accessType: 'FREE',
  requireApproval: true,
  category: 'Ôn thi',
  createdAt: new Date().toISOString(),
} as Classroom;

let current: Classroom;
let calls: { method: string; url: string }[];
const json = (data: unknown) => new Response(JSON.stringify({ success: true, data }), { status: 200 });

function installFetch() {
  calls = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = input.toString();
    const method = (init?.method || 'GET').toUpperCase();
    calls.push({ method, url });
    if (url.includes('/classes/slug/demo-class')) return json(current);
    if (url.endsWith('/classes/class-1/join') && method === 'POST') {
      current = { ...current, memberState: 'PENDING' };
      return json(current);
    }
    if (url.endsWith('/classes/class-1/join-request') && method === 'DELETE') {
      current = { ...current, memberState: 'NONE' };
      return json(null);
    }
    if (url.endsWith('/classes/class-1/about')) return json({ id: 'a', classId: 'class-1', contentMarkdown: '', rulesMarkdown: '', sections: [] });
    return new Response('{}', { status: 404 });
  });
}

const lockedTab = (label: string) => screen.getByText(label).closest('[aria-disabled="true"]');

beforeEach(() => {
  vi.restoreAllMocks();
  resetCheckoutAvailabilityCache();
  mockLocation.pathname = '/classes/demo-class/feed';
  current = { ...base };
});
afterEach(() => vi.restoreAllMocks());

describe('ClassroomLayout - join approval', () => {
  it('offers "Xin tham gia" and, once requested, shows the pending status with the member tabs still locked', async () => {
    installFetch();
    render(<ClassroomLayout />);
    const join = await screen.findByRole('button', { name: /Xin tham gia/ });
    expect(screen.getByText('Cần duyệt')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Tham gia lớp ngay/ })).not.toBeInTheDocument();

    await act(async () => { fireEvent.click(join); });

    const pending = await screen.findByTestId('join-pending');
    expect(pending).toHaveTextContent('Đã gửi yêu cầu tham gia · chờ người dẫn dắt duyệt');
    expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/classes/class-1/join'))).toBe(true);
    expect(screen.queryByRole('button', { name: /Xin tham gia/ })).not.toBeInTheDocument();
    expect(screen.getByTestId('badge-pending')).toHaveTextContent('Chờ duyệt');
    expect(screen.queryByText('Đã tham gia')).not.toBeInTheDocument();
    for (const label of ['Khóa học', 'Thi', 'Tài liệu', 'Thành viên', 'Bảng xếp hạng']) expect(lockedTab(label)).toBeInTheDocument();
  });

  it('shows the pending status on load (memberState PENDING) and keeps the public content', async () => {
    current = { ...base, memberState: 'PENDING' };
    installFetch();
    render(<ClassroomLayout />);
    expect(await screen.findByTestId('join-pending')).toBeInTheDocument();
    expect(screen.getByTestId('outlet')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Xin tham gia|Tham gia lớp ngay/ })).not.toBeInTheDocument();
    expect(lockedTab('Khóa học')).toBeInTheDocument();
  });

  it('withdraws the request only after confirming, then offers "Xin tham gia" again', async () => {
    current = { ...base, memberState: 'PENDING' };
    installFetch();
    render(<ClassroomLayout />);
    const pending = await screen.findByTestId('join-pending');

    fireEvent.click(within(pending).getByRole('button', { name: 'Rút yêu cầu' }));
    const confirm = within(pending).getByRole('group', { name: 'Xác nhận rút yêu cầu tham gia' });
    fireEvent.click(within(confirm).getByRole('button', { name: 'Giữ yêu cầu' }));
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);
    expect(within(pending).queryByRole('group')).not.toBeInTheDocument();

    fireEvent.click(within(pending).getByRole('button', { name: 'Rút yêu cầu' }));
    await act(async () => {
      fireEvent.click(within(screen.getByRole('group', { name: 'Xác nhận rút yêu cầu tham gia' })).getByRole('button', { name: 'Rút yêu cầu' }));
    });

    expect(calls.some((c) => c.method === 'DELETE' && c.url.endsWith('/classes/class-1/join-request'))).toBe(true);
    await waitFor(() => expect(screen.queryByTestId('join-pending')).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: /Xin tham gia/ })).toBeInTheDocument();
  });

  it('a class without approval keeps the plain "Tham gia lớp ngay" prompt', async () => {
    current = { ...base, requireApproval: false };
    installFetch();
    render(<ClassroomLayout />);
    expect(await screen.findByRole('button', { name: /Tham gia lớp ngay/ })).toBeInTheDocument();
    expect(screen.queryByText('Cần duyệt')).not.toBeInTheDocument();
  });
});

describe('ClassroomHeader - class avatar, cover position, category', () => {
  it('shows the uploaded avatar and cover at their stored object-position, and the category chip', () => {
    render(<ClassroomHeader classroom={{ ...base, avatarUrl: 'https://cdn.test/a.png', avatarPosition: '20% 80%', coverUrl: 'https://cdn.test/c.png', coverPosition: '50% 30%' }} />);
    const avatar = screen.getByTestId('class-avatar-image');
    expect(avatar).toHaveAttribute('src', 'https://cdn.test/a.png');
    expect(avatar.style.objectPosition).toBe('20% 80%');
    const cover = screen.getByRole('img', { name: 'Ảnh bìa lớp học Lớp Duyệt Thành Viên' });
    expect(cover.style.objectPosition).toBe('50% 30%');
    expect(screen.getByTestId('class-category')).toHaveTextContent('Ôn thi');
  });

  it('falls back to the letter tile without an avatar (or when it fails to load) and ignores a malformed position', () => {
    const { unmount } = render(<ClassroomHeader classroom={{ ...base, category: null, coverUrl: 'https://cdn.test/c.png', coverPosition: 'center; x' }} />);
    expect(screen.queryByTestId('class-avatar-image')).not.toBeInTheDocument();
    expect(screen.getByText('L')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /Ảnh bìa/ }).style.objectPosition).toBe('');
    expect(screen.queryByTestId('class-category')).not.toBeInTheDocument();
    unmount();

    render(<ClassroomHeader classroom={{ ...base, avatarUrl: 'https://cdn.test/broken.png' }} />);
    fireEvent.error(screen.getByTestId('class-avatar-image'));
    expect(screen.queryByTestId('class-avatar-image')).not.toBeInTheDocument();
    expect(screen.getByText('L')).toBeInTheDocument();
  });
});

describe('AboutTab - category and approval facts', () => {
  it('lists the category chip and that the leader approves each member', async () => {
    outletClassroom = { ...base };
    installFetch();
    render(<AboutTab />);
    expect(await screen.findByTestId('about-category')).toHaveTextContent('Ôn thi');
    expect(screen.getByText('Người dẫn dắt duyệt từng người trước khi vào lớp')).toBeInTheDocument();
  });
});
