import React, { useEffect, useId, useRef, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, ClassMember } from '../../types';
import { api } from '../../api/client';
import { formatDate } from '../../api/format';
import { LoadingSpinner, ErrorBanner, StatusBadge } from '../../components/UIStates';
import { Modal } from '../../components/Modal';
import { hasStudioPermission } from '../../api/permissions';
import { InviteManager } from './InviteManager';
import { UserX, ShieldOff, ShieldCheck } from 'lucide-react';

type StudioMember = ClassMember;

type ConfirmAction = { userId: string; type: 'remove' | 'block' | 'unblock'; label: string };

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
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
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
  const [filter, setFilter] = useState<'ALL' | 'ACTIVE' | 'EXPIRED' | 'REMOVED' | 'BLOCKED'>('ALL');
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

  if (!canView) {
    return (
      <div className="max-w-xl mx-auto py-12">
        <ErrorBanner message="Bạn không có quyền xem danh sách thành viên trong Studio." />
      </div>
    );
  }

  return (
    <div className="max-w-5xl mx-auto space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:justify-between sm:items-center">
        <div>
          <h1 className="text-2xl font-black text-slate-900 tracking-tight">Thành viên</h1>
          <p className="text-xs text-slate-600">Quản lý thành viên, chặn/mở khóa và xóa khỏi lớp học</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <label htmlFor={searchFieldId} className="sr-only">
            Tìm thành viên
          </label>
          <input
            id={searchFieldId}
            type="search"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            placeholder="Tìm theo tên hoặc email..."
            className="px-3 py-2 bg-white border border-slate-300 rounded-xl text-xs w-full sm:w-56"
          />
          <label htmlFor={filterFieldId} className="sr-only">
            Lọc theo trạng thái
          </label>
          <select
            id={filterFieldId}
            value={filter}
            onChange={(e) => setFilter(e.target.value as typeof filter)}
            className="px-3 py-2 bg-white border border-slate-300 rounded-xl text-xs font-semibold"
          >
            <option value="ALL">Tất cả trạng thái</option>
            <option value="ACTIVE">Đang hoạt động</option>
            <option value="EXPIRED">Đã hết hạn</option>
            <option value="REMOVED">Đã bị xóa</option>
            <option value="BLOCKED">Đã bị chặn</option>
          </select>
        </div>
      </div>

      {/* D-19: invite links (owner or MEMBER:EDIT) */}
      {canEdit && <InviteManager classroom={classroom} />}

      {loading && <LoadingSpinner message="Đang tải danh sách thành viên..." />}
      {error && <ErrorBanner message={error} onRetry={fetchMembers} />}
      {actionError && <ErrorBanner message={actionError} />}

      {!loading && !error && (
        <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm divide-y divide-slate-100">
          {visibleMembers.length === 0 && (
            <p className="p-6 text-xs text-slate-500 text-center">Không có thành viên nào phù hợp bộ lọc.</p>
          )}
          {visibleMembers.length > 0 && (
            <p className="px-5 py-2 text-[11px] text-slate-500 bg-slate-50" data-testid="member-count">
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
              <div key={m.id} className="p-5 flex items-center justify-between gap-4">
                <div className="space-y-1 min-w-0">
                  <div className="flex items-center flex-wrap gap-2">
                    <span className="text-sm font-bold text-slate-900 truncate">{m.userFullName || m.userId}</span>
                    <StatusBadge status={m.role} />
                    <StatusBadge status={state} />
                    {m.isPro && <StatusBadge status="PRO" />}
                  </div>
                  <div className="text-xs text-slate-500 font-mono truncate">{m.userEmail || m.userId}</div>
                  <div className="text-[11px] text-slate-500">
                    Tham gia: {m.joinedAt ? new Date(m.joinedAt).toLocaleDateString('vi-VN') : '—'}
                  </div>
                  {expiryText && (
                    <div data-testid="member-expiry" className={`text-[11px] font-semibold ${state === 'EXPIRED' ? 'text-purple-800' : 'text-slate-600'}`}>
                      {expiryText}
                    </div>
                  )}
                </div>

                {canEdit && !isOwnerRow && (
                  <div className="flex gap-2 flex-shrink-0">
                    {canModerate && canRemoveOrBlock && (
                      <>
                        <button
                          onClick={() => setConfirmAction({ userId: m.userId, type: 'block', label: `chặn ${m.userFullName || m.userId}` })}
                          disabled={pendingUserId === m.userId}
                          className="p-2 text-amber-500 hover:bg-amber-50 rounded-lg disabled:opacity-50"
                          title="Chặn thành viên"
                          aria-label={`Chặn ${m.userFullName || m.userId}`}
                        >
                          <ShieldOff className="w-4 h-4" />
                        </button>
                        <button
                          onClick={() => setConfirmAction({ userId: m.userId, type: 'remove', label: `xóa ${m.userFullName || m.userId} khỏi lớp` })}
                          disabled={pendingUserId === m.userId}
                          className="p-2 text-slate-500 hover:text-rose-600 rounded-lg hover:bg-rose-50 transition disabled:opacity-50"
                          title="Xóa khỏi lớp"
                          aria-label={`Xóa ${m.userFullName || m.userId} khỏi lớp`}
                        >
                          <UserX className="w-4 h-4" />
                        </button>
                      </>
                    )}
                    {(state === 'BLOCKED' || state === 'REMOVED' || state === 'BANNED') && (
                      <button
                        onClick={() => setConfirmAction({ userId: m.userId, type: 'unblock', label: `mở khóa ${m.userFullName || m.userId}` })}
                        disabled={pendingUserId === m.userId}
                        className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-emerald-700 hover:bg-emerald-50 rounded-lg disabled:opacity-50 text-xs font-bold"
                        title="Mở khóa / khôi phục"
                      >
                        <ShieldCheck className="w-4 h-4" />
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

      {!loading && !error && hasNext && (
        <div className="flex justify-center">
          <button
            type="button"
            onClick={loadMore}
            disabled={loadingMore}
            className="px-4 py-2 bg-white border border-slate-200 text-slate-700 rounded-xl text-xs font-bold shadow-sm hover:bg-slate-50 transition disabled:opacity-50"
          >
            {loadingMore ? 'Đang tải...' : `Xem thêm (${Math.max(0, total - members.length)})`}
          </button>
        </div>
      )}

      {confirmAction && (
        <Modal size="sm" title="Xác nhận thao tác" role="alertdialog" onClose={() => setConfirmAction(null)}>
            <p className="text-sm text-slate-600">Bạn có chắc chắn muốn {confirmAction.label} không?</p>
            <div className="flex justify-end space-x-2">
              <button
                type="button"
                onClick={() => setConfirmAction(null)}
                className="px-4 py-2 border border-slate-300 text-slate-700 rounded-xl text-xs font-semibold"
              >
                Hủy
              </button>
              <button
                type="button"
                disabled={pendingUserId === confirmAction.userId}
                onClick={() => runAction(confirmAction)}
                className="px-4 py-2 bg-rose-600 hover:bg-rose-700 text-white rounded-xl text-xs font-bold disabled:opacity-50"
              >
                {pendingUserId === confirmAction.userId ? 'Đang xử lý...' : 'Xác nhận'}
              </button>
            </div>
        </Modal>
      )}
    </div>
  );
};
