import React, { useEffect, useState } from 'react';
import { useParams, Outlet, NavLink, Link, useNavigate, useLocation } from 'react-router-dom';
import { Classroom } from '../../types';
import { api } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import {
  LayoutDashboard,
  BookOpen,
  Award,
  CheckSquare,
  Users,
  Layers,
  ShoppingBag,
  ShieldAlert,
  ArrowLeft,
  MessageSquare,
  FileText,
  Info,
} from 'lucide-react';

export const StudioLayout: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const { user } = useAuth();
  const navigate = useNavigate();

  const [classroom, setClassroom] = useState<Classroom | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchClassroom = async () => {
    if (!id) return;
    try {
      setLoading(true);
      const data = await api.get<Classroom>(`/classes/${id}`);
      if (data.userRole !== 'OWNER' && data.userRole !== 'STAFF') {
        throw new Error('Bạn không có quyền truy cập Studio của lớp học này');
      }
      setClassroom(data);
    } catch (err: any) {
      setError(err.message || 'Không thể truy cập Studio');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchClassroom();
  }, [id, user]);

  const navItems = [
    { label: 'Tổng quan', icon: LayoutDashboard, path: `/studio/classes/${id}/overview`, grants: ['STUDIO:VIEW'] },
    { label: 'Khóa học & Bài giảng', icon: BookOpen, path: `/studio/classes/${id}/courses`, grants: ['COURSE:VIEW', 'COURSE:CREATE', 'COURSE:EDIT'] },
    { label: 'Kỳ thi & Đề thi', icon: Award, path: `/studio/classes/${id}/exams`, grants: ['EXAM:VIEW', 'EXAM:CREATE', 'EXAM:EDIT'] },
    { label: 'Chấm bài thi', icon: CheckSquare, path: `/studio/classes/${id}/grading`, grants: ['EXAM:GRADE'] },
    { label: 'Nhân sự & Phân quyền', icon: Users, path: `/studio/classes/${id}/staff`, grants: ['STAFF:VIEW'] },
    { label: 'Phân khúc học viên', icon: Layers, path: `/studio/classes/${id}/segments`, grants: ['SEGMENT:VIEW', 'SEGMENT:CREATE', 'SEGMENT:EDIT'] },
    { label: 'Sản phẩm & Đơn hàng', icon: ShoppingBag, path: `/studio/classes/${id}/store`, grants: ['STORE:VIEW', 'STORE:CREATE', 'STORE:EDIT', 'STORE:PUBLISH'] },
    { label: 'Bảng tin', icon: MessageSquare, path: `/studio/classes/${id}/feed`, grants: ['FEED:VIEW', 'FEED:CREATE', 'FEED:EDIT'] },
    { label: 'Tài liệu', icon: FileText, path: `/studio/classes/${id}/documents`, grants: ['DOCUMENT:VIEW', 'DOCUMENT:CREATE', 'DOCUMENT:EDIT'] },
    { label: 'Giới thiệu & nội quy', icon: Info, path: `/studio/classes/${id}/about`, grants: ['CLASS:VIEW', 'ABOUT:EDIT'] },
    { label: 'Nhật ký kiểm toán', icon: ShieldAlert, path: `/studio/classes/${id}/audit`, grants: ['AUDIT:VIEW'] },
  ];

  if (loading) return <LoadingSpinner message="Đang kết nối Studio..." />;
  if (error || !classroom) {
    return (
      <div className="max-w-2xl mx-auto py-12 px-4">
        <ErrorBanner message={error || 'Không tìm thấy lớp học'} onRetry={() => navigate('/classes')} />
      </div>
    );
  }

  const location = useLocation();

  const activeNavItem = navItems.find((item) => location.pathname === item.path || location.pathname.startsWith(item.path + '/'));
  const isAuthorized = !activeNavItem || classroom.userRole === 'OWNER' || activeNavItem.grants.some((grant) => classroom.studioPermissions?.includes(grant));

  return (
    <div className="min-h-screen bg-slate-100 flex flex-col md:flex-row">
      {/* Studio Sidebar */}
      <aside className="w-full md:w-64 bg-slate-900 text-white flex-shrink-0 flex flex-col justify-between">
        <div>
          {/* Header */}
          <div className="p-5 border-b border-slate-800">
            <Link
              to={`/classes/${classroom.slug}/feed`}
              className="inline-flex items-center space-x-1.5 text-xs text-indigo-400 hover:text-indigo-300 font-bold mb-3 transition"
            >
              <ArrowLeft className="w-3.5 h-3.5" />
              <span>Trở về lớp học</span>
            </Link>
            <h2 className="text-base font-extrabold tracking-tight truncate">{classroom.title}</h2>
            <div className="flex items-center space-x-2 mt-1">
              <span className="text-[10px] px-2 py-0.5 rounded font-mono bg-indigo-500/20 text-indigo-300 font-bold">
                STUDIO • {classroom.userRole}
              </span>
            </div>
          </div>

          {/* Navigation Links */}
          <nav className="p-3 space-y-1">
            {navItems.filter((item) => classroom.userRole === 'OWNER' || item.grants.some((grant) => classroom.studioPermissions?.includes(grant))).map((item) => {
              const Icon = item.icon;
              return (
                <NavLink
                  key={item.path}
                  to={item.path}
                  className={({ isActive }) =>
                    `flex items-center space-x-3 px-3 py-2.5 rounded-xl text-xs font-semibold transition ${
                      isActive
                        ? 'bg-indigo-600 text-white shadow-md shadow-indigo-900/50'
                        : 'text-slate-400 hover:text-white hover:bg-slate-800/60'
                    }`
                  }
                >
                  <Icon className="w-4 h-4" />
                  <span>{item.label}</span>
                </NavLink>
              );
            })}
          </nav>
        </div>

        <div className="p-4 border-t border-slate-800 text-[11px] text-slate-500">
          Hệ Thống Lớp Học v0.1 • Studio
        </div>
      </aside>

      {/* Main Studio Work Area */}
      <main className="flex-1 p-6 md:p-10 overflow-y-auto">
        {!isAuthorized ? (
          <div className="max-w-xl mx-auto py-12">
            <div className="bg-white rounded-2xl border border-rose-200 p-8 shadow-sm text-center">
              <div className="w-12 h-12 rounded-full bg-rose-100 text-rose-600 mx-auto flex items-center justify-center mb-3">
                <ShieldAlert className="w-6 h-6" />
              </div>
              <h3 className="text-lg font-bold text-slate-900 mb-1">Không đủ quyền truy cập</h3>
              <p className="text-xs text-slate-500 mb-4">
                Tài khoản nhân sự của bạn không được cấp quyền truy cập khu vực này trong Studio.
              </p>
              <Link
                to={`/studio/classes/${classroom.id}/overview`}
                className="inline-flex items-center space-x-1.5 px-4 py-2 bg-slate-900 hover:bg-slate-800 text-white rounded-xl text-xs font-bold transition"
              >
                <span>Về trang Tổng quan</span>
              </Link>
            </div>
          </div>
        ) : (
          <Outlet context={{ classroom, refreshClassroom: fetchClassroom }} />
        )}
      </main>
    </div>
  );
};
