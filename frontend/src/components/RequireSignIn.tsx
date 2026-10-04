import React from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Lock, LogIn } from 'lucide-react';
import { buttonClass } from './ui';
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
    <div className="mx-auto flex max-w-xl flex-col items-center rounded-card border border-slate-200 bg-white p-8 text-center shadow-hairline sm:p-10">
      <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-tint text-blue-600">
        <Lock className="h-6 w-6" strokeWidth={1.75} aria-hidden="true" />
      </div>
      <h2 className="mb-1.5 text-h2-sm font-semibold text-slate-900">Đăng nhập để xem nội dung này</h2>
      <p className="mb-6 max-w-md text-ui text-slate-600">
        Nội dung này chỉ dành cho thành viên của lớp. Đăng nhập rồi tham gia lớp để học tập, luyện thi và xem tài liệu.
      </p>
      <Link to="/login" state={{ from: location }} className={buttonClass('primary', 'lg')}>
        <LogIn className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
        <span>Đăng nhập</span>
      </Link>
      <p className="mt-3 text-caption text-slate-500">Sau khi đăng nhập, bạn quay lại đúng trang này.</p>
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
      <div role="status" className="mx-auto max-w-xl space-y-3 rounded-card border border-slate-200 bg-white p-8 text-center shadow-hairline">
        <p className="text-ui text-slate-600">Đang kết nối lại...</p>
        <button type="button" onClick={retryReconnect} className={buttonClass('secondary', 'md')}>
          Thử lại
        </button>
      </div>
    );
  }
  return <SignInPrompt />;
};
