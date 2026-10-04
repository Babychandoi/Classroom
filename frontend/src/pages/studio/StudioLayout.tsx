import React, { useEffect, useId, useRef, useState } from 'react';
import { useParams, Outlet, NavLink, Link, Navigate, useNavigate, useLocation } from 'react-router-dom';
import { Classroom } from '../../types';
import { api, ApiException } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { hasAnyStudioPermission } from '../../api/permissions';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { ClassBadges } from '../../components/ClassBadges';
import { SuspendedChip, SuspendedNotice, isSuspended } from '../../components/SuspendedNotice';
import { ClassAvatar, buttonClass } from '../../components/ui';
import {
  LayoutDashboard,
  BookOpen,
  Award,
  CheckSquare,
  Users,
  Layers,
  ShoppingBag,
  ShieldAlert,
  MessageSquare,
  FileText,
  Info,
  Trophy,
  UserCog,
  Settings,
  Menu,
  X,
  Eye,
  PenSquare,
  CalendarDays,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

type NavItem = { label: string; icon: LucideIcon; path: string; grants: string[]; badge?: number };
/** Create-class contract fields read here (typed locally until the shared Classroom type has them). */
type ClassroomExtras = { avatarUrl?: string | null; avatarPosition?: string | null; pendingRequestCount?: number };

export const StudioLayout: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const { user } = useAuth();
  const navigate = useNavigate();
  // R7-05: useLocation is a hook and must run unconditionally on every render, so it has to sit
  // above the loading/error early returns below (a hook that only sometimes runs breaks React's
  // hook-order invariant and desyncs state on the very next render that takes the other branch).
  const location = useLocation();

  const [classroom, setClassroom] = useState<(Classroom & ClassroomExtras) | null>(null);
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

  // The desktop nav scrolls on its own inside the sticky sidebar; keep the current page's entry in view
  // (e.g. "Cài đặt" at the bottom of a short window).
  const navScrollRef = useRef<HTMLElement>(null);
  useEffect(() => {
    const active = navScrollRef.current?.querySelector<HTMLElement>('[aria-current="page"]');
    active?.scrollIntoView?.({ block: 'nearest' });
  }, [location.pathname, classroom]);

  // Grouped like the "Xưởng" sidebar of Dashboard.dc.html. `grants` gating is unchanged: an item is shown (and its
  // route reachable) when the viewer holds ANY of its grants (see isItemAuthorized below).
  const navGroups: { label: string | null; items: NavItem[] }[] = [
    {
      label: null,
      items: [{ label: 'Tổng quan', icon: LayoutDashboard, path: `/studio/classes/${id}/overview`, grants: ['STUDIO:VIEW'] }],
    },
    {
      label: 'Nội dung',
      items: [
        { label: 'Khóa học', icon: BookOpen, path: `/studio/classes/${id}/courses`, grants: ['COURSE:VIEW', 'COURSE:CREATE', 'COURSE:EDIT'] },
        { label: 'Thi', icon: Award, path: `/studio/classes/${id}/exams`, grants: ['EXAM:VIEW', 'EXAM:CREATE', 'EXAM:EDIT', 'EXAM:PUBLISH'] },
        // R11-02: this nav entry covers both exam grading (EXAM:GRADE) and assignment grading
        // (COURSE:GRADE, course-scopable) - either grant alone must make it reachable, matching what
        // StudioGrading itself gates each of its two sections on.
        { label: 'Chấm bài', icon: CheckSquare, path: `/studio/classes/${id}/grading`, grants: ['EXAM:GRADE', 'COURSE:GRADE'] },
        { label: 'Blog', icon: PenSquare, path: `/studio/classes/${id}/blog`, grants: ['BLOG:VIEW', 'BLOG:CREATE', 'BLOG:EDIT', 'BLOG:PUBLISH', 'BLOG:DELETE'] },
        { label: 'Sự kiện', icon: CalendarDays, path: `/studio/classes/${id}/events`, grants: ['EVENT:VIEW', 'EVENT:CREATE', 'EVENT:EDIT', 'EVENT:DELETE'] },
        { label: 'Tài liệu', icon: FileText, path: `/studio/classes/${id}/documents`, grants: ['DOCUMENT:VIEW', 'DOCUMENT:CREATE', 'DOCUMENT:EDIT'] },
        { label: 'Bảng tin', icon: MessageSquare, path: `/studio/classes/${id}/feed`, grants: ['FEED:VIEW', 'FEED:CREATE', 'FEED:EDIT'] },
        { label: 'Giới thiệu', icon: Info, path: `/studio/classes/${id}/about`, grants: ['CLASS:VIEW', 'ABOUT:EDIT'] },
      ],
    },
    {
      label: 'Thành viên & bán hàng',
      items: [
        // pendingRequestCount is only filled for MEMBER:VIEW holders (0 otherwise), so the badge never leaks the count.
        { label: 'Thành viên', icon: UserCog, path: `/studio/classes/${id}/members`, grants: ['MEMBER:VIEW', 'MEMBER:EDIT'], badge: classroom?.pendingRequestCount ?? 0 },
        { label: 'Trợ giảng', icon: Users, path: `/studio/classes/${id}/staff`, grants: ['STAFF:VIEW'] },
        { label: 'Nhóm học viên', icon: Layers, path: `/studio/classes/${id}/segments`, grants: ['SEGMENT:VIEW', 'SEGMENT:CREATE', 'SEGMENT:EDIT'] },
        { label: 'Shop & đơn hàng', icon: ShoppingBag, path: `/studio/classes/${id}/store`, grants: ['STORE:VIEW', 'STORE:CREATE', 'STORE:EDIT', 'STORE:PUBLISH'] },
        { label: 'Bảng xếp hạng', icon: Trophy, path: `/studio/classes/${id}/leaderboard`, grants: ['LEADERBOARD:EDIT'] },
      ],
    },
    {
      label: 'Hệ thống',
      items: [
        { label: 'Cài đặt', icon: Settings, path: `/studio/classes/${id}/settings`, grants: ['CLASS:VIEW', 'CLASS:EDIT'] },
        { label: 'Nhật ký', icon: ShieldAlert, path: `/studio/classes/${id}/audit`, grants: ['AUDIT:VIEW'] },
      ],
    },
  ];
  const navItems = navGroups.flatMap((group) => group.items);

  if (loading) return <LoadingSpinner message="Đang mở Xưởng..." />;
  if (error || !classroom) {
    return (
      <div className="mx-auto max-w-2xl px-4 py-12">
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

  const roleLabel = classroom.userRole === 'OWNER' ? 'Chủ lớp' : 'Trợ giảng';
  const visibleGroups = navGroups
    .map((group) => ({ ...group, items: group.items.filter(isItemAuthorized) }))
    .filter((group) => group.items.length > 0);

  return (
    <div className="min-h-[calc(100vh-4rem)] bg-slate-50 md:grid md:grid-cols-[248px_minmax(0,1fr)]">
      {/* Studio ("Xưởng") sidebar - white, hairline right border (Dashboard.dc.html) */}
      <aside className="flex flex-col border-b border-slate-200 bg-white md:sticky md:top-16 md:h-[calc(100vh-4rem)] md:border-b-0 md:border-r">
        <div className="px-4 pb-3 pt-4">
          <div className="flex items-center justify-between gap-3 px-1 md:mb-2">
            <span className="inline-flex h-5 items-center rounded-full bg-slate-100 px-2 text-micro font-bold tracking-[0.4px] text-slate-600">
              XƯỞNG
            </span>
            <button
              type="button"
              onClick={() => setMenuOpen((open) => !open)}
              aria-expanded={menuOpen}
              aria-controls={navId}
              className="inline-flex h-10 min-w-0 items-center gap-2 rounded-btn border border-slate-200 bg-white px-3 text-meta font-semibold text-slate-900 transition-colors duration-micro hover:bg-slate-100 md:hidden"
            >
              {menuOpen ? <X className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} /> : <Menu className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} />}
              <span className="truncate">{`Menu Studio${activeNavItem && isAuthorized ? ` · ${activeNavItem.label}` : ''}`}</span>
              {(classroom.pendingRequestCount ?? 0) > 0 && navItems.some((item) => item.badge && isItemAuthorized(item)) && (
                <span className="inline-flex h-5 min-w-[20px] flex-shrink-0 items-center justify-center rounded-full bg-amber-100 px-1.5 text-micro font-bold text-amber-800 tabular" aria-label={`${classroom.pendingRequestCount} yêu cầu chờ duyệt`}>
                  {classroom.pendingRequestCount}
                </span>
              )}
            </button>
          </div>
          {/* Class card: class avatar (rounded square - never round) + title + the viewer's role */}
          <div className="mt-3 flex items-center gap-2.5 rounded-community border border-slate-200 bg-slate-50 px-3 py-2 md:mt-0">
            {/* The class's own square avatar when it has one (never a crop of the 16:9 cover), else the letter tile. */}
            {classroom.avatarUrl ? (
              <img
                src={classroom.avatarUrl as string}
                alt=""
                data-testid="studio-class-avatar"
                className="h-8 w-8 flex-shrink-0 rounded-[10px] object-cover"
                style={{ objectPosition: classroom.avatarPosition || '50% 50%' }}
              />
            ) : (
              <ClassAvatar title={classroom.title} seed={classroom.id} size={32} />
            )}
            <div className="min-w-0 flex-1">
              <p className="truncate text-meta font-semibold text-slate-900">{classroom.title}</p>
              <p className="text-micro leading-[15px] text-slate-500">{roleLabel}</p>
            </div>
          </div>
          {/* D-19: visibility + fee of the class at a glance, on every Studio page */}
          <div className="mt-2 flex flex-wrap items-center gap-1.5 px-1">
            {isSuspended(classroom) && <SuspendedChip />}
            <ClassBadges classroom={classroom} showPublic />
          </div>
        </div>

        <div id={navId} className={`${menuOpen ? 'block' : 'hidden'} md:block md:min-h-0 md:flex-1`}>
          <div className="md:flex md:h-full md:flex-col">
          <nav ref={navScrollRef} aria-label="Điều hướng Studio" className="px-3 pb-3 md:flex-1 md:overflow-y-auto">
            {visibleGroups.map((group, groupIndex) => (
              <div key={group.label ?? 'root'} className={groupIndex > 0 ? 'mt-2.5' : ''}>
                {group.label && (
                  <p className="mb-0.5 px-3 text-micro font-semibold uppercase tracking-[0.6px] text-slate-500">{group.label}</p>
                )}
                <ul>
                  {group.items.map((item) => {
                    const Icon = item.icon;
                    return (
                      <li key={item.path}>
                        <NavLink
                          to={item.path}
                          className={({ isActive }) =>
                            `flex h-10 items-center gap-[11px] md:h-9 rounded-btn px-3 text-ui transition-colors duration-micro ${
                              isActive
                                ? 'bg-tint font-semibold text-blue-600'
                                : 'font-medium text-slate-600 hover:bg-slate-100 hover:text-slate-900'
                            }`
                          }
                        >
                          <Icon className="h-[18px] w-[18px] flex-shrink-0" strokeWidth={1.75} aria-hidden="true" />
                          <span className="truncate">{item.label}</span>
                          {!!item.badge && item.badge > 0 && (
                            <span
                              className="ml-auto inline-flex h-5 min-w-[20px] items-center justify-center rounded-full bg-amber-100 px-1.5 text-micro font-bold text-amber-800 tabular"
                              aria-label={`${item.badge} yêu cầu chờ duyệt`}
                              title={`${item.badge} yêu cầu chờ duyệt`}
                            >
                              {item.badge > 99 ? '99+' : item.badge}
                            </span>
                          )}
                        </NavLink>
                      </li>
                    );
                  })}
                </ul>
              </div>
            ))}
          </nav>

          <div className="border-t border-slate-100 px-4 py-3">
            <Link
              to={`/classes/${classroom.slug}`}
              className="inline-flex items-center gap-2 text-meta font-medium text-slate-600 transition-colors duration-micro hover:text-slate-900"
            >
              <Eye className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
              <span>Xem như học viên</span>
            </Link>
          </div>
          </div>
        </div>
      </aside>

      {/* Main Studio work area */}
      <main className="min-w-0 px-4 pb-24 pt-6 sm:px-8 sm:pt-8">
        {isSuspended(classroom) && (
          <div className="mx-auto mb-6 w-full max-w-[1080px]">
            <SuspendedNotice classroom={classroom} />
          </div>
        )}
        {!isAuthorized ? (
          <div className="mx-auto max-w-[560px] py-12">
            <div className="rounded-card border border-slate-200 bg-white p-8 text-center shadow-hairline">
              <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-community bg-red-50 text-red-700">
                <ShieldAlert className="h-6 w-6" strokeWidth={1.75} aria-hidden="true" />
              </div>
              <h3 className="mb-1 text-h3-lg font-semibold text-slate-900">Không đủ quyền truy cập</h3>
              <p className="mb-6 text-ui text-slate-600">
                Tài khoản trợ giảng của bạn chưa được cấp quyền vào khu vực này của Xưởng. Hãy nhờ chủ lớp cấp thêm quyền nếu bạn cần.
              </p>
              {/* R8-10: when literally nothing in Studio is authorized for this staff member,
                  firstAuthorizedPath falls back to /overview — which they also cannot reach, making
                  this a dead end. Send them back to the classroom itself instead, which every member
                  can always reach. */}
              <Link
                to={firstAuthorizedItem ? firstAuthorizedPath : `/classes/${classroom.slug}/feed`}
                className={buttonClass('secondary', 'md')}
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
