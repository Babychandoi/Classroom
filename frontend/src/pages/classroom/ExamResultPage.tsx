import React, { useEffect, useState } from 'react';
import { useParams, useSearchParams, Link } from 'react-router-dom';
import { api } from '../../api/client';
import { ExamAttempt } from '../../types';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { Award, ArrowLeft, CheckCircle2, Clock, Calendar, Eye } from 'lucide-react';

export const ExamResultPage: React.FC = () => {
  const { slug, examId } = useParams<{ slug: string; examId: string }>();
  const [searchParams] = useSearchParams();
  const attemptId = searchParams.get('attemptId');
  const [attempts, setAttempts] = useState<ExamAttempt[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

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
  }, [examId]);

  if (loading) return <LoadingSpinner message="Đang tải kết quả bài thi..." />;
  if (error) {
    return (
      <div className="max-w-2xl mx-auto py-12 px-4">
        <ErrorBanner message={error} onRetry={fetchAttempts} />
      </div>
    );
  }

  if (attempts.length === 0) {
    return (
      <div className="max-w-2xl mx-auto py-12 px-4 text-center">
        <h2 className="text-xl font-bold text-slate-800 mb-2">Chưa có bài thi nào</h2>
        <p className="text-xs text-slate-500 mb-6">Bạn chưa tham gia lượt làm bài nào cho kỳ thi này.</p>
        <Link
          to={`/classes/${slug}/exams`}
          className="inline-flex items-center space-x-1.5 px-4 py-2 bg-indigo-600 text-white rounded-xl text-xs font-bold"
        >
          <ArrowLeft className="w-4 h-4" />
          <span>Quay lại danh sách kỳ thi</span>
        </Link>
      </div>
    );
  }

  // R8-02: prefer the attempt the caller just submitted (navigated here with ?attemptId=) so a
  // student who submits, then starts another attempt before this one is graded, still lands on the
  // result they just produced rather than whichever happens to be newest.
  const latestAttempt = (attemptId && attempts.find((a) => a.id === attemptId)) || attempts[0];
  const isPublished = latestAttempt.status === 'PUBLISHED';

  return (
    <div className="max-w-3xl mx-auto py-8 px-4 space-y-6">
      <Link
        to={`/classes/${slug}/exams`}
        className="inline-flex items-center space-x-1.5 text-xs text-indigo-600 hover:text-indigo-700 font-bold mb-2 transition"
      >
        <ArrowLeft className="w-3.5 h-3.5" />
        <span>Về danh mục kỳ thi</span>
      </Link>

      {latestAttempt.isPreview && (
        <p role="status" className="rounded-xl border border-amber-300 bg-amber-50 px-4 py-2.5 text-xs font-bold text-amber-800 flex items-center gap-2">
          <Eye className="w-4 h-4" />
          Chế độ xem thử — kết quả này không tính điểm/xếp hạng
        </p>
      )}

      <div className="bg-white rounded-3xl border border-slate-200 p-8 shadow-sm text-center">
        <div className="w-16 h-16 rounded-full bg-emerald-100 text-emerald-600 mx-auto flex items-center justify-center mb-4">
          <CheckCircle2 className="w-8 h-8" />
        </div>
        <h2 className="text-2xl font-black text-slate-900 mb-1">Kết quả bài thi</h2>
        <p className="text-sm text-slate-500 mb-6">{latestAttempt.examTitle || 'Kỳ thi'}</p>

        {latestAttempt.resultHidden ? (
          // R19-04: the server withheld the score of this preview attempt (the viewer may not read the answer key).
          <div role="status" className="max-w-sm mx-auto bg-slate-50 p-6 rounded-2xl border border-slate-100 mb-6">
            <div className="text-sm font-bold text-slate-700">
              {latestAttempt.notice || 'Chế độ xem thử: không hiển thị điểm/đáp án cho quyền của bạn'}
            </div>
            <div className="text-xs text-slate-500 mt-2">Bài làm của bạn đã được ghi nhận.</div>
          </div>
        ) : (
          <div className="max-w-xs mx-auto bg-slate-50 p-6 rounded-2xl border border-slate-100 mb-6">
            <div className="text-xs uppercase font-bold text-slate-500 tracking-wider mb-1">
              Điểm số đạt được
            </div>
            <div className="text-5xl font-black text-indigo-600">
              {isPublished && latestAttempt.score !== undefined ? `${latestAttempt.score}%` : 'Chờ chấm điểm'}
            </div>
            <div className="text-xs text-slate-500 mt-2">
              Trạng thái: <span className="font-bold text-slate-700">{latestAttempt.status}</span>
            </div>
          </div>
        )}

        {attempts.length > 1 && (
          <div className="text-left mt-6 pt-6 border-t border-slate-100">
            <h4 className="text-xs font-bold text-slate-700 uppercase tracking-wider mb-3">
              Lịch sử các lần làm bài ({attempts.length})
            </h4>
            <div className="space-y-2">
              {attempts.map((att, idx) => (
                <div key={att.id} className="flex justify-between items-center bg-slate-50 p-3 rounded-xl text-xs">
                  <div className="flex items-center space-x-2">
                    <span className="font-bold text-slate-700">Lần {attempts.length - idx}</span>
                    <span className="text-slate-500">· {att.status}</span>
                  </div>
                  <div className="flex items-center space-x-3">
                    <span className="font-bold text-indigo-600">
                      {att.resultHidden ? 'Không hiển thị' : att.status === 'PUBLISHED' && att.score !== undefined ? `${att.score}%` : 'Chờ chấm'}
                    </span>
                    <span className="text-slate-500 text-[10px]">
                      {att.submittedAt ? new Date(att.submittedAt).toLocaleString('vi-VN') : ''}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {isPublished && latestAttempt.answers && latestAttempt.answers.length > 0 && (
          <div className="text-left mt-6 pt-6 border-t border-slate-100">
            <h4 className="text-xs font-bold text-slate-700 uppercase tracking-wider mb-4">
              Chi tiết câu trả lời
            </h4>
            <div className="space-y-3">
              {latestAttempt.answers.map((ans, idx) => (
                <div key={ans.questionId || idx} className="bg-slate-50 p-4 rounded-xl text-xs space-y-1">
                  <div className="flex justify-between font-bold text-slate-800">
                    <span>Câu hỏi #{idx + 1}</span>
                    {ans.pointsAwarded !== undefined && ans.pointsAwarded !== null && (
                      <span className="text-indigo-600">+{ans.pointsAwarded} điểm</span>
                    )}
                  </div>
                  <div className="text-slate-600">
                    <span className="text-slate-500">Câu trả lời của bạn: </span>
                    <span className="font-medium">{ans.studentAnswer || '(Chưa trả lời)'}</span>
                  </div>
                  {ans.teacherFeedback && (
                    <div className="mt-1 text-emerald-700 bg-emerald-50 p-2 rounded-lg">
                      <span className="font-semibold">Nhận xét của giáo viên: </span>
                      {ans.teacherFeedback}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}

        <div className="flex justify-center space-x-4 mt-8">
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
};
