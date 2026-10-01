import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MyProfilePage } from '../pages/MyProfilePage';

// R13-05 (FR-12/D-05): "Hồ sơ của tôi" — edits fullName/bio/avatarUrl/profileVisibility via
// PUT /users/profile, with a Vietnamese explanation shown per visibility option.

const mockUser = {
  id: 'u1',
  fullName: 'Nguyen Van A',
  email: 'a@test.local',
  bio: 'Xin chào',
  avatarUrl: 'https://example.com/a.png',
  profileVisibility: 'PRIVATE' as const,
  role: 'STUDENT',
  status: 'ACTIVE',
};

const mockRefreshUser = vi.fn().mockResolvedValue(undefined);

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: mockUser, refreshUser: mockRefreshUser }),
}));

vi.mock('react-router-dom', () => ({
  Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a>,
}));

describe('MyProfilePage', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    mockRefreshUser.mockClear();
  });

  it('renders current profile values and all three visibility options with Vietnamese explanations', () => {
    render(<MyProfilePage />);

    expect(screen.getByDisplayValue('Nguyen Van A')).toBeInTheDocument();
    expect(screen.getByDisplayValue('Xin chào')).toBeInTheDocument();
    expect(screen.getByDisplayValue('https://example.com/a.png')).toBeInTheDocument();

    expect(screen.getByText('Riêng tư')).toBeInTheDocument();
    expect(screen.getByText('Trong lớp học')).toBeInTheDocument();
    expect(screen.getByText('Công khai')).toBeInTheDocument();
    // Each option carries an explanatory sentence, not just a label.
    expect(screen.getByText(/quản trị lớp/)).toBeInTheDocument();
  });

  it('submits PUT /users/profile with the edited fields and refreshes the session user', async () => {
    let capturedBody: any = null;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      if (url.includes('/users/profile') && init?.method === 'PUT') {
        capturedBody = JSON.parse(init.body as string);
        return new Response(JSON.stringify({ success: true, data: { ...mockUser, ...capturedBody } }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<MyProfilePage />);

    fireEvent.change(screen.getByDisplayValue('Nguyen Van A'), { target: { value: 'Nguyen Van B' } });
    fireEvent.click(screen.getByText('Công khai'));
    fireEvent.click(screen.getByText('Lưu hồ sơ'));

    await waitFor(() => expect(capturedBody).not.toBeNull());
    expect(capturedBody.fullName).toBe('Nguyen Van B');
    expect(capturedBody.profileVisibility).toBe('PUBLIC');
    await waitFor(() => expect(mockRefreshUser).toHaveBeenCalled());
  });
});
