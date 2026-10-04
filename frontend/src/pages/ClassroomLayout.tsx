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
import { Badge, buttonClass } from '../components/ui';
import { AlertCircle, Ban, Check, Hourglass, Lock, SearchX, UserPlus } from 'lucide-react';

// D-19: the tabs whose data is members-only on the server (an EXPIRED member gets 403 MEMBERSHIP_EXPIRED for them, a person who
// never paid gets 403). Giới thiệu and Shop (store) are the public / renewal-friendly tabs; the feed is public until the membership lapsed.
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
  // Join approval: withdrawing a pending request asks for a second click first.
  const [confirmWithdraw, setConfirmWithdraw] = useState(false);
  const [withdrawing, setWithdrawing] = useState(false);

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

  // Moving to another page of the class (a tab, a member's profile) starts at the top instead of keeping the previous page's scroll
  // position, which could land the reader halfway down a page they have not seen. Not on first arrival (the browser handles that).
  const lastPathRef = useRef(location.pathname);
  useEffect(() => {
    if (lastPathRef.current === location.pathname) return;
    lastPathRef.current = location.pathname;
    if (window.scrollY > 0 && typeof window.scrollTo === 'function') {
      try { window.scrollTo({ top: 0 }); } catch { /* not supported (tests) */ }
    }
  }, [location.pathname]);

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

  // Join approval: the caller takes back their PENDING request (DELETE is idempotent) and is a plain visitor again.
  const handleWithdraw = async () => {
    if (!classroom) return;
    setWithdrawing(true);
    setJoinError(null);
    try {
      await api.delete(`/classes/${classroom.id}/join-request`);
      setClassroom((prev) => (prev ? { ...prev, memberState: 'NONE' } : prev));
      setConfirmWithdraw(false);
      void fetchClassroom();
    } catch (err: any) {
      setJoinError(err.message || 'Không rút được yêu cầu tham gia');
    } finally {
      setWithdrawing(false);
    }
  };

  if (authLoading || loading) {
    return <LoadingSpinner message="Đang tải dữ liệu lớp học..." />;
  }

  if (notFound) {
    return (
      <div className="mx-auto max-w-xl px-4 py-12 sm:py-16">
        <div role="alert" className="rounded-card border border-slate-200 bg-white p-8 text-center shadow-hairline">
          <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-slate-100 text-slate-600">
            <SearchX className="h-6 w-6" strokeWidth={1.75} aria-hidden="true" />
          </div>
          <h1 className="text-h2-sm font-semibold text-slate-900">Không tìm thấy lớp học</h1>
          <p className="mt-2 text-ui text-slate-600">
            Lớp học này không tồn tại hoặc là lớp riêng tư. Nếu bạn được mời, hãy mở lại liên kết mời mà chủ lớp đã gửi.
          </p>
          <Link to="/classes" className={buttonClass('primary', 'lg', 'mt-6')}>
            Về danh sách lớp học
          </Link>
        </div>
      </div>
    );
  }

  if (error || !classroom) {
    return (
      <div className="mx-auto max-w-container px-4 py-8 sm:px-8">
        <ErrorBanner message={error || 'Không tìm thấy lớp học'} onRetry={fetchClassroom} />
      </div>
    );
  }

  // R16-01: the server now reports the caller's membership lifecycle (memberState). Only an ACTIVE
  // member (or the owner) gets the member experience; a REMOVED person is offered a rejoin, and a
  // BLOCKED person only sees a notice - no rejoin button, no member tabs/content.
  // D-19: an EXPIRED person (paid access lapsed) sees the class card, Giới thiệu and Shop plus a renewal prompt.
  const notAMember = !classroom.isMember && !classroom.isOwner;
  const isBlocked = notAMember && classroom.memberState === 'BLOCKED';
  const isRemoved = notAMember && classroom.memberState === 'REMOVED';
  const isExpired = notAMember && classroom.memberState === 'EXPIRED';
  // Join approval: asked to join, waiting for the owner. Not a member - the member-only tabs stay locked.
  const isPending = notAMember && classroom.memberState === 'PENDING';
  const isGuest = notAMember && !isBlocked && !isRemoved && !isExpired && !isPending;
  const needsApproval = !!classroom.requireApproval;
  const isPaid = classroom.accessType === 'PAID';
  // A lapsed member of a class that has since become FREE needs no purchase, only to join again.
  const expiredCanRejoin = isExpired && !isPaid;
  const paidOutsider = isGuest && isPaid;
  const privateOutsider = isGuest && !isPaid && classroom.visibility === 'PRIVATE';
  // Anyone outside the class (a visitor or a removed member) sees the member-only tabs locked in the strip;
  // an EXPIRED member's strip is cut down to Giới thiệu + Shop instead (D-19).
  const lockedTabs = notAMember && !isExpired ? MEMBER_ONLY_TABS : undefined;

  const tab = location.pathname.split('/')[3] ?? '';
  // Focus mode: a lesson being studied and an exam attempt being taken are "push" screens - the class masthead and the tab strip
  // are hidden so the content starts right under the top bar (the pages carry their own way back). Only for someone who is
  // actually inside the class; a visitor / lapsed / blocked person keeps the full header with its gating and calls to action.
  const focusMode = !notAMember && /^\/classes\/[^/]+\/(learn\/lessons\/[^/]+|exams\/[^/]+\/attempt)\/?$/.test(location.pathname);
  const lockedTab = isExpired ? !EXPIRED_TABS.includes(tab) : paidOutsider ? MEMBER_ONLY_TABS.includes(tab) : false;
  const gateProps = {
    classroom,
    signedIn: !!user,
    checkoutAvailable: checkout.checkoutAvailable,
    busy: checkout.processing || joining,
    error: gateError,
    onAction: () => { void startCheckout(); },
  };
  // One call to action per page: the full card on the feed and on locked tabs, the one-line banner on Giới thiệu / Shop.
  const showCard = (isExpired && !expiredCanRejoin && lockedTab) || (paidOutsider && (lockedTab || tab === 'feed' || tab === ''));
  const showBanner = (isExpired && !expiredCanRejoin && !lockedTab) || (paidOutsider && !showCard);

  const noticeRow = 'flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between';

  return (
    <div className="flex min-h-screen flex-col bg-slate-50">
      {!focusMode && (
        <ClassroomHeader
          classroom={classroom}
          hideTabs={isBlocked}
          allowedTabs={isExpired ? EXPIRED_TABS : undefined}
          lockedTabs={lockedTabs}
        />
      )}

      <div className="mx-auto w-full max-w-container space-y-4 px-4 pt-6 empty:hidden sm:px-8 sm:pt-7">
        {joinError && (
          <div role="alert" className="flex items-start gap-2.5 rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-ui font-medium text-red-700">
            <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" />
            <span>{joinError}</span>
          </div>
        )}

        {/* Join prompt if user is not member and not owner (the design's "Tham gia lớp học" card). */}
        {isGuest && !paidOutsider && !privateOutsider && (
          <section aria-label="Tham gia lớp học" className="rounded-card border border-slate-200 bg-white p-5 shadow-lift sm:px-6">
            <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between md:gap-8">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2.5">
                  <p className="text-body-sm font-semibold text-slate-900">Tham gia lớp học</p>
                  <Badge tone="free" size="sm">Miễn phí</Badge>
                  {needsApproval && <Badge tone="neutral" size="sm">Cần duyệt</Badge>}
                </div>
                <p className="mt-1 text-ui text-slate-600">
                  {needsApproval
                    ? 'Bạn đang xem lớp học này ở chế độ khách. Người dẫn dắt duyệt từng người trước khi vào lớp — gửi yêu cầu để bắt đầu.'
                    : 'Bạn đang xem lớp học này ở chế độ khách. Hãy tham gia lớp để học tập và thảo luận!'}
                </p>
                <div className="mt-3 flex flex-col gap-x-6 gap-y-1.5 sm:flex-row sm:flex-wrap">
                  {['Mở khóa học, bài thi và tài liệu của lớp', 'Đặt câu hỏi và thảo luận cùng thành viên'].map((text) => (
                    <p key={text} className="flex items-start gap-2 text-meta text-slate-600">
                      <Check className="mt-0.5 h-[13px] w-[13px] flex-shrink-0 text-green-600" strokeWidth={2.4} aria-hidden="true" />
                      {text}
                    </p>
                  ))}
                </div>
              </div>
              <div className="flex flex-shrink-0 flex-col items-stretch gap-1.5 md:w-[240px]">
                <button type="button" onClick={handleJoin} disabled={joining} className={buttonClass('primary', 'lg', '!h-12 w-full')}>
                  <UserPlus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                  <span>{joining ? (needsApproval ? 'Đang gửi yêu cầu...' : 'Đang tham gia...') : needsApproval ? 'Xin tham gia' : 'Tham gia lớp ngay'}</span>
                </button>
                <p className="text-center text-caption text-slate-500">
                  {!user ? 'Bạn sẽ đăng nhập trước, rồi quay lại đây' : needsApproval ? 'Miễn phí · vào lớp khi được duyệt' : 'Miễn phí · vào học ngay'}
                </p>
              </div>
            </div>
          </section>
        )}

        {/* Join approval: the request is waiting for the owner - a calm status, with a way to take it back. */}
        {isPending && (
          <section role="status" aria-label="Yêu cầu tham gia" data-testid="join-pending" className="rounded-card border border-slate-200 bg-white p-5 shadow-hairline sm:px-6">
            <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between md:gap-8">
              <div className="flex min-w-0 items-start gap-3">
                <span aria-hidden="true" className="inline-flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-full bg-tint text-blue-600">
                  <Hourglass className="h-[18px] w-[18px]" strokeWidth={1.75} />
                </span>
                <div className="min-w-0">
                  <p className="text-body-sm font-semibold text-slate-900">Đã gửi yêu cầu tham gia · chờ người dẫn dắt duyệt</p>
                  <p className="mt-1 text-ui text-slate-600">
                    Bạn sẽ vào lớp ngay khi được duyệt. Trong lúc chờ, bạn vẫn xem được Blog, Thảo luận công khai, Sự kiện, Shop và Giới thiệu.
                  </p>
                </div>
              </div>
              <div className="flex flex-shrink-0 items-center gap-2">
                {confirmWithdraw ? (
                  <div role="group" aria-label="Xác nhận rút yêu cầu tham gia" className="flex flex-wrap items-center gap-2">
                    <span className="text-meta font-medium text-slate-900">Rút yêu cầu tham gia?</span>
                    <button type="button" onClick={() => setConfirmWithdraw(false)} disabled={withdrawing} className={buttonClass('ghost', 'md')}>
                      Giữ yêu cầu
                    </button>
                    <button type="button" onClick={handleWithdraw} disabled={withdrawing} className={buttonClass('danger', 'md')}>
                      {withdrawing ? 'Đang rút...' : 'Rút yêu cầu'}
                    </button>
                  </div>
                ) : (
                  <button type="button" onClick={() => setConfirmWithdraw(true)} className={buttonClass('secondary', 'md')}>
                    Rút yêu cầu
                  </button>
                )}
              </div>
            </div>
          </section>
        )}

        {/* D-19: a PRIVATE class is only joined through an invite link - there is nothing to click here. */}
        {privateOutsider && (
          <div role="status" className="flex items-start gap-2.5 rounded-2xl border border-slate-200 bg-white px-4 py-3 text-ui text-slate-900 shadow-hairline sm:px-5">
            <Lock className="mt-0.5 h-4 w-4 flex-shrink-0 text-slate-600" strokeWidth={1.75} aria-hidden="true" />
            <span>Đây là lớp riêng tư. Bạn cần liên kết mời của chủ lớp để tham gia.</span>
          </div>
        )}

        {/* R16-01: REMOVED members may rejoin on their own (decision D-12). */}
        {isRemoved && (
          <div role="status" className="rounded-2xl border border-amber-200 bg-warn-soft px-4 py-3 sm:px-5">
            <div className={noticeRow}>
              <span className="text-ui text-slate-900">
                Bạn đã bị xóa khỏi lớp học này nên chưa thể học tập hay thảo luận. Bạn có thể tham gia lại bất cứ lúc nào.
              </span>
              <button type="button" onClick={handleJoin} disabled={joining} className={buttonClass('primary', 'md', 'flex-shrink-0')}>
                <UserPlus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                <span>{joining ? 'Đang tham gia...' : needsApproval ? 'Xin tham gia lại' : 'Tham gia lại'}</span>
              </button>
            </div>
          </div>
        )}

        {/* D-19: the class has become FREE since this person's paid access lapsed: no purchase, just join again. */}
        {expiredCanRejoin && (
          <div role="status" className="rounded-2xl border border-amber-200 bg-warn-soft px-4 py-3 sm:px-5">
            <div className={noticeRow}>
              <span className="text-ui text-slate-900 tabular">
                Gói thành viên lớp đã hết hạn ngày {formatDate(classroom.accessExpiresAt) || '—'}. Lớp hiện đã miễn phí: bạn có thể tham gia lại ngay.
              </span>
              <button type="button" onClick={handleJoin} disabled={joining} className={buttonClass('primary', 'md', 'flex-shrink-0')}>
                <UserPlus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                <span>{joining ? 'Đang tham gia...' : 'Tham gia lại'}</span>
              </button>
            </div>
          </div>
        )}

        {showBanner && <ClassPaywallBanner {...gateProps} mode={isExpired ? 'renew' : 'buy'} />}
      </div>

      <main className={`mx-auto w-full max-w-container flex-1 px-4 pb-24 sm:px-8 ${focusMode ? 'pt-4 sm:pt-6' : 'pt-6 sm:pt-7'}`}>
        {isBlocked ? (
          // R16-01: BLOCKED is terminal for the person - only a Studio unblock restores access - so
          // there is no rejoin button and none of the class's tabs/content is rendered.
          <div role="alert" className="mx-auto max-w-xl rounded-card border border-slate-200 bg-white p-8 text-center shadow-hairline">
            <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-red-50 text-red-700">
              <Ban className="h-6 w-6" strokeWidth={1.75} aria-hidden="true" />
            </div>
            <h2 className="text-h2-sm font-semibold text-slate-900">Bạn đã bị chặn khỏi lớp học này</h2>
            <p className="mt-2 text-ui text-slate-600">
              Bạn không thể tham gia lại hay xem nội dung dành cho thành viên. Nếu cho rằng đây là nhầm lẫn,
              hãy liên hệ chủ nhiệm lớp để được mở khóa.
            </p>
          </div>
        ) : lockedTab && expiredCanRejoin ? (
          <div className="mx-auto max-w-xl rounded-card border border-slate-200 bg-white p-8 text-center shadow-hairline">
            <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-warn-soft text-amber-800">
              <Lock className="h-6 w-6" strokeWidth={1.75} aria-hidden="true" />
            </div>
            <h2 className="text-h2-sm font-semibold text-slate-900">Nội dung dành cho thành viên</h2>
            <p className="mt-2 text-ui text-slate-600">Hãy tham gia lại lớp để tiếp tục học tập và thảo luận.</p>
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
