import React from 'react';
import { GraduationCap } from 'lucide-react';
import { CLASS_TABS } from '../ClassroomHeader';
import { Avatar } from '../ui';
import { ImageSlot, PickedImage } from './ImageSlot';

// Live preview of the class page being created (Tạo lớp học design, right column): the same header, tabs and first
// screen a member would see, filled from the form as you type. Image slots are live: drop / pick / "Căn ảnh".

export interface PreviewModel {
  name: string;
  description: string;
  category: string;
  isPrivate: boolean;
  priceTag: string;
  approval: boolean;
  owner: { fullName: string; avatarUrl?: string | null };
  cover: PickedImage | null;
  coverPosition: string;
  avatar: PickedImage | null;
  avatarPosition: string;
  onPickCover: (file: File) => void;
  onPickAvatar: (file: File) => void;
  onCoverPosition: (position: string) => void;
  onAvatarPosition: (position: string) => void;
}

const NAME_FALLBACK = 'Tên lớp học';
const DESC_FALLBACK = 'Mô tả ngắn sẽ hiện ở đây. Viết một hoặc hai câu về người nên tham gia và họ đạt được gì.';
/** A new class opens on Thảo luận (the class route's index is /feed). */
const ACTIVE_TAB = 'feed';

const derive = (m: PreviewModel) => {
  const named = m.name.trim();
  const described = m.description.trim();
  return {
    nameOr: named || NAME_FALLBACK,
    nameFg: named ? 'text-[#0F172A]' : 'text-[#64748B]',
    initial: named ? named[0].toUpperCase() : 'L',
    descOr: described || DESC_FALLBACK,
    descFg: described ? 'text-[#0F172A]' : 'text-[#64748B]',
    visLabel: m.isPrivate ? 'Riêng tư' : 'Công khai',
    joinLabel: m.approval ? 'Xin tham gia' : 'Tham gia',
    approveLabel: m.approval ? 'duyệt từng người' : 'vào ngay',
  };
};

const BrandMark: React.FC<{ size?: number }> = ({ size = 20 }) => (
  <span
    aria-hidden="true"
    style={{ width: size, height: size, borderRadius: Math.round(size * 0.3) }}
    className="flex flex-none items-center justify-center bg-gradient-to-tr from-blue-600 to-sky-400 text-white"
  >
    <GraduationCap style={{ width: size * 0.62, height: size * 0.62 }} strokeWidth={2} />
  </span>
);

const Tabs: React.FC<{ mobile?: boolean }> = ({ mobile = false }) => (
  // The strip scrolls sideways (all 10 tabs do not fit a phone), so it is focusable to be scrollable from the keyboard too
  // (WCAG 2.1.1, axe "scrollable-region-focusable").
  <div
    role="group"
    aria-label="Các khu vực của lớp (xem trước)"
    tabIndex={0}
    data-testid={mobile ? 'preview-tabs-mobile' : 'preview-tabs-desktop'}
    className={`scrollbar-none flex gap-0.5 overflow-x-auto border-b border-[#EEF1F5] ${mobile ? 'mt-3' : 'mt-3.5'}`}
  >
    {CLASS_TABS.map((tab) => {
      const active = tab.key === ACTIVE_TAB;
      return (
        <span
          key={tab.key}
          className={`relative inline-flex flex-none items-center whitespace-nowrap font-semibold ${
            mobile ? 'h-10 px-3 text-[13.5px] leading-[18px]' : 'h-[42px] px-3.5 text-[14px] leading-[18px]'
          } ${active ? 'text-[#0F172A]' : 'text-[#64748B]'}`}
        >
          {tab.label}
          <span className={`absolute bottom-0 h-[3px] ${mobile ? 'left-3 right-3' : 'left-3.5 right-3.5'} ${active ? 'bg-[#0F172A]' : 'bg-transparent'}`} />
        </span>
      );
    })}
  </div>
);

const SearchIcon: React.FC<{ size: number; color: string }> = ({ size, color }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" aria-hidden="true" className="flex-none">
    <circle cx="10.6" cy="10.6" r="7.1" />
    <path d="m20.5 20.5-4.8-4.8" />
  </svg>
);

const avatarFrame = (filled: boolean) => (filled ? '' : 'border border-dashed border-[#CBD5E1]');

