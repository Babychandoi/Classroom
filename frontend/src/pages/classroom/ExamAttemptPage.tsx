import React, { useEffect, useState, useRef } from 'react';
import { useParams, useNavigate, useSearchParams, Link } from 'react-router-dom';
import { ExamAttempt, Question } from '../../types';
import { api, ApiException } from '../../api/client';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { Modal } from '../../components/Modal';
import { Badge, Textarea, buttonClass } from '../../components/ui';
import { ArrowLeft, Clock, AlertTriangle, Send, Check, PlayCircle, Eye, RefreshCw } from 'lucide-react';

/** Letter keys (A, B, …) are shown as a prefix; word keys such as TRUE/FALSE are not - the option text already says it. */
export const showOptionKey = (key: string) => /^[A-Za-z0-9]$/.test(key);

const QUESTION_TYPE_LABEL: Record<Question['type'], string> = {
  MULTIPLE_CHOICE: 'Trắc nghiệm',
  TRUE_FALSE: 'Đúng / Sai',
  ESSAY: 'Tự luận',
};

/**
 * R20-07: a failed autosave that is worth retrying - no HTTP response at all (network down, backend restarting: the API client
 * reports it as status 503 / NETWORK_ERROR), a 5xx / 502-504 from the server or the proxy in front of it, 408 or 429. A definitive
 * refusal (400 "the attempt has ended", 401/403/404 ...) is NOT retried: repeating it cannot succeed.
 */
export const isTransientSaveError = (e: unknown): boolean => {
  if (e instanceof ApiException) {
    const status = e.status;
    return status === undefined || status === 0 || status === 408 || status === 429 || status >= 500;
  }
  // Anything that is not an API answer (a rejected fetch that escaped the client) is a connectivity problem.
  return true;
};

/** Backoff of the autosave retry loop: 1 s, 2 s, 4 s ... capped at 30 s; reset by any successful save, 'online' or tab focus. */
export const AUTOSAVE_RETRY_BASE_MS = 1000;
/** Mirrors ExamService.MAX_ANSWER_CHARS: the server refuses (400) a longer answer, so the box stops accepting text at the limit. */
export const ESSAY_MAX_CHARS = 10000;
export const AUTOSAVE_RETRY_MAX_MS = 30000;
/** Seconds before the deadline at which a still-unsaved answer is pushed again (the deadline submit is the last chance). */
export const PRE_DEADLINE_FLUSH_SECONDS = [10, 3];

