import React, { useEffect, useState } from 'react';
import { useOutletContext, Link } from 'react-router-dom';
import { Classroom, Exam } from '../../types';
import { api } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { LoadingSpinner, ErrorBanner, EmptyState, StatusBadge } from '../../components/UIStates';
import { Award, Clock, AlertCircle, ArrowRight, CheckCircle2, Lock } from 'lucide-react';

type AudienceScope = Exam['audienceScope'];

// Every audience scope the backend (ExamAudiencePolicy) supports must be labelled here, so a
// restricted exam is never presented to learners as open to everyone.
const AUDIENCE_LABEL: Record<AudienceScope, string> = {
  ALL: 'Tất cả học viên',
  PRO: 'Chỉ dành cho PRO ⭐',
  COURSE: 'Theo khóa học',
  SEGMENT: 'Theo nhóm học viên',
  COURSE_SEGMENT: 'Theo khóa học & nhóm học viên',
};

const AUDIENCE_BADGE_CLASS: Record<AudienceScope, string> = {
  ALL: 'bg-indigo-100 text-indigo-800',
  PRO: 'bg-amber-100 text-amber-800',
  COURSE: 'bg-purple-100 text-purple-800',
  SEGMENT: 'bg-teal-100 text-teal-800',
  COURSE_SEGMENT: 'bg-rose-100 text-rose-800',
};

const AUDIENCE_BLOCKED_REASON: Record<AudienceScope, string> = {
  ALL: 'Chưa đủ điều kiện tham gia',
  PRO: 'Yêu cầu tài khoản PRO để tham gia',
  COURSE: 'Yêu cầu quyền truy cập khóa học liên quan',
  SEGMENT: 'Bạn không thuộc nhóm học viên được tham gia',
  COURSE_SEGMENT: 'Yêu cầu quyền truy cập khóa học và thuộc nhóm học viên được tham gia',
};

const getScheduleStatus = (exam: Exam) => {
  const now = Date.now();
  if (exam.scheduleStart && new Date(exam.scheduleStart).getTime() > now) {
    return { status: 'UPCOMING', label: 'Chưa mở', badgeClass: 'bg-amber-50 text-amber-700 border-amber-200' };
  }
  if (exam.scheduleEnd && new Date(exam.scheduleEnd).getTime() <= now) {
    return { status: 'CLOSED', label: 'Đã kết thúc', badgeClass: 'bg-slate-100 text-slate-600 border-slate-200' };
  }
  return { status: 'OPEN', label: 'Đang mở', badgeClass: 'bg-emerald-50 text-emerald-700 border-emerald-200' };
};

const blockedReason = (exam: Exam): string => {
  const sched = getScheduleStatus(exam);
  if (sched.status === 'UPCOMING') {
    const startStr = exam.scheduleStart ? new Date(exam.scheduleStart).toLocaleString('vi-VN') : '';
    return `Kỳ thi chưa mở (Bắt đầu: ${startStr})`;
  }
  if (sched.status === 'CLOSED') {
    return 'Kỳ thi đã kết thúc';
  }
  if (exam.userAttemptsCount >= exam.attemptLimit) return 'Đã hết lượt làm bài';
  return AUDIENCE_BLOCKED_REASON[exam.audienceScope] ?? AUDIENCE_BLOCKED_REASON.ALL;
};

