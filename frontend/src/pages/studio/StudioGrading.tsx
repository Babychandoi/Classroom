import React, { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom } from '../../types';
import { api } from '../../api/client';
import { hasAnyStudioPermission, hasCoursePermission } from '../../api/permissions';

// R13-07 gap fix: the class's exams, filtered to ones this grader can actually grade (class-wide
// EXAM:GRADE, or a course-scoped grant matching the exam's targetCourseId) — used to seed
// "Kết quả đã công bố" with every gradeable exam, not only the ones the grading queue happened to
// surface (an exam whose attempts are all already published/graded never appears in the queue, so
// it never used to show up here at all).
type ExamSummary = { id: string; title: string; targetCourseId: string | null };

type Question = { id: string; questionText: string; type: string; points: number };
type Answer = { questionId: string; studentAnswer: string; pointsAwarded: number | null; teacherFeedback: string | null };
// R13-13: learnerDisplayName is batch-resolved server-side (ExamService.toGradingDtos), mirroring
// AssignmentSubmissionDto's learner field — never shown as a raw userId in the queue.
type Attempt = { id: string; examId: string; examTitle: string; userId: string; learnerDisplayName?: string; status: string; score: number | null; questions?: Question[]; answers?: Answer[] };
// R12-03: the backend now returns AssignmentSubmissionDto instead of the raw AssignmentSubmission
// entity - lesson/course titles and the learner's display name are resolved server-side (batch,
// no N+1) instead of the UI showing raw UUIDs.
type AssignmentSubmission = {
  submissionId: string;
  lessonId: string;
  lessonTitle: string | null;
  courseId: string;
  courseTitle: string | null;
  learner: { userId: string; displayName: string };
  submissionText: string;
  attemptNumber: number;
  status: string;
  score: number | null;
  feedback: string | null;
  submittedAt: string;
};

