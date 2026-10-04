import React, { useCallback, useEffect, useRef, useState } from 'react';
import { NavLink, Link } from 'react-router-dom';
import { Classroom } from '../types';
import { ClassBadges } from './ClassBadges';
import { SuspendedChip, isSuspended } from './SuspendedNotice';
import { ClassAvatar, CoverImage, buttonClass } from './ui';
import { daysUntil, formatDate } from '../api/format';
import { Check, Clock, GraduationCap, Hourglass, LayoutDashboard, Lock, Share2, Tag } from 'lucide-react';

// D-19: a paid member is told about the end of their access this many days ahead (gently - a chip, not a banner).
export const EXPIRY_WARNING_DAYS = 7;

const POSITION = /^\d{1,3}% \d{1,3}%$/;
/** A stored "x% y%" object-position, or undefined (centred) for anything else. */
export const objectPosition = (value?: string | null): string | undefined => (value && POSITION.test(value) ? value : undefined);

/**
 * The class tabs, in the design's order ("Ngôi nhà" tab strip). Route paths never change: only the labels follow the
 * design (feed = Thảo luận, learn = Khóa học, exams = Thi, store = Shop, leaderboard = Bảng xếp hạng).
 */
export const CLASS_TABS: { key: string; label: string }[] = [
  { key: 'blog', label: 'Blog' },
  { key: 'feed', label: 'Thảo luận' },
  { key: 'learn', label: 'Khóa học' },
  { key: 'exams', label: 'Thi' },
  { key: 'events', label: 'Sự kiện' },
  { key: 'documents', label: 'Tài liệu' },
  { key: 'members', label: 'Thành viên' },
  { key: 'store', label: 'Shop' },
  { key: 'leaderboard', label: 'Bảng xếp hạng' },
  { key: 'about', label: 'Giới thiệu' },
];

const ROLE_LABEL: Record<string, string> = {
  OWNER: 'Chủ lớp',
  STAFF: 'Trợ giảng',
};

