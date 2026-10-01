import React, { useEffect, useRef, useState } from 'react';
import { useParams, Outlet, useNavigate, useLocation, Link } from 'react-router-dom';
import { Classroom, ClassAccessProduct } from '../types';
import { api, ApiException } from '../api/client';
import { accessProductFromError, isInviteRequired, isPaymentRequired } from '../api/errors';
import { MEMBERSHIP_EXPIRED_EVENT } from '../api/errorMessages';
import { formatDate } from '../api/format';
import { useAuth } from '../context/AuthContext';
import { useCheckout } from '../hooks/useCheckout';
import { ClassroomHeader } from '../components/ClassroomHeader';
import { CheckoutDialog } from '../components/CheckoutDialog';
import { ClassPaywallBanner, ClassPaywallCard } from '../components/ClassAccessGate';
import { LoadingSpinner, ErrorBanner } from '../components/UIStates';
import { Ban, Lock, SearchX, UserPlus } from 'lucide-react';

// D-19: the tabs whose data is members-only on the server (an EXPIRED member gets 403 MEMBERSHIP_EXPIRED for them, a person who
// never paid gets 403). Giới thiệu and Cửa hàng are the public / renewal-friendly tabs; the feed is public until the membership lapsed.
const MEMBER_ONLY_TABS = ['learn', 'exams', 'leaderboard', 'documents', 'members'];
const EXPIRED_TABS = ['about', 'store'];

