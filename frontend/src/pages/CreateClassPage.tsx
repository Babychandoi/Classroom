import React, { useCallback, useEffect, useId, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { GraduationCap } from 'lucide-react';
import { api } from '../api/client';
import { useAuth } from '../context/AuthContext';
import type { Classroom } from '../types';
import { CENTER, ImageSlot, PickedImage } from '../components/create/ImageSlot';
import { DesktopPreview, MobilePreview, PreviewModel } from '../components/create/CreatePreview';
import { FALLBACK_CATEGORIES, formatPriceInput, priceValue, uploadClassImage } from '../components/create/classMedia';

// "Tạo lớp học" (design: export-create.dc.html, black-primary variant): a 420px form panel on the left and a live
// preview of the class page on the right (desktop / mobile toggle). Below 1000px the preview hides and the image
// slots move into the form. The design's second step (platform subscription plans) does not exist in this product,
// so creating goes straight to the "đã mở" dialog.

const NAME_MAX = 60;
const DESC_MAX = 160;
const BLACK = '#0F172A';
const LINE = '#E2E8F0';

type Visibility = 'PUBLIC' | 'PRIVATE';
type Fee = 'FREE' | 'PAID';
type Device = 'desktop' | 'mobile';

/** What the post-create steps have finished, so "Thử lại" redoes only what failed. */
interface SetupState {
  accessDone: boolean;
  coverMediaId?: string;
  avatarMediaId?: string;
  coverSaved: boolean;
  avatarSaved: boolean;
}

const errorText = (err: unknown, fallback: string) => (err instanceof Error && err.message ? err.message : fallback);

const Check: React.FC = () => (
  <span aria-hidden="true" className="flex h-[22px] w-[22px] flex-none items-center justify-center rounded-full bg-[#0F172A]" data-testid="check">
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#FFFFFF" strokeWidth="3.2" strokeLinecap="round" strokeLinejoin="round">
      <path d="m5 12 5 5L20 7" />
    </svg>
  </span>
);

const fieldLabel = 'text-[12px] leading-4 text-[#64748B]';

/** Bordered box with the label inside (the design's input style); turns black on focus or once valid. */
const Box: React.FC<{ valid?: boolean; className?: string; children: React.ReactNode }> = ({ valid = false, className = '', children }) => (
  <div
    className={`rounded-xl border border-[#E2E8F0] focus-within:border-[#0F172A] ${className}`}
    style={{ borderColor: valid ? BLACK : undefined }}
  >
    {children}
  </div>
);

const Choice: React.FC<{ on: boolean; title: string; desc: string; onClick: () => void }> = ({ on, title, desc, onClick }) => (
  <button
    type="button"
    onClick={onClick}
    aria-pressed={on}
    className="cursor-pointer rounded-xl px-3 py-2.5 text-left"
    style={{ border: `1.5px solid ${on ? BLACK : LINE}`, background: on ? '#F8FAFC' : '#FFFFFF' }}
  >
    <span className="block text-[13.5px] font-semibold leading-[18px] text-[#0F172A]">{title}</span>
    <span className="mt-0.5 block text-[12px] leading-4 text-[#64748B]">{desc}</span>
  </button>
);

export const CreateClassPage: React.FC = () => {
  const { user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const nameId = useId();
  const descId = useId();
  const priceId = useId();

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [categories, setCategories] = useState<string[]>(FALLBACK_CATEGORIES);
  const [category, setCategory] = useState('');
  const [visibility, setVisibility] = useState<Visibility>('PUBLIC');
  const [fee, setFee] = useState<Fee>('FREE');
  const [price, setPrice] = useState('');
  const [approve, setApprove] = useState(false);
  const [device, setDevice] = useState<Device>('desktop');
  const [cover, setCover] = useState<PickedImage | null>(null);
  const [avatar, setAvatar] = useState<PickedImage | null>(null);
  const [coverPosition, setCoverPosition] = useState(CENTER);
  const [avatarPosition, setAvatarPosition] = useState(CENTER);

  const [submitting, setSubmitting] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const [created, setCreated] = useState<Classroom | null>(null);
  const [problems, setProblems] = useState<string[]>([]);
  const [retrying, setRetrying] = useState(false);
  const setup = useRef<SetupState>({ accessDone: false, coverSaved: false, avatarSaved: false });

  // Categories come from the server; the contract's fixed list stands in when the endpoint is not there (yet).
  useEffect(() => {
    let cancelled = false;
    api.get<string[]>('/classes/categories')
      .then((list) => {
        if (cancelled || !Array.isArray(list) || list.length === 0) return;
        setCategories(list);
        setCategory((current) => (current && !list.includes(current) ? '' : current));
      })
      .catch(() => { /* keep the fallback list */ });
    return () => { cancelled = true; };
  }, []);

  // Object URLs of the picked images are released when replaced and when the page goes away.
  const coverUrl = useRef<string | null>(null);
  const avatarUrl = useRef<string | null>(null);
  useEffect(() => () => {
    if (coverUrl.current) URL.revokeObjectURL(coverUrl.current);
    if (avatarUrl.current) URL.revokeObjectURL(avatarUrl.current);
  }, []);
  const pick = (kind: 'cover' | 'avatar') => (file: File) => {
    const ref = kind === 'cover' ? coverUrl : avatarUrl;
    if (ref.current) URL.revokeObjectURL(ref.current);
    ref.current = URL.createObjectURL(file);
    const picked = { file, url: ref.current };
    if (kind === 'cover') { setCover(picked); setCoverPosition(CENTER); } else { setAvatar(picked); setAvatarPosition(CENTER); }
  };
  const onPickCover = pick('cover');
  const onPickAvatar = pick('avatar');

  const paid = fee === 'PAID';
  const nameOk = !!name.trim();
  const catOk = !!category;
  const priceOk = !paid || priceValue(price) > 0;
  const canCreate = nameOk && catOk && priceOk;
  // Approval only gates joining a public free class (invite links and purchases bypass it - docs/API-CREATE-CLASS.md §3).
  const approvalApplies = visibility === 'PUBLIC' && !paid;
  const approval = approve && approvalApplies;
  const priceTag = paid ? (priceValue(price) > 0 ? `${price}đ/tháng` : 'Có phí') : 'Miễn phí';

  const createHint = canCreate
    ? 'Bằng việc tạo, bạn đồng ý với Điều khoản dành cho người dẫn dắt.'
    : !nameOk
    ? 'Nhập tên lớp học để tiếp tục.'
    : !catOk
    ? 'Chọn một lĩnh vực để tiếp tục.'
    : 'Nhập phí mỗi tháng để tiếp tục.';

  // Everything after POST /classes: paid access, image uploads, then one PUT with the new media ids. Each step is
  // remembered, so a retry only redoes what failed - and the created class is never lost.
  const finishSetup = useCallback(async (cls: Classroom) => {
    const s = setup.current;
    const issues: string[] = [];
    if (paid && !s.accessDone) {
      try {
        await api.put(`/classes/${cls.id}/access`, { accessType: 'PAID', price: priceValue(price), currency: 'VND', durationDays: 30 });
        s.accessDone = true;
      } catch (err) {
        issues.push(`Chưa đặt được phí ${price}đ/tháng (${errorText(err, 'lỗi không xác định')}) — lớp đang miễn phí.`);
      }
    }
    if (cover && !s.coverMediaId) {
      try { s.coverMediaId = await uploadClassImage(cls.id, cover.file, 'CLASS_COVER'); } catch (err) {
        issues.push(`Chưa tải được ảnh bìa (${errorText(err, 'lỗi không xác định')}).`);
      }
    }
    if (avatar && !s.avatarMediaId) {
      try { s.avatarMediaId = await uploadClassImage(cls.id, avatar.file, 'CLASS_AVATAR'); } catch (err) {
        issues.push(`Chưa tải được ảnh đại diện (${errorText(err, 'lỗi không xác định')}).`);
      }
    }
    const saveCover = !!s.coverMediaId && !s.coverSaved;
    const saveAvatar = !!s.avatarMediaId && !s.avatarSaved;
    if (saveCover || saveAvatar) {
      try {
        await api.put(`/classes/${cls.id}`, {
          title: cls.title,
          description: cls.description ?? '',
          ...(saveCover ? { coverMediaId: s.coverMediaId, coverPosition } : {}),
          ...(saveAvatar ? { avatarMediaId: s.avatarMediaId, avatarPosition } : {}),
        });
        if (saveCover) s.coverSaved = true;
        if (saveAvatar) s.avatarSaved = true;
      } catch (err) {
        issues.push(`Chưa gắn được ảnh vào lớp (${errorText(err, 'lỗi không xác định')}).`);
      }
    }
    setProblems(issues);
  }, [paid, price, cover, avatar, coverPosition, avatarPosition]);

  const create = async () => {
    if (!canCreate || submitting || created) return;
    setSubmitting(true);
    setCreateError(null);
    let cls: Classroom;
    try {
      cls = await api.post<Classroom>('/classes', {
        title: name.trim(),
        description: description.trim(),
        visibility,
        category,
        requireApproval: approval,
        ...(cover ? { coverPosition } : {}),
        ...(avatar ? { avatarPosition } : {}),
      });
    } catch (err) {
      setCreateError(errorText(err, 'Không tạo được lớp học. Bạn thử lại sau ít phút nhé.'));
      setSubmitting(false);
      return;
    }
    await finishSetup(cls);
    setCreated(cls);
    setSubmitting(false);
  };

  const retry = async () => {
    if (!created) return;
    setRetrying(true);
    await finishSetup(created);
    setRetrying(false);
  };

  const close = () => {
    // Back to where the person came from; a direct visit (no in-app history) goes home.
    if (location.key !== 'default') navigate(-1);
    else navigate('/classes');
  };

  const owner = { fullName: user?.fullName || 'Bạn', avatarUrl: user?.avatarUrl };
  const model: PreviewModel = {
    name, description, category, isPrivate: visibility === 'PRIVATE', priceTag, approval, owner,
    cover, coverPosition, avatar, avatarPosition,
    onPickCover, onPickAvatar, onCoverPosition: setCoverPosition, onAvatarPosition: setAvatarPosition,
  };

  const seg = (on: boolean) => ({ background: on ? '#FFFFFF' : 'transparent', color: on ? BLACK : '#475569', boxShadow: on ? '0 1px 3px rgba(15,23,42,.12)' : 'none' });

  return (
    <div className="grid min-h-screen w-full grid-cols-[420px_minmax(0,1fr)] bg-white text-[#0F172A] antialiased max-[1000px]:grid-cols-[minmax(0,1fr)]">
      <style>{'@keyframes ccIn{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}.cc-in{animation:ccIn .25s ease both}'}</style>

      <aside className="sticky top-0 flex h-screen flex-col border-r border-[#E9EEF4] bg-white">
        <div className="flex flex-none items-center gap-3 border-b border-[#EEF1F5] px-5 py-3.5">
          <button
            type="button"
            onClick={close}
            title="Đóng"
            aria-label="Đóng"
            className="flex h-9 w-9 flex-none items-center justify-center rounded-full bg-[#F1F5F9] text-[#0F172A] hover:bg-[#E2E8F0]"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true">
              <path d="M6 6l12 12M18 6 6 18" />
            </svg>
          </button>
          <Link to="/classes" className="flex min-w-0 items-center gap-2 text-[#0F172A] hover:text-[#334155]" aria-label="Lớp Học Trực Tuyến - về trang chủ">
            <span className="flex h-6 w-6 flex-none items-center justify-center rounded-[7px] bg-gradient-to-tr from-blue-600 to-sky-400 text-white">
              <GraduationCap className="h-4 w-4" strokeWidth={2} aria-hidden="true" />
            </span>
            <span className="truncate text-[15px] font-bold tracking-[-0.3px]">Lớp Học Trực Tuyến</span>
          </Link>
        </div>

        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-5 pb-6 pt-5">
          <div>
            <nav aria-label="Breadcrumb" className="text-[12.5px] leading-4 text-[#64748B]">
              <Link to="/classes" className="text-[#64748B] hover:text-[#334155]">Trang chủ</Link> › Tạo lớp học
            </nav>
            <h1 className="mt-1 text-[24px] font-bold leading-[30px] tracking-[-0.5px]">Tạo lớp học</h1>
            <p className="mt-1.5 text-[13.5px] leading-5 text-[#64748B] [text-wrap:pretty]">Điền bên trái, kết quả hiện ngay bên phải. Mọi thứ sửa được sau.</p>
          </div>

          <Box valid={nameOk} className="flex items-center gap-2.5 px-3.5 py-2.5">
            <label htmlFor={nameId} className="flex min-w-0 flex-1 flex-col gap-0.5">
              <span className={fieldLabel}>Tên lớp học · bắt buộc</span>
              <input
                id={nameId}
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Ví dụ: Bếp cơm nhà"
                maxLength={NAME_MAX}
                className="border-0 bg-transparent p-0 text-[15.5px] font-medium leading-[22px] text-[#0F172A] outline-none placeholder:text-[#94A3B8] focus:outline-none focus-visible:outline-none"
              />
            </label>
            {nameOk && <Check />}
          </Box>

          <Box valid={catOk} className="px-3.5 pb-3 pt-2.5">
            <div className="flex items-center justify-between gap-2.5">
              <span className={fieldLabel} id={`${nameId}-cat`}>Lĩnh vực · bắt buộc</span>
              {catOk && <Check />}
            </div>
            <div role="group" aria-labelledby={`${nameId}-cat`} className="mt-2 flex flex-wrap gap-1.5">
              {categories.map((label) => {
                const on = category === label;
                return (
                  <button
                    key={label}
                    type="button"
                    onClick={() => setCategory(label)}
                    aria-pressed={on}
                    className="h-[30px] cursor-pointer whitespace-nowrap rounded-full px-3 text-[12.5px] font-medium leading-4"
                    style={{ border: `1px solid ${on ? BLACK : LINE}`, background: on ? BLACK : '#FFFFFF', color: on ? '#FFFFFF' : BLACK }}
                  >
                    {label}
                  </button>
                );
              })}
            </div>
          </Box>

          <Box className="px-3.5 py-2.5">
            <label htmlFor={descId} className="flex flex-col gap-0.5">
              <span className={`flex justify-between ${fieldLabel}`}>
                <span>Mô tả ngắn</span>
                <span className="tabular-nums" data-testid="desc-count">{description.length}/{DESC_MAX}</span>
              </span>
              <textarea
                id={descId}
                value={description}
                onChange={(e) => setDescription(e.target.value.slice(0, DESC_MAX))}
                placeholder="Ai nên tham gia và họ sẽ đạt được gì."
                maxLength={DESC_MAX}
                rows={3}
                className="resize-none border-0 bg-transparent p-0 text-[14.5px] leading-[21px] text-[#0F172A] outline-none placeholder:text-[#94A3B8] focus:outline-none focus-visible:outline-none"
              />
            </label>
          </Box>

          <div className="flex items-start gap-2.5 rounded-xl border border-[#E2E8F0] bg-[#F8FAFC] px-3.5 py-3">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke={BLACK} strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" className="mt-px flex-none" aria-hidden="true">
              <rect x="3.5" y="5.5" width="17" height="13" rx="2.5" />
              <path d="m3.5 15.5 4.6-4.6a1.5 1.5 0 0 1 2.1 0l5.3 5.3M14 13l1.9-1.9a1.5 1.5 0 0 1 2.1 0l2.5 2.5" />
              <circle cx="15.5" cy="9" r="1.3" />
            </svg>
            <p className="text-[12.5px] leading-[18px] text-[#334155] [text-wrap:pretty]">
              <b className="font-semibold text-[#0F172A]">Ảnh bìa và ảnh đại diện</b>
              <span className="max-[1000px]:hidden"> thêm ngay trên bản xem trước bên phải</span>
              <span className="min-[1001px]:hidden"> thêm ngay bên dưới</span>: kéo ảnh vào khung, rồi dùng{' '}
              <b className="font-semibold text-[#0F172A]">Căn ảnh</b> để kéo chỉnh vị trí, <b className="font-semibold text-[#0F172A]">Đổi ảnh</b> để thay ảnh khác.
            </p>
          </div>

          {/* Narrow screens: the preview column is hidden, so the image slots live here. */}
          <div className="hidden rounded-xl border border-[#E2E8F0] px-3.5 pb-3.5 pt-3 max-[1000px]:block" data-testid="narrow-media">
            <div className="text-[13.5px] font-medium leading-[18px]">Ảnh bìa</div>
            <div className={`mt-2 aspect-[16/6] overflow-hidden rounded-[10px] bg-[#F1F5F9] ${cover ? '' : 'border border-dashed border-[#CBD5E1]'}`}>
              <ImageSlot label="Ảnh bìa" testId="slot-cover-narrow" image={cover} position={coverPosition} onPick={onPickCover} onPositionChange={setCoverPosition} placeholder="Kéo ảnh vào đây hoặc bấm để chọn" />
            </div>
            <div className="mt-3.5 text-[13.5px] font-medium leading-[18px]">Ảnh đại diện</div>
            <div className="mt-2 flex items-center gap-3">
              <div className={`h-24 w-24 flex-none overflow-hidden rounded-2xl bg-[#F1F5F9] ${avatar ? '' : 'border border-dashed border-[#CBD5E1]'}`}>
                <ImageSlot label="Ảnh đại diện" testId="slot-avatar-narrow" image={avatar} position={avatarPosition} onPick={onPickAvatar} onPositionChange={setAvatarPosition} />
              </div>
              <p className="text-[12.5px] leading-[18px] text-[#64748B] [text-wrap:pretty]">
                Hình vuông. Dùng <b className="font-semibold text-[#0F172A]">Căn ảnh</b> để kéo chỉnh, <b className="font-semibold text-[#0F172A]">Đổi ảnh</b> để thay.
              </p>
            </div>
          </div>

          <div className="flex flex-col gap-2.5 border-t border-[#EEF1F5] pt-4">
            <h2 className="text-[12px] font-semibold uppercase leading-4 tracking-[0.4px] text-[#64748B]">Quyền và phí</h2>
            <div role="group" aria-label="Ai thấy lớp" className="grid grid-cols-2 gap-2">
              <Choice on={visibility === 'PUBLIC'} title="Công khai" desc="Ai cũng tìm thấy và xem trước được." onClick={() => setVisibility('PUBLIC')} />
              <Choice on={visibility === 'PRIVATE'} title="Riêng tư" desc="Ẩn khỏi khám phá, vào bằng liên kết mời." onClick={() => setVisibility('PRIVATE')} />
            </div>
            <div role="group" aria-label="Học phí" className="grid grid-cols-2 gap-2">
              <Choice on={!paid} title="Miễn phí" desc="Vào ngay, không thanh toán." onClick={() => setFee('FREE')} />
              <Choice on={paid} title="Có phí" desc="Thu phí thành viên theo tháng." onClick={() => setFee('PAID')} />
            </div>
            {paid && (
              <Box valid={priceValue(price) > 0} className="cc-in flex items-center gap-2.5 px-3.5 py-2.5">
                <label htmlFor={priceId} className="flex min-w-0 flex-1 flex-col gap-0.5">
                  <span className={fieldLabel}>Phí mỗi tháng · bắt buộc</span>
                  <input
                    id={priceId}
                    value={price}
                    onChange={(e) => setPrice(formatPriceInput(e.target.value))}
                    inputMode="numeric"
                    placeholder="199.000"
                    className="border-0 bg-transparent p-0 text-[15.5px] font-semibold leading-[22px] tabular-nums text-[#0F172A] outline-none placeholder:text-[#94A3B8] focus:outline-none focus-visible:outline-none"
                  />
                </label>
                <span className="text-[14px] font-medium leading-5 text-[#64748B]">đ</span>
                {priceValue(price) > 0 && <Check />}
              </Box>
            )}
            <div className="flex items-center justify-between gap-3 px-0.5 py-1">
              <span>
                <span className="block text-[13.5px] font-medium leading-[18px]" id={`${nameId}-approve`}>Duyệt từng người trước khi vào</span>
                <span className="block text-[12px] leading-4 text-[#64748B]">
                  {approvalApplies ? 'Bạn duyệt trong Studio › Thành viên.' : 'Chỉ áp dụng cho lớp công khai miễn phí.'}
                </span>
              </span>
              <button
                type="button"
                role="switch"
                aria-checked={approve}
                aria-labelledby={`${nameId}-approve`}
                onClick={() => setApprove(!approve)}
                className="relative h-6 w-10 flex-none cursor-pointer rounded-full border-0 transition-colors duration-150"
                style={{ background: approve ? BLACK : '#CBD5E1' }}
              >
                <span
                  className="absolute top-[3px] h-[18px] w-[18px] rounded-full bg-white shadow-[0_1px_3px_rgba(15,23,42,.25)] transition-[left] duration-150"
                  style={{ left: approve ? 19 : 3 }}
                />
              </button>
            </div>
          </div>
        </div>

        <div className="flex-none border-t border-[#EEF1F5] bg-white px-5 pb-4 pt-3.5">
          {createError && (
            <p role="alert" className="mb-2.5 rounded-[10px] border border-red-200 bg-red-50 px-3 py-2 text-[12.5px] leading-[18px] text-red-700">{createError}</p>
          )}
          <button
            type="button"
            onClick={() => void create()}
            aria-disabled={!canCreate || submitting}
            className="h-[46px] w-full cursor-pointer rounded-xl border-0 bg-[#0F172A] text-[15px] font-semibold leading-5 text-white"
            style={{ opacity: canCreate && !submitting ? 1 : 0.5 }}
          >
            {submitting ? 'Đang tạo lớp học…' : 'Tạo lớp học'}
          </button>
          <p className="mt-2 text-center text-[12px] leading-4 text-[#64748B] [text-wrap:pretty]" data-testid="create-hint">{createHint}</p>
        </div>
      </aside>

      <div className="min-w-0 bg-[#F6F8FB] px-7 pb-12 pt-6 max-[1000px]:hidden" data-testid="preview-col">
        <div className="mx-auto max-w-[1120px]">
          <div className="mb-3.5 flex items-center justify-between gap-3">
            <h2 className="text-[15px] font-semibold leading-5">Xem trước {device === 'mobile' ? 'trên điện thoại' : 'trên máy tính'}</h2>
            <div role="group" aria-label="Thiết bị xem trước" className="inline-flex rounded-full bg-[#E9EEF4] p-[3px]">
              {(['desktop', 'mobile'] as const).map((key) => (
                <button
                  key={key}
                  type="button"
                  onClick={() => setDevice(key)}
                  title={key === 'desktop' ? 'Máy tính' : 'Điện thoại'}
                  aria-label={key === 'desktop' ? 'Xem trên máy tính' : 'Xem trên điện thoại'}
                  aria-pressed={device === key}
                  className="flex h-[30px] w-[34px] cursor-pointer items-center justify-center rounded-full border-0"
                  style={seg(device === key)}
                >
                  {key === 'desktop' ? (
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                      <rect x="3" y="4.5" width="18" height="12" rx="2" />
                      <path d="M8 20h8M12 16.5V20" />
                    </svg>
                  ) : (
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                      <rect x="7" y="3" width="10" height="18" rx="2.5" />
                      <path d="M11 17.5h2" />
                    </svg>
                  )}
                </button>
              ))}
            </div>
          </div>
          {device === 'desktop' ? <DesktopPreview model={model} /> : <MobilePreview model={model} />}
        </div>
      </div>

      {created && (
        <DoneDialog
          cls={created}
          name={name.trim()}
          avatar={avatar}
          avatarPosition={avatarPosition}
          isPrivate={visibility === 'PRIVATE'}
          paidLabel={paid && setup.current.accessDone ? `${price}đ/tháng` : null}
          problems={problems}
          retrying={retrying}
          onRetry={() => void retry()}
        />
      )}
    </div>
  );
};

const DoneDialog: React.FC<{
  cls: Classroom;
  name: string;
  avatar: PickedImage | null;
  avatarPosition: string;
  isPrivate: boolean;
  paidLabel: string | null;
  problems: string[];
  retrying: boolean;
  onRetry: () => void;
}> = ({ cls, name, avatar, avatarPosition, isPrivate, paidLabel, problems, retrying, onRetry }) => {
  const titleId = useId();
  const primaryRef = useRef<HTMLAnchorElement>(null);
  useEffect(() => { primaryRef.current?.focus({ preventScroll: true }); }, []);
  const title = name || cls.title;
  const initial = (title.trim()[0] || 'L').toUpperCase();
  const studio = `/studio/classes/${cls.id}`;
  const sub = [
    paidLabel
      ? `Lớp học có phí ${paidLabel} đã sẵn sàng: thành viên thanh toán để vào lớp, rồi học, thảo luận và thi ngay.`
      : 'Lớp học miễn phí đã sẵn sàng: thảo luận, khóa học, thi và sự kiện.',
    isPrivate ? 'Lớp riêng tư chỉ mở cho người có liên kết mời.' : '',
    'Mở Studio bất cứ lúc nào để quản trị lớp.',
  ].filter(Boolean).join(' ');
  const steps = [
    { text: 'Đăng bài chào mừng trong Thảo luận để thành viên đầu tiên biết bắt đầu từ đâu', to: `/classes/${cls.slug}/feed` },
    { text: 'Tạo khóa học đầu tiên trong Studio', to: `${studio}/courses` },
    { text: isPrivate ? 'Tạo liên kết mời và gửi cho người bạn muốn mời vào lớp' : 'Mời 5 người bạn tin nhất vào lớp', to: `${studio}/members` },
  ];

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center overflow-y-auto bg-[rgba(15,23,42,0.5)] p-6">
      <div role="dialog" aria-modal="true" aria-labelledby={titleId} className="cc-in w-full max-w-[520px] rounded-[22px] bg-white p-7">
        <div className="flex items-center gap-3.5">
          <div className="flex h-[52px] w-[52px] flex-none items-center justify-center overflow-hidden rounded-[14px] bg-[#0F172A] text-[22px] font-bold leading-[26px] text-white">
            {avatar ? <img src={avatar.url} alt="" className="h-full w-full object-cover" style={{ objectPosition: avatarPosition }} /> : initial}
          </div>
          <div className="min-w-0">
            <h2 id={titleId} className="text-[20px] font-bold leading-[26px] tracking-[-0.3px]">{title} đã mở</h2>
            <p className="mt-[3px] text-[13.5px] leading-[19px] text-[#475569] [text-wrap:pretty]">{sub}</p>
          </div>
        </div>

        {problems.length > 0 && (
          <div role="alert" className="mt-5 rounded-xl border border-amber-200 bg-amber-50 px-3.5 py-3 text-[13px] leading-[19px] text-amber-900">
            <p className="font-semibold">Lớp học đã được tạo, nhưng còn việc chưa xong:</p>
            <ul className="mt-1 list-disc space-y-0.5 pl-5">
              {problems.map((p) => <li key={p}>{p}</li>)}
            </ul>
            <div className="mt-2.5 flex flex-wrap items-center gap-3">
              <button
                type="button"
                onClick={onRetry}
                disabled={retrying}
                className="h-8 rounded-full border border-amber-300 bg-white px-3.5 text-[12.5px] font-semibold text-amber-900 hover:bg-amber-100 disabled:opacity-60"
              >
                {retrying ? 'Đang thử lại…' : 'Thử lại'}
              </button>
              <Link to={`${studio}/settings`} className="text-[12.5px] font-semibold text-amber-900 underline underline-offset-2">
                Làm tiếp trong Studio › Cài đặt
              </Link>
            </div>
          </div>
        )}

        <h3 className="mt-[22px] text-[12px] font-semibold uppercase leading-4 tracking-[0.4px] text-[#64748B]">Ba việc đầu tiên</h3>
        <ol className="mt-2.5 flex flex-col gap-2">
          {steps.map((s, i) => (
            <li key={s.to}>
              <Link to={s.to} className="flex items-start gap-2.5 rounded-xl border border-[#E9EEF4] px-3 py-2.5 text-[14px] leading-5 text-[#0F172A] hover:bg-[#F8FAFC] hover:text-[#0F172A]">
                <span className="flex h-[22px] w-[22px] flex-none items-center justify-center rounded-full bg-[#F1F5F9] text-[11.5px] font-semibold leading-[14px]">{i + 1}</span>
                <span>{s.text}</span>
              </Link>
            </li>
          ))}
        </ol>
        <div className="mt-5 flex flex-wrap gap-2.5">
          <Link
            ref={primaryRef}
            to={`/classes/${cls.slug}/feed`}
            className="flex h-11 min-w-[180px] flex-1 items-center justify-center whitespace-nowrap rounded-full bg-[#0F172A] text-[14.5px] font-semibold leading-5 text-white hover:bg-[#1E293B] hover:text-white"
          >
            Vào lớp học
          </Link>
          <Link
            to="/classes"
            className="flex h-11 min-w-[140px] flex-1 items-center justify-center whitespace-nowrap rounded-full border border-[#CBD5E1] bg-white text-[14.5px] font-semibold leading-5 text-[#0F172A] hover:bg-[#F1F5F9] hover:text-[#0F172A]"
          >
            Về trang chủ
          </Link>
        </div>
      </div>
    </div>
  );
};
