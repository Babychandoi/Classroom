import React, { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Search } from 'lucide-react';
import { api } from '../../api/client';
import { adminApi, AdminClassRow, CLASS_STATUS_LABEL, Page } from '../../api/admin';
import { formatDate } from '../../api/format';
import { Button, Card, ClassAvatar, FilterChip, Input, Select } from '../../components/ui';
import { ErrorBanner, LoadingSpinner } from '../../components/UIStates';
import { FALLBACK_CATEGORIES } from '../../components/create/classMedia';
import { EmptyRow, PageHeader, StudioPage, tdClass, thClass } from '../studio/studioUi';
import { errorText, Pager, Pill } from './adminUi';

const PAGE_SIZE = 20;

const STATUS_CHIPS = [
  { key: '', label: 'Tất cả' },
  { key: 'ACTIVE', label: 'Đang hoạt động' },
  { key: 'ARCHIVED', label: 'Đã lưu trữ' },
  { key: 'SUSPENDED', label: 'Tạm khóa' },
];

export const ClassStatusPill: React.FC<{ status: string }> = ({ status }) => (
  <Pill tone={status === 'ACTIVE' ? 'success' : status === 'SUSPENDED' ? 'warn' : 'neutral'} title={status}>
    {CLASS_STATUS_LABEL[status] ?? status}
  </Pill>
);

/** Square class thumbnail: the class avatar, else its cover, else the letter tile (a class is never round). */
export const ClassThumb: React.FC<{ row: Pick<AdminClassRow, 'id' | 'title' | 'avatarUrl' | 'coverUrl'>; size?: number }> = ({ row, size = 40 }) => {
  const [failed, setFailed] = useState(false);
  const src = row.avatarUrl || row.coverUrl;
  if (src && !failed) {
    return (
      <img
        src={src}
        alt=""
        onError={() => setFailed(true)}
        style={{ width: size, height: size }}
        className={`flex-shrink-0 object-cover ${size >= 44 ? 'rounded-community' : 'rounded-[10px]'}`}
      />
    );
  }
  return <ClassAvatar title={row.title} seed={row.id} size={size} />;
};

