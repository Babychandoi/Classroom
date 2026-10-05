import React from 'react';
import { Link } from 'react-router-dom';
import { BookOpen } from 'lucide-react';
import { listMyCourses, ME_PAGE_SIZE } from '../api/me';
import { formatDate } from '../api/format';
import { usePagedList } from '../hooks/usePagedList';
import { LoadMore, MePage } from '../components/MePageBits';
import { EmptyState, ErrorBanner, LoadingSpinner } from '../components/UIStates';
import { Card, ClassAvatar, ProgressBar, buttonClass } from '../components/ui';
import type { MyCourse } from '../types';

/** Resume at the next unfinished lesson; fall back to the class's learning page. */
export const courseHref = (course: MyCourse) =>
  course.nextLessonId
    ? `/classes/${course.classSlug}/learn/lessons/${course.nextLessonId}`
    : `/classes/${course.classSlug}/learn`;

const CourseCard: React.FC<{ course: MyCourse; primary: boolean }> = ({ course, primary }) => {
  const percent = Math.max(0, Math.min(100, Math.round(course.progressPercent ?? 0)));
  const done = course.totalLessons > 0 && course.completedLessons >= course.totalLessons;
  const label = !course.started ? 'Bắt đầu học' : done ? 'Xem lại' : 'Học tiếp';
  const last = formatDate(course.lastActivityAt);
  return (
    <Card as="article" hover padded={false} className="flex h-full flex-col p-4 sm:p-5">
      <div className="flex min-w-0 items-center gap-2.5">
        <ClassAvatar title={course.classTitle} seed={course.classId} src={course.classAvatarUrl} size={32} />
        <Link to={`/classes/${course.classSlug}/feed`} className="min-w-0 truncate text-meta font-medium text-slate-600 hover:text-blue-600">
          {course.classTitle}
        </Link>
      </div>
      <h3 className="mt-3 line-clamp-2 text-h3 font-semibold text-slate-900 sm:text-h3-lg">{course.title}</h3>
      {course.description && <p className="mt-1 line-clamp-2 text-ui text-slate-600">{course.description}</p>}
      <div className="mt-4">
        <div className="mb-1.5 flex items-baseline justify-between gap-3 text-meta">
          <span className="tabular font-medium text-slate-900">{course.completedLessons}/{course.totalLessons} bài</span>
          <span className="tabular text-slate-600">{percent}%</span>
        </div>
        <ProgressBar value={percent} label={`Tiến độ ${course.title}`} />
        {last && course.started && <p className="mt-1.5 text-caption text-slate-500">Học gần nhất: {last}</p>}
      </div>
      <div className="mt-auto flex justify-end pt-4">
        <Link
          to={courseHref(course)}
          aria-label={`${label}: ${course.title}`}
          className={buttonClass(primary ? 'primary' : 'secondary', 'md')}
        >
          {label}
        </Link>
      </div>
    </Card>
  );
};

const Grid: React.FC<{ id: string; title: string; courses: MyCourse[]; primaryId?: string }> = ({ id, title, courses, primaryId }) => (
  <section aria-labelledby={id} className="mb-10 last:mb-0">
    <h2 id={id} className="mb-4 text-[17px] font-semibold leading-[23px] text-slate-900 sm:text-h2">{title}</h2>
    <ul className="grid gap-4 sm:grid-cols-2 sm:gap-6 lg:grid-cols-3">
      {courses.map((c) => (
        <li key={c.id} className="flex"><div className="w-full"><CourseCard course={c} primary={c.id === primaryId} /></div></li>
      ))}
    </ul>
  </section>
);

// "Khóa học của tôi": the courses the caller can learn, with progress. The server lists started courses first
// (most recently studied first), so the first started course is the "Học tiếp" shortcut.
export const MyCoursesPage: React.FC = () => {
  const list = usePagedList<MyCourse>((page) => listMyCourses(page, ME_PAGE_SIZE), ME_PAGE_SIZE, 'courses');
  const started = list.items.filter((c) => c.started);
  const notStarted = list.items.filter((c) => !c.started);

  let body: React.ReactNode;
  if (list.loading) {
    body = <LoadingSpinner message="Đang tải khóa học của bạn..." />;
  } else if (list.error) {
    body = <div className="mx-auto max-w-xl"><ErrorBanner message={list.error} onRetry={list.reload} /></div>;
  } else if (list.items.length === 0) {
    body = (
      <EmptyState
        title="Bạn chưa có khóa học nào để học"
        description="Tham gia một lớp học để mở khóa các khóa học trong đó."
        icon={<BookOpen className="h-6 w-6" strokeWidth={1.7} />}
      >
        <Link to="/classes" className={buttonClass('primary', 'md')}>Khám phá lớp học</Link>
        <Link to="/me/classes" className={buttonClass('secondary', 'md')}>Lớp học của tôi</Link>
      </EmptyState>
    );
  } else {
    body = (
      <>
        {started.length > 0 && <Grid id="courses-started" title="Đang học" courses={started} primaryId={started[0].id} />}
        {notStarted.length > 0 && <Grid id="courses-new" title="Chưa bắt đầu" courses={notStarted} />}
        {list.hasMore && (
          <LoadMore label="Xem thêm khóa học" loading={list.loadingMore} error={list.moreError} onClick={() => void list.loadMore()} />
        )}
      </>
    );
  }

  return (
    <MePage title="Khóa học của tôi" description="Tiếp tục học từ đúng bài bạn đang dở.">
      {body}
    </MePage>
  );
};
