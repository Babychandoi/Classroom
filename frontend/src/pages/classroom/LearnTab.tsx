import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useOutletContext, Link, useNavigate } from 'react-router-dom';
import { Classroom, Course, Lesson } from '../../types';
import { api } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { LoadingSpinner, ErrorBanner, EmptyState } from '../../components/UIStates';
import { Avatar, Badge, CoverImage, FilterChip, ProgressBar, buttonClass } from '../../components/ui';
import { ArrowRight, BookOpen, Check, ChevronDown, Lock, PlayCircle, ShoppingBag } from 'lucide-react';

// R14-12: a paid entitlement that has not started yet (accessReason OWNED_UPCOMING) is shown as
// "Bắt đầu từ dd/MM/yyyy" - the learner already owns it, so there is no purchase/renew call to action.
const formatViDate = (iso: string) => new Date(iso).toLocaleDateString('vi-VN');
const upcomingLabel = (course: Course) =>
  course.accessStartsAt ? `Bắt đầu từ ${formatViDate(course.accessStartsAt)}` : 'Sắp mở';

// R19-12: the backend reports whether the course's product can still be bought (PUBLISHED). Older payloads
// (and FREE courses) do not carry the flag, so only an explicit `false` means "not for sale".
const isNotForSale = (course: Course) => course.canPurchase === false;
const ownedExpiryLabel = (course: Course) =>
  course.canLearn && course.accessReason === 'OWNED' && course.expiresAt
    ? `Còn hạn đến ${formatViDate(course.expiresAt)}`
    : null;

type AccessFilter = 'ALL' | 'FREE' | 'PAID';

const isInProgress = (course: Course) =>
  course.canLearn && course.completedLessons > 0 && course.completedLessons < course.totalLessons;

/** Every lesson of a course in curriculum order. */
const lessonsOf = (course: Course): Lesson[] => (course.sections ?? []).flatMap((section) => section.lessons ?? []);

/** Where "Học tiếp" lands: the first lesson not completed yet, or the first lesson of a finished course. */
const resumeLessonOf = (course: Course): Lesson | null => {
  const lessons = lessonsOf(course);
  return lessons.find((lesson) => !lesson.completed) ?? lessons[0] ?? null;
};

const ctaLabel = (course: Course) => (course.completedLessons > 0 ? 'Học tiếp' : 'Vào học');

const AccessBadge: React.FC<{ course: Course; size?: 'md' | 'sm' }> = ({ course, size = 'sm' }) =>
  course.accessMode === 'FREE' ? (
    <Badge tone="free" size={size}>Miễn phí</Badge>
  ) : (
    <Badge tone="paid" size={size}>Trả phí</Badge>
  );

