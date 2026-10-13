import React from 'react';
import { NavLink } from 'react-router-dom';
import { BookOpen, CalendarCheck, Compass, School, UserRound } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

const TABS: { to: string; label: string; Icon: LucideIcon; also?: string[] }[] = [
  { to: '/classes', label: 'Khám phá', Icon: Compass },
  { to: '/me/classes', label: 'Lớp học', Icon: School },
  { to: '/me/courses', label: 'Khóa học', Icon: BookOpen },
  { to: '/me/events', label: 'Sự kiện', Icon: CalendarCheck },
  { to: '/me/profile', label: 'Tôi', Icon: UserRound, also: ['/privacy'] },
];

/** D-35 phone tab bar of the home area (below lg): the five personal destinations, fixed to the bottom edge. */
export const MobileTabBar: React.FC<{ pathname: string }> = ({ pathname }) => (
  <nav
    aria-label="Điều hướng điện thoại"
    className="fixed inset-x-0 bottom-0 z-40 grid grid-cols-5 border-t border-line bg-white px-2 pb-[max(8px,env(safe-area-inset-bottom))] pt-2 lg:hidden"
  >
    {TABS.map(({ to, label, Icon, also }) => {
      const forced = also?.some((p) => pathname === p || pathname.startsWith(`${p}/`)) ?? false;
      return (
        <NavLink
          key={to}
          to={to}
          end
          className={({ isActive }) =>
            `flex flex-col items-center gap-1 rounded-btn py-1 text-micro transition-colors duration-micro ${
              isActive || forced ? 'font-semibold text-slate-900' : 'font-medium text-slate-500 hover:text-slate-900'
            }`
          }
        >
          <Icon className="h-6 w-6" strokeWidth={1.8} aria-hidden="true" />
          <span>{label}</span>
        </NavLink>
      );
    })}
  </nav>
);
