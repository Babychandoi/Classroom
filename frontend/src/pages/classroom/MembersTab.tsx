import React, { useEffect, useState } from 'react';
import { useOutletContext, Link } from 'react-router-dom';
import { Classroom } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner, StatusBadge } from '../../components/UIStates';
import { Users, ShieldCheck, ChevronRight } from 'lucide-react';

interface MemberItem {
  id: string;
  userId: string;
  role: string;
  state: string;
  joinedAt: string;
  userFullName?: string | null;
  userAvatarUrl?: string | null;
}

export const MembersTab: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();

  const [members, setMembers] = useState<MemberItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchMembers = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.get<MemberItem[]>(`/classes/${classroom.id}/members`);
      setMembers(data || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải danh sách thành viên');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchMembers();
  }, [classroom.id]);

  // R15-04: the API returns every state only to class administrators (OWNER / MEMBER:VIEW staff);
  // everyone else already receives ACTIVE members alone. The headline count must be the number of
  // people who actually belong to the class, so a REMOVED/BLOCKED row never inflates it.
  const activeCount = members.filter((m) => (m.state || '').toUpperCase() === 'ACTIVE').length;

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      <div className="flex justify-between items-center">
        <div>
          <h2 className="text-xl font-extrabold text-slate-900 tracking-tight">Thành viên lớp học</h2>
          <p className="text-xs text-slate-500">Danh sách các giáo viên, trợ giảng và học viên cùng tham gia lớp</p>
        </div>
        <div className="text-xs font-semibold px-3 py-1 bg-indigo-50 text-indigo-700 rounded-lg">
          {activeCount} thành viên
        </div>
      </div>

      {loading && <LoadingSpinner message="Đang tải danh sách thành viên..." />}
      {error && <ErrorBanner message={error} onRetry={fetchMembers} />}

      {!loading && (
        <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm divide-y divide-slate-100">
          {members.map((m) => {
            // R16-07: class administrators receive REMOVED/BLOCKED rows with the person's real name (the
            // API reveals identity to them for any roster row); dim those rows so they read as "no longer
            // in the class" without hiding who they are.
            const memberState = (m.state || '').toUpperCase();
            const inactive = memberState !== 'ACTIVE';
            const stateClass = memberState === 'BLOCKED' ? 'text-rose-600' : memberState === 'REMOVED' ? 'text-amber-700' : 'text-slate-500';
            // R8-07: an anonymised member has no userId — never link to /members/null (a broken
            // profile route); render the same row as a non-interactive div instead.
            const content = (
              <>
                <div className="flex items-center space-x-3">
                  <div className="w-10 h-10 rounded-full bg-slate-100 border border-slate-200 flex items-center justify-center font-bold text-slate-700 text-sm group-hover:border-indigo-300 transition overflow-hidden">
                    {m.userAvatarUrl ? (
                      <img src={m.userAvatarUrl} alt="" className="w-full h-full object-cover" />
                    ) : (
                      m.role === 'OWNER' ? 'GV' : m.role === 'STAFF' ? 'TG' : 'HV'
                    )}
                  </div>
                  <div>
                    <div className="flex items-center space-x-2">
                      <span className="text-sm font-bold text-slate-900 group-hover:text-indigo-600 transition">
                        {m.userFullName || (m.userId ? `ID: ${m.userId.substring(0, 8)}...` : 'Thành viên ẩn danh')}
                      </span>
                      <StatusBadge status={m.role} />
                    </div>
                    <span className="text-[11px] text-slate-500">
                      Tham gia ngày {new Date(m.joinedAt).toLocaleDateString('vi-VN')}
                    </span>
                  </div>
                </div>

                <div className="flex items-center space-x-2">
                  <span className={`text-xs font-semibold ${stateClass}`}>{m.state}</span>
                  {m.userId && (
                    <span className="text-xs text-indigo-600 font-semibold hidden sm:inline group-hover:underline">
                      Xem hồ sơ
                    </span>
                  )}
                  {m.userId && <ChevronRight className="w-4 h-4 text-slate-500 group-hover:text-indigo-600 transition" />}
                </div>
              </>
            );

            if (!m.userId) {
              return (
                <div key={m.id} data-member-state={memberState} className={`p-4 flex items-center justify-between group ${inactive ? 'opacity-70' : ''}`}>
                  {content}
                </div>
              );
            }

            return (
              <Link
                key={m.id}
                to={`/classes/${classroom.slug}/members/${m.userId}`}
                data-member-state={memberState}
                className={`p-4 flex items-center justify-between hover:bg-slate-50 transition group ${inactive ? 'opacity-70' : ''}`}
              >
                {content}
              </Link>
            );
          })}
        </div>
      )}
    </div>
  );
};
