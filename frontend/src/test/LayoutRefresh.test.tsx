import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { StudioLayout } from '../pages/studio/StudioLayout';
import { ClassroomLayout } from '../pages/ClassroomLayout';
import type { Classroom } from '../types';

/**
 * R18-05: refreshing the classroom (StudioSettings after saving, StoreTab after a purchase) used to flip the layout
 * back to its full-page spinner, unmounting the page that asked - so its "Đã lưu…" message and the open checkout
 * dialog were thrown away before anyone could see them. A refresh of data that is already on screen must be
 * silent: the page stays mounted, and only losing access (or a first load / a different person) shows a spinner.
 */

let currentPath = '/studio/classes/class-1/settings';
let mounts = 0;

vi.mock('react-router-dom', () => ({
  useParams: () => ({ id: 'class-1', slug: 'demo-class' }),
  useNavigate: () => vi.fn(),
  useLocation: () => ({ pathname: currentPath, search: '', hash: '', state: null, key: 'k' }),
  Outlet: ({ context }: { context: { refreshClassroom: () => Promise<void> } }) => <Probe context={context} />,
  NavLink: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
  Link: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
  Navigate: () => null,
}));

const mockUser = { id: 'u1', fullName: 'Chủ lớp', email: 'owner@test.local', role: 'USER', status: 'ACTIVE' };
let authState: { user: typeof mockUser | null; isLoading: boolean } = { user: mockUser, isLoading: false };
vi.mock('../context/AuthContext', () => ({
  useAuth: () => authState,
}));

/** Stands in for StudioSettings / StoreTab: keeps local state that a remount would lose. */
const Probe: React.FC<{ context: { refreshClassroom: () => Promise<void> } }> = ({ context }) => {
  const [message, setMessage] = React.useState('');
  React.useEffect(() => { mounts += 1; }, []);
  return (
    <div>
      <button onClick={async () => { await context.refreshClassroom(); setMessage('Đã lưu cài đặt lớp học.'); }}>lưu</button>
      <p data-testid="message">{message}</p>
    </div>
  );
};

const classroom: Classroom = {
  id: 'class-1',
  ownerId: 'u1',
  slug: 'demo-class',
  title: 'Demo Class',
  status: 'ACTIVE',
  memberCount: 1,
  userRole: 'OWNER',
  isOwner: true,
  isMember: true,
  studioPermissions: [],
  createdAt: new Date().toISOString(),
} as Classroom;

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(status < 400 ? { success: true, data } : data), { status });
}

/** First call answers straight away; every later call waits for release() (or fails) so the in-flight state can be observed. */
function scriptedFetch(match: string, later: 'ok' | Response | 'network' = 'ok') {
  let calls = 0;
  let release: (() => void) | null = null;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    if (!input.toString().includes(match)) return new Response('{}', { status: 404 });
    calls += 1;
    if (calls === 1) return json(classroom);
    await new Promise<void>((resolve) => { release = resolve; });
    if (later === 'network') throw new TypeError('Failed to fetch');
    if (later instanceof Response) return later;
    return json({ ...classroom, title: 'Demo Class (đã sửa)' });
  });
  return { release: () => release?.(), calls: () => calls };
}

describe.each([
  ['StudioLayout', () => <StudioLayout />, '/classes/class-1', 'Đang kết nối Studio...', '/studio/classes/class-1/settings'],
  ['ClassroomLayout', () => <ClassroomLayout />, '/classes/slug/demo-class', 'Đang tải dữ liệu lớp học...', '/classes/demo-class/store'],
])('%s refresh (R18-05)', (_name, layout, match, spinnerText, path) => {
  beforeEach(() => {
    vi.restoreAllMocks();
    mounts = 0;
    currentPath = path;
    authState = { user: mockUser, isLoading: false };
  });

  it('keeps the page mounted while refreshing: no spinner, no remount, and its message survives', async () => {
    const script = scriptedFetch(match);
    render(layout());

    await waitFor(() => expect(screen.getByRole('button', { name: 'lưu' })).toBeInTheDocument());
    // R19-11: the mount counter is bumped by a PASSIVE effect, which React may not have flushed yet when the button
    // first appears in the DOM - asserting right after the button's waitFor raced it (1 in ~N runs saw 0). Wait for
    // the counter itself.
    await waitFor(() => expect(mounts).toBe(1));

    fireEvent.click(screen.getByRole('button', { name: 'lưu' }));
    await waitFor(() => expect(script.calls()).toBe(2));
    // The refresh is in flight: the page is still there and no full-page spinner replaced it.
    expect(screen.getByRole('button', { name: 'lưu' })).toBeInTheDocument();
    expect(screen.queryByText(spinnerText)).not.toBeInTheDocument();

    await act(async () => { script.release(); });
    await waitFor(() => expect(screen.getByTestId('message')).toHaveTextContent('Đã lưu cài đặt lớp học.'));
    expect(mounts).toBe(1);
  });

  it('keeps the working page when a background refresh fails transiently', async () => {
    const script = scriptedFetch(match, 'network');
    render(layout());
    await waitFor(() => expect(screen.getByRole('button', { name: 'lưu' })).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'lưu' }));
    await waitFor(() => expect(script.calls()).toBe(2));
    await act(async () => { script.release(); });

    await waitFor(() => expect(screen.getByTestId('message')).toHaveTextContent('Đã lưu cài đặt lớp học.'));
    expect(screen.getByRole('button', { name: 'lưu' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(mounts).toBe(1);
  });

  it('still shows the error when the refresh reports access was lost (403)', async () => {
    const forbidden = json({ success: false, error: { code: 'FORBIDDEN', message: 'Bạn không còn quyền truy cập' } }, 403);
    const script = scriptedFetch(match, forbidden);
    render(layout());
    await waitFor(() => expect(screen.getByRole('button', { name: 'lưu' })).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'lưu' }));
    await waitFor(() => expect(script.calls()).toBe(2));
    await act(async () => { script.release(); });

    await waitFor(() => expect(screen.getByText('Bạn không còn quyền truy cập')).toBeInTheDocument());
  });
});
