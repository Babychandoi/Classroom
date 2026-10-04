import React from 'react';
import { vi } from 'vitest';
import { render } from '@testing-library/react';
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom';
import type { BlogPost, ClassEvent, Classroom } from '../types';

// Shared fixtures for the Blog / Events page tests: a router whose parent route provides the outlet context the classroom
// layouts give their tabs, and a fetch mock that answers by "METHOD path" so each test lists only the calls it cares about.

export const baseClassroom: Classroom = {
  id: 'class-1',
  ownerId: 'owner-1',
  ownerName: 'Chủ Lớp',
  slug: 'demo-class',
  title: 'Lớp Demo',
  status: 'ACTIVE',
  memberCount: 12,
  userRole: 'MEMBER',
  isMember: true,
  visibility: 'PUBLIC',
  accessType: 'FREE',
  createdAt: '2026-01-01T00:00:00Z',
};

export const person = (id: string, fullName: string) => ({ id, fullName, avatarUrl: null });

export const makePost = (over: Partial<BlogPost> = {}): BlogPost => ({
  id: 'post-1',
  classId: 'class-1',
  title: 'Bài viết đầu tiên',
  excerpt: 'Tóm tắt ngắn',
  category: 'Bắt đầu',
  contentMarkdown: null,
  coverMediaId: null,
  coverUrl: null,
  audience: 'PUBLIC',
  status: 'PUBLISHED',
  locked: false,
  readingMinutes: 4,
  author: person('owner-1', 'Chủ Lớp'),
  publishedAt: '2026-07-08T03:00:00Z',
  createdAt: '2026-07-07T03:00:00Z',
  updatedAt: '2026-07-08T03:00:00Z',
  ...over,
});

const inDays = (days: number, hour = 13) => {
  const d = new Date();
  d.setDate(d.getDate() + days);
  d.setHours(hour, 0, 0, 0);
  return d.toISOString();
};

export const makeEvent = (over: Partial<ClassEvent> = {}): ClassEvent => ({
  id: 'event-1',
  classId: 'class-1',
  title: 'Hỏi đáp tuần',
  description: 'Mô tả buổi',
  forWhom: 'Người mới',
  takeaways: ['Lộ trình rõ ràng'],
  format: 'ONLINE',
  location: 'Zoom',
  meetingUrl: null,
  startsAt: inDays(3, 13),
  endsAt: inDays(3, 14),
  capacity: 50,
  registeredCount: 10,
  isRegistered: false,
  isFull: false,
  host: person('owner-1', 'Chủ Lớp'),
  coverMediaId: null,
  coverUrl: null,
  audience: 'PUBLIC',
  status: 'SCHEDULED',
  createdAt: '2026-01-01T00:00:00Z',
  ...over,
});

export const pastDays = (days: number, hour = 13) => inDays(-days, hour);

export const ok = (data: unknown, status = 200) => new Response(JSON.stringify({ success: true, data }), { status });
export const fail = (status: number, code: string, message: string) =>
  new Response(JSON.stringify({ success: false, error: { code, message } }), { status });

export interface Call { method: string; path: string; body: any }

/**
 * Mocks fetch. `routes` maps "METHOD /path-prefix" (path without /api/v1, query included when given) to a handler.
 * The longest matching key wins. Every call is recorded in the returned array.
 */
export function mockApi(routes: Record<string, (call: Call) => Response | Promise<Response>>) {
  const calls: Call[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = input.toString().replace(/^.*\/api\/v1/, '');
    const method = (init?.method || 'GET').toUpperCase();
    let body: any = undefined;
    if (typeof init?.body === 'string') { try { body = JSON.parse(init.body); } catch { body = init.body; } }
    const call = { method, path: url, body };
    calls.push(call);
    const key = Object.keys(routes)
      .filter((k) => {
        const [m, p] = k.split(' ');
        return m === method && url.startsWith(p);
      })
      .sort((a, b) => b.length - a.length)[0];
    if (!key) return new Response(JSON.stringify({ success: false, error: { code: 'NOT_FOUND', message: 'not mocked' } }), { status: 404 });
    return routes[key](call);
  });
  return calls;
}

/** Renders `element` at `path` (matched by `pattern`) under a parent route that provides the classroom outlet context. */
export function renderInClass(
  element: React.ReactElement,
  { path, pattern, classroom = baseClassroom, refreshClassroom = vi.fn().mockResolvedValue(undefined) }:
    { path: string; pattern: string; classroom?: Classroom; refreshClassroom?: () => Promise<void> },
) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<Outlet context={{ classroom, refreshClassroom }} />}>
          <Route path={pattern} element={element} />
        </Route>
        <Route path="/login" element={<div>Trang đăng nhập</div>} />
        <Route path="*" element={<div>Trang khác</div>} />
      </Routes>
    </MemoryRouter>,
  );
}
