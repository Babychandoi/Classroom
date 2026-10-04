import React, { useEffect, useMemo, useState } from 'react';
import { useOutletContext, Link } from 'react-router-dom';
import { Classroom, Exam } from '../../types';
import { api } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { LoadingSpinner, ErrorBanner, EmptyState } from '../../components/UIStates';
import { Badge, BadgeTone, DateBlock, FilterChip, buttonClass } from '../../components/ui';
import { ArrowRight, Award, ClipboardList, Clock, ListChecks, Lock, RotateCcw, Target } from 'lucide-react';

type AudienceScope = Exam['audienceScope'];

// Every audience scope the backend (ExamAudiencePolicy) supports must be labelled here, so a
// restricted exam is never presented to learners as open to everyone.
const AUDIENCE_LABEL: Record<AudienceScope, string> = {
  ALL: 'Tất cả học viên',
  PRO: 'Chỉ dành cho PRO',
  COURSE: 'Theo khóa học',
  SEGMENT: 'Theo nhóm học viên',
  COURSE_SEGMENT: 'Theo khóa học & nhóm học viên',
};

const AUDIENCE_TONE: Record<AudienceScope, BadgeTone> = {
  ALL: 'neutral',
  PRO: 'pro',
  COURSE: 'member',
  SEGMENT: 'info',
  COURSE_SEGMENT: 'paid',
};

const AUDIENCE_BLOCKED_REASON: Record<AudienceScope, string> = {
  ALL: 'Chưa đủ điều kiện tham gia',
  PRO: 'Yêu cầu tài khoản PRO để tham gia',
  COURSE: 'Yêu cầu quyền truy cập khóa học liên quan',
  SEGMENT: 'Bạn không thuộc nhóm học viên được tham gia',
  COURSE_SEGMENT: 'Yêu cầu quyền truy cập khóa học và thuộc nhóm học viên được tham gia',
};

type ScheduleStatus = 'OPEN' | 'UPCOMING' | 'CLOSED';

const getScheduleStatus = (exam: Exam): { status: ScheduleStatus; label: string; tone: BadgeTone } => {
  const now = Date.now();
  if (exam.status === 'CLOSED' || exam.status === 'ARCHIVED') {
    return { status: 'CLOSED', label: 'Đã đóng', tone: 'neutral' };
  }
  if (exam.scheduleStart && new Date(exam.scheduleStart).getTime() > now) {
    return { status: 'UPCOMING', label: 'Sắp mở', tone: 'warn' };
  }
  if (exam.scheduleEnd && new Date(exam.scheduleEnd).getTime() <= now) {
    return { status: 'CLOSED', label: 'Đã đóng', tone: 'neutral' };
  }
  return { status: 'OPEN', label: 'Đang mở', tone: 'success' };
};

