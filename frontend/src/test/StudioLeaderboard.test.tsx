import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { StudioLeaderboard } from '../pages/studio/StudioLeaderboard';
import type { Classroom, Exam } from '../types';

/**
 * R8-05: the Studio leaderboard configuration page — renders the current tiers/reward rules
 * (GET .../leaderboard/configuration) and saves the edited form as the shape
 * LeaderboardService.configure/LeaderboardConfigRequest expects (PUT .../leaderboard/configuration).
 */

const mockClassroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 3,
  userRole: 'OWNER',
  createdAt: new Date().toISOString(),
} as Classroom;

const exam: Exam = {
  id: 'exam-1',
  classId: 'class-1',
  title: 'Kỳ thi giữa kỳ',
  durationMinutes: 45,
  attemptLimit: 1,
  audienceScope: 'ALL',
  status: 'PUBLISHED',
  passScore: 50,
  canEnter: false,
  userAttemptsCount: 0,
  createdAt: new Date().toISOString(),
} as Exam;

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: mockClassroom }),
}));

describe('StudioLeaderboard — render and save (R8-05)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders the existing configuration and saves an edited tier/reward payload', async () => {
    let capturedBody: any = null;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();

      if (url.includes('/leaderboard/configuration') && method === 'GET') {
        return new Response(
          JSON.stringify({
            success: true,
            data: {
              tiers: [{ tierName: 'Bạc', minPoints: 100, badgeUrl: null, description: null }],
              rewards: [{ examId: 'exam-1', minExamScore: 50, rewardPoints: 10 }],
            },
          }),
          { status: 200 }
        );
      }
      if (url.includes('/leaderboard/configuration') && method === 'PUT') {
        capturedBody = JSON.parse(String(init?.body));
        return new Response(JSON.stringify({ success: true, data: { message: 'ok' } }), { status: 200 });
      }
      if (url.includes('/classes/class-1/exams')) {
        return new Response(JSON.stringify({ success: true, data: [exam] }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioLeaderboard />);

    await waitFor(() => expect(screen.getByDisplayValue('Bạc')).toBeInTheDocument());

    // Add a new tier and a new reward rule, then submit.
    fireEvent.click(screen.getByText('Thêm bậc thành tích'));
    const tierNameInputs = screen.getAllByLabelText(/^Tên bậc/);
    fireEvent.change(tierNameInputs[tierNameInputs.length - 1], { target: { value: 'Vàng' } });
    const tierPointsInputs = screen.getAllByLabelText(/^Điểm tối thiểu bậc/);
    fireEvent.change(tierPointsInputs[tierPointsInputs.length - 1], { target: { value: '500' } });

    const form = screen.getByText('Lưu cấu hình').closest('form')!;
    await act(async () => {
      fireEvent.submit(form);
    });

    await waitFor(() => expect(capturedBody).not.toBeNull());
    expect(capturedBody.tiers).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ tierName: 'Bạc', minPoints: 100 }),
        expect.objectContaining({ tierName: 'Vàng', minPoints: 500 }),
      ])
    );
    expect(capturedBody.rewards).toEqual([{ examId: 'exam-1', minExamScore: 50, rewardPoints: 10 }]);
  });
});
