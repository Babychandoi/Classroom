import React, { useEffect, useState } from 'react';
import { useOutletContext, Link } from 'react-router-dom';
import { Classroom, ClassEvent, Order } from '../../types';
import { api } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { hasAnyStudioPermission, hasStudioPermission } from '../../api/permissions';
import { Button, Card, DateBlock, buttonClass, formatCompact } from '../../components/ui';
import { StudioPage, Notice } from './studioUi';
import {
  BookOpen,
  CalendarDays,
  CheckSquare,
  ClipboardCheck,
  Receipt,
  RefreshCw,
  Sparkles,
  UserPlus,
  Users,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

const WEEKDAYS = ['Chủ Nhật', 'Thứ Hai', 'Thứ Ba', 'Thứ Tư', 'Thứ Năm', 'Thứ Sáu', 'Thứ Bảy'];

/** "Thứ Bảy, 11/07/2026" */
export const formatToday = (date: Date) =>
  `${WEEKDAYS[date.getDay()]}, ${String(date.getDate()).padStart(2, '0')}/${String(date.getMonth() + 1).padStart(2, '0')}/${date.getFullYear()}`;


const timeLabel = (iso: string) => {
  const d = new Date(iso);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
};

/** Counts the dashboard reads from existing endpoints; `null` = not loaded / not allowed for this viewer. */
interface OverviewCounts {
  examQueue: number | null;
  assignmentQueue: number | null;
  pendingOrders: number | null;
  courses: number | null;
  draftCourses: number | null;
  exams: number | null;
}

type Task = {
  key: string;
  icon: LucideIcon;
  tone: string;
  title: string;
  detail: string;
  cta: string;
  to: string;
};

/**
 * Studio dashboard (Dashboard.dc.html): greeting + date, "Làm một việc này trước" (the single most useful next step,
 * derived from real queues), the rest of today's queue, and the class's real counts. Every number on this page
 * comes from an existing endpoint; blocks the backend has no data for (revenue, retention, diagnosis) are left out.
 */
export const StudioOverview: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom & { pendingRequestCount?: number } }>();
  const { user } = useAuth();
  const canGradeExams = hasAnyStudioPermission(classroom, 'EXAM', 'GRADE');
  const canGradeAssignments = hasAnyStudioPermission(classroom, 'COURSE', 'GRADE');
  const canViewOrders = hasStudioPermission(classroom, 'STORE', 'VIEW');
  const canViewCourses = hasAnyStudioPermission(classroom, 'COURSE', 'VIEW') || hasAnyStudioPermission(classroom, 'COURSE', 'EDIT');
  const canCreateCourse = hasStudioPermission(classroom, 'COURSE', 'CREATE');
  const canViewMembers = hasStudioPermission(classroom, 'MEMBER', 'VIEW');
  const canViewEvents = hasStudioPermission(classroom, 'EVENT', 'VIEW') || hasStudioPermission(classroom, 'EVENT', 'EDIT');
  const canRebuildLeaderboard = hasStudioPermission(classroom, 'LEADERBOARD', 'EDIT');

  const [counts, setCounts] = useState<OverviewCounts>({
    examQueue: null, assignmentQueue: null, pendingOrders: null, courses: null, draftCourses: null, exams: null,
  });
  const [nextEvent, setNextEvent] = useState<ClassEvent | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [rebuilding, setRebuilding] = useState(false);
  const [rebuildMessage, setRebuildMessage] = useState<{ tone: 'success' | 'warn'; text: string } | null>(null);

  useEffect(() => {
    let cancelled = false;
    // Each request is independent and optional: a failure (or a missing grant) just leaves its number out.
    const safe = async <T,>(enabled: boolean, load: () => Promise<T>): Promise<T | null> => {
      if (!enabled) return null;
      try { return await load(); } catch { return null; }
    };
    (async () => {
      const [examQueue, assignmentQueue, orders, courses, exams, events] = await Promise.all([
        safe(canGradeExams, () => api.get<unknown[]>(`/classes/${classroom.id}/grading-queue`)),
        safe(canGradeAssignments, () => api.get<unknown[]>(`/classes/${classroom.id}/assignment-queue`)),
        safe(canViewOrders, () => api.get<Order[]>(`/classes/${classroom.id}/orders`)),
        safe(canViewCourses, () => api.get<Array<{ status?: string }>>(`/classes/${classroom.id}/courses`)),
        safe(true, () => api.get<unknown[]>(`/classes/${classroom.id}/exams`)),
        safe(true, () => api.get<ClassEvent[]>(`/classes/${classroom.id}/events?scope=upcoming`)),
      ]);
      if (cancelled) return;
      setCounts({
        examQueue: examQueue ? examQueue.length : null,
        assignmentQueue: assignmentQueue ? assignmentQueue.length : null,
        pendingOrders: orders ? orders.filter((o) => o.status === 'PENDING').length : null,
        courses: courses ? courses.length : null,
        draftCourses: courses ? courses.filter((c) => c.status === 'DRAFT').length : null,
        exams: exams ? exams.length : null,
      });
      setNextEvent((events || []).find((e) => e.status !== 'CANCELLED') ?? null);
      setLoaded(true);
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classroom.id]);

  const handleRebuildLeaderboard = async () => {
    setRebuilding(true);
    setRebuildMessage(null);
    try {
      await api.post(`/classes/${classroom.id}/leaderboard/rebuild`);
      setRebuildMessage({ tone: 'success', text: 'Đã tính lại bảng xếp hạng cho toàn bộ học viên.' });
    } catch (err: any) {
      setRebuildMessage({ tone: 'warn', text: err.message || 'Chưa tính lại được bảng xếp hạng. Bạn thử lại sau ít phút nhé.' });
    } finally {
      setRebuilding(false);
    }
  };

  const base = `/studio/classes/${classroom.id}`;
  const gradingTotal = (counts.examQueue ?? 0) + (counts.assignmentQueue ?? 0);

  // Today's queue, most urgent first. Only real, non-zero work becomes a task.
  const tasks: Task[] = [];
  // Join requests first: these people are waiting at the door. The count is only filled for MEMBER:VIEW holders.
  const pendingRequests = classroom.pendingRequestCount ?? 0;
  if (pendingRequests > 0 && canViewMembers) {
    tasks.push({
      key: 'requests', icon: UserPlus, tone: 'bg-warn-soft text-amber-800',
      title: `Duyệt ${pendingRequests} yêu cầu tham gia lớp`,
      detail: 'Những người này chỉ vào được lớp sau khi bạn duyệt.',
      cta: 'Xem yêu cầu', to: `${base}/members`,
    });
  }
  if (gradingTotal > 0) {
    const parts = [
      counts.assignmentQueue ? `${counts.assignmentQueue} bài tập` : null,
      counts.examQueue ? `${counts.examQueue} bài thi` : null,
    ].filter(Boolean).join(' và ');
    tasks.push({
      key: 'grading', icon: CheckSquare, tone: 'bg-warn-soft text-amber-800',
      title: `Chấm ${gradingTotal} bài đang chờ`,
      detail: `${parts} học viên đã nộp đang chờ bạn chấm và phản hồi.`,
      cta: 'Mở hàng chấm bài', to: `${base}/grading`,
    });
  }
  if (counts.pendingOrders) {
    tasks.push({
      key: 'orders', icon: Receipt, tone: 'bg-violet-100 text-violet-800',
      title: `${counts.pendingOrders} đơn hàng chưa thanh toán xong`,
      detail: 'Kiểm tra trạng thái các đơn đang chờ để người mua sớm nhận quyền lợi.',
      cta: 'Xem đơn hàng', to: `${base}/store`,
    });
  }
  if (counts.draftCourses) {
    tasks.push({
      key: 'drafts', icon: BookOpen, tone: 'bg-blue-100 text-blue-800',
      title: `${counts.draftCourses} khóa học còn ở bản nháp`,
      detail: 'Học viên chỉ thấy khóa học sau khi bạn xuất bản.',
      cta: 'Mở khóa học', to: `${base}/courses`,
    });
  }
  if (counts.courses === 0 && canCreateCourse) {
    tasks.push({
      key: 'first-course', icon: Sparkles, tone: 'bg-blue-100 text-blue-800',
      title: 'Tạo khóa học đầu tiên của lớp',
      detail: 'Một khóa học với vài bài ngắn là đủ để học viên có việc để làm ngay hôm nay.',
      cta: 'Tạo khóa học', to: `${base}/courses`,
    });
  }
  const [firstTask, ...otherTasks] = tasks;

  const pulse: { label: string; value: number | null | undefined; to?: string }[] = [
    { label: 'Thành viên', value: classroom.memberCount, to: canViewMembers ? `${base}/members` : undefined },
    { label: 'Khóa học', value: counts.courses },
    { label: 'Kỳ thi', value: counts.exams },
    { label: 'Sự kiện sắp tới', value: classroom.upcomingEventCount },
    { label: 'Bài chờ chấm', value: canGradeExams || canGradeAssignments ? gradingTotal : null },
    { label: 'Đơn chờ thanh toán', value: counts.pendingOrders },
  ];
  // The full display name, like the home page greeting: names may carry a title ("Thầy ..."), so the last word alone reads oddly.
  const name = user?.fullName?.trim() ?? '';

  return (
    <StudioPage>
      {/* Greeting */}
      <section className="flex flex-wrap items-end justify-between gap-4">
        <div className="min-w-0">
          <p className="text-meta text-slate-500 tabular">{formatToday(new Date())}</p>
          <h1 className="mt-1.5 text-h2-sm font-semibold tracking-[-0.4px] text-slate-900 sm:text-[28px] sm:leading-9">
            {name ? `Chào ${name} — lớp của bạn hôm nay` : 'Chào bạn — lớp của bạn hôm nay'}
          </h1>
        </div>
        <span className="inline-flex h-[34px] items-center gap-2 rounded-full border border-slate-200 bg-white px-3.5 text-meta font-semibold text-slate-600 tabular">
          <Users className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
          {classroom.memberCount.toLocaleString('vi-VN')} thành viên
        </span>
      </section>

      <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_340px] lg:gap-6">
        {/* Left column */}
        <div className="flex min-w-0 flex-col gap-5">
          {/* Do this one thing first */}
          <Card as="section" padded={false} aria-labelledby="first-task-title" className="px-5 py-5 sm:px-7 sm:py-6">
            <p className="text-caption font-semibold uppercase tracking-[0.6px] text-slate-600">Làm một việc này trước</p>
            {!loaded ? (
              <p className="mt-3 text-ui text-slate-500" role="status">Đang xem lớp có việc gì cần bạn...</p>
            ) : firstTask ? (
              <div className="mt-3.5 flex flex-wrap items-center gap-4">
                <span className="inline-flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-community bg-tint text-blue-600" aria-hidden="true">
                  <firstTask.icon className="h-[22px] w-[22px]" strokeWidth={1.75} />
                </span>
                <div className="min-w-[220px] flex-1">
                  <h2 id="first-task-title" className="text-h3-lg font-semibold tracking-[-0.2px] text-slate-900 sm:text-[19px] sm:leading-[27px]">{firstTask.title}</h2>
                  <p className="mt-0.5 text-ui text-slate-600">{firstTask.detail}</p>
                </div>
                <Link to={firstTask.to} className={buttonClass('primary', 'lg', 'w-full sm:w-auto')}>{firstTask.cta}</Link>
              </div>
            ) : (
              <div className="mt-3.5 flex flex-wrap items-center gap-4">
                <span className="inline-flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-community bg-green-100 text-green-800" aria-hidden="true">
                  <ClipboardCheck className="h-[22px] w-[22px]" strokeWidth={1.75} />
                </span>
                <div className="min-w-[220px] flex-1">
                  <h2 id="first-task-title" className="text-h3-lg font-semibold text-slate-900">Không có việc nào đang chờ bạn</h2>
                  <p className="mt-0.5 text-ui text-slate-600">
                    Không có bài chờ chấm hay đơn hàng treo. Dành 5 phút đăng một thông báo ngắn để lớp biết tuần này học gì.
                  </p>
                </div>
                <Link to={`${base}/feed`} className={buttonClass('secondary', 'md', 'w-full sm:w-auto')}>Viết thông báo</Link>
              </div>
            )}
          </Card>

          {/* The rest of today's queue */}
          {otherTasks.length > 0 && (
            <Card as="section" padded={false} aria-labelledby="queue-title" className="overflow-hidden">
              <h2 id="queue-title" className="px-6 pb-2 pt-[18px] text-[16px] font-semibold leading-6 text-slate-900">Còn lại trong hôm nay</h2>
              <ul>
                {otherTasks.map((task) => (
                  <li key={task.key} className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-3.5 border-t border-slate-100 px-6 py-[13px]">
                    <span className={`inline-flex h-9 w-9 items-center justify-center rounded-[11px] ${task.tone}`} aria-hidden="true">
                      <task.icon className="h-[18px] w-[18px]" strokeWidth={1.75} />
                    </span>
                    <span className="min-w-0">
                      <span className="block text-ui font-semibold text-slate-900">{task.title}</span>
                      <span className="block text-meta text-slate-500">{task.detail}</span>
                    </span>
                    <Link to={task.to} className="whitespace-nowrap text-meta font-medium text-blue-600 hover:text-blue-700">{task.cta}</Link>
                  </li>
                ))}
              </ul>
            </Card>
          )}

          {/* Quick paths into the main content tools (only those the viewer can use) */}
          <section aria-labelledby="shortcuts-title">
            <h2 id="shortcuts-title" className="mb-3 text-[16px] font-semibold leading-6 text-slate-900">Đi nhanh tới</h2>
            <div className="grid gap-3 sm:grid-cols-2">
              {[
                { show: canViewCourses, icon: BookOpen, title: 'Khóa học & bài giảng', detail: 'Thêm chương, bài học video hoặc tài liệu', to: `${base}/courses` },
                { show: hasAnyStudioPermission(classroom, 'EXAM', 'VIEW') || hasAnyStudioPermission(classroom, 'EXAM', 'CREATE') || hasAnyStudioPermission(classroom, 'EXAM', 'EDIT'), icon: ClipboardCheck, title: 'Thi', detail: 'Soạn đề trắc nghiệm hoặc tự luận', to: `${base}/exams` },
                { show: canViewEvents || hasStudioPermission(classroom, 'EVENT', 'CREATE'), icon: CalendarDays, title: 'Sự kiện', detail: 'Lên lịch buổi học trực tiếp hoặc workshop', to: `${base}/events` },
                { show: canViewMembers || hasStudioPermission(classroom, 'MEMBER', 'EDIT'), icon: UserPlus, title: 'Thành viên', detail: 'Mời người mới, quản lý quyền truy cập', to: `${base}/members` },
              ].filter((s) => s.show).map((s) => (
                <Link
                  key={s.to}
                  to={s.to}
                  className="card-hover flex items-start gap-3 rounded-2xl border border-slate-200 bg-white px-[18px] py-4 shadow-hairline"
                >
                  <span className="inline-flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-[11px] bg-slate-100 text-slate-600" aria-hidden="true">
                    <s.icon className="h-[18px] w-[18px]" strokeWidth={1.75} />
                  </span>
                  <span className="min-w-0">
                    <span className="block text-ui font-semibold text-slate-900">{s.title}</span>
                    <span className="block text-meta text-slate-600">{s.detail}</span>
                  </span>
                </Link>
              ))}
            </div>
          </section>
        </div>

        {/* Right column */}
        <aside className="flex min-w-0 flex-col gap-5">
          {nextEvent && (
            <Card as="section" padded={false} aria-labelledby="next-event-title" className="p-[22px]">
              <h2 id="next-event-title" className="text-body-sm font-semibold text-slate-900">Sự kiện sắp tới</h2>
              <div className="mt-3.5 flex items-center gap-3">
                <DateBlock date={new Date(nextEvent.startsAt)} />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-ui font-semibold text-slate-900">{nextEvent.title}</span>
                  <span className="mt-px block text-caption text-slate-500 tabular">
                    {timeLabel(nextEvent.startsAt)}
                    {nextEvent.location ? ` · ${nextEvent.location}` : ''}
                    {` · ${nextEvent.registeredCount.toLocaleString('vi-VN')} đã đăng ký`}
                  </span>
                </span>
              </div>
              <Link to={`/classes/${classroom.slug}/events/${nextEvent.id}`} className={buttonClass('secondary', 'md', 'mt-3.5 w-full')}>
                Xem chi tiết
              </Link>
            </Card>
          )}

          <Card as="section" padded={false} aria-labelledby="pulse-title" className="p-[22px]">
            <h2 id="pulse-title" className="text-body-sm font-semibold text-slate-900">Nhịp của lớp</h2>
            <dl className="mt-3 space-y-2.5">
              {pulse.filter((p) => p.value !== null && p.value !== undefined).map((p) => (
                <div key={p.label} className="flex items-baseline justify-between gap-3">
                  <dt className="text-meta text-slate-600">
                    {p.to ? <Link to={p.to} className="text-slate-600 hover:text-slate-900">{p.label}</Link> : p.label}
                  </dt>
                  <dd className="text-ui font-semibold text-slate-900 tabular">{formatCompact(p.value as number)}</dd>
                </div>
              ))}
            </dl>
          </Card>

          {canRebuildLeaderboard && (
            <Card as="section" padded={false} aria-labelledby="rebuild-title" className="p-[22px]">
              <h2 id="rebuild-title" className="text-body-sm font-semibold text-slate-900">Bảng xếp hạng</h2>
              <p className="mt-1 text-meta text-slate-600">Tính lại điểm tích lũy của toàn bộ học viên sau khi bạn đổi quy tắc thưởng điểm.</p>
              <Button variant="secondary" size="md" className="mt-3.5 w-full" onClick={handleRebuildLeaderboard} disabled={rebuilding}>
                <RefreshCw className={`h-4 w-4 ${rebuilding ? 'animate-spin' : ''}`} strokeWidth={1.75} aria-hidden="true" />
                {rebuilding ? 'Đang tính lại...' : 'Tái tạo bảng xếp hạng'}
              </Button>
              {rebuildMessage && (
                <Notice tone={rebuildMessage.tone} role={rebuildMessage.tone === 'success' ? 'status' : 'alert'} className="mt-3">
                  {rebuildMessage.text}
                </Notice>
              )}
            </Card>
          )}
        </aside>
      </div>
    </StudioPage>
  );
};
