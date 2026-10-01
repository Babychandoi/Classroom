import React from 'react';
import { Link, useLocation } from 'react-router-dom';
import { LogIn } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { LoadingSpinner } from './UIStates';

/**
 * R18-10: what a visitor who is not signed in sees on a member-only page (Góc học tập, Luyện thi, Bảng xếp hạng,
 * Tài liệu, Thành viên, hồ sơ thành viên). The server refuses all of these to guests (401), so the page used to
 * fire its requests anyway, each one triggering an extra /auth/refresh, and end on the raw "Chưa đăng nhập hoặc
 * phiên đã hết hạn" error with a useless "Thử lại". This says what is needed and offers the way in, returning to
 * the same page afterwards (LoginPage honours state.from).
 */
export const SignInPrompt: React.FC = () => {
  const location = useLocation();
  return (
    <div className="max-w-xl mx-auto flex flex-col items-center text-center p-10 bg-white rounded-2xl border border-slate-200 shadow-sm">
      <div className="w-12 h-12 rounded-full bg-indigo-50 flex items-center justify-center text-indigo-600 mb-4">
        <LogIn className="w-6 h-6" />
      </div>
      <h2 className="text-lg font-bold text-slate-900 mb-1">Đăng nhập để xem nội dung này</h2>
      <p className="text-sm text-slate-600 max-w-md mb-6">
        Nội dung này chỉ dành cho thành viên của lớp. Đăng nhập rồi tham gia lớp để học tập, luyện thi và xem tài liệu.
      </p>
      <Link
        to="/login"
        state={{ from: location }}
        className="inline-flex items-center space-x-2 px-5 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-bold rounded-xl shadow-sm transition"
      >
        <LogIn className="w-4 h-4" />
        <span>Đăng nhập</span>
      </Link>
    </div>
  );
};

/**
 * Renders its children only for a signed-in person, so a guest never causes a member-only request. It waits for the
 * session bootstrap to settle first (isLoading), and while the backend cannot be reached at all (isReconnecting)
 * it says so instead of claiming the visitor is signed out. If the session ends mid-visit (user becomes null) the
 * page is replaced by the same prompt.
 */
export const RequireSignIn: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, isLoading, isReconnecting, retryReconnect } = useAuth();
  if (isLoading) return <LoadingSpinner message="Đang xác thực..." />;
  if (user) return <>{children}</>;
  if (isReconnecting) {
    return (
      <div role="status" className="max-w-xl mx-auto p-8 space-y-3 text-center">
        <p className="text-sm text-slate-700">Đang kết nối lại...</p>
        <button
          type="button"
          onClick={retryReconnect}
          className="px-3 py-1.5 text-xs font-bold rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white transition"
        >
          Thử lại
        </button>
      </div>
    );
  }
  return <SignInPrompt />;
};
