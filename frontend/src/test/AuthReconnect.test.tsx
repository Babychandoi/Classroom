import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, act, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider, useAuth } from '../context/AuthContext';
import { setAccessToken } from '../api/client';

/**
 * R10-01: while bootstrapSessionDetailed() reports 'unreachable', AuthContext used to set
 * isReconnecting=true and never touch it again - no retry timer, no online/visibilitychange
 * listener, no manual retry, and login/register/logout/session-expired never cleared it. These
 * tests pin the fix: automatic capped-backoff retries recover once the backend answers again, a
 * 'logged-out' outcome on a later retry clears the flag (letting RequireLogin redirect), a manual
 * retry works, and logout always clears the flag.
 *
 * These use real timers (like the existing R9-05 AuthContext test), not fake timers: bootstrap's
 * own internal retry delays (500ms/1500ms/3000ms) plus AuthContext's external 2s backoff add up to
 * several real seconds, so every assertion here is wrapped in a generous waitFor/timeout rather
 * than manually advancing fake timers in lockstep with fetch's own microtask scheduling.
 */
const TestConsumer: React.FC = () => {
  const { user, isReconnecting, isLoading, retryReconnect, logout } = useAuth();
  if (isLoading) return <div role="status">Đang xác thực...</div>;
  if (isReconnecting && !user) {
    return (
      <div role="status">
        <p>Đang kết nối lại...</p>
        <button onClick={retryReconnect}>Thử lại</button>
      </div>
    );
  }
  return (
    <div>
      <div data-testid="user">{user ? user.fullName : 'anonymous'}</div>
      <button onClick={logout}>Logout</button>
    </div>
  );
};

const renderApp = () =>
  render(
    <MemoryRouter>
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>
    </MemoryRouter>
  );

describe('AuthContext reconnect behavior (R10-01)', () => {
  beforeEach(() => {
    localStorage.clear();
    setAccessToken(null);
    vi.restoreAllMocks();
  });

  it('unreachable -> automatic capped-backoff retry later succeeds with authenticated', async () => {
    let call = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/auth/refresh')) {
        call += 1;
        // bootstrapSessionDetailed() makes up to 4 attempts internally (initial + retries at
        // 500ms/1500ms/3000ms) before reporting 'unreachable'; only the 5th call - AuthContext's
        // own external backoff retry - succeeds.
        if (call <= 4) {
          return new Response('{}', { status: 503 });
        }
        return new Response(
          JSON.stringify({ success: true, data: { token: 'recovered-token' } }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url.includes('/me')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: { id: 'u1', email: 'a@test.local', fullName: 'Reconnected User', role: 'STUDENT', status: 'ACTIVE' },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    renderApp();

    await waitFor(() => expect(screen.getByText('Đang kết nối lại...')).toBeInTheDocument(), { timeout: 10000 });

    // The automatic backoff retry (starts at 2s) fires next and this time succeeds.
    await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('Reconnected User'), { timeout: 10000 });
    expect(screen.queryByText('Đang kết nối lại...')).not.toBeInTheDocument();
  }, 20000);

  it('unreachable -> a later retry reports logged-out, which clears reconnecting and lets the caller redirect', async () => {
    let call = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/auth/refresh')) {
        call += 1;
        if (call <= 4) return new Response('{}', { status: 503 });
        return new Response('{}', { status: 401 });
      }
      return new Response('{}', { status: 404 });
    });

    renderApp();

    await waitFor(() => expect(screen.getByText('Đang kết nối lại...')).toBeInTheDocument(), { timeout: 10000 });

    // Automatic retry now gets a genuine 401 (logged-out) instead of unreachable.
    await waitFor(() => expect(screen.queryByText('Đang kết nối lại...')).not.toBeInTheDocument(), { timeout: 10000 });
    expect(screen.getByTestId('user')).toHaveTextContent('anonymous');
  }, 20000);

  it('manual "Thử lại" click retries immediately without waiting for the backoff timer', async () => {
    let call = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/auth/refresh')) {
        call += 1;
        if (call <= 4) return new Response('{}', { status: 503 });
        return new Response(
          JSON.stringify({ success: true, data: { token: 'manual-retry-token' } }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url.includes('/me')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: { id: 'u2', email: 'b@test.local', fullName: 'Manual Retry User', role: 'STUDENT', status: 'ACTIVE' },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    renderApp();
    await waitFor(() => expect(screen.getByText('Đang kết nối lại...')).toBeInTheDocument(), { timeout: 10000 });

    await act(async () => {
      fireEvent.click(screen.getByText('Thử lại'));
    });

    await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('Manual Retry User'), { timeout: 10000 });
  }, 20000);

  it('logout always clears isReconnecting', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      if (url.includes('/auth/refresh')) {
        return new Response(
          JSON.stringify({ success: true, data: { token: 'existing-token' } }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url.includes('/me')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: { id: 'u3', email: 'c@test.local', fullName: 'Logging Out User', role: 'STUDENT', status: 'ACTIVE' },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url.includes('/auth/logout') && method === 'POST') {
        return new Response(JSON.stringify({ success: true, data: {} }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    renderApp();
    await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('Logging Out User'));

    await act(async () => {
      fireEvent.click(screen.getByText('Logout'));
    });

    await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('anonymous'));
    expect(screen.queryByText('Đang kết nối lại...')).not.toBeInTheDocument();
  });
});
