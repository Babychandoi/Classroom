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

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      <div className="flex justify-between items-center">
        <div>
          <h2 className="text-xl font-extrabold text-slate-900 tracking-tight">Thành viên lớp học</h2>
          <p className="text-xs text-slate-500">Danh sách các giáo viên, trợ giảng và học viên cùng tham gia lớp</p>
        </div>
        <div className="text-xs font-semibold px-3 py-1 bg-indigo-50 text-indigo-700 rounded-lg">
          {members.length} thành viên
        </div>
      </div>

      {loading && <LoadingSpinner message="Đang tải danh sách thành viên..." />}
      {error && <ErrorBanner message={error} onRetry={fetchMembers} />}

      {!loading && (
        <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm divide-y divide-slate-100">
          {members.map((m) => (
            <Link
              key={m.id}
              to={`/classes/${classroom.slug}/members/${m.userId}`}
              className="p-4 flex items-center justify-between hover:bg-slate-50 transition group"
            >
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
                      {m.userFullName || `ID: ${m.userId.substring(0, 8)}...`}
                    </span>
                    <StatusBadge status={m.role} />
                  </div>
                  <span className="text-[11px] text-slate-400">
                    Tham gia ngày {new Date(m.joinedAt).toLocaleDateString('vi-VN')}
                  </span>
                </div>
              </div>

              <div className="flex items-center space-x-2">
                <span className="text-xs font-semibold text-slate-500">{m.state}</span>
                <span className="text-xs text-indigo-600 font-semibold hidden sm:inline group-hover:underline">
                  Xem hồ sơ
                </span>
                <ChevronRight className="w-4 h-4 text-slate-400 group-hover:text-indigo-600 transition" />
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
};
