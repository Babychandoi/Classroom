import React, { useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { api } from '../api/client';
import { ArrowLeft, ShieldCheck, Save } from 'lucide-react';
import { DataRightsPanel } from '../components/DataRightsPanel';

// R13-05 (FR-12/D-05): mirrors UserService.updateProfile's accepted values and validation
// (PRIVATE/CLASS/PUBLIC; anything else is rejected server-side as BAD_REQUEST).
const VISIBILITY_OPTIONS: { value: 'PRIVATE' | 'CLASS' | 'PUBLIC'; label: string; description: string }[] = [
  {
    value: 'PRIVATE',
    label: 'Riêng tư',
    description: 'Chỉ bạn và quản trị lớp (chủ lớp, trợ giảng có quyền xem thành viên) thấy được tên và ảnh đại diện thật của bạn.',
  },
  {
    value: 'CLASS',
    label: 'Trong lớp học',
    description: 'Các thành viên khác cùng lớp học với bạn có thể thấy tên và ảnh đại diện; người ngoài lớp thì không.',
  },
  {
    value: 'PUBLIC',
    label: 'Công khai',
    // R18-10 (D-16): PUBLIC widens who sees name + avatar (guests included, e.g. on public feed posts) - it does not
    // open the member profile page, journey, leaderboard or member list, which stay members-only.
    description: 'Mọi người xem lớp học, kể cả khách chưa đăng nhập hoặc chưa tham gia (ví dụ trên bài đăng công khai), đều thấy tên và ảnh đại diện của bạn. Trang hồ sơ, bảng xếp hạng và danh sách thành viên vẫn chỉ dành cho thành viên của lớp.',
  },
];

export const MyProfilePage: React.FC = () => {
  const { user, refreshUser } = useAuth();
  const fullNameId = useId();
  const avatarUrlId = useId();
  const bioId = useId();

  const [fullName, setFullName] = useState(user?.fullName ?? '');
  const [avatarUrl, setAvatarUrl] = useState(user?.avatarUrl ?? '');
  const [bio, setBio] = useState(user?.bio ?? '');
  const [profileVisibility, setProfileVisibility] = useState<'PRIVATE' | 'CLASS' | 'PUBLIC'>(
    (user?.profileVisibility as 'PRIVATE' | 'CLASS' | 'PUBLIC') ?? 'PRIVATE',
  );
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  if (!user) {
    return <div role="alert" className="p-8">Vui lòng đăng nhập để xem hồ sơ của bạn.</div>;
  }

  const handleSave = async (event: React.FormEvent) => {
    event.preventDefault();
    setSaving(true);
    setError(null);
    setSuccess(false);
    try {
      await api.put('/users/profile', {
        fullName,
        avatarUrl,
        bio,
        profileVisibility,
      });
      await refreshUser();
      setSuccess(true);
    } catch (err: any) {
      setError(err.message || 'Không thể lưu hồ sơ');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="max-w-2xl mx-auto space-y-6 p-4 sm:p-0">
      <Link to="/classes" className="inline-flex items-center space-x-2 text-sm font-semibold text-slate-600 hover:text-indigo-600 transition">
        <ArrowLeft className="w-4 h-4" />
        <span>Về danh sách lớp</span>
      </Link>

      <DataRightsPanel />
      <div className="bg-white rounded-2xl border border-slate-200 p-6 shadow-sm space-y-6">
        <div>
          <h1 className="text-2xl font-black text-slate-900 tracking-tight">Hồ sơ của tôi</h1>
          <p className="text-sm text-slate-500 mt-1">Cập nhật thông tin cá nhân và quyền riêng tư hiển thị với người khác.</p>
        </div>

        {error && <div role="alert" className="rounded-xl bg-red-50 p-3 text-sm text-red-700">{error}</div>}
        {success && <div role="status" className="rounded-xl bg-emerald-50 p-3 text-sm text-emerald-700">Đã lưu hồ sơ thành công.</div>}

        <form onSubmit={handleSave} className="space-y-5">
          <div>
            <label htmlFor={fullNameId} className="block text-sm font-semibold text-slate-700 mb-1">Họ và tên</label>
            <input
              id={fullNameId}
              type="text"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              required
              className="w-full rounded-xl border border-slate-300 p-2.5 text-sm"
            />
          </div>

          <div>
            <label htmlFor={avatarUrlId} className="block text-sm font-semibold text-slate-700 mb-1">Ảnh đại diện (URL https)</label>
            <input
              id={avatarUrlId}
              type="url"
              placeholder="https://..."
              value={avatarUrl}
              onChange={(e) => setAvatarUrl(e.target.value)}
              className="w-full rounded-xl border border-slate-300 p-2.5 text-sm"
            />
            <p className="text-xs text-slate-500 mt-1">Chỉ chấp nhận đường dẫn https hợp lệ; để trống để xóa ảnh đại diện.</p>
          </div>

          <div>
            <label htmlFor={bioId} className="block text-sm font-semibold text-slate-700 mb-1">Giới thiệu bản thân</label>
            <textarea
              id={bioId}
              value={bio}
              onChange={(e) => setBio(e.target.value)}
              rows={3}
              className="w-full rounded-xl border border-slate-300 p-2.5 text-sm"
            />
          </div>

          <fieldset className="space-y-3">
            <legend className="flex items-center space-x-2 text-sm font-semibold text-slate-700 mb-1">
              <ShieldCheck className="w-4 h-4 text-indigo-600" />
              <span>Chế độ hiển thị hồ sơ</span>
            </legend>
            {VISIBILITY_OPTIONS.map((opt) => (
              <label
                key={opt.value}
                className={`flex items-start space-x-3 rounded-xl border p-3 cursor-pointer transition ${
                  profileVisibility === opt.value ? 'border-indigo-400 bg-indigo-50/50' : 'border-slate-200'
                }`}
              >
                <input
                  type="radio"
                  name="profileVisibility"
                  value={opt.value}
                  checked={profileVisibility === opt.value}
                  onChange={() => setProfileVisibility(opt.value)}
                  className="mt-1"
                />
                <span>
                  <span className="block text-sm font-bold text-slate-800">{opt.label}</span>
                  <span className="block text-xs text-slate-500">{opt.description}</span>
                </span>
              </label>
            ))}
          </fieldset>

          <button
            type="submit"
            disabled={saving}
            className="inline-flex items-center space-x-2 px-5 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white font-bold text-sm rounded-xl shadow-sm transition disabled:opacity-50"
          >
            <Save className="w-4 h-4" />
            <span>{saving ? 'Đang lưu...' : 'Lưu hồ sơ'}</span>
          </button>
        </form>
      </div>
    </div>
  );
};
