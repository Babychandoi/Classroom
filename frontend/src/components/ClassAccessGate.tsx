import React from 'react';
import { AlertCircle, Check, Clock, KeyRound, Lock } from 'lucide-react';
import type { Classroom } from '../types';
import { durationLabel, formatDate, formatDong } from '../api/format';
import { buttonClass } from './ui';

/**
 * D-19: what a person who is not (or no longer) a paying member sees in a PAID class.
 *  - mode "buy"   : never joined (a guest or a signed-in non-member)  -> "Mua để tham gia" / "Đăng nhập để mua"
 *  - mode "renew" : the membership lapsed (memberState EXPIRED)        -> "Gia hạn"
 * The card is the full paywall (price, length, what you get); the banner is the one-line version used on the public tabs.
 * Presentation follows the design's paid-upgrade page: benefits on the left, a checkout box with the price on the right.
 */
export interface ClassAccessGateProps {
  classroom: Classroom;
  mode: 'buy' | 'renew';
  signedIn: boolean;
  /** null while the deployment has not said yet; false = no payment rail here ("Tạm chưa hỗ trợ thanh toán"). */
  checkoutAvailable: boolean | null;
  busy: boolean;
  error?: string | null;
  onAction: () => void;
}

const actionLabel = (props: ClassAccessGateProps): string => {
  if (props.checkoutAvailable === false) return 'Tạm chưa hỗ trợ thanh toán';
  if (props.busy) return 'Đang xử lý...';
  if (props.mode === 'renew') return 'Gia hạn';
  return props.signedIn ? 'Mua để tham gia' : 'Đăng nhập để mua';
};

const priceText = (classroom: Classroom): { price: string; per: string } | null => {
  const product = classroom.accessProduct;
  if (!product) return null;
  return { price: formatDong(product.price), per: durationLabel(product.durationDays, product.lifetime) };
};

export const ClassPaywallCard: React.FC<ClassAccessGateProps> = (props) => {
  const { classroom, mode, busy, error, onAction, checkoutAvailable } = props;
  const price = priceText(classroom);
  const renew = mode === 'renew';
  const lifetime = classroom.accessProduct ? durationLabel(classroom.accessProduct.durationDays, classroom.accessProduct.lifetime) === 'trọn đời' : false;
  const benefits = [
    'Toàn bộ khóa học và bài giảng của lớp',
    'Luyện thi, chấm điểm và bảng xếp hạng',
    'Bảng tin, tài liệu và thảo luận cùng thành viên',
    lifetime
      ? 'Truy cập trọn đời, không phải gia hạn'
      : `Truy cập ${classroom.accessProduct ? durationLabel(classroom.accessProduct.durationDays) : 'theo gói'} kể từ khi thanh toán, gia hạn bất cứ lúc nào`,
  ];
  const disabled = busy || checkoutAvailable === false || !price;
  return (
    <section
      aria-labelledby="class-paywall-title"
      data-testid={renew ? 'renewal-card' : 'paywall-card'}
      className="mx-auto max-w-[880px] overflow-hidden rounded-card border border-slate-200 bg-white shadow-hairline"
    >
      <div className="grid gap-0 md:grid-cols-[minmax(0,1fr)_320px]">
        <div className="p-5 sm:p-7">
          <div className="flex items-center gap-2">
            <span
              aria-hidden="true"
              className={`inline-flex h-9 w-9 items-center justify-center rounded-btn ${renew ? 'bg-warn-soft text-amber-800' : 'bg-violet-100 text-violet-800'}`}
            >
              {renew ? <Clock className="h-[18px] w-[18px]" strokeWidth={1.75} /> : <Lock className="h-[18px] w-[18px]" strokeWidth={1.75} />}
            </span>
            <p className={`text-caption font-semibold uppercase tracking-[0.5px] ${renew ? 'text-amber-800' : 'text-violet-800'}`}>
              {renew ? 'Gia hạn thành viên' : 'Gói vào lớp'}
            </p>
          </div>
          <h2 id="class-paywall-title" className="mt-3 text-h2-sm font-semibold text-slate-900">
            {renew ? 'Gói thành viên của bạn đã hết hạn' : 'Lớp học trả phí'}
          </h2>
          <p className="mt-1.5 text-body-sm text-slate-600">
            {renew
              ? `Gói thành viên lớp đã hết hạn ngày ${formatDate(classroom.accessExpiresAt) || '—'}. Gia hạn để tiếp tục học tập và thảo luận; tiến độ và dữ liệu của bạn vẫn được giữ nguyên.`
              : 'Mua gói vào lớp để trở thành thành viên: học tập, luyện thi và thảo luận cùng cả lớp.'}
          </p>

          <h3 className="mt-5 text-caption font-semibold uppercase tracking-[0.5px] text-slate-500">Bạn nhận được</h3>
          <ul className="mt-2.5 space-y-2">
            {benefits.map((text) => (
              <li key={text} className="flex items-start gap-2 text-ui text-slate-900">
                <Check className="mt-0.5 h-4 w-4 flex-shrink-0 text-green-600" strokeWidth={2.4} aria-hidden="true" />
                <span>{text}</span>
              </li>
            ))}
          </ul>
        </div>

        <div className="flex flex-col gap-5 border-t border-slate-100 bg-slate-50 p-5 sm:p-7 md:border-l md:border-t-0">
          {price ? (
            <div>
              <span className="block text-meta font-semibold text-slate-900">{renew ? 'Phí gia hạn' : 'Giá gói vào lớp'}</span>
              <div className="mt-1 flex flex-wrap items-baseline gap-1.5">
                <span data-testid="paywall-price" className="text-h2 font-semibold text-slate-900 tabular">{price.price}</span>
                <span data-testid="paywall-duration" className="text-ui font-medium text-slate-600 tabular">/ {price.per}</span>
              </div>
              <p className="mt-2 text-caption text-slate-600">
                {lifetime ? 'Thanh toán một lần, không tự gia hạn.' : 'Không tự gia hạn — bạn chủ động gia hạn khi muốn.'}
              </p>
            </div>
          ) : (
            <p className="text-ui text-slate-600">Chủ lớp chưa công bố giá gói vào lớp.</p>
          )}

          <div className="space-y-2">
            <button type="button" onClick={onAction} disabled={disabled} className={buttonClass('primary', 'lg', 'w-full !h-12')}>
              <KeyRound className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
              <span>{actionLabel(props)}</span>
            </button>
            {checkoutAvailable === false && (
              <p className="text-center text-caption text-slate-600">Lớp chưa bật thanh toán trực tuyến. Hãy liên hệ chủ lớp để tham gia.</p>
            )}
            {error && (
              <div role="alert" className="flex items-start gap-2 rounded-btn border border-red-200 bg-red-50 p-3 text-meta text-red-700">
                <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" />
                <span>{error}</span>
              </div>
            )}
          </div>
        </div>
      </div>
    </section>
  );
};

