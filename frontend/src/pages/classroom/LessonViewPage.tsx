import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { Course, Lesson, QuestionAnswer } from '../../types';
import { api } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { SafeMarkdown } from '../../components/SafeMarkdown';
import { bundleOf, formatBytes } from '../../api/lessonComponents';
import { Avatar, Badge, ProgressBar, Textarea, buttonClass, inputClass } from '../../components/ui';
import {
  ArrowLeft, ArrowRight, Check, ChevronRight, Clock, Download, FileText, Lock, MessageSquare, PlayCircle, Send, Video,
} from 'lucide-react';

type Submission = { id: string; attemptNumber: number; submissionText: string; score: number | null; feedback: string | null; submittedAt: string };
type TabKey = 'content' | 'assignment' | 'qa' | 'docs';


const formatDateTime = (iso: string) => {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

export const LessonViewPage: React.FC = () => {
  const { slug, lessonId } = useParams<{ slug: string; lessonId: string }>();
  const { user } = useAuth();
  const navigate = useNavigate();

  const [lesson, setLesson] = useState<Lesson | null>(null);
  const [captionUrl, setCaptionUrl] = useState<string>();
  useEffect(() => {
    if (!lesson?.captionsVtt) { setCaptionUrl(undefined); return; }
    const url = URL.createObjectURL(new Blob([lesson.captionsVtt], { type: 'text/vtt' }));
    setCaptionUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [lesson?.captionsVtt]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  // The course the lesson belongs to - feeds the curriculum sidebar, "Bài x/y", the progress and "Tiếp theo trong khóa".
  // Optional: if it cannot be loaded the lesson itself still renders, just without the sidebar.
  const [course, setCourse] = useState<Course | null>(null);
  const [tab, setTab] = useState<TabKey>('content');
  const [toggling, setToggling] = useState(false);

  // Q&A
  const [questions, setQuestions] = useState<QuestionAnswer[]>([]);
  const [newQuestionText, setNewQuestionText] = useState('');
  const [asking, setAsking] = useState(false);

  // Answers
  const [answerInputs, setAnswerInputs] = useState<Record<string, string>>({});
  const [submissionText, setSubmissionText] = useState('');
  const [submissions, setSubmissions] = useState<Submission[]>([]);
  const [submitting, setSubmitting] = useState(false);
  // R8-01/R9-06: lesson.mediaDownloadUrl is a short-lived presigned MinIO URL (issued by
  // MediaService#signPresignedDownloadUrl), not a same-origin proxy path. The <video> element
  // consumes it directly - MinIO natively supports Range, so playback can seek and never has to
  // pass through the backend's async request (which previously capped out at 30s and silently
  // truncated large files).
  //
  // R9-06: on expiry mid-playback, the previous fix re-fetched the *whole lesson* (fetchLesson()),
  // which sets loading=true and unmounts the <video>/Q&A subtree entirely - playback restarted from
  // 0:00 and the Q&A list reloaded even though only the presigned URL was stale. It also only ever
  // reset its one-shot guard on lessonId change, so a *second* expiry on the same lesson (a long
  // video whose 10-minute presigned TTL elapses more than once during playback) was permanently
  // unrecoverable. Fetching only a fresh presigned URL (GET /media/{mediaAssetId}/download-url) and
  // swapping `src` in place preserves currentTime/play state; the guard now tracks the specific
  // URL that was last (re)issued so each newly-issued URL gets its own one-shot retry instead of
  // being permanently exhausted after the first refresh.
  const [videoUrl, setVideoUrl] = useState<string | undefined>(undefined);
  const [docError, setDocError] = useState<string | null>(null);
  const lastRefreshedUrlRef = useRef<string | undefined>(undefined);
  const videoRef = useRef<HTMLVideoElement | null>(null);

  useEffect(() => {
    setVideoUrl(lesson?.mediaDownloadUrl);
    lastRefreshedUrlRef.current = undefined;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lesson?.id, lesson?.mediaDownloadUrl]);

  const fetchFreshMediaUrl = async (): Promise<string | null> => {
    if (!lesson?.mediaAssetId) return null;
    try {
      const res = await api.get<{ downloadUrl: string }>(`/media/${lesson.mediaAssetId}/download-url`);
      return res.downloadUrl;
    } catch {
      return null;
    }
  };

  const handleVideoError = async () => {
    // At most one automatic retry per issued URL: if the freshly-reissued URL itself errors, stop
    // instead of looping forever against a genuinely broken asset.
    if (!videoUrl || lastRefreshedUrlRef.current === videoUrl) return;
    lastRefreshedUrlRef.current = videoUrl;
    const currentTime = videoRef.current?.currentTime ?? 0;
    const wasPlaying = videoRef.current ? !videoRef.current.paused : false;
    const fresh = await fetchFreshMediaUrl();
    if (!fresh) return;
    setVideoUrl(fresh);
    // Restore playback position/state once the new src has loaded, without unmounting the player.
    requestAnimationFrame(() => {
      const el = videoRef.current;
      if (!el) return;
      el.currentTime = currentTime;
      if (wasPlaying) el.play().catch(() => {});
    });
  };

  /** Each document downloads through a fresh presigned URL (the same pattern as DocumentsTab), so an old page never serves an expired link. */
  const downloadAttachment = async (mediaAssetId: string) => {
    setDocError(null);
    try {
      const res = await api.get<{ downloadUrl: string }>(`/media/${mediaAssetId}/download-url`);
      const link = document.createElement('a');
      link.href = res.downloadUrl;
      link.rel = 'noopener';
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
    } catch (err: any) {
      setDocError(err.message || 'Không thể tải tài liệu. Vui lòng thử lại.');
    }
  };

  const fetchLesson = async () => {
    if (!lessonId) return;
    try {
      setLoading(true);
      setError(null);
      setForbidden(false);

      const data = await api.get<Lesson>(`/lessons/${lessonId}`);
      setLesson(data);
      setTab('content');
      setDocError(null);

      // The curriculum is loaded alongside (never blocks the lesson). Reuse it when moving within the same course.
      if (data.courseId && course?.id !== data.courseId) {
        api.get<Course>(`/courses/${data.courseId}`).then(setCourse).catch(() => setCourse(null));
      }

      if (bundleOf(data).assignment) {
        setSubmissions(await api.get<Submission[]>(`/lessons/${lessonId}/submissions/mine`));
      } else {
        setSubmissions([]);
      }

      const qData = await api.get<QuestionAnswer[]>(`/lessons/${lessonId}/questions`);
      setQuestions(qData || []);
    } catch (err: any) {
      if (err.code === 'COURSE_ACCESS_REQUIRED' || err.code === 'FORBIDDEN') {
        setForbidden(true);
      } else {
        setError(err.message || 'Không thể tải bài học');
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchLesson();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lessonId]);

  // Curriculum order, with this page's own (possibly just toggled) completion state applied.
  const allLessons = useMemo(() => {
    const list = (course?.sections ?? []).flatMap((s) => s.lessons ?? []);
    return lesson ? list.map((l) => (l.id === lesson.id ? { ...l, completed: lesson.completed } : l)) : list;
  }, [course, lesson]);
  const currentIndex = lesson ? allLessons.findIndex((l) => l.id === lesson.id) : -1;
  const nextLessons = currentIndex >= 0 ? allLessons.slice(currentIndex + 1, currentIndex + 3) : [];
  const nextLesson = nextLessons[0] ?? null;
  const doneCount = allLessons.filter((l) => l.completed).length;
  const totalCount = allLessons.length || course?.totalLessons || 0;

  const lessonPath = (id: string) => `/classes/${slug}/learn/lessons/${id}`;

  /**
   * The existing progress toggle (PUT /lessons/{id}/progress {completed}), presented as the design's
   * "Hoàn thành và tiếp tục": marking a lesson done also moves on to the next lesson of the course when
   * there is one; pressing it on a completed lesson un-marks it, as before.
   */
  const toggleCompleted = async () => {
    if (!lesson || toggling) return;
    const nextState = !lesson.completed;
    setToggling(true);
    try {
      await api.put(`/lessons/${lesson.id}/progress`, { completed: nextState });
      setLesson({ ...lesson, completed: nextState });
      setCourse((old) =>
        old
          ? {
              ...old,
              sections: old.sections?.map((s) => ({
                ...s,
                lessons: s.lessons.map((l) => (l.id === lesson.id ? { ...l, completed: nextState } : l)),
              })),
            }
          : old,
      );
      if (nextState && nextLesson) navigate(lessonPath(nextLesson.id));
    } catch (err: any) {
      alert(err.message || 'Không thể cập nhật tiến độ');
    } finally {
      setToggling(false);
    }
  };

  const handleAsk = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newQuestionText.trim() || !lessonId) return;

    setAsking(true);
    try {
      await api.post(`/lessons/${lessonId}/questions`, { questionText: newQuestionText });
      setNewQuestionText('');
      const qData = await api.get<QuestionAnswer[]>(`/lessons/${lessonId}/questions`);
      setQuestions(qData || []);
    } catch (err: any) {
      alert(err.message || 'Gửi câu hỏi thất bại');
    } finally {
      setAsking(false);
    }
  };

  const handleAnswer = async (questionId: string) => {
    const text = answerInputs[questionId];
    if (!text || !text.trim()) return;

    try {
      await api.post(`/questions/${questionId}/answers`, { answerText: text });
      setAnswerInputs((prev) => ({ ...prev, [questionId]: '' }));
      const qData = await api.get<QuestionAnswer[]>(`/lessons/${lessonId}/questions`);
      setQuestions(qData || []);
    } catch (err: any) {
      alert(err.message || 'Trả lời thất bại');
    }
  };

  const handleSubmitAssignment = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!lessonId || !submissionText.trim()) return;
    setSubmitting(true);
    try {
      await api.post(`/lessons/${lessonId}/submissions`, { submissionText });
      setSubmissionText('');
      setSubmissions(await api.get<Submission[]>(`/lessons/${lessonId}/submissions/mine`));
    } catch (err) { alert(err instanceof Error ? err.message : 'Nộp bài thất bại'); }
    finally { setSubmitting(false); }
  };

  if (loading) {
    return <LoadingSpinner message="Đang mở bài học..." />;
  }

  if (forbidden) {
    // Styled like the design's locked-lesson screen (dark panel + lock + condition + one primary next step).
    return (
      <div className="mx-auto max-w-3xl">
        <Link to={`/classes/${slug}/learn`} className="mb-4 inline-flex min-h-[40px] items-center gap-2 text-ui font-medium text-slate-600 hover:text-slate-900">
          <ArrowLeft className="h-4 w-4" strokeWidth={1.8} aria-hidden="true" />
          Khóa học
        </Link>
        <section role="alert" className="overflow-hidden rounded-card border border-slate-200 bg-white shadow-hairline">
          <div className="flex aspect-[16/7] flex-col items-center justify-center gap-2.5 bg-slate-900">
            <span className="inline-flex h-[52px] w-[52px] items-center justify-center rounded-full bg-white/[0.12]" aria-hidden="true">
              <Lock className="h-5 w-5 text-white" strokeWidth={1.8} />
            </span>
            <Badge tone="paid">Trả phí</Badge>
          </div>
          <div className="px-5 pb-8 pt-7 text-center sm:px-8">
            <p className="text-caption font-semibold uppercase tracking-[0.6px] text-slate-500">Dành cho học viên đã mở khóa học</p>
            <h1 className="mt-2 text-h2-sm font-semibold text-slate-900 sm:text-[22px] sm:leading-[30px]">Nội dung bài học bị khóa</h1>
            <p className="mx-auto mt-2.5 max-w-[480px] text-body-sm text-slate-600">
              Khóa học này yêu cầu thanh toán để tiếp tục học. Vui lòng vào Cửa hàng để mở khóa quyền sử dụng.
            </p>
            <div className="mt-5 flex flex-wrap justify-center gap-2.5">
              <button type="button" onClick={() => navigate(`/classes/${slug}/store`)} className={buttonClass('primary', 'lg')}>
                Đến Cửa hàng lớp học
              </button>
              <Link to={`/classes/${slug}/learn`} className={buttonClass('secondary', 'lg')}>Xem các khóa học khác</Link>
            </div>
          </div>
        </section>
      </div>
    );
  }

  if (error || !lesson) {
    return (
      <div className="mx-auto max-w-3xl py-12">
        <ErrorBanner message={error || 'Không tìm thấy bài học'} onRetry={fetchLesson} />
      </div>
    );
  }

  const bundle = bundleOf(lesson);
  const attachments = lesson.attachments ?? [];
  const hasSubmitted = submissions.length > 0;
  const lessonNumber = currentIndex >= 0 ? currentIndex + 1 : null;
  const eyebrow = [
    lessonNumber && totalCount ? `Bài ${lessonNumber}/${totalCount}` : null,
    lesson.durationMinutes > 0 ? `${lesson.durationMinutes} phút` : null,
  ].filter(Boolean).join(' · ');
  // A single blue per viewport: while an assignment is still unsubmitted, its "Nộp bài" is the main action.
  const completeVariant = lesson.completed ? 'done' : bundle.assignment && !hasSubmitted ? 'secondary' : 'primary';
  const completeNote = lesson.completed
    ? 'Bấm lần nữa để bỏ đánh dấu'
    : bundle.assignment && !hasSubmitted
      ? 'Nên nộp bài tập trước khi đánh dấu hoàn thành'
      : nextLesson
        ? `Tiếp theo: ${nextLesson.title}`
        : null;

  // Tabs exist only for the components the lesson has (plus the content tab when nothing else would be shown), then Hỏi đáp.
  const tabs: { key: TabKey; label: React.ReactNode }[] = [
    ...(bundle.content || (attachments.length === 0 && !bundle.assignment) ? [{ key: 'content' as TabKey, label: 'Nội dung' }] : []),
    ...(attachments.length > 0 ? [{ key: 'docs' as TabKey, label: <>Tài liệu<span className="ml-1.5 tabular text-slate-500">({attachments.length})</span></> }] : []),
    ...(bundle.assignment
      ? [{
          key: 'assignment' as TabKey,
          label: (
            <>
              Bài tập
              {!hasSubmitted && <span aria-label="(chưa nộp)" className="ml-1 inline-block h-1.5 w-1.5 -translate-y-2 rounded-full bg-amber-500" />}
            </>
          ),
        }]
      : []),
    { key: 'qa', label: <>Hỏi đáp<span className="ml-1.5 tabular text-slate-500">{questions.length}</span></> },
  ];
  const activeTab: TabKey = tabs.some((t) => t.key === tab) ? tab : tabs[0].key;

  const onTabKeyDown = (event: React.KeyboardEvent<HTMLButtonElement>) => {
    if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return;
    event.preventDefault();
    const index = tabs.findIndex((t) => t.key === activeTab);
    const next = tabs[(index + (event.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
    setTab(next.key);
    document.getElementById(`lesson-tab-${next.key}`)?.focus();
  };

  return (
    <div className="space-y-5">
      {/* Orientation: back to the course list + where you are in the course. */}
      <div className="flex flex-wrap items-center gap-x-3.5 gap-y-2">
        <Link
          to={`/classes/${slug}/learn`}
          className="inline-flex min-h-[40px] min-w-0 items-center gap-2 text-ui font-medium text-slate-600 transition-colors duration-micro hover:text-slate-900"
        >
          <ArrowLeft className="h-4 w-4 flex-shrink-0" strokeWidth={1.8} aria-hidden="true" />
          <span className="truncate">{course?.title ?? 'Khóa học'}</span>
        </Link>
        {lessonNumber && totalCount > 0 && (
          <>
            <span aria-hidden="true" className="hidden h-5 w-px bg-slate-200 sm:block" />
            <span className="text-meta text-slate-500 tabular">Bài {lessonNumber}/{totalCount}</span>
          </>
        )}
        {totalCount > 0 && (
          <div className="ml-auto flex items-center gap-2.5">
            <div className="w-24"><ProgressBar value={doneCount} max={totalCount} label="Tiến độ khóa học" className="!h-1 bg-slate-200" /></div>
            <span className="text-meta text-slate-600 tabular">{doneCount}/{totalCount}</span>
          </div>
        )}
      </div>

      <div className={course ? 'grid grid-cols-1 items-start gap-6 lg:grid-cols-[320px_minmax(0,1fr)] lg:gap-7' : ''}>
        {course && (
          <CurriculumSidebar course={course} lessons={allLessons} currentId={lesson.id} lessonPath={lessonPath} />
        )}

        <article className="min-w-0 space-y-6 lg:order-2" aria-labelledby="lesson-title">
          {bundle.video && (
            <section aria-label="Video bài học" className="overflow-hidden rounded-2xl bg-slate-900 shadow-lift sm:rounded-card">
              {lesson.embedUrl ? (
                <div>
                  <div className="aspect-video bg-black">
                    <iframe
                      title={`Video bài học: ${lesson.title}`}
                      src={lesson.embedUrl}
                      className="h-full w-full border-0"
                      allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
                      allowFullScreen
                      referrerPolicy="strict-origin-when-cross-origin"
                      sandbox="allow-scripts allow-same-origin allow-presentation allow-popups"
                    />
                  </div>
                  {lesson.videoUrl && (
                    <p className="bg-slate-900 px-4 py-2.5 text-meta text-slate-300">
                      Video {lesson.videoProvider === 'GOOGLE_DRIVE' ? 'Google Drive' : 'YouTube'}.{' '}
                      <a href={lesson.videoUrl} target="_blank" rel="noopener noreferrer" className="font-semibold text-white underline underline-offset-2">Mở trong tab mới</a>
                      <span className="sr-only"> (mở cửa sổ mới)</span>
                    </p>
                  )}
                </div>
              ) : videoUrl ? (
                <div className="aspect-video bg-black">
                  {/* R8-01/R9-06: src is a short-lived presigned MinIO URL, so the browser streams
                      and seeks directly against the object store (Range support included) instead
                      of buffering the whole file through this app first. onError covers playback
                      starting (or resuming, e.g. a long video outliving the URL's TTL more than
                      once) after the current URL has expired: fetch only a fresh presigned URL and
                      swap src in place, preserving playback position instead of remounting the
                      player via a full lesson refetch. */}
                  <video
                    ref={videoRef}
                    controls
                    className="h-full w-full"
                    src={videoUrl}
                    onError={() => { void handleVideoError(); }}
                  >
                    {captionUrl && <track kind="captions" src={captionUrl} srcLang="vi" label="Tiếng Việt" default />}
                    Trình duyệt của bạn không hỗ trợ video HTML5.
                  </video>
                </div>
              ) : (
                <div className="flex aspect-video flex-col items-center justify-center p-6 text-center">
                  <span className="mb-3 inline-flex h-14 w-14 items-center justify-center rounded-full bg-white/[0.12]" aria-hidden="true">
                    <Video className="h-6 w-6 text-white" strokeWidth={1.6} />
                  </span>
                  <p className="text-ui font-semibold text-white">Video bài giảng đang được xử lý hoặc cập nhật link.</p>
                  <p className="mt-1 text-meta text-slate-400">Vui lòng quay lại sau ít phút.</p>
                </div>
              )}
            </section>
          )}

          {/* Title + the one primary action of the page. */}
          <section className="flex flex-wrap items-end justify-between gap-x-6 gap-y-4">
            <div className="min-w-0 flex-1 sm:min-w-[280px]">
              <p className="text-caption font-semibold uppercase tracking-[0.6px] text-slate-500 tabular">{eyebrow}</p>
              <h1 id="lesson-title" className="mt-1.5 text-h2-sm font-semibold text-slate-900 sm:text-h2">{lesson.title}</h1>
            </div>
            <div className="flex w-full flex-col items-stretch gap-1.5 sm:w-auto sm:items-end">
              <button
                type="button"
                onClick={toggleCompleted}
                disabled={toggling}
                aria-pressed={lesson.completed}
                className={buttonClass(completeVariant, 'lg', 'w-full sm:w-auto')}
              >
                {lesson.completed ? (
                  <>
                    <Check className="h-4 w-4 text-green-600" strokeWidth={2.6} aria-hidden="true" />
                    <span>Đã hoàn thành</span>
                  </>
                ) : (
                  <>
                    <span>{nextLesson ? 'Hoàn thành và tiếp tục' : 'Đánh dấu hoàn thành'}</span>
                    <ArrowRight className="h-4 w-4" strokeWidth={2} aria-hidden="true" />
                  </>
                )}
              </button>
              {completeNote && <p className="truncate text-caption text-slate-500 sm:max-w-[320px] sm:text-right">{completeNote}</p>}
            </div>
          </section>

          {/* Tabs: Nội dung · Bài tập · Hỏi đáp · Tài liệu */}
          <div>
            <div role="tablist" aria-label="Nội dung bài học" className="-mx-4 flex gap-6 overflow-x-auto border-b border-slate-200 px-4 scrollbar-none sm:mx-0 sm:gap-[26px] sm:px-0">
              {tabs.map((t) => (
                <button
                  key={t.key}
                  id={`lesson-tab-${t.key}`}
                  type="button"
                  role="tab"
                  aria-selected={activeTab === t.key}
                  aria-controls={`lesson-panel-${t.key}`}
                  tabIndex={activeTab === t.key ? 0 : -1}
                  onClick={() => setTab(t.key)}
                  onKeyDown={onTabKeyDown}
                  className={`-mb-px inline-flex min-h-[44px] flex-shrink-0 items-center whitespace-nowrap border-b-2 px-0.5 pb-3 pt-2 text-ui transition-colors duration-micro ${
                    activeTab === t.key ? 'border-blue-600 font-semibold text-blue-600' : 'border-transparent font-medium text-slate-600 hover:text-slate-900'
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </div>

            <div id={`lesson-panel-${activeTab}`} role="tabpanel" aria-labelledby={`lesson-tab-${activeTab}`} className="mt-6">
              {activeTab === 'content' && (
                <section className="rounded-2xl border border-slate-200 bg-white px-5 py-6 shadow-hairline sm:rounded-card sm:px-10 sm:py-9">
                  <div className="mx-auto max-w-[680px]">
                    {lesson.contentText ? (
                      <>
                        {bundle.video && <h2 className="mb-3 text-[19px] font-semibold leading-[27px] tracking-[-0.2px] text-slate-900">Bản chép lời và mô tả video</h2>}
                        <SafeMarkdown source={lesson.contentText} size="body" />
                      </>
                    ) : (
                      <p className="text-ui text-slate-500">
                        Bài học này chưa có nội dung văn bản.
                        {bundle.video ? ' Hãy xem video phía trên, có thắc mắc thì hỏi ngay ở tab Hỏi đáp.' : ' Có thắc mắc thì hỏi ngay ở tab Hỏi đáp.'}
                      </p>
                    )}
                  </div>
                </section>
              )}

              {activeTab === 'assignment' && bundle.assignment && (
                <section className="space-y-5">
                  {lesson.assignmentInstructions && (
                    <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-hairline sm:rounded-card sm:p-7">
                      <h2 className="mb-3 text-h3 font-semibold text-slate-900">Yêu cầu bài tập</h2>
                      <SafeMarkdown source={lesson.assignmentInstructions} size="body" />
                    </div>
                  )}
                  <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-hairline sm:rounded-card sm:p-7">
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <h2 className="text-h3 font-semibold text-slate-900">Bài nộp của bạn</h2>
                      {!hasSubmitted ? (
                        <Badge tone="neutral">Chưa nộp</Badge>
                      ) : submissions[0].score == null ? (
                        <Badge tone="warn">Đã nộp — chờ chấm</Badge>
                      ) : (
                        <Badge tone="success">Đã chấm</Badge>
                      )}
                    </div>
                    <form className="mt-4 space-y-3" onSubmit={handleSubmitAssignment}>
                      <label className="block text-meta font-semibold text-slate-900">
                        Nội dung bài làm
                        <Textarea
                          required
                          maxLength={100000}
                          value={submissionText}
                          onChange={(e) => setSubmissionText(e.target.value)}
                          rows={6}
                          className="mt-1.5 font-normal"
                        />
                      </label>
                      <div className="flex flex-wrap items-center gap-3">
                        <button type="submit" disabled={submitting} className={buttonClass(hasSubmitted ? 'secondary' : 'primary', 'md')}>
                          {submitting ? 'Đang nộp…' : hasSubmitted ? 'Nộp lại' : 'Nộp bài'}
                        </button>
                        <p className="text-caption text-slate-500">Chỉ người dẫn dắt lớp và bạn thấy bài nộp.</p>
                      </div>
                    </form>
                  </div>

                  {hasSubmitted && (
                    <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-hairline sm:rounded-card sm:px-7 sm:py-6">
                      <p className="mb-3.5 text-meta font-semibold text-slate-600">Các lần nộp</p>
                      <ol className="space-y-3">
                        {submissions.map((item) => (
                          <li key={item.id} className="rounded-2xl bg-slate-50 px-4 py-3.5">
                            <p className="text-caption text-slate-500 tabular">Lần nộp {item.attemptNumber} · {formatDateTime(item.submittedAt)}</p>
                            <p className="mt-2 whitespace-pre-wrap text-ui text-slate-900">{item.submissionText}</p>
                            <p className="mt-2 inline-flex items-center gap-1.5 text-ui font-semibold text-slate-900">
                              {item.score == null ? (
                                <><Clock className="h-4 w-4 text-amber-500" strokeWidth={1.8} aria-hidden="true" /> Đang chờ chấm</>
                              ) : (
                                <><Check className="h-4 w-4 text-green-600" strokeWidth={2.4} aria-hidden="true" /> <span className="tabular">Điểm: {item.score}</span></>
                              )}
                            </p>
                            {item.feedback && (
                              <div className="mt-2.5 rounded-2xl bg-slate-100 px-4 py-3">
                                <p className="text-caption font-semibold text-slate-600">Phản hồi</p>
                                <p className="mt-1 text-ui text-slate-900">{item.feedback}</p>
                              </div>
                            )}
                          </li>
                        ))}
                      </ol>
                    </div>
                  )}
                </section>
              )}

              {activeTab === 'qa' && (
                <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-hairline sm:rounded-card sm:p-7">
                  <div className="flex items-center gap-3">
                    <span className="inline-flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-full bg-tint text-blue-600" aria-hidden="true">
                      <MessageSquare className="h-5 w-5" strokeWidth={1.75} />
                    </span>
                    <div className="min-w-0">
                      <h2 className="text-body font-semibold text-slate-900">
                        Hỏi đáp về bài học này (<span className="tabular">{questions.length}</span>)
                      </h2>
                      <p className="text-meta text-slate-600">Câu hỏi tự gắn kèm bài này để mọi người cùng xem.</p>
                    </div>
                  </div>

                  {/* Ask Question Form */}
                  {user && (
                    <form onSubmit={handleAsk} className="mt-4 flex items-center gap-2.5">
                      <Avatar name={user.fullName} src={user.avatarUrl} size={32} className="hidden sm:inline-flex" />
                      <input
                        type="text"
                        required
                        aria-label="Đặt câu hỏi về bài này"
                        value={newQuestionText}
                        onChange={(e) => setNewQuestionText(e.target.value)}
                        placeholder="Bạn có thắc mắc gì về bài học này?..."
                        className={inputClass('h-11 flex-1 rounded-full border-transparent bg-slate-100 px-4 hover:border-transparent focus:bg-white')}
                      />
                      <button type="submit" disabled={asking} className={buttonClass('secondary', 'md', 'h-11 rounded-full')}>
                        <Send className="h-3.5 w-3.5" strokeWidth={1.9} aria-hidden="true" />
                        <span>Gửi câu hỏi</span>
                      </button>
                    </form>
                  )}

                  {questions.length === 0 ? (
                    <p className="mt-5 border-t border-slate-100 pt-5 text-ui text-slate-500">
                      Chưa có câu hỏi nào. Vướng ở đâu, bạn cứ hỏi — câu hỏi của bạn cũng giúp người học sau.
                    </p>
                  ) : (
                    <ul className="mt-5 divide-y divide-slate-100 border-t border-slate-100">
                      {questions.map((q) => (
                        <li key={q.id} className="py-4">
                          <div className="flex items-start gap-3">
                            <Avatar name={q.authorName} size={32} />
                            <div className="min-w-0 flex-1">
                              <p className="text-caption text-slate-500">
                                <span className="font-semibold text-slate-900">{q.authorName}</span> · <span className="tabular">{formatDateTime(q.createdAt)}</span>
                              </p>
                              <p className="mt-0.5 text-ui font-medium text-slate-900">{q.questionText}</p>

                              {/* Answers */}
                              {q.answers && q.answers.length > 0 && (
                                <div className="mt-2.5 space-y-2 rounded-2xl bg-slate-50 px-4 py-3">
                                  {q.answers.map((ans) => (
                                    <p key={ans.id} className="text-meta text-slate-600">
                                      <span className="font-semibold text-slate-900">{ans.authorName}: </span>
                                      {ans.answerText}
                                    </p>
                                  ))}
                                </div>
                              )}

                              {/* Reply box */}
                              {user && (
                                <div className="mt-2.5 flex gap-2">
                                  <input
                                    type="text"
                                    aria-label={`Trả lời câu hỏi của ${q.authorName}`}
                                    value={answerInputs[q.id] || ''}
                                    onChange={(e) => setAnswerInputs({ ...answerInputs, [q.id]: e.target.value })}
                                    onKeyDown={(e) => {
                                      if (e.key === 'Enter') handleAnswer(q.id);
                                    }}
                                    placeholder="Viết câu trả lời..."
                                    className={inputClass('h-10 flex-1 text-meta')}
                                  />
                                  <button type="button" onClick={() => handleAnswer(q.id)} className={buttonClass('ghost', 'md')}>
                                    Trả lời
                                  </button>
                                </div>
                              )}
                            </div>
                          </div>
                        </li>
                      ))}
                    </ul>
                  )}
                </section>
              )}

              {activeTab === 'docs' && (
                <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-hairline sm:rounded-card sm:p-7">
                  <h2 className="mb-3 text-body font-semibold text-slate-900">Tài liệu của bài học ({attachments.length})</h2>
                  {docError && <p role="alert" className="mb-3 text-meta font-medium text-red-600">{docError}</p>}
                  <ul className="divide-y divide-slate-100 rounded-2xl border border-slate-200">
                    {attachments.map((att) => (
                      <li key={att.id} className="flex flex-col items-start justify-between gap-3 px-4 py-3 sm:flex-row sm:items-center">
                        <div className="flex min-w-0 items-center gap-3">
                          <span className="inline-flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-btn bg-red-100 text-red-800" aria-hidden="true">
                            <FileText className="h-[17px] w-[17px]" strokeWidth={1.6} />
                          </span>
                          <span className="min-w-0">
                            <span className="block truncate text-ui font-semibold text-slate-900">{att.title}</span>
                            {formatBytes(att.sizeBytes) && <span className="block text-caption text-slate-500 tabular">{formatBytes(att.sizeBytes)}</span>}
                          </span>
                        </div>
                        <button type="button" onClick={() => void downloadAttachment(att.mediaAssetId)} aria-label={`Tải tài liệu ${att.title}`} className={buttonClass('secondary', 'md')}>
                          <Download className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                          <span>Tải tài liệu</span>
                        </button>
                      </li>
                    ))}
                  </ul>
                  <div className="mt-4 border-t border-slate-100 pt-4">
                    <Link to={`/classes/${slug}/documents`} className="text-ui font-medium text-blue-600 hover:text-blue-700">
                      Xem tài liệu chung của lớp
                    </Link>
                  </div>
                </section>
              )}
            </div>
          </div>

          {/* Tiếp theo trong khóa */}
          {nextLessons.length > 0 && (
            <section aria-labelledby="next-in-course" className="rounded-2xl border border-slate-200 bg-white shadow-hairline sm:rounded-card">
              <h2 id="next-in-course" className="px-5 pb-2 pt-4 text-meta font-semibold text-slate-600 sm:px-7 sm:pt-5">Tiếp theo trong khóa</h2>
              <ul className="pb-2">
                {nextLessons.map((next) => (
                  <li key={next.id}>
                    <Link
                      to={lessonPath(next.id)}
                      className="grid min-h-[52px] grid-cols-[32px_minmax(0,1fr)_auto] items-center gap-3 px-5 py-2 transition-colors duration-micro hover:bg-slate-50 sm:px-7"
                    >
                      <span className="inline-flex h-8 w-8 items-center justify-center rounded-thumb bg-slate-100 text-slate-600" aria-hidden="true">
                        {bundleOf(next).video ? <PlayCircle className="h-4 w-4" strokeWidth={1.75} /> : <FileText className="h-4 w-4" strokeWidth={1.75} />}
                      </span>
                      <span className="min-w-0">
                        <span className="block truncate text-ui font-semibold text-slate-900">{next.title}</span>
                        <span className="block text-caption text-slate-500 tabular">
                          Bài {allLessons.indexOf(next) + 1}{next.durationMinutes > 0 ? ` · ${next.durationMinutes} phút` : ''}
                        </span>
                      </span>
                      <ChevronRight className="h-4 w-4 text-slate-400" strokeWidth={1.75} aria-hidden="true" />
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </article>
      </div>
    </div>
  );
};

// ---------------------------------------------------------------------------------------------------------------

const CurriculumSidebar: React.FC<{
  course: Course;
  lessons: Lesson[];
  currentId: string;
  lessonPath: (id: string) => string;
}> = ({ course, lessons, currentId, lessonPath }) => {
  const sections = course.sections ?? [];
  const completedById = new Map(lessons.map((l) => [l.id, l.completed]));
  const numberById = new Map(lessons.map((l, i) => [l.id, i + 1]));
  return (
    <aside
      aria-label="Chương trình khóa học"
      className="order-2 lg:sticky lg:top-20 lg:order-1 lg:max-h-[calc(100vh-96px)] lg:overflow-y-auto lg:scrollbar-none"
    >
      <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-hairline">
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-[18px] py-3.5">
          <div className="min-w-0">
            <p className="truncate text-meta font-semibold text-slate-900">{course.title}</p>
            <p className="mt-px text-caption text-slate-500 tabular">{sections.length} chương · {lessons.length} bài</p>
          </div>
          <Badge tone={course.accessMode === 'FREE' ? 'free' : 'paid'} size="xs">{course.accessMode === 'FREE' ? 'Free' : 'Trả phí'}</Badge>
        </div>

        {sections.map((section, sectionIndex) => {
          const done = section.lessons.filter((l) => completedById.get(l.id)).length;
          return (
            <div key={section.id} className={sectionIndex > 0 ? 'mt-2 border-t border-slate-100' : ''}>
              <div className="px-[18px] pb-2 pt-4">
                <p className="text-meta font-semibold text-slate-900">{section.title}</p>
                <p className="mt-px text-caption text-slate-500 tabular">{done}/{section.lessons.length} hoàn thành</p>
              </div>
              <ul className={sectionIndex === sections.length - 1 ? 'pb-3' : ''}>
                {section.lessons.map((l) => {
                  const isCurrent = l.id === currentId;
                  const isDone = completedById.get(l.id);
                  const number = numberById.get(l.id);
                  const status = isDone ? (
                    <span className="inline-flex h-[18px] w-[18px] items-center justify-center rounded-full bg-green-100" aria-hidden="true">
                      <Check className="h-2.5 w-2.5 text-green-600" strokeWidth={3} />
                    </span>
                  ) : isCurrent ? (
                    <span className="inline-flex h-[18px] w-[18px] items-center justify-center rounded-full bg-blue-600 text-micro-xs font-bold text-white tabular" aria-hidden="true">{number}</span>
                  ) : !course.canLearn ? (
                    <span className="inline-flex h-[18px] w-[18px] items-center justify-center rounded-full bg-slate-100" aria-hidden="true">
                      <Lock className="h-2.5 w-2.5 text-slate-400" strokeWidth={2.4} />
                    </span>
                  ) : (
                    <span className="inline-flex h-[18px] w-[18px] items-center justify-center rounded-full border-[1.5px] border-slate-200 text-micro-xs font-bold text-slate-500 tabular" aria-hidden="true">{number}</span>
                  );
                  const srState = isDone ? 'Đã hoàn thành' : isCurrent ? 'Đang học' : !course.canLearn ? 'Đang khóa' : 'Chưa học';
                  // Seeded titles often already start with "Bài 3:" - do not number them twice.
                  const title = /^bài\s*\d+/i.test(l.title.trim()) ? l.title : `${number}. ${l.title}`;
                  if (isCurrent) {
                    return (
                      <li key={l.id}>
                        <div aria-current="true" className="grid min-h-[44px] grid-cols-[22px_minmax(0,1fr)] items-center gap-2.5 border-l-[3px] border-blue-600 bg-tint py-1.5 pl-[15px] pr-[18px]">
                          {status}
                          <span className="min-w-0">
                            <span className="block truncate text-meta font-semibold text-slate-900">{title}</span>
                            <span className="block text-caption font-medium text-blue-600">
                              Đang học{bundleOf(l).assignment ? ' · có bài tập' : ''}
                            </span>
                          </span>
                        </div>
                      </li>
                    );
                  }
                  return (
                    <li key={l.id}>
                      <Link
                        to={lessonPath(l.id)}
                        className="grid min-h-[40px] grid-cols-[22px_minmax(0,1fr)] items-center gap-2.5 px-[18px] py-1.5 transition-colors duration-micro hover:bg-slate-50"
                      >
                        {status}
                        <span className={`min-w-0 truncate text-meta font-medium ${isDone ? 'text-slate-500' : 'text-slate-600'}`}>
                          <span className="sr-only">{srState}: </span>
                          {title}
                        </span>
                      </Link>
                    </li>
                  );
                })}
              </ul>
            </div>
          );
        })}
      </div>
    </aside>
  );
};
