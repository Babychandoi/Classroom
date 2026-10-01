import React, { useCallback, useEffect, useRef, useState } from 'react';
import { NavLink, Link } from 'react-router-dom';
import { Classroom } from '../types';
import { StatusBadge } from './UIStates';
import { ClassBadges } from './ClassBadges';
import { daysUntil, formatDate } from '../api/format';
import { Users, LayoutDashboard, Sparkles, BookOpen, MessageSquare, Award, FileText, ShoppingBag, Info, Clock } from 'lucide-react';

// D-19: a paid member is told about the end of their access this many days ahead (gently - a chip, not a banner).
export const EXPIRY_WARNING_DAYS = 7;

// R16-01: hideTabs is set for a BLOCKED person, who must not be offered any of the class's member tabs.
// D-19: allowedTabs limits the strip to the named tabs (an EXPIRED member only has Giới thiệu and Cửa hàng).
export const ClassroomHeader: React.FC<{ classroom: Classroom; hideTabs?: boolean; allowedTabs?: string[] }> = ({ classroom, hideTabs = false, allowedTabs }) => {
  const canAccessStudio = classroom.userRole === 'OWNER' || classroom.userRole === 'STAFF';
  // D-19 (e): an ACTIVE paid member whose access ends within a week. The owner / staff never expire.
  const daysLeft = classroom.isMember && !classroom.isOwner && !canAccessStudio && classroom.memberState !== 'EXPIRED'
    ? daysUntil(classroom.accessExpiresAt)
    : Number.NaN;
  const expiresSoon = Number.isFinite(daysLeft) && daysLeft > 0 && daysLeft <= EXPIRY_WARNING_DAYS;

  // R17-05: at phone width the 8 tabs overflow and scroll sideways with no visible cue (the native
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

  const allTabs = [
    { key: 'feed', label: 'Bảng tin', icon: MessageSquare, path: `/classes/${classroom.slug}/feed` },
    { key: 'learn', label: 'Góc học tập', icon: BookOpen, path: `/classes/${classroom.slug}/learn` },
    { key: 'exams', label: 'Luyện thi', icon: Award, path: `/classes/${classroom.slug}/exams` },
    { key: 'leaderboard', label: 'Xếp hạng', icon: Sparkles, path: `/classes/${classroom.slug}/leaderboard` },
    { key: 'documents', label: 'Tài liệu', icon: FileText, path: `/classes/${classroom.slug}/documents` },
    { key: 'members', label: 'Thành viên', icon: Users, path: `/classes/${classroom.slug}/members` },
    { key: 'about', label: 'Giới thiệu', icon: Info, path: `/classes/${classroom.slug}/about` },
    { key: 'store', label: 'Cửa hàng', icon: ShoppingBag, path: `/classes/${classroom.slug}/store` },
  ];
  const tabs = allowedTabs ? allTabs.filter((tab) => allowedTabs.includes(tab.key)) : allTabs;

  return (
    <div className="bg-white border-b border-slate-200">
      {/* Cover Banner */}
      <div className="h-44 md:h-52 w-full bg-slate-900 relative overflow-hidden">
        {classroom.coverImageUrl ? (
          <img
            src={classroom.coverImageUrl}
            alt={classroom.title}
            className="w-full h-full object-cover opacity-60"
          />
        ) : (
          <div className="w-full h-full bg-gradient-to-r from-indigo-900 via-indigo-800 to-purple-900" />
        )}
        <div className="absolute inset-0 bg-gradient-to-t from-slate-950/80 via-transparent to-transparent" />

        <div className="absolute bottom-4 left-4 right-4 max-w-7xl mx-auto flex flex-col md:flex-row md:items-end justify-between gap-4">
          <div className="text-white">
            <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1.5 mb-1.5">
              <span className="text-xs uppercase font-mono tracking-wider px-2 py-0.5 rounded bg-white/20 backdrop-blur-sm">
                /{classroom.slug}
              </span>
              {classroom.memberState === 'EXPIRED' && !classroom.isMember ? (
                // D-19: a lapsed member is not a GUEST - say what happened to them.
                <span data-testid="badge-expired" className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-purple-100 text-purple-800">
                  Hết hạn
                </span>
              ) : (
                classroom.userRole && <StatusBadge status={classroom.userRole} />
              )}
              <ClassBadges classroom={classroom} showPublic={canAccessStudio} />
            </div>
            <h1 className="text-2xl md:text-3xl font-extrabold tracking-tight drop-shadow-sm">
              {classroom.title}
            </h1>
            <p className="text-sm text-slate-200 line-clamp-1 max-w-2xl mt-0.5">
              {classroom.description || 'Chưa có mô tả chi tiết cho lớp học.'}
            </p>
            {expiresSoon && (
              <div data-testid="expiry-chip" className="mt-2 inline-flex flex-wrap items-center gap-x-2 gap-y-1 px-3 py-1 rounded-full bg-amber-100 text-amber-900 text-xs font-semibold">
                <Clock className="w-3.5 h-3.5" aria-hidden="true" />
                <span>Sắp hết hạn · còn {daysLeft} ngày (đến {formatDate(classroom.accessExpiresAt)})</span>
                <Link to={`/classes/${classroom.slug}/store`} className="font-bold underline hover:text-amber-950">
                  Gia hạn
                </Link>
              </div>
            )}
          </div>

          {canAccessStudio && (
            <Link
              // R7-01: link to the Studio root (not /overview directly) so StudioLayout's index
              // redirect can route staff lacking class-wide STUDIO:VIEW to the first Studio page
              // they are actually authorized for, instead of dropping every staff member onto
              // /overview and immediately hitting "Không đủ quyền truy cập".
              to={`/studio/classes/${classroom.id}`}
              className="inline-flex items-center space-x-2 px-4 py-2 bg-indigo-600/90 hover:bg-indigo-600 text-white font-semibold text-sm rounded-xl backdrop-blur-sm shadow-lg transition flex-shrink-0"
            >
              <LayoutDashboard className="w-4 h-4" />
              <span>Studio Quản Trị</span>
            </Link>
          )}
        </div>
      </div>

      {/* 8 Tabs Bar (hidden for a BLOCKED person) */}
      {!hideTabs && (
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 relative">
        <div ref={tabsRef} className="relative flex space-x-1 overflow-x-auto overscroll-x-contain scrollbar-none py-1.5">
          {tabs.map((tab) => {
            const Icon = tab.icon;
            return (
              <NavLink
                key={tab.key}
                to={tab.path}
                className={({ isActive }) =>
                  `flex items-center space-x-2 px-3.5 py-2.5 rounded-lg text-sm font-semibold whitespace-nowrap transition ${
                    isActive
                      ? 'text-indigo-600 bg-indigo-50/80 font-bold border-b-2 border-indigo-600'
                      : 'text-slate-600 hover:text-slate-900 hover:bg-slate-100'
                  }`
                }
              >
                <Icon className="w-4 h-4" />
                <span>{tab.label}</span>
              </NavLink>
            );
          })}
        </div>
        {fade.left && (
          <div aria-hidden="true" data-testid="tabs-fade-left" className="pointer-events-none absolute inset-y-0 left-4 sm:left-6 lg:left-8 w-8 bg-gradient-to-r from-white to-transparent" />
        )}
        {fade.right && (
          <div aria-hidden="true" data-testid="tabs-fade-right" className="pointer-events-none absolute inset-y-0 right-4 sm:right-6 lg:right-8 w-8 bg-gradient-to-l from-white to-transparent" />
        )}
      </div>
      )}
    </div>
  );
};