export const LearnTab: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const { user } = useAuth();
  const navigate = useNavigate();

  const [courses, setCourses] = useState<Course[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<AccessFilter>('ALL');

  // The featured (selected) course: its details carry the curriculum, the access state and the call to action.
  const [selectedCourse, setSelectedCourse] = useState<Course | null>(null);
  const [loadingDetails, setLoadingDetails] = useState(false);
  const featuredRef = useRef<HTMLElement | null>(null);

  const lessonPath = (lessonId: string) => `/classes/${classroom.slug}/learn/lessons/${lessonId}`;

  const loadCourseDetails = async (courseId: string): Promise<Course | null> => {
    try {
      setLoadingDetails(true);
      const raw = await api.get<Course>(`/courses/${courseId}`);
      // GET /courses/{id} carries the curriculum but leaves totalLessons/completedLessons at 0, so the counts
      // shown in the featured card are taken from the lessons themselves.
      const lessons = raw.sections ? lessonsOf(raw) : null;
      const data = lessons
        ? { ...raw, totalLessons: lessons.length, completedLessons: lessons.filter((l) => l.completed).length }
        : raw;
      setSelectedCourse(data);
      return data;
    } catch (err: any) {
      alert(err.message || 'Không thể tải chi tiết khóa học');
      return null;
    } finally {
      setLoadingDetails(false);
    }
  };

  const fetchCourses = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.get<Course[]>(`/classes/${classroom.id}/courses`);
      setCourses(data || []);
      if (data && data.length > 0 && !selectedCourse) {
        // Featured = the course the learner is in the middle of, otherwise the first one.
        loadCourseDetails((data.find(isInProgress) ?? data[0]).id);
      }
    } catch (err: any) {
      setError(err.message || 'Không thể tải danh sách khóa học');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchCourses();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classroom.id, user]);

  // Design: clicking a course goes straight into learning (no separate overview screen). A course the learner
  // cannot open yet (or one without lessons) is shown in the featured card instead, with its access conditions.
  const openCourse = async (course: Course) => {
    const details = await loadCourseDetails(course.id);
    if (!details) return;
    const target = details.canLearn ? resumeLessonOf(details) : null;
    if (target) {
      navigate(lessonPath(target.id));
      return;
    }
    featuredRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  };

  const freeCount = courses.filter((c) => c.accessMode === 'FREE').length;
  const paidCount = courses.length - freeCount;
  const visibleCourses = useMemo(
    () => courses.filter((c) => filter === 'ALL' || (filter === 'FREE' ? c.accessMode === 'FREE' : c.accessMode !== 'FREE')),
    [courses, filter],
  );

  return (
    <div className="space-y-8 sm:space-y-10">
      {loading && <LoadingSpinner message="Đang tải danh mục khóa học..." />}
      {error && <ErrorBanner message={error} onRetry={fetchCourses} />}

      {!loading && !error && courses.length === 0 && (
        <EmptyState
          title="Chưa có khóa học nào"
          description="Người dẫn dắt đang chuẩn bị bài giảng. Trong lúc chờ, bạn có thể xem tab Thảo luận hoặc Tài liệu của lớp."
          icon={<BookOpen className="h-6 w-6" strokeWidth={1.75} />}
        />
      )}

      {selectedCourse && (
        <section ref={featuredRef} aria-labelledby="featured-course-heading" className="scroll-mt-32">
          <h2 id="featured-course-heading" className="mb-3.5 text-h2-sm font-semibold text-slate-900">
            {isInProgress(selectedCourse) ? 'Học tiếp' : 'Khóa học nổi bật'}
          </h2>
          <FeaturedCourse
            course={selectedCourse}
            classroom={classroom}
            loadingDetails={loadingDetails}
            lessonPath={lessonPath}
            onStart={(lessonId) => navigate(lessonPath(lessonId))}
          />
        </section>
      )}

      {courses.length > 0 && (
        <section aria-labelledby="all-courses-heading">
          <div className="mb-5 flex flex-wrap items-end justify-between gap-4">
            <div>
              <h2 id="all-courses-heading" className="text-h2-sm font-semibold text-slate-900 sm:text-h2">Tất cả khóa học</h2>
              <p className="mt-1 text-ui text-slate-600 sm:text-body-sm">
                <span className="tabular">{courses.length}</span> khóa trong lớp học
              </p>
            </div>
            <div role="group" aria-label="Lọc khóa học theo quyền truy cập" className="-mx-4 flex gap-2 overflow-x-auto px-4 scrollbar-none sm:mx-0 sm:px-0">
              <FilterChip selected={filter === 'ALL'} onClick={() => setFilter('ALL')}>
                Tất cả · <span className="tabular">{courses.length}</span>
              </FilterChip>
              <FilterChip selected={filter === 'FREE'} onClick={() => setFilter('FREE')}>
                Miễn phí · <span className="tabular">{freeCount}</span>
              </FilterChip>
              <FilterChip selected={filter === 'PAID'} onClick={() => setFilter('PAID')}>
                Trả phí · <span className="tabular">{paidCount}</span>
              </FilterChip>
            </div>
          </div>

          {visibleCourses.length === 0 ? (
            <p className="rounded-card border border-dashed border-slate-300 bg-white px-6 py-10 text-center text-ui text-slate-600">
              Không có khóa học nào trong nhóm này. Chọn “Tất cả” để xem mọi khóa của lớp.
            </p>
          ) : (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 sm:gap-6 lg:grid-cols-3">
              {visibleCourses.map((course) => (
                <CourseCard
                  key={course.id}
                  course={course}
                  selected={selectedCourse?.id === course.id}
                  onOpen={() => openCourse(course)}
                />
              ))}
            </div>
          )}
        </section>
      )}
    </div>
  );
};

// ---------------------------------------------------------------------------------------------------------------

