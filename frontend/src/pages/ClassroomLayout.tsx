import React, { useEffect, useState } from 'react';
import { useParams, Outlet, useNavigate } from 'react-router-dom';
import { Classroom } from '../types';
import { api } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { ClassroomHeader } from '../components/ClassroomHeader';
import { LoadingSpinner, ErrorBanner } from '../components/UIStates';
import { UserPlus } from 'lucide-react';

export const ClassroomLayout: React.FC = () => {
  const { slug } = useParams<{ slug: string }>();
  const { user } = useAuth();
  const navigate = useNavigate();

  const [classroom, setClassroom] = useState<Classroom | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [joining, setJoining] = useState(false);

  const fetchClassroom = async () => {
    if (!slug) return;
    try {
      setLoading(true);
      const data = await api.get<Classroom>(`/classes/slug/${slug}`);
      setClassroom(data);
    } catch (err: any) {
      setError(err.message || 'Không thể tải thông tin lớp học');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchClassroom();
  }, [slug, user]);

  const handleJoin = async () => {
    if (!classroom) return;
    if (!user) {
      navigate('/login');
      return;
    }
    setJoining(true);
    try {
      const updated = await api.post<Classroom>(`/classes/${classroom.id}/join`);
      setClassroom(updated);
    } catch (err: any) {
      alert(err.message || 'Tham gia lớp thất bại');
    } finally {
      setJoining(false);
    }
  };

  if (loading) {
    return <LoadingSpinner message="Đang tải dữ liệu lớp học..." />;
  }

  if (error || !classroom) {
    return (
      <div className="max-w-4xl mx-auto p-8">
        <ErrorBanner message={error || 'Không tìm thấy lớp học'} onRetry={fetchClassroom} />
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
      <ClassroomHeader classroom={classroom} />

      {/* Join prompt if user is not member and not owner */}
      {!classroom.isMember && !classroom.isOwner && (
        <div className="bg-indigo-50 border-b border-indigo-100 py-3 px-4">
          <div className="max-w-7xl mx-auto flex items-center justify-between">
            <span className="text-xs sm:text-sm font-medium text-indigo-900">
              Bạn đang xem lớp học này ở chế độ khách. Hãy tham gia lớp để học tập và thảo luận!
            </span>
            <button
              onClick={handleJoin}
              disabled={joining}
              className="inline-flex items-center space-x-1.5 px-4 py-1.5 bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-bold rounded-lg shadow-sm transition disabled:opacity-50"
            >
              <UserPlus className="w-3.5 h-3.5" />
              <span>{joining ? 'Đang tham gia...' : 'Tham gia lớp ngay'}</span>
            </button>
          </div>
        </div>
      )}

      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <Outlet context={{ classroom, refreshClassroom: fetchClassroom }} />
      </main>
    </div>
  );
};
