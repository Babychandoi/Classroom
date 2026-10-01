import React, { useEffect, useState, useRef } from 'react';
import { useParams, useNavigate, useSearchParams, Link } from 'react-router-dom';
import { ExamAttempt, Question } from '../../types';
import { api, ApiException } from '../../api/client';
import { LoadingSpinner, ErrorBanner, ForbiddenState } from '../../components/UIStates';
import { Clock, AlertTriangle, Send, Check, PlayCircle, Eye, RefreshCw } from 'lucide-react';

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
      <div className="max-w-xl mx-auto py-16">
        <div className="bg-white rounded-3xl border border-slate-200 p-8 shadow-sm text-center">
          <div className="w-14 h-14 rounded-full bg-indigo-100 text-indigo-600 mx-auto flex items-center justify-center mb-4">
            <PlayCircle className="w-7 h-7" />
          </div>
          <h2 className="text-xl font-black text-slate-900 mb-2">Sẵn sàng làm bài?</h2>
          <p className="text-xs text-slate-500 mb-6">
            Khi bắt đầu, một lượt làm bài sẽ được tính và đồng hồ đếm ngược sẽ chạy ngay lập tức.
          </p>
          <div className="flex justify-center space-x-3">
            <Link
              to={`/classes/${slug}/exams`}
              className="px-5 py-2.5 bg-slate-100 hover:bg-slate-200 text-slate-700 text-xs font-bold rounded-xl transition"
            >
              Quay lại
            </Link>
            <button
              onClick={startExam}
              className="px-5 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-bold rounded-xl shadow-sm transition inline-flex items-center space-x-1.5"
            >
              <PlayCircle className="w-4 h-4" />
              <span>Bắt đầu làm bài</span>
            </button>
          </div>
        </div>
      </div>
    );
  }

  if (error || !attempt) {
    return (
      <div className="max-w-2xl mx-auto py-12">
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

  return (
    <div className="max-w-4xl mx-auto space-y-6 pb-12">
      {isPreview && (
        <p role="status" className="rounded-xl border border-amber-300 bg-amber-50 px-4 py-2.5 text-xs font-bold text-amber-800 flex items-center gap-2">
          <Eye className="w-4 h-4" />
          Chế độ xem thử — không tính điểm/xếp hạng
        </p>
      )}
      {/* Top sticky bar with timer */}
      <div className="bg-white/90 backdrop-blur-md sticky top-20 z-30 p-4 rounded-2xl border border-slate-200 shadow-sm flex items-center justify-between">
        <div>
          <h2 className="text-sm font-bold text-slate-900">{attempt.examTitle}</h2>
          <div className="flex items-center space-x-2 text-xs text-slate-500">
            <span>Đang làm bài thi</span>
            {savedStatus && (
              <span
                className={`font-medium inline-flex items-center space-x-1 ${
                  hasUnsavedAnswer ? (retrying ? 'text-slate-600' : 'text-amber-700') : 'text-emerald-700'
                }`}
              >
                {hasUnsavedAnswer ? (
                  retrying ? <RefreshCw className="w-3 h-3 animate-spin" /> : <AlertTriangle className="w-3 h-3" />
                ) : (
                  <Check className="w-3 h-3" />
                )}
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
            onClick={() => handleSubmit()}
            disabled={submitting}
            className="px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold transition shadow-sm disabled:opacity-50 flex items-center space-x-1.5"
          >
            <Send className="w-3.5 h-3.5" />
            <span>{submitting ? 'Đang nộp...' : 'Nộp bài thi'}</span>
          </button>
        </div>
      </div>

      {retrying && (
        <p
          role="status"
          className="rounded-xl border border-slate-200 bg-slate-50 px-4 py-2 text-xs font-semibold text-slate-700"
        >
          Đang lưu lại… Kết nối tới máy chủ đang gián đoạn nên bài làm mới nhất chưa được lưu. Bạn cứ tiếp tục làm bài: hệ thống
          tự thử lại và sẽ lưu ngay khi có kết nối.
        </p>
      )}

      {hasUnsavedAnswer && !retrying && (
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
                <span className="text-xs font-medium text-slate-500">{q.type}</span>
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
                  aria-label={`Câu trả lời tự luận cho câu hỏi ${idx + 1}`}
                  maxLength={ESSAY_MAX_CHARS}
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
          onClick={() => handleSubmit()}
          disabled={submitting}
          className="px-8 py-3 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-sm font-bold shadow-md transition disabled:opacity-50"
        >
          {submitting ? 'Đang nộp bài...' : 'Hoàn tất & Nộp bài thi'}
        </button>
      </div>
    </div>
  );
};
