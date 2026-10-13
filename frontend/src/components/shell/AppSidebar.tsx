import React, { useEffect, useState } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { BookOpen, CalendarCheck, Compass, LogIn, LogOut, School, ShieldCheck, ShieldHalf, UserRound } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { isPlatformAdmin } from '../../api/admin';
import { listMyClasses } from '../../api/me';
import type { Classroom } from '../../types';
import { ClassAvatar } from '../ui';

const cx = (...parts: Array<string | false | null | undefined>) => parts.filter(Boolean).join(' ');

export const BRAND = 'Lớp Học Trực Tuyến';

/** How many joined classes the "Lớp đang học" group lists. */
export const SIDEBAR_CLASS_LIMIT = 6;

const MAIN_LINKS: { to: string; label: string; Icon: LucideIcon }[] = [
  { to: '/classes', label: 'Khám phá lớp học', Icon: Compass },
  { to: '/me/classes', label: 'Lớp học của tôi', Icon: School },
  { to: '/me/courses', label: 'Khóa học của tôi', Icon: BookOpen },
  { to: '/me/events', label: 'Sự kiện tham gia', Icon: CalendarCheck },
];

const ROW = 'flex min-h-[38px] w-full items-center gap-3 rounded-[10px] px-2.5 text-left text-[13.5px] leading-[18px] transition-colors duration-micro';
const rowClass = (active: boolean) =>
  cx(ROW, active ? 'bg-blue-50 font-semibold text-blue-700' : 'font-medium text-slate-700 hover:bg-slate-100 hover:text-slate-900');

const Group: React.FC<{ title: string }> = ({ title }) => (
  <p className="px-2.5 pb-1.5 pt-3.5 text-micro font-semibold uppercase tracking-[0.8px] text-slate-500">{title}</p>
);

/** The prototype's wordmark: brand name over a small spaced eyebrow. */
export const Wordmark: React.FC<{ eyebrow: string; dark?: boolean }> = ({ eyebrow, dark = false }) => (
  <span className="flex min-w-0 flex-col">
    <span className={cx('whitespace-nowrap text-[14.5px] font-bold leading-[18px] tracking-[-0.2px]', dark ? 'text-white' : 'text-slate-900')}>{BRAND}</span>
    <span className={cx('whitespace-nowrap text-[9.5px] font-semibold leading-3 tracking-[1.2px]', dark ? 'text-slate-300' : 'text-slate-500')}>{eyebrow}</span>
  </span>
);

/**
 * D-35 desktop app sidebar (home and class areas, `lg` and up): wordmark, the main menu, the classes the person is in and
 * their account links. Phones use the top bar's account menu and the bottom tab bar instead.
 */
export const AppSidebar: React.FC = () => {
  const { user, logout } = useAuth();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const [classes, setClasses] = useState<Classroom[]>([]);

  useEffect(() => {
    if (!user) {
      setClasses([]);
      return;
    }
    let cancelled = false;
    listMyClasses(0, SIDEBAR_CLASS_LIMIT)
      .then((rows) => { if (!cancelled) setClasses(Array.isArray(rows) ? rows.slice(0, SIDEBAR_CLASS_LIMIT) : []); })
      .catch(() => { if (!cancelled) setClasses([]); });
    return () => { cancelled = true; };
  }, [user?.id]);

  const currentSlug = /^\/classes\/([^/]+)/.exec(pathname)?.[1];

  const handleLogout = () => {
    void logout();
    navigate('/login');
  };

  return (
    <aside
      data-testid="app-sidebar"
      className="sticky top-0 z-[35] hidden h-screen w-[248px] flex-none flex-col border-r border-line bg-white lg:flex"
    >
      <div className="flex h-16 flex-none items-center px-[18px]">
        <Link to="/classes" aria-label={`${BRAND} - trang khám phá`} className="min-w-0">
          <Wordmark eyebrow="KHÁM PHÁ · HỌC · THI" />
        </Link>
      </div>

      <nav aria-label="Điều hướng chính" className="flex min-h-0 flex-1 flex-col gap-0.5 overflow-y-auto px-3 pb-4 pt-1 scrollbar-none">
        <Group title="Menu chính" />
        {MAIN_LINKS.map(({ to, label, Icon }) => (
          <NavLink key={to} to={to} end className={({ isActive }) => rowClass(isActive)}>
            <Icon className="h-[18px] w-[18px] flex-shrink-0" strokeWidth={1.8} aria-hidden="true" />
            <span className="min-w-0 flex-1 truncate">{label}</span>
          </NavLink>
        ))}

        {user && classes.length > 0 && (
          <>
            <Group title="Lớp đang học" />
            {classes.map((c) => (
              <Link
                key={c.id}
                to={`/classes/${c.slug}`}
                aria-current={currentSlug === c.slug ? 'page' : undefined}
                className={rowClass(currentSlug === c.slug)}
              >
                <ClassAvatar title={c.title} seed={c.id} src={c.avatarUrl} size={20} className="!rounded-md !text-[10px]" />
                <span className="min-w-0 flex-1 truncate">{c.title}</span>
              </Link>
            ))}
          </>
        )}

        <Group title="Tài khoản" />
        {user ? (
          <>
            <NavLink to="/me/profile" end className={({ isActive }) => rowClass(isActive)}>
              <UserRound className="h-[18px] w-[18px] flex-shrink-0" strokeWidth={1.8} aria-hidden="true" />
              <span className="min-w-0 flex-1 truncate">Hồ sơ của tôi</span>
            </NavLink>
            <NavLink to="/privacy" end className={({ isActive }) => rowClass(isActive)}>
              <ShieldCheck className="h-[18px] w-[18px] flex-shrink-0" strokeWidth={1.8} aria-hidden="true" />
              <span className="min-w-0 flex-1 truncate">Quyền riêng tư &amp; dữ liệu</span>
            </NavLink>
            {isPlatformAdmin(user) && (
              <NavLink to="/admin" className={({ isActive }) => rowClass(isActive)}>
                <ShieldHalf className="h-[18px] w-[18px] flex-shrink-0" strokeWidth={1.8} aria-hidden="true" />
                <span className="min-w-0 flex-1 truncate">Quản trị nền tảng</span>
              </NavLink>
            )}
          </>
        ) : (
          <Link to="/login" className={rowClass(false)}>
            <LogIn className="h-[18px] w-[18px] flex-shrink-0" strokeWidth={1.8} aria-hidden="true" />
            <span className="min-w-0 flex-1 truncate">Đăng nhập</span>
          </Link>
        )}
      </nav>

      {user && (
        <div className="flex-none border-t border-line px-3 py-2.5">
          <button type="button" onClick={handleLogout} className={rowClass(false)}>
            <LogOut className="h-[18px] w-[18px] flex-shrink-0" strokeWidth={1.8} aria-hidden="true" />
            <span className="min-w-0 flex-1 truncate">Đăng xuất</span>
          </button>
        </div>
      )}
    </aside>
  );
};
