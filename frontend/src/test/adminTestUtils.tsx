import { vi } from 'vitest';

// Route-based fetch mock for the platform-admin tests: `handler(method, path, query, body)` returns the `data` of the
// standard envelope, or `{ status, error }` for a failure; anything unhandled is a 404.

export type ApiCall = { method: string; path: string; query: URLSearchParams; body: any };
export type Reply = { status: number; error: { code: string; message: string } } | { data: unknown } | undefined;

export function mockApi(handler: (method: string, path: string, query: URLSearchParams, body: any) => Reply) {
  const calls: ApiCall[] = [];
  const spy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = new URL(input.toString(), 'http://localhost');
    const path = url.pathname.replace(/^\/api\/v1/, '');
    const method = (init?.method ?? 'GET').toUpperCase();
    const body = init?.body ? JSON.parse(init.body as string) : undefined;
    calls.push({ method, path, query: url.searchParams, body });
    const reply = handler(method, path, url.searchParams, body);
    if (!reply) return new Response(JSON.stringify({ success: false, error: { code: 'NOT_FOUND', message: 'Không tìm thấy' } }), { status: 404 });
    if ('error' in reply) return new Response(JSON.stringify({ success: false, error: reply.error }), { status: reply.status });
    return new Response(JSON.stringify({ success: true, data: reply.data }), { status: 200 });
  });
  return { calls, spy };
}

export const page = <T,>(content: T[], over: Partial<{ page: number; size: number; totalElements: number; totalPages: number }> = {}) => ({
  content,
  page: 0,
  size: 20,
  totalElements: content.length,
  totalPages: content.length ? 1 : 0,
  ...over,
});

export const adminUser = { id: 'admin-1', fullName: 'Quản Trị', email: 'admin@classroom.local', role: 'PLATFORM_ADMIN', status: 'ACTIVE' };
export const plainUser = { id: 'user-1', fullName: 'Học Viên', email: 'student@classroom.local', role: 'USER', status: 'ACTIVE' };
