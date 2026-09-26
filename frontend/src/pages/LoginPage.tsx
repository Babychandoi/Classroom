import React, { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { GraduationCap, ArrowRight, ShieldCheck, UserCheck } from 'lucide-react';
import { ErrorBanner } from '../components/UIStates';

export const LoginPage: React.FC = () => {
  const { login, register, quickLogin } = useAuth();
  const demoLoginEnabled = import.meta.env.VITE_ENABLE_DEMO_LOGIN === 'true';
  const navigate = useNavigate();
  const location = useLocation();
  const returnTo = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname || '/classes';

  const [isRegister, setIsRegister] = useState(false);
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

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col justify-center py-12 sm:px-6 lg:px-8">
      <div className="sm:mx-auto sm:w-full sm:max-w-md text-center">
        <div className="mx-auto w-12 h-12 rounded-2xl bg-indigo-600 flex items-center justify-center text-white shadow-lg shadow-indigo-300">
          <GraduationCap className="w-7 h-7" />
        </div>
        <h2 className="mt-4 text-3xl font-extrabold text-slate-900 tracking-tight">
          {isRegister ? 'Đăng ký tài khoản mới' : 'Đăng nhập hệ thống'}
        </h2>
        <p className="mt-1 text-sm text-slate-500">
          Nền tảng lớp học trực tuyến & phân quyền đa vai trò
        </p>
      </div>

      <div className="mt-8 sm:mx-auto sm:w-full sm:max-w-md">
        <div className="bg-white py-8 px-4 shadow-xl shadow-slate-200/50 sm:rounded-2xl sm:px-10 border border-slate-200">
          {error && <div className="mb-4"><ErrorBanner message={error} /></div>}

          <form className="space-y-4" onSubmit={handleSubmit}>
            {isRegister && (
              <div>
                <label className="block text-xs font-semibold text-slate-700 uppercase">Họ và tên</label>
                <input
                  type="text"
                  required
                  value={fullName}
                  onChange={(e) => setFullName(e.target.value)}
                  placeholder="Nguyễn Văn A"
                  className="mt-1 block w-full px-3.5 py-2.5 bg-slate-50 border border-slate-300 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
              </div>
            )}

            <div>
              <label className="block text-xs font-semibold text-slate-700 uppercase">Email</label>
              <input
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="name@example.com"
                className="mt-1 block w-full px-3.5 py-2.5 bg-slate-50 border border-slate-300 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>

            <div>
              <label className="block text-xs font-semibold text-slate-700 uppercase">Mật khẩu</label>
              <input
                type="password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••"
                className="mt-1 block w-full px-3.5 py-2.5 bg-slate-50 border border-slate-300 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>

            <button
              type="submit"
              disabled={loading}
              className="w-full flex justify-center items-center space-x-2 py-3 px-4 border border-transparent rounded-xl shadow-sm text-sm font-bold text-white bg-indigo-600 hover:bg-indigo-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 transition disabled:opacity-50"
            >
              <span>{isRegister ? 'Tạo tài khoản' : 'Đăng nhập'}</span>
              <ArrowRight className="w-4 h-4" />
            </button>
          </form>

          <div className="mt-4 text-center">
            <button
              onClick={() => {
                setIsRegister(!isRegister);
                setError(null);
              }}
              className="text-xs font-semibold text-indigo-600 hover:text-indigo-800"
            >
              {isRegister ? 'Đã có tài khoản? Đăng nhập ngay' : 'Chưa có tài khoản? Đăng ký mới'}
            </button>
          </div>

          {/* Quick Demo Login Grid */}
          {demoLoginEnabled && <div className="mt-6 pt-6 border-t border-slate-200">
            <div className="flex items-center space-x-2 mb-3 text-xs font-bold text-slate-600 uppercase tracking-wider">
              <UserCheck className="w-4 h-4 text-indigo-600" />
              <span>Đăng nhập nhanh (Tài khoản kiểm thử)</span>
            </div>

            <div className="space-y-2">
              <button
                onClick={() => handleQuick('owner@classroom.local')}
                className="w-full text-left px-3 py-2 bg-indigo-50/60 hover:bg-indigo-100 rounded-lg text-xs font-medium text-slate-800 flex items-center justify-between border border-indigo-100 transition"
              >
                <div>
                  <span className="font-bold text-indigo-900 block">Thầy Chủ Nhiệm (OWNER)</span>
                  <span className="text-[11px] text-slate-500">owner@classroom.local</span>
                </div>
                <span className="px-2 py-0.5 rounded bg-indigo-600 text-white font-bold text-[10px]">OWNER</span>
              </button>

              <button
                onClick={() => handleQuick('staff@classroom.local')}
                className="w-full text-left px-3 py-2 bg-blue-50/60 hover:bg-blue-100 rounded-lg text-xs font-medium text-slate-800 flex items-center justify-between border border-blue-100 transition"
              >
                <div>
                  <span className="font-bold text-blue-900 block">Cô Trợ Giảng (STAFF)</span>
                  <span className="text-[11px] text-slate-500">staff@classroom.local</span>
                </div>
                <span className="px-2 py-0.5 rounded bg-blue-600 text-white font-bold text-[10px]">STAFF</span>
              </button>

              <button
                onClick={() => handleQuick('student.free@classroom.local')}
                className="w-full text-left px-3 py-2 bg-slate-100 hover:bg-slate-200 rounded-lg text-xs font-medium text-slate-800 flex items-center justify-between border border-slate-200 transition"
              >
                <div>
                  <span className="font-bold text-slate-900 block">Học Viên FREE (Miễn phí)</span>
                  <span className="text-[11px] text-slate-500">student.free@classroom.local</span>
                </div>
                <span className="px-2 py-0.5 rounded bg-slate-600 text-white font-bold text-[10px]">FREE</span>
              </button>

              <button
                onClick={() => handleQuick('student.pro@classroom.local')}
                className="w-full text-left px-3 py-2 bg-amber-50/60 hover:bg-amber-100 rounded-lg text-xs font-medium text-slate-800 flex items-center justify-between border border-amber-200 transition"
              >
                <div>
                  <span className="font-bold text-amber-900 block">Học Viên VIP (PRO)</span>
                  <span className="text-[11px] text-slate-500">student.pro@classroom.local</span>
                </div>
                <span className="px-2 py-0.5 rounded bg-amber-500 text-white font-bold text-[10px]">PRO</span>
              </button>

              <button
                onClick={() => handleQuick('student.expired@classroom.local')}
                className="w-full text-left px-3 py-2 bg-rose-50/60 hover:bg-rose-100 rounded-lg text-xs font-medium text-slate-800 flex items-center justify-between border border-rose-200 transition"
              >
                <div>
                  <span className="font-bold text-rose-900 block">Học Viên Hết Hạn PRO</span>
                  <span className="text-[11px] text-slate-500">student.expired@classroom.local</span>
                </div>
                <span className="px-2 py-0.5 rounded bg-rose-600 text-white font-bold text-[10px]">EXPIRED</span>
              </button>
            </div>
          </div>}
        </div>
      </div>
    </div>
  );
};
