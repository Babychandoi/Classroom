import React, { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Search } from 'lucide-react';
import { adminApi, AdminUserRow, Page, USER_ROLE_LABEL, USER_STATUS_LABEL } from '../../api/admin';
import { formatDate, formatDateTime } from '../../api/format';
import { Avatar, Button, Card, FilterChip, Input, Select } from '../../components/ui';
import { ErrorBanner, LoadingSpinner } from '../../components/UIStates';
import { EmptyRow, PageHeader, StudioPage, tdClass, thClass } from '../studio/studioUi';
import { errorText, Pager, Pill } from './adminUi';

const PAGE_SIZE = 20;

const STATUS_CHIPS = [
  { key: '', label: 'Tất cả' },
  { key: 'ACTIVE', label: 'Đang hoạt động' },
  { key: 'BANNED', label: 'Bị khóa' },
  { key: 'DELETED', label: 'Đã xóa' },
];
const ROLE_CHIPS = [
  { key: '', label: 'Mọi vai trò' },
  { key: 'USER', label: 'Thành viên' },
  { key: 'PLATFORM_ADMIN', label: 'Quản trị nền tảng' },
];

export const UserStatusPill: React.FC<{ status: string }> = ({ status }) => (
  <Pill tone={status === 'ACTIVE' ? 'success' : status === 'BANNED' ? 'danger' : 'neutral'} title={status}>
    {USER_STATUS_LABEL[status] ?? status}
  </Pill>
);

export const UserRolePill: React.FC<{ role: string }> = ({ role }) => (
  <Pill tone={role === 'PLATFORM_ADMIN' ? 'dark' : 'neutral'} title={role}>
    {USER_ROLE_LABEL[role] ?? role}
  </Pill>
);