const CourseCard: React.FC<{ course: Course; selected: boolean; onOpen: () => void }> = ({ course, selected, onOpen }) => {
  const expiry = ownedExpiryLabel(course);
  const lockedTitle =
    course.accessReason === 'EXPIRED' && course.expiresAt
      ? `Sản phẩm hết hạn ngày ${formatViDate(course.expiresAt)}`
      : course.accessReason === 'OWNED_UPCOMING'
        ? upcomingLabel(course)
        : undefined;
  return (
    // R18-03: a real button (was a click-only div): reachable with Tab, activated with Enter/Space, with a
    // visible focus ring. Its contents are phrasing elements only, as HTML requires inside a button.
    <button
      type="button"
      onClick={onOpen}
      aria-pressed={selected}
      className={`card-hover flex w-full flex-col overflow-hidden rounded-2xl border bg-white text-left shadow-hairline focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-600 focus-visible:ring-offset-2 sm:rounded-card ${
        selected ? 'border-blue-200 ring-1 ring-blue-200' : 'border-slate-200'
      }`}
    >
      <span className="block aspect-video w-full flex-shrink-0 bg-slate-100">
        <CoverImage
          src={course.coverImageUrl}
          seed={course.id}
          icon={<BookOpen className="h-8 w-8 sm:h-10 sm:w-10" strokeWidth={1.4} />}
        />
      </span>
      <span className="flex flex-1 flex-col px-3.5 pb-3.5 pt-3 sm:px-[18px] sm:pb-[18px] sm:pt-4">
        <span className="flex flex-wrap items-center gap-2">
          <AccessBadge course={course} />
          <span className="text-meta text-slate-500 tabular">{course.totalLessons} bài</span>
          {selected && <span className="sr-only">(đang chọn)</span>}
        </span>
        <span className="mt-2.5 block truncate text-body-sm font-semibold text-slate-900 sm:text-h3">{course.title}</span>
        <span className="mt-1 line-clamp-2 text-meta text-slate-600 sm:text-ui">
          {course.description || 'Chưa có mô tả chi tiết cho khóa học.'}
        </span>
        {expiry && <span className="mt-1.5 block text-caption font-semibold text-green-800">{expiry}</span>}

        <span className="mt-auto block pt-3.5">
          {course.canLearn ? (
            <span className="flex items-center gap-2.5">
              <span className="min-w-0 flex-1">
                <ProgressBar
                  value={course.completedLessons}
                  max={Math.max(course.totalLessons, 1)}
                  label={`Tiến độ ${course.title}`}
                />
              </span>
              <span className="flex-shrink-0 text-caption font-medium text-slate-600 tabular">
                {course.completedLessons}/{course.totalLessons}
              </span>
              <span className="inline-flex flex-shrink-0 items-center gap-1 text-meta font-semibold text-blue-600">
                {ctaLabel(course)}
                <ArrowRight className="h-3.5 w-3.5" strokeWidth={2} aria-hidden="true" />
              </span>
            </span>
          ) : (
            <span className="flex items-center gap-1.5 text-meta font-medium text-slate-600" title={lockedTitle}>
              <Lock className="h-3.5 w-3.5 flex-shrink-0 text-slate-400" strokeWidth={2} aria-hidden="true" />
              <span>
                {course.accessReason === 'EXPIRED' ? 'Đã hết hạn' : course.accessReason === 'OWNED_UPCOMING' ? upcomingLabel(course) : 'Khóa bảo vệ'}
              </span>
            </span>
          )}
        </span>
      </span>
    </button>
  );
};

// ---------------------------------------------------------------------------------------------------------------

