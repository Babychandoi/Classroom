import React from 'react';
import { AlertCircle, CheckCircle2, Clock, KeyRound, Lock } from 'lucide-react';
import type { Classroom } from '../types';
import { durationLabel, formatDate, formatDong } from '../api/format';

/**
 * D-19: what a person who is not (or no longer) a paying member sees in a PAID class.
 *  - mode "buy"   : never joined (a guest or a signed-in non-member)  -> "Mua để tham gia" / "Đăng nhập để mua"
 *  - mode "renew" : the membership lapsed (memberState EXPIRED)        -> "Gia hạn"
 * The card is the full paywall (price, length, what you get); the banner is the one-line version used on the public tabs.
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
  return (
    <section
      aria-labelledby="class-paywall-title"
      data-testid={renew ? 'renewal-card' : 'paywall-card'}
      className="max-w-2xl mx-auto bg-white border border-slate-200 rounded-3xl shadow-sm overflow-hidden"
    >
      <div className={`px-6 py-5 flex items-start gap-4 ${renew ? 'bg-amber-50 border-b border-amber-200' : 'bg-indigo-50 border-b border-indigo-100'}`}>
        <div className={`w-11 h-11 rounded-2xl flex items-center justify-center flex-shrink-0 ${renew ? 'bg-amber-100 text-amber-800' : 'bg-indigo-100 text-indigo-700'}`}>
          {renew ? <Clock className="w-6 h-6" aria-hidden="true" /> : <Lock className="w-6 h-6" aria-hidden="true" />}
        </div>
        <div className="min-w-0">
          <h2 id="class-paywall-title" className="text-lg font-extrabold text-slate-900">
            {renew ? 'Gói thành viên của bạn đã hết hạn' : 'Lớp học trả phí'}
          </h2>
          <p className="text-sm text-slate-700 mt-0.5">
            {renew
              ? `Gói thành viên lớp đã hết hạn ngày ${formatDate(classroom.accessExpiresAt) || '—'}. Gia hạn để tiếp tục học tập và thảo luận; tiến độ và dữ liệu của bạn vẫn được giữ nguyên.`
              : 'Mua gói vào lớp để trở thành thành viên: học tập, luyện thi và thảo luận cùng cả lớp.'}
          </p>
        </div>
      </div>

      <div className="p-6 grid gap-6 sm:grid-cols-2">
        <div>
          <h3 className="text-xs font-bold text-slate-600 uppercase tracking-wider mb-2">Bạn nhận được</h3>
          <ul className="space-y-2">
            {benefits.map((text) => (
              <li key={text} className="flex items-start gap-2 text-sm text-slate-700">
                <CheckCircle2 className="w-4 h-4 text-emerald-600 flex-shrink-0 mt-0.5" aria-hidden="true" />
                <span>{text}</span>
              </li>
            ))}
          </ul>
        </div>

        <div className="flex flex-col justify-between gap-4">
          {price ? (
            <div>
              <span className="text-xs text-slate-600 block">{renew ? 'Phí gia hạn' : 'Giá gói vào lớp'}</span>
              <div className="flex items-baseline gap-1.5 flex-wrap">
                <span data-testid="paywall-price" className="text-3xl font-black text-indigo-700">{price.price}</span>
                <span data-testid="paywall-duration" className="text-sm font-semibold text-slate-600">/ {price.per}</span>
              </div>
            </div>
          ) : (
            <p className="text-sm text-slate-600">Chủ lớp chưa công bố giá gói vào lớp.</p>
          )}

          <div className="space-y-2">
            <button
              type="button"
              onClick={onAction}
              disabled={busy || checkoutAvailable === false || !price}
              className={`w-full inline-flex items-center justify-center gap-2 px-5 py-3 rounded-xl text-sm font-bold shadow-sm transition disabled:opacity-60 disabled:cursor-not-allowed ${
                checkoutAvailable === false ? 'bg-slate-200 text-slate-600' : renew ? 'bg-amber-700 hover:bg-amber-800 text-white' : 'bg-indigo-600 hover:bg-indigo-700 text-white'
              }`}
            >
              <KeyRound className="w-4 h-4" aria-hidden="true" />
              <span>{actionLabel(props)}</span>
            </button>
            {error && (
              <div role="alert" className="flex items-start gap-2 p-3 bg-rose-50 border border-rose-200 rounded-xl text-xs text-rose-800">
                <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" aria-hidden="true" />
                <span>{error}</span>
              </div>
            )}
          </div>
        </div>
      </div>
    </section>
  );
};

/** The one-line version of the paywall / renewal prompt for the tabs a non-member may still read (Giới thiệu, Cửa hàng). */
export const ClassPaywallBanner: React.FC<ClassAccessGateProps> = (props) => {
  const { classroom, mode, busy, error, onAction, checkoutAvailable } = props;
  const renew = mode === 'renew';
  const product = classroom.accessProduct;
  return (
    <div
      role="status"
      data-testid={renew ? 'renewal-banner' : 'paywall-banner'}
      className={`border-b py-3 px-4 ${renew ? 'bg-amber-50 border-amber-200' : 'bg-indigo-50 border-indigo-100'}`}
    >
      <div className="max-w-7xl mx-auto flex flex-col sm:flex-row sm:items-center justify-between gap-2 sm:gap-3">
        <span className={`text-xs sm:text-sm font-medium ${renew ? 'text-amber-900' : 'text-indigo-900'}`}>
          {renew
            ? `Gói thành viên lớp đã hết hạn ngày ${formatDate(classroom.accessExpiresAt) || '—'}. Gia hạn để tiếp tục học tập và thảo luận.`
            : `Lớp học trả phí${product ? ` · ${formatDong(product.price)} / ${durationLabel(product.durationDays, product.lifetime)}` : ''}. Mua gói vào lớp để học tập và thảo luận.`}
        </span>
        <button
          type="button"
          onClick={onAction}
          disabled={busy || checkoutAvailable === false || !product}
          className={`inline-flex items-center justify-center space-x-1.5 px-4 py-1.5 text-white text-xs font-bold rounded-lg shadow-sm transition flex-shrink-0 disabled:opacity-60 disabled:cursor-not-allowed ${
            checkoutAvailable === false ? '!bg-slate-300 !text-slate-700' : renew ? 'bg-amber-700 hover:bg-amber-800' : 'bg-indigo-600 hover:bg-indigo-700'
          }`}
        >
          <KeyRound className="w-3.5 h-3.5" aria-hidden="true" />
          <span>{actionLabel(props)}</span>
        </button>
      </div>
      {error && (
        <div className="max-w-7xl mx-auto mt-2">
          <p role="alert" className="text-xs font-semibold text-rose-800">{error}</p>
        </div>
      )}
    </div>
  );
};
