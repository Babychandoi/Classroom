import React, { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { GraduationCap, UserCheck, LogOut, ChevronDown, ChevronRight, Compass, ShieldCheck, UserRound } from 'lucide-react';
import { Avatar } from './ui';

const DEMO_ACCOUNTS = [
  { label: 'Thầy Chủ Nhiệm (OWNER)', email: 'owner@classroom.local', role: 'OWNER' },
  { label: 'Trợ Giảng (STAFF)', email: 'staff@classroom.local', role: 'STAFF' },
  { label: 'Học Viên FREE', email: 'student.free@classroom.local', role: 'FREE' },
  { label: 'Học Viên VIP (PRO)', email: 'student.pro@classroom.local', role: 'PRO' },
  { label: 'Học Viên HẾT HẠN', email: 'student.expired@classroom.local', role: 'EXPIRED' },
];

/** Closes a popover on Escape or on a click outside `ref`. */
function useDismiss(open: boolean, close: () => void, ref: React.RefObject<HTMLElement>) {
  useEffect(() => {
    if (!open) return;
    const onPointer = (event: MouseEvent) => {
      if (ref.current && !ref.current.contains(event.target as Node)) close();
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') close();
    };
    document.addEventListener('mousedown', onPointer);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
  }, [open, close, ref]);
}

const MENU_ROW = 'flex h-12 w-full items-center gap-3 rounded-btn px-2 text-left text-ui font-medium text-slate-900 transition-colors duration-micro hover:bg-slate-100';
const MENU_ICON = 'inline-flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-full bg-slate-100 text-slate-900';

// Sticky top bar of the design (glass, 64px, 1200 container): brand mark + "Lớp Học Trực Tuyến", one nav link,
// then the demo switcher (VITE_ENABLE_DEMO_LOGIN) and the account avatar that opens the account menu.
export const Navbar: React.FC = () => {
  const { user, isLoading, logout, quickLogin } = useAuth();
  const navigate = useNavigate();
  const [showDemoMenu, setShowDemoMenu] = useState(false);
  const [showAccountMenu, setShowAccountMenu] = useState(false);
  const demoLoginEnabled = import.meta.env.VITE_ENABLE_DEMO_LOGIN === 'true';
  const demoRef = useRef<HTMLDivElement>(null);
  const accountRef = useRef<HTMLDivElement>(null);
  const closeDemo = React.useCallback(() => setShowDemoMenu(false), []);
  const closeAccount = React.useCallback(() => setShowAccountMenu(false), []);
  useDismiss(showDemoMenu, closeDemo, demoRef);
  useDismiss(showAccountMenu, closeAccount, accountRef);

  const handleSelectDemo = async (email: string) => {
    setShowDemoMenu(false);
    await quickLogin(email);
  };

  const handleLogout = () => {
    setShowAccountMenu(false);
    void logout();
    navigate('/login');
  };

  return (
    <header className="glass-bar sticky top-0 z-50 border-b border-slate-200">
      <div className="mx-auto flex h-16 w-full max-w-container items-center gap-4 px-4 sm:gap-8 sm:px-8">
        {/* R17-05: the brand stays on one line (it used to wrap to 3 lines and spill out of the
            h-16 bar at phone width) and truncates rather than pushing the account controls out. */}
        <Link to="/classes" className="flex min-w-0 items-center gap-2.5 text-slate-900" aria-label="Lớp Học Trực Tuyến - về trang chủ">
          <span className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-[10px] bg-gradient-to-tr from-blue-600 to-sky-400 text-white">
            <GraduationCap className="h-5 w-5" strokeWidth={1.9} aria-hidden="true" />
          </span>
          <span className="truncate whitespace-nowrap text-base font-bold leading-6 tracking-[-0.4px] text-slate-900 sm:text-[19px]">
            Lớp Học Trực Tuyến
          </span>
        </Link>

        {/* The brand already links to the class list, so the duplicate text link is desktop-only. */}
        <nav aria-label="Điều hướng chính" className="hidden items-center gap-7 sm:flex">
          <Link
            to="/classes"
            className="hidden whitespace-nowrap text-ui font-medium text-slate-600 transition-colors duration-micro hover:text-slate-900 sm:inline-block"
          >
            Khám phá lớp học
          </Link>
        </nav>

        <div className="flex-1" />

        <div className="flex flex-shrink-0 items-center gap-2 sm:gap-3">
          {/* Quick Demo Switcher */}
          {demoLoginEnabled && (
            <div className="relative" ref={demoRef}>
              <button
                type="button"
                onClick={() => setShowDemoMenu(!showDemoMenu)}
                aria-expanded={showDemoMenu}
                aria-haspopup="true"
                className="press inline-flex h-9 items-center gap-1.5 rounded-[10px] border border-slate-200 bg-white px-2.5 text-meta font-medium text-slate-600 transition-colors duration-micro hover:bg-slate-100 hover:text-slate-900"
                title="Chuyển đổi nhanh giữa các tài khoản demo kiểm thử"
              >
                <UserCheck className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                <span className="hidden lg:inline">Tài khoản demo:</span>
                <span className="hidden max-w-[120px] truncate font-semibold text-slate-900 sm:inline">{user ? user.email.split('@')[0] : 'Chọn demo'}</span>
                <span className="sr-only sm:hidden">Tài khoản demo</span>
                <ChevronDown className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />
              </button>

              {showDemoMenu && (
                <div className="absolute right-0 z-50 mt-2 w-72 rounded-card border border-slate-200 bg-white p-2 shadow-modal">
                  <p className="px-2.5 pb-1.5 pt-1 text-micro font-bold uppercase tracking-[0.4px] text-slate-500">
                    Chuyển vai trò thử nghiệm
                  </p>
                  {DEMO_ACCOUNTS.map((acc) => {
                    const current = user?.email === acc.email;
                    return (
                      <button
                        type="button"
                        key={acc.email}
                        onClick={() => handleSelectDemo(acc.email)}
                        aria-current={current ? 'true' : undefined}
                        className={`flex h-11 w-full items-center justify-between gap-3 rounded-[10px] px-2.5 text-left text-meta transition-colors duration-micro ${
                          current ? 'bg-tint font-semibold text-blue-700' : 'font-medium text-slate-900 hover:bg-slate-100'
                        }`}
                      >
                        <span className="truncate">{acc.label}</span>
                        <span className="inline-flex h-[18px] flex-shrink-0 items-center rounded-full bg-slate-100 px-1.5 text-micro-xs font-bold uppercase tracking-[0.3px] text-slate-600">
                          {acc.role}
                        </span>
                      </button>
                    );
                  })}
                </div>
              )}
            </div>
          )}

          {isLoading ? (
            // R17-01: while the silent session restore is in flight `user` is still null even for a
            // signed-in person - rendering "Đăng nhập" now would flash the wrong state and invite a
            // click that races the bootstrap. Reserve the space instead.
            <div aria-hidden="true" className="h-9 w-24" />
          ) : user ? (
            <div className="relative" ref={accountRef}>
              <button
                type="button"
                onClick={() => setShowAccountMenu(!showAccountMenu)}
                aria-haspopup="menu"
                aria-expanded={showAccountMenu}
                aria-label="Mở menu tài khoản"
                title={user.fullName}
                className="inline-flex h-10 w-10 items-center justify-center rounded-full transition-colors duration-micro hover:bg-slate-100"
              >
                <Avatar name={user.fullName} src={user.avatarUrl} size={32} />
              </button>

              {showAccountMenu && (
                <div
                  role="menu"
                  aria-label="Menu tài khoản"
                  className="absolute right-0 z-50 mt-2 w-[min(360px,calc(100vw-32px))] overflow-hidden rounded-card border border-slate-200 bg-white shadow-modal"
                >
                  <div className="m-3 rounded-2xl border border-slate-200 p-3.5 shadow-hairline">
                    <div className="flex items-center gap-3">
                      <Avatar name={user.fullName} src={user.avatarUrl} size={44} />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-body-sm font-semibold text-slate-900">{user.fullName}</span>
                        <span className="mt-px block truncate text-caption text-slate-500">{user.email}</span>
                      </span>
                    </div>
                    <Link
                      to="/me/profile"
                      role="menuitem"
                      onClick={closeAccount}
                      className="mt-3 flex h-[38px] items-center justify-center gap-2 rounded-[10px] bg-slate-100 text-meta font-semibold text-slate-900 transition-colors duration-micro hover:bg-slate-200"
                    >
                      <UserRound className="h-4 w-4" strokeWidth={1.8} aria-hidden="true" />
                      Hồ sơ của tôi
                    </Link>
                  </div>

                  <div className="px-3 pb-1.5 pt-0.5">
                    <Link to="/classes" role="menuitem" onClick={closeAccount} className={MENU_ROW}>
                      <span className={MENU_ICON} aria-hidden="true"><Compass className="h-[17px] w-[17px]" strokeWidth={1.7} /></span>
                      <span className="min-w-0 flex-1">Khám phá lớp học</span>
                      <ChevronRight className="h-3.5 w-3.5 text-slate-400" strokeWidth={2} aria-hidden="true" />
                    </Link>
                    <Link to="/privacy" role="menuitem" onClick={closeAccount} className={MENU_ROW}>
                      <span className={MENU_ICON} aria-hidden="true"><ShieldCheck className="h-[17px] w-[17px]" strokeWidth={1.7} /></span>
                      <span className="min-w-0 flex-1">Quyền riêng tư &amp; dữ liệu</span>
                      <ChevronRight className="h-3.5 w-3.5 text-slate-400" strokeWidth={2} aria-hidden="true" />
                    </Link>
                  </div>

                  <div aria-hidden="true" className="mx-5 h-px bg-slate-100" />

                  <div className="px-3 py-1.5">
                    <button type="button" role="menuitem" onClick={handleLogout} className={MENU_ROW} title="Đăng xuất">
                      <span className={MENU_ICON} aria-hidden="true"><LogOut className="h-[17px] w-[17px]" strokeWidth={1.7} /></span>
                      <span className="min-w-0 flex-1">Đăng xuất</span>
                    </button>
                  </div>

                  <p className="px-5 pb-3.5 pt-2 text-micro leading-[17px] text-slate-500">Lớp Học Trực Tuyến © 2026</p>
                </div>
              )}
            </div>
          ) : (
            <>
              <Link
                to="/login"
                className="whitespace-nowrap px-1 text-ui font-medium text-slate-900 transition-colors duration-micro hover:text-slate-600"
              >
                Đăng nhập
              </Link>
              <Link
                to="/login"
                state={{ mode: 'register' }}
                className="press hidden h-10 items-center whitespace-nowrap rounded-btn border border-slate-200 bg-white px-4 text-ui font-semibold text-slate-900 transition-colors duration-micro hover:bg-slate-100 sm:inline-flex"
              >
                Đăng ký miễn phí
              </Link>
            </>
          )}
        </div>
      </div>
    </header>
  );
};
