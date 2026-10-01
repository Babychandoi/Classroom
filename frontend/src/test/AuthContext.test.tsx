import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, act, waitFor } from '@testing-library/react';
import { AuthProvider, useAuth } from '../context/AuthContext';
import { setAccessToken } from '../api/client';

const fireVisibilityChange = (state: 'visible' | 'hidden') => {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true });
  document.dispatchEvent(new Event('visibilitychange'));
};

const TestConsumer: React.FC = () => {
  const { user, token, isLoading, isReconnecting, login, logout, refreshUser } = useAuth();
  return (
    <div>
      <div data-testid="loading">{isLoading ? 'loading' : 'idle'}</div>
      <div data-testid="reconnecting">{isReconnecting ? 'reconnecting' : 'idle'}</div>
      <div data-testid="user">{user ? user.fullName : 'anonymous'}</div>
      <div data-testid="token">{token || 'none'}</div>
      <button onClick={() => login('student@test.local', 'Password123!')}>Login</button>
      <button onClick={logout}>Logout</button>
      <button onClick={() => void refreshUser()}>RefreshUser</button>
    </div>
  );
};

/** Every test bootstraps against /auth/refresh on mount; this stub answers with a 401 (no cookie). */
const mockNoRefreshCookie = () =>
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    if (input.toString().includes('/auth/refresh')) {
      return new Response('{}', { status: 401 });
    }
    return new Response(JSON.stringify({ error: { code: 'NOT_FOUND', message: 'Not found' } }), { status: 404 });
  });

