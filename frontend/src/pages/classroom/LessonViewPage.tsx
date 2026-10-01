import React, { useEffect, useRef, useState } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { Lesson, QuestionAnswer } from '../../types';
import { api } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { LoadingSpinner, ErrorBanner, ForbiddenState } from '../../components/UIStates';
import { CheckCircle2, Circle, ArrowLeft, MessageSquare, Send, Video, FileText, Download } from 'lucide-react';

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

  // Q&A
  const [questions, setQuestions] = useState<QuestionAnswer[]>([]);
  const [newQuestionText, setNewQuestionText] = useState('');
  const [asking, setAsking] = useState(false);

  // Answers
  const [answerInputs, setAnswerInputs] = useState<Record<string, string>>({});
  const [submissionText, setSubmissionText] = useState('');
  const [submissions, setSubmissions] = useState<Array<{ id: string; attemptNumber: number; submissionText: string; score: number | null; feedback: string | null; submittedAt: string }>>([]);
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
  const [documentUrl, setDocumentUrl] = useState<string | undefined>(undefined);
  const lastRefreshedUrlRef = useRef<string | undefined>(undefined);
  const videoRef = useRef<HTMLVideoElement | null>(null);

  useEffect(() => {
    setVideoUrl(lesson?.mediaDownloadUrl);
    setDocumentUrl(lesson?.mediaDownloadUrl);
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

  /** R9-06: the "Tải tài liệu" link fetches a fresh URL on click, the same pattern as DocumentsTab. */
  const handleDocumentDownloadClick = async (event: React.MouseEvent<HTMLAnchorElement>) => {
    event.preventDefault();
    const fresh = (await fetchFreshMediaUrl()) || documentUrl;
    if (!fresh) return;
    setDocumentUrl(fresh);
    const link = document.createElement('a');
    link.href = fresh;
    link.rel = 'noopener';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  const fetchLesson = async () => {
    if (!lessonId) return;
    try {
      setLoading(true);
      setError(null);
      setForbidden(false);

      const data = await api.get<Lesson>(`/lessons/${lessonId}`);
      setLesson(data);

      if (data.type === 'ASSIGNMENT') {
        setSubmissions(await api.get<Array<{ id: string; attemptNumber: number; submissionText: string; score: number | null; feedback: string | null; submittedAt: string }>>(`/lessons/${lessonId}/submissions/mine`));
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
  }, [lessonId]);

  const toggleCompleted = async () => {
    if (!lesson) return;
    const nextState = !lesson.completed;
    try {
      await api.put(`/lessons/${lesson.id}/progress`, { completed: nextState });
      setLesson({ ...lesson, completed: nextState });
    } catch (err: any) {
      alert(err.message || 'Không thể cập nhật tiến độ');
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

  if (loading) {
    return <LoadingSpinner message="Đang mở bài học..." />;
  }

  if (forbidden) {
    return (
      <div className="max-w-3xl mx-auto py-12">
        <ForbiddenState
          title="Nội dung bài học bị khóa"
          message="Khóa học này yêu cầu thanh toán để tiếp tục học. Vui lòng vào Cửa hàng để mở khóa quyền sử dụng."
          actionText="Đến Cửa hàng lớp học"
          onAction={() => navigate(`/classes/${slug}/store`)}
        />
      </div>
    );
  }

  if (error || !lesson) {
    return (
      <div className="max-w-3xl mx-auto py-12">
        <ErrorBanner message={error || 'Không tìm thấy bài học'} onRetry={fetchLesson} />
      </div>
    );
  }

  return (
    <div className="max-w-5xl mx-auto space-y-6">
      <Link
        to={`/classes/${slug}/learn`}
        className="inline-flex items-center space-x-1.5 text-xs font-bold text-slate-500 hover:text-indigo-600 transition"
      >
        <ArrowLeft className="w-4 h-4" />
        <span>Quay lại danh mục khóa học</span>
      </Link>

      {/* Main Lesson Card */}
      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
        <div className="p-6 border-b border-slate-100 flex flex-col md:flex-row md:items-center justify-between gap-4">
          <div>
            <span className="text-xs font-bold text-indigo-600 uppercase tracking-wider">{lesson.type}</span>
            <h1 className="text-2xl font-black text-slate-900 mt-0.5">{lesson.title}</h1>
          </div>

          <button
            onClick={toggleCompleted}
            className={`inline-flex items-center space-x-2 px-4 py-2 rounded-xl text-xs font-bold transition shadow-sm ${
              lesson.completed
                ? 'bg-emerald-50 text-emerald-700 border border-emerald-200'
                : 'bg-indigo-600 hover:bg-indigo-700 text-white'
            }`}
          >
            {lesson.completed ? (
              <>
                <CheckCircle2 className="w-4 h-4 text-emerald-600" />
                <span>Đã hoàn thành</span>
              </>
            ) : (
              <>
                <Circle className="w-4 h-4" />
                <span>Đánh dấu hoàn thành</span>
              </>
            )}
          </button>
        </div>

        {/* Video or Content Viewer */}
        <div className="p-6">
          {lesson.type === 'VIDEO' && (
            <div className="mb-6">
              {videoUrl ? (
                <div className="aspect-video bg-black rounded-xl overflow-hidden shadow-inner">
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
                    className="w-full h-full"
                    src={videoUrl}
                    onError={() => { void handleVideoError(); }}
                  >
                    {captionUrl && <track kind="captions" src={captionUrl} srcLang="vi" label="Tiếng Việt" default />}
                    Trình duyệt của bạn không hỗ trợ video HTML5.
                  </video>
                </div>
              ) : (
                <div className="aspect-video bg-slate-900 rounded-xl flex flex-col items-center justify-center text-slate-400 p-6 text-center">
                  <Video className="w-12 h-12 mb-2 text-slate-600" />
                  <p className="text-sm font-semibold">Video bài giảng đang được xử lý hoặc cập nhật link.</p>
                  <p className="text-xs text-slate-500 mt-1">Vui lòng quay lại sau ít phút.</p>
                </div>
              )}
            </div>
          )}

          {lesson.type === 'DOCUMENT' && (
            <div className="mb-6 p-6 bg-slate-50 border border-slate-200 rounded-2xl flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
              <div className="flex items-center space-x-3">
                <div className="w-12 h-12 rounded-xl bg-indigo-100 text-indigo-600 flex items-center justify-center flex-shrink-0">
                  <FileText className="w-6 h-6" />
                </div>
                <div>
                  <h3 className="font-bold text-slate-900 text-sm">{lesson.title}</h3>
                  <p className="text-xs text-slate-500">Tài liệu học tập đính kèm cho bài học</p>
                </div>
              </div>
              {documentUrl ? (
                // R8-01/R9-06: fetches a fresh presigned URL on click (same pattern as
                // DocumentsTab#handleDownload) instead of relying on the URL issued when the
                // lesson first loaded, which may have since expired if the page was left open.
                // The object was issued with response-content-disposition=attachment, so the
                // browser downloads it directly from the object store with native Range/resume
                // support, instead of this app buffering the whole file into memory as a Blob first.
                <a
                  href={documentUrl}
                  rel="noopener"
                  onClick={handleDocumentDownloadClick}
                  className="inline-flex items-center space-x-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-bold rounded-xl shadow-sm transition"
                >
                  <Download className="w-4 h-4" />
                  <span>Tải tài liệu</span>
                </a>
              ) : (
                <span className="text-xs text-slate-500 italic">Đang chuẩn bị liên kết tải...</span>
              )}
            </div>
          )}

          {lesson.contentText && (
            <div className="prose prose-slate max-w-none text-slate-700 leading-relaxed whitespace-pre-line bg-slate-50/50 p-6 rounded-xl border border-slate-100">
              {lesson.type === 'VIDEO' && <h2 className="mb-3 text-lg font-bold">Bản chép lời và mô tả video</h2>}
              {lesson.contentText}
            </div>
          )}
        </div>
      </div>

      {/* Lesson Q&A */}
      {lesson.type === 'ASSIGNMENT' && <section className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="mb-3 text-lg font-bold">Bài nộp của bạn</h2>
        <form className="space-y-3" onSubmit={async (event) => {
          event.preventDefault();
          if (!lessonId || !submissionText.trim()) return;
          setSubmitting(true);
          try {
            await api.post(`/lessons/${lessonId}/submissions`, { submissionText });
            setSubmissionText('');
            setSubmissions(await api.get<Array<{ id: string; attemptNumber: number; submissionText: string; score: number | null; feedback: string | null; submittedAt: string }>>(`/lessons/${lessonId}/submissions/mine`));
          } catch (err) { alert(err instanceof Error ? err.message : 'Nộp bài thất bại'); }
          finally { setSubmitting(false); }
        }}>
          <label className="block text-sm font-medium">Nội dung bài làm<textarea required maxLength={100000} value={submissionText} onChange={e => setSubmissionText(e.target.value)} rows={6} className="mt-1 block w-full rounded-xl border p-3" /></label>
          <button disabled={submitting} className="rounded-xl bg-indigo-600 px-4 py-2 text-sm font-bold text-white disabled:opacity-50">{submitting ? 'Đang nộp…' : 'Nộp bài'}</button>
        </form>
        <ol className="mt-5 space-y-3">{submissions.map(item => <li key={item.id} className="rounded-xl bg-slate-50 p-4">
          <p className="text-xs text-slate-500">Lần nộp {item.attemptNumber} · {new Date(item.submittedAt).toLocaleString('vi-VN')}</p>
          <p className="mt-2 whitespace-pre-wrap text-sm">{item.submissionText}</p>
          <p className="mt-2 text-sm font-semibold">{item.score == null ? 'Đang chờ chấm' : `Điểm: ${item.score}`}</p>
          {item.feedback && <p className="mt-1 text-sm text-slate-600">Phản hồi: {item.feedback}</p>}
        </li>)}</ol>
      </section>}

      {/* Lesson Q&A */}
      <div className="bg-white rounded-2xl border border-slate-200 p-6 shadow-sm">
        <div className="flex items-center space-x-2 mb-4">
          <MessageSquare className="w-5 h-5 text-indigo-600" />
          <h2 className="text-lg font-bold text-slate-900">Hỏi đáp về bài học này ({questions.length})</h2>
        </div>

        {/* Ask Question Form */}
        {user && (
          <form onSubmit={handleAsk} className="mb-6 flex space-x-2">
            <input
              type="text"
              required
              value={newQuestionText}
              onChange={(e) => setNewQuestionText(e.target.value)}
              placeholder="Bạn có thắc mắc gì về bài học này?..."
              className="flex-1 px-4 py-2.5 bg-slate-50 border border-slate-200 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
            <button
              type="submit"
              disabled={asking}
              className="px-4 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold transition disabled:opacity-50 flex items-center space-x-1.5"
            >
              <Send className="w-3.5 h-3.5" />
              <span>Gửi câu hỏi</span>
            </button>
          </form>
        )}

        {/* Questions list */}
        <div className="space-y-4">
          {questions.map((q) => (
            <div key={q.id} className="p-4 bg-slate-50/80 rounded-xl border border-slate-100">
              <div className="flex justify-between items-center mb-1">
                <span className="text-xs font-bold text-slate-900">{q.authorName}</span>
                <span className="text-[10px] text-slate-500">
                  {new Date(q.createdAt).toLocaleString('vi-VN')}
                </span>
              </div>
              <p className="text-sm text-slate-800 font-medium mb-3">{q.questionText}</p>

              {/* Answers */}
              {q.answers && q.answers.length > 0 && (
                <div className="space-y-2 mb-3 pl-3 border-l-2 border-indigo-200">
                  {q.answers.map((ans) => (
                    <div key={ans.id} className="text-xs">
                      <span className="font-bold text-indigo-900">{ans.authorName}: </span>
                      <span className="text-slate-700">{ans.answerText}</span>
                    </div>
                  ))}
                </div>
              )}

              {/* Reply box */}
              {user && (
                <div className="flex space-x-2 pt-2">
                  <input
                    type="text"
                    value={answerInputs[q.id] || ''}
                    onChange={(e) => setAnswerInputs({ ...answerInputs, [q.id]: e.target.value })}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') handleAnswer(q.id);
                    }}
                    placeholder="Viết câu trả lời..."
                    className="flex-1 px-3 py-1 bg-white border border-slate-200 rounded-lg text-xs focus:outline-none focus:ring-1 focus:ring-indigo-500"
                  />
                  <button
                    onClick={() => handleAnswer(q.id)}
                    className="px-3 py-1 bg-slate-800 text-white rounded-lg text-xs font-semibold hover:bg-indigo-600 transition"
                  >
                    Trả lời
                  </button>
                </div>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};
