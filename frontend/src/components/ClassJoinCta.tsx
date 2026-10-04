import React, { useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Lock, LogIn, UserPlus } from 'lucide-react';
import { api } from '../api/client';
import { isPaymentRequired } from '../api/errors';
import { useAuth } from '../context/AuthContext';
import { buttonClass } from './ui';
import type { Classroom } from '../types';

/**
 * The one next step for someone who may see a card but not its content (a members-only blog post, a members-only event):
 * a guest signs in (and comes back here), a signed-in outsider of a FREE public class joins in place, a PAID class goes to
 * the Shop tab where its access is sold, and a PRIVATE class can only be entered through an invite link.
 */
export const ClassJoinCta: React.FC<{
  classroom: Classroom;
  refreshClassroom?: () => Promise<unknown> | void;
  /** Label for the sign-in link, e.g. "Đăng nhập để đọc tiếp". */
  signInLabel?: string;
  joinLabel?: string;
  className?: string;
}> = ({ classroom, refreshClassroom, signInLabel = 'Đăng nhập để tiếp tục', joinLabel = 'Tham gia lớp', className }) => {
  const { user } = useAuth();
  const location = useLocation();
  const [joining, setJoining] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!user) {
    return (
      <Link to="/login" state={{ from: location }} className={buttonClass('primary', 'lg', className)}>
        <LogIn className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
        {signInLabel}
      </Link>
    );
  }

  if (classroom.visibility === 'PRIVATE') {
    return (
      <p role="status" className={`inline-flex items-center gap-2 text-ui text-slate-600 ${className ?? ''}`}>
        <Lock className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" />
        Đây là lớp riêng tư. Bạn cần liên kết mời của chủ lớp để tham gia.
      </p>
    );
  }

  if (classroom.accessType === 'PAID' || classroom.memberState === 'EXPIRED') {
    return (
      <Link to={`/classes/${classroom.slug}/store`} className={buttonClass('primary', 'lg', className)}>
        <UserPlus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
        {joinLabel}
      </Link>
    );
  }

  const join = async () => {
    setJoining(true);
    setError(null);
    try {
      await api.post<Classroom>(`/classes/${classroom.id}/join`);
      await refreshClassroom?.();
    } catch (err) {
      if (isPaymentRequired(err)) setError('Lớp học này có thu phí. Mở tab Shop để nhận quyền thành viên.');
      else setError(err instanceof Error && err.message ? err.message : 'Tham gia lớp thất bại. Vui lòng thử lại.');
    } finally {
      setJoining(false);
    }
  };

  return (
    <div className={className}>
      <button type="button" onClick={join} disabled={joining || classroom.memberState === 'BLOCKED'} className={buttonClass('primary', 'lg')}>
        <UserPlus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
        {joining ? 'Đang tham gia...' : joinLabel}
      </button>
      {error && <p role="alert" className="mt-2 text-meta text-red-600">{error}</p>}
    </div>
  );
};