export const ClassroomLayout: React.FC = () => {
  const { slug } = useParams<{ slug: string }>();
  // R17-01: isLoading stays true until the silent session bootstrap (refresh cookie -> /me) settles.
  // Until then `user` is null even for a signed-in person, so nothing that branches on guest-vs-member
  // may render or fetch yet - otherwise "Tham gia lớp ngay" bounces a signed-in user to /login and
  // every tab fires anonymous requests that are repeated once the user arrives.
  const { user, isLoading: authLoading } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [classroom, setClassroom] = useState<Classroom | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // D-19: a PRIVATE class is a 404 (never a 403) to anybody outside it, so "not found" is a first-class outcome, not an error banner.
  const [notFound, setNotFound] = useState(false);
  const [joining, setJoining] = useState(false);
  const [joinError, setJoinError] = useState<string | null>(null);
  const [gateError, setGateError] = useState<string | null>(null);

  // R18-05: the spinner (which unmounts the current tab and throws away its state - the open checkout dialog
  // in StoreTab, a "Đã lưu…" message) is only for the first load of a class for a given person. A later
  // refresh (a tab calling refreshClassroom after a purchase, say) is stale-while-revalidate: the tab stays
  // mounted. Changing person (login/logout) still counts as a first load, so a stale guest view is never shown
  // to a signed-in member (R17-01). `requestSeq` drops replies superseded by a newer request.
  const loadedRef = useRef<{ slug: string; userId: string | null } | null>(null);
  const requestSeq = useRef(0);

  const fetchClassroom = async () => {
    if (!slug) return;
    const userId = user?.id ?? null;
    const silent = loadedRef.current?.slug === slug && loadedRef.current.userId === userId;
    const seq = ++requestSeq.current;
    if (!silent) {
      setLoading(true);
      setError(null);
      setNotFound(false);
    }
    try {
      const data = await api.get<Classroom>(`/classes/slug/${slug}`);
      if (seq !== requestSeq.current) return;
      loadedRef.current = { slug, userId };
      setClassroom(data);
      setError(null);
      setNotFound(false);
    } catch (err: any) {
      if (seq !== requestSeq.current) return;
      // A background refresh that fails transiently keeps the working page; the class disappearing or access
      // being withdrawn (401/403/404) does not.
      const lostAccess = err instanceof ApiException && (err.status === 401 || err.status === 403 || err.status === 404);
      if (silent && !lostAccess) return;
      loadedRef.current = null;
      setNotFound(err instanceof ApiException && err.status === 404);
      setError(err.message || 'Không thể tải thông tin lớp học');
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  };

  useEffect(() => {
    if (authLoading) return;
    fetchClassroom();
    // Keyed on the person (id), not the user object, so a profile refresh does not refetch the class.
  }, [slug, user?.id, authLoading]);

  // D-19: any API call answered with MEMBERSHIP_EXPIRED (access lapsed while a tab was open) tells us to re-read the membership state,
  // which flips the page to the renewal prompt instead of leaving a tab showing an error.
  const fetchRef = useRef(fetchClassroom);
  fetchRef.current = fetchClassroom;
  useEffect(() => {
    const onExpired = () => { void fetchRef.current(); };
    window.addEventListener(MEMBERSHIP_EXPIRED_EVENT, onExpired);
    return () => window.removeEventListener(MEMBERSHIP_EXPIRED_EVENT, onExpired);
  }, []);

  // D-19: the purchase flow (shared with the Store tab) for a PAID class / a lapsed membership. The availability lookup is only made
  // when a checkout can actually be needed, so an ordinary member's page view makes no payment request.
  const needsCheckout =
    !!classroom && !classroom.isMember && !classroom.isOwner && (classroom.accessType === 'PAID' || classroom.memberState === 'EXPIRED');
  const checkout = useCheckout({
    classId: classroom?.id,
    checkAvailability: needsCheckout,
    // A settled order makes the buyer a member: re-read the class (silently - the open dialog and tab stay mounted).
    onChanged: async () => { await fetchClassroom(); },
  });

  const startCheckout = async (fromError?: ClassAccessProduct | null) => {
    if (!classroom || authLoading) return;
    if (!user) {
      // Sign in first and come back to this class afterwards.
      navigate('/login', { state: { from: location } });
      return;
    }
    const product = fromError ?? classroom.accessProduct;
    if (!product) {
      setGateError('Chủ lớp chưa công bố gói vào lớp. Vui lòng liên hệ chủ lớp.');
      return;
    }
    setGateError(null);
    const result = await checkout.buy({
      id: product.id,
      title: `Gói vào lớp · ${classroom.title}`,
      durationDays: product.durationDays,
      price: product.price,
    });
    if (!result.ok) setGateError((result.error as { message?: string })?.message || 'Khởi tạo đơn hàng thất bại');
  };

  const handleJoin = async () => {
    if (!classroom || authLoading) return;
    if (!user) {
      // Truly unauthenticated (the bootstrap has settled without a session): sign in first and come
      // back to this class afterwards.
      navigate('/login', { state: { from: location } });
      return;
    }
    setJoining(true);
    setJoinError(null);
    try {
      const updated = await api.post<Classroom>(`/classes/${classroom.id}/join`);
      setClassroom(updated);
    } catch (err: any) {
      if (isPaymentRequired(err)) {
        // D-19: the class turned out to be PAID (stale page, or a removed member whose paid access has run out): open checkout.
        await startCheckout(accessProductFromError(err));
      } else if (isInviteRequired(err)) {
        setJoinError(err.message);
      } else {
        setJoinError(err.message || 'Tham gia lớp thất bại');
      }
    } finally {
      setJoining(false);
    }
  };

  if (authLoading || loading) {
    return <LoadingSpinner message="Đang tải dữ liệu lớp học..." />;
  }

  if (notFound) {
    return (
      <div className="max-w-xl mx-auto p-8">
        <div role="alert" className="p-8 bg-white border border-slate-200 rounded-2xl shadow-sm text-center">
          <div className="w-12 h-12 mx-auto rounded-full bg-slate-100 flex items-center justify-center text-slate-600 mb-3">
            <SearchX className="w-6 h-6" aria-hidden="true" />
          </div>
          <h1 className="text-lg font-bold text-slate-900">Không tìm thấy lớp học</h1>
          <p className="text-sm text-slate-600 mt-2">
            Lớp học này không tồn tại hoặc là lớp riêng tư. Nếu bạn được mời, hãy mở lại liên kết mời mà chủ lớp đã gửi.
          </p>
          <Link
            to="/classes"
            className="mt-5 inline-flex items-center px-5 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-bold rounded-xl shadow-sm transition"
          >
            Về danh sách lớp học
          </Link>
        </div>
      </div>
    );
  }

  if (error || !classroom) {
    return (
      <div className="max-w-4xl mx-auto p-8">
        <ErrorBanner message={error || 'Không tìm thấy lớp học'} onRetry={fetchClassroom} />
      </div>
    );
  }

  // R16-01: the server now reports the caller's membership lifecycle (memberState). Only an ACTIVE
  // member (or the owner) gets the member experience; a REMOVED person is offered a rejoin, and a
  // BLOCKED person only sees a notice - no rejoin button, no member tabs/content.
  // D-19: an EXPIRED person (paid access lapsed) sees the class card, Giới thiệu and Cửa hàng plus a renewal prompt.
  const notAMember = !classroom.isMember && !classroom.isOwner;
  const isBlocked = notAMember && classroom.memberState === 'BLOCKED';
  const isRemoved = notAMember && classroom.memberState === 'REMOVED';
  const isExpired = notAMember && classroom.memberState === 'EXPIRED';
  const isGuest = notAMember && !isBlocked && !isRemoved && !isExpired;
  const isPaid = classroom.accessType === 'PAID';
  // A lapsed member of a class that has since become FREE needs no purchase, only to join again.
  const expiredCanRejoin = isExpired && !isPaid;
  const paidOutsider = isGuest && isPaid;
  const privateOutsider = isGuest && !isPaid && classroom.visibility === 'PRIVATE';

  const tab = location.pathname.split('/')[3] ?? '';
  const lockedTab = isExpired ? !EXPIRED_TABS.includes(tab) : paidOutsider ? MEMBER_ONLY_TABS.includes(tab) : false;
  const gateProps = {
    classroom,
    signedIn: !!user,
    checkoutAvailable: checkout.checkoutAvailable,
    busy: checkout.processing || joining,
    error: gateError,
    onAction: () => { void startCheckout(); },
  };
  // One call to action per page: the full card on the feed and on locked tabs, the one-line banner on Giới thiệu / Cửa hàng.
  const showCard = (isExpired && !expiredCanRejoin && lockedTab) || (paidOutsider && (lockedTab || tab === 'feed' || tab === ''));
  const showBanner = (isExpired && !expiredCanRejoin && !lockedTab) || (paidOutsider && !showCard);

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
      <ClassroomHeader
        classroom={classroom}
        hideTabs={isBlocked}
        allowedTabs={isExpired ? EXPIRED_TABS : undefined}
      />

      {joinError && (
        <div role="alert" className="bg-rose-50 border-b border-rose-200 py-2.5 px-4">
          <div className="max-w-7xl mx-auto text-xs sm:text-sm font-semibold text-rose-800">{joinError}</div>
        </div>
      )}

      {/* Join prompt if user is not member and not owner */}
      {isGuest && !paidOutsider && !privateOutsider && (
        <div className="bg-indigo-50 border-b border-indigo-100 py-3 px-4">
          <div className="max-w-7xl mx-auto flex items-center justify-between">
            <span className="text-xs sm:text-sm font-medium text-indigo-900">
              Bạn đang xem lớp học này ở chế độ khách. Hãy tham gia lớp để học tập và thảo luận!
            </span>
            <button
              onClick={handleJoin}
              disabled={joining}
              className="inline-flex items-center space-x-1.5 px-4 py-1.5 bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-bold rounded-lg shadow-sm transition disabled:opacity-50"
            >
              <UserPlus className="w-3.5 h-3.5" />
              <span>{joining ? 'Đang tham gia...' : 'Tham gia lớp ngay'}</span>
            </button>
          </div>
        </div>
      )}

      {/* D-19: a PRIVATE class is only joined through an invite link - there is nothing to click here. */}
      {privateOutsider && (
        <div role="status" className="bg-slate-100 border-b border-slate-200 py-3 px-4">
          <div className="max-w-7xl mx-auto flex items-center gap-2 text-xs sm:text-sm font-medium text-slate-800">
            <Lock className="w-4 h-4 flex-shrink-0" aria-hidden="true" />
            <span>Đây là lớp riêng tư. Bạn cần liên kết mời của chủ lớp để tham gia.</span>
          </div>
        </div>
      )}

      {/* R16-01: REMOVED members may rejoin on their own (decision D-12). */}
      {isRemoved && (
        <div className="bg-amber-50 border-b border-amber-200 py-3 px-4" role="status">
          <div className="max-w-7xl mx-auto flex items-center justify-between gap-3">
            <span className="text-xs sm:text-sm font-medium text-amber-900">
              Bạn đã bị xóa khỏi lớp học này nên chưa thể học tập hay thảo luận. Bạn có thể tham gia lại bất cứ lúc nào.
            </span>
            <button
              onClick={handleJoin}
              disabled={joining}
              className="inline-flex items-center space-x-1.5 px-4 py-1.5 bg-amber-700 hover:bg-amber-800 text-white text-xs font-bold rounded-lg shadow-sm transition disabled:opacity-50 flex-shrink-0"
            >
              <UserPlus className="w-3.5 h-3.5" />
              <span>{joining ? 'Đang tham gia...' : 'Tham gia lại'}</span>
            </button>
          </div>
        </div>
      )}

      {/* D-19: the class has become FREE since this person's paid access lapsed: no purchase, just join again. */}
      {expiredCanRejoin && (
        <div className="bg-amber-50 border-b border-amber-200 py-3 px-4" role="status">
          <div className="max-w-7xl mx-auto flex items-center justify-between gap-3">
            <span className="text-xs sm:text-sm font-medium text-amber-900">
              Gói thành viên lớp đã hết hạn ngày {formatDate(classroom.accessExpiresAt) || '—'}. Lớp hiện đã miễn phí: bạn có thể tham gia lại ngay.
            </span>
            <button
              onClick={handleJoin}
              disabled={joining}
              className="inline-flex items-center space-x-1.5 px-4 py-1.5 bg-amber-700 hover:bg-amber-800 text-white text-xs font-bold rounded-lg shadow-sm transition disabled:opacity-50 flex-shrink-0"
            >
              <UserPlus className="w-3.5 h-3.5" />
              <span>{joining ? 'Đang tham gia...' : 'Tham gia lại'}</span>
            </button>
          </div>
        </div>
      )}

      {showBanner && <ClassPaywallBanner {...gateProps} mode={isExpired ? 'renew' : 'buy'} />}

      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-8">
        {isBlocked ? (
          // R16-01: BLOCKED is terminal for the person - only a Studio unblock restores access - so
          // there is no rejoin button and none of the class's tabs/content is rendered.
          <div className="max-w-xl mx-auto p-8 bg-rose-50 border border-rose-200 rounded-2xl text-center" role="alert">
            <div className="w-12 h-12 mx-auto rounded-full bg-rose-100 flex items-center justify-center text-rose-600 mb-3">
              <Ban className="w-6 h-6" />
            </div>
            <h2 className="text-lg font-bold text-rose-900">Bạn đã bị chặn khỏi lớp học này</h2>
            <p className="text-sm text-rose-800 mt-2">
              Bạn không thể tham gia lại hay xem nội dung dành cho thành viên. Nếu cho rằng đây là nhầm lẫn,
              hãy liên hệ chủ nhiệm lớp để được mở khóa.
            </p>
          </div>
        ) : lockedTab && expiredCanRejoin ? (
          <div className="max-w-xl mx-auto p-8 bg-white border border-slate-200 rounded-2xl text-center shadow-sm">
            <div className="w-12 h-12 mx-auto rounded-full bg-amber-100 flex items-center justify-center text-amber-700 mb-3">
              <Lock className="w-6 h-6" aria-hidden="true" />
            </div>
            <h2 className="text-lg font-bold text-slate-900">Nội dung dành cho thành viên</h2>
            <p className="text-sm text-slate-600 mt-2">Hãy tham gia lại lớp để tiếp tục học tập và thảo luận.</p>
          </div>
        ) : lockedTab ? (
          <ClassPaywallCard {...gateProps} mode={isExpired ? 'renew' : 'buy'} />
        ) : (
          <>
            {showCard && (
              <div className="mb-8">
                <ClassPaywallCard {...gateProps} mode={isExpired ? 'renew' : 'buy'} />
              </div>
            )}
            <Outlet context={{ classroom, refreshClassroom: fetchClassroom }} />
          </>
        )}
      </main>

      {/* The purchase dialog lives here (not inside the paywall) so it stays open when the purchase settles and the paywall goes away. */}
      <CheckoutDialog checkout={checkout} paidAction={{ label: 'Bắt đầu học', onClick: checkout.close }} />
    </div>
  );
};