const FeaturedCourse: React.FC<{
  course: Course;
  classroom: Classroom;
  loadingDetails: boolean;
  lessonPath: (lessonId: string) => string;
  onStart: (lessonId: string) => void;
}> = ({ course, classroom, loadingDetails, lessonPath, onStart }) => {
  const resume = course.canLearn ? resumeLessonOf(course) : null;
  const sections = course.sections ?? [];
  const lessonCount = lessonsOf(course).length;
  const expiry = ownedExpiryLabel(course);

  return (
    <article className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-hairline sm:rounded-card">
      <div className="grid grid-cols-1 md:grid-cols-[minmax(0,400px)_minmax(0,1fr)] lg:grid-cols-[440px_minmax(0,1fr)]">
        <div className="aspect-video bg-slate-100 md:aspect-auto md:min-h-[240px]">
          <CoverImage src={course.coverImageUrl} seed={course.id} icon={<BookOpen className="h-10 w-10" strokeWidth={1.4} />} />
        </div>

        <div className="flex min-w-0 flex-col px-4 py-4 sm:px-7 sm:py-6">
          <div className="flex flex-wrap items-center gap-2">
            <AccessBadge course={course} />
            {course.accessMode === 'PURCHASE_REQUIRED' && !course.canLearn && <Badge tone="neutral" size="sm">Yêu cầu mua</Badge>}
            <span className="text-meta text-slate-500 tabular">{course.totalLessons} bài</span>
          </div>
          <h3 className="mt-3 text-h3 font-semibold text-slate-900 sm:text-[22px] sm:leading-[30px] sm:tracking-[-0.3px]">{course.title}</h3>
          <p className="mt-1.5 text-meta text-slate-600 sm:text-ui sm:leading-[22px]">
            {course.description || 'Chưa có mô tả chi tiết cho khóa học.'}
          </p>
          {classroom.ownerName && (
            <div className="mt-3 flex items-center gap-2">
              <Avatar name={classroom.ownerName} src={classroom.ownerAvatarUrl} size={22} />
              <span className="text-meta text-slate-600">
                <strong className="font-semibold text-slate-900">{classroom.ownerName}</strong> · Người dẫn dắt
              </span>
            </div>
          )}

          <div className="mt-auto pt-4">
            {course.canLearn ? (
              <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:gap-4">
                <div className="flex flex-1 items-center gap-2.5">
                  <div className="min-w-0 flex-1">
                    <ProgressBar value={course.completedLessons} max={Math.max(course.totalLessons, 1)} label="Tiến độ khóa học" />
                  </div>
                  <span className="flex-shrink-0 text-caption font-medium text-slate-600 tabular">
                    {course.completedLessons}/{course.totalLessons}
                  </span>
                </div>
                {resume ? (
                  <button type="button" onClick={() => onStart(resume.id)} className={buttonClass('primary', 'md', 'w-full sm:w-auto')}>
                    {ctaLabel(course)}
                    <ArrowRight className="h-3.5 w-3.5" strokeWidth={2} aria-hidden="true" />
                  </button>
                ) : (
                  !loadingDetails && <span className="text-meta text-slate-500">Khóa học chưa có bài học nào.</span>
                )}
              </div>
            ) : (
              <AccessGate course={course} classroom={classroom} />
            )}

            {/* R19-12: an existing buyer sees when their access ends - also after the product was taken off sale. */}
            {expiry && (
              <div className="mt-3 space-y-0.5">
                <p className="text-caption font-semibold text-green-800">{expiry}</p>
                {isNotForSale(course) && (
                  <p className="text-caption text-slate-500">Khóa học đã ngừng bán nên không thể gia hạn thêm.</p>
                )}
              </div>
            )}
          </div>
        </div>
      </div>

      {loadingDetails && (
        <div className="border-t border-slate-100">
          <LoadingSpinner message="Đang tải giáo trình..." />
        </div>
      )}

      {!loadingDetails && course.sections && (
        <details className="group border-t border-slate-100" open={!course.canLearn || undefined}>
          <summary className="flex min-h-[48px] cursor-pointer list-none items-center justify-between gap-3 px-4 py-3 text-ui font-semibold text-slate-900 hover:bg-slate-50 sm:px-7 [&::-webkit-details-marker]:hidden">
            <span>
              Chương trình học
              <span className="ml-2 font-normal text-slate-500 tabular">
                {sections.length} chương · {lessonCount} bài
              </span>
            </span>
            <ChevronDown className="h-4 w-4 text-slate-400 transition-transform duration-state group-open:rotate-180" strokeWidth={1.75} aria-hidden="true" />
          </summary>
          {sections.length === 0 ? (
            <p className="px-4 pb-5 text-ui text-slate-500 sm:px-7">Khóa học chưa có bài học nào.</p>
          ) : (
            <div className="pb-3">
              {sections.map((section) => {
                const done = section.lessons.filter((l) => l.completed).length;
                return (
                  <div key={section.id} className="border-t border-slate-100 first:border-t-0">
                    <div className="flex items-baseline justify-between gap-3 px-4 pb-1.5 pt-3.5 sm:px-7">
                      <p className="text-meta font-semibold text-slate-900">{section.title}</p>
                      <p className="flex-shrink-0 text-caption text-slate-500 tabular">
                        {course.canLearn ? `${done}/${section.lessons.length} hoàn thành` : `${section.lessons.length} bài học`}
                      </p>
                    </div>
                    <ul>
                      {section.lessons.map((lesson) => {
                        const provider = lesson.videoProvider === 'YOUTUBE' ? 'YouTube' : lesson.videoProvider === 'GOOGLE_DRIVE' ? 'Google Drive' : null;
                        const meta = [provider, lesson.durationMinutes > 0 ? `${lesson.durationMinutes} phút` : null].filter(Boolean).join(' · ') || null;
                        const row = (
                          <>
                            {lesson.completed ? (
                              <span className="inline-flex h-[18px] w-[18px] items-center justify-center rounded-full bg-green-100" aria-label="Đã hoàn thành">
                                <Check className="h-2.5 w-2.5 text-green-600" strokeWidth={3} />
                              </span>
                            ) : course.canLearn ? (
                              <PlayCircle className="h-[18px] w-[18px] text-slate-400" strokeWidth={1.75} aria-hidden="true" />
                            ) : (
                              <span className="inline-flex h-[18px] w-[18px] items-center justify-center rounded-full bg-slate-100" aria-label="Đang khóa">
                                <Lock className="h-2.5 w-2.5 text-slate-400" strokeWidth={2.4} />
                              </span>
                            )}
                            <span className={`min-w-0 truncate text-meta font-medium ${course.canLearn ? 'text-slate-900' : 'text-slate-500'}`}>
                              {lesson.title}
                            </span>
                            {meta && <span className="flex-shrink-0 text-caption text-slate-500 tabular">{meta}</span>}
                          </>
                        );
                        return (
                          <li key={lesson.id}>
                            {course.canLearn ? (
                              <Link
                                to={lessonPath(lesson.id)}
                                className="grid min-h-[40px] grid-cols-[22px_minmax(0,1fr)_auto] items-center gap-2.5 px-4 py-1.5 transition-colors duration-micro hover:bg-slate-50 sm:px-7"
                              >
                                {row}
                              </Link>
                            ) : (
                              <div className="grid min-h-[40px] grid-cols-[22px_minmax(0,1fr)_auto] items-center gap-2.5 px-4 py-1.5 sm:px-7">{row}</div>
                            )}
                          </li>
                        );
                      })}
                    </ul>
                  </div>
                );
              })}
            </div>
          )}
        </details>
      )}
    </article>
  );
};

