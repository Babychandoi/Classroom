import React, { useId, useState } from 'react';
import { useNavigate, useLocation, Navigate, Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { Eye, EyeOff, GraduationCap, UserCheck } from 'lucide-react';
import { ErrorBanner } from '../components/UIStates';
import { Badge, BadgeTone, buttonClass, inputClass } from '../components/ui';

const DEFAULT_RETURN_TO = '/classes';

const DEMO_ACCOUNTS: { label: string; email: string; role: string; tone: BadgeTone }[] = [
  { label: 'Thầy Chủ Nhiệm (OWNER)', email: 'owner@classroom.local', role: 'OWNER', tone: 'member' },
  { label: 'Cô Trợ Giảng (STAFF)', email: 'staff@classroom.local', role: 'STAFF', tone: 'info' },
  { label: 'Học Viên FREE (Miễn phí)', email: 'student.free@classroom.local', role: 'FREE', tone: 'free' },
  { label: 'Học Viên VIP (PRO)', email: 'student.pro@classroom.local', role: 'PRO', tone: 'pro' },
  { label: 'Học Viên Hết Hạn PRO', email: 'student.expired@classroom.local', role: 'EXPIRED', tone: 'danger' },
];

// R17-01: RequireLogin and ClassroomLayout hand over the page the person wanted as state.from (a router
// Location). Honor it after login/register - including its query string, which carries e.g. an exam
// attempt id - but only for in-app absolute paths, and never bounce back to /login itself.
const resolveReturnTo = (state: unknown): string => {
  const from = (state as { from?: { pathname?: string; search?: string; hash?: string } } | null)?.from;
  const pathname = from?.pathname;
  if (!pathname || !pathname.startsWith('/') || pathname.startsWith('//') || pathname.startsWith('/login')) {
    return DEFAULT_RETURN_TO;
  }
  return `${pathname}${from?.search ?? ''}${from?.hash ?? ''}`;
};

export const LoginPage: React.FC = () => {
  const { user, login, register, quickLogin } = useAuth();
  const demoLoginEnabled = import.meta.env.VITE_ENABLE_DEMO_LOGIN === 'true';
  const navigate = useNavigate();
  const location = useLocation();
  const returnTo = resolveReturnTo(location.state);

  const fullNameId = useId();
  const emailId = useId();
  const passwordId = useId();
  // The top bar's "Đăng ký miễn phí" opens this page in register mode (state.mode).
  const [isRegister, setIsRegister] = useState((location.state as { mode?: string } | null)?.mode === 'register');
  const [showPassword, setShowPassword] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      if (isRegister) {
        await register(email, password, fullName);
      } else {
        await login(email, password);
      }
      navigate(returnTo, { replace: true });
    } catch (err: any) {
      setError(err.message || 'Đăng nhập thất bại');
    } finally {
      setLoading(false);
    }
  };

  const handleQuick = async (demoEmail: string) => {
    setError(null);
    setLoading(true);
    try {
      await quickLogin(demoEmail);
      navigate(returnTo, { replace: true });
    } catch (err: any) {
      setError(err.message || 'Đăng nhập thất bại');
    } finally {
      setLoading(false);
    }
  };

  // R17-01: an already signed-in person (the session bootstrap restored it, or they are on /login by
  // hand) has nothing to do here - send them on instead of leaving them stranded on the form.
  if (user) {
    return <Navigate to={returnTo} replace />;
  }

  const switchMode = () => {
    setIsRegister(!isRegister);
    setError(null);
  };

  return (
    <div className="flex flex-1 items-center justify-center px-4 py-10 sm:px-6 sm:py-12">
      <div className="w-full max-w-[440px] rounded-section border border-slate-200 bg-white px-6 py-8 shadow-e1 sm:px-11 sm:pb-9 sm:pt-10">
        <div className="flex items-center justify-center gap-2.5">
          <span className="flex h-8 w-8 items-center justify-center rounded-[10px] bg-gradient-to-tr from-blue-600 to-sky-400 text-white">
            <GraduationCap className="h-5 w-5" strokeWidth={1.9} aria-hidden="true" />
          </span>
          <span className="text-[17px] font-bold tracking-[-0.4px] text-slate-900">Lớp Học Trực Tuyến</span>
        </div>
        <h1 className="mt-5 text-center text-[22px] font-semibold leading-[30px] tracking-[-0.4px] text-slate-900">
          {isRegister ? 'Tạo tài khoản' : 'Đăng nhập'}
        </h1>
        <p className="mt-1 text-center text-ui text-slate-500">
          {isRegister ? 'Miễn phí — bắt đầu trong 1 phút.' : 'Chào mừng bạn quay lại.'}
        </p>

        {error && <div className="mt-6"><ErrorBanner message={error} /></div>}

        <form className="mt-6 space-y-3.5" onSubmit={handleSubmit}>
          {isRegister && (
            <div>
              <label htmlFor={fullNameId} className="block text-meta font-semibold text-slate-900">Họ và tên</label>
              <input
                id={fullNameId}
                type="text"
                required
                autoComplete="name"
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                placeholder="VD: Nguyễn Văn A"
                className={inputClass('mt-1.5 h-[46px]')}
              />
            </div>
          )}

          <div>
            <label htmlFor={emailId} className="block text-meta font-semibold text-slate-900">Email</label>
            <input
              id={emailId}
              type="email"
              required
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="ban@email.com"
              className={inputClass('mt-1.5 h-[46px]')}
            />
          </div>

          <div>
            <label htmlFor={passwordId} className="block text-meta font-semibold text-slate-900">Mật khẩu</label>
            <div className="relative mt-1.5">
              <input
                id={passwordId}
                type={showPassword ? 'text' : 'password'}
                required
                autoComplete={isRegister ? 'new-password' : 'current-password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder={isRegister ? 'Tối thiểu 8 ký tự' : '••••••••'}
                className={inputClass('h-[46px] pr-11')}
              />
              <button
                type="button"
                onClick={() => setShowPassword(!showPassword)}
                aria-label={showPassword ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'}
                aria-pressed={showPassword}
                className="absolute right-2 top-1/2 inline-flex h-8 w-8 -translate-y-1/2 items-center justify-center rounded-full text-slate-400 transition-colors duration-micro hover:bg-slate-100 hover:text-slate-600"
              >
                {showPassword ? <EyeOff className="h-4 w-4" strokeWidth={1.7} /> : <Eye className="h-4 w-4" strokeWidth={1.7} />}
              </button>
            </div>
          </div>

          <button type="submit" disabled={loading} className={buttonClass('primary', 'lg', '!mt-[22px] h-12 w-full')}>
            {isRegister ? 'Tạo tài khoản' : 'Đăng nhập'}
          </button>
        </form>

        <p className="mt-[22px] text-center text-ui text-slate-600">
          {isRegister ? 'Đã có tài khoản? ' : 'Bạn chưa có tài khoản? '}
          <button type="button" onClick={switchMode} className="font-semibold text-blue-600 hover:text-blue-700">
            {isRegister ? 'Đăng nhập' : 'Đăng ký miễn phí'}
          </button>
        </p>

        {/* Quick Demo Login Grid */}
        {demoLoginEnabled && (
          <div className="mt-7 border-t border-slate-100 pt-6">
            <p className="mb-3 flex items-center gap-2 text-caption font-semibold uppercase tracking-[0.5px] text-slate-500">
              <UserCheck className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
              Đăng nhập nhanh (Tài khoản kiểm thử)
            </p>
            <div className="space-y-1">
              {DEMO_ACCOUNTS.map((acc) => (
                <button
                  key={acc.email}
                  type="button"
                  disabled={loading}
                  onClick={() => handleQuick(acc.email)}
                  className="flex min-h-[48px] w-full items-center justify-between gap-3 rounded-btn px-3 py-1.5 text-left transition-colors duration-micro hover:bg-slate-100 disabled:opacity-60"
                >
                  <span className="min-w-0">
                    <span className="block truncate text-meta font-semibold text-slate-900">{acc.label}</span>
                    <span className="block truncate text-caption text-slate-500">{acc.email}</span>
                  </span>
                  <Badge tone={acc.tone} size="xs">{acc.role}</Badge>
                </button>
              ))}
            </div>
          </div>
        )}

        <p className="mt-7 text-center text-caption leading-[18px] text-slate-500">
          Tiếp tục nghĩa là bạn đồng ý với{' '}
          <Link to="/privacy" className="font-medium text-slate-600 hover:text-slate-900">Chính sách dữ liệu cá nhân</Link>
        </p>
      </div>
    </div>
  );
};
