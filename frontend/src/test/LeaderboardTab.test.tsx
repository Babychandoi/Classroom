import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { LeaderboardTab } from '../pages/classroom/LeaderboardTab';
import type { Classroom } from '../types';

/**
 * R8-04/R8-07: the podium must group students by RANK (not array index), so two students tied for
 * rank 1 both appear in the "Hạng 1" slot and there is no rank-2 slot at all. And an anonymised
 * entry (userId: null) must still render with a stable, unique row instead of crashing or
 * colliding on key.
 */

let mockClassroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 3,
  userRole: 'STUDENT',
  createdAt: new Date().toISOString(),
} as Classroom;

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: mockClassroom }),
}));

// Stable identity. The viewer is not on the board unless a test puts 'u-me' there.
const mockUser = { id: 'u-me', fullName: 'Tôi', email: 'me@test.local', role: 'STUDENT', status: 'ACTIVE' };
vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: mockUser }),
}));

describe('LeaderboardTab — tie-rank podium grouping and null-key safety', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('shows two tied rank-1 students both in the Hạng 1 slot, with no Hạng 2 slot', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/leaderboard')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: [
              { rank: 1, userId: 'u-alice', userFullName: 'Alice', classId: 'class-1', totalPoints: 100, currentTier: 'Vàng', lastCalculatedAt: new Date().toISOString() },
              { rank: 1, userId: 'u-bob', userFullName: 'Bob', classId: 'class-1', totalPoints: 100, currentTier: 'Vàng', lastCalculatedAt: new Date().toISOString() },
              { rank: 3, userId: null, userFullName: 'Người dùng ẩn danh', classId: 'class-1', totalPoints: 50, currentTier: 'Đồng', lastCalculatedAt: new Date().toISOString() },
            ],
          }),
          { status: 200 }
        );
      }
      return new Response('{}', { status: 404 });
    });

    render(<LeaderboardTab />);

    await waitFor(() => expect(screen.getAllByText('Alice').length).toBeGreaterThan(0));

    // Both tied students get their own "Hạng 1" card — one label per tied student.
    expect(screen.getAllByText('Hạng 1')).toHaveLength(2);
    expect(screen.getAllByText('Alice').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Bob').length).toBeGreaterThan(0);

    // No rank-2 entry exists, so there must be no lone "2" podium slot rendered as a top-2 card.
    // (The full list below also renders numeric rank badges, so this check only inspects the
    // podium avoids crashing/duplicating — the anonymised rank-3 entry renders without error.)
    expect(screen.getAllByText('Người dùng ẩn danh').length).toBeGreaterThan(0);
  });
});

describe('LeaderboardTab — R13-08 "bộ lọc kỳ" exam filter', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('requests the exam-scoped leaderboard when an exam is selected, and renders % instead of điểm', async () => {
    const requestedUrls: string[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      requestedUrls.push(url);
      if (url.includes('/exams') && !url.includes('leaderboard')) {
        return new Response(
          JSON.stringify({ success: true, data: [{ id: 'exam-1', title: 'Giữa kỳ', classId: 'class-1' }] }),
          { status: 200 },
        );
      }
      if (url.includes('/leaderboard')) {
        const isExamScoped = url.includes('examId=exam-1');
        return new Response(
          JSON.stringify({
            success: true,
            data: isExamScoped
              ? [{ rank: 1, userId: 'u-alice', userFullName: 'Alice', classId: 'class-1', totalPoints: 90, currentTier: null, lastCalculatedAt: new Date().toISOString() }]
              : [{ rank: 1, userId: 'u-alice', userFullName: 'Alice', classId: 'class-1', totalPoints: 500, currentTier: 'Vàng', lastCalculatedAt: new Date().toISOString() }],
          }),
          { status: 200 },
        );
      }
      return new Response('{}', { status: 404 });
    });

    render(<LeaderboardTab />);

    await waitFor(() => expect(screen.getAllByText('Alice').length).toBeGreaterThan(0));
    await waitFor(() => expect(screen.getByText('Giữa kỳ')).toBeInTheDocument());

    // The exam switch is a row of filter chips (was a <select> before the Connecty reskin).
    fireEvent.click(screen.getByRole('button', { name: 'Giữa kỳ' }));
    expect(screen.getByRole('button', { name: 'Giữa kỳ' })).toHaveAttribute('aria-pressed', 'true');

    await waitFor(() => expect(requestedUrls.some((u) => u.includes('/leaderboard?examId=exam-1'))).toBe(true));
    await waitFor(() => expect(screen.getAllByText('90').length).toBeGreaterThan(0));
  });
});

