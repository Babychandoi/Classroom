import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { ClassesPage, CLASSES_PAGE_SIZE } from '../pages/ClassesPage';
import type { Classroom } from '../types';

/**
 * R16-08: GET /classes is paged on the server (default 50, max 100). The page must request an explicit
 * page/size and offer "Xem thêm lớp học" while full pages keep coming, instead of assuming one call
 * returns every class.
 */

vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
  Link: ({ children, to }: { children?: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: null }),
}));

const makeClasses = (from: number, count: number): Classroom[] =>
  Array.from({ length: count }, (_, i) => ({
    id: `class-${from + i}`,
    ownerId: 'owner-1',
    slug: `lop-${from + i}`,
    title: `Lớp số ${from + i}`,
    status: 'ACTIVE',
    memberCount: 1,
    createdAt: new Date().toISOString(),
  }) as Classroom);

function mockPages(pages: Classroom[][]) {
  const requested: string[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = input.toString();
    if (url.includes('/classes')) {
      requested.push(url);
      const page = Number(new URL(url, 'http://localhost').searchParams.get('page') ?? '0');
      return new Response(JSON.stringify({ success: true, data: pages[page] ?? [] }), { status: 200 });
    }
    return new Response('{}', { status: 404 });
  });
  return requested;
}

describe('ClassesPage paging (R16-08)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('asks for an explicit first page and shows no "load more" when the page is short', async () => {
    const requested = mockPages([makeClasses(0, 3)]);
    render(<ClassesPage />);

    await waitFor(() => expect(screen.getAllByText('Lớp số 0').length).toBeGreaterThan(0));
    expect(requested[0]).toContain(`page=0&size=${CLASSES_PAGE_SIZE}`);
    fireEvent.click(screen.getByRole('button', { name: 'Xem tất cả lớp học' }));
    await waitFor(() => expect(screen.getByText('Lớp số 0')).toBeInTheDocument());
    expect(screen.queryByText('Xem thêm lớp học')).not.toBeInTheDocument();
  });

  it('keeps offering "Xem thêm lớp học" after a full page and appends the next page without duplicates', async () => {
    const requested = mockPages([
      makeClasses(0, CLASSES_PAGE_SIZE),
      // The second page repeats the last row of page 0 (a class created meanwhile shifted rows) plus 2 new ones.
      [...makeClasses(CLASSES_PAGE_SIZE - 1, 3)],
    ]);
    render(<ClassesPage />);

    fireEvent.click(await screen.findByRole('button', { name: 'Xem tất cả lớp học' }));
    const more = await screen.findByText('Xem thêm lớp học');
    fireEvent.click(more);

    await waitFor(() => expect(screen.getByText(`Lớp số ${CLASSES_PAGE_SIZE + 1}`)).toBeInTheDocument());
    // (the curated rails ask for their own small pages; only the catalog pages are counted here)
    const catalogPages = requested.filter((url) => url.includes(`size=${CLASSES_PAGE_SIZE}`));
    expect(catalogPages[1]).toContain(`page=1&size=${CLASSES_PAGE_SIZE}`);
    expect(screen.getAllByText(`Lớp số ${CLASSES_PAGE_SIZE - 1}`)).toHaveLength(1);
    // The second page was short, so the button is gone.
    expect(screen.queryByText('Xem thêm lớp học')).not.toBeInTheDocument();
  });
});
