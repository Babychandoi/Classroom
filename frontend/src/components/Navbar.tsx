import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { GraduationCap, UserCheck, LogOut, ChevronDown, LayoutDashboard, UserCircle } from 'lucide-react';

export const Navbar: React.FC = () => {
  const { user, isLoading, logout, quickLogin } = useAuth();
  const navigate = useNavigate();
  const [showDemoMenu, setShowDemoMenu] = useState(false);
  const demoLoginEnabled = import.meta.env.VITE_ENABLE_DEMO_LOGIN === 'true';

  const demoAccounts = [
    { label: 'Thầy Chủ Nhiệm (OWNER)', email: 'owner@classroom.local', role: 'OWNER' },
    { label: 'Trợ Giảng (STAFF)', email: 'staff@classroom.local', role: 'STAFF' },
    { label: 'Học Viên FREE', email: 'student.free@classroom.local', role: 'FREE' },
    { label: 'Học Viên VIP (PRO)', email: 'student.pro@classroom.local', role: 'PRO' },
    { label: 'Học Viên HẾT HẠN', email: 'student.expired@classroom.local', role: 'EXPIRED' },
  ];

  const handleSelectDemo = async (email: string) => {
    setShowDemoMenu(false);
    await quickLogin(email);
  };

  return (
    <nav className="bg-white border-b border-slate-200 sticky top-0 z-40 shadow-sm">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex justify-between h-16">
          <div className="flex items-center space-x-6 min-w-0">
            {/* R17-05: the brand stays on one line (it used to wrap to 3 lines and spill out of the
                h-16 bar at phone width) and truncates rather than pushing the account controls out. */}
            <Link to="/classes" className="flex items-center space-x-2.5 min-w-0">
              <div className="w-10 h-10 flex-shrink-0 rounded-xl bg-gradient-to-tr from-indigo-600 to-violet-500 flex items-center justify-center text-white shadow-md shadow-indigo-200">
                <GraduationCap className="w-6 h-6" />
              </div>
              <span className="font-extrabold text-base sm:text-xl whitespace-nowrap truncate tracking-tight bg-gradient-to-r from-slate-900 to-indigo-900 bg-clip-text text-transparent">
                Lớp Học Trực Tuyến
              </span>
            </Link>

            {/* The brand already links to the class list, so the duplicate text link is desktop-only. */}
            <Link
              to="/classes"
              className="hidden sm:inline-block whitespace-nowrap text-sm font-semibold text-slate-600 hover:text-indigo-600 px-3 py-1.5 rounded-lg hover:bg-slate-50 transition"
            >
              Khám phá lớp học
            </Link>
          </div>

          <div className="flex items-center space-x-3 flex-shrink-0 pl-3">
            {/* Quick Demo Switcher */}
            {demoLoginEnabled && <div className="relative">
              <button
                onClick={() => setShowDemoMenu(!showDemoMenu)}
                className="flex items-center space-x-2 text-xs font-semibold px-3 py-1.5 bg-indigo-50 text-indigo-700 rounded-lg hover:bg-indigo-100 border border-indigo-200 transition"
                title="Chuyển đổi nhanh giữa các tài khoản demo kiểm thử"
              >
                <UserCheck className="w-3.5 h-3.5" />
                <span className="hidden sm:inline">Tài khoản demo:</span>
                <span className="font-bold underline">{user ? user.email.split('@')[0] : 'Chọn demo'}</span>
                <ChevronDown className="w-3 h-3" />
              </button>

              {showDemoMenu && (
                <div className="absolute right-0 mt-2 w-64 bg-white rounded-xl shadow-xl border border-slate-200 py-1.5 z-50">
                  <div className="px-3 py-1.5 text-xs font-bold text-slate-500 uppercase tracking-wider">
                    Chuyển vai trò thử nghiệm
                  </div>
                  {demoAccounts.map((acc) => (
                    <button
                      key={acc.email}
                      onClick={() => handleSelectDemo(acc.email)}
                      className={`w-full text-left px-3 py-2 text-xs font-medium hover:bg-indigo-50 flex items-center justify-between ${
                        user?.email === acc.email ? 'bg-indigo-50/70 text-indigo-600 font-bold' : 'text-slate-700'
                      }`}
                    >
                      <span>{acc.label}</span>
                      <span className="text-[10px] px-1.5 py-0.5 rounded bg-slate-100 text-slate-600 uppercase font-mono">
                        {acc.role}
                      </span>
                    </button>
                  ))}
                </div>
              )}
            </div>}

            {isLoading ? (
              // R17-01: while the silent session restore is in flight `user` is still null even for a
              // signed-in person - rendering "Đăng nhập" now would flash the wrong state and invite a
              // click that races the bootstrap. Reserve the space instead.
              <div aria-hidden="true" className="h-9 w-24" />
            ) : user ? (
              <div className="flex items-center space-x-3 pl-2 border-l border-slate-200">
                <Link
                  to="/me/profile"
                  className="hidden md:flex flex-col text-right hover:text-indigo-600 transition"
                  title="Hồ sơ của tôi"
                >
                  <span className="text-sm font-bold text-slate-800 leading-none">{user.fullName}</span>
                  <span className="text-[11px] text-slate-500 font-medium">{user.email}</span>
                </Link>
                <Link
                  to="/me/profile"
                  className="p-2 text-slate-500 hover:text-indigo-600 rounded-lg hover:bg-slate-100 transition"
                  title="Hồ sơ của tôi"
                >
                  <UserCircle className="w-5 h-5" />
                </Link>
                <button
                  onClick={() => {
                    void logout();
                    navigate('/login');
                  }}
                  className="p-2 text-slate-500 hover:text-rose-600 rounded-lg hover:bg-slate-100 transition"
                  title="Đăng xuất"
                >
                  <LogOut className="w-5 h-5" />
                </button>
              </div>
            ) : (
              <Link
                to="/login"
                className="whitespace-nowrap text-sm font-semibold px-4 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 transition"
              >
                Đăng nhập
              </Link>
            )}
          </div>
        </div>
      </div>
    </nav>
  );
};
