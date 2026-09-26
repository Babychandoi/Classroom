import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { api, ApiException, setAccessToken, setSessionExpiredHandler } from '../api/client';

describe('API Client Behavior & Error Handling', () => {
  beforeEach(() => {
    localStorage.clear();
    setAccessToken(null);
    setSessionExpiredHandler(null);
    vi.restoreAllMocks();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('injects only the in-memory Bearer token into Authorization header', async () => {
    setAccessToken('test-jwt-token-xyz');

    let capturedHeaders: any = null;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      capturedHeaders = new Headers(init?.headers);
      return new Response(JSON.stringify({ success: true, data: { status: 'UP' } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    });

    const result = await api.get<{ status: string }>('/health');
    expect(result).toEqual({ status: 'UP' });
    expect(capturedHeaders?.get('Authorization')).toBe('Bearer test-jwt-token-xyz');
    expect(capturedHeaders?.get('Content-Type')).toBe('application/json');
    expect(localStorage.getItem('token')).toBeNull();
  });

  it('omits Authorization header when user is unauthenticated', async () => {
    let capturedHeaders: any = null;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      capturedHeaders = new Headers(init?.headers);
      return new Response(JSON.stringify({ success: true, data: [] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    });

    await api.get<unknown[]>('/classes');
    expect(capturedHeaders?.get('Authorization')).toBeNull();
  });

  it('downloads protected files with the in-memory bearer token', async () => {
    setAccessToken('download-token');
    const authorizationHeaders: string[] = [];
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
      authorizationHeaders.push(new Headers(init?.headers).get('Authorization') || '');
      return new Response('protected file', { status: 200 });
    });

    const result = await api.download('/media/asset-1/download');

    expect(await result.text()).toBe('protected file');
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/media/asset-1/download', expect.anything());
    expect(authorizationHeaders).toEqual(['Bearer download-token']);
  });

  it('downloads protected lesson media through the authenticated API client', async () => {
    setAccessToken('lesson-media-token');
    const requestHeaders: Headers[] = [];
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
      requestHeaders.push(new Headers(init?.headers));
      return new Response('media bytes', { status: 200, headers: { 'Content-Type': 'video/mp4' } });
    });

    const media = await api.download('/media/lesson-asset/download');

    expect(await media.text()).toBe('media bytes');
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/media/lesson-asset/download', expect.anything());
    expect(requestHeaders[0]?.get('Authorization')).toBe('Bearer lesson-media-token');
  });

  it('does not duplicate the API prefix on server-provided lesson media routes', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('media', { status: 200 }));
    await api.download('/api/v1/media/lesson-asset/download');
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/media/lesson-asset/download', expect.anything());
  });

  it('unwraps ApiResponse data on success', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      return new Response(
        JSON.stringify({
          success: true,
          data: { id: 'c1', title: 'Toán học nâng cao' },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    });

    const data = await api.get<{ id: string; title: string }>('/classes/c1');
    expect(data.id).toBe('c1');
    expect(data.title).toBe('Toán học nâng cao');
  });

  it('throws ApiException with structured error code and message on 403 Forbidden', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      return new Response(
        JSON.stringify({
          success: false,
          error: {
            code: 'ACCESS_DENIED',
            message: 'Bạn không phải là thành viên của lớp học này',
            requestId: 'req-403',
          },
        }),
        { status: 403, headers: { 'Content-Type': 'application/json' } }
      );
    });

    await expect(api.get('/classes/c1/members')).rejects.toThrow(ApiException);

    try {
      await api.get('/classes/c1/members');
    } catch (err: any) {
      expect(err).toBeInstanceOf(ApiException);
      expect(err.code).toBe('ACCESS_DENIED');
      expect(err.message).toBe('Bạn không phải là thành viên của lớp học này');
      expect(err.requestId).toBe('req-403');
    }
  });

  it('throws ApiException on 401 Unauthorized for unauthenticated access', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      return new Response(
        JSON.stringify({
          success: false,
          error: {
            code: 'UNAUTHORIZED',
            message: 'Yêu cầu đăng nhập',
          },
        }),
        { status: 401, headers: { 'Content-Type': 'application/json' } }
      );
    });

    await expect(api.get('/me')).rejects.toThrow(ApiException);
  });

  it('expires an in-memory session after a 401 and omits it on the next request', async () => {
    setAccessToken('expired-token');
    const expired = vi.fn();
    setSessionExpiredHandler(expired);
    const headers: (string | null)[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
      headers.push(new Headers(init?.headers).get('Authorization'));
      return new Response('{}', { status: headers.length === 1 ? 401 : 200 });
    });
    await expect(api.get('/me')).rejects.toThrow(ApiException);
    await api.get('/classes');
    expect(expired).toHaveBeenCalledOnce();
    expect(headers).toEqual(['Bearer expired-token', null]);
  });

  it('supports POST request with JSON body serialization', async () => {
    let capturedBody: string | null = null;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      capturedBody = init?.body as string;
      return new Response(
        JSON.stringify({
          success: true,
          data: { orderId: 'ord-1' },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    });

    const res = await api.post<{ orderId: string }>('/orders', {
      classId: 'class-1',
      productId: 'prod-1',
      idempotencyKey: 'idemp-123',
    });

    expect(res.orderId).toBe('ord-1');
    expect(JSON.parse(capturedBody!)).toEqual({
      classId: 'class-1',
      productId: 'prod-1',
      idempotencyKey: 'idemp-123',
    });
  });
});