/** The one-line version of the paywall / renewal prompt for the tabs a non-member may still read (Giới thiệu, Shop). */
export const ClassPaywallBanner: React.FC<ClassAccessGateProps> = (props) => {
  const { classroom, mode, busy, error, onAction, checkoutAvailable } = props;
  const renew = mode === 'renew';
  const product = classroom.accessProduct;
  return (
    <div
      role="status"
      data-testid={renew ? 'renewal-banner' : 'paywall-banner'}
      className={`rounded-2xl border px-4 py-3 sm:px-5 ${renew ? 'border-amber-200 bg-warn-soft' : 'border-slate-200 bg-white shadow-hairline'}`}
    >
      <div className="flex flex-col justify-between gap-3 sm:flex-row sm:items-center">
        <span className="flex items-start gap-2.5 text-ui text-slate-900">
          {renew
            ? <Clock className="mt-0.5 h-4 w-4 flex-shrink-0 text-amber-700" strokeWidth={1.75} aria-hidden="true" />
            : <Lock className="mt-0.5 h-4 w-4 flex-shrink-0 text-violet-800" strokeWidth={1.75} aria-hidden="true" />}
          <span className="tabular">
            {renew
              ? `Gói thành viên lớp đã hết hạn ngày ${formatDate(classroom.accessExpiresAt) || '—'}. Gia hạn để tiếp tục học tập và thảo luận.`
              : `Lớp học trả phí${product ? ` · ${formatDong(product.price)} / ${durationLabel(product.durationDays, product.lifetime)}` : ''}. Mua gói vào lớp để học tập và thảo luận.`}
          </span>
        </span>
        <button
          type="button"
          onClick={onAction}
          disabled={busy || checkoutAvailable === false || !product}
          className={buttonClass('primary', 'md', 'flex-shrink-0')}
        >
          <KeyRound className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
          <span>{actionLabel(props)}</span>
        </button>
      </div>
      {error && <p role="alert" className="mt-2 text-meta font-medium text-red-600">{error}</p>}
    </div>
  );
};
