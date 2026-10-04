import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { LoginPage } from '../pages/LoginPage';

/**
 * R17-01: a signed-in person who lands on /login (or is bounced there by a race with the session
 * bootstrap) must be sent on rather than stranded on the form, and a successful login/register must
 * honor the page they came from (state.from) - including its query string.
 */

const mockNavigate = vi.fn();
let mockState: unknown = null;

vi.mock('react-router-dom', () => ({
  useNavigate: () => mockNavigate,
  useLocation: () => ({ pathname: '/login', search: '', hash: '', state: mockState, key: 'k' }),
  Link: ({ children, to }: { children?: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
  Navigate: ({ to, replace }: { to: string; replace?: boolean }) => (
    <div data-testid="redirect" data-to={to} data-replace={String(!!replace)} />
  ),
}));

const mockLogin = vi.fn();
const mockRegister = vi.fn();
const mockUser = { id: 'u1', fullName: 'Student', email: 'student@test.local', role: 'STUDENT', status: 'ACTIVE' };
let authUser: typeof mockUser | null = null;

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: authUser, login: mockLogin, register: mockRegister, quickLogin: vi.fn() }),
}));

describe('LoginPage return-to handling (R17-01)', () => {
  beforeEach(() => {
    mockNavigate.mockReset();
    mockLogin.mockReset().mockResolvedValue(undefined);
    mockRegister.mockReset().mockResolvedValue(undefined);
    mockState = null;
    authUser = null;
  });

  it('redirects an already signed-in person to the page they wanted (replace), not leaving the form up', () => {
    authUser = mockUser;
    mockState = { from: { pathname: '/classes/demo-class/feed', search: '', hash: '' } };
    render(<LoginPage />);

    const redirect = screen.getByTestId('redirect');
    expect(redirect).toHaveAttribute('data-to', '/classes/demo-class/feed');
    expect(redirect).toHaveAttribute('data-replace', 'true');
    expect(screen.queryByLabelText('Email')).not.toBeInTheDocument();
  });

  it('falls back to the class list for a signed-in person who came to /login directly', () => {
    authUser = mockUser;
    render(<LoginPage />);
    expect(screen.getByTestId('redirect')).toHaveAttribute('data-to', '/classes');
  });

  it('shows the form to a signed-out visitor and returns to state.from (with its query) after login', async () => {
    mockState = { from: { pathname: '/classes/demo-class/exams/e1/result', search: '?attemptId=a1', hash: '' } };
    render(<LoginPage />);

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'student@test.local' } });
    fireEvent.change(screen.getByLabelText('Mật khẩu'), { target: { value: 'secret-pass' } });
    fireEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));

    await waitFor(() => expect(mockLogin).toHaveBeenCalledWith('student@test.local', 'secret-pass'));
    await waitFor(() =>
      expect(mockNavigate).toHaveBeenCalledWith('/classes/demo-class/exams/e1/result?attemptId=a1', { replace: true }),
    );
  });

  it('honors state.from after registering too', async () => {
    mockState = { from: { pathname: '/classes/demo-class/store' } };
    render(<LoginPage />);

    fireEvent.click(screen.getByRole('button', { name: 'Đăng ký miễn phí' }));
    fireEvent.change(screen.getByLabelText('Họ và tên'), { target: { value: 'New Person' } });
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'new@test.local' } });
    fireEvent.change(screen.getByLabelText('Mật khẩu'), { target: { value: 'secret-pass' } });
    fireEvent.click(screen.getByRole('button', { name: 'Tạo tài khoản' }));

    await waitFor(() => expect(mockRegister).toHaveBeenCalledWith('new@test.local', 'secret-pass', 'New Person'));
    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/classes/demo-class/store', { replace: true }));
  });

  it('opens in register mode when the top bar sends state.mode=register', () => {
    mockState = { mode: 'register' };
    render(<LoginPage />);
    expect(screen.getByLabelText('Họ và tên')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Tạo tài khoản' })).toBeInTheDocument();
  });

  it('shows a login failure inline and keeps the form', async () => {
    mockLogin.mockRejectedValue(new Error('Email hoặc mật khẩu không đúng'));
    render(<LoginPage />);
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'a@test.local' } });
    fireEvent.change(screen.getByLabelText('Mật khẩu'), { target: { value: 'wrong-pass' } });
    fireEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Email hoặc mật khẩu không đúng');
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('toggles password visibility', () => {
    render(<LoginPage />);
    const input = screen.getByLabelText('Mật khẩu');
    expect(input).toHaveAttribute('type', 'password');
    fireEvent.click(screen.getByRole('button', { name: 'Hiện mật khẩu' }));
    expect(input).toHaveAttribute('type', 'text');
  });

  it.each([
    ['a protocol-relative URL', '//evil.example/steal'],
    ['/login itself', '/login'],
    ['a non-path value', 'javascript:alert(1)'],
  ])('ignores an unsafe state.from (%s) and lands on the class list', async (_label, pathname) => {
    mockState = { from: { pathname } };
    render(<LoginPage />);

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'student@test.local' } });
    fireEvent.change(screen.getByLabelText('Mật khẩu'), { target: { value: 'secret-pass' } });
    fireEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));

    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/classes', { replace: true }));
  });
});