export const ExamAttemptPage: React.FC = () => {
  const { slug, examId } = useParams<{ slug: string; examId: string }>();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  // R13-04: preview mode is opted into via ?preview=1 — set only by StudioExams' "Chạy thử"
  // action. The server is the actual authority (startAttempt/getGradingAttempt-style
  // enforceEnterExam(..., isStaffPreview=true) rejects anyone without EXAM:PREVIEW/EXAM:EDIT), this
  // flag only controls which endpoints/UI copy this page uses.
  const isPreview = searchParams.get('preview') === '1';

  const [attempt, setAttempt] = useState<ExamAttempt | null>(null);
  // R8-02: null while we haven't confirmed either way yet, then true/false once the mount-time
  // resumeOnly check has answered whether an IN_PROGRESS attempt exists to resume.
  const [needsStartConfirmation, setNeedsStartConfirmation] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [savedStatus, setSavedStatus] = useState<string | null>(null);
  // Submit confirmation dialog, and the question the navigator rail highlights (the one last focused / jumped to).
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [currentQuestion, setCurrentQuestion] = useState(0);

  // Student answers: questionId -> answer
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const answersRef = useRef<Record<string, string>>({});
  const inFlightAutosaveRef = useRef<Promise<any> | null>(null);
  const [timeLeft, setTimeLeft] = useState<number>(0);
  const [hasUnsavedAnswer, setHasUnsavedAnswer] = useState(false);
  const autosaveTimerRef = useRef<any>(null);
  // R20-07: exponential-backoff retry of a failed autosave (network / 5xx), and the "still trying" status it shows.
  const [retrying, setRetrying] = useState(false);
  const retryTimerRef = useRef<any>(null);
  const retryDelayRef = useRef(AUTOSAVE_RETRY_BASE_MS);
  const preDeadlineFlushedRef = useRef<Set<number>>(new Set());
  const flushNowRef = useRef<() => void>(() => {});
  // Authoritative server deadline. The countdown is derived from it on every tick rather than
  // decremented, so a throttled or suspended tab cannot drift past the real deadline.
  const endsAtRef = useRef<number>(0);
  // Answers changed in the browser but not yet accepted by the server. The server refuses
  // request-body answers at or after the deadline and grades only persisted autosaves, so these
  // must be flushed before any submit.
  const dirtyRef = useRef(false);
  const answerRevisionRef = useRef(0);
  const handleSubmitRef = useRef<(isDeadline?: boolean) => Promise<void>>(async () => {});

  const applyAttempt = (data: ExamAttempt) => {
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
    retryDelayRef.current = AUTOSAVE_RETRY_BASE_MS;
    preDeadlineFlushedRef.current = new Set();
    setRetrying(false);
    setHasUnsavedAnswer(false);
    setTimeLeft(Math.max(0, Math.ceil((endsAtRef.current - Date.now()) / 1000)));
  };

  // R8-02: mount only ever tries to RESUME an existing IN_PROGRESS attempt (resumeOnly=true never
  // creates one). A fresh page load (first visit, or F5 after a previous submit) with nothing to
  // resume must not silently burn an attempt — it shows an explicit "Bắt đầu làm bài" action instead.
  //
  // R13-04: this resumeOnly-first flow is a *student* (non-preview) concern. In preview mode,
  // ExamService.startAttempt takes the isStaffPreview branch entirely and never consults
  // resumeOnly (it starts - or, since R14-15, resumes the still-running - preview attempt there), so
  // resumeOnly must never be sent alongside preview=true — see startPreview below instead.
  const checkForResumableAttempt = async () => {
    if (!examId) return;
    try {
      setLoading(true);
      setError(null);
      setNeedsStartConfirmation(false);
      const data = await api.post<ExamAttempt>(`/exams/${examId}/attempts?resumeOnly=true`);
      applyAttempt(data);
    } catch (err: any) {
      if (err instanceof ApiException && err.code === 'NOT_FOUND') {
        setNeedsStartConfirmation(true);
      } else {
        setError(err.message || 'Không thể tải bài thi');
      }
    } finally {
      setLoading(false);
    }
  };

  // Explicit "Bắt đầu làm bài" action: only this ever creates a NEW attempt.
  const startExam = async () => {
    if (!examId) return;
    try {
      setLoading(true);
      setError(null);
      const data = await api.post<ExamAttempt>(`/exams/${examId}/attempts`);
      setNeedsStartConfirmation(false);
      applyAttempt(data);
    } catch (err: any) {
      setError(err.message || 'Không thể bắt đầu kỳ thi');
    } finally {
      setLoading(false);
    }
  };

  // R13-04: preview mode always goes straight through the isStaffPreview branch of
  // ExamService.startAttempt (permission-gated: OWNER or EXAM:PREVIEW/EXAM:EDIT). No start
  // confirmation step — Studio's "Chạy thử" is itself the explicit confirmation — and never
  // resumeOnly, since the server ignores it for preview anyway. R14-15: this is the ONLY place a
  // preview attempt is started (Studio just navigates here); the server returns the caller's existing
  // IN_PROGRESS preview attempt for this exam instead of creating another, so a re-mount or a repeated
  // "Chạy thử" resumes the same attempt.
  const startPreview = async () => {
    if (!examId) return;
    try {
      setLoading(true);
      setError(null);
      const data = await api.post<ExamAttempt>(`/exams/${examId}/attempts?preview=true`);
      applyAttempt(data);
    } catch (err: any) {
      setError(err.message || 'Không thể mở chế độ xem thử');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (isPreview) {
      startPreview();
    } else {
      checkForResumableAttempt();
    }
    return () => {
      if (autosaveTimerRef.current) {
        clearTimeout(autosaveTimerRef.current);
      }
      if (retryTimerRef.current) {
        clearTimeout(retryTimerRef.current);
        retryTimerRef.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [examId, isPreview]);

  const clearRetryTimer = () => {
    if (retryTimerRef.current) {
      clearTimeout(retryTimerRef.current);
      retryTimerRef.current = null;
    }
  };

  /** Schedules the next background attempt after the current backoff delay, then doubles the delay (cap 30 s). */
  const scheduleRetry = (attemptId: string) => {
    if (retryTimerRef.current) return;
    const delay = retryDelayRef.current;
    retryDelayRef.current = Math.min(AUTOSAVE_RETRY_MAX_MS, delay * 2);
    retryTimerRef.current = setTimeout(() => {
      retryTimerRef.current = null;
      flushAutosave(attemptId).catch(() => {});
    }, delay);
  };

  /**
   * Persists any answer the student has changed but not yet saved, cancelling the pending debounce
   * so the save happens now. Called before every submit (manual and deadline-triggered) because the
   * server grades only persisted autosaves once the deadline has passed.
   *
   * R20-07: a TRANSIENT failure (no connection, backend restarting, 5xx/502-504, 408, 429 - see
   * {@link isTransientSaveError}) does not throw and is not reported as a failure: the answers stay dirty, the status
   * calmly says "Đang lưu lại…", and the save is retried with exponential backoff (1 s -> 30 s) until it succeeds - and
   * immediately when the browser comes back online, the tab becomes visible again, or the deadline is near. Only a
   * definitive refusal still throws. Before this, only a 429 was retried: after a backend restart the answers stayed
   * unsaved until the student typed again, and were lost at the deadline.
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
    // This flush supersedes any backoff retry that was waiting.
    clearRetryTimer();

    const snapshot = { ...answersRef.current };
    const savedRevision = answerRevisionRef.current;
    const promise = api.put(`/attempts/${attemptId}/answers`, { answers: snapshot });
    inFlightAutosaveRef.current = promise;
    try {
      await promise;
      retryDelayRef.current = AUTOSAVE_RETRY_BASE_MS;
      setRetrying(false);
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
      setHasUnsavedAnswer(true);
      if (isTransientSaveError(e)) {
        // R13-11(a) / R20-07: keep the answer dirty and try again on the backoff; not the alarming "chưa được ghi nhận" copy.
        setRetrying(true);
        setSavedStatus(
          e instanceof ApiException && e.status === 429
            ? 'Đang lưu lại… (tạm hoãn do quá nhiều yêu cầu, sẽ tự thử lại)'
            : 'Đang lưu lại…'
        );
        scheduleRetry(attemptId);
      } else {
        setRetrying(false);
        setSavedStatus('Lưu tự động thất bại — câu trả lời chưa được ghi nhận');
        throw e;
      }
    } finally {
      if (inFlightAutosaveRef.current === promise) {
        inFlightAutosaveRef.current = null;
      }
    }
  };

  /**
   * Pushes unsaved answers NOW and restarts the backoff from its shortest delay: used when connectivity may just have come
   * back ('online', tab visible again) and shortly before the deadline.
   */
  const flushNowIfDirty = () => {
    if (!attempt || attempt.status !== 'IN_PROGRESS' || !dirtyRef.current || submitting) return;
    clearRetryTimer();
    retryDelayRef.current = AUTOSAVE_RETRY_BASE_MS;
    flushAutosave(attempt.id).catch(() => {});
  };
  flushNowRef.current = flushNowIfDirty;

  // R20-07: retry as soon as the browser reports the network is back, or the student returns to this tab (a throttled or
  // suspended background tab may have missed its backoff timer entirely).
  useEffect(() => {
    if (!attempt || attempt.status !== 'IN_PROGRESS') return;
    const onOnline = () => flushNowRef.current();
    const onVisibility = () => {
      if (document.visibilityState === 'visible') flushNowRef.current();
    };
    window.addEventListener('online', onOnline);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.removeEventListener('online', onOnline);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [attempt?.id, attempt?.status]);

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
      // R20-07: shortly BEFORE the deadline push anything still unsaved - the server refuses request-body answers at/after the
      // deadline, so a save that is stuck in backoff would otherwise be lost. (The deadline submit below re-sends every answer too.)
      for (const threshold of PRE_DEADLINE_FLUSH_SECONDS) {
        if (remaining > 0 && remaining <= threshold && !preDeadlineFlushedRef.current.has(threshold)) {
          preDeadlineFlushedRef.current.add(threshold);
          flushNowRef.current();
        }
      }
      if (remaining <= 0) {
        clearInterval(timer);
        void handleSubmitRef.current(true);
      }
    };

    tick();
    timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [attempt?.id, attempt?.status]);

  /**
   * R9-02: `isDeadline` is passed explicitly by the countdown effect instead of being inferred from
   * the `timeLeft` state, because the interval tick that fires the auto-submit closes over
   * whatever `timeLeft` was at the *previous* render (it can still read 1, not 0, at the instant
   * the deadline auto-submit runs) - checking `timeLeft > 0` here to decide "is this the deadline"
   * was therefore unreliable. The authoritative deadline is `endsAtRef.current`, read fresh.
   *
   * At the deadline, a flush failure (e.g. the server now rejects request-body answers at/after
   * endsAt with 400) must not leave the student stuck at 00:00 with an alert and no submit: the
   * server grades whatever was already persisted (or the timeout sweeper finalizes it), so the
   * submit call still goes out, and a "this attempt was already finalized" response is treated as
   * success (the sweeper or a concurrent deadline tick already closed it out) rather than an error.
   *
   * R10-05: a *manual* submit started with time still remaining computes `atDeadline = false` up
   * front, but the deadline can arrive while `flushAutosave` is still in flight (the countdown tick
   * that would otherwise auto-submit is a no-op here because `submitting` is already true - see the
   * early return above). If the flush then fails, that stale `atDeadline = false` used to make this
   * rethrow as a hard error instead of proceeding with deadline semantics. Re-read the deadline
   * fresh right before deciding whether to rethrow, so a flush failure that lands at/after `endsAt`
   * is always treated as the deadline case (submit anyway, navigate to the result page) regardless
   * of what `atDeadline` read when this call started.
   */
  const handleSubmit = async (isDeadline = false) => {
    if (!attempt || submitting) return;
    let atDeadline = isDeadline || endsAtRef.current - Date.now() <= 0;

    // The deadline submit must not wait behind an open confirmation dialog.
    setConfirmOpen(false);
    setSubmitting(true);
    setSavedStatus('Đang nộp bài...');
    try {
      // Persist the pending debounced answer first.
      try {
        await flushAutosave(attempt.id);
      } catch (flushErr) {
        // R10-05: the deadline may have arrived while the flush above was in flight - re-check
        // before deciding whether this is still a genuine mid-exam failure.
        atDeadline = atDeadline || endsAtRef.current - Date.now() <= 0;
        // Manual submission with time still remaining: stop and let the student see/retry the
        // failure. At the deadline, keep going - the server grades the last successful autosave.
        if (!atDeadline) {
          throw flushErr;
        }
      }

      if (!atDeadline && dirtyRef.current) {
        throw new Error('Chưa thể lưu câu trả lời mới nhất. Vui lòng kiểm tra lại trước khi nộp bài.');
      }

      // Submit authoritatively with the latest synchronous answer state
      const latestAnswers = answersRef.current;
      let res: ExamAttempt;
      try {
        res = await api.post<ExamAttempt>(`/attempts/${attempt.id}/submit`, {
          answers: latestAnswers,
        });
      } catch (submitErr: any) {
        // R9-02: at the deadline, a concurrent finalize (the timeout sweeper, or another tab/tick)
        // may have already closed this attempt out. That is success, not failure - re-fetch the
        // now-finalized attempt instead of surfacing an error and leaving the student stuck.
        if (atDeadline && submitErr instanceof ApiException
            && (submitErr.code === 'ALREADY_SUBMITTED' || submitErr.code === 'CONFLICT' || submitErr.status === 409)) {
          res = await api.post<ExamAttempt>(`/exams/${examId}/attempts?resumeOnly=true`).catch(() => attempt);
        } else {
          throw submitErr;
        }
      }
      setSavedStatus('Đã nộp bài thành công');
      // R8-02: leave the /attempt URL once and for all after a submit, so an F5 afterwards loads
      // the result page (which only ever reads, never creates an attempt) instead of re-mounting
      // this page and burning a new attempt. R13-04: carry the preview flag through so the result
      // page can label a preview attempt's result accordingly.
      navigate(`/classes/${slug}/exams/${examId}/result?attemptId=${res.id}${isPreview ? '&preview=1' : ''}`, { replace: true });
    } catch (err: any) {
      if (atDeadline) {
        // R9-02: never leave the student stuck at 00:00 with just an alert - still navigate to the
        // result page; it only reads the attempt (never creates one), and the server-side timeout
        // sweeper is the ultimate authority on finalizing an expired attempt regardless of whether
        // this particular submit call succeeded.
        navigate(`/classes/${slug}/exams/${examId}/result?attemptId=${attempt.id}${isPreview ? '&preview=1' : ''}`, { replace: true });
      } else {
        alert(err.message || 'Nộp bài thất bại');
      }
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
    return <LoadingSpinner message="Đang tải bài thi..." />;
  }

  // R8-02: nothing to resume — require an explicit confirmation before ever creating an attempt,
  // instead of auto-starting (and burning a lượt) just because the page was opened or reloaded.
  if (needsStartConfirmation) {
    return (
      <div className="mx-auto max-w-md py-8 sm:py-12">
        <div className="rounded-card border border-slate-200 bg-white p-6 text-center shadow-hairline sm:p-8">
          <span className="mx-auto mb-4 inline-flex h-14 w-14 items-center justify-center rounded-full bg-tint text-blue-600" aria-hidden="true">
            <PlayCircle className="h-7 w-7" strokeWidth={1.6} />
          </span>
          <h2 className="text-h2-sm font-semibold text-slate-900">Sẵn sàng làm bài?</h2>
          <p className="mx-auto mt-2 max-w-[340px] text-ui text-slate-600">
            Khi bắt đầu, một lượt làm bài sẽ được tính và đồng hồ đếm ngược sẽ chạy ngay lập tức.
          </p>
          <ul className="mx-auto mt-4 max-w-[340px] space-y-1.5 text-left text-meta text-slate-600">
            <li className="flex gap-2"><Check className="mt-0.5 h-4 w-4 flex-shrink-0 text-green-600" strokeWidth={2.2} aria-hidden="true" />Câu trả lời được tự động lưu khi bạn làm.</li>
            <li className="flex gap-2"><Check className="mt-0.5 h-4 w-4 flex-shrink-0 text-green-600" strokeWidth={2.2} aria-hidden="true" />Hết giờ, bài được nộp với các câu đã lưu.</li>
          </ul>
          <div className="mt-6 flex flex-col-reverse justify-center gap-2.5 sm:flex-row">
            <Link to={`/classes/${slug}/exams`} className={buttonClass('secondary', 'lg')}>
              Quay lại
            </Link>
            <button type="button" onClick={startExam} className={buttonClass('primary', 'lg')}>
              <PlayCircle className="h-4 w-4" strokeWidth={1.9} aria-hidden="true" />
              <span>Bắt đầu làm bài</span>
            </button>
          </div>
        </div>
      </div>
    );
  }

  if (error || !attempt) {
    return (
      <div className="mx-auto max-w-2xl py-12">
        <ErrorBanner message={error || 'Không thể khởi tạo bài thi'} onRetry={isPreview ? startPreview : checkForResumableAttempt} />
      </div>
    );
  }

  // The only attempts this page ever holds are IN_PROGRESS ones (resumeOnly only ever returns an
  // IN_PROGRESS attempt, and startExam creates a fresh IN_PROGRESS one) — anything else (e.g. the
  // timeout sweeper finalized it concurrently) means there is nothing left to answer here.
  if (attempt.status !== 'IN_PROGRESS') {
    navigate(`/classes/${slug}/exams/${examId}/result?attemptId=${attempt.id}${isPreview ? '&preview=1' : ''}`, { replace: true });
    return <LoadingSpinner message="Đang chuyển đến trang kết quả..." />;
  }

  const questions = attempt.questions ?? [];
  const isAnswered = (questionId: string) => (answers[questionId] ?? '').trim().length > 0;
  const answeredCount = questions.filter((q) => isAnswered(q.id)).length;
  const unansweredCount = questions.length - answeredCount;
  // Calm by default; the chip only changes tone in the last minutes (with the label, never color alone).
  const timeTone = timeLeft <= 60 ? 'border-red-200 bg-red-50 text-red-700' : timeLeft <= 300 ? 'border-amber-200 bg-warn-soft text-amber-800' : 'border-slate-200 bg-white text-slate-900';

  const goToQuestion = (index: number) => {
    setCurrentQuestion(index);
    const card = document.getElementById(`question-${index + 1}`);
    card?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
    card?.querySelector<HTMLElement>('input, textarea')?.focus({ preventScroll: true });
  };

  const navigator = (
    <nav aria-label="Danh sách câu hỏi" className="rounded-2xl border border-slate-200 bg-white p-4 shadow-hairline">
      <div className="flex items-baseline justify-between gap-3">
        <p className="text-meta font-semibold text-slate-900">Câu hỏi</p>
        <p className="text-caption text-slate-500 tabular">Đã trả lời {answeredCount}/{questions.length}</p>
      </div>
      <ol className="mt-3 flex flex-wrap gap-2 lg:grid lg:grid-cols-5">
        {questions.map((q, idx) => {
          const answered = isAnswered(q.id);
          const current = idx === currentQuestion;
          return (
            <li key={q.id}>
              <button
                type="button"
                onClick={() => goToQuestion(idx)}
                aria-current={current ? 'step' : undefined}
                aria-label={`Câu ${idx + 1}${answered ? ', đã trả lời' : ', chưa trả lời'}`}
                className={`inline-flex h-10 w-10 items-center justify-center rounded-[10px] text-meta font-semibold tabular transition-colors duration-micro ${
                  current
                    ? 'bg-blue-600 text-white'
                    : answered
                      ? 'border border-blue-200 bg-tint text-blue-700 hover:bg-blue-100'
                      : 'border border-slate-200 bg-white text-slate-600 hover:bg-slate-100'
                }`}
              >
                {idx + 1}
              </button>
            </li>
          );
        })}
      </ol>
      <div className="mt-3 flex flex-wrap gap-x-3 gap-y-1 border-t border-slate-100 pt-3 text-caption text-slate-500">
        <span className="inline-flex items-center gap-1.5"><span aria-hidden="true" className="h-2.5 w-2.5 rounded-[3px] border border-blue-200 bg-tint" />Đã trả lời</span>
        <span className="inline-flex items-center gap-1.5"><span aria-hidden="true" className="h-2.5 w-2.5 rounded-[3px] border border-slate-200 bg-white" />Chưa trả lời</span>
      </div>
    </nav>
  );

  return (
    <div className="space-y-4 pb-12">
      {isPreview && (
        <p role="status" className="flex items-center gap-2 rounded-btn border border-amber-200 bg-warn-soft px-4 py-2.5 text-meta font-semibold text-amber-800">
          <Eye className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" />
          Chế độ xem thử — không tính điểm/xếp hạng
        </p>
      )}

      {/* Sticky bar: back, title + autosave state, server-driven countdown, the one primary action. */}
      <div className="glass-bar sticky top-[72px] z-30 flex flex-wrap items-center gap-x-4 gap-y-2.5 rounded-2xl border border-slate-200 px-4 py-3 shadow-hairline sm:flex-nowrap sm:px-5">
        <Link
          to={`/classes/${slug}/exams`}
          className="inline-flex min-h-[40px] flex-shrink-0 items-center gap-1.5 text-ui font-medium text-slate-600 transition-colors duration-micro hover:text-slate-900"
        >
          <ArrowLeft className="h-4 w-4" strokeWidth={1.8} aria-hidden="true" />
          Thi
        </Link>
        <span aria-hidden="true" className="hidden h-5 w-px flex-shrink-0 bg-slate-200 sm:block" />
        <div className="min-w-0 flex-1 basis-[160px]">
          <h2 className="truncate text-ui font-semibold text-slate-900">{attempt.examTitle}</h2>
          <div className="flex min-h-[18px] items-center gap-2 text-caption text-slate-500">
            {savedStatus ? (
              <span
                aria-live="polite"
                className={`inline-flex min-w-0 items-center gap-1 font-medium ${
                  hasUnsavedAnswer ? (retrying ? 'text-slate-600' : 'text-amber-800') : 'text-green-800'
                }`}
              >
                {hasUnsavedAnswer ? (
                  retrying ? <RefreshCw className="h-3 w-3 flex-shrink-0 animate-spin" aria-hidden="true" /> : <AlertTriangle className="h-3 w-3 flex-shrink-0" aria-hidden="true" />
                ) : (
                  <Check className="h-3 w-3 flex-shrink-0" strokeWidth={2.4} aria-hidden="true" />
                )}
                <span className="truncate">{savedStatus}</span>
              </span>
            ) : (
              <span>Đang làm bài thi</span>
            )}
          </div>
        </div>

        <div className="flex w-full items-center gap-2.5 sm:w-auto">
          <div
            role="timer"
            aria-label="Thời gian còn lại"
            className={`inline-flex h-10 items-center gap-2 rounded-btn border px-3 text-ui font-semibold tabular ${timeTone}`}
          >
            <Clock className="h-4 w-4" strokeWidth={1.9} aria-hidden="true" />
            <span>{formatTime(timeLeft)}</span>
          </div>
          <button
            type="button"
            onClick={() => setConfirmOpen(true)}
            disabled={submitting}
            className={buttonClass('primary', 'md', 'flex-1 sm:flex-none')}
          >
            <Send className="h-3.5 w-3.5" strokeWidth={1.9} aria-hidden="true" />
            <span>{submitting ? 'Đang nộp...' : 'Nộp bài thi'}</span>
          </button>
        </div>
      </div>

      {retrying && (
        <p
          role="status"
          className="rounded-btn border border-slate-200 bg-slate-50 px-4 py-2.5 text-meta font-medium text-slate-700"
        >
          Đang lưu lại… Kết nối tới máy chủ đang gián đoạn nên bài làm mới nhất chưa được lưu. Bạn cứ tiếp tục làm bài: hệ thống
          tự thử lại và sẽ lưu ngay khi có kết nối.
        </p>
      )}

      {hasUnsavedAnswer && !retrying && (
        <p
          role="alert"
          className="flex items-start gap-2 rounded-btn border border-amber-200 bg-warn-soft px-4 py-2.5 text-meta font-medium text-amber-800"
        >
          <AlertTriangle className="mt-0.5 h-4 w-4 flex-shrink-0" strokeWidth={1.9} aria-hidden="true" />
          Câu trả lời mới nhất chưa được lưu lên máy chủ. Chỉ những câu đã lưu trước hạn nộp mới được chấm.
        </p>
      )}

      <div className="grid grid-cols-1 items-start gap-4 pt-2 lg:grid-cols-[minmax(0,1fr)_248px] lg:gap-6">
        <aside className="lg:sticky lg:top-[156px] lg:order-2">{navigator}</aside>

        {/* Questions list */}
        <div className="min-w-0 space-y-4 lg:order-1">
          {questions.map((q, idx) => (
            <section
              key={q.id}
              id={`question-${idx + 1}`}
              aria-labelledby={`question-${idx + 1}-title`}
              onFocusCapture={() => setCurrentQuestion(idx)}
              className="scroll-mt-[160px] rounded-2xl border border-slate-200 bg-white p-4 shadow-hairline sm:rounded-card sm:p-6"
            >
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="text-caption font-semibold uppercase tracking-[0.6px] text-slate-500 tabular">
                  Câu {idx + 1} · {q.points} điểm
                </p>
                <Badge tone="neutral" size="sm">{QUESTION_TYPE_LABEL[q.type] ?? q.type}</Badge>
              </div>

              <p id={`question-${idx + 1}-title`} className="mt-2.5 whitespace-pre-line text-body font-semibold text-slate-900 sm:text-h3">
                {q.questionText}
              </p>

              {/* Options — radio cards */}
              {q.options && q.options.length > 0 && (
                <div role="radiogroup" aria-labelledby={`question-${idx + 1}-title`} className="mt-4 space-y-2.5">
                  {q.options.map((opt) => {
                    const checked = answers[q.id] === opt.optionKey;
                    return (
                      <label
                        key={opt.optionKey}
                        className={`grid min-h-[48px] cursor-pointer grid-cols-[auto_minmax(0,1fr)] items-center gap-3 rounded-[14px] transition-colors duration-micro ${
                          checked
                            ? 'border-2 border-blue-600 bg-tint px-[13px] py-[11px]'
                            : 'border border-slate-200 bg-white px-3.5 py-3 hover:bg-slate-50'
                        }`}
                      >
                        <input
                          type="radio"
                          name={q.id}
                          value={opt.optionKey}
                          checked={checked}
                          onChange={() => handleAnswerChange(q.id, opt.optionKey)}
                          className="h-[18px] w-[18px] flex-shrink-0 cursor-pointer appearance-none rounded-full border-[1.5px] border-slate-300 bg-white transition-[border] duration-micro checked:border-[5px] checked:border-blue-600 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-600 focus-visible:ring-offset-2"
                        />
                        <span className="min-w-0 text-ui text-slate-900">
                          {showOptionKey(opt.optionKey) && <span className="mr-1.5 font-semibold">{opt.optionKey}.</span>}
                          <span className={checked ? 'font-medium' : ''}>{opt.optionText}</span>
                        </span>
                      </label>
                    );
                  })}
                </div>
              )}

              {/* Essay textarea */}
              {q.type === 'ESSAY' && (
                <div className="mt-4">
                  <Textarea
                    aria-label={`Câu trả lời tự luận cho câu hỏi ${idx + 1}`}
                    maxLength={ESSAY_MAX_CHARS}
                    rows={5}
                    value={answers[q.id] || ''}
                    onChange={(e) => handleAnswerChange(q.id, e.target.value)}
                    placeholder="Nhập câu trả lời tự luận của bạn..."
                  />
                  <p className="mt-1.5 text-right text-caption text-slate-500 tabular">
                    {(answers[q.id] || '').length.toLocaleString('vi-VN')}/{ESSAY_MAX_CHARS.toLocaleString('vi-VN')} ký tự
                  </p>
                </div>
              )}
            </section>
          ))}

          <div className="flex flex-col items-start justify-between gap-3 rounded-2xl border border-slate-200 bg-white p-4 shadow-hairline sm:flex-row sm:items-center sm:rounded-card sm:px-6 sm:py-5">
            <div>
              <p className="text-ui font-semibold text-slate-900">
                Bạn đã trả lời <span className="tabular">{answeredCount}/{questions.length}</span> câu
              </p>
              <p className="text-meta text-slate-600">
                {unansweredCount > 0 ? `Còn ${unansweredCount} câu chưa trả lời.` : 'Bạn đã trả lời tất cả các câu.'} Bài làm được lưu tự động.
              </p>
            </div>
            <button type="button" onClick={() => setConfirmOpen(true)} disabled={submitting} className={buttonClass('secondary', 'md', 'w-full sm:w-auto')}>
              {submitting ? 'Đang nộp bài...' : 'Hoàn tất & nộp bài'}
            </button>
          </div>
        </div>
      </div>

      {confirmOpen && !submitting && (
        <Modal title="Nộp bài và kết thúc lượt làm?" role="alertdialog" size="sm" onClose={() => setConfirmOpen(false)}>
          <p className="text-ui text-slate-600">
            Bạn đã trả lời <span className="font-semibold text-slate-900 tabular">{answeredCount}/{questions.length}</span> câu.
            {unansweredCount > 0 && <> Còn <span className="font-semibold text-slate-900 tabular">{unansweredCount}</span> câu bỏ trống sẽ không được tính điểm.</>}
          </p>
          <p className="mt-2 text-meta text-slate-500">Sau khi nộp, bạn không sửa được câu trả lời của lượt này.</p>
          <div className="mt-6 flex flex-col-reverse gap-2.5 sm:flex-row sm:justify-end">
            <button type="button" onClick={() => setConfirmOpen(false)} className={buttonClass('secondary', 'md')}>
              Làm tiếp
            </button>
            <button
              type="button"
              onClick={() => {
                setConfirmOpen(false);
                void handleSubmit();
              }}
              className={buttonClass('primary', 'md')}
            >
              Xác nhận nộp bài
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
};
