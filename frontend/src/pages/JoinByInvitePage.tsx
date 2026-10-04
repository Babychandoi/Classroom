import React, { useCallback, useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { AlertCircle, Ban, Check, GraduationCap, KeyRound, LogIn, MailX, UserPlus } from 'lucide-react';
import { Classroom, InvitePreview } from '../types';
import { api, ApiException } from '../api/client';
import { accessProductFromError, isPaymentRequired } from '../api/errors';
import { accessPriceLabel, durationLabel } from '../api/format';
import { useAuth } from '../context/AuthContext';
import { useCheckout } from '../hooks/useCheckout';
import { CheckoutDialog } from '../components/CheckoutDialog';
import { LoadingSpinner, ErrorBanner } from '../components/UIStates';
import { Avatar, Badge, buttonClass, toneFor } from '../components/ui';

// The format every generated code has (24 random bytes -> 32 URL-safe characters); the server checks the same 16..128 range before it
// touches the database. Anything else cannot be a code, so the page does not even ask (and does not count as a guess).
const INVITE_CODE_FORMAT = /^[A-Za-z0-9_-]{16,128}$/;

type PreviewState =
  | { kind: 'loading' }
  | { kind: 'ready'; preview: InvitePreview }
  | { kind: 'invalid' }
  | { kind: 'error'; message: string };

/**
 * D-19: /join/:code - where an invite link lands. SECURITY: the code is a bearer secret that sits in the URL, so this page
 *  - sets `<meta name="referrer" content="no-referrer">` for as long as it is mounted (and tags its one external <img> the same way),
 *  - sends the code only to our own API, never logs it and never puts it in document.title,
 *  - leaves history with `replace` navigation once the person has joined / bought (see also the guest's sign-in link, which replaces too).
 * Every unusable code (unknown, malformed, revoked, expired, used up, class archived) is the same "Mã mời không hợp lệ hoặc đã hết hạn".
 */
export const JoinByInvitePage: React.FC = () => {
  const { code = '' } = useParams<{ code: string }>();
  const { user, isLoading: authLoading } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [state, setState] = useState<PreviewState>({ kind: 'loading' });
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [blockedMessage, setBlockedMessage] = useState<string | null>(null);

  // The code must not leak through the Referer header of anything this page loads (cover image, links) while it is open.
  useEffect(() => {
    const meta = document.createElement('meta');
    meta.name = 'referrer';
    meta.content = 'no-referrer';
    document.head.appendChild(meta);
    return () => { meta.remove(); };
  }, []);

  const loadPreview = useCallback(async () => {
    setActionError(null);
    setBlockedMessage(null);
    if (!INVITE_CODE_FORMAT.test(code)) {
      setState({ kind: 'invalid' });
      return;
    }
    setState({ kind: 'loading' });
    try {
      const preview = await api.get<InvitePreview>(`/classes/invites/${encodeURIComponent(code)}`);
      setState(preview ? { kind: 'ready', preview } : { kind: 'invalid' });
    } catch (err: any) {
      if (err instanceof ApiException && err.status === 404) {
        setState({ kind: 'invalid' });
      } else if (err instanceof ApiException && err.status === 429) {
        setState({ kind: 'error', message: 'Bạn đã thử quá nhiều lần trong thời gian ngắn. Vui lòng đợi ít phút rồi thử lại.' });
      } else {
        setState({ kind: 'error', message: err?.message || 'Không thể tải thông tin lớp học' });
      }
    }
  }, [code]);

  useEffect(() => {
    void loadPreview();
  }, [loadPreview]);

  const preview = state.kind === 'ready' ? state.preview : null;
  const isPaid = preview?.accessType === 'PAID';

  const goToClass = (slug: string) => navigate(`/classes/${slug}/feed`, { replace: true });

  // Only a PAID invite can need checkout; the availability lookup is skipped for a guest and for a FREE class.
  const checkout = useCheckout({ classId: preview?.classId, checkAvailability: isPaid && !!user });

  const handleJoin = async () => {
    if (!preview || !user || busy) return;
    setBusy(true);
    setActionError(null);
    try {
      // An existing member, the owner and staff are simply let in (the server spends no use of the code on them).
      const joined = await api.post<Classroom>(`/classes/invites/${encodeURIComponent(code)}/join`);
      goToClass(joined?.slug || preview.slug);
    } catch (err: any) {
      if (isPaymentRequired(err)) {
        // A PAID class answers 402 with the product to buy (that is also how a PRIVATE class, invisible to an outsider, reveals it).
        const product = accessProductFromError(err);
        if (!product) {
          setActionError('Không thể mở thanh toán cho lớp này. Vui lòng thử lại sau.');
        } else {
          const result = await checkout.buy(
            { id: product.id, title: `Gói vào lớp · ${preview.title}`, durationDays: product.durationDays, price: product.price },
            { inviteCode: code },
          );
          if (!result.ok) {
            const reason = result.error as { status?: number; message?: string };
            if (reason?.status === 404) setState({ kind: 'invalid' });
            else setActionError(reason?.message || 'Khởi tạo đơn hàng thất bại');
          }
        }
      } else if (err instanceof ApiException && err.status === 404) {
        // The code was revoked / used up / the class was archived between the preview and this click.
        setState({ kind: 'invalid' });
      } else if (err instanceof ApiException && err.status === 403) {
        setBlockedMessage(err.message || 'Bạn đã bị chặn khỏi lớp học này.');
      } else {
        setActionError(err?.message || 'Tham gia lớp thất bại');
      }
    } finally {
      setBusy(false);
    }
  };

  if (state.kind === 'loading') {
    return <LoadingSpinner message="Đang kiểm tra liên kết mời..." />;
  }

  if (state.kind === 'invalid') {
    return (
      <div className="mx-auto w-full max-w-xl px-4 py-12 sm:py-16">
        <div role="alert" className="rounded-card border border-slate-200 bg-white p-8 text-center shadow-hairline sm:p-10">
          <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-community bg-slate-100 text-slate-500">
            <MailX className="h-6 w-6" strokeWidth={1.7} aria-hidden="true" />
          </div>
          <h1 className="text-h3-lg font-semibold text-slate-900">Mã mời không hợp lệ hoặc đã hết hạn</h1>
          <p className="mx-auto mt-2 max-w-md text-ui text-slate-600">
            Liên kết có thể đã bị thu hồi, hết hạn, hết lượt dùng hoặc lớp học đã đóng. Hãy xin chủ lớp một liên kết mới.
          </p>
          <Link to="/classes" className={buttonClass('secondary', 'md', 'mt-6')}>
            Xem các lớp học công khai
          </Link>
        </div>
      </div>
    );
  }

  if (state.kind === 'error') {
    return (
      <div className="mx-auto w-full max-w-xl px-4 py-12">
        <ErrorBanner message={state.message} onRetry={loadPreview} />
      </div>
    );
  }

  const { preview: card } = state;
  const hasPrice = card.accessType === 'PAID' && card.price != null;
  const unavailable = isPaid && !!user && checkout.checkoutAvailable === false;

  const primaryLabel = authLoading
    ? 'Đang xác thực...'
    : unavailable
    ? 'Tạm chưa hỗ trợ thanh toán'
    : busy
    ? 'Đang xử lý...'
    : isPaid
    ? 'Mua để tham gia'
    : 'Tham gia lớp';

  const perks = [
    'Bạn vào lớp bằng liên kết mời của chủ lớp.',
    isPaid
      ? hasPrice ? `Thanh toán một lần, vào lớp ${durationLabel(card.durationDays, card.lifetime)}.` : 'Lớp trả phí — thanh toán để vào lớp.'
      : 'Miễn phí — không cần thanh toán.',
  ];

  return (
    <div className="mx-auto w-full max-w-[1040px] px-4 py-6 sm:px-8 sm:py-10">
      <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_360px]">
        <article aria-labelledby="invite-class-title" className="min-w-0">
          <div className="relative h-40 overflow-hidden rounded-section bg-slate-100 sm:h-[216px]">
            {card.coverImageUrl ? (
              <img src={card.coverImageUrl} alt="" referrerPolicy="no-referrer" className="h-full w-full object-cover" />
            ) : (
              <div aria-hidden="true" className={`flex h-full w-full items-center justify-center ${toneFor(card.classId)}`}>
                <GraduationCap className="h-11 w-11 opacity-80" strokeWidth={1.5} />
              </div>
            )}
            <span className="absolute bottom-3 left-3 inline-flex h-[26px] items-center gap-1.5 rounded-full bg-slate-900/75 px-2.5 text-caption font-semibold text-white">
              <KeyRound className="h-3.5 w-3.5" strokeWidth={1.8} aria-hidden="true" />
              Bạn được mời vào lớp
            </span>
          </div>
          <div className="flex items-end gap-4 px-4 sm:px-6">
            <span
              aria-hidden="true"
              className={`-mt-7 flex h-[72px] w-[72px] flex-shrink-0 items-center justify-center rounded-[20px] border-4 border-slate-50 text-[26px] font-bold ${toneFor(card.classId)}`}
            >
              {(card.title.trim()[0] || '?').toUpperCase()}
            </span>
            <div className="min-w-0 pt-3">
              <h1 id="invite-class-title" className="break-words text-h2-sm font-semibold tracking-[-0.3px] text-slate-900 sm:text-[22px] sm:leading-[30px]">
                {card.title}
              </h1>
              <p className="mt-0.5 text-meta text-slate-600">Lời mời tham gia lớp học</p>
            </div>
          </div>

          <section className="mt-6 rounded-card border border-slate-200 bg-white p-5 shadow-hairline sm:px-7 sm:py-6">
            <h2 className="text-h3 font-semibold text-slate-900">Về lớp học này</h2>
            <p className="mt-2 whitespace-pre-line break-words text-body-sm text-slate-600">{card.description || 'Chưa có mô tả lớp học.'}</p>
            {card.ownerName && (
              <div className="mt-5 flex items-center gap-3 border-t border-slate-100 pt-5">
                <Avatar name={card.ownerName} size={40} />
                <div className="min-w-0">
                  <p className="text-caption font-semibold uppercase tracking-[0.5px] text-slate-500">Người dẫn dắt</p>
                  <p className="truncate text-ui text-slate-600">Chủ lớp: <strong className="font-semibold text-slate-900">{card.ownerName}</strong></p>
                </div>
              </div>
            )}
          </section>
        </article>

        <aside className="rounded-card border border-slate-200 bg-white p-[22px] shadow-lift lg:sticky lg:top-[88px]">
          <div className="flex items-center justify-between gap-3">
            <h2 className="text-body-sm font-semibold text-slate-900">Tham gia lớp học</h2>
            {isPaid ? <Badge tone="paid">Trả phí</Badge> : <Badge tone="free">Miễn phí</Badge>}
          </div>
          {hasPrice && (
            <p className="mt-3 text-ui text-slate-600">
              Gói vào lớp: <strong data-testid="invite-price" className="font-semibold text-slate-900 tabular">{accessPriceLabel(Number(card.price), card.durationDays, card.lifetime)}</strong>
            </p>
          )}
          <ul className="mt-3.5 space-y-2.5">
            {perks.map((perk) => (
              <li key={perk} className="flex items-start gap-2.5 text-meta text-slate-600">
                <Check className="mt-0.5 h-4 w-4 flex-shrink-0 text-green-600" strokeWidth={2} aria-hidden="true" />
                <span>{perk}</span>
              </li>
            ))}
          </ul>

          {blockedMessage ? (
            <div role="alert" className="mt-4 rounded-2xl border border-red-200 bg-red-50 p-4 text-center">
              <div className="mx-auto mb-2 flex h-10 w-10 items-center justify-center rounded-full bg-white text-red-600">
                <Ban className="h-5 w-5" strokeWidth={1.8} aria-hidden="true" />
              </div>
              <h2 className="text-ui font-semibold text-red-700">Bạn đã bị chặn khỏi lớp học này</h2>
              <p className="mt-1 text-meta text-red-700">{blockedMessage} Nếu cho rằng đây là nhầm lẫn, hãy liên hệ chủ lớp để được mở khóa.</p>
            </div>
          ) : (
            <div className="mt-4 space-y-3">
              {!user && !authLoading ? (
                // `replace` so the page carrying the code does not pile up in history (sign-in returns here and replaces /login again).
                <Link to="/login" replace state={{ from: location }} className={buttonClass('primary', 'lg', 'h-12 w-full')}>
                  <LogIn className="h-4 w-4" strokeWidth={1.8} aria-hidden="true" />
                  <span>Đăng nhập để tham gia</span>
                </Link>
              ) : (
                <button
                  type="button"
                  onClick={handleJoin}
                  disabled={busy || authLoading || unavailable}
                  className={buttonClass('primary', 'lg', 'h-12 w-full')}
                >
                  {isPaid ? <KeyRound className="h-4 w-4" strokeWidth={1.8} aria-hidden="true" /> : <UserPlus className="h-4 w-4" strokeWidth={1.8} aria-hidden="true" />}
                  <span>{primaryLabel}</span>
                </button>
              )}

              {actionError && (
                <div role="alert" className="flex items-start gap-2 rounded-btn border border-red-200 bg-red-50 p-3 text-meta text-red-700">
                  <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0" strokeWidth={1.8} aria-hidden="true" />
                  <span>{actionError}</span>
                </div>
              )}

              <p className="text-center text-caption text-slate-500">
                Liên kết mời này là bí mật của riêng bạn. Đừng chia sẻ nó với người khác.
              </p>
            </div>
          )}
        </aside>
      </div>

      <CheckoutDialog
        checkout={checkout}
        paidAction={{ label: 'Vào lớp học', onClick: () => goToClass(card.slug) }}
      />
    </div>
  );
};
