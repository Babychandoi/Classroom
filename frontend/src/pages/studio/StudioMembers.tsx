import React, { useEffect, useId, useRef, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, ClassMember } from '../../types';
import { api } from '../../api/client';
import { formatDate } from '../../api/format';
import { LoadingSpinner, ErrorBanner, StatusBadge } from '../../components/UIStates';
import { Modal } from '../../components/Modal';
import { hasStudioPermission } from '../../api/permissions';
import { InviteManager } from './InviteManager';
import { Avatar, Badge, Button, Card, inputClass } from '../../components/ui';
import { EmptyRow, ModalActions, PageHeader, StudioPage, iconActionClass, rowActionClass } from './studioUi';
import { Check, Search, UserX, ShieldOff, ShieldCheck, X } from 'lucide-react';

/** API-CREATE-CLASS §3: a PENDING row is a join request (not a member) and carries `requestedAt`. */
type StudioMember = ClassMember & { requestedAt?: string | null };
/** Fields of the create-class contract this page reads (typed locally until the shared type catches up). */
type ClassroomWithRequests = Classroom & { pendingRequestCount?: number; requireApproval?: boolean };

type ConfirmAction = { userId: string; type: 'remove' | 'block' | 'unblock' | 'reject'; label: string };
type MemberFilter = 'ALL' | 'ACTIVE' | 'PENDING' | 'EXPIRED' | 'REMOVED' | 'BLOCKED';

/** R20-03: the roster is paged on the server (default 50, max 200 per request) with server-side search / state filters. */
interface StudioMemberPage {
  members: StudioMember[];
  total: number;
  page: number;
  size: number;
  hasNext: boolean;
}

const PAGE_SIZE = 50;
const SEARCH_DEBOUNCE_MS = 300;

/**
 * R13-02: Studio "Thành viên" page (FR-14 / sitemap /studio/classes/:id/members). Gated on
 * MEMBER:VIEW to load, MEMBER:EDIT to act — mirrors MemberService's server-side gating exactly.
 */
