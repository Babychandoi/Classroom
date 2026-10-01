import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { StudioExams } from '../pages/studio/StudioExams';
import type { Classroom, Exam } from '../types';

/**
 * R8-03: a staff member holding EXAM:PUBLISH but NOT EXAM:EDIT must still be able to publish a
 * drafted exam — the "Công bố" button used to be rendered only inside the EDIT-gated authoring
 * block, so a PUBLISH-only staff member never saw it despite the server (ExamService.publishExam)
 * happily authorizing them.
 */

const publishOnlyClassroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 3,
  userRole: 'STAFF',
  studioPermissions: ['EXAM:PUBLISH'],
  studioScopedPermissions: [],
  createdAt: new Date().toISOString(),
} as Classroom;

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: publishOnlyClassroom }),
  useNavigate: () => vi.fn(),
}));

const draftExam: Exam = {
  id: 'exam-1',
  classId: 'class-1',
  title: 'Kỳ thi giữa kỳ',
  durationMinutes: 45,
  attemptLimit: 1,
  audienceScope: 'ALL',
  status: 'DRAFT',
  passScore: 50,
  canEnter: false,
  userAttemptsCount: 0,
  questionCount: 3,
  createdAt: new Date().toISOString(),
} as Exam;

describe('StudioExams — PUBLISH-only staff can publish without EDIT (R8-03)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('shows the Công bố button for a PUBLISH-only staff member, without the EDIT authoring block', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/classes/class-1/exams')) {
        return new Response(JSON.stringify({ success: true, data: [draftExam] }), { status: 200 });
      }
      if (url.includes('/courses') || url.includes('/segments')) {
        return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioExams />);

    await waitFor(() => expect(screen.getByText('Kỳ thi giữa kỳ')).toBeInTheDocument());

    // PUBLISH-only: the publish button is visible even though EDIT is not granted.
    expect(screen.getByText('Công bố')).toBeInTheDocument();
    // The EDIT-gated authoring affordance must not appear for this staff member.
    expect(screen.queryByText('Soạn câu hỏi và công bố')).not.toBeInTheDocument();
  });
});
