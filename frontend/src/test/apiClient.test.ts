import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { api, ApiException, NETWORK_ERROR_MESSAGE, bootstrapSession, bootstrapSessionDetailed, setAccessToken, setSessionExpiredHandler } from '../api/client';

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

  it('fetches a short-lived presigned download URL through the authenticated API client', async () => {
    setAccessToken('download-token');
    const authorizationHeaders: string[] = [];
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
      authorizationHeaders.push(new Headers(init?.headers).get('Authorization') || '');
      return new Response(
        JSON.stringify({
          success: true,
          data: {
            assetId: 'asset-1',
            downloadUrl: 'http://localhost:9000/classroom-media/classes/c1/media/asset-1.pdf?X-Amz-Signature=abc',
            expiresAt: '2026-09-29T10:10:00Z',
          },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    });

    // R8-01: the client no longer buffers the whole file into a Blob (that path silently
    // truncated large downloads once Tomcat's async request hit its 30s cap). It fetches only the
    // short-lived presigned MinIO URL and hands that URL straight to the browser (<a>/<video>),
    // which streams and supports Range natively.
    const result = await api.get<{ assetId: string; downloadUrl: string; expiresAt: string }>('/media/asset-1/download-url');

    expect(result.downloadUrl).toContain('http://localhost:9000/');
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/media/asset-1/download-url', expect.anything());
    expect(authorizationHeaders).toEqual(['Bearer download-token']);
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

  it('throws ApiException on 401 Unauthorized when the refresh attempt also fails', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (String(input).includes('/auth/refresh')) {
        return new Response('{}', { status: 401 });
      }
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

  it('R8-06: expires the session after a 401 whose silent refresh attempt also fails', async () => {
    setAccessToken('expired-token');
    const expired = vi.fn();
    setSessionExpiredHandler(expired);
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (String(input).includes('/auth/refresh')) {
        return new Response('{}', { status: 401 });
      }
      return new Response('{}', { status: 401 });
    });

    await expect(api.get('/me')).rejects.toThrow(ApiException);
    expect(expired).toHaveBeenCalledOnce();
  });

  it('R12-04: a 401 whose refresh attempt fails with a network error throws a retryable NETWORK_ERROR ApiException, without expiring the session', async () => {
    setAccessToken('expired-token');
    const expired = vi.fn();
    setSessionExpiredHandler(expired);
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (String(input).includes('/auth/refresh')) {
        throw new TypeError('Failed to fetch');
      }
      return new Response('{}', { status: 401 });
    });

    await expect(api.get('/me')).rejects.toMatchObject({ code: 'NETWORK_ERROR', status: 503 });
    expect(expired).not.toHaveBeenCalled();
  });

  it('R12-04: a 401 whose refresh attempt fails with a 503 throws a retryable NETWORK_ERROR ApiException, without expiring the session', async () => {
    setAccessToken('expired-token');
    const expired = vi.fn();
    setSessionExpiredHandler(expired);
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (String(input).includes('/auth/refresh')) {
        return new Response('Service Unavailable', { status: 503 });
      }
      return new Response('{}', { status: 401 });
    });

    await expect(api.get('/me')).rejects.toMatchObject({ code: 'NETWORK_ERROR', status: 503 });
    expect(expired).not.toHaveBeenCalled();
  });

  it('R12-04: a 401 whose refresh attempt fails with a 429 (rate limited) throws a retryable NETWORK_ERROR ApiException, without expiring the session', async () => {
    setAccessToken('expired-token');
    const expired = vi.fn();
    setSessionExpiredHandler(expired);
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (String(input).includes('/auth/refresh')) {
        return new Response('{}', { status: 429 });
      }
      return new Response('{}', { status: 401 });
    });

    await expect(api.get('/me')).rejects.toMatchObject({ code: 'NETWORK_ERROR', status: 503 });
    expect(expired).not.toHaveBeenCalled();
  });

  it('R8-06: on a 401, transparently refreshes the access token via the HttpOnly cookie and retries once', async () => {
    setAccessToken('expired-token');
    const expired = vi.fn();
    setSessionExpiredHandler(expired);
    const authorizationHeaders: (string | null)[] = [];
    let meCallCount = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = String(input);
      if (url.includes('/auth/refresh')) {
        expect(init?.credentials).toBe('include');
        expect(new Headers(init?.headers).get('X-Requested-With')).toBe('XMLHttpRequest');
        return new Response(JSON.stringify({ success: true, data: { token: 'fresh-access-token' } }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      meCallCount += 1;
      authorizationHeaders.push(new Headers(init?.headers).get('Authorization'));
      if (meCallCount === 1) return new Response('{}', { status: 401 });
      return new Response(JSON.stringify({ success: true, data: { id: 'u1' } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    });

    const result = await api.get<{ id: string }>('/me');

    expect(result).toEqual({ id: 'u1' });
    expect(authorizationHeaders).toEqual(['Bearer expired-token', 'Bearer fresh-access-token']);
    expect(expired).not.toHaveBeenCalled();
  });

  it('R8-06: concurrent 401s share a single in-flight refresh call (single-flight)', async () => {
    setAccessToken('expired-token');
    let refreshCallCount = 0;
    let nonRefreshCallCount = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = String(input);
      if (url.includes('/auth/refresh')) {
        refreshCallCount += 1;
        // Delay so both concurrent requests' 401s are in flight before this resolves, proving they
        // share one promise instead of each starting (and racing) their own rotation.
        await new Promise((resolve) => setTimeout(resolve, 10));
        return new Response(JSON.stringify({ success: true, data: { token: 'fresh-access-token' } }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      nonRefreshCallCount += 1;
      // Each of the two endpoints 401s on its first hit (stale token), then succeeds once retried
      // with the refreshed token (calls 3 and 4).
      if (nonRefreshCallCount <= 2) return new Response('{}', { status: 401 });
      return new Response(JSON.stringify({ success: true, data: {} }), { status: 200 });
    });

    const [a, b] = await Promise.all([api.get('/a'), api.get('/b')]);
    expect(a).toEqual({});
    expect(b).toEqual({});
    expect(refreshCallCount).toBe(1);
  });

  it('R8-06: bootstrapSession() fetches /auth/refresh with credentials included and stores the returned access token', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      return new Response(JSON.stringify({ success: true, data: { token: 'bootstrapped-token' } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    });

    const token = await bootstrapSession();

    expect(token).toBe('bootstrapped-token');
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/auth/refresh',
      expect.objectContaining({ method: 'POST', credentials: 'include' })
    );
  });

  it('R8-06: bootstrapSession() returns null when there is no valid refresh cookie', async () => {
    setAccessToken(null);
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response('{}', { status: 401 }));

    const token = await bootstrapSession();

    expect(token).toBeNull();
  });

  it('R9-05: bootstrapSessionDetailed() retries a 429 and reports "unreachable" if it never recovers', async () => {
    let callCount = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      callCount += 1;
      return new Response('{"code":"RATE_LIMITED"}', { status: 429 });
    });

    const outcome = await bootstrapSessionDetailed();

    expect(outcome).toEqual({ status: 'unreachable' });
    expect(callCount).toBeGreaterThan(1); // proves it retried rather than failing on the first 429
  }, 15000);

  it('R9-05: bootstrapSessionDetailed() recovers after a transient 5xx and reports "authenticated"', async () => {
    let callCount = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      callCount += 1;
      if (callCount === 1) return new Response('Internal Server Error', { status: 503 });
      return new Response(JSON.stringify({ success: true, data: { token: 'recovered-token' } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    });

    const outcome = await bootstrapSessionDetailed();

    expect(outcome).toEqual({ status: 'authenticated', token: 'recovered-token' });
  }, 15000);

  it('R9-05: bootstrapSessionDetailed() reports "logged-out" immediately on 401 without retrying', async () => {
    let callCount = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      callCount += 1;
      return new Response('{}', { status: 401 });
    });

    const outcome = await bootstrapSessionDetailed();

    expect(outcome).toEqual({ status: 'logged-out' });
    expect(callCount).toBe(1);
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

  // R18-09: fetch() rejects (TypeError: Failed to fetch / Load failed / NetworkError...) when no response could be
  // obtained; callers used to show that raw browser text.
  it.each([
    ['Chrome', 'Failed to fetch'],
    ['Safari', 'Load failed'],
    ['Firefox', 'NetworkError when attempting to fetch resource.'],
  ])('R18-09: a network failure (%s) becomes a friendly Vietnamese NETWORK_ERROR ApiException', async (_browser, rawMessage) => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError(rawMessage));

    const failure: any = await api.get('/classes').catch((err) => err);

    expect(failure).toBeInstanceOf(ApiException);
    expect(failure.code).toBe('NETWORK_ERROR');
    expect(failure.message).toBe('Không thể kết nối tới máy chủ. Vui lòng kiểm tra mạng và thử lại.');
    expect(failure.message).toBe(NETWORK_ERROR_MESSAGE);
    expect(failure.message).not.toContain(rawMessage);
    // 503 like the refresh path, so callers that key off the status (e.g. the checkout idempotency key)
    // treat it as retryable, not as a definitive 4xx rejection.
    expect(failure.status).toBe(503);
  });

  it('R18-09: a network failure on POST/PUT/DELETE gets the same friendly error', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'));
    for (const call of [
      () => api.post('/orders', { a: 1 }),
      () => api.put('/users/profile', { b: 2 }),
      () => api.delete('/courses/c1'),
    ]) {
      await expect(call()).rejects.toMatchObject({ code: 'NETWORK_ERROR', message: NETWORK_ERROR_MESSAGE });
    }
  });

  it('R18-09: a network failure on the retried request after a token refresh is friendly too', async () => {
    let calls = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      calls += 1;
      if (input.toString().endsWith('/auth/refresh')) {
        return new Response(JSON.stringify({ success: true, data: { token: 'fresh' } }), { status: 200 });
      }
      if (calls === 1) return new Response('{}', { status: 401 });
      throw new TypeError('Failed to fetch');
    });

    await expect(api.get('/classes/c1/members')).rejects.toMatchObject({ code: 'NETWORK_ERROR', message: NETWORK_ERROR_MESSAGE });
  });

  it('R18-09: a caller-initiated abort is not reported as a connectivity problem', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new DOMException('The operation was aborted.', 'AbortError'));

    await expect(api.get('/classes')).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('R18-09: an HTTP error response is still reported with its own code, not as a network error', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(
      JSON.stringify({ success: false, error: { code: 'NOT_FOUND', message: 'Không tìm thấy' } }), { status: 404 }));

    await expect(api.get('/classes/x')).rejects.toMatchObject({ code: 'NOT_FOUND', status: 404 });
  });
});
