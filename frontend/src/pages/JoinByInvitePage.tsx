import React, { useCallback, useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { AlertCircle, Ban, KeyRound, LogIn, MailX, UserPlus } from 'lucide-react';
import { Classroom, InvitePreview } from '../types';
import { api, ApiException } from '../api/client';
import { accessProductFromError, isPaymentRequired } from '../api/errors';
import { accessPriceLabel } from '../api/format';
import { useAuth } from '../context/AuthContext';
import { useCheckout } from '../hooks/useCheckout';
import { CheckoutDialog } from '../components/CheckoutDialog';
import { AccessBadge } from '../components/ClassBadges';
import { LoadingSpinner, ErrorBanner } from '../components/UIStates';

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
      <div className="max-w-xl mx-auto w-full px-4 py-12">
        <div role="alert" className="p-8 bg-white border border-slate-200 rounded-2xl shadow-sm text-center">
          <div className="w-12 h-12 mx-auto rounded-full bg-slate-100 flex items-center justify-center text-slate-600 mb-3">
            <MailX className="w-6 h-6" aria-hidden="true" />
          </div>
          <h1 className="text-lg font-bold text-slate-900">Mã mời không hợp lệ hoặc đã hết hạn</h1>
          <p className="text-sm text-slate-600 mt-2">
            Liên kết có thể đã bị thu hồi, hết hạn, hết lượt dùng hoặc lớp học đã đóng. Hãy xin chủ lớp một liên kết mới.
          </p>
          <Link
            to="/classes"
            className="mt-5 inline-flex items-center px-5 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-bold rounded-xl shadow-sm transition"
          >
            Xem các lớp học công khai
          </Link>
        </div>
      </div>
    );
  }

  if (state.kind === 'error') {
    return (
      <div className="max-w-xl mx-auto w-full px-4 py-12">
        <ErrorBanner message={state.message} onRetry={loadPreview} />
      </div>
    );
  }

  const { preview: card } = state;
  const hasPrice = card.accessType === 'PAID' && card.price != null;
  const accessForBadge = {
    accessType: card.accessType,
    accessProduct: hasPrice
      ? { id: '', price: Number(card.price), currency: card.currency || 'VND', durationDays: card.durationDays ?? null, lifetime: card.lifetime ?? card.durationDays == null }
      : null,
  };
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

  return (
    <div className="max-w-xl mx-auto w-full px-4 py-8 sm:py-12">
      <article aria-labelledby="invite-class-title" className="bg-white border border-slate-200 rounded-3xl shadow-sm overflow-hidden">
        <div className="h-40 sm:h-48 w-full bg-slate-800 relative overflow-hidden">
          {card.coverImageUrl ? (
            <img src={card.coverImageUrl} alt="" referrerPolicy="no-referrer" className="w-full h-full object-cover opacity-90" />
          ) : (
            <div className="w-full h-full bg-gradient-to-tr from-indigo-700 to-purple-600" />
          )}
          <div className="absolute inset-x-0 bottom-0 h-16 bg-gradient-to-t from-slate-950/60 to-transparent" />
          <span className="absolute left-4 bottom-3 inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-white text-indigo-800 text-xs font-bold">
            <KeyRound className="w-3.5 h-3.5" aria-hidden="true" />
            Bạn được mời vào lớp
          </span>
        </div>

        <div className="p-6 space-y-4">
          <div>
            <h1 id="invite-class-title" className="text-2xl font-black text-slate-900 tracking-tight break-words">{card.title}</h1>
            {card.ownerName && <p className="text-sm text-slate-600 mt-1">Chủ lớp: <span className="font-semibold text-slate-800">{card.ownerName}</span></p>}
          </div>

          <p className="text-sm text-slate-700 whitespace-pre-line break-words">{card.description || 'Chưa có mô tả lớp học.'}</p>

          <div className="flex flex-wrap items-center gap-2">
            <AccessBadge classroom={accessForBadge} />
            {hasPrice && (
              <span className="text-sm text-slate-700">
                Gói vào lớp: <strong data-testid="invite-price">{accessPriceLabel(Number(card.price), card.durationDays, card.lifetime)}</strong>
              </span>
            )}
          </div>

          {blockedMessage ? (
            <div role="alert" className="p-4 bg-rose-50 border border-rose-200 rounded-2xl text-center">
              <div className="w-10 h-10 mx-auto rounded-full bg-rose-100 flex items-center justify-center text-rose-600 mb-2">
                <Ban className="w-5 h-5" aria-hidden="true" />
              </div>
              <h2 className="text-base font-bold text-rose-900">Bạn đã bị chặn khỏi lớp học này</h2>
              <p className="text-sm text-rose-800 mt-1">{blockedMessage} Nếu cho rằng đây là nhầm lẫn, hãy liên hệ chủ lớp để được mở khóa.</p>
            </div>
          ) : (
            <div className="space-y-3 pt-1">
              {!user && !authLoading ? (
                // `replace` so the page carrying the code does not pile up in history (sign-in returns here and replaces /login again).
                <Link
                  to="/login"
                  replace
                  state={{ from: location }}
                  className="w-full inline-flex items-center justify-center gap-2 px-5 py-3 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-bold rounded-xl shadow-sm transition"
                >
                  <LogIn className="w-4 h-4" aria-hidden="true" />
                  <span>Đăng nhập để tham gia</span>
                </Link>
              ) : (
                <button
                  type="button"
                  onClick={handleJoin}
                  disabled={busy || authLoading || unavailable}
                  className="w-full inline-flex items-center justify-center gap-2 px-5 py-3 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-bold rounded-xl shadow-sm transition disabled:opacity-60 disabled:cursor-not-allowed"
                >
                  {isPaid ? <KeyRound className="w-4 h-4" aria-hidden="true" /> : <UserPlus className="w-4 h-4" aria-hidden="true" />}
                  <span>{primaryLabel}</span>
                </button>
              )}

              {actionError && (
                <div role="alert" className="flex items-start gap-2 p-3 bg-rose-50 border border-rose-200 rounded-xl text-xs text-rose-800">
                  <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" aria-hidden="true" />
                  <span>{actionError}</span>
                </div>
              )}

              <p className="text-[11px] text-slate-500 text-center">
                Liên kết mời này là bí mật của riêng bạn. Đừng chia sẻ nó với người khác.
              </p>
            </div>
          )}
        </div>
      </article>

      <CheckoutDialog
        checkout={checkout}
        paidAction={{ label: 'Vào lớp học', onClick: () => goToClass(card.slug) }}
      />
    </div>
  );
};
