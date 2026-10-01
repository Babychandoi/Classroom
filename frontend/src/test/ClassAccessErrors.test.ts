import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { api, ApiException } from '../api/client';
import {
  FRIENDLY_CODE_MESSAGES,
  INVITE_REQUIRED,
  MEMBERSHIP_EXPIRED,
  MEMBERSHIP_EXPIRED_EVENT,
  PAYMENT_REQUIRED,
  friendlyMessageFor,
} from '../api/errorMessages';
import { accessProductFromError, isInviteRequired, isMembershipExpired, isPaymentRequired } from '../api/errors';
import { accessPriceLabel, daysUntil, durationLabel, formatDate, formatDateTime, formatDong } from '../api/format';

/**
 * D-19: ApiException exposes `code` and `details`, the three new class-access codes read as a friendly Vietnamese sentence on every
 * screen that shows `err.message`, and PAYMENT_REQUIRED hands the UI the product to buy.
 */

const errorResponse = (status: number, error: Record<string, unknown>) =>
  new Response(JSON.stringify({ success: false, error }), { status, headers: { 'Content-Type': 'application/json' } });

describe('ApiException for the class-access error codes (D-19)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('keeps code, status, requestId and details on the exception', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      errorResponse(402, {
        code: PAYMENT_REQUIRED,
        message: 'Lớp học trả phí; cần thanh toán để tham gia',
        requestId: 'req-1',
        details: { classId: 'c1', accessType: 'PAID', accessProduct: { id: 'p1', price: 199000, currency: 'VND', durationDays: 30, lifetime: false } },
      }),
    );

    const err: any = await api.post('/classes/c1/join').catch((e) => e);
    expect(err).toBeInstanceOf(ApiException);
    expect(err.code).toBe('PAYMENT_REQUIRED');
    expect(err.status).toBe(402);
    expect(err.requestId).toBe('req-1');
    expect(err.details).toEqual(expect.objectContaining({ classId: 'c1', accessType: 'PAID' }));
    expect(isPaymentRequired(err)).toBe(true);
    expect(isInviteRequired(err)).toBe(false);
  });

  it.each([
    [INVITE_REQUIRED, 403, 'Lớp riêng tư, cần mã mời', /lớp riêng tư/i],
    [PAYMENT_REQUIRED, 402, 'Lớp học trả phí; cần thanh toán để tham gia', /có thu phí/i],
    [MEMBERSHIP_EXPIRED, 403, 'Quyền truy cập lớp học của bạn đã hết hạn', /đã hết hạn/i],
  ])('%s gets a friendly Vietnamese message, whatever the server text was', async (code, status, serverMessage, expected) => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(errorResponse(status, { code, message: serverMessage }));

    const err: any = await api.get('/classes/c1/posts').catch((e) => e);
    expect(err.code).toBe(code);
    expect(err.message).toBe(FRIENDLY_CODE_MESSAGES[code]);
    expect(err.message).toMatch(expected);
    expect(err.message).not.toMatch(/failed to fetch|phiên đã hết hạn/i);
  });

  it('leaves the message of every other error untouched and has no details', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(errorResponse(400, { code: 'BAD_REQUEST', message: 'Giá phải lớn hơn 0' }));

    const err: any = await api.put('/classes/c1/access', { accessType: 'PAID' }).catch((e) => e);
    expect(err.message).toBe('Giá phải lớn hơn 0');
    expect(err.details).toBeUndefined();
  });

  it('announces MEMBERSHIP_EXPIRED on window so the open classroom can re-read its state (and only that code)', async () => {
    const listener = vi.fn();
    window.addEventListener(MEMBERSHIP_EXPIRED_EVENT, listener);
    try {
      vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(errorResponse(403, { code: 'FORBIDDEN', message: 'no' }));
      await api.get('/classes/c1/posts').catch(() => undefined);
      expect(listener).not.toHaveBeenCalled();

      vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(errorResponse(403, { code: MEMBERSHIP_EXPIRED, message: 'x' }));
      const err: any = await api.get('/classes/c1/posts').catch((e) => e);
      expect(isMembershipExpired(err)).toBe(true);
      expect(listener).toHaveBeenCalledTimes(1);
    } finally {
      window.removeEventListener(MEMBERSHIP_EXPIRED_EVENT, listener);
    }
  });

  it('still reports a missing connection as NETWORK_ERROR, not as a class-access error', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'));
    const err: any = await api.get('/classes/c1').catch((e) => e);
    expect(err.code).toBe('NETWORK_ERROR');
    expect(err.message).not.toMatch(/failed to fetch/i);
  });

  it('friendlyMessageFor falls back to the server text for unknown codes', () => {
    expect(friendlyMessageFor('NOT_FOUND', 'Không tìm thấy')).toBe('Không tìm thấy');
    expect(friendlyMessageFor(undefined, 'x')).toBe('x');
    expect(friendlyMessageFor(PAYMENT_REQUIRED, 'x')).toBe(FRIENDLY_CODE_MESSAGES[PAYMENT_REQUIRED]);
  });
});

