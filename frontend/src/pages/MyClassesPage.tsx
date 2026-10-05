import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { GraduationCap, Plus } from 'lucide-react';
import { listMyClasses, ME_PAGE_SIZE } from '../api/me';
import { usePagedList } from '../hooks/usePagedList';
import { ClassCard, isManager } from '../components/ClassCard';
import { LoadMore, MePage } from '../components/MePageBits';
import { EmptyState, ErrorBanner, LoadingSpinner } from '../components/UIStates';
import { FilterChip, buttonClass } from '../components/ui';
import type { Classroom } from '../types';

type ClassFilter = 'ALL' | 'LEAD' | 'JOIN' | 'PRIVATE';
const FILTERS: { key: ClassFilter; label: string }[] = [
  { key: 'ALL', label: 'Tất cả' },
  { key: 'LEAD', label: 'Tôi dẫn dắt' },
  { key: 'JOIN', label: 'Tôi tham gia' },
  { key: 'PRIVATE', label: 'Riêng tư' },
];

const matches = (cls: Classroom, filter: ClassFilter) => {
  if (filter === 'LEAD') return isManager(cls);
  if (filter === 'JOIN') return !isManager(cls);
  if (filter === 'PRIVATE') return cls.visibility === 'PRIVATE';
  return true;
};

const EXPLORE = <Link to="/classes" className={buttonClass('primary', 'md')}>Khám phá lớp học</Link>;

// "Lớp học của tôi": every class the caller owns, staffs or belongs to (also expired / pending ones). PRIVATE classes
// are listed here - the home page never shows them, so this is where their members find them again.
export const MyClassesPage: React.FC = () => {
  const [filter, setFilter] = useState<ClassFilter>('ALL');
  const list = usePagedList<Classroom>((page) => listMyClasses(page, ME_PAGE_SIZE), ME_PAGE_SIZE, 'classes');
  const visible = list.items.filter((cls) => matches(cls, filter));

  let body: React.ReactNode;
  if (list.loading) {
    body = <LoadingSpinner message="Đang tải lớp học của bạn..." />;
  } else if (list.error) {
    body = (
      <div className="mx-auto max-w-xl">
        <ErrorBanner message={list.error} onRetry={list.reload} />
      </div>
    );
  } else if (list.items.length === 0) {
    body = (
      <EmptyState
        title="Bạn chưa tham gia lớp học nào"
        description="Khám phá các lớp học đang mở, hoặc tạo lớp của riêng bạn để bắt đầu."
        icon={<GraduationCap className="h-6 w-6" strokeWidth={1.7} />}
      >
        {EXPLORE}
        <Link to="/classes/new" className={buttonClass('secondary', 'md')}>Tạo lớp học</Link>
      </EmptyState>
    );
  } else {
    body = (
      <>
        {visible.length > 0 ? (
          <ul className="grid gap-4 sm:grid-cols-2 sm:gap-6 lg:grid-cols-3">
            {visible.map((cls) => (
              <li key={cls.id} className="flex">
                <ClassCard cls={cls} personal className="w-full" />
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState
            title="Chưa có lớp nào trong mục này"
            description={list.hasMore ? 'Hãy tải thêm lớp học, hoặc quay lại mục Tất cả.' : 'Thử mục Tất cả để xem mọi lớp học của bạn.'}
            actionText="Xem tất cả"
            onAction={() => setFilter('ALL')}
          />
        )}
        {list.hasMore && (
          <LoadMore label="Xem thêm lớp học" loading={list.loadingMore} error={list.moreError} onClick={() => void list.loadMore()} />
        )}
      </>
    );
  }

  return (
    <MePage
      title="Lớp học của tôi"
      description="Các lớp bạn dẫn dắt hoặc tham gia, kể cả lớp riêng tư."
      actions={
        <Link to="/classes/new" className={buttonClass('secondary', 'md')}>
          <Plus className="h-4 w-4" strokeWidth={1.8} aria-hidden="true" />
          Tạo lớp học
        </Link>
      }
    >
      {!list.loading && !list.error && list.items.length > 0 && (
        <div role="group" aria-label="Lọc lớp học" className="scrollbar-none -mx-4 mb-6 flex gap-2 overflow-x-auto px-4 sm:mx-0 sm:px-0">
          {FILTERS.map((f) => (
            <FilterChip key={f.key} selected={filter === f.key} onClick={() => setFilter(f.key)}>{f.label}</FilterChip>
          ))}
        </div>
      )}
      {body}
    </MePage>
  );
};
