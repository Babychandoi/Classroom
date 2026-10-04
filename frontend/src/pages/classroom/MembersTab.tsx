import React, { useEffect, useMemo, useState } from 'react';
import { useOutletContext, Link } from 'react-router-dom';
import { Classroom } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { Avatar, Badge, buttonClass } from '../../components/ui';
import { formatDate } from '../../api/format';
import { ChevronRight, Link2, Search } from 'lucide-react';

const DAY_MS = 24 * 60 * 60 * 1000;
const ROLE_BADGE: Record<string, { label: string; tone: 'member' | 'neutral' }> = {
  OWNER: { label: 'Chủ lớp', tone: 'member' },
  STAFF: { label: 'Trợ giảng', tone: 'neutral' },
};
// R16-07: REMOVED/BLOCKED rows (administrators only) say what happened, in words and colour.
const STATE_LABEL: Record<string, { label: string; className: string }> = {
  BLOCKED: { label: 'Đã chặn', className: 'text-red-600' },
  REMOVED: { label: 'Đã xóa khỏi lớp', className: 'text-amber-700' },
};

interface MemberItem {
  id: string;
  userId: string;
  role: string;
  state: string;
  joinedAt: string;
  userFullName?: string | null;
  userAvatarUrl?: string | null;
}

export const MembersTab: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();

  const [members, setMembers] = useState<MemberItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchMembers = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.get<MemberItem[]>(`/classes/${classroom.id}/members`);
      setMembers(data || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải danh sách thành viên');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchMembers();
  }, [classroom.id]);

  // R15-04: the API returns every state only to class administrators (OWNER / MEMBER:VIEW staff);
  // everyone else already receives ACTIVE members alone. The headline count must be the number of
  // people who actually belong to the class, so a REMOVED/BLOCKED row never inflates it.
  const activeCount = members.filter((m) => (m.state || '').toUpperCase() === 'ACTIVE').length;

  const [query, setQuery] = useState('');
  const [shareMessage, setShareMessage] = useState('');
  const groups = useMemo(() => {
    const q = query.trim().toLowerCase();
    const now = Date.now();
    const matches = members.filter((m) => !q || (m.userFullName || '').toLowerCase().includes(q));
    const isActive = (m: MemberItem) => (m.state || '').toUpperCase() === 'ACTIVE';
    const isStaff = (m: MemberItem) => m.role === 'OWNER' || m.role === 'STAFF';
    const isNew = (m: MemberItem) => now - new Date(m.joinedAt).getTime() <= 7 * DAY_MS;
    return {
      // The owner leads the staff list, then the teaching assistants.
      staff: matches.filter((m) => isActive(m) && isStaff(m)).sort((a, b) => Number(b.role === 'OWNER') - Number(a.role === 'OWNER')),
      newcomers: matches.filter((m) => isActive(m) && !isStaff(m) && isNew(m)),
      others: matches.filter((m) => isActive(m) && !isStaff(m) && !isNew(m)),
      inactive: matches.filter((m) => !isActive(m)),
      total: matches.length,
    };
  }, [members, query]);
  const newThisMonth = members.filter(
    (m) => (m.state || '').toUpperCase() === 'ACTIVE' && Date.now() - new Date(m.joinedAt).getTime() <= 30 * DAY_MS,
  ).length;
  const staffCount = members.filter((m) => (m.state || '').toUpperCase() === 'ACTIVE' && (m.role === 'OWNER' || m.role === 'STAFF')).length;

  const copyInvite = async () => {
    try {
      await navigator.clipboard.writeText(`${window.location.origin}/classes/${classroom.slug}/about`);
      setShareMessage('Đã sao chép liên kết lớp.');
    } catch {
      setShareMessage('Không sao chép được — hãy sao chép địa chỉ trên trình duyệt.');
    }
  };

  const renderRow = (m: MemberItem, highlight = false) => {
    // R16-07: class administrators receive REMOVED/BLOCKED rows with the person's real name (the
    // API reveals identity to them for any roster row); dim those rows so they read as "no longer
    // in the class" without hiding who they are.
    const memberState = (m.state || '').toUpperCase();
    const inactive = memberState !== 'ACTIVE';
    const state = STATE_LABEL[memberState];
    const role = ROLE_BADGE[m.role];
    const name = m.userFullName || (m.userId ? `ID: ${m.userId.substring(0, 8)}...` : 'Thành viên ẩn danh');
    const rowClass = `grid grid-cols-[44px_minmax(0,1fr)_auto] items-center gap-3.5 px-4 py-3.5 sm:px-6 ${highlight ? 'bg-slate-50' : ''} ${inactive ? 'opacity-70' : ''}`;
    // R8-07: an anonymised member has no userId — never link to /members/null (a broken
    // profile route); render the same row as a non-interactive div instead.
    const content = (
      <>
        <Avatar name={m.userFullName || undefined} src={m.userAvatarUrl} size={44} />
        <span className="min-w-0">
          <span className="flex min-w-0 flex-wrap items-center gap-2">
            <span className="truncate text-body-sm font-semibold text-slate-900 group-hover:text-blue-600">{name}</span>
            {role && <Badge tone={role.tone} size="sm">{role.label}</Badge>}
          </span>
          <span className="mt-0.5 block text-meta text-slate-600 tabular">
            Tham gia ngày {formatDate(m.joinedAt)}
            {state && (
              <>
                {' · '}
                <span className={`font-semibold ${state.className}`}>{state.label}</span>
              </>
            )}
          </span>
        </span>
        {m.userId ? (
          <span className="flex items-center gap-1 text-meta font-medium text-blue-600">
            <span className="hidden sm:inline">Xem hồ sơ</span>
            <ChevronRight className="h-4 w-4 text-slate-400 group-hover:text-blue-600" strokeWidth={1.75} aria-hidden="true" />
          </span>
        ) : <span />}
      </>
    );
    if (!m.userId) {
      return (
        <li key={m.id}>
          <div data-member-state={memberState} className={rowClass}>{content}</div>
        </li>
      );
    }
    return (
      <li key={m.id}>
        <Link
          to={`/classes/${classroom.slug}/members/${m.userId}`}
          data-member-state={memberState}
          className={`group transition-colors duration-micro hover:bg-slate-50 ${rowClass}`}
        >
          {content}
        </Link>
      </li>
    );
  };

  const section = (title: React.ReactNode, items: MemberItem[], highlight = false) =>
    items.length > 0 && (
      <section>
        <h3 className="mb-3 text-body font-semibold text-slate-900">{title}</h3>
        <ul className="divide-y divide-slate-100 overflow-hidden rounded-card border border-slate-200 bg-white shadow-hairline">
          {items.map((m) => renderRow(m, highlight))}
        </ul>
      </section>
    );

  return (
    <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_360px]">
      <div className="min-w-0 space-y-6">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <h2 className="text-h2-sm font-semibold text-slate-900">Thành viên lớp học</h2>
            <p className="mt-0.5 text-meta text-slate-600">Giáo viên, trợ giảng và học viên cùng tham gia lớp</p>
          </div>
          <Badge tone="neutral" className="tabular">{activeCount} thành viên</Badge>
        </div>

        {loading && <LoadingSpinner message="Đang tải danh sách thành viên..." />}
        {error && <ErrorBanner message={error} onRetry={fetchMembers} />}

        {!loading && (
          <>
            {members.length > 0 && (
              <label className="flex h-11 items-center gap-2.5 rounded-input border border-slate-200 bg-white px-3.5 focus-within:border-blue-600 focus-within:ring-2 focus-within:ring-blue-600/20">
                <Search className="h-4 w-4 flex-shrink-0 text-slate-600" strokeWidth={1.5} aria-hidden="true" />
                <input
                  type="search"
                  aria-label="Tìm thành viên"
                  placeholder="Tìm thành viên theo tên…"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  className="min-w-0 flex-1 bg-transparent text-ui text-slate-900 outline-none placeholder:text-slate-400 focus-visible:outline-none"
                />
              </label>
            )}
            {section('Ban quản lý lớp', groups.staff)}
            {section(<>Mới tham gia tuần này · <span className="font-medium text-slate-600 tabular">{groups.newcomers.length} người</span></>, groups.newcomers)}
            {section(groups.staff.length || groups.newcomers.length ? 'Các thành viên khác' : 'Tất cả thành viên', groups.others)}
            {section('Đã rời lớp hoặc bị chặn', groups.inactive, true)}
            {!error && query && groups.total === 0 && (
              <p className="rounded-card border border-slate-200 bg-white px-6 py-8 text-center text-ui text-slate-600 shadow-hairline">
                Không có thành viên nào tên “{query}”. Thử gõ một phần tên khác.
              </p>
            )}
          </>
        )}
      </div>

      <aside className="min-w-0 space-y-5">
        {!loading && !error && (
          <section className="rounded-card border border-slate-200 bg-white p-[22px] shadow-hairline">
            <h3 className="text-body-sm font-semibold text-slate-900">Lớp học bằng con số</h3>
            <dl className="mt-3 space-y-2.5">
              {[
                ['Tổng thành viên', activeCount],
                ['Mới trong 30 ngày', newThisMonth],
                ['Ban quản lý', staffCount],
              ].map(([label, value]) => (
                <div key={label} className="flex items-baseline justify-between gap-3">
                  <dt className="text-meta text-slate-600">{label}</dt>
                  <dd className="text-ui font-semibold text-slate-900 tabular">{Number(value).toLocaleString('vi-VN')}</dd>
                </div>
              ))}
            </dl>
          </section>
        )}
        <section className="rounded-card border border-slate-200 bg-white p-[22px] shadow-hairline">
          <h3 className="text-body-sm font-semibold text-slate-900">Biết ai muốn học cùng?</h3>
          <p className="mt-2 text-meta text-slate-600">Gửi họ trang giới thiệu của lớp — họ xem trước rồi tự quyết định tham gia.</p>
          <button type="button" onClick={copyInvite} className={buttonClass('secondary', 'md', 'mt-3 w-full')}>
            <Link2 className="h-4 w-4 text-slate-600" strokeWidth={1.75} aria-hidden="true" />
            Sao chép liên kết lớp
          </button>
          <p role="status" className="mt-2 text-caption text-slate-600 empty:hidden">{shareMessage}</p>
        </section>
      </aside>
    </div>
  );
};
