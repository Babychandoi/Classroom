import React, { useEffect, useState } from 'react';
import { useParams, Link, useOutletContext } from 'react-router-dom';
import { Classroom, MemberProfile } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner, StatusBadge } from '../../components/UIStates';
import { ArrowLeft, Award, BookOpen, ShieldCheck, Sparkles, Mail, Calendar, UserCheck } from 'lucide-react';

export const MemberProfilePage: React.FC = () => {
  const { userId } = useParams<{ userId: string }>();
  const { classroom } = useOutletContext<{ classroom: Classroom }>();

  const [profile, setProfile] = useState<MemberProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

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

  useEffect(() => {
    fetchProfile();
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

                <p className="text-xs text-slate-400 font-mono">Mã thành viên: {profile.id}</p>

                {profile.bio && <p className="text-sm text-slate-600">{profile.bio}</p>}

                <div className="pt-2 flex flex-wrap gap-4 text-xs text-slate-500">
                  {profile.email ? (
                    <div className="flex items-center space-x-1 text-slate-700">
                      <Mail className="w-3.5 h-3.5 text-indigo-500" />
                      <span>{profile.email}</span>
                    </div>
                  ) : (
                    <div className="flex items-center space-x-1 text-slate-400 italic">
                      <Mail className="w-3.5 h-3.5 text-slate-400" />
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
                <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider">Điểm tích lũy</span>
                <p className="text-2xl font-black text-indigo-600">{profile.totalPoints ?? 0}</p>
                <p className="text-xs text-slate-500">Từ các kỳ thi đã công bố kết quả</p>
              </div>

              <div className="bg-white rounded-xl border border-slate-200 p-5 shadow-sm space-y-1">
                <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider">Hạng thành tích</span>
                <p className="text-2xl font-black text-slate-800">{profile.rankTier || 'Chưa xếp hạng'}</p>
                <p className="text-xs text-slate-500">Xác định theo thang điểm lớp học</p>
              </div>

              <div className="bg-white rounded-xl border border-slate-200 p-5 shadow-sm space-y-1">
                <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider">Vai trò trong lớp</span>
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
        </div>
      )}
    </div>
  );
};
