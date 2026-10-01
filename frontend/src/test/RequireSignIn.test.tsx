import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { RequireSignIn } from '../components/RequireSignIn';

/**
 * R18-10: a guest on a member-only page gets a friendly sign-in prompt (no request fired, no raw 401 error);
 * a signed-in person gets the page; the bootstrap-in-progress and backend-unreachable states are not mistaken
 * for "signed out".
 */

const auth = {
  user: null as null | { id: string },
  isLoading: false,
  isReconnecting: false,
  retryReconnect: vi.fn(),
};

vi.mock('../context/AuthContext', () => ({
  useAuth: () => auth,
}));

const Probe: React.FC = () => {
  React.useEffect(() => { void fetch('/api/v1/classes/c1/courses'); }, []);
  return <p>Nội dung dành cho thành viên</p>;
};

function renderAt(path = '/classes/demo/learn?tab=1') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/classes/:slug/learn" element={<RequireSignIn><Probe /></RequireSignIn>} />
        <Route path="/login" element={<p>Trang đăng nhập</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('RequireSignIn (R18-10)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    auth.user = null;
    auth.isLoading = false;
    auth.isReconnecting = false;
    auth.retryReconnect.mockClear();
  });

  it('shows a sign-in prompt to a guest and never renders (or requests for) the member-only page', () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    renderAt();

    expect(screen.getByRole('heading', { name: 'Đăng nhập để xem nội dung này' })).toBeInTheDocument();
    expect(screen.queryByText('Nội dung dành cho thành viên')).not.toBeInTheDocument();
    expect(screen.queryByText(/Chưa đăng nhập hoặc phiên đã hết hạn/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Thử lại' })).not.toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(screen.getByRole('link', { name: /Đăng nhập/ })).toHaveAttribute('href', '/login');
  });

  it('renders the page for a signed-in person', () => {
    auth.user = { id: 'u1' };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}'));
    renderAt();
    expect(screen.getByText('Nội dung dành cho thành viên')).toBeInTheDocument();
    expect(screen.queryByText('Đăng nhập để xem nội dung này')).not.toBeInTheDocument();
  });

  it('waits for the session bootstrap instead of flashing the prompt at a signed-in person', () => {
    auth.isLoading = true;
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    renderAt();
    expect(screen.getByRole('status')).toHaveTextContent('Đang xác thực');
    expect(screen.queryByText('Đăng nhập để xem nội dung này')).not.toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('says it is reconnecting (with a retry) when the backend cannot be reached, not that the visitor is signed out', () => {
    auth.isReconnecting = true;
    renderAt();
    expect(screen.getByRole('status')).toHaveTextContent('Đang kết nối lại');
    expect(screen.queryByText('Đăng nhập để xem nội dung này')).not.toBeInTheDocument();
    screen.getByRole('button', { name: 'Thử lại' }).click();
    expect(auth.retryReconnect).toHaveBeenCalledTimes(1);
  });
});