export const StudioMembers: React.FC = () => {
  const { classroom, refreshClassroom } = useOutletContext<{ classroom: ClassroomWithRequests; refreshClassroom?: () => Promise<void> | void }>();
  const canView = classroom.userRole === 'OWNER' || hasStudioPermission(classroom, 'MEMBER', 'VIEW');
  const canEdit = classroom.userRole === 'OWNER' || hasStudioPermission(classroom, 'MEMBER', 'EDIT');

  const [members, setMembers] = useState<StudioMember[]>([]);
  const [total, setTotal] = useState(0);
  const [nextPage, setNextPage] = useState(1);
  const [hasNext, setHasNext] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [filter, setFilter] = useState<MemberFilter>('ALL');
  // Join requests (state=PENDING), listed in their own section above the roster.
  const [requests, setRequests] = useState<StudioMember[]>([]);
  const [requestsTotal, setRequestsTotal] = useState(0);
  const [requestsError, setRequestsError] = useState<string | null>(null);
  const pendingCount = classroom.pendingRequestCount ?? 0;
  const [searchInput, setSearchInput] = useState('');
  const [query, setQuery] = useState('');
  const [pendingUserId, setPendingUserId] = useState<string | null>(null);
  const [confirmAction, setConfirmAction] = useState<ConfirmAction | null>(null);
  const filterFieldId = useId();
  const searchFieldId = useId();
  // Only the response of the most recent request may update the list (a slow page for an old filter must not overwrite a newer one).
  const requestSeq = useRef(0);

  const buildUrl = (page: number) => {
    const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
    if (filter !== 'ALL') params.set('state', filter);
    if (query.trim()) params.set('q', query.trim());
    return `/classes/${classroom.id}/studio/members?${params.toString()}`;
  };

  /** Reads the paged envelope (a bare array from an older server is still understood). */
  const readPage = (data: StudioMemberPage | StudioMember[] | null | undefined): StudioMemberPage => {
    if (Array.isArray(data)) return { members: data, total: data.length, page: 0, size: data.length, hasNext: false };
    return data || { members: [], total: 0, page: 0, size: PAGE_SIZE, hasNext: false };
  };

  const fetchMembers = async () => {
    const seq = ++requestSeq.current;
    try {
      setLoading(true);
      setError(null);
      const data = readPage(await api.get<StudioMemberPage | StudioMember[]>(buildUrl(0)));
      if (seq !== requestSeq.current) return;
      setMembers(data.members || []);
      setTotal(data.total ?? (data.members || []).length);
      setHasNext(Boolean(data.hasNext));
      setNextPage(1);
    } catch (err: any) {
      if (seq === requestSeq.current) setError(err.message || 'Không thể tải danh sách thành viên');
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  };

  /** "Xem thêm": appends the next page, de-duplicated by member id (a member can shift pages while others are removed). */
  const loadMore = async () => {
    const seq = requestSeq.current;
    try {
      setLoadingMore(true);
      setActionError(null);
      const data = readPage(await api.get<StudioMemberPage | StudioMember[]>(buildUrl(nextPage)));
      if (seq !== requestSeq.current) return;
      setMembers((prev) => {
        const have = new Set(prev.map((m) => m.id));
        return [...prev, ...(data.members || []).filter((m) => !have.has(m.id))];
      });
      setTotal(data.total ?? total);
      setHasNext(Boolean(data.hasNext));
      setNextPage((p) => p + 1);
    } catch (err: any) {
      setActionError(err.message || 'Không thể tải thêm thành viên');
    } finally {
      setLoadingMore(false);
    }
  };

  const fetchRequests = async () => {
    try {
      setRequestsError(null);
      const data = readPage(await api.get<StudioMemberPage | StudioMember[]>(
        `/classes/${classroom.id}/studio/members?${new URLSearchParams({ state: 'PENDING', page: '0', size: String(PAGE_SIZE) }).toString()}`,
      ));
      // An older server ignores the PENDING filter and returns the whole roster - keep only real requests.
      const rows = (data.members || []).filter((m) => (m.state || '').toUpperCase() === 'PENDING');
      setRequests(rows);
      setRequestsTotal(Array.isArray(data.members) && rows.length === data.members.length ? data.total ?? rows.length : rows.length);
    } catch (err: any) {
      setRequestsError(err.message || 'Không thể tải yêu cầu tham gia');
    }
  };

  // Requests can only exist while approval is on, or while some are left over from when it was on (count > 0).
  const mayHaveRequests = pendingCount > 0 || classroom.requireApproval === true;
  useEffect(() => {
    if (canView && mayHaveRequests) void fetchRequests();
    else { setRequests([]); setRequestsTotal(0); }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classroom.id, pendingCount, mayHaveRequests]);

  /** Duyệt (immediate) / Từ chối (after confirmation): then refresh both lists and the class (its pendingRequestCount feeds the nav badge). */
  const decide = async (userId: string, decision: 'approve' | 'reject') => {
    setPendingUserId(userId);
    setActionError(null);
    try {
      await api.post(`/classes/${classroom.id}/studio/members/${userId}/${decision}`);
      await Promise.all([fetchRequests(), fetchMembers()]);
      await refreshClassroom?.();
    } catch (err: any) {
      setActionError(err.message || (decision === 'approve' ? 'Không thể duyệt yêu cầu' : 'Không thể từ chối yêu cầu'));
    } finally {
      setPendingUserId(null);
      setConfirmAction(null);
    }
  };

  // Search box: apply after a short pause so every keystroke is not a request.
  useEffect(() => {
    const timer = setTimeout(() => setQuery(searchInput), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [searchInput]);

  useEffect(() => {
    if (canView) void fetchMembers();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classroom.id, filter, query]);

  const runAction = async (action: ConfirmAction) => {
    if (action.type === 'reject') return decide(action.userId, 'reject');
    setPendingUserId(action.userId);
    setActionError(null);
    try {
      await api.post(`/classes/${classroom.id}/studio/members/${action.userId}/${action.type}`);
      await fetchMembers();
    } catch (err: any) {
      setActionError(err.message || 'Thao tác thất bại');
    } finally {
      setPendingUserId(null);
      setConfirmAction(null);
    }
  };

  // Filtering and search happen on the server; `members` already is exactly what matches.
  const visibleMembers = members;

  const nameOf = (m: StudioMember) => m.userFullName || m.userEmail || m.userId;
  const requestedLabel = (m: StudioMember) => (m.requestedAt || m.joinedAt ? `Gửi yêu cầu: ${formatDate(m.requestedAt || m.joinedAt)}` : '');

  /** "Duyệt" / "Từ chối" for one join request - only for MEMBER:EDIT (the server enforces the same). */
  const requestActions = (m: StudioMember) =>
    canEdit ? (
      <div className="flex flex-shrink-0 flex-wrap justify-end gap-1.5">
        <Button
          size="sm"
          variant="secondary"
          disabled={pendingUserId === m.userId}
          onClick={() => setConfirmAction({ userId: m.userId, type: 'reject', label: `từ chối yêu cầu tham gia của ${nameOf(m)}` })}
          aria-label={`Từ chối ${nameOf(m)}`}
        >
          <X className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
          Từ chối
        </Button>
        <Button size="sm" variant="primary" disabled={pendingUserId === m.userId} onClick={() => decide(m.userId, 'approve')} aria-label={`Duyệt ${nameOf(m)}`}>
          <Check className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
          {pendingUserId === m.userId ? 'Đang duyệt...' : 'Duyệt'}
        </Button>
      </div>
    ) : null;

  const shownRequestCount = Math.max(pendingCount, requests.length);

  if (!canView) {
    return (
      <StudioPage width="narrow" className="py-12">
        <ErrorBanner message="Bạn không có quyền xem danh sách thành viên trong Studio." />
      </StudioPage>
    );
  }

  return (
    <StudioPage>
      <PageHeader title="Thành viên" description={shownRequestCount > 0 ? 'Duyệt người xin vào lớp, mời người mới, chặn hoặc mở khóa và xóa thành viên.' : 'Mời người mới, chặn hoặc mở khóa và xóa thành viên khỏi lớp học.'} />

      {/* API-CREATE-CLASS §3: join requests of a class with "Duyệt từng người trước khi vào" */}
      {(shownRequestCount > 0 || requestsError) && (
        <Card as="section" padded={false} aria-labelledby="requests-title" className="overflow-hidden">
          <div className="flex flex-wrap items-center gap-2 px-5 py-4 sm:px-6">
            <h2 id="requests-title" className="text-[16px] font-semibold leading-6 text-slate-900">Chờ duyệt</h2>
            <span data-testid="pending-count"><Badge tone="warn" size="sm" className="tabular">{shownRequestCount} yêu cầu</Badge></span>
            <p className="w-full text-meta text-slate-600">
              {canEdit
                ? 'Người xin vào lớp chỉ trở thành thành viên sau khi bạn duyệt. Từ chối không chặn họ: họ có thể gửi lại yêu cầu sau.'
                : 'Bạn xem được danh sách nhưng cần quyền MEMBER:EDIT để duyệt hoặc từ chối.'}
            </p>
          </div>
          {requestsError && <div className="px-5 pb-4 sm:px-6"><ErrorBanner message={requestsError} onRetry={fetchRequests} /></div>}
          <ul className="divide-y divide-slate-100 border-t border-slate-100" aria-label="Yêu cầu tham gia">
            {requests.map((m) => (
              <li key={m.id} className="flex flex-col gap-3 px-5 py-4 sm:flex-row sm:items-center sm:justify-between sm:px-6">
                <div className="flex min-w-0 items-start gap-3">
                  <Avatar name={nameOf(m)} src={m.userAvatarUrl} size={36} />
                  <div className="min-w-0">
                    <span className="block truncate text-ui font-semibold text-slate-900">{nameOf(m)}</span>
                    {m.userEmail && <span className="block truncate text-meta text-slate-500">{m.userEmail}</span>}
                    <span className="block text-caption text-slate-500 tabular" data-testid="requested-at">{requestedLabel(m)}</span>
                  </div>
                </div>
                {requestActions(m)}
              </li>
            ))}
          </ul>
          {requestsTotal > requests.length && (
            <p className="border-t border-slate-100 px-5 py-3 text-caption text-slate-500 sm:px-6">
              Đang hiện {requests.length} / {requestsTotal} yêu cầu. Chọn bộ lọc “Chờ duyệt” ở danh sách bên dưới để xem tất cả.
            </p>
          )}
        </Card>
      )}

      {/* D-19: invite links (owner or MEMBER:EDIT) */}
      {canEdit && <InviteManager classroom={classroom} />}

      <Card as="section" padded={false} aria-labelledby="roster-title" className="overflow-hidden">
        <div className="flex flex-col gap-3 px-5 py-4 sm:flex-row sm:items-center sm:justify-between sm:px-6">
          <h2 id="roster-title" className="text-[16px] font-semibold leading-6 text-slate-900">
            Danh sách thành viên <span className="text-slate-500 tabular">({total.toLocaleString('vi-VN')})</span>
          </h2>
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
            <label htmlFor={searchFieldId} className="sr-only">
              Tìm thành viên
            </label>
            <div className="relative">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" strokeWidth={1.75} aria-hidden="true" />
              <input
                id={searchFieldId}
                type="search"
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                placeholder="Tìm theo tên hoặc email..."
                className={inputClass('h-10 pl-9 sm:w-60')}
              />
            </div>
            <label htmlFor={filterFieldId} className="sr-only">
              Lọc theo trạng thái
            </label>
            <select
              id={filterFieldId}
              value={filter}
              onChange={(e) => setFilter(e.target.value as typeof filter)}
              className={inputClass('h-10 pr-8 sm:w-48')}
            >
              <option value="ALL">Tất cả trạng thái</option>
              <option value="ACTIVE">Đang hoạt động</option>
              <option value="PENDING">{`Chờ duyệt${shownRequestCount ? ` (${shownRequestCount})` : ''}`}</option>
              <option value="EXPIRED">Đã hết hạn</option>
              <option value="REMOVED">Đã bị xóa</option>
              <option value="BLOCKED">Đã bị chặn</option>
            </select>
          </div>
        </div>

        {loading && <LoadingSpinner message="Đang tải danh sách thành viên..." />}
        {error && <div className="px-5 pb-5 sm:px-6"><ErrorBanner message={error} onRetry={fetchMembers} /></div>}
        {actionError && <div className="px-5 pb-5 sm:px-6"><ErrorBanner message={actionError} /></div>}

        {!loading && !error && (
          <div className="divide-y divide-slate-100 border-t border-slate-100">
            {visibleMembers.length === 0 && (
              <EmptyRow>Không có thành viên nào phù hợp bộ lọc. Thử bỏ bớt từ khóa hoặc chọn “Tất cả trạng thái”.</EmptyRow>
            )}
            {visibleMembers.length > 0 && (
              <p className="bg-slate-50 px-5 py-2 text-caption text-slate-500 tabular sm:px-6" data-testid="member-count">
                Hiển thị {visibleMembers.length} / {total} thành viên
              </p>
            )}
            {visibleMembers.map((m) => {
              const state = (m.state || 'ACTIVE').toUpperCase();
              const isOwnerRow = m.role === 'OWNER';
              // R14-01: removing/blocking a STAFF member is OWNER-only server-side (MemberService), even for
              // a delegate holding MEMBER:EDIT - so the controls are not offered on staff rows to anyone else.
              const canRemoveOrBlock = classroom.userRole === 'OWNER' || m.role !== 'STAFF';
              // D-19: an EXPIRED member (paid access lapsed) is still on the roster and can be removed / blocked like an ACTIVE one.
              const canModerate = state === 'ACTIVE' || state === 'EXPIRED';
              const expiryText = m.accessExpiresAt
                ? `${state === 'EXPIRED' ? 'Hết hạn' : 'Hạn truy cập đến'} ${formatDate(m.accessExpiresAt)}`
                : state === 'EXPIRED' ? 'Đã hết hạn' : '';
              return (
                <div key={m.id} className="flex items-center justify-between gap-4 p-5 sm:px-6">
                  <div className="flex min-w-0 items-start gap-3">
                    <Avatar name={m.userFullName || m.userId} src={m.userAvatarUrl} size={36} />
                    <div className="min-w-0 space-y-1">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <span className="truncate text-ui font-semibold text-slate-900">{m.userFullName || m.userId}</span>
                        <StatusBadge status={m.role} />
                        <StatusBadge status={state} />
                        {m.isPro && <StatusBadge status="PRO" />}
                      </div>
                      <div className="truncate text-meta text-slate-500">{m.userEmail || m.userId}</div>
                      <div className="text-caption text-slate-500 tabular">
                        {state === 'PENDING' ? requestedLabel(m) : `Tham gia: ${m.joinedAt ? formatDate(m.joinedAt) : '—'}`}
                      </div>
                      {expiryText && (
                        <div data-testid="member-expiry" className={`text-caption font-semibold tabular ${state === 'EXPIRED' ? 'text-violet-800' : 'text-slate-600'}`}>
                          {expiryText}
                        </div>
                      )}
                    </div>
                  </div>

                  {state === 'PENDING' && requestActions(m)}
                  {canEdit && !isOwnerRow && state !== 'PENDING' && (
                    <div className="flex flex-shrink-0 gap-1">
                      {canModerate && canRemoveOrBlock && (
                        <>
                          <button
                            onClick={() => setConfirmAction({ userId: m.userId, type: 'block', label: `chặn ${m.userFullName || m.userId}` })}
                            disabled={pendingUserId === m.userId}
                            className={iconActionClass()}
                            title="Chặn thành viên"
                            aria-label={`Chặn ${m.userFullName || m.userId}`}
                          >
                            <ShieldOff className="h-4 w-4" strokeWidth={1.75} />
                          </button>
                          <button
                            onClick={() => setConfirmAction({ userId: m.userId, type: 'remove', label: `xóa ${m.userFullName || m.userId} khỏi lớp` })}
                            disabled={pendingUserId === m.userId}
                            className={iconActionClass('danger')}
                            title="Xóa khỏi lớp"
                            aria-label={`Xóa ${m.userFullName || m.userId} khỏi lớp`}
                          >
                            <UserX className="h-4 w-4" strokeWidth={1.75} />
                          </button>
                        </>
                      )}
                      {(state === 'BLOCKED' || state === 'REMOVED' || state === 'BANNED') && (
                        <button
                          onClick={() => setConfirmAction({ userId: m.userId, type: 'unblock', label: `mở khóa ${m.userFullName || m.userId}` })}
                          disabled={pendingUserId === m.userId}
                          className={rowActionClass('success')}
                          title="Mở khóa / khôi phục"
                        >
                          <ShieldCheck className="h-4 w-4" strokeWidth={1.75} />
                          <span>Mở khóa</span>
                        </button>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </Card>

      {!loading && !error && hasNext && (
        <div className="flex justify-center">
          <Button variant="secondary" size="md" onClick={loadMore} disabled={loadingMore}>
            {loadingMore ? 'Đang tải...' : `Xem thêm (${Math.max(0, total - members.length)})`}
          </Button>
        </div>
      )}

      {confirmAction && (
        <Modal size="sm" title="Xác nhận thao tác" role="alertdialog" onClose={() => setConfirmAction(null)}>
          <p className="text-ui text-slate-600">Bạn có chắc chắn muốn {confirmAction.label} không?</p>
          <ModalActions className="mt-5">
            <Button variant="secondary" onClick={() => setConfirmAction(null)}>Hủy</Button>
            <Button
              variant={confirmAction.type === 'unblock' ? 'primary' : 'danger'}
              disabled={pendingUserId === confirmAction.userId}
              onClick={() => runAction(confirmAction)}
            >
              {pendingUserId === confirmAction.userId ? 'Đang xử lý...' : confirmAction.type === 'reject' ? 'Từ chối' : 'Xác nhận'}
            </Button>
          </ModalActions>
        </Modal>
      )}
    </StudioPage>
  );
};
