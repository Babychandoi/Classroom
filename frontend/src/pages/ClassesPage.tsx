import React, { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Classroom } from '../types';
import { api } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { LoadingSpinner, ErrorBanner, StatusBadge } from '../components/UIStates';
import { Plus, Users, ArrowRight, BookOpen, Sparkles } from 'lucide-react';

export const ClassesPage: React.FC = () => {
  const { user } = useAuth();
  const navigate = useNavigate();

  const [classes, setClasses] = useState<Classroom[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const [newSlug, setNewSlug] = useState('');
  const [newDesc, setNewDesc] = useState('');
  const [creating, setCreating] = useState(false);

  const fetchClasses = async () => {
    try {
      setLoading(true);
      const data = await api.get<Classroom[]>('/classes');
      setClasses(data || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải danh sách lớp học');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchClasses();
  }, [user]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setCreating(true);
    try {
      const created = await api.post<Classroom>('/classes', {
        title: newTitle,
        slug: newSlug,
        description: newDesc,
      });
      setShowCreateModal(false);
      navigate(`/classes/${created.slug}/feed`);
    } catch (err: any) {
      alert(err.message || 'Tạo lớp thất bại');
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      {/* Hero Banner */}
      <div className="bg-gradient-to-r from-indigo-900 via-indigo-800 to-purple-900 rounded-3xl p-8 md:p-12 text-white shadow-xl mb-10 relative overflow-hidden">
        <div className="max-w-2xl relative z-10">
          <div className="inline-flex items-center space-x-2 px-3 py-1 rounded-full bg-indigo-500/30 text-indigo-200 text-xs font-semibold mb-4 backdrop-blur-sm border border-indigo-400/20">
            <Sparkles className="w-3.5 h-3.5" />
            <span>Nền tảng lớp học số thế hệ mới</span>
          </div>
          <h1 className="text-3xl md:text-5xl font-black tracking-tight leading-tight">
            Khám phá không gian học tập trực tuyến
          </h1>
          <p className="mt-4 text-base text-indigo-100 font-medium leading-relaxed">
            Học tập nội dung chuyên sâu, tham gia các kỳ thi xếp hạng thử thách, thảo luận cùng bạn bè và mở khóa đặc quyền PRO.
          </p>

          {user && (
            <div className="mt-6 flex flex-wrap gap-3">
              <button
                onClick={() => setShowCreateModal(true)}
                className="inline-flex items-center space-x-2 px-5 py-3 bg-white text-indigo-900 font-bold text-sm rounded-xl hover:bg-indigo-50 shadow-md transition"
              >
                <Plus className="w-4 h-4" />
                <span>Tạo lớp học mới</span>
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Classroom List */}
      <div className="mb-6 flex justify-between items-center">
        <div>
          <h2 className="text-2xl font-extrabold text-slate-900 tracking-tight">Danh sách lớp học</h2>
          <p className="text-sm text-slate-500">Chọn lớp học để bắt đầu hành trình của bạn</p>
        </div>
      </div>

      {loading && <LoadingSpinner message="Đang tải danh sách lớp..." />}
      {error && <ErrorBanner message={error} onRetry={fetchClasses} />}

      {!loading && !error && classes.length === 0 && (
        <div className="text-center p-12 bg-white rounded-2xl border border-slate-200">
          <BookOpen className="w-12 h-12 mx-auto text-slate-400 mb-3" />
          <h3 className="text-lg font-bold text-slate-800">Chưa có lớp học nào</h3>
          <p className="text-sm text-slate-500 mt-1">Hãy tạo lớp học đầu tiên ngay bây giờ!</p>
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {classes.map((cls) => (
          <div
            key={cls.id}
            className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm hover:shadow-md transition flex flex-col group"
          >
            <div className="h-44 w-full bg-slate-800 relative overflow-hidden">
              {cls.coverImageUrl ? (
                <img
                  src={cls.coverImageUrl}
                  alt={cls.title}
                  className="w-full h-full object-cover group-hover:scale-105 transition duration-500 opacity-80"
                />
              ) : (
                <div className="w-full h-full bg-gradient-to-tr from-indigo-700 to-purple-600" />
              )}
              <div className="absolute top-3 right-3">
                {cls.userRole && <StatusBadge status={cls.userRole} />}
              </div>
            </div>

            <div className="p-5 flex-1 flex flex-col justify-between">
              <div>
                <span className="text-[11px] font-mono text-indigo-600 font-bold uppercase tracking-wider">
                  /{cls.slug}
                </span>
                <h3 className="text-lg font-bold text-slate-900 mt-1 line-clamp-1 group-hover:text-indigo-600 transition">
                  {cls.title}
                </h3>
                <p className="text-sm text-slate-500 line-clamp-2 mt-1.5 mb-4">
                  {cls.description || 'Chưa có mô tả lớp học.'}
                </p>
              </div>

              <div className="pt-4 border-t border-slate-100 flex items-center justify-between">
                <div className="flex items-center space-x-1.5 text-xs text-slate-500 font-medium">
                  <Users className="w-4 h-4 text-slate-400" />
                  <span>{cls.memberCount} thành viên</span>
                </div>

                <Link
                  to={`/classes/${cls.slug}/feed`}
                  className="inline-flex items-center space-x-1.5 px-4 py-2 bg-slate-900 text-white hover:bg-indigo-600 rounded-xl text-xs font-bold transition shadow-sm"
                >
                  <span>Vào lớp</span>
                  <ArrowRight className="w-3.5 h-3.5" />
                </Link>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Create Class Modal */}
      {showCreateModal && (
        <div className="fixed inset-0 bg-slate-900/50 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-white rounded-2xl max-w-lg w-full p-6 shadow-2xl border border-slate-200">
            <h3 className="text-xl font-bold text-slate-900 mb-4">Tạo lớp học mới</h3>
            <form onSubmit={handleCreate} className="space-y-4">
              <div>
                <label className="block text-xs font-semibold text-slate-700 uppercase">Tên lớp học</label>
                <input
                  type="text"
                  required
                  value={newTitle}
                  onChange={(e) => setNewTitle(e.target.value)}
                  placeholder="Ví dụ: Lớp Luyện Thi THPT Quốc Gia"
                  className="mt-1 block w-full px-3.5 py-2.5 bg-slate-50 border border-slate-300 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-700 uppercase">Đường dẫn slug (URL)</label>
                <input
                  type="text"
                  required
                  value={newSlug}
                  onChange={(e) => setNewSlug(e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '-'))}
                  placeholder="lop-luyen-thi-thpt"
                  className="mt-1 block w-full px-3.5 py-2.5 bg-slate-50 border border-slate-300 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 font-mono"
                />
                <p className="text-[11px] text-slate-500 mt-1">Chỉ gồm chữ thường, số và dấu gạch ngang.</p>
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-700 uppercase">Mô tả ngắn</label>
                <textarea
                  rows={3}
                  value={newDesc}
                  onChange={(e) => setNewDesc(e.target.value)}
                  placeholder="Mục tiêu và lộ trình của lớp..."
                  className="mt-1 block w-full px-3.5 py-2.5 bg-slate-50 border border-slate-300 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              <div className="flex justify-end space-x-3 pt-3">
                <button
                  type="button"
                  onClick={() => setShowCreateModal(false)}
                  className="px-4 py-2 border border-slate-300 text-slate-700 text-sm font-semibold rounded-xl hover:bg-slate-50"
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  disabled={creating}
                  className="px-5 py-2 bg-indigo-600 text-white text-sm font-bold rounded-xl hover:bg-indigo-700 disabled:opacity-50"
                >
                  {creating ? 'Đang tạo...' : 'Xác nhận tạo lớp'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
