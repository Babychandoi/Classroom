import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { LearnTab } from '../pages/classroom/LearnTab';
import type { Classroom, Course } from '../types';

/**
 * R14-12: a paid entitlement whose start date is still in the future (accessReason OWNED_UPCOMING)
 * must read "Bắt đầu từ dd/MM/yyyy" and must NOT invite the learner to buy the course again.
 */

const mockClassroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 1,
  createdAt: new Date().toISOString(),
};

const mockUser = { id: 'u1', fullName: 'Học viên', email: 'hv@test.local', role: 'STUDENT', status: 'ACTIVE' };

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: mockClassroom }),
  useNavigate: () => vi.fn(),
  Link: (props: { to: string; children?: React.ReactNode }) => React.createElement('a', { href: props.to }, props.children),
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: mockUser }),
}));

function paidCourse(overrides: Partial<Course>): Course {
  return {
    id: 'course-1',
    classId: 'class-1',
    title: 'Khóa nâng cao',
    accessMode: 'PURCHASE_REQUIRED',
    status: 'PUBLISHED',
    position: 0,
    canLearn: false,
    totalLessons: 4,
    completedLessons: 0,
    progressPercentage: 0,
    sections: [],
    ...overrides,
  } as Course;
}

function mockCourse(course: Course) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = input.toString();
    if (url.includes('/classes/class-1/courses')) {
      return new Response(JSON.stringify({ success: true, data: [course] }), { status: 200 });
    }
    if (url.includes('/courses/course-1')) {
      return new Response(JSON.stringify({ success: true, data: course }), { status: 200 });
    }
    return new Response('{}', { status: 404 });
  });
}

describe('LearnTab — access reason copy (R14-12)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    window.alert = vi.fn();
  });

  it('shows "Bắt đầu từ dd/MM/yyyy" and no purchase CTA for an OWNED_UPCOMING course', async () => {
    mockCourse(paidCourse({ accessReason: 'OWNED_UPCOMING', accessStartsAt: '2026-10-15T12:00:00Z', expiresAt: '2026-11-15T12:00:00Z' }));

    render(<LearnTab />);

    await waitFor(() => expect(screen.getAllByText('Bắt đầu từ 15/10/2026').length).toBeGreaterThan(0));
    expect(screen.getByText(/Bạn đã mua khóa học này/)).toBeInTheDocument();
    expect(screen.queryByText('Mua khóa học tại Cửa hàng')).not.toBeInTheDocument();
    expect(screen.queryByText('Khóa bảo vệ')).not.toBeInTheDocument();
  });

  it('still offers the store CTA and the locked badge for a never-purchased course', async () => {
    mockCourse(paidCourse({ accessReason: 'NOT_PURCHASED' }));

    render(<LearnTab />);

    await waitFor(() => expect(screen.getByText('Mua khóa học tại Cửa hàng')).toBeInTheDocument());
    expect(screen.getByText('Khóa bảo vệ')).toBeInTheDocument();
    expect(screen.queryByText(/Bắt đầu từ/)).not.toBeInTheDocument();
  });
});

/**
 * R19-12: once a course's product is archived ("gỡ bán") the store no longer lists it. A learner without access
 * must not be sent there (or invited to renew), and an existing buyer must still see when their access ends.
 */
describe('LearnTab — course whose product is no longer for sale (R19-12)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    window.alert = vi.fn();
  });

  it('says the course is not on sale, with no store link, for a learner who never bought it', async () => {
    mockCourse(paidCourse({ accessReason: 'NOT_PURCHASED', canPurchase: false, productStatus: 'ARCHIVED' }));

    render(<LearnTab />);

    await waitFor(() => expect(screen.getByText('Khóa học hiện không mở bán')).toBeInTheDocument());
    expect(screen.queryByText('Mua khóa học tại Cửa hàng')).not.toBeInTheDocument();
    expect(screen.queryByText('Gia hạn tại Cửa hàng')).not.toBeInTheDocument();
  });

  it('does not offer a renewal to a buyer whose access expired after the product was archived', async () => {
    mockCourse(paidCourse({
      accessReason: 'EXPIRED', expiresAt: '2026-08-10T12:00:00Z', canPurchase: false, productStatus: 'ARCHIVED',
    }));

    render(<LearnTab />);

    await waitFor(() => expect(screen.getByText('Sản phẩm hết hạn ngày 10/8/2026')).toBeInTheDocument());
    expect(screen.getByText('Khóa học hiện không mở bán')).toBeInTheDocument();
    expect(screen.queryByText('Gia hạn tại Cửa hàng')).not.toBeInTheDocument();
  });

  it('still shows the expiry to an existing buyer after the product was archived', async () => {
    mockCourse(paidCourse({
      canLearn: true, accessReason: 'OWNED', expiresAt: '2026-11-20T12:00:00Z', canPurchase: false, productStatus: 'ARCHIVED',
    }));

    render(<LearnTab />);

    await waitFor(() => expect(screen.getAllByText('Còn hạn đến 20/11/2026').length).toBeGreaterThan(0));
    expect(screen.getByText(/không thể gia hạn thêm/)).toBeInTheDocument();
    expect(screen.queryByText('Mua khóa học tại Cửa hàng')).not.toBeInTheDocument();
  });

  it('keeps the store CTA when the product is still for sale (canPurchase true) or the flag is absent', async () => {
    mockCourse(paidCourse({ accessReason: 'NOT_PURCHASED', canPurchase: true, productStatus: 'PUBLISHED' }));
    const first = render(<LearnTab />);
    await waitFor(() => expect(screen.getByText('Mua khóa học tại Cửa hàng')).toBeInTheDocument());
    expect(screen.queryByText('Khóa học hiện không mở bán')).not.toBeInTheDocument();
    first.unmount();

    vi.restoreAllMocks();
    mockCourse(paidCourse({ accessReason: 'EXPIRED', expiresAt: '2026-08-10T12:00:00Z' }));
    render(<LearnTab />);
    await waitFor(() => expect(screen.getByText('Gia hạn tại Cửa hàng')).toBeInTheDocument());
  });

  it('shows the owned expiry for a buyer of a product that is still on sale, without the "cannot renew" note', async () => {
    mockCourse(paidCourse({ canLearn: true, accessReason: 'OWNED', expiresAt: '2026-11-20T12:00:00Z', canPurchase: true }));

    render(<LearnTab />);

    await waitFor(() => expect(screen.getAllByText('Còn hạn đến 20/11/2026').length).toBeGreaterThan(0));
    expect(screen.queryByText(/không thể gia hạn thêm/)).not.toBeInTheDocument();
  });
});