/** Lớp học: every class on the platform (incl. private, archived, suspended) with filters in the URL. */
export const AdminClasses: React.FC = () => {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const status = params.get('status') ?? '';
  const visibility = params.get('visibility') ?? '';
  const accessType = params.get('accessType') ?? '';
  const category = params.get('category') ?? '';
  const sort = params.get('sort') ?? 'newest';
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0);

  const [query, setQuery] = useState(q);
  const [categories, setCategories] = useState<string[]>(FALLBACK_CATEGORIES);
  const [data, setData] = useState<Page<AdminClassRow> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => setQuery(q), [q]);

  useEffect(() => {
    let cancelled = false;
    api.get<string[]>('/classes/categories')
      .then((list) => { if (!cancelled && Array.isArray(list) && list.length) setCategories(list); })
      .catch(() => { /* keep the fallback list */ });
    return () => { cancelled = true; };
  }, []);

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
      setData(await adminApi.classes({
        q: q || undefined,
        status: status || undefined,
        visibility: visibility || undefined,
        accessType: accessType || undefined,
        category: category || undefined,
        sort,
        page,
        size: PAGE_SIZE,
      }));
    } catch (err) {
      setError(errorText(err, 'Không thể tải danh sách lớp học.'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, [q, status, visibility, accessType, category, sort, page]);

  const categoryOptions = category && !categories.includes(category) ? [...categories, category] : categories;

  return (
    <StudioPage>
      <PageHeader title="Lớp học" description="Mọi lớp trên nền tảng, kể cả lớp riêng tư, đã lưu trữ hay đang tạm khóa." />

      <div className="grid gap-3">
        <form
          role="search"
          onSubmit={(e) => {
            e.preventDefault();
            update({ q: query.trim().slice(0, 100) });
          }}
          className="flex gap-2"
        >
          <label className="relative min-w-0 flex-1">
            <span className="sr-only">Tìm theo tên lớp, đường dẫn hoặc email chủ lớp</span>
            <Search className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" strokeWidth={1.75} aria-hidden="true" />
            <Input
              type="search"
              value={query}
              maxLength={100}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Tên lớp, đường dẫn hoặc email chủ lớp"
              className="pl-10"
            />
          </label>
          <Button type="submit" className="h-11">Tìm</Button>
        </form>

        <div role="group" aria-label="Lọc theo trạng thái" className="flex flex-wrap gap-2">
          {STATUS_CHIPS.map((chip) => (
            <FilterChip key={chip.key || 'all'} selected={status === chip.key} onClick={() => update({ status: chip.key })}>
              {chip.label}
            </FilterChip>
          ))}
        </div>

        <div className="grid grid-cols-2 gap-2 lg:grid-cols-4">
          <label>
            <span className="sr-only">Hiển thị</span>
            <Select value={visibility} onChange={(e) => update({ visibility: e.target.value })}>
              <option value="">Mọi hiển thị</option>
              <option value="PUBLIC">Công khai</option>
              <option value="PRIVATE">Riêng tư</option>
            </Select>
          </label>
          <label>
            <span className="sr-only">Học phí</span>
            <Select value={accessType} onChange={(e) => update({ accessType: e.target.value })}>
              <option value="">Mọi mức phí</option>
              <option value="FREE">Miễn phí</option>
              <option value="PAID">Trả phí</option>
            </Select>
          </label>
          <label>
            <span className="sr-only">Chủ đề</span>
            <Select value={category} onChange={(e) => update({ category: e.target.value })}>
              <option value="">Mọi chủ đề</option>
              {categoryOptions.map((c) => <option key={c} value={c}>{c}</option>)}
            </Select>
          </label>
          <label>
            <span className="sr-only">Sắp xếp</span>
            <Select value={sort} onChange={(e) => update({ sort: e.target.value })}>
              <option value="newest">Mới nhất</option>
              <option value="members">Nhiều thành viên nhất</option>
              <option value="name">Theo tên</option>
            </Select>
          </label>
        </div>
      </div>

      {error && <ErrorBanner message={error} onRetry={() => void load()} />}

      <Card padded={false} className="overflow-hidden">
        {loading && !data ? (
          <LoadingSpinner message="Đang tải lớp học..." />
        ) : data && data.content.length === 0 ? (
          <EmptyRow>Không có lớp nào khớp bộ lọc. Hãy bỏ bớt điều kiện lọc để xem thêm.</EmptyRow>
        ) : data ? (
          <>
            <div className={`overflow-x-auto ${loading ? 'opacity-60' : ''}`} aria-busy={loading}>
              <table className="w-full min-w-[820px]">
                <caption className="sr-only">Danh sách lớp học</caption>
                <thead className="border-b border-slate-200 bg-slate-50">
                  <tr>
                    <th scope="col" className={`${thClass} whitespace-nowrap`}>Lớp học</th>
                    <th scope="col" className={`${thClass} whitespace-nowrap`}>Chủ lớp</th>
                    <th scope="col" className={`${thClass} whitespace-nowrap`}>Trạng thái</th>
                    <th scope="col" className={`${thClass} whitespace-nowrap`}>Hiển thị · phí</th>
                    <th scope="col" className={`${thClass} whitespace-nowrap text-right`}>Thành viên</th>
                    <th scope="col" className={`${thClass} whitespace-nowrap text-right`}>Chờ duyệt</th>
                    <th scope="col" className={`${thClass} whitespace-nowrap`}>Ngày tạo</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {data.content.map((c) => (
                    <tr key={c.id} className="transition-colors duration-micro hover:bg-slate-50">
                      <td className={tdClass}>
                        <div className="flex items-center gap-3">
                          <ClassThumb row={c} />
                          <div className="min-w-0 max-w-[180px]">
                            <Link to={`/admin/classes/${c.id}`} className="block truncate font-semibold text-slate-900 hover:text-blue-700">
                              {c.title}
                            </Link>
                            <p className="truncate text-meta text-slate-600">
                              /{c.slug}{c.category ? ` · ${c.category}` : ''}
                            </p>
                            {/* Phones: the table scrolls sideways, so the first column also carries the essentials. */}
                            <p className="mt-1 flex items-center gap-1.5 text-caption text-slate-600 tabular md:hidden">
                              <ClassStatusPill status={c.status} />
                              <span>{c.memberCount.toLocaleString('vi-VN')} thành viên</span>
                            </p>
                          </div>
                        </div>
                      </td>
                      <td className={tdClass}>
                        <Link to={`/admin/users/${c.owner.id}`} className="block max-w-[150px] truncate font-medium text-slate-900 hover:text-blue-700">
                          {c.owner.fullName}
                        </Link>
                        <p className="max-w-[150px] truncate text-meta text-slate-600">{c.owner.email}</p>
                      </td>
                      <td className={tdClass}><ClassStatusPill status={c.status} /></td>
                      <td className={tdClass}>
                        <div className="flex gap-1.5">
                          <Pill tone={c.visibility === 'PRIVATE' ? 'dark' : 'neutral'}>{c.visibility === 'PRIVATE' ? 'Riêng tư' : 'Công khai'}</Pill>
                          <Pill tone={c.accessType === 'PAID' ? 'paid' : 'success'}>{c.accessType === 'PAID' ? 'Trả phí' : 'Miễn phí'}</Pill>
                        </div>
                      </td>
                      <td className={`${tdClass} text-right tabular`}>{c.memberCount.toLocaleString('vi-VN')}</td>
                      <td className={`${tdClass} text-right tabular ${c.pendingRequestCount > 0 ? 'font-semibold' : 'text-slate-500'}`}>
                        {c.pendingRequestCount.toLocaleString('vi-VN')}
                      </td>
                      <td className={`${tdClass} whitespace-nowrap text-meta text-slate-600 tabular`}>{formatDate(c.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} unit="lớp học" onPage={(p) => update({ page: p })} />
          </>
        ) : null}
      </Card>
    </StudioPage>
  );
};