/** Người dùng: search + status / role chips + a paged table (GET /admin/users). Filters live in the URL. */
export const AdminUsers: React.FC = () => {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const status = params.get('status') ?? '';
  const role = params.get('role') ?? '';
  const sort = params.get('sort') ?? 'newest';
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0);

  const [query, setQuery] = useState(q);
  const [data, setData] = useState<Page<AdminUserRow> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => setQuery(q), [q]);

  const update = (patch: Record<string, string | number>) => {
    const next = new URLSearchParams(params);
    Object.entries(patch).forEach(([key, value]) => {
      if (value === '' || (key === 'page' && value === 0) || (key === 'sort' && value === 'newest')) next.delete(key);
      else next.set(key, String(value));
    });
    if (!('page' in patch)) next.delete('page');
    setParams(next);
  };

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await adminApi.users({ q: q || undefined, status: status || undefined, role: role || undefined, sort, page, size: PAGE_SIZE }));
    } catch (err) {
      setError(errorText(err, 'Không thể tải danh sách người dùng.'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, [q, status, role, sort, page]);

  return (
    <StudioPage>
      <PageHeader title="Người dùng" description="Tìm tài khoản theo email hoặc tên, xem lớp họ sở hữu và khóa tài khoản vi phạm." />

      <div className="grid gap-3">
        <form
          role="search"
          onSubmit={(e) => {
            e.preventDefault();
            update({ q: query.trim().slice(0, 100) });
          }}
          className="flex flex-col gap-2 sm:flex-row"
        >
          <label className="relative min-w-0 flex-1">
            <span className="sr-only">Tìm theo email hoặc tên</span>
            <Search className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" strokeWidth={1.75} aria-hidden="true" />
            <Input
              type="search"
              value={query}
              maxLength={100}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Tìm theo email hoặc tên"
              className="pl-10"
            />
          </label>
          <div className="flex gap-2">
            <Button type="submit" className="h-11 flex-1 sm:flex-none">Tìm</Button>
            <label className="flex-1 sm:w-[170px] sm:flex-none">
              <span className="sr-only">Sắp xếp</span>
              <Select value={sort} onChange={(e) => update({ sort: e.target.value })}>
                <option value="newest">Mới nhất</option>
                <option value="oldest">Cũ nhất</option>
                <option value="name">Theo tên</option>
              </Select>
            </label>
          </div>
        </form>
        <div className="flex flex-wrap items-center gap-2">
          <div role="group" aria-label="Lọc theo trạng thái" className="flex flex-wrap gap-2">
            {STATUS_CHIPS.map((chip) => (
              <FilterChip key={chip.key || 'all'} selected={status === chip.key} onClick={() => update({ status: chip.key })}>
                {chip.label}
              </FilterChip>
            ))}
          </div>
          <span aria-hidden="true" className="mx-1 hidden h-5 w-px bg-slate-200 sm:block" />
          <div role="group" aria-label="Lọc theo vai trò" className="flex flex-wrap gap-2">
            {ROLE_CHIPS.map((chip) => (
              <FilterChip key={chip.key || 'any'} selected={role === chip.key} onClick={() => update({ role: chip.key })}>
                {chip.label}
              </FilterChip>
            ))}
          </div>
        </div>
      </div>

      {error && <ErrorBanner message={error} onRetry={() => void load()} />}

      <Card padded={false} className="overflow-hidden">
        {loading && !data ? (
          <LoadingSpinner message="Đang tải người dùng..." />
        ) : data && data.content.length === 0 ? (
          <EmptyRow>Không có tài khoản nào khớp bộ lọc. Hãy thử từ khóa khác hoặc chọn "Tất cả".</EmptyRow>
        ) : data ? (
          <>
            <div className={`overflow-x-auto ${loading ? 'opacity-60' : ''}`} aria-busy={loading}>
              <table className="w-full min-w-[860px]">
                <caption className="sr-only">Danh sách người dùng</caption>
                <thead className="border-b border-slate-200 bg-slate-50">
                  <tr>
                    <th scope="col" className={`${thClass} whitespace-nowrap`}>Người dùng</th>
                    <th scope="col" className={`${thClass} whitespace-nowrap`}>Vai trò</th>
                    <th scope="col" className={`${thClass} whitespace-nowrap`}>Trạng thái</th>
                    <th scope="col" className={`${thClass} whitespace-nowrap text-right`}>Lớp sở hữu</th>
                    <th scope="col" className={`${thClass} whitespace-nowrap text-right`}>Đang tham gia</th>
                    <th scope="col" className={`${thClass} whitespace-nowrap`}>Ngày tạo</th>
                    <th scope="col" className={`${thClass} whitespace-nowrap`}>Đăng nhập gần nhất</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {data.content.map((u) => (
                    <tr key={u.id} className="transition-colors duration-micro hover:bg-slate-50">
                      <td className={tdClass}>
                        <div className="flex items-center gap-3">
                          <Avatar name={u.fullName} src={u.avatarUrl} size={32} />
                          <div className="min-w-0">
                            <Link to={`/admin/users/${u.id}`} className="block truncate font-semibold text-slate-900 hover:text-blue-700">
                              {u.fullName}
                            </Link>
                            <p className="truncate text-meta text-slate-600">{u.email}</p>
                            {/* Phones: the table scrolls sideways, so the first column also carries the status. */}
                            <p className="mt-1 flex gap-1.5 md:hidden"><UserStatusPill status={u.status} /></p>
                          </div>
                        </div>
                      </td>
                      <td className={tdClass}><UserRolePill role={u.role} /></td>
                      <td className={tdClass}><UserStatusPill status={u.status} /></td>
                      <td className={`${tdClass} text-right tabular`}>{u.ownedClassCount.toLocaleString('vi-VN')}</td>
                      <td className={`${tdClass} text-right tabular`}>{u.membershipCount.toLocaleString('vi-VN')}</td>
                      <td className={`${tdClass} whitespace-nowrap text-meta text-slate-600 tabular`}>{formatDate(u.createdAt)}</td>
                      <td className={`${tdClass} whitespace-nowrap text-meta text-slate-600 tabular`}>{formatDateTime(u.lastLoginAt) || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} unit="người dùng" onPage={(p) => update({ page: p })} />
          </>
        ) : null}
      </Card>
    </StudioPage>
  );
};
