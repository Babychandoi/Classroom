import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { StudioSegments } from '../pages/studio/StudioSegments';
import type { Classroom } from '../types';

/**
 * R8-11: the segment creation form used to always send exactly one rule, making the AND/OR
 * combinator selector meaningless. It must now support adding/removing multiple rules and submit
 * all of them together with the chosen combinator.
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

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: mockClassroom }),
}));

describe('StudioSegments — multi-rule support (R8-11)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('sends every configured rule with the chosen combinator on create', async () => {
    let capturedBody: any = null;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();
      if (url.includes('/segments') && method === 'GET') {
        return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
      }
      if (url.includes('/segments') && method === 'POST') {
        capturedBody = JSON.parse(String(init?.body));
        return new Response(JSON.stringify({ success: true, data: {} }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioSegments />);

    await waitFor(() => expect(screen.getByText('Tạo nhóm phân khúc')).toBeInTheDocument());
    fireEvent.click(screen.getByText('Tạo nhóm phân khúc'));

    await waitFor(() => expect(screen.getByText('Thêm điều kiện')).toBeInTheDocument());

    fireEvent.change(screen.getByPlaceholderText('VD: Học viên hoàn thành trên 5 bài học'), {
      target: { value: 'PRO lâu năm' },
    });

    // Add a second rule — the combinator selector should now appear since there is more than one.
    fireEvent.click(screen.getByText('Thêm điều kiện'));
    expect(screen.getByLabelText('Toán tử kết hợp điều kiện')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Tiêu chí điều kiện 2'), { target: { value: 'DAYS_SINCE_JOINED' } });
    fireEvent.change(screen.getByLabelText('Toán tử điều kiện 2'), { target: { value: 'GREATER_THAN_OR_EQUAL' } });
    fireEvent.change(screen.getByLabelText('Giá trị điều kiện 2'), { target: { value: '30' } });
    fireEvent.change(screen.getByLabelText('Toán tử kết hợp điều kiện'), { target: { value: 'OR' } });

    const form = screen.getByText('Tạo phân khúc').closest('form')!;
    await act(async () => {
      fireEvent.submit(form);
    });

    expect(capturedBody).not.toBeNull();
    expect(capturedBody.logicOperator).toBe('OR');
    expect(capturedBody.rules).toHaveLength(2);
    expect(capturedBody.rules[0]).toEqual({ criterion: 'IS_PRO', operator: 'EQUALS', value: 'true' });
    expect(capturedBody.rules[1]).toEqual({ criterion: 'DAYS_SINCE_JOINED', operator: 'GREATER_THAN_OR_EQUAL', value: '30' });
  });

  it('cannot remove the last remaining rule', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/segments')) {
        return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioSegments />);
    await waitFor(() => expect(screen.getByText('Tạo nhóm phân khúc')).toBeInTheDocument());
    fireEvent.click(screen.getByText('Tạo nhóm phân khúc'));

    await waitFor(() => expect(screen.getByLabelText('Xóa điều kiện 1')).toBeInTheDocument());
    expect(screen.getByLabelText('Xóa điều kiện 1')).toBeDisabled();
  });
});