/** Why a course cannot be opened yet, and the one next step that exists for it (store / wait / nothing). */
const AccessGate: React.FC<{ course: Course; classroom: Classroom }> = ({ course, classroom }) => (
  <div className="space-y-2 rounded-2xl bg-slate-50 px-4 py-3.5">
    {/* R13-09: distinct copy for an expired buyer vs. someone who never purchased at all. */}
    {course.accessReason === 'EXPIRED' && course.expiresAt && (
      <p className="text-meta font-semibold text-red-600">Sản phẩm hết hạn ngày {formatViDate(course.expiresAt)}</p>
    )}
    {course.accessReason === 'OWNED_UPCOMING' ? (
      <p className="text-meta font-semibold text-slate-900">Bạn đã mua khóa học này. {upcomingLabel(course)}.</p>
    ) : isNotForSale(course) ? (
      // R19-12: the product was archived / is not published - the store no longer lists it, so a link there
      // (or a renewal call to action) would lead nowhere.
      <p className="text-meta font-semibold text-slate-600">Khóa học hiện không mở bán</p>
    ) : (
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <p className="text-meta text-slate-600">
          {course.accessReason === 'EXPIRED'
            ? 'Gia hạn để học tiếp — tiến độ của bạn được giữ nguyên.'
            : 'Khóa học này cần mua để mở toàn bộ bài học.'}
        </p>
        <Link to={`/classes/${classroom.slug}/store`} className={buttonClass('primary', 'md', 'flex-shrink-0')}>
          <ShoppingBag className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
          <span>{course.accessReason === 'EXPIRED' ? 'Gia hạn tại Cửa hàng' : 'Mua khóa học tại Cửa hàng'}</span>
        </Link>
      </div>
    )}
  </div>
);
