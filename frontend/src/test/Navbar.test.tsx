import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Navbar } from '../components/Navbar';

/**
 * R17-01/R17-05: while the silent session restore is in flight the navbar must not offer "Đăng nhập" to
 * a person who is actually signed in (clicking it races the bootstrap), and the brand stays on one line
 * at phone width.
 */

vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
  Link: ({ children, to, className, role }: { children?: React.ReactNode; to: string; className?: string; role?: string }) => (
    <a href={to} className={className} role={role}>{children}</a>
  ),
}));

const mockUser = { id: 'u1', fullName: 'Student', email: 'student@test.local', role: 'STUDENT', status: 'ACTIVE' };
let authState: { user: typeof mockUser | null; isLoading: boolean } = { user: null, isLoading: false };

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ ...authState, logout: vi.fn(), quickLogin: vi.fn() }),
}));

describe('Navbar', () => {
  beforeEach(() => {
    authState = { user: null, isLoading: false };
  });

  it('offers neither "Đăng nhập" nor the account controls while the session is being restored', () => {
    authState = { user: null, isLoading: true };
    render(<Navbar />);

    expect(screen.queryByText('Đăng nhập')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Mở menu tài khoản' })).not.toBeInTheDocument();
  });

  it('shows "Đăng nhập" to a visitor once the bootstrap has settled without a session', () => {
    render(<Navbar />);
    expect(screen.getByText('Đăng nhập').closest('a')).toHaveAttribute('href', '/login');
  });

  it('shows the account controls to a restored session', () => {
    authState = { user: mockUser, isLoading: false };
    render(<Navbar />);

    const trigger = screen.getByRole('button', { name: 'Mở menu tài khoản' });
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('Đăng nhập')).not.toBeInTheDocument();

    // The account menu (profile card + logout) opens from the avatar and closes on Escape.
    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    const menu = screen.getByRole('menu', { name: 'Menu tài khoản' });
    expect(menu).toHaveTextContent('Student');
    expect(menu).toHaveTextContent('student@test.local');
    expect(screen.getByRole('menuitem', { name: 'Đăng xuất' })).toBeInTheDocument();
    expect(screen.getByText('Hồ sơ của tôi').closest('a')).toHaveAttribute('href', '/me/profile');

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('lists the personal shortcuts right under the profile card, before discovery, admin and privacy', () => {
    authState = { user: { ...mockUser, role: 'PLATFORM_ADMIN' }, isLoading: false };
    render(<Navbar />);
    fireEvent.click(screen.getByRole('button', { name: 'Mở menu tài khoản' }));
    const items = screen.getAllByRole('menuitem').map((el) => el.textContent?.trim());
    expect(items).toEqual([
      'Hồ sơ của tôi',
      'Lớp học của tôi',
      'Khóa học của tôi',
      'Sự kiện tham gia',
      'Khám phá lớp học',
      'Quản trị nền tảng',
      'Quyền riêng tư & dữ liệu',
      'Đăng xuất',
    ]);
    expect(screen.getByRole('menuitem', { name: 'Lớp học của tôi' })).toHaveAttribute('href', '/me/classes');
    expect(screen.getByRole('menuitem', { name: 'Khóa học của tôi' })).toHaveAttribute('href', '/me/courses');
    expect(screen.getByRole('menuitem', { name: 'Sự kiện tham gia' })).toHaveAttribute('href', '/me/events');
  });

  it('offers "Quản trị nền tảng" in the account menu to a PLATFORM_ADMIN only', () => {
    authState = { user: mockUser, isLoading: false };
    const { unmount } = render(<Navbar />);
    fireEvent.click(screen.getByRole('button', { name: 'Mở menu tài khoản' }));
    expect(screen.queryByText('Quản trị nền tảng')).not.toBeInTheDocument();
    unmount();

    authState = { user: { ...mockUser, role: 'PLATFORM_ADMIN' }, isLoading: false };
    render(<Navbar />);
    fireEvent.click(screen.getByRole('button', { name: 'Mở menu tài khoản' }));
    const link = screen.getByRole('menuitem', { name: 'Quản trị nền tảng' });
    expect(link).toHaveAttribute('href', '/admin');
  });

  it('keeps the brand on a single line and hides the duplicate "Khám phá lớp học" link on phones', () => {
    render(<Navbar />);

    const brand = screen.getByText('Lớp Học Trực Tuyến');
    expect(brand).toHaveClass('whitespace-nowrap');
    expect(brand).toHaveClass('truncate');
    expect(screen.getByText('Khám phá lớp học').closest('a')).toHaveClass('hidden', 'sm:inline-block');
  });
});