export const ExamsTab: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const { user } = useAuth();

  const [exams, setExams] = useState<Exam[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchExams = async () => {
    try {
      setLoading(true);
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
  }, [classroom.id, user]);

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-xl font-extrabold text-slate-900 tracking-tight">Kỳ thi & Khảo sát năng lực</h2>
        <p className="text-xs text-slate-500">Tham gia làm bài để tích lũy điểm thưởng và thăng hạng trên Bảng Xếp Hạng</p>
      </div>

      {loading && <LoadingSpinner message="Đang tải danh sách kỳ thi..." />}
      {error && <ErrorBanner message={error} onRetry={fetchExams} />}

      {!loading && !error && exams.length === 0 && (
        <EmptyState
          title="Chưa có kỳ thi nào"
          description="Lớp học hiện tại chưa mở kỳ thi nào. Vui lòng quay lại sau!"
        />
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
        {exams.map((exam) => (
          <div
            key={exam.id}
            className="bg-white rounded-2xl border border-slate-200 p-6 shadow-sm flex flex-col justify-between"
          >
            <div>
              <div className="flex items-center justify-between mb-3">
                <span
                  className={`text-[10px] font-bold px-2.5 py-0.5 rounded-full uppercase tracking-wider ${AUDIENCE_BADGE_CLASS[exam.audienceScope] ?? AUDIENCE_BADGE_CLASS.ALL}`}
                >
                  {AUDIENCE_LABEL[exam.audienceScope] ?? AUDIENCE_LABEL.ALL}
                </span>

                <div className="flex items-center space-x-2">
                  <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full border ${getScheduleStatus(exam).badgeClass}`}>
                    {getScheduleStatus(exam).label}
                  </span>
                  <span className="flex items-center space-x-1 text-xs text-slate-500 font-medium">
                    <Clock className="w-3.5 h-3.5 text-slate-400" />
                    <span>{exam.durationMinutes} phút</span>
                  </span>
                </div>
              </div>

              <h3 className="text-lg font-bold text-slate-900 mb-1.5">{exam.title}</h3>
              <p className="text-xs text-slate-500 line-clamp-2 mb-2">
                {exam.description || 'Bài thi đánh giá chuẩn kiến thức.'}
              </p>

              {(exam.scheduleStart || exam.scheduleEnd) && (
                <div className="text-[11px] text-slate-500 mb-3 space-y-0.5">
                  {exam.scheduleStart && <div>Mở: {new Date(exam.scheduleStart).toLocaleString('vi-VN')}</div>}
                  {exam.scheduleEnd && <div>Đóng: {new Date(exam.scheduleEnd).toLocaleString('vi-VN')}</div>}
                </div>
              )}

              <div className="space-y-1.5 text-xs text-slate-600 mb-6 bg-slate-50 p-3 rounded-xl border border-slate-100">
                <div className="flex justify-between">
                  <span>Số câu hỏi:</span>
                  <span className="font-bold text-slate-800">{exam.questionCount ?? exam.questions?.length ?? 0} câu</span>
                </div>
                <div className="flex justify-between">
                  <span>Điểm chuẩn đạt:</span>
                  <span className="font-bold text-slate-800">{exam.passScore}/100</span>
                </div>
                <div className="flex justify-between">
                  <span>Số lượt làm bài:</span>
                  <span className="font-bold text-slate-800">
                    {exam.userAttemptsCount} / {exam.attemptLimit} lượt
                  </span>
                </div>
              </div>
            </div>

            <div className="space-y-2">
              {exam.canEnter ? (
                <Link
                  to={`/classes/${classroom.slug}/exams/${exam.id}/attempt`}
                  className="w-full inline-flex items-center justify-center space-x-2 py-2.5 px-4 bg-indigo-600 hover:bg-indigo-700 text-white font-bold text-xs rounded-xl shadow-sm transition"
                >
                  <Award className="w-4 h-4" />
                  <span>Vào thi ngay</span>
                  <ArrowRight className="w-3.5 h-3.5" />
                </Link>
              ) : (
                <div className="text-center py-2 px-3 bg-slate-100 rounded-xl text-xs font-semibold text-slate-500 flex items-center justify-center space-x-1.5">
                  <Lock className="w-3.5 h-3.5 text-slate-400" />
                  <span>{blockedReason(exam)}</span>
                </div>
              )}
              {exam.userAttemptsCount > 0 && (
                <Link
                  to={`/classes/${classroom.slug}/exams/${exam.id}/result`}
                  className="w-full inline-flex items-center justify-center space-x-2 py-2 px-4 bg-slate-100 hover:bg-slate-200 text-slate-700 font-bold text-xs rounded-xl transition"
                >
                  <Award className="w-3.5 h-3.5" />
                  <span>Xem kết quả bài thi</span>
                </Link>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};
