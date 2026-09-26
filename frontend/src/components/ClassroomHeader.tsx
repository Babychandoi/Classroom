import React from 'react';
import { NavLink, Link } from 'react-router-dom';
import { Classroom } from '../types';
import { StatusBadge } from './UIStates';
import { Users, LayoutDashboard, Sparkles, BookOpen, MessageSquare, Award, FileText, ShoppingBag, Info } from 'lucide-react';

export const ClassroomHeader: React.FC<{ classroom: Classroom }> = ({ classroom }) => {
  const canAccessStudio = classroom.userRole === 'OWNER' || classroom.userRole === 'STAFF';

  const tabs = [
    { key: 'feed', label: 'Bảng tin', icon: MessageSquare, path: `/classes/${classroom.slug}/feed` },
    { key: 'learn', label: 'Góc học tập', icon: BookOpen, path: `/classes/${classroom.slug}/learn` },
    { key: 'exams', label: 'Luyện thi', icon: Award, path: `/classes/${classroom.slug}/exams` },
    { key: 'leaderboard', label: 'Xếp hạng', icon: Sparkles, path: `/classes/${classroom.slug}/leaderboard` },
    { key: 'documents', label: 'Tài liệu', icon: FileText, path: `/classes/${classroom.slug}/documents` },
    { key: 'members', label: 'Thành viên', icon: Users, path: `/classes/${classroom.slug}/members` },
    { key: 'about', label: 'Giới thiệu', icon: Info, path: `/classes/${classroom.slug}/about` },
    { key: 'store', label: 'Cửa hàng', icon: ShoppingBag, path: `/classes/${classroom.slug}/store` },
  ];

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
            <div className="flex items-center space-x-2.5 mb-1.5">
              <span className="text-xs uppercase font-mono tracking-wider px-2 py-0.5 rounded bg-white/20 backdrop-blur-sm">
                /{classroom.slug}
              </span>
              {classroom.userRole && <StatusBadge status={classroom.userRole} />}
            </div>
            <h1 className="text-2xl md:text-3xl font-extrabold tracking-tight drop-shadow-sm">
              {classroom.title}
            </h1>
            <p className="text-sm text-slate-200 line-clamp-1 max-w-2xl mt-0.5">
              {classroom.description || 'Chưa có mô tả chi tiết cho lớp học.'}
            </p>
          </div>

          {canAccessStudio && (
            <Link
              to={`/studio/classes/${classroom.id}/overview`}
              className="inline-flex items-center space-x-2 px-4 py-2 bg-indigo-600/90 hover:bg-indigo-600 text-white font-semibold text-sm rounded-xl backdrop-blur-sm shadow-lg transition flex-shrink-0"
            >
              <LayoutDashboard className="w-4 h-4" />
              <span>Studio Quản Trị</span>
            </Link>
          )}
        </div>
      </div>

      {/* 8 Tabs Bar */}
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex space-x-1 overflow-x-auto scrollbar-none py-1.5">
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
      </div>
    </div>
  );
};
