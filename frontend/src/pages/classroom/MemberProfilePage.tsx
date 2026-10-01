import React, { useEffect, useState } from 'react';
import { useParams, Link, useOutletContext } from 'react-router-dom';
import { Classroom, MemberProfile, UserJourney } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner, StatusBadge } from '../../components/UIStates';
import { ArrowLeft, Award, BookOpen, ShieldCheck, Sparkles, Mail, Calendar, UserCheck, GraduationCap, FileCheck2 } from 'lucide-react';

export const MemberProfilePage: React.FC = () => {
  const { userId } = useParams<{ userId: string }>();
  const { classroom } = useOutletContext<{ classroom: Classroom }>();

  const [profile, setProfile] = useState<MemberProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // R13-05 (FR-12): learning/exam journey — a separate request so a viewer who cannot see the
  // journey (private learner, per ProfileVisibilityPolicy) still sees the rest of the profile
  // instead of the whole page failing.
  const [journey, setJourney] = useState<UserJourney | null>(null);
  const [journeyLoading, setJourneyLoading] = useState(true);
  const [journeyError, setJourneyError] = useState<string | null>(null);

  const fetchProfile = async () => {
    if (!userId || !classroom.id) return;
    try {
      setLoading(true);
      setError(null);
      const data = await api.get<MemberProfile>(`/classes/${classroom.id}/members/${userId}/profile`);
      setProfile(data);
    } catch (err: any) {
      setError(err.message || 'Không thể tải hồ sơ thành viên');
    } finally {
      setLoading(false);
    }
  };

  const fetchJourney = async () => {
    if (!userId || !classroom.id) return;
    try {
      setJourneyLoading(true);
      setJourneyError(null);
      const data = await api.get<UserJourney>(`/users/${userId}/journey?classId=${classroom.id}`);
      setJourney(data);
    } catch (err: any) {
      setJourneyError(err.message || 'Không thể tải hành trình học tập');
    } finally {
      setJourneyLoading(false);
    }
  };

  useEffect(() => {
    fetchProfile();
    fetchJourney();
  }, [classroom.id, userId]);

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      <Link
        to={`/classes/${classroom.slug}/members`}
        className="inline-flex items-center space-x-2 text-sm font-semibold text-slate-600 hover:text-indigo-600 transition"
      >
        <ArrowLeft className="w-4 h-4" />
        <span>Danh sách thành viên</span>
      </Link>

      {loading && <LoadingSpinner message="Đang tải hồ sơ thành viên..." />}
      {error && <ErrorBanner message={error} onRetry={fetchProfile} />}

      {!loading && profile && (
        <div className="space-y-6">
          {/* Header Card */}
          <div className="bg-white rounded-2xl border border-slate-200 p-6 shadow-sm">
            <div className="flex flex-col sm:flex-row items-start sm:items-center space-y-4 sm:space-y-0 sm:space-x-5">
              <div className="w-20 h-20 rounded-2xl bg-gradient-to-tr from-indigo-500 to-purple-600 flex items-center justify-center text-white text-2xl font-black shadow-md flex-shrink-0">
                {profile.avatarUrl ? (
                  <img
                    src={profile.avatarUrl}
                    alt={profile.fullName}
                    className="w-full h-full object-cover rounded-2xl"
                  />
                ) : (
                  profile.fullName?.charAt(0)?.toUpperCase() || 'U'
                )}
              </div>

              <div className="flex-1 space-y-2">
                <div className="flex flex-wrap items-center gap-2">
                  <h1 className="text-2xl font-black text-slate-900 tracking-tight">{profile.fullName}</h1>
                  <StatusBadge status={profile.membershipRole || 'STUDENT'} />
                  {profile.isPro && (
                    <span className="inline-flex items-center space-x-1 px-2.5 py-1 rounded-full text-xs font-black bg-amber-50 text-amber-700 border border-amber-200">
                      <Sparkles className="w-3 h-3" />
                      <span>PRO</span>
                    </span>
                  )}
                </div>

                <p className="text-xs text-slate-500 font-mono">Mã thành viên: {profile.id}</p>

                {profile.bio && <p className="text-sm text-slate-600">{profile.bio}</p>}

                <div className="pt-2 flex flex-wrap gap-4 text-xs text-slate-500">
                  {profile.email ? (
                    <div className="flex items-center space-x-1 text-slate-700">
                      <Mail className="w-3.5 h-3.5 text-indigo-500" />
                      <span>{profile.email}</span>
                    </div>
                  ) : (
                    <div className="flex items-center space-x-1 text-slate-500 italic">
                      <Mail className="w-3.5 h-3.5 text-slate-500" />
                      <span>Email được bảo vệ quyền riêng tư</span>
                    </div>
                  )}

                  {profile.createdAt && (
                    <div className="flex items-center space-x-1 text-slate-500">
                      <Calendar className="w-3.5 h-3.5" />
                      <span>Gia nhập: {new Date(profile.createdAt).toLocaleDateString('vi-VN')}</span>
                    </div>
                  )}
                </div>
              </div>
            </div>
          </div>

          {/* Learning & Exam Journey (FR-12) */}
          <div className="space-y-4">
            <h2 className="text-lg font-extrabold text-slate-900 tracking-tight flex items-center space-x-2">
              <Award className="w-5 h-5 text-indigo-600" />
              <span>Hành trình học tập & thành tích</span>
            </h2>

            <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
              <div className="bg-white rounded-xl border border-slate-200 p-5 shadow-sm space-y-1">
                <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Điểm tích lũy</span>
                <p className="text-2xl font-black text-indigo-600">{profile.totalPoints ?? 0}</p>
                <p className="text-xs text-slate-500">Từ các kỳ thi đã công bố kết quả</p>
              </div>

              <div className="bg-white rounded-xl border border-slate-200 p-5 shadow-sm space-y-1">
                <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Hạng thành tích</span>
                <p className="text-2xl font-black text-slate-800">{profile.rankTier || 'Chưa xếp hạng'}</p>
                <p className="text-xs text-slate-500">Xác định theo thang điểm lớp học</p>
              </div>

              <div className="bg-white rounded-xl border border-slate-200 p-5 shadow-sm space-y-1">
                <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Vai trò trong lớp</span>
                <div className="flex items-center space-x-2 pt-1">
                  <UserCheck className="w-5 h-5 text-emerald-600" />
                  <span className="text-base font-bold text-slate-800">
                    {profile.membershipRole === 'OWNER'
                      ? 'Giáo viên chủ nhiệm'
                      : profile.membershipRole === 'STAFF'
                      ? 'Trợ giảng'
                      : profile.isPro
                      ? 'Học viên PRO'
                      : 'Học viên'}
                  </span>
                </div>
                <p className="text-xs text-slate-500">{classroom.title}</p>
              </div>
            </div>
          </div>

          {/* R13-05 (FR-12/D-05): per-course progress + published exam results in this class */}
          <div className="space-y-4">
            {journeyLoading && <LoadingSpinner message="Đang tải hành trình học tập..." />}
            {journeyError && <ErrorBanner message={journeyError} onRetry={fetchJourney} />}

            {!journeyLoading && !journeyError && journey && (
              <>
                <div className="bg-white rounded-xl border border-slate-200 p-5 shadow-sm space-y-3">
                  <h3 className="text-sm font-extrabold text-slate-800 flex items-center space-x-2">
                    <GraduationCap className="w-4 h-4 text-indigo-600" />
                    <span>Tiến độ khóa học</span>
                  </h3>
                  {journey.courses.length === 0 ? (
                    <p className="text-xs text-slate-500">Chưa có khóa học nào có thể theo dõi tiến độ.</p>
                  ) : (
                    <div className="space-y-3">
                      {journey.courses.map((c) => {
                        const pct = c.totalLessons > 0 ? Math.round((c.completedLessons / c.totalLessons) * 100) : 0;
                        return (
                          <div key={c.courseId}>
                            <div className="flex justify-between items-center text-xs font-semibold text-slate-600 mb-1">
                              <span>{c.courseTitle}</span>
                              <span>{c.completedLessons}/{c.totalLessons} bài ({pct}%)</span>
                            </div>
                            <div className="w-full h-1.5 bg-slate-100 rounded-full overflow-hidden">
                              <div className="h-full bg-indigo-600 rounded-full" style={{ width: `${pct}%` }} />
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>

                <div className="bg-white rounded-xl border border-slate-200 p-5 shadow-sm space-y-3">
                  <h3 className="text-sm font-extrabold text-slate-800 flex items-center space-x-2">
                    <FileCheck2 className="w-4 h-4 text-indigo-600" />
                    <span>Kết quả thi đã công bố</span>
                  </h3>
                  {journey.examResults.length === 0 ? (
                    <p className="text-xs text-slate-500">Chưa có kết quả thi nào được công bố.</p>
                  ) : (
                    <ul className="divide-y divide-slate-100">
                      {journey.examResults.map((r, i) => (
                        <li key={`${r.examId}-${i}`} className="flex items-center justify-between py-2 text-sm">
                          <span className="font-semibold text-slate-800">{r.examTitle}</span>
                          <span className="text-xs text-slate-500">
                            {r.score != null ? `${r.score}%` : '—'}
                            {r.submittedAt && ` · ${new Date(r.submittedAt).toLocaleDateString('vi-VN')}`}
                          </span>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