describe('accessProductFromError (PAYMENT_REQUIRED details)', () => {
  const payment = (details: Record<string, unknown> | undefined) => new ApiException(PAYMENT_REQUIRED, 'x', 'r', 402, details);

  it('reads the class-access product the 402 carries', () => {
    const product = accessProductFromError(payment({ accessProduct: { id: 'p1', price: 199000, currency: 'VND', durationDays: 30 } }));
    expect(product).toEqual({ id: 'p1', price: 199000, currency: 'VND', durationDays: 30, lifetime: false });
  });

  it('treats a missing / zero duration as lifetime', () => {
    expect(accessProductFromError(payment({ accessProduct: { id: 'p1', price: 1000, durationDays: null } }))?.lifetime).toBe(true);
    expect(accessProductFromError(payment({ accessProduct: { id: 'p1', price: 1000, durationDays: 0 } }))?.durationDays).toBeNull();
  });

  it('is null for other errors and for a malformed payload', () => {
    expect(accessProductFromError(new ApiException('NOT_FOUND', 'x', 'r', 404))).toBeNull();
    expect(accessProductFromError(new Error('boom'))).toBeNull();
    expect(accessProductFromError(payment(undefined))).toBeNull();
    expect(accessProductFromError(payment({ accessProduct: { price: 1000 } }))).toBeNull();
    expect(accessProductFromError(payment({ accessProduct: { id: 'p1', price: 'abc' } }))).toBeNull();
  });
});

describe('class-access display helpers', () => {
  it('formats whole dong with vi-VN grouping', () => {
    expect(formatDong(199000)).toBe('199.000đ');
    expect(formatDong(1500000)).toBe('1.500.000đ');
  });

  it('labels the length of access', () => {
    expect(durationLabel(30)).toBe('30 ngày');
    expect(durationLabel(null)).toBe('trọn đời');
    expect(durationLabel(0)).toBe('trọn đời');
    expect(durationLabel(30, true)).toBe('trọn đời');
    expect(accessPriceLabel(199000, 30)).toBe('199.000đ / 30 ngày');
    expect(accessPriceLabel(199000, null, true)).toBe('199.000đ / trọn đời');
  });

  it('formats dates as dd/MM/yyyy in the browser time zone', () => {
    const d = new Date(2026, 8, 5, 14, 7); // local 05/09/2026 14:07
    expect(formatDate(d.toISOString())).toBe('05/09/2026');
    expect(formatDateTime(d.toISOString())).toBe('05/09/2026 14:07');
    expect(formatDate(null)).toBe('');
    expect(formatDate('không phải ngày')).toBe('');
  });

  it('counts whole days until an instant (rounded up)', () => {
    const now = Date.parse('2026-10-01T00:00:00Z');
    expect(daysUntil('2026-10-04T00:00:00Z', now)).toBe(3);
    expect(daysUntil('2026-10-03T12:00:00Z', now)).toBe(3);
    expect(daysUntil('2026-09-30T00:00:00Z', now)).toBeLessThanOrEqual(0);
    expect(Number.isNaN(daysUntil(null, now))).toBe(true);
  });
});
