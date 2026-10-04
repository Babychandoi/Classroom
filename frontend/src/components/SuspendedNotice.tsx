import React from 'react';
import { PauseCircle } from 'lucide-react';
import type { Classroom } from '../types';
import { formatDate } from '../api/format';

// A class SUSPENDED by a platform admin (docs/API-PLATFORM-ADMIN.md §3) is only visible to its owner, read-only.
// Calm, not alarming: one amber notice with the admin's reason, and a small "Tạm khóa" chip next to the class badges.

export const isSuspended = (classroom?: Pick<Classroom, 'status'> | null) => classroom?.status === 'SUSPENDED';

export const SuspendedNotice: React.FC<{ classroom: Pick<Classroom, 'status' | 'suspendedReason' | 'suspendedAt'>; className?: string }> = ({
  classroom, className = '',
}) => {
  if (!isSuspended(classroom)) return null;
  const since = formatDate(classroom.suspendedAt);
  return (
    <div role="status" data-testid="suspended-notice" className={`flex items-start gap-2.5 rounded-2xl border border-amber-200 bg-warn-soft px-4 py-3 sm:px-5 ${className}`}>
      <PauseCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-amber-800" strokeWidth={1.75} aria-hidden="true" />
      <div className="min-w-0 text-ui text-slate-900">
        <p className="break-words font-semibold">
          Lớp đang bị tạm khóa bởi quản trị nền tảng{classroom.suspendedReason ? `: ${classroom.suspendedReason}` : '.'}
        </p>
        <p className="mt-0.5 text-meta text-slate-600">
          {since ? <span className="tabular">Từ {since}. </span> : null}
          Lớp chỉ hiển thị với bạn ở chế độ chỉ đọc: thành viên và khách không xem được, và chưa ai tham gia, mua hay đăng bài mới được. Hãy
          liên hệ quản trị nền tảng nếu bạn cần trao đổi.
        </p>
      </div>
    </div>
  );
};

export const SuspendedChip: React.FC<{ className?: string }> = ({ className = '' }) => (
  <span
    data-testid="badge-suspended"
    className={`inline-flex h-[26px] flex-shrink-0 items-center gap-1 whitespace-nowrap rounded-full bg-warn-soft px-2.5 text-caption font-semibold text-amber-800 ${className}`}
  >
    <PauseCircle className="h-3 w-3" strokeWidth={2} aria-hidden="true" />
    Tạm khóa
  </span>
);