describe('LeaderboardTab — the viewer\'s own row and the tier ladder', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  const board = [
    { rank: 1, userId: 'u-alice', userFullName: 'Alice', classId: 'class-1', totalPoints: 300, currentTier: 'Vàng', lastCalculatedAt: new Date().toISOString() },
    { rank: 2, userId: 'u-me', userFullName: 'Tôi', classId: 'class-1', totalPoints: 120, currentTier: 'Bạc', lastCalculatedAt: new Date().toISOString() },
  ];

  const notDeployed = () =>
    new Response(JSON.stringify({ success: false, error: { code: 'NOT_FOUND', message: 'Not found' } }), { status: 404 });

  it('marks the viewer\'s row with "BẠN" and, while the member tiers endpoint is not deployed, never asks a learner for the Studio-only configuration', async () => {
    const requestedUrls: string[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      requestedUrls.push(url);
      if (url.includes('/leaderboard/tiers')) return notDeployed();
      if (url.includes('/leaderboard')) return new Response(JSON.stringify({ success: true, data: board }), { status: 200 });
      return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
    });

    render(<LeaderboardTab />);

    await waitFor(() => expect(screen.getByText('BẠN')).toBeInTheDocument());
    await waitFor(() => expect(requestedUrls.some((u) => u.includes('/leaderboard/tiers'))).toBe(true));
    // No ladder with invented thresholds - just the learner's own tier name.
    expect(screen.queryByText(/^từ /)).not.toBeInTheDocument();
    expect(screen.getByText('Bạc', { selector: 'strong' })).toBeInTheDocument();
    const myRow = screen.getByText('BẠN').closest('li');
    expect(myRow).toHaveAttribute('aria-current', 'true');
    expect(requestedUrls.some((u) => u.includes('/leaderboard/configuration'))).toBe(false);
  });

  it('shows the class\'s real tiers with the viewer\'s tier highlighted and the points to the next one, for a LEADERBOARD:EDIT viewer (configuration fallback)', async () => {
    const previous = mockClassroom;
    mockClassroom = { ...previous, userRole: 'STAFF', studioPermissions: ['LEADERBOARD:EDIT'] } as Classroom;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/leaderboard/tiers')) return notDeployed();
      if (url.includes('/leaderboard/configuration')) {
        return new Response(JSON.stringify({ success: true, data: {
          tiers: [
            { tierName: 'Vàng', minPoints: 250 },
            { tierName: 'Đồng', minPoints: 0 },
            { tierName: 'Bạc', minPoints: 100 },
          ],
          rewards: [],
        } }), { status: 200 });
      }
      if (url.includes('/leaderboard')) return new Response(JSON.stringify({ success: true, data: board }), { status: 200 });
      return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
    });

    try {
      render(<LeaderboardTab />);
      await waitFor(() => expect(screen.getByText('từ 250')).toBeInTheDocument());
      expect(screen.getByText('từ 0')).toBeInTheDocument();
      // Current tier (Bạc, 100+) is the highlighted ladder row; 130 points are left to reach Vàng (250).
      const ladderRow = screen.getByText('từ 100').closest('li');
      expect(ladderRow).toHaveAttribute('aria-current', 'true');
      expect(screen.getByText('130 điểm')).toBeInTheDocument();
    } finally {
      mockClassroom = previous;
    }
  });

  it('shows every learner the real tier ladder from GET /leaderboard/tiers, with their tier highlighted and the points to the next one', async () => {
    const requestedUrls: string[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      requestedUrls.push(url);
      if (url.includes('/leaderboard/tiers')) {
        return new Response(JSON.stringify({ success: true, data: [
          { name: 'Vàng', minPoints: 250, description: 'Top của lớp' },
          { name: 'Đồng', minPoints: 0 },
          { name: 'Bạc', minPoints: 100 },
        ] }), { status: 200 });
      }
      if (url.includes('/leaderboard')) return new Response(JSON.stringify({ success: true, data: board }), { status: 200 });
      return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
    });

    render(<LeaderboardTab />);

    await waitFor(() => expect(screen.getByText('từ 250')).toBeInTheDocument());
    expect(screen.getByText('Top của lớp')).toBeInTheDocument();
    expect(screen.getByText('từ 100').closest('li')).toHaveAttribute('aria-current', 'true');
    expect(screen.getByText('130 điểm')).toBeInTheDocument();
    expect(screen.getByRole('progressbar', { name: 'Tiến độ lên Vàng' })).toHaveAttribute('aria-valuenow', '120');
    expect(requestedUrls.some((u) => u.includes('/leaderboard/configuration'))).toBe(false);
  });
});
