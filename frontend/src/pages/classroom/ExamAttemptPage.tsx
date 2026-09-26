import React, { useEffect, useState, useRef } from 'react';
import { useParams, Link } from 'react-router-dom';
import { ExamAttempt, Question } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner, ForbiddenState } from '../../components/UIStates';
import { Award, Clock, ArrowLeft, CheckCircle2, AlertTriangle, Send, Check } from 'lucide-react';

export const ExamAttemptPage: React.FC = () => {
  const { slug, examId } = useParams<{ slug: string; examId: string }>();

  const [attempt, setAttempt] = useState<ExamAttempt | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [savedStatus, setSavedStatus] = useState<string | null>(null);

  // Student answers: questionId -> answer
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const answersRef = useRef<Record<string, string>>({});
  const inFlightAutosaveRef = useRef<Promise<any> | null>(null);
  const [timeLeft, setTimeLeft] = useState<number>(0);
  const [hasUnsavedAnswer, setHasUnsavedAnswer] = useState(false);
  const autosaveTimerRef = useRef<any>(null);
  // Authoritative server deadline. The countdown is derived from it on every tick rather than
  // decremented, so a throttled or suspended tab cannot drift past the real deadline.
  const endsAtRef = useRef<number>(0);
  // Answers changed in the browser but not yet accepted by the server. The server refuses
  // request-body answers at or after the deadline and grades only persisted autosaves, so these
  // must be flushed before any submit.
  const dirtyRef = useRef(false);
  const answerRevisionRef = useRef(0);
  const handleSubmitRef = useRef<() => Promise<void>>(async () => {});

  const startExam = async () => {
    if (!examId) return;
    try {
      setLoading(true);
      setError(null);
      const data = await api.post<ExamAttempt>(`/exams/${examId}/attempts`);
      setAttempt(data);

      // Restore previously autosaved answers (Finding 4, Finding 8)
      if (data.answers && data.answers.length > 0) {
        const restored: Record<string, string> = {};
        data.answers.forEach((a) => {
          if (a.questionId && a.studentAnswer) {
            restored[a.questionId] = a.studentAnswer;
          }
        });
        answersRef.current = restored;
        setAnswers(restored);
      }

      // Initialize timer from the authoritative server deadline
      endsAtRef.current = new Date(data.endsAt).getTime();
      dirtyRef.current = false;
      answerRevisionRef.current = 0;
      setHasUnsavedAnswer(false);
      setTimeLeft(Math.max(0, Math.ceil((endsAtRef.current - Date.now()) / 1000)));
    } catch (err: any) {
      setError(err.message || 'Không thể bắt đầu kỳ thi');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    startExam();
    return () => {
      if (autosaveTimerRef.current) {
        clearTimeout(autosaveTimerRef.current);
      }
    };
  }, [examId]);

  /**
   * Persists any answer the student has changed but not yet saved, cancelling the pending debounce
   * so the save happens now. Called before every submit (manual and deadline-triggered) because the
   * server grades only persisted autosaves once the deadline has passed.
   */
  const flushAutosave = async (attemptId: string) => {
    if (autosaveTimerRef.current) {
      clearTimeout(autosaveTimerRef.current);
      autosaveTimerRef.current = null;
    }
    if (inFlightAutosaveRef.current) {
      try { await inFlightAutosaveRef.current; } catch (ignored) {}
    }
    if (!dirtyRef.current) return;

    const snapshot = { ...answersRef.current };
    const savedRevision = answerRevisionRef.current;
    const promise = api.put(`/attempts/${attemptId}/answers`, { answers: snapshot });
    inFlightAutosaveRef.current = promise;
    try {
      await promise;
      // A newer edit may have happened while this snapshot was in flight. Only acknowledge
      // the revision the server actually received; leave newer edits dirty for the next flush.
      if (answerRevisionRef.current === savedRevision) {
        dirtyRef.current = false;
        setHasUnsavedAnswer(false);
        setSavedStatus('Đã tự động lưu');
      } else {
        dirtyRef.current = true;
        setHasUnsavedAnswer(true);
        setSavedStatus('Đang lưu...');
      }
    } catch (e) {
      // Keep the answer marked dirty: the next flush (or submit) retries it.
      setHasUnsavedAnswer(true);
      setSavedStatus('Lưu tự động thất bại — câu trả lời chưa được ghi nhận');
      throw e;
    } finally {
      if (inFlightAutosaveRef.current === promise) {
        inFlightAutosaveRef.current = null;
      }
    }
  };

  // Autosave answer to server
  const handleAnswerChange = (questionId: string, val: string) => {
    const nextAnswers = { ...answersRef.current, [questionId]: val };
    answersRef.current = nextAnswers;
    answerRevisionRef.current += 1;
    setAnswers(nextAnswers);

    if (attempt && attempt.status === 'IN_PROGRESS') {
      dirtyRef.current = true;
      setHasUnsavedAnswer(true);
      if (autosaveTimerRef.current) {
        clearTimeout(autosaveTimerRef.current);
      }
      setSavedStatus('Đang lưu...');
      autosaveTimerRef.current = setTimeout(() => {
        autosaveTimerRef.current = null;
        flushAutosave(attempt.id).catch(() => {});
      }, 500);
    }
  };

  // Countdown effect: the remaining time is recomputed from the server deadline on every tick,
  // so the displayed value and the auto-submit both track `endsAt` rather than a drifting counter.
  useEffect(() => {
    if (!attempt || attempt.status !== 'IN_PROGRESS' || !endsAtRef.current) return;

    let timer: any;
    const tick = () => {
      const remaining = Math.max(0, Math.ceil((endsAtRef.current - Date.now()) / 1000));
      setTimeLeft(remaining);
      if (remaining <= 0) {
        clearInterval(timer);
        void handleSubmitRef.current();
      }
    };

    tick();
    timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [attempt?.id, attempt?.status]);

  const handleSubmit = async () => {
    if (!attempt || submitting) return;

    setSubmitting(true);
    setSavedStatus('Đang nộp bài...');
    try {
      // Persist the pending debounced answer first.
      try {
        await flushAutosave(attempt.id);
      } catch (flushErr) {
        // If manual submission while answering time remains, stop and warn the user
        if (timeLeft > 0) {
          throw flushErr;
        }
      }

      if (timeLeft > 0 && dirtyRef.current) {
        throw new Error('Chưa thể lưu câu trả lời mới nhất. Vui lòng kiểm tra lại trước khi nộp bài.');
      }

      // Submit authoritatively with the latest synchronous answer state
      const latestAnswers = answersRef.current;
      const res = await api.post<ExamAttempt>(`/attempts/${attempt.id}/submit`, {
        answers: latestAnswers,
      });
      setAttempt(res);
      setSavedStatus('Đã nộp bài thành công');
    } catch (err: any) {
      alert(err.message || 'Nộp bài thất bại');
    } finally {
      setSubmitting(false);
    }
  };

  handleSubmitRef.current = handleSubmit;

  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  };

  if (loading) {
    return <LoadingSpinner message="Đang chuẩn bị đề thi..." />;
  }

  if (error || !attempt) {
    return (
      <div className="max-w-2xl mx-auto py-12">
        <ErrorBanner message={error || 'Không thể khởi tạo bài thi'} onRetry={startExam} />
      </div>
    );
  }

  // Result View after submit
  if (attempt.status !== 'IN_PROGRESS') {
    const isPublished = attempt.status === 'PUBLISHED';
    return (
      <div className="max-w-3xl mx-auto py-8">
        <div className="bg-white rounded-3xl border border-slate-200 p-8 shadow-sm text-center">
          <div className="w-16 h-16 rounded-full bg-emerald-100 text-emerald-600 mx-auto flex items-center justify-center mb-4">
            <CheckCircle2 className="w-8 h-8" />
          </div>
          <h2 className="text-2xl font-black text-slate-900 mb-1">Đã hoàn thành bài thi!</h2>
          <p className="text-sm text-slate-500 mb-6">{attempt.examTitle}</p>

          <div className="max-w-xs mx-auto bg-slate-50 p-6 rounded-2xl border border-slate-100 mb-8">
            <div className="text-xs uppercase font-bold text-slate-400 tracking-wider mb-1">
              Điểm số đạt được
            </div>
            <div className="text-5xl font-black text-indigo-600">
              {isPublished && attempt.score !== undefined ? `${attempt.score}%` : 'Chờ chấm điểm'}
            </div>
            <div className="text-xs text-slate-500 mt-2">
              Trạng thái: <span className="font-bold text-slate-700">{attempt.status}</span>
            </div>
          </div>

          <div className="flex justify-center space-x-4">
            <Link
              to={`/classes/${slug}/exams`}
              className="px-6 py-2.5 bg-slate-900 hover:bg-slate-800 text-white text-xs font-bold rounded-xl transition"
            >
              Về danh mục kỳ thi
            </Link>
            <Link
              to={`/classes/${slug}/leaderboard`}
              className="px-6 py-2.5 bg-indigo-50 hover:bg-indigo-100 text-indigo-700 text-xs font-bold rounded-xl transition"
            >
              Xem Bảng Xếp Hạng
            </Link>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="max-w-4xl mx-auto space-y-6 pb-12">
      {/* Top sticky bar with timer */}
      <div className="bg-white/90 backdrop-blur-md sticky top-20 z-30 p-4 rounded-2xl border border-slate-200 shadow-sm flex items-center justify-between">
        <div>
          <h2 className="text-sm font-bold text-slate-900">{attempt.examTitle}</h2>
          <div className="flex items-center space-x-2 text-xs text-slate-500">
            <span>Đang làm bài thi</span>
            {savedStatus && (
              <span
                className={`font-medium inline-flex items-center space-x-1 ${
                  hasUnsavedAnswer ? 'text-amber-600' : 'text-emerald-600'
                }`}
              >
                {hasUnsavedAnswer ? <AlertTriangle className="w-3 h-3" /> : <Check className="w-3 h-3" />}
                <span>{savedStatus}</span>
              </span>
            )}
          </div>
        </div>

        <div className="flex items-center space-x-4">
          <div className="flex items-center space-x-2 text-indigo-600 bg-indigo-50 px-3 py-1.5 rounded-xl border border-indigo-200 font-mono font-bold text-sm">
            <Clock className="w-4 h-4" />
            <span>{formatTime(timeLeft)}</span>
          </div>

          <button
            onClick={handleSubmit}
            disabled={submitting}
            className="px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold transition shadow-sm disabled:opacity-50 flex items-center space-x-1.5"
          >
            <Send className="w-3.5 h-3.5" />
            <span>{submitting ? 'Đang nộp...' : 'Nộp bài thi'}</span>
          </button>
        </div>
      </div>

      {hasUnsavedAnswer && (
        <p
          role="alert"
          className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-2 text-xs font-semibold text-amber-800"
        >
          Câu trả lời mới nhất chưa được lưu lên máy chủ. Chỉ những câu đã lưu trước hạn nộp mới được chấm.
        </p>
      )}

      {/* Questions list */}
      <div className="space-y-6">
        {attempt.questions &&
          attempt.questions.map((q, idx) => (
            <div key={q.id} className="bg-white rounded-2xl border border-slate-200 p-6 shadow-sm">
              <div className="flex justify-between items-center mb-3">
                <span className="text-xs font-bold text-indigo-600 uppercase tracking-wider">
                  Câu hỏi {idx + 1} ({q.points} điểm)
                </span>
                <span className="text-xs font-medium text-slate-400">{q.type}</span>
              </div>

              <p className="text-base font-semibold text-slate-800 mb-4">{q.questionText}</p>

              {/* Options */}
              {q.options && q.options.length > 0 && (
                <div className="space-y-2">
                  {q.options.map((opt) => (
                    <label
                      key={opt.optionKey}
                      className={`flex items-center space-x-3 p-3.5 rounded-xl border cursor-pointer transition ${
                        answers[q.id] === opt.optionKey
                          ? 'bg-indigo-50 border-indigo-400 font-bold text-indigo-950'
                          : 'bg-slate-50 border-slate-200 hover:bg-slate-100/70 text-slate-700'
                      }`}
                    >
                      <input
                        type="radio"
                        name={q.id}
                        value={opt.optionKey}
                        checked={answers[q.id] === opt.optionKey}
                        onChange={() => handleAnswerChange(q.id, opt.optionKey)}
                        className="text-indigo-600 focus:ring-indigo-500 h-4 w-4"
                      />
                      <span className="text-sm font-semibold">{opt.optionKey}.</span>
                      <span className="text-sm">{opt.optionText}</span>
                    </label>
                  ))}
                </div>
              )}

              {/* Essay textarea */}
              {q.type === 'ESSAY' && (
                <textarea
                  rows={4}
                  value={answers[q.id] || ''}
                  onChange={(e) => handleAnswerChange(q.id, e.target.value)}
                  placeholder="Nhập câu trả lời tự luận của bạn..."
                  className="w-full p-3.5 bg-slate-50 border border-slate-200 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
              )}
            </div>
          ))}
      </div>

      <div className="text-center pt-4">
        <button
          onClick={handleSubmit}
          disabled={submitting}
          className="px-8 py-3 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-sm font-bold shadow-md transition disabled:opacity-50"
        >
          {submitting ? 'Đang nộp bài...' : 'Hoàn tất & Nộp bài thi'}
        </button>
      </div>
    </div>
  );
};
