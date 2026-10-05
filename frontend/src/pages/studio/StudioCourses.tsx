import React, { useEffect, useRef, useState } from 'react';
import { Link, useOutletContext } from 'react-router-dom';
import { ArrowDown, ArrowUp, BookOpen, Eye, MoreHorizontal, Pencil, Plus } from 'lucide-react';
import { Classroom, Course, Product } from '../../types';
import { api } from '../../api/client';
import { accessPriceLabel, formatDate } from '../../api/format';
import { LoadingSpinner, ErrorBanner, StatusBadge } from '../../components/UIStates';
import { hasStudioPermission, hasCoursePermission } from '../../api/permissions';
import { Badge, Card, CoverImage, buttonClass } from '../../components/ui';
import { PageHeader, StudioPage } from './studioUi';

// The course list is deliberately plain: one card per course, one primary action ("Chỉnh sửa" opens the step-by-step wizard,
// where renaming, content, publishing, archiving, restoring and deleting live) and a small "⋯" menu for what belongs to the
// list itself: moving the course up / down and viewing it as a learner.

const RowMenu: React.FC<{ course: Course; classroom: Classroom; canReorder: boolean; index: number; count: number; busy: boolean; onMove: (direction: -1 | 1) => void }> = ({
  course, classroom, canReorder, index, count, busy, onMove,
}) => {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false); };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { setOpen(false); triggerRef.current?.focus(); } };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey); };
  }, [open]);

  const itemClass = 'flex h-9 w-full items-center gap-2 rounded-[8px] px-2.5 text-left text-meta font-medium text-slate-900 hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-40';
  return (
    <div ref={ref} className="relative">
      <button
        ref={triggerRef} type="button" aria-haspopup="menu" aria-expanded={open} aria-label={`Thao tác khác cho khóa học "${course.title}"`}
        onClick={() => setOpen((o) => !o)}
        className="inline-flex h-10 w-10 items-center justify-center rounded-btn border border-slate-200 bg-white text-slate-600 hover:bg-slate-100"
      >
        <MoreHorizontal className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
      </button>
      {open && (
        <div role="menu" aria-label={`Thao tác với khóa học "${course.title}"`} className="absolute right-0 z-20 mt-1 w-56 rounded-2xl border border-slate-200 bg-white p-1 shadow-e1">
          {canReorder && count > 1 && (
            <>
              <button
                type="button" role="menuitem" aria-label={`Chuyển khóa học "${course.title}" lên trên`} disabled={index === 0 || busy} className={itemClass}
                onClick={() => { setOpen(false); onMove(-1); }}
              >
                <ArrowUp className="h-4 w-4 text-slate-500" strokeWidth={1.75} aria-hidden="true" />Chuyển lên trên
              </button>
              <button
                type="button" role="menuitem" aria-label={`Chuyển khóa học "${course.title}" xuống dưới`} disabled={index === count - 1 || busy} className={itemClass}
                onClick={() => { setOpen(false); onMove(1); }}
              >
                <ArrowDown className="h-4 w-4 text-slate-500" strokeWidth={1.75} aria-hidden="true" />Chuyển xuống dưới
              </button>
            </>
          )}
          <Link role="menuitem" to={`/classes/${classroom.slug}/learn`} className={itemClass}>
            <Eye className="h-4 w-4 text-slate-500" strokeWidth={1.75} aria-hidden="true" />Xem như học viên
          </Link>
        </div>
      )}
    </div>
  );
};

