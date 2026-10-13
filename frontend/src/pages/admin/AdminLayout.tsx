import React, { useEffect, useId, useState } from 'react';
import { NavLink, Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { ArrowLeft, FileLock2, LayoutDashboard, Menu, School, ScrollText, Users, X } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { isPlatformAdmin } from '../../api/admin';
import { ForbiddenState } from '../../components/UIStates';
import { Avatar } from '../../components/ui';

type NavItem = { label: string; icon: LucideIcon; path: string };

const NAV_GROUPS: { label: string | null; items: NavItem[] }[] = [
  { label: null, items: [{ label: 'Tổng quan', icon: LayoutDashboard, path: '/admin/overview' }] },
  {
    label: 'Quản lý',
    items: [
      { label: 'Người dùng', icon: Users, path: '/admin/users' },
      { label: 'Lớp học', icon: School, path: '/admin/classes' },
    ],
  },
  {
    label: 'Tuân thủ',
    items: [
      { label: 'Yêu cầu dữ liệu', icon: FileLock2, path: '/admin/privacy' },
      { label: 'Nhật ký', icon: ScrollText, path: '/admin/audit' },
    ],
  },
];
const NAV_ITEMS = NAV_GROUPS.flatMap((group) => group.items);

/**
 * Shell of "Quản trị nền tảng" (/admin/*): the white 248px grouped sidebar of the Studio ("Xưởng", Dashboard.dc.html),
 * collapsible below md. Rendered inside RequireLogin (guests go to /login); a signed-in person without the
 * PLATFORM_ADMIN role gets a calm ForbiddenState - the server enforces the same rule on every /admin route.
 */
export const AdminLayout: React.FC = () => {
  const { user } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);
  const navId = useId();

  useEffect(() => {
    setMenuOpen(false);
  }, [location.pathname]);

  if (!isPlatformAdmin(user)) {
    return (
      <div className="mx-auto w-full max-w-[560px] px-4 py-12">
        <ForbiddenState
          title="Khu vực dành cho quản trị nền tảng"
          message="Tài khoản của bạn không có quyền quản trị nền tảng. Nếu bạn cần quyền này, hãy liên hệ quản trị viên của hệ thống."
          actionText="Về danh sách lớp"
          onAction={() => navigate('/classes')}
        />
      </div>
    );
  }

  const active = NAV_ITEMS.find((item) => location.pathname === item.path || location.pathname.startsWith(`${item.path}/`));

  return (
    <div className="min-h-[calc(100vh-4rem)] bg-slate-50 md:grid md:grid-cols-[248px_minmax(0,1fr)]">
      {/* D-35: the platform-admin sidebar is the prototype's dark ink-900 variant. */}
      <aside className="flex flex-col bg-slate-900 text-white md:sticky md:top-16 md:h-[calc(100vh-4rem)]">
        <div className="px-3 pb-3 pt-4">
          <div className="flex items-center justify-between gap-3 px-1.5 md:mb-2.5">
            <span className="whitespace-nowrap text-[10.5px] font-bold leading-[14px] tracking-[1.2px] text-slate-300">
              QUẢN TRỊ NỀN TẢNG
            </span>
            <button
              type="button"
              onClick={() => setMenuOpen((open) => !open)}
              aria-expanded={menuOpen}
              aria-controls={navId}
              className="inline-flex h-10 min-w-0 items-center gap-2 rounded-btn border border-white/15 bg-white/5 px-3 text-meta font-semibold text-white transition-colors duration-micro hover:bg-white/10 md:hidden"
            >
              {menuOpen ? <X className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} /> : <Menu className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} />}
              <span className="truncate">{`Menu${active ? ` · ${active.label}` : ''}`}</span>
            </button>
          </div>
          {/* Who is acting: a person (round avatar) with the platform role. */}
          <div className="mt-3 flex items-center gap-2.5 rounded-community border border-white/10 bg-white/5 px-3 py-2.5 md:mt-0">
            <Avatar name={user?.fullName} src={user?.avatarUrl} size={34} />
            <div className="min-w-0 flex-1">
              <p className="truncate text-[13.5px] font-semibold leading-[18px] text-white">{user?.fullName}</p>
              <p className="truncate text-caption text-slate-300">Quản trị nền tảng</p>
            </div>
          </div>
        </div>

        <div id={navId} className={`${menuOpen ? 'block' : 'hidden'} md:block md:min-h-0 md:flex-1`}>
          <div className="md:flex md:h-full md:flex-col">
            <nav aria-label="Điều hướng quản trị nền tảng" className="px-3 pb-3 md:flex-1 md:overflow-y-auto">
              {NAV_GROUPS.map((group, groupIndex) => (
                <div key={group.label ?? 'root'} className={groupIndex > 0 ? 'mt-1' : ''}>
                  {group.label && (
                    <p className="px-2.5 pb-1.5 pt-3.5 text-micro font-semibold uppercase tracking-[0.8px] text-slate-300">{group.label}</p>
                  )}
                  <ul>
                    {group.items.map((item) => {
                      const Icon = item.icon;
                      return (
                        <li key={item.path}>
                          <NavLink
                            to={item.path}
                            className={({ isActive }) =>
                              `flex h-10 items-center gap-3 rounded-[10px] px-2.5 text-[13.5px] leading-[18px] transition-colors duration-micro md:h-[38px] ${
                                isActive
                                  ? 'bg-white/10 font-semibold text-white'
                                  : 'font-medium text-slate-300 hover:bg-white/5 hover:text-white'
                              }`
                            }
                          >
                            <Icon className="h-[18px] w-[18px] flex-shrink-0" strokeWidth={1.75} aria-hidden="true" />
                            <span className="truncate">{item.label}</span>
                          </NavLink>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              ))}
            </nav>

            <div className="border-t border-white/10 px-3 py-2.5">
              <Link
                to="/classes"
                className="flex min-h-[38px] items-center gap-3 rounded-[10px] px-2.5 text-[13.5px] font-medium text-slate-300 transition-colors duration-micro hover:bg-white/5 hover:text-white"
              >
                <ArrowLeft className="h-[18px] w-[18px]" strokeWidth={1.8} aria-hidden="true" />
                <span>Về trang lớp học</span>
              </Link>
            </div>
          </div>
        </div>
      </aside>

      <main className="min-w-0 px-4 pb-24 pt-6 sm:px-8 sm:pt-8">
        <Outlet />
      </main>
    </div>
  );
};
