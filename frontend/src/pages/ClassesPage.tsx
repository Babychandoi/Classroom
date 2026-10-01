import React, { useEffect, useId, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Classroom, ClassVisibility } from '../types';
import { api } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { LoadingSpinner, ErrorBanner, StatusBadge } from '../components/UIStates';
import { Modal } from '../components/Modal';
import { ClassBadges } from '../components/ClassBadges';
import { Plus, Users, ArrowRight, BookOpen, Sparkles, Globe, Lock } from 'lucide-react';

// R16-08: GET /classes is paged (the server caps a page at 100 and defaults to 50). A page shorter than
// the requested size is the last one; anything else offers "Xem thêm lớp học".
export const CLASSES_PAGE_SIZE = 50;

// D-19: client-side filter on the classes already loaded (the server has no fee filter).
type FeeFilter = 'ALL' | 'FREE' | 'PAID';
const FEE_FILTERS: { key: FeeFilter; label: string }[] = [
  { key: 'ALL', label: 'Tất cả' },
  { key: 'FREE', label: 'Miễn phí' },
  { key: 'PAID', label: 'Trả phí' },
];

export const ClassesPage: React.FC = () => {
  // R17-01: wait for the session bootstrap before the first fetch - GET /classes answers differently for
  // a signed-in person (their role/membership), so fetching first as a guest just to redo it moments
  // later doubled the request and flashed the wrong state.
  const { user, isLoading: authLoading } = useAuth();
  const navigate = useNavigate();

  const [classes, setClasses] = useState<Classroom[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [nextPage, setNextPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const [newSlug, setNewSlug] = useState('');
  const [newDesc, setNewDesc] = useState('');
  // D-19: a new class is PUBLIC unless its owner makes it PRIVATE (joined only through an invite link).
  const [newVisibility, setNewVisibility] = useState<ClassVisibility>('PUBLIC');
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const [feeFilter, setFeeFilter] = useState<FeeFilter>('ALL');
  const visibilityGroupId = useId();
  const titleId = useId();
  const slugId = useId();
  const descId = useId();

  const fetchClasses = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = (await api.get<Classroom[]>(`/classes?page=0&size=${CLASSES_PAGE_SIZE}`)) || [];
      setClasses(data);
      setNextPage(1);
      setHasMore(data.length >= CLASSES_PAGE_SIZE);
    } catch (err: any) {
      setError(err.message || 'Không thể tải danh sách lớp học');
    } finally {
      setLoading(false);
    }
  };

  const loadMoreClasses = async () => {
    try {
      setLoadingMore(true);
      setError(null);
      const data = (await api.get<Classroom[]>(`/classes?page=${nextPage}&size=${CLASSES_PAGE_SIZE}`)) || [];
      // De-dup by id: a class created meanwhile can shift rows across a page boundary.
      setClasses((current) => {
        const seen = new Set(current.map((c) => c.id));
        return [...current, ...data.filter((c) => !seen.has(c.id))];
      });
      setNextPage(nextPage + 1);
      setHasMore(data.length >= CLASSES_PAGE_SIZE);
    } catch (err: any) {
      setError(err.message || 'Không thể tải thêm lớp học');
    } finally {
      setLoadingMore(false);
    }
  };

  useEffect(() => {
    if (authLoading) return;
    fetchClasses();
  }, [user?.id, authLoading]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setCreating(true);
    setCreateError(null);
    try {
      const created = await api.post<Classroom>('/classes', {
        title: newTitle,
        slug: newSlug,
        description: newDesc,
        visibility: newVisibility,
      });
      setShowCreateModal(false);
      navigate(`/classes/${created.slug}/feed`);
    } catch (err: any) {
      setCreateError(err.message || 'Tạo lớp thất bại');
    } finally {
      setCreating(false);
    }
  };

  const visibleClasses = classes.filter((cls) => feeFilter === 'ALL' || (cls.accessType ?? 'FREE') === feeFilter);

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
                onClick={() => { setCreateError(null); setShowCreateModal(true); }}
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
      <div className="mb-6 flex flex-col sm:flex-row sm:justify-between sm:items-center gap-3">
        <div>
          <h2 className="text-2xl font-extrabold text-slate-900 tracking-tight">Danh sách lớp học</h2>
          <p className="text-sm text-slate-500">Chọn lớp học để bắt đầu hành trình của bạn</p>
        </div>

        {/* D-19: fee filter chips (client-side, on the classes already loaded) */}
        <div role="group" aria-label="Lọc theo học phí" className="flex items-center gap-2">
          {FEE_FILTERS.map((chip) => {
            const active = feeFilter === chip.key;
            return (
              <button
                key={chip.key}
                type="button"
                aria-pressed={active}
                onClick={() => setFeeFilter(chip.key)}
                className={`px-3.5 py-1.5 rounded-full text-xs font-bold border transition focus:outline-none focus:ring-2 focus:ring-indigo-500 ${
                  active
                    ? 'bg-indigo-600 border-indigo-600 text-white'
                    : 'bg-white border-slate-300 text-slate-700 hover:bg-slate-50'
                }`}
              >
                {chip.label}
              </button>
            );
          })}
        </div>
      </div>

      {loading && <LoadingSpinner message="Đang tải danh sách lớp..." />}
      {error && <ErrorBanner message={error} onRetry={fetchClasses} />}

      {!loading && !error && classes.length === 0 && (
        <div className="text-center p-12 bg-white rounded-2xl border border-slate-200">
          <BookOpen className="w-12 h-12 mx-auto text-slate-500 mb-3" />
          <h3 className="text-lg font-bold text-slate-800">Chưa có lớp học nào</h3>
          <p className="text-sm text-slate-500 mt-1">Hãy tạo lớp học đầu tiên ngay bây giờ!</p>
        </div>
      )}

      {!loading && !error && classes.length > 0 && visibleClasses.length === 0 && (
        <div className="text-center p-10 bg-white rounded-2xl border border-slate-200" data-testid="filter-empty">
          <h3 className="text-base font-bold text-slate-800">Không có lớp nào phù hợp bộ lọc</h3>
          <p className="text-sm text-slate-500 mt-1">
            Bộ lọc chỉ áp dụng cho các lớp đã tải{hasMore ? '; hãy bấm "Xem thêm lớp học" để tải thêm.' : '.'}
          </p>
          <button
            type="button"
            onClick={() => setFeeFilter('ALL')}
            className="mt-4 px-4 py-2 bg-white border border-slate-300 text-slate-700 text-xs font-bold rounded-xl hover:bg-slate-50"
          >
            Xem tất cả lớp
          </button>
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {visibleClasses.map((cls) => (
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
              {/* D-19: "Riêng tư" (only ever on a class the viewer can see) and "Miễn phí" / "Trả phí · 199.000đ / 30 ngày" */}
              <ClassBadges classroom={cls} className="absolute bottom-3 left-3 right-3" />
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
                  <Users className="w-4 h-4 text-slate-500" />
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

      {hasMore && !loading && (
        <div className="mt-8 flex justify-center">
          <button
            type="button"
            onClick={loadMoreClasses}
            disabled={loadingMore}
            className="px-5 py-2.5 bg-white border border-slate-300 text-slate-700 text-sm font-semibold rounded-xl hover:bg-slate-50 disabled:opacity-50"
          >
            {loadingMore ? 'Đang tải...' : 'Xem thêm lớp học'}
          </button>
        </div>
      )}

      {/* Create Class Modal */}
      {showCreateModal && (
        <Modal size="lg" title="Tạo lớp học mới" onClose={() => setShowCreateModal(false)}>
            <form onSubmit={handleCreate} className="space-y-4">
              {createError && (
                <p role="alert" className="p-3 bg-rose-50 border border-rose-200 rounded-xl text-xs font-semibold text-rose-800">{createError}</p>
              )}
              <div>
                <label htmlFor={titleId} className="block text-xs font-semibold text-slate-700 uppercase">Tên lớp học</label>
                <input
                  id={titleId}
                  type="text"
                  required
                  value={newTitle}
                  onChange={(e) => setNewTitle(e.target.value)}
                  placeholder="Ví dụ: Lớp Luyện Thi THPT Quốc Gia"
                  className="mt-1 block w-full px-3.5 py-2.5 bg-slate-50 border border-slate-300 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              <div>
                <label htmlFor={slugId} className="block text-xs font-semibold text-slate-700 uppercase">Đường dẫn slug (URL)</label>
                <input
                  id={slugId}
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
                <label htmlFor={descId} className="block text-xs font-semibold text-slate-700 uppercase">Mô tả ngắn</label>
                <textarea
                  id={descId}
                  rows={3}
                  value={newDesc}
                  onChange={(e) => setNewDesc(e.target.value)}
                  placeholder="Mục tiêu và lộ trình của lớp..."
                  className="mt-1 block w-full px-3.5 py-2.5 bg-slate-50 border border-slate-300 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              {/* D-19: who can find and join the class */}
              <fieldset>
                <legend className="block text-xs font-semibold text-slate-700 uppercase">Chế độ hiển thị</legend>
                <div className="mt-2 grid gap-2">
                  {([
                    { value: 'PUBLIC' as const, label: 'Công khai', hint: 'Hiện trong danh sách khám phá; ai cũng có thể tìm thấy và tham gia.', Icon: Globe },
                    { value: 'PRIVATE' as const, label: 'Riêng tư', hint: 'Ẩn khỏi khám phá; chỉ người có liên kết mời của bạn mới vào được.', Icon: Lock },
                  ]).map(({ value, label, hint, Icon }) => (
                    <label
                      key={value}
                      className={`flex items-start gap-3 p-3 border rounded-xl cursor-pointer transition ${
                        newVisibility === value ? 'border-indigo-500 bg-indigo-50' : 'border-slate-300 bg-white hover:bg-slate-50'
                      }`}
                    >
                      <input
                        type="radio"
                        name={visibilityGroupId}
                        value={value}
                        checked={newVisibility === value}
                        onChange={() => setNewVisibility(value)}
                        className="mt-1 h-4 w-4 accent-indigo-600"
                      />
                      <span className="min-w-0">
                        <span className="flex items-center gap-1.5 text-sm font-bold text-slate-900">
                          <Icon className="w-3.5 h-3.5" aria-hidden="true" />
                          {label}
                        </span>
                        <span className="block text-xs text-slate-600 mt-0.5">{hint}</span>
                      </span>
                    </label>
                  ))}
                </div>
              </fieldset>

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
        </Modal>
      )}
    </div>
  );
};
