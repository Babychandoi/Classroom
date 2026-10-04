import React, { useEffect, useState } from 'react';
import { useParams, useSearchParams, Link } from 'react-router-dom';
import { api } from '../../api/client';
import { Exam, ExamAttempt, Question } from '../../types';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { showOptionKey } from './ExamAttemptPage';
import { Badge, BadgeTone, buttonClass } from '../../components/ui';
import { ArrowLeft, Check, Clock, Eye, Minus, Trophy, X } from 'lucide-react';

const STATUS_LABEL: Record<ExamAttempt['status'], { label: string; tone: BadgeTone }> = {
  IN_PROGRESS: { label: 'Đang làm', tone: 'info' },
  SUBMITTED: { label: 'Đã nộp', tone: 'neutral' },
  GRADING: { label: 'Đang chấm', tone: 'warn' },
  GRADED: { label: 'Đã chấm, chờ công bố', tone: 'warn' },
  PUBLISHED: { label: 'Đã công bố', tone: 'success' },
  CANCELLED: { label: 'Đã hủy', tone: 'neutral' },
};

const statusOf = (status: string) => STATUS_LABEL[status as ExamAttempt['status']] ?? { label: status, tone: 'neutral' as BadgeTone };

const pad = (n: number) => String(n).padStart(2, '0');
const formatDateTime = (iso: string) => {
  const d = new Date(iso);
  return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

type Verdict = 'correct' | 'partial' | 'wrong' | 'unknown';

/** Correctness is never sent to learners; it is read from the points the grader awarded vs. the question's points. */
const verdictOf = (awarded: number | undefined | null, question?: Question): Verdict => {
  if (awarded === undefined || awarded === null || !question || !(question.points > 0)) return 'unknown';
  if (awarded >= question.points) return 'correct';
  if (awarded <= 0) return 'wrong';
  return 'partial';
};

const VERDICT_STYLE: Record<Exclude<Verdict, 'unknown'>, { label: string; icon: React.ReactNode; chip: string; bar: string }> = {
  correct: { label: 'Đúng', icon: <Check className="h-3.5 w-3.5" strokeWidth={2.6} aria-hidden="true" />, chip: 'bg-green-100 text-green-800', bar: 'border-l-green-600' },
  partial: { label: 'Đúng một phần', icon: <Minus className="h-3.5 w-3.5" strokeWidth={2.6} aria-hidden="true" />, chip: 'bg-warn-soft text-amber-800', bar: 'border-l-amber-500' },
  wrong: { label: 'Chưa đúng', icon: <X className="h-3.5 w-3.5" strokeWidth={2.6} aria-hidden="true" />, chip: 'bg-red-50 text-red-700', bar: 'border-l-red-600' },
};

export const ExamResultPage: React.FC = () => {
  const { slug, examId } = useParams<{ slug: string; examId: string }>();
  const [searchParams] = useSearchParams();
  const attemptId = searchParams.get('attemptId');
  const [attempts, setAttempts] = useState<ExamAttempt[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Question texts / points / option labels for the per-question review (GET /exams/{id} never carries the answer key
  // for a learner). Optional: without it the review still lists the learner's answers and awarded points.
  const [questions, setQuestions] = useState<Question[]>([]);

  const fetchAttempts = async () => {
    if (!examId) return;
    try {
      setLoading(true);
      setError(null);
      const data = await api.get<ExamAttempt[]>(`/exams/${examId}/my-attempts`);
      setAttempts(data || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải kết quả bài thi');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchAttempts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [examId]);

  // R8-02: prefer the attempt the caller just submitted (navigated here with ?attemptId=) so a
  // student who submits, then starts another attempt before this one is graded, still lands on the
  // result they just produced rather than whichever happens to be newest.
  const latestAttempt = (attemptId && attempts.find((a) => a.id === attemptId)) || attempts[0];
  const isPublished = latestAttempt?.status === 'PUBLISHED';
  const showReview = !!latestAttempt && isPublished && !latestAttempt.resultHidden && (latestAttempt.answers?.length ?? 0) > 0;

  useEffect(() => {
    if (!showReview || !examId) return;
    let cancelled = false;
    api.get<Exam>(`/exams/${examId}`)
      .then((exam) => { if (!cancelled) setQuestions(exam?.questions ?? []); })
      .catch(() => { if (!cancelled) setQuestions([]); });
    return () => { cancelled = true; };
  }, [showReview, examId]);

  if (loading) return <LoadingSpinner message="Đang tải kết quả bài thi..." />;
  if (error) {
    return (
      <div className="mx-auto max-w-2xl py-12">
        <ErrorBanner message={error} onRetry={fetchAttempts} />
      </div>
    );
  }

  if (!latestAttempt) {
    return (
      <div className="mx-auto max-w-md py-12">
        <div className="rounded-card border border-slate-200 bg-white p-8 text-center shadow-hairline">
          <h2 className="text-h2-sm font-semibold text-slate-900">Chưa có bài thi nào</h2>
          <p className="mt-2 text-ui text-slate-600">Bạn chưa tham gia lượt làm bài nào cho kỳ thi này. Quay lại danh sách để bắt đầu.</p>
          <Link to={`/classes/${slug}/exams`} className={buttonClass('secondary', 'md', 'mt-6')}>
            <ArrowLeft className="h-4 w-4" strokeWidth={1.8} aria-hidden="true" />
            <span>Quay lại danh sách kỳ thi</span>
          </Link>
        </div>
      </div>
    );
  }

  const status = statusOf(latestAttempt.status);
  const questionById = new Map(questions.map((q) => [q.id, q]));
  // The attempt only lists the questions the learner answered; once the exam's questions are known the review follows
  // the exam's order and shows skipped questions too, so "đúng x/y" counts every question.
  type Answer = NonNullable<ExamAttempt['answers']>[number];
  const answers: Answer[] = latestAttempt.answers ?? [];
  const answerById = new Map(answers.map((a) => [a.questionId, a]));
  const reviewRows: Answer[] = questions.length
    ? questions.map((q) => answerById.get(q.id) ?? ({ questionId: q.id, studentAnswer: '' } as Answer))
    : answers;
  const verdicts = reviewRows.map((ans) => (answerById.has(ans.questionId) ? verdictOf(ans.pointsAwarded, questionById.get(ans.questionId)) : 'unknown'));
  const correctCount = verdicts.filter((v) => v === 'correct').length;
  const knownCount = questions.length ? reviewRows.length : verdicts.filter((v) => v !== 'unknown').length;

  return (
    <div className="mx-auto max-w-3xl space-y-5 pb-12">
      <Link
        to={`/classes/${slug}/exams`}
        className="inline-flex min-h-[40px] items-center gap-1.5 text-ui font-medium text-slate-600 transition-colors duration-micro hover:text-slate-900"
      >
        <ArrowLeft className="h-4 w-4" strokeWidth={1.8} aria-hidden="true" />
        <span>Về danh mục kỳ thi</span>
      </Link>

      {latestAttempt.isPreview && (
        <p role="status" className="flex items-center gap-2 rounded-btn border border-amber-200 bg-warn-soft px-4 py-2.5 text-meta font-semibold text-amber-800">
          <Eye className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" />
          Chế độ xem thử — kết quả này không tính điểm/xếp hạng
        </p>
      )}

      {/* Score summary */}
      <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-hairline sm:rounded-card sm:p-8">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <h1 className="text-h2-sm font-semibold text-slate-900 sm:text-h2">Kết quả bài thi</h1>
            <p className="mt-0.5 truncate text-ui text-slate-600">{latestAttempt.examTitle || 'Kỳ thi'}</p>
          </div>
          {!latestAttempt.resultHidden && !latestAttempt.isPreview && <Badge tone={status.tone}>{status.label}</Badge>}
        </div>

        {latestAttempt.resultHidden ? (
          // R19-04: the server withheld the score of this preview attempt (the viewer may not read the answer key).
          <div role="status" className="mt-6 rounded-2xl bg-slate-50 px-5 py-5">
            <p className="text-ui font-semibold text-slate-900">
              {latestAttempt.notice || 'Chế độ xem thử: không hiển thị điểm/đáp án cho quyền của bạn'}
            </p>
            <p className="mt-1 text-meta text-slate-600">Bài làm của bạn đã được ghi nhận.</p>
          </div>
        ) : (
          <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-end">
            <div>
              <p className="text-caption font-semibold uppercase tracking-[0.6px] text-slate-500">Điểm số đạt được</p>
              {isPublished && latestAttempt.score !== undefined && latestAttempt.score !== null ? (
                <p className="mt-1 text-[48px] font-semibold leading-[56px] tracking-[-1px] text-slate-900 tabular">
                  {latestAttempt.score}<span className="text-h2 text-slate-500">%</span>
                </p>
              ) : (
                <p className="mt-2 inline-flex items-center gap-2 text-h2-sm font-semibold text-slate-900">
                  <Clock className="h-5 w-5 text-amber-500" strokeWidth={1.8} aria-hidden="true" />
                  Chờ chấm điểm
                </p>
              )}
              <p className="mt-1.5 text-meta text-slate-600">
                {isPublished
                  ? knownCount > 0
                    ? <>Bạn trả lời đúng <span className="font-semibold text-slate-900 tabular">{correctCount}/{knownCount}</span> câu.</>
                    : 'Kết quả đã được công bố.'
                  : 'Kết quả hiện ra ở đây ngay khi người dẫn dắt công bố.'}
              </p>
            </div>
            {latestAttempt.submittedAt && (
              <p className="text-caption text-slate-500 tabular sm:text-right">Nộp lúc {formatDateTime(latestAttempt.submittedAt)}</p>
            )}
          </div>
        )}

        <div className="mt-6 flex flex-col gap-2.5 border-t border-slate-100 pt-5 sm:flex-row">
          <Link to={`/classes/${slug}/leaderboard`} className={buttonClass('secondary', 'md')}>
            <Trophy className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
            <span>Xem Bảng xếp hạng</span>
          </Link>
          <Link to={`/classes/${slug}/exams`} className={buttonClass('ghost', 'md')}>
            Về danh sách kỳ thi
          </Link>
        </div>
      </section>

      {/* Attempt history */}
      {attempts.length > 1 && (
        <section className="rounded-2xl border border-slate-200 bg-white shadow-hairline sm:rounded-card">
          <h2 className="px-5 pb-2 pt-5 text-ui font-semibold text-slate-900 sm:px-6">
            Lịch sử các lần làm bài (<span className="tabular">{attempts.length}</span>)
          </h2>
          <ul className="divide-y divide-slate-100 pb-2">
            {attempts.map((att, idx) => {
              const s = statusOf(att.status);
              return (
                <li key={att.id} className={`flex flex-wrap items-center justify-between gap-2 px-5 py-3 sm:px-6 ${att.id === latestAttempt.id ? 'bg-tint' : ''}`}>
                  <div className="flex items-center gap-2.5">
                    <span className="text-ui font-semibold text-slate-900 tabular">Lần {attempts.length - idx}</span>
                    {att.resultHidden ? null : <Badge tone={s.tone} size="sm">{s.label}</Badge>}
                    {att.id === latestAttempt.id && <span className="sr-only text-caption font-medium text-blue-700 sm:not-sr-only">Đang xem</span>}
                  </div>
                  <div className="ml-auto flex items-center gap-3">
                    <span className="text-ui font-semibold text-slate-900 tabular">
                      {att.resultHidden ? 'Không hiển thị' : att.status === 'PUBLISHED' && att.score !== undefined ? `${att.score}%` : 'Chờ chấm'}
                    </span>
                    {att.submittedAt && <span className="text-caption text-slate-500 tabular">{formatDateTime(att.submittedAt)}</span>}
                  </div>
                </li>
              );
            })}
          </ul>
        </section>
      )}

      {/* Per-question review (only once the result is published, and never for a withheld preview result). */}
      {showReview && (
        <section aria-labelledby="answer-review" className="space-y-3">
          <h2 id="answer-review" className="text-h3 font-semibold text-slate-900">Chi tiết câu trả lời</h2>
          {reviewRows.map((ans, idx) => {
            const question = questionById.get(ans.questionId);
            const verdict = verdicts[idx];
            const style = verdict === 'unknown' ? null : VERDICT_STYLE[verdict];
            const option = question?.options?.find((o) => o.optionKey === ans.studentAnswer);
            return (
              <article
                key={ans.questionId || idx}
                className={`rounded-2xl border border-slate-200 bg-white p-4 shadow-hairline sm:p-5 ${style ? `border-l-4 ${style.bar}` : ''}`}
              >
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <p className="text-caption font-semibold uppercase tracking-[0.6px] text-slate-500 tabular">Câu hỏi #{idx + 1}</p>
                  <div className="flex items-center gap-2">
                    {style && (
                      <span className={`inline-flex h-[22px] items-center gap-1 rounded-full px-2 text-micro font-semibold ${style.chip}`}>
                        {style.icon}
                        {style.label}
                      </span>
                    )}
                    {ans.pointsAwarded !== undefined && ans.pointsAwarded !== null && (
                      <span className="text-meta font-semibold text-slate-900 tabular">
                        +{ans.pointsAwarded}{question ? `/${question.points}` : ''} điểm
                      </span>
                    )}
                  </div>
                </div>
                {question && <p className="mt-2 whitespace-pre-line text-ui font-medium text-slate-900">{question.questionText}</p>}
                <div className="mt-2.5 rounded-btn bg-slate-50 px-3.5 py-2.5 text-meta">
                  <span className="text-slate-500">Câu trả lời của bạn: </span>
                  <span className="whitespace-pre-wrap font-medium text-slate-900">
                    {ans.studentAnswer ? (option ? (showOptionKey(option.optionKey) ? `${option.optionKey}. ${option.optionText}` : option.optionText) : ans.studentAnswer) : '(Chưa trả lời)'}
                  </span>
                </div>
                {ans.teacherFeedback && (
                  <div className="mt-2.5 rounded-btn bg-tint px-3.5 py-2.5 text-meta text-slate-900">
                    <span className="font-semibold">Nhận xét của giáo viên: </span>
                    {ans.teacherFeedback}
                  </div>
                )}
              </article>
            );
          })}
        </section>
      )}
    </div>
  );
};