// R16-01: hideTabs is set for a BLOCKED person, who must not be offered any of the class's member tabs.
// D-19: allowedTabs limits the strip to the named tabs (an EXPIRED member only has Giới thiệu and Shop).
// lockedTabs are shown but not navigable (the design's locked tab for a visitor: grey text + a small lock).
export const ClassroomHeader: React.FC<{
  classroom: Classroom;
  hideTabs?: boolean;
  allowedTabs?: string[];
  lockedTabs?: string[];
}> = ({ classroom, hideTabs = false, allowedTabs, lockedTabs = [] }) => {
  const canAccessStudio = classroom.userRole === 'OWNER' || classroom.userRole === 'STAFF';
  const isActiveMember = !!classroom.isOwner || (!!classroom.isMember && classroom.memberState !== 'EXPIRED');
  const lapsed = classroom.memberState === 'EXPIRED' && !classroom.isMember;
  // D-19 (e): an ACTIVE paid member whose access ends within a week. The owner / staff never expire.
  const daysLeft = classroom.isMember && !classroom.isOwner && !canAccessStudio && classroom.memberState !== 'EXPIRED'
    ? daysUntil(classroom.accessExpiresAt)
    : Number.NaN;
  const expiresSoon = Number.isFinite(daysLeft) && daysLeft > 0 && daysLeft <= EXPIRY_WARNING_DAYS;
  const coverSrc = classroom.coverUrl ?? classroom.coverImageUrl ?? null;
  const roleLabel = classroom.userRole ? ROLE_LABEL[classroom.userRole] : undefined;
  const chip = coverSrc ? 'chip-glass text-white' : 'bg-white/90 text-slate-900';
  const pending = classroom.memberState === 'PENDING' && !classroom.isMember && !classroom.isOwner;
  // An uploaded cover / avatar that fails to load falls back to the topic tile / letter tile.
  const [coverFailed, setCoverFailed] = useState(false);
  const [avatarFailed, setAvatarFailed] = useState(false);
  useEffect(() => setCoverFailed(false), [coverSrc]);
  useEffect(() => setAvatarFailed(false), [classroom.avatarUrl]);

  // "Chia sẻ": copies the class link. The confirmation is announced politely and fades after a moment.
  const [shareState, setShareState] = useState<'idle' | 'copied' | 'failed'>('idle');
  useEffect(() => {
    if (shareState === 'idle') return;
    const t = window.setTimeout(() => setShareState('idle'), 2500);
    return () => window.clearTimeout(t);
  }, [shareState]);
  const handleShare = async () => {
    const url = `${window.location.origin}/classes/${classroom.slug}`;
    try {
      await navigator.clipboard.writeText(url);
      setShareState('copied');
    } catch {
      setShareState('failed');
    }
  };

  // R17-05: at phone width the tabs overflow and scroll sideways with no visible cue (the native
  // scrollbar is hidden on touch devices), so tabs past the fold looked like they did not exist. Fade
  // the edge that still has hidden tabs, and keep the active tab scrolled into view on arrival.
  const tabsRef = useRef<HTMLDivElement>(null);
  const [fade, setFade] = useState({ left: false, right: false });

  const updateFade = useCallback(() => {
    const el = tabsRef.current;
    if (!el) return;
    const left = el.scrollLeft > 4;
    const right = el.scrollLeft + el.clientWidth < el.scrollWidth - 4;
    setFade((current) => (current.left === left && current.right === right ? current : { left, right }));
  }, []);

  useEffect(() => {
    const el = tabsRef.current;
    if (!el) return;
    updateFade();
    // Tab widths change once the web font is applied, which can add or remove overflow.
    void document.fonts?.ready?.then(updateFade);
    el.addEventListener('scroll', updateFade, { passive: true });
    window.addEventListener('resize', updateFade);
    return () => {
      el.removeEventListener('scroll', updateFade);
      window.removeEventListener('resize', updateFade);
    };
  }, [hideTabs, updateFade]);

  // Brings the active tab into view when it CHANGES (arrival on a deep link, or navigating to another
  // tab). It must not re-run for the re-renders caused by the person scrolling the strip themselves
  // (the fade state updates on scroll), or it would yank the strip back to the active tab mid-swipe.
  const lastActiveRef = useRef<string | null>(null);
  useEffect(() => {
    const el = tabsRef.current;
    const active = el?.querySelector<HTMLElement>('[aria-current="page"]');
    const activeKey = active?.getAttribute('href') ?? null;
    if (!el || !active || activeKey === lastActiveRef.current) return;
    lastActiveRef.current = activeKey;
    const start = active.offsetLeft; // the strip is position:relative, so this is in scroll coordinates
    const end = start + active.offsetWidth;
    if (start < el.scrollLeft || end > el.scrollLeft + el.clientWidth) {
      el.scrollLeft = Math.max(0, start - (el.clientWidth - active.offsetWidth) / 2);
      updateFade();
    }
  });

  const tabs = (allowedTabs ? CLASS_TABS.filter((tab) => allowedTabs.includes(tab.key)) : CLASS_TABS).map((tab) => ({
    ...tab,
    path: `/classes/${classroom.slug}/${tab.key}`,
    locked: lockedTabs.includes(tab.key),
  }));

  return (
    <div className="bg-slate-50">
      <div className="mx-auto w-full max-w-container px-4 pt-4 sm:px-8 sm:pt-7">
        {/* Masthead: cover (or topic tile) + the image overlay gradient, with the class chips on the glass. */}
        <section aria-label="Thông tin lớp học">
          <div className="relative h-[180px] overflow-hidden rounded-card bg-slate-100 sm:h-[288px] sm:rounded-section">
            {coverSrc && !coverFailed ? (
              <img
                src={coverSrc}
                alt={`Ảnh bìa lớp học ${classroom.title}`}
                onError={() => setCoverFailed(true)}
                style={{ objectPosition: objectPosition(classroom.coverPosition) }}
                className="h-full w-full object-cover"
              />
            ) : (
              <CoverImage
                seed={classroom.id || classroom.slug}
                icon={<GraduationCap className="h-11 w-11 opacity-80" strokeWidth={1.5} aria-hidden="true" />}
              />
            )}
            {/* The image overlay keeps the glass chips readable; a topic tile has no image to darken, so its chips are plain pills. */}
            {coverSrc && <div aria-hidden="true" className="cover-gradient pointer-events-none absolute inset-0" />}
            <div className="absolute bottom-3 left-20 right-3 flex flex-wrap items-center justify-end gap-1.5 sm:bottom-5 sm:left-32 sm:right-6 sm:gap-2">
              {lapsed ? (
                // D-19: a lapsed member is not a GUEST - say what happened to them.
                <span data-testid="badge-expired" className={`${chip} inline-flex h-[26px] items-center gap-1 rounded-full px-2.5 text-caption font-semibold`}>
                  <Clock className="h-3 w-3" strokeWidth={2} aria-hidden="true" />
                  Hết hạn
                </span>
              ) : pending ? (
                <span data-testid="badge-pending" className={`${chip} inline-flex h-[26px] items-center gap-1 rounded-full px-2.5 text-caption font-semibold`}>
                  <Hourglass className="h-3 w-3" strokeWidth={2} aria-hidden="true" />
                  Chờ duyệt
                </span>
              ) : (
                roleLabel && (
                  <span className={`${chip} inline-flex h-[26px] items-center rounded-full px-2.5 text-caption font-semibold`}>
                    {roleLabel}
                  </span>
                )
              )}
              {isSuspended(classroom) && <SuspendedChip />}
              <ClassBadges classroom={classroom} variant={coverSrc ? 'glass' : 'default'} />
            </div>
          </div>

          <div className="flex flex-col gap-4 px-1 sm:flex-row sm:items-end sm:justify-between sm:gap-6 sm:px-6">
            <div className="flex min-w-0 items-end gap-3 sm:gap-4">
              {classroom.avatarUrl && !avatarFailed ? (
                <img
                  src={classroom.avatarUrl}
                  alt=""
                  data-testid="class-avatar-image"
                  onError={() => setAvatarFailed(true)}
                  style={{ objectPosition: objectPosition(classroom.avatarPosition) }}
                  className="relative -mt-7 h-14 w-14 flex-shrink-0 self-start rounded-2xl border-4 border-slate-50 bg-white object-cover sm:h-[72px] sm:w-[72px] sm:rounded-[20px]"
                />
              ) : (
                <ClassAvatar
                  title={classroom.title}
                  seed={classroom.id || classroom.slug}
                  size={72}
                  className="relative -mt-7 self-start !rounded-[20px] border-4 border-slate-50 !text-[26px] max-sm:!h-14 max-sm:!w-14 max-sm:!text-[20px] max-sm:!rounded-2xl"
                />
              )}
              <div className="min-w-0 pt-3">
                <h1 className="text-[20px] font-semibold leading-7 tracking-[-0.3px] text-slate-900 sm:text-[22px] sm:leading-[30px]">
                  {classroom.title}
                </h1>
                <div className="mt-0.5 flex flex-wrap items-center gap-x-1.5 gap-y-0.5 text-meta text-slate-600">
                  {classroom.category && (
                    <span data-testid="class-category" className="mr-1 inline-flex h-[22px] items-center gap-1 rounded-full bg-slate-100 px-2 text-caption font-semibold text-slate-600">
                      <Tag className="h-3 w-3" strokeWidth={2} aria-hidden="true" />
                      {classroom.category}
                    </span>
                  )}
                  <span>{classroom.visibility === 'PRIVATE' ? 'Lớp riêng tư' : 'Lớp công khai'}</span>
                  <span aria-hidden="true">·</span>
                  <span className="tabular">{(classroom.memberCount ?? 0).toLocaleString('vi-VN')} thành viên</span>
                  {classroom.ownerName && (
                    <>
                      <span aria-hidden="true">·</span>
                      <span>
                        Dẫn dắt bởi <strong className="font-semibold text-slate-900">{classroom.ownerName}</strong>
                      </span>
                    </>
                  )}
                </div>
              </div>
            </div>

            <div className="flex flex-wrap items-center gap-2.5 sm:pt-3.5">
              {isActiveMember && !canAccessStudio && (
                <span className={buttonClass('secondary', 'md', 'cursor-default hover:bg-white')}>
                  <Check className="h-4 w-4 text-green-600" strokeWidth={2.2} aria-hidden="true" />
                  Đã tham gia
                </span>
              )}
              {canAccessStudio && (
                <Link
                  // R7-01: link to the Studio root (not /overview directly) so StudioLayout's index
                  // redirect can route staff lacking class-wide STUDIO:VIEW to the first Studio page
                  // they are actually authorized for, instead of dropping every staff member onto
                  // /overview and immediately hitting "Không đủ quyền truy cập".
                  to={`/studio/classes/${classroom.id}`}
                  className={buttonClass('secondary', 'md')}
                >
                  <LayoutDashboard className="h-4 w-4 text-slate-600" strokeWidth={1.75} aria-hidden="true" />
                  <span>Studio quản trị</span>
                </Link>
              )}
              <button type="button" onClick={handleShare} className={buttonClass('secondary', 'md')}>
                <Share2 className="h-4 w-4 text-slate-600" strokeWidth={1.8} aria-hidden="true" />
                Chia sẻ
              </button>
              <span role="status" aria-live="polite" className="text-caption text-slate-600">
                {shareState === 'copied' ? 'Đã sao chép liên kết lớp' : shareState === 'failed' ? 'Không sao chép được — hãy sao chép địa chỉ trên trình duyệt' : ''}
              </span>
            </div>
          </div>

          {expiresSoon && (
            <div className="px-1 sm:px-6">
              <div data-testid="expiry-chip" className="mt-3 inline-flex flex-wrap items-center gap-x-2 gap-y-1 rounded-full bg-warn-soft px-3 py-1.5 text-caption font-semibold text-amber-800">
                <Clock className="h-3.5 w-3.5" strokeWidth={2} aria-hidden="true" />
                <span className="tabular">Sắp hết hạn · còn {daysLeft} ngày (đến {formatDate(classroom.accessExpiresAt)})</span>
                <Link to={`/classes/${classroom.slug}/store`} className="font-semibold text-amber-900 underline underline-offset-2 hover:text-amber-950">
                  Gia hạn
                </Link>
              </div>
            </div>
          )}
        </section>
      </div>

      {/* Tab strip (hidden for a BLOCKED person) - sticky under the 64px top bar. */}
      {!hideTabs && (
        <nav aria-label="Các khu vực trong lớp học" className="sticky top-16 z-40 mt-5 bg-slate-50 sm:mt-6">
          <div className="relative mx-auto w-full max-w-container px-4 sm:px-8">
            <div className="border-b border-slate-200">
              <div ref={tabsRef} className="relative flex gap-6 overflow-x-auto overscroll-x-contain pt-2 scrollbar-none sm:gap-7">
                {tabs.map((tab) =>
                  tab.locked ? (
                    <span
                      key={tab.key}
                      aria-disabled="true"
                      title="Dành cho thành viên lớp"
                      className="-mb-px inline-flex flex-shrink-0 items-center gap-[5px] whitespace-nowrap border-b-2 border-transparent px-0.5 pb-3 text-ui font-medium text-slate-400"
                    >
                      {tab.label}
                      <Lock className="h-[11px] w-[11px] text-slate-300" strokeWidth={2.2} aria-hidden="true" />
                      <span className="sr-only">(dành cho thành viên)</span>
                    </span>
                  ) : (
                    <NavLink
                      key={tab.key}
                      to={tab.path}
                      className={({ isActive }) =>
                        `-mb-px inline-flex flex-shrink-0 items-center whitespace-nowrap border-b-2 px-0.5 pb-3 text-ui transition-colors duration-micro ${
                          isActive
                            ? 'border-blue-600 font-semibold text-blue-600'
                            : 'border-transparent font-medium text-slate-600 hover:text-slate-900'
                        }`
                      }
                    >
                      <span>{tab.label}</span>
                    </NavLink>
                  ),
                )}
              </div>
            </div>
            {fade.left && (
              <div aria-hidden="true" data-testid="tabs-fade-left" className="pointer-events-none absolute inset-y-0 left-4 w-8 bg-gradient-to-r from-slate-50 to-transparent sm:left-8" />
            )}
            {fade.right && (
              <div aria-hidden="true" data-testid="tabs-fade-right" className="pointer-events-none absolute inset-y-0 right-4 w-8 bg-gradient-to-l from-slate-50 to-transparent sm:right-8" />
            )}
          </div>
        </nav>
      )}
    </div>
  );
};
