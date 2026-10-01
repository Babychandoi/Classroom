import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { StudioStore } from '../pages/studio/StudioStore';
import type { Classroom, Product } from '../types';

/**
 * R13-01: StudioStore's product edit form (title/description/price/duration), gated on
 * STORE:EDIT, PUTs to /products/{id} with the fields CommerceService.updateProduct accepts.
 */

const ownerClassroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 3,
  userRole: 'OWNER',
  studioPermissions: [],
  studioScopedPermissions: [],
  createdAt: new Date().toISOString(),
} as Classroom;

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom: ownerClassroom }),
}));

const product: Product = {
  id: 'prod-1',
  classId: 'class-1',
  title: 'Gói PRO 30 ngày',
  description: 'Mô tả gốc',
  status: 'PUBLISHED',
  price: 199000,
  currency: 'VND',
  durationDays: 30,
} as Product;

describe('StudioStore — edit product (R13-01)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('opens the edit modal pre-filled and PUTs the updated fields to /products/{id}', async () => {
    let putBody: any = null;

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      const method = (init?.method || 'GET').toUpperCase();

      if (url.includes('/studio/products')) {
        return new Response(JSON.stringify({ success: true, data: [product] }), { status: 200 });
      }
      if (url.includes('/courses')) {
        return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
      }
      if (url.includes('/payments/sandbox-status')) {
        return new Response(JSON.stringify({ success: true, data: { checkoutAvailable: false } }), { status: 200 });
      }
      if (url.includes('/orders')) {
        return new Response(JSON.stringify({ success: true, data: [] }), { status: 200 });
      }
      if (url.endsWith('/products/prod-1') && method === 'PUT') {
        putBody = JSON.parse((init?.body as string) ?? '{}');
        return new Response(JSON.stringify({ success: true, data: { ...product, ...putBody } }), { status: 200 });
      }
      return new Response('{}', { status: 404 });
    });

    render(<StudioStore />);

    await waitFor(() => expect(screen.getByText('Gói PRO 30 ngày')).toBeInTheDocument());

    fireEvent.click(screen.getByText('Sửa'));
    await waitFor(() => expect(screen.getByText('Sửa sản phẩm')).toBeInTheDocument());
    expect(screen.getByText(/giá mới chỉ áp dụng cho đơn hàng mới/i)).toBeInTheDocument();

    const titleInput = screen.getByDisplayValue('Gói PRO 30 ngày');
    fireEvent.change(titleInput, { target: { value: 'Gói PRO 60 ngày' } });

    fireEvent.click(screen.getByText('Lưu thay đổi'));

    await waitFor(() => expect(putBody).not.toBeNull());
    expect(putBody.title).toBe('Gói PRO 60 ngày');
    expect(putBody.durationDays).toBe(30);
  });
});