export const StudioCourses: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  // R6-01: creating a course is a class-wide action (there is no course yet to scope it to), so it stays gated by the
  // class-wide grant - mirrors LearningService.createCourse's enforceManage(..., "COURSE", "CREATE", null). Editing an
  // existing course is scoped to that course server-side, so course-scoped-only staff are evaluated per course.
  const canCreateCourse = hasStudioPermission(classroom, 'COURSE', 'CREATE');
  // R18-04: reordering courses is class-wide (the whole ordered id list), so it needs the class-wide EDIT grant.
  const canReorderCourses = hasStudioPermission(classroom, 'COURSE', 'EDIT');
  const canSeeProducts = ['VIEW', 'CREATE', 'EDIT', 'PUBLISH'].some((action) => hasStudioPermission(classroom, 'STORE', action));

  const [courses, setCourses] = useState<Course[]>([]);
  const [products, setProducts] = useState<Record<string, Product>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reordering, setReordering] = useState(false);

  const fetchCourses = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.get<Course[]>(`/classes/${classroom.id}/courses`);
      setCourses(data || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải danh sách khóa học');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchCourses(); }, [classroom.id]);

  // Prices live on the products; they are only shown to people who may see the Shop (best effort, the list works without).
  useEffect(() => {
    if (!canSeeProducts) return;
    let cancelled = false;
    api.get<Product[]>(`/classes/${classroom.id}/studio/products`)
      .then((all) => { if (!cancelled) setProducts(Object.fromEntries((all || []).map((p) => [p.id, p]))); })
      .catch(() => { /* prices are optional */ });
    return () => { cancelled = true; };
  }, [classroom.id, canSeeProducts]);

  // R18-04: move a course one place up/down. The endpoint takes the whole class's ordered id list.
  const moveCourse = async (index: number, direction: -1 | 1) => {
    const targetIndex = index + direction;
    if (targetIndex < 0 || targetIndex >= courses.length || reordering) return;
    const reordered = [...courses];
    const [moved] = reordered.splice(index, 1);
    reordered.splice(targetIndex, 0, moved);
    setReordering(true);
    setError(null);
    try {
      await api.put(`/classes/${classroom.id}/courses/reorder`, reordered.map((c) => c.id));
      setCourses(reordered);
    } catch (err: any) {
      setError(err.message || 'Không thể sắp xếp lại khóa học');
    } finally {
      setReordering(false);
    }
  };

  const wizardBase = `/studio/classes/${classroom.id}/courses`;

  return (
    <StudioPage>
      <PageHeader
        title="Khóa học"
        description="Tổ chức chương trình học theo khóa, danh mục và từng bài học."
        action={canCreateCourse && (
          <Link to={`${wizardBase}/new`} className={buttonClass('primary', 'md')}>
            <Plus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
            <span>Tạo khóa học</span>
          </Link>
        )}
      />

      {loading && <LoadingSpinner message="Đang tải dữ liệu khóa học..." />}
      {error && <ErrorBanner message={error} onRetry={fetchCourses} />}

      {!loading && !error && courses.length === 0 && (
        <Card className="text-center">
          <p className="text-ui text-slate-600">
            Lớp chưa có khóa học nào. {canCreateCourse ? 'Bắt đầu với một khóa ngắn vài bài để học viên có việc để làm ngay.' : ''}
          </p>
        </Card>
      )}

      <div className="space-y-3">
        {courses.map((course, index) => {
          const canEditCourse = hasCoursePermission(classroom, 'COURSE', 'EDIT', course.id);
          const product = course.productId ? products[course.productId] : undefined;
          const archived = course.status === 'ARCHIVED';
          // Drafts reopen at their furthest step (the wizard remembers it); an archived course opens at "Quản lý khóa học".
          const editTo = `${wizardBase}/${course.id}/edit${archived ? '?step=4' : course.status === 'DRAFT' ? '' : '?step=1'}`;
          const created = formatDate(course.createdAt);
          return (
            <article key={course.id} className="rounded-card border border-slate-200 bg-white p-4 shadow-hairline sm:p-5">
              <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <div className="flex min-w-0 items-center gap-3.5">
                  <div className="h-14 w-24 flex-shrink-0 overflow-hidden rounded-thumb bg-slate-100">
                    <CoverImage src={course.coverImageUrl} seed={course.id} icon={<BookOpen className="h-5 w-5" strokeWidth={1.75} />} />
                  </div>
                  <div className="min-w-0">
                    <h3 className="truncate text-h3 font-semibold text-slate-900">{course.title}</h3>
                    <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1">
                      <Badge tone={course.accessMode === 'FREE' ? 'free' : 'paid'} size="sm">
                        {course.accessMode === 'FREE' ? 'Miễn phí' : 'Yêu cầu mua'}
                      </Badge>
                      <StatusBadge status={course.status} />
                      <span className="text-meta text-slate-600 tabular">{course.totalLessons ?? 0} bài học</span>
                      {product && <span className="text-meta font-semibold text-slate-900 tabular">{accessPriceLabel(product.price, product.durationDays)}</span>}
                      {created && <span className="text-meta text-slate-500 tabular">Tạo {created}</span>}
                    </div>
                  </div>
                </div>
                <div className="flex flex-shrink-0 items-center gap-2">
                  {canEditCourse && (
                    <Link to={editTo} className={buttonClass('secondary', 'md')}>
                      <Pencil className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                      <span>{archived ? 'Quản lý' : 'Chỉnh sửa'}</span>
                    </Link>
                  )}
                  <RowMenu
                    course={course} classroom={classroom} canReorder={canReorderCourses} index={index} count={courses.length}
                    busy={reordering} onMove={(direction) => moveCourse(index, direction)}
                  />
                </div>
              </div>
            </article>
          );
        })}
      </div>
    </StudioPage>
  );
};
