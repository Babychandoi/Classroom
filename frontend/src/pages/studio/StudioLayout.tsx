import React, { useEffect, useId, useRef, useState } from 'react';
import { useParams, Outlet, NavLink, Link, Navigate, useNavigate, useLocation } from 'react-router-dom';
import { Classroom } from '../../types';
import { api, ApiException } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { hasAnyStudioPermission } from '../../api/permissions';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { ClassBadges } from '../../components/ClassBadges';
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
  Trophy,
  UserCog,
  Settings,
  Menu,
  X,
} from 'lucide-react';

export const StudioLayout: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const { user } = useAuth();
  const navigate = useNavigate();
  // R7-05: useLocation is a hook and must run unconditionally on every render, so it has to sit
  // above the loading/error early returns below (a hook that only sometimes runs breaks React's
  // hook-order invariant and desyncs state on the very next render that takes the other branch).
  const location = useLocation();

  const [classroom, setClassroom] = useState<Classroom | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // R17-05: below md the 14-item sidebar used to stack above the page and push the actual content to
  // y~800 on a phone. The nav is now a collapsible menu there (always expanded from md up).
  const [menuOpen, setMenuOpen] = useState(false);
  const navId = useId();

  // R18-05: the spinner (which unmounts the page below and throws away its state) is only for the first load
  // of a class for a given person. A later refresh - StudioSettings saving, the user object being refreshed -
  // is stale-while-revalidate: the current page stays mounted, so its "Đã lưu…" message and any open dialog
  // survive. `loadedRef` remembers what the current data was loaded for; `requestSeq` drops superseded replies.
  const loadedRef = useRef<{ id: string; userId: string | null } | null>(null);
  const requestSeq = useRef(0);

  const fetchClassroom = async () => {
    if (!id) return;
    const userId = user?.id ?? null;
    const silent = loadedRef.current?.id === id && loadedRef.current.userId === userId;
    const seq = ++requestSeq.current;
    const fail = (message: string) => {
      loadedRef.current = null;
      setError(message);
    };
    if (!silent) {
      setLoading(true);
      setError(null);
    }
    try {
      const data = await api.get<Classroom>(`/classes/${id}`);
      if (seq !== requestSeq.current) return;
      if (data.userRole !== 'OWNER' && data.userRole !== 'STAFF') {
        fail('Bạn không có quyền truy cập Studio của lớp học này');
        return;
      }
      loadedRef.current = { id, userId };
      setClassroom(data);
      setError(null);
    } catch (err: any) {
      if (seq !== requestSeq.current) return;
      // A background refresh that fails for a transient reason (network blip, 5xx) must not blank a working page;
      // losing access (401/403/404) still must.
      const lostAccess = err instanceof ApiException && (err.status === 401 || err.status === 403 || err.status === 404);
      if (silent && !lostAccess) return;
      fail(err.message || 'Không thể truy cập Studio');
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  };

  useEffect(() => {
    fetchClassroom();
  }, [id, user]);

  // Picking a page (or navigating any other way) closes the phone menu again.
  useEffect(() => {
    setMenuOpen(false);
  }, [location.pathname]);

  const navItems = [
    { label: 'Tổng quan', icon: LayoutDashboard, path: `/studio/classes/${id}/overview`, grants: ['STUDIO:VIEW'] },
    { label: 'Khóa học & Bài giảng', icon: BookOpen, path: `/studio/classes/${id}/courses`, grants: ['COURSE:VIEW', 'COURSE:CREATE', 'COURSE:EDIT'] },
    { label: 'Kỳ thi & Đề thi', icon: Award, path: `/studio/classes/${id}/exams`, grants: ['EXAM:VIEW', 'EXAM:CREATE', 'EXAM:EDIT', 'EXAM:PUBLISH'] },
    // R11-02: this nav entry covers both exam grading (EXAM:GRADE) and assignment grading
    // (COURSE:GRADE, course-scopable) - either grant alone must make it reachable, matching what
    // StudioGrading itself gates each of its two sections on.
    { label: 'Chấm bài', icon: CheckSquare, path: `/studio/classes/${id}/grading`, grants: ['EXAM:GRADE', 'COURSE:GRADE'] },
    { label: 'Cấu hình xếp hạng', icon: Trophy, path: `/studio/classes/${id}/leaderboard`, grants: ['LEADERBOARD:EDIT'] },
    { label: 'Nhân sự & Phân quyền', icon: Users, path: `/studio/classes/${id}/staff`, grants: ['STAFF:VIEW'] },
    { label: 'Thành viên', icon: UserCog, path: `/studio/classes/${id}/members`, grants: ['MEMBER:VIEW', 'MEMBER:EDIT'] },
    { label: 'Phân khúc học viên', icon: Layers, path: `/studio/classes/${id}/segments`, grants: ['SEGMENT:VIEW', 'SEGMENT:CREATE', 'SEGMENT:EDIT'] },
    { label: 'Sản phẩm & Đơn hàng', icon: ShoppingBag, path: `/studio/classes/${id}/store`, grants: ['STORE:VIEW', 'STORE:CREATE', 'STORE:EDIT', 'STORE:PUBLISH'] },
    { label: 'Bảng tin', icon: MessageSquare, path: `/studio/classes/${id}/feed`, grants: ['FEED:VIEW', 'FEED:CREATE', 'FEED:EDIT'] },
    { label: 'Tài liệu', icon: FileText, path: `/studio/classes/${id}/documents`, grants: ['DOCUMENT:VIEW', 'DOCUMENT:CREATE', 'DOCUMENT:EDIT'] },
    { label: 'Giới thiệu & nội quy', icon: Info, path: `/studio/classes/${id}/about`, grants: ['CLASS:VIEW', 'ABOUT:EDIT'] },
    { label: 'Nhật ký kiểm toán', icon: ShieldAlert, path: `/studio/classes/${id}/audit`, grants: ['AUDIT:VIEW'] },
    { label: 'Cài đặt lớp', icon: Settings, path: `/studio/classes/${id}/settings`, grants: ['CLASS:VIEW', 'CLASS:EDIT'] },
  ];

  if (loading) return <LoadingSpinner message="Đang kết nối Studio..." />;
  if (error || !classroom) {
    return (
      <div className="max-w-2xl mx-auto py-12 px-4">
        <ErrorBanner message={error || 'Không tìm thấy lớp học'} onRetry={() => navigate('/classes')} />
      </div>
    );
  }

  // R6-01: a grant here is a "MODULE:ACTION" string; nav/route access is reachable with EITHER a
  // class-wide grant OR a course-scoped grant for at least one course (hasAnyStudioPermission) —
  // otherwise course-scoped-only staff would see an empty Studio. The finer-grained "can I act on
  // course X specifically" decision is made per-page via hasCoursePermission.
  const isItemAuthorized = (item: { grants: string[] }) =>
    classroom.userRole === 'OWNER' ||
    item.grants.some((grant) => {
      const [module, action] = grant.split(':');
      return hasAnyStudioPermission(classroom, module, action);
    });

  const activeNavItem = navItems.find((item) => location.pathname === item.path || location.pathname.startsWith(item.path + '/'));
  const isAuthorized = !activeNavItem || isItemAuthorized(activeNavItem);

  // R7-01: the Studio index route and the "Không đủ quyền" fallback both used to send staff to
  // /overview, which requires class-wide STUDIO:VIEW — a staff member who only holds e.g.
  // EXAM:CREATE scoped to one course would land there and immediately hit "insufficient
  // permission" with nowhere reachable to go. Route them to the first nav item they are actually
  // authorized for instead; only fall back to overview if literally nothing is authorized (should
  // not happen for real STAFF, but keeps this well-defined for an edge case / future nav entry).
  const firstAuthorizedItem = navItems.find(isItemAuthorized);
  const firstAuthorizedPath = firstAuthorizedItem?.path ?? `/studio/classes/${id}/overview`;

  // R7-01: the Studio index route (bare /studio/classes/:id, no sub-path) used to hardcode
  // Navigate("overview") in App.tsx — sending every entrant through a page that requires
  // class-wide STUDIO:VIEW. Redirect to the first page this user is actually authorized for
  // instead, computed the same way as the nav list and the "insufficient permission" fallback.
  const isIndexRoute = location.pathname === `/studio/classes/${id}` || location.pathname === `/studio/classes/${id}/`;
  if (isIndexRoute) {
    return <Navigate to={firstAuthorizedPath} replace />;
  }

  return (
    <div className="min-h-screen bg-slate-100 flex flex-col md:flex-row">
      {/* Studio Sidebar */}
      <aside className="w-full md:w-64 bg-slate-900 text-white flex-shrink-0 flex flex-col justify-between">
        <div>
          {/* Header */}
          <div className="p-4 md:p-5 border-b border-slate-800">
            <div className="flex items-center justify-between gap-3 mb-2 md:mb-3">
              <Link
                to={`/classes/${classroom.slug}/feed`}
                className="inline-flex items-center space-x-1.5 text-xs text-indigo-400 hover:text-indigo-300 font-bold transition flex-shrink-0"
              >
                <ArrowLeft className="w-3.5 h-3.5" />
                <span>Trở về lớp học</span>
              </Link>
              <button
                type="button"
                onClick={() => setMenuOpen((open) => !open)}
                aria-expanded={menuOpen}
                aria-controls={navId}
                className="md:hidden inline-flex min-w-0 items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-xs font-bold text-slate-100 transition"
              >
                {menuOpen ? <X className="w-4 h-4 flex-shrink-0" /> : <Menu className="w-4 h-4 flex-shrink-0" />}
                <span className="truncate">{`Menu Studio${activeNavItem && isAuthorized ? ` · ${activeNavItem.label}` : ''}`}</span>
              </button>
            </div>
            <h2 className="text-base font-extrabold tracking-tight truncate">{classroom.title}</h2>
            <div className="flex items-center space-x-2 mt-1">
              <span className="text-[10px] px-2 py-0.5 rounded font-mono bg-indigo-500/20 text-indigo-300 font-bold">
                STUDIO • {classroom.userRole}
              </span>
            </div>
            {/* D-19: visibility + fee of the class at a glance, on every Studio page */}
            <ClassBadges classroom={classroom} showPublic className="mt-2" />
          </div>

          {/* Navigation Links */}
          <nav id={navId} aria-label="Điều hướng Studio" className={`${menuOpen ? 'block' : 'hidden'} md:block p-3 space-y-1`}>
            {navItems.filter(isItemAuthorized).map((item) => {
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

        <div className="hidden md:block p-4 border-t border-slate-800 text-[11px] text-slate-400">
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
              {/* R8-10: when literally nothing in Studio is authorized for this staff member,
                  firstAuthorizedPath falls back to /overview — which they also cannot reach, making
                  this a dead end. Send them back to the classroom itself instead, which every member
                  can always reach. */}
              <Link
                to={firstAuthorizedItem ? firstAuthorizedPath : `/classes/${classroom.slug}/feed`}
                className="inline-flex items-center space-x-1.5 px-4 py-2 bg-slate-900 hover:bg-slate-800 text-white rounded-xl text-xs font-bold transition"
              >
                <span>{firstAuthorizedItem ? `Về ${firstAuthorizedItem.label}` : 'Về trang lớp học'}</span>
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