export const StudioGrading: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  // R11-02: gate each grading section on the permission the server actually enforces for it, so a
  // staff member holding only one of the two grants (e.g. COURSE:GRADE scoped to a single course)
  // never triggers the other section's request at all - avoiding both a wasted 403 and an error
  // banner for a section they were never meant to see.
  const canGradeExams = hasAnyStudioPermission(classroom, 'EXAM', 'GRADE');
  const canGradeAssignments = hasAnyStudioPermission(classroom, 'COURSE', 'GRADE');

  const [queue, setQueue] = useState<Attempt[]>([]);
  const [selected, setSelected] = useState<Attempt | null>(null);
  const [scores, setScores] = useState<Record<string, string>>({});
  const [feedback, setFeedback] = useState<Record<string, string>>({});
  const [examLoading, setExamLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [examError, setExamError] = useState('');
  const [gradeError, setGradeError] = useState('');

  const [assignmentQueue, setAssignmentQueue] = useState<AssignmentSubmission[]>([]);
  const [assignmentLoading, setAssignmentLoading] = useState(true);
  const [assignmentError, setAssignmentError] = useState('');
  const [assignmentScores, setAssignmentScores] = useState<Record<string, string>>({});
  const [assignmentFeedback, setAssignmentFeedback] = useState<Record<string, string>>({});

  // R13-07: "Kết quả đã công bố" — published exam results, so an authorized grader can correct a
  // score after publication. Loaded per-exam (the exams the current grading queue/selection has
  // touched), since there is no single "all published results" endpoint.
  const [publishedByExam, setPublishedByExam] = useState<Record<string, Attempt[]>>({});
  const [publishedExamIds, setPublishedExamIds] = useState<string[]>([]);
  const [publishedLoading, setPublishedLoading] = useState<Record<string, boolean>>({});
  const [publishedError, setPublishedError] = useState('');
  // Non-null while correcting an already-PUBLISHED attempt: forces the mandatory reason field.
  const [correctingPublished, setCorrectingPublished] = useState(false);
  const [regradeReason, setRegradeReason] = useState('');

  const loadQueue = async () => {
    setExamLoading(true);
    setExamError('');
    try {
      const data = await api.get<Attempt[]>(`/classes/${classroom.id}/grading-queue`);
      setQueue(data);
      // R13-07: offer "Kết quả đã công bố" per exam the grader's queue has touched — a stable,
      // low-noise way to surface which exams there might be published results worth correcting,
      // without a dedicated "list every exam in this class" round trip.
      const examIds = Array.from(new Set(data.map((a) => a.examId)));
      setPublishedExamIds((current) => Array.from(new Set([...current, ...examIds])));
    } catch (e) {
      setExamError(e instanceof Error ? e.message : 'Không thể tải hàng đợi chấm bài thi');
    } finally {
      setExamLoading(false);
    }
  };

  // R13-07 gap fix: load the class's full exam list (existing GET /classes/{classId}/exams) and
  // add every exam this grader can grade to publishedExamIds — not just the ones surfaced by the
  // grading queue, which omits an exam once every one of its attempts is already published.
  const [examTitleById, setExamTitleById] = useState<Record<string, string>>({});
  const loadGradeableExams = async () => {
    try {
      const data = await api.get<ExamSummary[]>(`/classes/${classroom.id}/exams`);
      const gradeable = data.filter((exam) =>
        exam.targetCourseId
          ? hasCoursePermission(classroom, 'EXAM', 'GRADE', exam.targetCourseId)
          : hasAnyStudioPermission(classroom, 'EXAM', 'GRADE'),
      );
      setExamTitleById((current) => ({ ...current, ...Object.fromEntries(gradeable.map((e) => [e.id, e.title])) }));
      setPublishedExamIds((current) => Array.from(new Set([...current, ...gradeable.map((e) => e.id)])));
    } catch {
      // Non-fatal: "Kết quả đã công bố" simply falls back to whatever the grading queue surfaced.
    }
  };

  const loadPublishedForExam = async (examId: string) => {
    setPublishedLoading((current) => ({ ...current, [examId]: true }));
    setPublishedError('');
    try {
      const data = await api.get<Attempt[]>(`/exams/${examId}/published-attempts?limit=50`);
      setPublishedByExam((current) => ({ ...current, [examId]: data }));
    } catch (e) {
      setPublishedError(e instanceof Error ? e.message : 'Không thể tải kết quả đã công bố');
    } finally {
      setPublishedLoading((current) => ({ ...current, [examId]: false }));
    }
  };

  useEffect(() => {
    if (!canGradeExams) {
      setExamLoading(false);
      return;
    }
    void loadQueue();
    void loadGradeableExams();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classroom.id, canGradeExams]);

  const loadAssignmentQueue = async () => {
    setAssignmentLoading(true);
    setAssignmentError('');
    try {
      // R11-02: a single server-side endpoint that already filters submissions down to the
      // courses this staff member holds COURSE:GRADE on (class-wide or scoped) - see
      // AssignmentService.classQueue. This replaces the previous client-side fan-out of one
      // /lessons/{id}/submissions request per ASSIGNMENT lesson in every course, where a single
      // course-scoped 403 used to reject the whole Promise.all and blank the section for everyone.
      setAssignmentQueue(await api.get<AssignmentSubmission[]>(`/classes/${classroom.id}/assignment-queue`));
    } catch (e) {
      setAssignmentError(e instanceof Error ? e.message : 'Không thể tải bài tập cần chấm');
    } finally {
      setAssignmentLoading(false);
    }
  };

  useEffect(() => {
    if (!canGradeAssignments) {
      setAssignmentLoading(false);
      return;
    }
    void loadAssignmentQueue();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classroom.id, canGradeAssignments]);

  const gradeAssignment = async (submission: AssignmentSubmission) => {
    setAssignmentError('');
    try {
      await api.put(`/assignment-submissions/${submission.submissionId}/grade`, {
        score: Number(assignmentScores[submission.submissionId]), feedback: assignmentFeedback[submission.submissionId] ?? '',
      });
      await loadAssignmentQueue();
    } catch (e) { setAssignmentError(e instanceof Error ? e.message : 'Chấm bài tập thất bại'); }
  };

  const openAttempt = async (attemptId: string) => {
    setGradeError('');
    setRegradeReason('');
    try {
      const attempt = await api.get<Attempt>(`/attempts/${attemptId}/grading`);
      setSelected(attempt);
      // R13-07: opening an already-PUBLISHED result via "Sửa điểm" requires a mandatory reason;
      // first-time grading of a SUBMITTED/GRADING attempt does not.
      setCorrectingPublished(attempt.status === 'PUBLISHED');
      const essayIds = new Set((attempt.questions ?? []).filter(q => q.type.toUpperCase() === 'ESSAY').map(q => q.id));
      setScores(Object.fromEntries((attempt.answers ?? []).filter(a => essayIds.has(a.questionId) && a.pointsAwarded != null).map(a => [a.questionId, String(a.pointsAwarded)])));
      setFeedback(Object.fromEntries((attempt.answers ?? []).filter(a => a.teacherFeedback).map(a => [a.questionId, a.teacherFeedback ?? ''])));
    } catch (e) {
      setGradeError(e instanceof Error ? e.message : 'Không thể tải bài làm');
    }
  };

  const submitGrades = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!selected) return;
    if (correctingPublished && !regradeReason.trim()) {
      setGradeError('Sửa điểm bài thi đã công bố cần nêu rõ lý do');
      return;
    }
    setSaving(true);
    setGradeError('');
    try {
      const numericScores = Object.fromEntries(Object.entries(scores).filter(([, value]) => value.trim() !== '').map(([id, value]) => [id, Number(value)]));
      const body: { scores: Record<string, number>; feedback: Record<string, string>; reason?: string } = { scores: numericScores, feedback };
      if (correctingPublished) body.reason = regradeReason.trim();
      const result = await api.post<Attempt>(`/attempts/${selected.id}/grade`, body);
      setSelected(result);
      await loadQueue();
      if (correctingPublished) await loadPublishedForExam(result.examId);
    } catch (e) {
      setGradeError(e instanceof Error ? e.message : 'Chấm bài thất bại');
    } finally {
      setSaving(false);
    }
  };

  return <div className="max-w-5xl mx-auto space-y-6">
    <header><h1 className="text-2xl font-black text-slate-900">Chấm bài</h1><p className="text-sm text-slate-600">Bài thi và bài tập chờ chấm · {classroom.title}</p></header>

    {canGradeAssignments && <section className="space-y-4 rounded-2xl border bg-white p-5">
      <h2 className="text-lg font-bold">Bài tập</h2>
      {assignmentError && <div role="alert" className="rounded-xl bg-red-50 p-3 text-sm text-red-700">{assignmentError}</div>}
      {assignmentLoading ? <p>Đang tải…</p> : assignmentQueue.length === 0 ? <p className="text-sm text-slate-500">Chưa có bài nộp cần chấm.</p> : <div className="space-y-3">
        {assignmentQueue.map(submission => <article key={submission.submissionId} className="space-y-2 rounded-lg bg-slate-50 p-3">
          <p className="text-xs text-slate-600">{submission.courseTitle ?? 'Khóa học'} · {submission.lessonTitle ?? 'Bài tập'} · Học viên {submission.learner.displayName} · Lần {submission.attemptNumber}</p><p className="whitespace-pre-wrap text-sm">{submission.submissionText}</p>
          <div className="flex flex-wrap gap-2"><label className="text-sm">Điểm<input type="number" min="0" step="0.01" value={assignmentScores[submission.submissionId] ?? (submission.score == null ? '' : String(submission.score))} onChange={event => setAssignmentScores(current => ({ ...current, [submission.submissionId]: event.target.value }))} className="ml-2 w-28 rounded border p-2" /></label>
          <input aria-label="Phản hồi bài tập" placeholder="Phản hồi" value={assignmentFeedback[submission.submissionId] ?? submission.feedback ?? ''} onChange={event => setAssignmentFeedback(current => ({ ...current, [submission.submissionId]: event.target.value }))} className="min-w-48 flex-1 rounded border p-2" />
          <button type="button" onClick={() => void gradeAssignment(submission)} disabled={!((assignmentScores[submission.submissionId] ?? (submission.score == null ? '' : String(submission.score))).trim())} className="rounded bg-indigo-600 px-3 py-2 text-sm font-bold text-white disabled:opacity-50">Lưu điểm</button></div>
        </article>)}
      </div>}
    </section>}

    {canGradeExams && <>
      {examError && <div role="alert" className="rounded-xl bg-red-50 p-3 text-sm text-red-700">{examError}</div>}
      {gradeError && <div role="alert" className="rounded-xl bg-red-50 p-3 text-sm text-red-700">{gradeError}</div>}
      <div className="grid gap-5 lg:grid-cols-[minmax(16rem,0.8fr)_minmax(0,1.2fr)]">
        <section className="rounded-2xl border bg-white p-4" aria-label="Hàng đợi chấm bài">
          <h2 className="mb-3 font-bold">Hàng đợi</h2>
          {examLoading ? <p>Đang tải…</p> : queue.length === 0 ? <p className="text-sm text-slate-500">Không có bài cần chấm.</p> : <ul className="space-y-2">
            {queue.map(attempt => <li key={attempt.id}><button type="button" onClick={() => void openAttempt(attempt.id)} className="w-full rounded-xl border p-3 text-left hover:bg-slate-50">
              <span className="block font-semibold">{attempt.examTitle}</span><span className="text-xs text-slate-500">{attempt.learnerDisplayName ?? attempt.userId} · {attempt.status}</span>
            </button></li>)}
          </ul>}
        </section>
        <section className="rounded-2xl border bg-white p-5">
          {!selected ? <p className="text-sm text-slate-500">Chọn bài làm để xem câu trả lời và chấm tự luận.</p> : <form onSubmit={submitGrades} className="space-y-5">
            <div><h2 className="font-bold">{selected.examTitle}</h2><p className="text-xs text-slate-600">Học viên {selected.learnerDisplayName ?? selected.userId} · Trạng thái hiện tại: {selected.status}</p></div>
            {(selected.questions ?? []).filter(q => q.type.toUpperCase() === 'ESSAY').map((question, index) => {
              const answer = selected.answers?.find(a => a.questionId === question.id);
              return <fieldset key={question.id} className="space-y-2 rounded-xl border p-4">
                <legend className="px-1 font-semibold">Câu {index + 1} · tối đa {question.points} điểm</legend>
                <p>{question.questionText}</p><p className="whitespace-pre-wrap rounded-lg bg-slate-50 p-3 text-sm">{answer?.studentAnswer || 'Chưa có câu trả lời'}</p>
                <label className="block text-sm">Điểm<input required type="number" min="0" max={question.points} step="any" value={scores[question.id] ?? ''} onChange={e => setScores(current => ({ ...current, [question.id]: e.target.value }))} className="mt-1 block w-full rounded-lg border p-2" /></label>
                <label className="block text-sm">Phản hồi<textarea value={feedback[question.id] ?? ''} onChange={e => setFeedback(current => ({ ...current, [question.id]: e.target.value }))} className="mt-1 block w-full rounded-lg border p-2" rows={2} /></label>
              </fieldset>;
            })}
            {(selected.questions ?? []).every(q => q.type.toUpperCase() !== 'ESSAY') && <p className="text-sm text-slate-600">Không có câu tự luận cần chấm thủ công.</p>}
            {correctingPublished && (
              <fieldset className="space-y-2 rounded-xl border border-amber-300 bg-amber-50 p-4">
                <legend className="px-1 font-semibold text-amber-800">Sửa điểm bài đã công bố</legend>
                <label className="block text-sm">Lý do sửa điểm (bắt buộc)
                  <textarea required maxLength={1000} value={regradeReason} onChange={e => setRegradeReason(e.target.value)} placeholder="VD: Học viên khiếu nại, chấm lại theo đáp án chi tiết" className="mt-1 block w-full rounded-lg border p-2" rows={2} />
                </label>
              </fieldset>
            )}
            <button disabled={saving} className="rounded-xl bg-indigo-600 px-5 py-2.5 text-sm font-bold text-white disabled:opacity-50">{saving ? 'Đang lưu…' : correctingPublished ? 'Sửa điểm' : 'Lưu điểm'}</button>
            {selected.status === 'GRADING' && <p role="status" className="text-sm text-amber-700">Đã lưu một phần; kết quả chưa được công bố vì còn câu hỏi chưa chấm.</p>}
            {selected.status === 'PUBLISHED' && <p role="status" className="text-sm text-emerald-700">Đã công bố · Điểm: {selected.score ?? '—'}%</p>}
          </form>}
        </section>
      </div>

      {/* R13-07: "Kết quả đã công bố" — per exam the queue has surfaced, list PUBLISHED results
          with a "Sửa điểm" action that opens the same grading form above with a mandatory reason. */}
      {publishedExamIds.length > 0 && (
        <section className="rounded-2xl border bg-white p-5 space-y-4">
          <h2 className="text-lg font-bold">Kết quả đã công bố</h2>
          {publishedError && <div role="alert" className="rounded-xl bg-red-50 p-3 text-sm text-red-700">{publishedError}</div>}
          {publishedExamIds.map(examId => {
            const examTitle = queue.find(a => a.examId === examId)?.examTitle
              ?? (selected?.examId === examId ? selected?.examTitle : undefined)
              ?? examTitleById[examId]
              ?? examId;
            const rows = publishedByExam[examId];
            return <div key={examId} className="space-y-2">
              <div className="flex items-center justify-between">
                <h3 className="font-semibold text-sm">{examTitle}</h3>
                <button type="button" onClick={() => void loadPublishedForExam(examId)} disabled={publishedLoading[examId]} className="text-xs font-bold text-indigo-700 disabled:opacity-50">
                  {publishedLoading[examId] ? 'Đang tải…' : rows ? 'Tải lại' : 'Xem kết quả đã công bố'}
                </button>
              </div>
              {rows && (rows.length === 0 ? <p className="text-xs text-slate-600">Chưa có kết quả đã công bố.</p> : <ul className="space-y-1">
                {rows.map(attempt => <li key={attempt.id} className="flex items-center justify-between rounded-lg bg-slate-50 p-2 text-xs">
                  <span>{attempt.learnerDisplayName ?? attempt.userId} · Điểm: {attempt.score ?? '—'}%</span>
                  <button type="button" onClick={() => void openAttempt(attempt.id)} className="font-bold text-indigo-700">Sửa điểm</button>
                </li>)}
              </ul>)}
            </div>;
          })}
        </section>
      )}
    </>}

    {!canGradeExams && !canGradeAssignments && <p className="text-sm text-slate-500">Bạn không có quyền chấm bài trong lớp học này.</p>}
  </div>;
};
