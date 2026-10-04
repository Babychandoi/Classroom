import React, { useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { api } from '../api/client';
import { Eye, Globe, Lock, Mail, ShieldCheck, Users } from 'lucide-react';
import { DataRightsPanel } from '../components/DataRightsPanel';
import { Avatar, Badge, buttonClass, inputClass, toneFor } from '../components/ui';

const VISIBILITY_ICON = { PRIVATE: Lock, CLASS: Users, PUBLIC: Globe } as const;

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

  // The header shows what is saved (the session user), the form below holds the draft.
  const savedVisibility = VISIBILITY_OPTIONS.find((opt) => opt.value === (user.profileVisibility ?? 'PRIVATE')) ?? VISIBILITY_OPTIONS[0];
  const SavedIcon = VISIBILITY_ICON[savedVisibility.value];

  return (
    <div className="mx-auto w-full max-w-[1120px] px-4 py-6 sm:px-8 sm:pb-24">
      {/* Cover band + identity (no cover upload exists for people, so the band is a calm topic tile). */}
      <section aria-labelledby="profile-name" className="overflow-hidden rounded-card border border-slate-200 bg-white shadow-hairline">
        <div aria-hidden="true" className={`h-[104px] w-full sm:h-[200px] ${toneFor(user.id)}`} />
        <div className="px-5 pb-5 sm:px-7 sm:pb-6">
          <div className="flex flex-wrap items-end gap-4 sm:gap-5">
            <span className="-mt-10 flex-shrink-0 rounded-full border-4 border-white bg-white shadow-[0_4px_12px_rgba(15,23,42,0.12)] sm:-mt-11">
              <Avatar name={user.fullName} src={user.avatarUrl} size={96} className="sm:hidden" />
              <Avatar name={user.fullName} src={user.avatarUrl} size={112} className="hidden sm:inline-flex" />
            </span>
            <div className="w-full min-w-0 sm:w-auto sm:flex-1 sm:pt-4">
              <h1 id="profile-name" className="truncate text-h2-sm font-semibold tracking-[-0.4px] text-slate-900 sm:text-h2">{user.fullName}</h1>
              <p className="mt-0.5 flex items-center gap-1.5 truncate text-ui text-slate-500">
                <Mail className="h-3.5 w-3.5 flex-shrink-0" strokeWidth={1.8} aria-hidden="true" />
                {user.email}
              </p>
            </div>
            <Badge tone="neutral" className="sm:mb-1">
              <SavedIcon className="h-3.5 w-3.5" strokeWidth={1.8} aria-hidden="true" />
              {`Hồ sơ: ${savedVisibility.label.toLowerCase()}`}
            </Badge>
          </div>
        </div>
      </section>

      <div className="mt-6 grid items-start gap-6 lg:grid-cols-[340px_minmax(0,1fr)]">
        <aside className="space-y-4 lg:sticky lg:top-[88px]">
          <section className="rounded-card border border-slate-200 bg-white px-6 py-5 shadow-hairline">
            <h2 className="text-body-sm font-semibold text-slate-900">Giới thiệu</h2>
            <p className="mt-3 whitespace-pre-line break-words text-ui leading-[22px] text-slate-600">
              {user.bio || 'Bạn chưa viết giới thiệu. Vài dòng về bạn giúp các thành viên khác trong lớp dễ kết nối hơn.'}
            </p>
          </section>
          <section className="rounded-card border border-slate-200 bg-white px-6 py-5 shadow-hairline">
            <h2 className="flex items-center gap-2 text-body-sm font-semibold text-slate-900">
              <Eye className="h-4 w-4 text-slate-600" strokeWidth={1.75} aria-hidden="true" />
              Ai thấy tên và ảnh của bạn
            </h2>
            <p className="mt-3 text-meta leading-[19px] text-slate-600">
              {`Hồ sơ của bạn đang ở chế độ “${savedVisibility.label}”. Bạn đổi được bất cứ lúc nào trong mục Chế độ hiển thị hồ sơ.`}
            </p>
            <Link to="/privacy" className="mt-3 inline-block text-meta font-medium text-blue-600 hover:text-blue-700">Đọc chính sách dữ liệu</Link>
          </section>
        </aside>

        <div className="min-w-0 space-y-6">
          <section aria-labelledby="profile-form-title" className="rounded-card border border-slate-200 bg-white p-5 shadow-hairline sm:p-7">
            <h2 id="profile-form-title" className="text-h3-lg font-semibold text-slate-900">Hồ sơ của tôi</h2>
            <p className="mt-1 text-ui text-slate-600">Cập nhật thông tin cá nhân và quyền riêng tư hiển thị với người khác.</p>

            {error && <div role="alert" className="mt-5 rounded-btn border border-red-200 bg-red-50 p-3 text-meta font-medium text-red-700">{error}</div>}
            {success && <div role="status" className="mt-5 rounded-btn border border-green-200 bg-green-100 p-3 text-meta font-medium text-green-800">Đã lưu hồ sơ thành công.</div>}

            <form onSubmit={handleSave} className="mt-6 space-y-5">
              <div>
                <label htmlFor={fullNameId} className="block text-meta font-semibold text-slate-900">Họ và tên</label>
                <input
                  id={fullNameId}
                  type="text"
                  value={fullName}
                  onChange={(e) => setFullName(e.target.value)}
                  required
                  autoComplete="name"
                  className={inputClass('mt-1.5 h-11')}
                />
              </div>

              <div>
                <label htmlFor={avatarUrlId} className="block text-meta font-semibold text-slate-900">Ảnh đại diện (URL https)</label>
                <div className="mt-1.5 flex items-center gap-3">
                  <Avatar name={fullName || user.fullName} src={avatarUrl || null} size={44} key={avatarUrl} />
                  <input
                    id={avatarUrlId}
                    type="url"
                    placeholder="https://..."
                    value={avatarUrl}
                    onChange={(e) => setAvatarUrl(e.target.value)}
                    className={inputClass('h-11 min-w-0 flex-1')}
                  />
                </div>
                <p className="mt-1.5 text-caption text-slate-500">Chỉ chấp nhận đường dẫn https hợp lệ; để trống để xóa ảnh đại diện.</p>
              </div>

              <div>
                <label htmlFor={bioId} className="block text-meta font-semibold text-slate-900">Giới thiệu bản thân</label>
                <textarea
                  id={bioId}
                  value={bio}
                  onChange={(e) => setBio(e.target.value)}
                  rows={3}
                  className={inputClass('mt-1.5 resize-none py-2.5 leading-[22px]')}
                />
              </div>

              <fieldset>
                <legend className="flex items-center gap-2 text-meta font-semibold text-slate-900">
                  <ShieldCheck className="h-4 w-4 text-slate-600" strokeWidth={1.75} aria-hidden="true" />
                  <span>Chế độ hiển thị hồ sơ</span>
                </legend>
                <div className="mt-2.5 space-y-2.5">
                  {VISIBILITY_OPTIONS.map((opt) => {
                    const selected = profileVisibility === opt.value;
                    const Icon = VISIBILITY_ICON[opt.value];
                    return (
                      <label
                        key={opt.value}
                        className={`flex cursor-pointer items-start gap-3 rounded-[14px] px-3.5 py-3 transition-colors duration-micro ${
                          selected ? 'border-2 border-blue-600 bg-tint' : 'border border-slate-200 bg-white hover:bg-slate-50'
                        }`}
                      >
                        <input
                          type="radio"
                          name="profileVisibility"
                          value={opt.value}
                          checked={selected}
                          onChange={() => setProfileVisibility(opt.value)}
                          className="mt-0.5 h-[18px] w-[18px] flex-shrink-0 accent-blue-600"
                        />
                        <span className="min-w-0">
                          <span className="flex items-center gap-1.5">
                            <Icon className="h-3.5 w-3.5 text-slate-600" strokeWidth={1.8} aria-hidden="true" />
                            <span className="text-ui font-semibold text-slate-900">{opt.label}</span>
                          </span>
                          <span className={`mt-0.5 block text-caption leading-[18px] ${selected ? 'text-slate-600' : 'text-slate-500'}`}>{opt.description}</span>
                        </span>
                      </label>
                    );
                  })}
                </div>
              </fieldset>

              <div className="flex justify-end pt-1">
                <button type="submit" disabled={saving} className={buttonClass('primary', 'lg')}>
                  <span>{saving ? 'Đang lưu...' : 'Lưu hồ sơ'}</span>
                </button>
              </div>
            </form>
          </section>

          <DataRightsPanel />
        </div>
      </div>
    </div>
  );
};
