import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { api, ApiException } from '../api/client';

describe('Store & Checkout Flow Behavior', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('fetches published products for classroom store', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/classes/class-1/products')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: [
              {
                id: 'prod-1',
                title: 'Gói PRO 30 ngày',
                description: 'Toàn quyền truy cập nội dung PRO',
                price: 299000,
                currency: 'VND',
                durationDays: 30,
                status: 'PUBLISHED',
              },
            ],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    const products = await api.get<any[]>('/classes/class-1/products');
    expect(products).toHaveLength(1);
    expect(products[0].id).toBe('prod-1');
    expect(products[0].price).toBe(299000);
    expect(products[0].durationDays).toBe(30);
  });

  it('creates purchase order with client-supplied idempotency key', async () => {
    let capturedBody: any = null;

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();
      if (url.includes('/orders')) {
        capturedBody = JSON.parse(init?.body as string);
        return new Response(
          JSON.stringify({
            success: true,
            data: {
              id: 'ord-123',
              orderNumber: 'ORD-TEST-001',
              buyerId: 'student-1',
              classId: 'class-1',
              status: 'PENDING',
              totalAmount: 299000,
              currency: 'VND',
              checkoutUrl: 'https://checkout.sandbox/pay/ORD-TEST-001',
            },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    const idempotencyKey = 'checkout-key-unique-987';
    const order = await api.post<any>('/orders', {
      classId: 'class-1',
      productId: 'prod-1',
      idempotencyKey,
    });

    expect(order.id).toBe('ord-123');
    expect(order.orderNumber).toBe('ORD-TEST-001');
    expect(order.status).toBe('PENDING');
    expect(capturedBody.idempotencyKey).toBe(idempotencyKey);
  });

  it('handles access denial when user is not a member of the class', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      return new Response(
        JSON.stringify({
          success: false,
          error: {
            code: 'FORBIDDEN',
            message: 'Bạn chưa là thành viên của lớp học này',
          },
        }),
        { status: 403, headers: { 'Content-Type': 'application/json' } }
      );
    });

    await expect(
      api.post('/orders', {
        classId: 'other-class',
        productId: 'prod-1',
      })
    ).rejects.toThrow(ApiException);

    try {
      await api.post('/orders', {
        classId: 'other-class',
        productId: 'prod-1',
      });
    } catch (e: any) {
      expect(e.code).toBe('FORBIDDEN');
      expect(e.message).toBe('Bạn chưa là thành viên của lớp học này');
    }
  });

  it('checks sandbox status and falls back gracefully when disabled', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/payments/sandbox-status')) {
        return new Response(
          JSON.stringify({
            success: false,
            error: { code: 'NOT_FOUND', message: 'Sandbox not active' },
          }),
          { status: 404, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response('{}', { status: 404 });
    });

    let sandboxAvailable = false;
    try {
      const res = await api.get<{ sandboxAvailable: boolean }>('/payments/sandbox-status');
      sandboxAvailable = !!res?.sandboxAvailable;
    } catch {
      sandboxAvailable = false;
    }

    expect(sandboxAvailable).toBe(false);
  });
});
