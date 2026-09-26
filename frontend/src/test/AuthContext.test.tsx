import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, act, waitFor } from '@testing-library/react';
import { AuthProvider, useAuth } from '../context/AuthContext';
import { setAccessToken } from '../api/client';

const TestConsumer: React.FC = () => {
  const { user, token, isLoading, login, logout } = useAuth();
  return (
    <div>
      <div data-testid="loading">{isLoading ? 'loading' : 'idle'}</div>
      <div data-testid="user">{user ? user.fullName : 'anonymous'}</div>
      <div data-testid="token">{token || 'none'}</div>
      <button onClick={() => login('student@test.local', 'Password123!')}>Login</button>
      <button onClick={logout}>Logout</button>
    </div>
  );
};

describe('AuthContext Behavior', () => {
  beforeEach(() => {
    localStorage.clear();
    setAccessToken(null);
    vi.restoreAllMocks();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('initializes in idle state without token when localStorage is empty', async () => {
    render(
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>
    );

    expect(screen.getByTestId('loading')).toHaveTextContent('idle');
    expect(screen.getByTestId('user')).toHaveTextContent('anonymous');
    expect(screen.getByTestId('token')).toHaveTextContent('none');
  });

  it('ignores a legacy token left in localStorage after a reload', async () => {
    localStorage.setItem('token', 'stored-jwt-token');

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/me')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: { id: 'u1', email: 'user@test.local', fullName: 'Nguyen Van A', role: 'STUDENT', status: 'ACTIVE' },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response(JSON.stringify({ error: { code: 'NOT_FOUND', message: 'Not found' } }), { status: 404 });
    });

    render(
      <AuthProvider>
        <TestConsumer />
      </AuthProvider>
    );

    expect(screen.getByTestId('user')).toHaveTextContent('anonymous');
    expect(screen.getByTestId('token')).toHaveTextContent('none');
  });

  it('performs login, keeps token only in memory, and updates state', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
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
      if (input.toString().includes('/auth/login')) return new Response(JSON.stringify({ success: true, data: { token: 'active-token' } }), { status: 200 });
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
});
