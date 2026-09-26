import React, { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, Course, Lesson } from '../../types';
import { api } from '../../api/client';

type Question = { id: string; questionText: string; type: string; points: number };
type Answer = { questionId: string; studentAnswer: string; pointsAwarded: number | null; teacherFeedback: string | null };
type Attempt = { id: string; examTitle: string; userId: string; status: string; score: number | null; questions?: Question[]; answers?: Answer[] };
type AssignmentSubmission = { id: string; lessonId: string; userId: string; submissionText: string; attemptNumber: number; score: number | null; feedback: string | null; submittedAt: string };

export const StudioGrading: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const [queue, setQueue] = useState<Attempt[]>([]);
  const [selected, setSelected] = useState<Attempt | null>(null);
  const [scores, setScores] = useState<Record<string, string>>({});
  const [feedback, setFeedback] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [assignmentLessons, setAssignmentLessons] = useState<Array<Lesson & { courseTitle: string }>>([]);
  const [assignmentQueues, setAssignmentQueues] = useState<Record<string, AssignmentSubmission[]>>({});
  const [assignmentScores, setAssignmentScores] = useState<Record<string, string>>({});
  const [assignmentFeedback, setAssignmentFeedback] = useState<Record<string, string>>({});

  const loadQueue = async () => {
    setLoading(true);
    try {
      setQueue(await api.get<Attempt[]>(`/classes/${classroom.id}/grading-queue`));
      setError('');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Không thể tải hàng đợi chấm bài');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void loadQueue(); }, [classroom.id]);

  useEffect(() => {
    void (async () => {
      try {
        const courses = await api.get<Course[]>(`/classes/${classroom.id}/courses`);
        const fullCourses = await Promise.all(
          (courses ?? []).map(async (c) => {
            try {
              return await api.get<Course>(`/courses/${c.id}`);
            } catch {
              return c;
            }
          })
        );
        const lessons = (fullCourses ?? []).flatMap(course => (course.sections ?? []).flatMap(section => (section.lessons ?? [])
          .filter(lesson => lesson.type === 'ASSIGNMENT').map(lesson => ({ ...lesson, courseTitle: course.title }))));
        setAssignmentLessons(lessons);
        const queues = await Promise.all(lessons.map(async lesson => [lesson.id, await api.get<AssignmentSubmission[]>(`/lessons/${lesson.id}/submissions`)] as const));
        setAssignmentQueues(Object.fromEntries(queues));
      } catch (e) { setError(e instanceof Error ? e.message : 'Không thể tải bài tập cần chấm'); }
    })();
  }, [classroom.id]);

  const gradeAssignment = async (submission: AssignmentSubmission) => {
    try {
      await api.put(`/assignment-submissions/${submission.id}/grade`, {
        score: Number(assignmentScores[submission.id]), feedback: assignmentFeedback[submission.id] ?? '',
      });
      const latest = await api.get<AssignmentSubmission[]>(`/lessons/${submission.lessonId}/submissions`);
      setAssignmentQueues(current => ({ ...current, [submission.lessonId]: latest }));
    } catch (e) { setError(e instanceof Error ? e.message : 'Chấm bài tập thất bại'); }
  };

  const openAttempt = async (attemptId: string) => {
    try {
      const attempt = await api.get<Attempt>(`/attempts/${attemptId}/grading`);
      setSelected(attempt);
      const essayIds = new Set((attempt.questions ?? []).filter(q => q.type.toUpperCase() === 'ESSAY').map(q => q.id));
      setScores(Object.fromEntries((attempt.answers ?? []).filter(a => essayIds.has(a.questionId) && a.pointsAwarded != null).map(a => [a.questionId, String(a.pointsAwarded)])));
      setFeedback(Object.fromEntries((attempt.answers ?? []).filter(a => a.teacherFeedback).map(a => [a.questionId, a.teacherFeedback ?? ''])));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Không thể tải bài làm');
    }
  };

  const submitGrades = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!selected) return;
    setSaving(true);
    try {
      const numericScores = Object.fromEntries(Object.entries(scores).filter(([, value]) => value.trim() !== '').map(([id, value]) => [id, Number(value)]));
      const result = await api.post<Attempt>(`/attempts/${selected.id}/grade`, { scores: numericScores, feedback });
      setSelected(result);
      await loadQueue();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Chấm bài thất bại');
    } finally {
      setSaving(false);
    }
  };

  return <div className="max-w-5xl mx-auto space-y-6">
    <header><h1 className="text-2xl font-black text-slate-900">Chấm bài</h1><p className="text-sm text-slate-500">Bài thi và bài tập chờ chấm · {classroom.title}</p></header>
    <section className="space-y-4 rounded-2xl border bg-white p-5">
      <h2 className="text-lg font-bold">Bài tập</h2>
      {assignmentLessons.length === 0 ? <p className="text-sm text-slate-500">Chưa có bài tập trong khóa học.</p> : assignmentLessons.map(lesson => <div key={lesson.id} className="space-y-3 rounded-xl border p-4">
        <h3 className="font-semibold">{lesson.courseTitle} · {lesson.title}</h3>
        {(assignmentQueues[lesson.id] ?? []).length === 0 ? <p className="text-sm text-slate-500">Chưa có bài nộp.</p> : (assignmentQueues[lesson.id] ?? []).map(submission => <article key={submission.id} className="space-y-2 rounded-lg bg-slate-50 p-3">
          <p className="text-xs text-slate-500">Học viên {submission.userId} · Lần {submission.attemptNumber}</p><p className="whitespace-pre-wrap text-sm">{submission.submissionText}</p>
          <div className="flex flex-wrap gap-2"><label className="text-sm">Điểm<input type="number" min="0" step="0.01" value={assignmentScores[submission.id] ?? (submission.score == null ? '' : String(submission.score))} onChange={event => setAssignmentScores(current => ({ ...current, [submission.id]: event.target.value }))} className="ml-2 w-28 rounded border p-2" /></label>
          <input aria-label="Phản hồi bài tập" placeholder="Phản hồi" value={assignmentFeedback[submission.id] ?? submission.feedback ?? ''} onChange={event => setAssignmentFeedback(current => ({ ...current, [submission.id]: event.target.value }))} className="min-w-48 flex-1 rounded border p-2" />
          <button type="button" onClick={() => void gradeAssignment(submission)} disabled={!((assignmentScores[submission.id] ?? (submission.score == null ? '' : String(submission.score))).trim())} className="rounded bg-indigo-600 px-3 py-2 text-sm font-bold text-white disabled:opacity-50">Lưu điểm</button></div>
        </article>)}
      </div>)}
    </section>
    {error && <div role="alert" className="rounded-xl bg-red-50 p-3 text-sm text-red-700">{error}</div>}
    <div className="grid gap-5 lg:grid-cols-[minmax(16rem,0.8fr)_minmax(0,1.2fr)]">
      <section className="rounded-2xl border bg-white p-4" aria-label="Hàng đợi chấm bài">
        <h2 className="mb-3 font-bold">Hàng đợi</h2>
        {loading ? <p>Đang tải…</p> : queue.length === 0 ? <p className="text-sm text-slate-500">Không có bài cần chấm.</p> : <ul className="space-y-2">
          {queue.map(attempt => <li key={attempt.id}><button type="button" onClick={() => void openAttempt(attempt.id)} className="w-full rounded-xl border p-3 text-left hover:bg-slate-50">
            <span className="block font-semibold">{attempt.examTitle}</span><span className="text-xs text-slate-500">{attempt.userId} · {attempt.status}</span>
          </button></li>)}
        </ul>}
      </section>
      <section className="rounded-2xl border bg-white p-5">
        {!selected ? <p className="text-sm text-slate-500">Chọn bài làm để xem câu trả lời và chấm tự luận.</p> : <form onSubmit={submitGrades} className="space-y-5">
          <div><h2 className="font-bold">{selected.examTitle}</h2><p className="text-xs text-slate-500">Trạng thái hiện tại: {selected.status}</p></div>
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
          <button disabled={saving} className="rounded-xl bg-indigo-600 px-5 py-2.5 text-sm font-bold text-white disabled:opacity-50">{saving ? 'Đang lưu…' : 'Lưu điểm'}</button>
          {selected.status === 'GRADING' && <p role="status" className="text-sm text-amber-700">Đã lưu một phần; kết quả chưa được công bố vì còn câu hỏi chưa chấm.</p>}
          {selected.status === 'PUBLISHED' && <p role="status" className="text-sm text-emerald-700">Đã công bố · Điểm: {selected.score ?? '—'}%</p>}
        </form>}
      </section>
    </div>
  </div>;
};
