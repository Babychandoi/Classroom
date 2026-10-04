import React, { useEffect, useState } from 'react';
import { useParams, Link, useOutletContext } from 'react-router-dom';
import { Classroom, MemberProfile, UserJourney } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { Avatar, Badge, CoverImage, ProgressBar } from '../../components/ui';
import { formatDate } from '../../api/format';
import { ArrowLeft, Calendar, FileCheck2, GraduationCap, Mail, Star, UserCheck } from 'lucide-react';

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

  const roleText = (p: MemberProfile) =>
    p.membershipRole === 'OWNER' ? 'Giáo viên chủ nhiệm' : p.membershipRole === 'STAFF' ? 'Trợ giảng' : p.isPro ? 'Học viên PRO' : 'Học viên';
  const card = 'rounded-card border border-slate-200 bg-white shadow-hairline';

  return (
    <div className="space-y-6">
      <Link
        to={`/classes/${classroom.slug}/members`}
        className="inline-flex items-center gap-2 text-ui font-medium text-slate-600 transition-colors duration-micro hover:text-slate-900"
      >
        <ArrowLeft className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
        <span>Danh sách thành viên</span>
      </Link>

      {loading && <LoadingSpinner message="Đang tải hồ sơ thành viên..." />}
      {error && <ErrorBanner message={error} onRetry={fetchProfile} />}

      {!loading && profile && (
        <div className="space-y-6">
          {/* Identity: cover strip (topic tile) + round person avatar overlapping it */}
          <section className={`overflow-hidden ${card}`}>
            <div className="h-24 sm:h-32">
              <CoverImage seed={profile.id || profile.fullName} />
            </div>
            <div className="px-5 pb-5 sm:px-7 sm:pb-6">
              <div className="flex flex-wrap items-end gap-4 sm:gap-5">
                <Avatar name={profile.fullName} src={profile.avatarUrl} size={96} className="-mt-12 border-4 border-white shadow-hairline" />
                <div className="min-w-0 flex-1 pt-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <h1 className="text-h2 font-semibold text-slate-900">{profile.fullName}</h1>
                    {profile.membershipRole === 'OWNER' && <Badge tone="member" size="sm">Chủ lớp</Badge>}
                    {profile.membershipRole === 'STAFF' && <Badge tone="neutral" size="sm">Trợ giảng</Badge>}
                    {profile.isPro && (
                      <Badge tone="pro" size="sm">
                        <Star className="h-3 w-3 fill-current" aria-hidden="true" />
                        PRO
                      </Badge>
                    )}
                  </div>
                  <p className="mt-0.5 text-ui text-slate-600">{roleText(profile)} · {classroom.title}</p>
                </div>
              </div>
            </div>
          </section>

          <div className="grid items-start gap-6 lg:grid-cols-[340px_minmax(0,1fr)]">
            {/* Left rail: about */}
            <section className={`px-5 py-5 sm:px-6 ${card}`}>
              <h2 className="text-body-sm font-semibold text-slate-900">Giới thiệu</h2>
              <p className="mt-3 text-ui leading-[22px] text-slate-600">{profile.bio || 'Thành viên này chưa viết lời giới thiệu.'}</p>
              <div className="mt-4 space-y-2 text-meta text-slate-600">
                {profile.email ? (
                  <p className="flex items-center gap-2.5 break-all">
                    <Mail className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" />
                    <span>{profile.email}</span>
                  </p>
                ) : (
                  <p className="flex items-center gap-2.5 text-slate-500">
                    <Mail className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" />
                    <span>Email được bảo vệ quyền riêng tư</span>
                  </p>
                )}
                {profile.createdAt && (
                  <p className="flex items-center gap-2.5 tabular">
                    <Calendar className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" />
                    <span>Gia nhập: {formatDate(profile.createdAt)}</span>
                  </p>
                )}
                <p className="flex items-center gap-2.5">
                  <UserCheck className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" />
                  <span>{roleText(profile)}</span>
                </p>
              </div>
              <p className="mt-4 border-t border-slate-100 pt-3 text-caption text-slate-500">Mã thành viên: <span className="font-mono">{profile.id}</span></p>
            </section>

            {/* Learning & Exam Journey (FR-12) */}
            <div className="min-w-0 space-y-4">
              <h2 className="text-h3-lg font-semibold text-slate-900">Hành trình học tập & thành tích</h2>
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <div className={`p-5 ${card}`}>
                  <span className="text-caption font-semibold uppercase tracking-[0.5px] text-slate-500">Điểm tích lũy</span>
                  <p className="mt-1 text-h2 font-semibold text-slate-900 tabular">{(profile.totalPoints ?? 0).toLocaleString('vi-VN')}</p>
                  <p className="text-caption text-slate-600">Từ các kỳ thi đã công bố kết quả</p>
                </div>
                <div className={`p-5 ${card}`}>
                  <span className="text-caption font-semibold uppercase tracking-[0.5px] text-slate-500">Hạng thành tích</span>
                  <p className="mt-1 text-h2 font-semibold text-slate-900">{profile.rankTier || 'Chưa xếp hạng'}</p>
                  <p className="text-caption text-slate-600">Xác định theo thang điểm lớp học</p>
                </div>
              </div>

              {/* R13-05 (FR-12/D-05): per-course progress + published exam results in this class */}
              {journeyLoading && <LoadingSpinner message="Đang tải hành trình học tập..." />}
              {journeyError && <ErrorBanner message={journeyError} onRetry={fetchJourney} />}

              {!journeyLoading && !journeyError && journey && (
                <>
                  <section className={`p-5 sm:px-6 ${card}`}>
                    <h3 className="flex items-center gap-2 text-ui font-semibold text-slate-900">
                      <GraduationCap className="h-4 w-4 text-slate-600" strokeWidth={1.75} aria-hidden="true" />
                      <span>Tiến độ khóa học</span>
                    </h3>
                    {journey.courses.length === 0 ? (
                      <p className="mt-3 text-meta text-slate-600">Chưa có khóa học nào có thể theo dõi tiến độ.</p>
                    ) : (
                      <ul className="mt-4 space-y-4">
                        {journey.courses.map((c) => {
                          const pct = c.totalLessons > 0 ? Math.round((c.completedLessons / c.totalLessons) * 100) : 0;
                          return (
                            <li key={c.courseId}>
                              <div className="mb-1.5 flex items-center justify-between gap-3 text-meta">
                                <span className="font-semibold text-slate-900">{c.courseTitle}</span>
                                <span className="flex-shrink-0 text-caption text-slate-600 tabular">{c.completedLessons}/{c.totalLessons} bài ({pct}%)</span>
                              </div>
                              <ProgressBar value={c.completedLessons} max={c.totalLessons || 1} label={`Tiến độ ${c.courseTitle}`} />
                            </li>
                          );
                        })}
                      </ul>
                    )}
                  </section>

                  <section className={`p-5 sm:px-6 ${card}`}>
                    <h3 className="flex items-center gap-2 text-ui font-semibold text-slate-900">
                      <FileCheck2 className="h-4 w-4 text-slate-600" strokeWidth={1.75} aria-hidden="true" />
                      <span>Kết quả thi đã công bố</span>
                    </h3>
                    {journey.examResults.length === 0 ? (
                      <p className="mt-3 text-meta text-slate-600">Chưa có kết quả thi nào được công bố.</p>
                    ) : (
                      <ul className="mt-2 divide-y divide-slate-100">
                        {journey.examResults.map((r, i) => (
                          <li key={`${r.examId}-${i}`} className="flex items-center justify-between gap-3 py-3 text-ui">
                            <span className="font-semibold text-slate-900">{r.examTitle}</span>
                            <span className="flex-shrink-0 text-meta text-slate-600 tabular">
                              {r.score != null ? `${r.score}%` : '—'}
                              {r.submittedAt && ` · ${formatDate(r.submittedAt)}`}
                            </span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </section>
                </>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