describe('AuthContext Behavior', () => {
  beforeEach(() => {
    localStorage.clear();
    setAccessToken(null);
    vi.restoreAllMocks();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('R8-06: starts loading, then settles idle with no user when there is no refresh cookie', async () => {
    mockNoRefreshCookie();

    render(
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>
    );

    expect(screen.getByTestId('loading')).toHaveTextContent('loading');

    await waitFor(() => {
      expect(screen.getByTestId('loading')).toHaveTextContent('idle');
    });
    expect(screen.getByTestId('user')).toHaveTextContent('anonymous');
    expect(screen.getByTestId('token')).toHaveTextContent('none');
  });

  it('ignores a legacy token left in localStorage after a reload', async () => {
    localStorage.setItem('token', 'stored-jwt-token');
    mockNoRefreshCookie();

    render(
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>
    );

    await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('idle'));
    expect(screen.getByTestId('user')).toHaveTextContent('anonymous');
    expect(screen.getByTestId('token')).toHaveTextContent('none');
  });

  it('R8-06: bootstrap success - a valid refresh cookie restores the session without any explicit login', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/auth/refresh')) {
        return new Response(
          JSON.stringify({ success: true, data: { token: 'bootstrapped-jwt-token' } }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url.includes('/me')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: { id: 'u1', email: 'user@test.local', fullName: 'Nguyen Van A', role: 'STUDENT', status: 'ACTIVE' },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    render(
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>
    );

    expect(screen.getByTestId('loading')).toHaveTextContent('loading');

    await waitFor(() => {
      expect(screen.getByTestId('user')).toHaveTextContent('Nguyen Van A');
      expect(screen.getByTestId('token')).toHaveTextContent('bootstrapped-jwt-token');
      expect(screen.getByTestId('loading')).toHaveTextContent('idle');
    });
  });

  it('R8-06: bootstrap failure - no refresh cookie leaves the user logged out without a flash-redirect before settling', async () => {
    mockNoRefreshCookie();

    render(
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>
    );

    // isLoading must be true for the whole bootstrap attempt, only becoming false once settled -
    // this is what lets a protected route show a loading state instead of redirecting immediately.
    expect(screen.getByTestId('loading')).toHaveTextContent('loading');
    await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('idle'));
    expect(screen.getByTestId('user')).toHaveTextContent('anonymous');
  });

  it('R9-05: a 429/5xx bootstrap failure (after retries) shows reconnecting instead of logged-out', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (input.toString().includes('/auth/refresh')) {
        return new Response('{"code":"RATE_LIMITED"}', { status: 429 });
      }
      return new Response('{}', { status: 404 });
    });

    render(
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>
    );

    await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('idle'), { timeout: 10000 });
    expect(screen.getByTestId('reconnecting')).toHaveTextContent('reconnecting');
    expect(screen.getByTestId('user')).toHaveTextContent('anonymous');
  }, 15000);

  it('performs login, keeps token only in memory, and updates state', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/auth/refresh')) {
        return new Response('{}', { status: 401 });
      }
      if (url.includes('/auth/login')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: { token: 'new-jwt-token-123' },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url.includes('/me')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: { id: 'u2', email: 'student@test.local', fullName: 'Le Thi B', role: 'STUDENT', status: 'ACTIVE' },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    render(
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>
    );

    await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('idle'));

    const loginButton = screen.getByText('Login');
    await act(async () => {
      loginButton.click();
    });

    await waitFor(() => {
      expect(localStorage.getItem('token')).toBeNull();
      expect(screen.getByTestId('user')).toHaveTextContent('Le Thi B');
      expect(screen.getByTestId('token')).toHaveTextContent('new-jwt-token-123');
    });
  });

  it('clears token and user on logout', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/auth/refresh')) return new Response('{}', { status: 401 });
      if (url.includes('/auth/logout')) return new Response(JSON.stringify({ success: true, data: {} }), { status: 200 });
      if (url.includes('/auth/login')) return new Response(JSON.stringify({ success: true, data: { token: 'active-token' } }), { status: 200 });
      return new Response(
        JSON.stringify({
          success: true,
          data: { id: 'u1', fullName: 'User One', email: 'one@test.local' },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    });

    render(
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>
    );

    await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('idle'));
    await act(async () => { screen.getByText('Login').click(); });

    await waitFor(() => {
      expect(screen.getByTestId('user')).toHaveTextContent('User One');
    });

    const logoutButton = screen.getByText('Logout');
    await act(async () => {
      logoutButton.click();
    });

    expect(localStorage.getItem('token')).toBeNull();
    expect(screen.getByTestId('user')).toHaveTextContent('anonymous');
    expect(screen.getByTestId('token')).toHaveTextContent('none');
  });

  it('R11-01: signed-in and idle (not reconnecting) - visibilitychange/online must not call /auth/refresh or /me again', async () => {
    let refreshCalls = 0;
    let meCalls = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/auth/refresh')) {
        refreshCalls++;
        return new Response(
          JSON.stringify({ success: true, data: { token: 'bootstrapped-jwt-token' } }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url.includes('/me')) {
        meCalls++;
        return new Response(
          JSON.stringify({
            success: true,
            data: { id: 'u1', email: 'user@test.local', fullName: 'Nguyen Van A', role: 'STUDENT', status: 'ACTIVE' },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    render(
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>
    );

    await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('idle'));
    expect(screen.getByTestId('reconnecting')).toHaveTextContent('idle');
    expect(refreshCalls).toBe(1);
    expect(meCalls).toBe(1);

    // Simulate the tab losing and regaining visibility, and the browser reporting 'online', while
    // the user is signed in and not reconnecting.
    await act(async () => {
      fireVisibilityChange('hidden');
      fireVisibilityChange('visible');
      window.dispatchEvent(new Event('online'));
    });

    expect(refreshCalls).toBe(1);
    expect(meCalls).toBe(1);
    expect(screen.getByTestId('user')).toHaveTextContent('Nguyen Van A');
  });

  it('R11-01: while reconnecting, visibilitychange/online retry the bootstrap immediately', async () => {
    let refreshCalls = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (input.toString().includes('/auth/refresh')) {
        refreshCalls++;
        return new Response('{"code":"RATE_LIMITED"}', { status: 429 });
      }
      return new Response('{}', { status: 404 });
    });

    render(
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>
    );

    await waitFor(() => expect(screen.getByTestId('reconnecting')).toHaveTextContent('reconnecting'), { timeout: 10000 });
    const callsBeforeRetry = refreshCalls;

    await act(async () => {
      fireVisibilityChange('visible');
    });

    await waitFor(() => expect(refreshCalls).toBeGreaterThan(callsBeforeRetry));
  }, 15000);

  it('R11-01: refreshUser keeps the session on a transient (non-401) failure', async () => {
    let meShouldFail = false;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/auth/refresh')) {
        return new Response(
          JSON.stringify({ success: true, data: { token: 'bootstrapped-jwt-token' } }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url.includes('/me')) {
        if (meShouldFail) {
          return new Response(JSON.stringify({ error: { code: 'BAD_GATEWAY', message: 'Upstream unavailable' } }), { status: 502 });
        }
        return new Response(
          JSON.stringify({
            success: true,
            data: { id: 'u1', email: 'user@test.local', fullName: 'Nguyen Van A', role: 'STUDENT', status: 'ACTIVE' },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    render(
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>
    );

    await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('idle'));
    expect(screen.getByTestId('user')).toHaveTextContent('Nguyen Van A');
    expect(screen.getByTestId('token')).toHaveTextContent('bootstrapped-jwt-token');

    // A subsequent transient failure via refreshUser() must not clear the session.
    meShouldFail = true;
    await act(async () => {
      screen.getByText('RefreshUser').click();
    });

    expect(screen.getByTestId('user')).toHaveTextContent('Nguyen Van A');
    expect(screen.getByTestId('token')).toHaveTextContent('bootstrapped-jwt-token');
  });
});