const pad = (n: number) => String(n).padStart(2, '0');
/** dd/mm/yyyy HH:mm, 24h. */
const formatDateTime = (iso: string) => {
  const d = new Date(iso);
  return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

const blockedReason = (exam: Exam, classArchived = false): string => {
  // R14-05 / R14-13: a closed/archived exam and an archived class accept no NEW attempts (a learner
  // with a running attempt still gets canEnter=true and the "Làm tiếp" button instead).
  if (exam.status === 'CLOSED' || exam.status === 'ARCHIVED') return 'Kỳ thi đã đóng, không nhận lượt làm bài mới';
  if (classArchived) return 'Lớp học đã được lưu trữ; không thể bắt đầu lượt làm bài mới';
  const sched = getScheduleStatus(exam);
  if (sched.status === 'UPCOMING') {
    const startStr = exam.scheduleStart ? formatDateTime(exam.scheduleStart) : '';
    return `Kỳ thi chưa mở (Bắt đầu: ${startStr})`;
  }
  if (sched.status === 'CLOSED') {
    return 'Kỳ thi đã kết thúc';
  }
  if (exam.userAttemptsCount >= exam.attemptLimit) return 'Đã hết lượt làm bài';
  return AUDIENCE_BLOCKED_REASON[exam.audienceScope] ?? AUDIENCE_BLOCKED_REASON.ALL;
};

/**
 * canEnter while no new attempt could be started (exam closed, class archived, or every lượt used) can only mean
 * the server is letting the learner back into an attempt that is still running - so the call to action is "Làm tiếp".
 */
const isResume = (exam: Exam, classArchived: boolean) =>
  exam.canEnter &&
  (exam.status === 'CLOSED' || exam.status === 'ARCHIVED' || classArchived || exam.userAttemptsCount >= exam.attemptLimit);

type Filter = 'ALL' | ScheduleStatus;

export const ExamsTab: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const { user } = useAuth();

  const [exams, setExams] = useState<Exam[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<Filter>('ALL');

  const fetchExams = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.get<Exam[]>(`/classes/${classroom.id}/exams`);
      setExams(data || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải danh sách kỳ thi');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchExams();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classroom.id, user]);

  const classArchived = classroom.status === 'ARCHIVED';
  const counts = useMemo(() => {
    const c: Record<Filter, number> = { ALL: exams.length, OPEN: 0, UPCOMING: 0, CLOSED: 0 };
    exams.forEach((e) => { c[getScheduleStatus(e).status] += 1; });
    return c;
  }, [exams]);
  const visible = exams.filter((e) => filter === 'ALL' || getScheduleStatus(e).status === filter);
  // One primary per viewport: only the first exam the learner can enter gets the blue button.
  const primaryExamId = visible.find((e) => e.canEnter)?.id;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h2 className="text-h2-sm font-semibold text-slate-900 sm:text-h2">Kỳ thi</h2>
          <p className="mt-1 text-ui text-slate-600">Làm bài để nhận điểm thưởng theo quy tắc của lớp và lên hạng trên Bảng xếp hạng.</p>
        </div>
        {exams.length > 0 && (
          <div role="group" aria-label="Lọc kỳ thi theo trạng thái" className="-mx-4 flex gap-2 overflow-x-auto px-4 scrollbar-none sm:mx-0 sm:px-0">
            {([
              ['ALL', 'Tất cả'],
              ['OPEN', 'Đang mở'],
              ['UPCOMING', 'Sắp mở'],
              ['CLOSED', 'Đã đóng'],
            ] as [Filter, string][]).map(([key, label]) => (
              <FilterChip key={key} selected={filter === key} onClick={() => setFilter(key)}>
                {label} · <span className="tabular">{counts[key]}</span>
              </FilterChip>
            ))}
          </div>
        )}
      </div>

      {loading && <LoadingSpinner message="Đang tải danh sách kỳ thi..." />}
      {error && <ErrorBanner message={error} onRetry={fetchExams} />}

      {!loading && !error && exams.length === 0 && (
        <EmptyState
          title="Chưa có kỳ thi nào"
          description="Lớp học hiện chưa mở kỳ thi nào. Trong lúc chờ, bạn có thể ôn lại bài ở tab Khóa học."
          icon={<ClipboardList className="h-6 w-6" strokeWidth={1.75} />}
        />
      )}

      {!loading && !error && exams.length > 0 && visible.length === 0 && (
        <p className="rounded-card border border-dashed border-slate-300 bg-white px-6 py-10 text-center text-ui text-slate-600">
          Không có kỳ thi nào ở trạng thái này. Chọn “Tất cả” để xem mọi kỳ thi của lớp.
        </p>
      )}

      <ul className="space-y-3 sm:space-y-4">
        {visible.map((exam) => {
          const sched = getScheduleStatus(exam);
          const resume = isResume(exam, classArchived);
          const questionCount = exam.questionCount ?? exam.questions?.length ?? 0;
          return (
            <li key={exam.id}>
              <article className="flex flex-col gap-4 rounded-2xl border border-slate-200 bg-white p-4 shadow-hairline sm:flex-row sm:gap-5 sm:rounded-card sm:p-6">
                <div className="flex items-start gap-4 sm:contents">
                  {exam.scheduleStart ? (
                    <DateBlock date={new Date(exam.scheduleStart)} />
                  ) : (
                    <span className="inline-flex h-14 w-[52px] flex-shrink-0 items-center justify-center rounded-community bg-slate-100 text-slate-600" aria-hidden="true">
                      <ClipboardList className="h-5 w-5" strokeWidth={1.75} />
                    </span>
                  )}

                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={sched.tone} size="sm">
                        {sched.status === 'OPEN' && <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-green-600" />}
                        {sched.label}
                      </Badge>
                      <Badge tone={AUDIENCE_TONE[exam.audienceScope] ?? 'neutral'} size="sm">
                        {AUDIENCE_LABEL[exam.audienceScope] ?? AUDIENCE_LABEL.ALL}
                      </Badge>
                    </div>
                    <h3 className="mt-2 text-h3 font-semibold text-slate-900">{exam.title}</h3>
                    <p className="mt-0.5 line-clamp-2 text-meta text-slate-600 sm:text-ui">
                      {exam.description || 'Bài thi đánh giá chuẩn kiến thức.'}
                    </p>

                    <ul className="mt-3 flex flex-wrap gap-x-4 gap-y-1.5 text-meta text-slate-600">
                      <li className="inline-flex items-center gap-1.5">
                        <Clock className="h-4 w-4 text-slate-400" strokeWidth={1.75} aria-hidden="true" />
                        <span className="tabular">{exam.durationMinutes} phút</span>
                      </li>
                      <li className="inline-flex items-center gap-1.5">
                        <ListChecks className="h-4 w-4 text-slate-400" strokeWidth={1.75} aria-hidden="true" />
                        <span className="tabular">{questionCount} câu</span>
                      </li>
                      <li className="inline-flex items-center gap-1.5">
                        <Target className="h-4 w-4 text-slate-400" strokeWidth={1.75} aria-hidden="true" />
                        <span>Đạt từ <span className="tabular">{exam.passScore}/100</span></span>
                      </li>
                      <li className="inline-flex items-center gap-1.5">
                        <RotateCcw className="h-4 w-4 text-slate-400" strokeWidth={1.75} aria-hidden="true" />
                        <span>Lượt làm <span className="tabular font-semibold text-slate-900">{exam.userAttemptsCount}/{exam.attemptLimit}</span></span>
                      </li>
                    </ul>

                    {(exam.scheduleStart || exam.scheduleEnd) && (
                      <p className="mt-1.5 text-caption text-slate-500 tabular">
                        {exam.scheduleStart && <>Mở {formatDateTime(exam.scheduleStart)}</>}
                        {exam.scheduleStart && exam.scheduleEnd && ' · '}
                        {exam.scheduleEnd && <>Đóng {formatDateTime(exam.scheduleEnd)}</>}
                      </p>
                    )}
                  </div>
                </div>

                <div className="flex flex-shrink-0 flex-col gap-2 sm:w-[200px] sm:items-stretch sm:justify-center">
                  {exam.canEnter ? (
                    <Link
                      to={`/classes/${classroom.slug}/exams/${exam.id}/attempt`}
                      className={buttonClass(exam.id === primaryExamId ? 'primary' : 'secondary', 'md', 'w-full')}
                    >
                      <span>{resume ? 'Làm tiếp' : 'Làm bài'}</span>
                      <ArrowRight className="h-3.5 w-3.5" strokeWidth={2} aria-hidden="true" />
                    </Link>
                  ) : (
                    <p className="flex items-start gap-1.5 rounded-btn bg-slate-50 px-3 py-2.5 text-meta font-medium text-slate-600">
                      <Lock className="mt-0.5 h-3.5 w-3.5 flex-shrink-0 text-slate-400" strokeWidth={2} aria-hidden="true" />
                      <span>{blockedReason(exam, classArchived)}</span>
                    </p>
                  )}
                  {exam.userAttemptsCount > 0 && (
                    <Link
                      to={`/classes/${classroom.slug}/exams/${exam.id}/result`}
                      className={buttonClass(exam.canEnter ? 'ghost' : 'secondary', 'md', 'w-full')}
                    >
                      <Award className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                      <span>Xem kết quả</span>
                    </Link>
                  )}
                </div>
              </article>
            </li>
          );
        })}
      </ul>
    </div>
  );
};