export const DesktopPreview: React.FC<{ model: PreviewModel }> = ({ model: m }) => {
  const d = derive(m);
  return (
    <div className="overflow-hidden rounded-[14px] border border-[#E9EEF4] bg-white" data-testid="preview-desktop">
      <div className="grid h-[52px] grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-4 border-b border-[#EEF1F5] px-4">
        <div className="flex min-w-0 items-center gap-3">
          <span className="flex flex-none items-center gap-2">
            <BrandMark />
            <span className="whitespace-nowrap text-[13.5px] font-bold tracking-[-0.3px] text-[#0F172A]">Lớp Học Trực Tuyến</span>
          </span>
          <span className="h-[22px] w-px bg-[#E2E8F0]" />
          <span className="flex min-w-0 items-center gap-[7px]">
            <span className="flex h-5 w-5 flex-none items-center justify-center rounded-[5px] bg-[#0F172A] text-[10px] font-bold leading-3 text-white">{d.initial}</span>
            <span className="max-w-[160px] truncate text-[13.5px] font-semibold leading-[18px]">{d.nameOr}</span>
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#64748B" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="m6 9 6 6 6-6" />
            </svg>
          </span>
        </div>
        <div className="flex min-w-0 justify-center">
          <span className="flex h-9 w-full max-w-[420px] items-center gap-2 overflow-hidden whitespace-nowrap rounded-full bg-[#F1F5F9] px-3.5 text-[13px] leading-[18px] text-[#475569]">
            <SearchIcon size={15} color="#64748B" />
            <span className="truncate">Tìm trong {d.nameOr}</span>
          </span>
        </div>
        <Avatar name={m.owner.fullName} src={m.owner.avatarUrl} size={30} />
      </div>

      <div className="px-4 pt-3.5">
        <div className="aspect-[4/1] overflow-hidden rounded-xl bg-[#F1F5F9]">
          <ImageSlot
            label="Ảnh bìa"
            testId="slot-cover-desktop"
            image={m.cover}
            position={m.coverPosition}
            onPick={m.onPickCover}
            onPositionChange={m.onCoverPosition}
            placeholder="Kéo ảnh bìa vào đây hoặc bấm để chọn · ngang 4:1"
          />
        </div>
        <div className="mt-4 flex flex-wrap items-start justify-between gap-4">
          <div className="flex min-w-0 items-center gap-3.5">
            <div className={`h-20 w-20 flex-none overflow-hidden rounded-2xl bg-[#F1F5F9] ${avatarFrame(!!m.avatar)}`}>
              <ImageSlot
                label="Ảnh đại diện"
                testId="slot-avatar-desktop"
                image={m.avatar}
                position={m.avatarPosition}
                onPick={m.onPickAvatar}
                onPositionChange={m.onAvatarPosition}
              />
            </div>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <span className={`text-[22px] font-bold leading-7 tracking-[-0.4px] ${d.nameFg}`} data-testid="preview-name">{d.nameOr}</span>
                <span className="whitespace-nowrap rounded-full bg-[#F1F5F9] px-[9px] py-1 text-[11.5px] font-semibold leading-[14px] text-[#0F172A]" data-testid="preview-tag">
                  {d.visLabel} · {m.priceTag}
                </span>
              </div>
              <div className="mt-[3px] flex flex-wrap items-center gap-1.5 text-[13.5px] leading-[18px] text-[#64748B]">
                <span>Dẫn dắt bởi <b className="font-semibold text-[#0F172A]">{m.owner.fullName}</b></span>
                <span>·</span>
                <span>1 thành viên</span>
              </div>
            </div>
          </div>
          <div className="flex flex-none gap-2">
            <span className="inline-flex h-10 items-center whitespace-nowrap rounded-full border border-[#CBD5E1] px-3.5 text-[14px] font-semibold leading-5 text-[#0F172A]">Chia sẻ</span>
            <span className="inline-flex h-10 items-center whitespace-nowrap rounded-full bg-[#0F172A] px-[18px] text-[14px] font-semibold leading-5 text-white" data-testid="preview-join">
              {d.joinLabel}
            </span>
          </div>
        </div>
        <p className={`mt-3 text-[14px] leading-5 ${d.descFg}`} data-testid="preview-desc">{d.descOr}</p>
        <Tabs />
      </div>

      <div className="grid grid-cols-[minmax(0,1.9fr)_minmax(0,1fr)] gap-4 bg-[#F6F8FB] p-4">
        <div className="flex min-w-0 flex-col gap-3">
          <div className="flex items-center gap-2.5 rounded-xl border border-[#E9EEF4] bg-white px-3.5 py-3">
            <Avatar name={m.owner.fullName} src={m.owner.avatarUrl} size={36} />
            <span className="flex h-[38px] min-w-0 flex-1 items-center truncate rounded-full bg-[#F1F5F9] px-3.5 text-[13.5px] leading-[18px] text-[#475569]">
              Chào mừng thành viên đầu tiên của bạn…
            </span>
            <span className="inline-flex h-[38px] items-center whitespace-nowrap rounded-full bg-[#0F172A] px-3.5 text-[13px] font-semibold leading-[18px] text-white">Tạo bài đăng</span>
          </div>
          <div className="rounded-xl border border-[#E9EEF4] bg-white px-4 py-[26px] text-center text-[13.5px] leading-[19px] text-[#64748B]">
            Chưa có bài viết. Bài chào mừng là việc đầu tiên sau khi mở lớp học.
          </div>
        </div>
        <div className="min-w-0 self-start rounded-xl border border-[#E9EEF4] bg-white px-4 py-3.5">
          <div className="text-[15px] font-semibold leading-5">Giới thiệu</div>
          <div className="mt-2.5 flex flex-col gap-2 text-[13px] leading-[18px] text-[#334155]">
            <div className="flex items-center gap-2">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#64748B" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M12 3l2 5.3 5.5.4-4.2 3.6 1.3 5.4L12 14.8 7.4 17.7l1.3-5.4L4.5 8.7 10 8.3z" />
              </svg>
              <span data-testid="preview-category">{m.category || 'Lĩnh vực'}</span>
            </div>
            <div className="flex items-center gap-2">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#64748B" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <circle cx="12" cy="12" r="8.5" />
                <path d="M12 8v4.5l3 1.8" />
              </svg>
              <span>{d.visLabel} · {d.approveLabel}</span>
            </div>
            <div className="flex items-center gap-2">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#64748B" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <circle cx="9" cy="8.4" r="2.9" />
                <path d="M3.8 19c0-2.9 2.3-4.8 5.2-4.8s5.2 1.9 5.2 4.8" />
                <path d="M16.2 6.2a2.9 2.9 0 0 1 0 5.4M18.2 19c0-2.2-.8-3.7-2.2-4.5" />
              </svg>
              1 thành viên
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export const MobilePreview: React.FC<{ model: PreviewModel }> = ({ model: m }) => {
  const d = derive(m);
  return (
    <div className="mx-auto w-[390px] max-w-full overflow-hidden rounded-[22px] border border-[#E9EEF4] bg-white" data-testid="preview-mobile">
      <div className="flex h-12 items-center justify-between gap-3 px-3.5">
        <span className="flex min-w-0 items-center gap-2">
          <BrandMark size={22} />
          <span className="truncate text-[14px] font-bold tracking-[-0.3px] text-[#0F172A]">Lớp Học Trực Tuyến</span>
        </span>
        <span className="flex flex-none items-center gap-3">
          <SearchIcon size={20} color="#0F172A" />
          <Avatar name={m.owner.fullName} src={m.owner.avatarUrl} size={28} />
        </span>
      </div>
      <div className="px-3">
        <div className="aspect-video overflow-hidden rounded-xl bg-[#F1F5F9]">
          <ImageSlot
            label="Ảnh bìa"
            testId="slot-cover-mobile"
            image={m.cover}
            position={m.coverPosition}
            onPick={m.onPickCover}
            onPositionChange={m.onCoverPosition}
            placeholder="Ảnh bìa"
          />
        </div>
        <div className="mt-3 flex items-center gap-3">
          <div className={`h-16 w-16 flex-none overflow-hidden rounded-[14px] bg-[#F1F5F9] ${avatarFrame(!!m.avatar)}`}>
            <ImageSlot
              label="Ảnh đại diện"
              testId="slot-avatar-mobile"
              image={m.avatar}
              position={m.avatarPosition}
              onPick={m.onPickAvatar}
              onPositionChange={m.onAvatarPosition}
            />
          </div>
          <div className="min-w-0 flex-1">
            <div className={`truncate text-[18px] font-bold leading-[23px] tracking-[-0.3px] ${d.nameFg}`}>{d.nameOr}</div>
            <div className="mt-0.5 truncate text-[12.5px] leading-[17px] text-[#64748B]">{d.visLabel} · {m.priceTag} · 1 thành viên</div>
          </div>
        </div>
        <p className={`mt-2.5 line-clamp-2 text-[13.5px] leading-[19px] ${d.descFg}`}>{d.descOr}</p>
        <div className="mt-3 flex gap-2">
          <span className="flex h-10 flex-1 items-center justify-center whitespace-nowrap rounded-full bg-[#0F172A] text-[14px] font-semibold leading-5 text-white">{d.joinLabel}</span>
          <span className="flex h-10 w-10 flex-none items-center justify-center rounded-full border border-[#CBD5E1]" title="Chia sẻ">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#0F172A" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M4 12v7a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-7" />
              <path d="M12 3v13M7 8l5-5 5 5" />
            </svg>
          </span>
        </div>
        <Tabs mobile />
      </div>
      <div className="flex flex-col gap-2.5 bg-[#F6F8FB] p-3">
        <div className="flex items-center gap-2.5 rounded-xl border border-[#E9EEF4] bg-white px-3 py-2.5">
          <Avatar name={m.owner.fullName} src={m.owner.avatarUrl} size={32} />
          <span className="flex h-[34px] min-w-0 flex-1 items-center truncate rounded-full bg-[#F1F5F9] px-3 text-[13px] leading-[18px] text-[#475569]">
            Chào mừng thành viên đầu tiên…
          </span>
        </div>
        <div className="rounded-xl border border-[#E9EEF4] bg-white px-3.5 py-[22px] text-center text-[13px] leading-[18px] text-[#64748B]">Chưa có bài viết.</div>
      </div>
    </div>
  );
};
