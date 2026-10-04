import React from 'react';
import { AlertCircle, CheckCircle2, Clock, RotateCcw, XCircle } from 'lucide-react';
import { Modal } from './Modal';
import { ORDER_STATUS_COPY, type OrderTone } from '../api/checkout';
import { durationLabel, formatDong } from '../api/format';
import { Badge, type BadgeTone, buttonClass } from './ui';
import type { Checkout } from '../hooks/useCheckout';

interface CheckoutDialogProps {
  checkout: Checkout;
  /** Extra primary action once the order is PAID (e.g. "Vào lớp học" on the invite page). */
  paidAction?: { label: string; onClick: () => void };
}

const ORDER_STATUS_LABEL: Record<string, { label: string; tone: BadgeTone }> = {
  PENDING: { label: 'Chờ xác nhận', tone: 'warn' },
  PAID: { label: 'Đã thanh toán', tone: 'success' },
  FAILED: { label: 'Thất bại', tone: 'danger' },
  REFUNDED: { label: 'Đã hoàn tiền', tone: 'neutral' },
  CANCELLED: { label: 'Đã hủy', tone: 'neutral' },
};

/** Vietnamese status pill of an order (never colour alone: the label says it). */
export const OrderStatusBadge: React.FC<{ status: string }> = ({ status }) => {
  const meta = ORDER_STATUS_LABEL[status] ?? { label: status, tone: 'neutral' as BadgeTone };
  return <Badge tone={meta.tone} size="sm">{meta.label}</Badge>;
};

// The status note's colours, in the design palette (the shared copy only names a tone).
const NOTE_TONE: Record<OrderTone, { box: string; icon: React.ReactNode }> = {
  amber: { box: 'border-amber-200 bg-warn-soft text-amber-800', icon: <Clock className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" /> },
  emerald: { box: 'border-green-200 bg-green-50 text-green-800', icon: <CheckCircle2 className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" /> },
  rose: { box: 'border-red-200 bg-red-50 text-red-700', icon: <XCircle className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" /> },
  slate: { box: 'border-slate-200 bg-slate-50 text-slate-600', icon: <RotateCcw className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" /> },
};

/**
 * The "Trạng thái thanh toán" dialog of the shared checkout (see hooks/useCheckout). Shows the order that was created, what the
 * status means, and lets the buyer re-read the status, cancel a still-pending order, or close. Renders nothing until an order exists.
 */
export const CheckoutDialog: React.FC<CheckoutDialogProps> = ({ checkout, paidAction }) => {
  const { order, item, processing, paymentError, checkoutAvailable, refreshOrderStatus, cancelOrder, close } = checkout;
  if (!order) return null;

  // R13-06: copy depends on the order's actual status, not a hardcoded "pending" message.
  const copy = !checkoutAvailable
    ? { title: 'Chưa cấu hình cổng thanh toán', body: 'Chức năng mua sẽ khả dụng khi lớp cấu hình một phương thức thanh toán.', tone: 'amber' as const }
    : ORDER_STATUS_COPY[order.status] || ORDER_STATUS_COPY.PENDING;
  const note = NOTE_TONE[copy.tone];

  return (
    <Modal size="md" ariaLabel="Trạng thái thanh toán" onClose={close}>
      <div className="mb-5 flex items-start justify-between gap-3">
        <div className="min-w-0">
          <span className="text-caption font-semibold text-slate-500 tabular">Mã đơn: {order.orderNumber}</span>
          <h3 className="mt-1 text-h2-sm font-semibold text-slate-900">Trạng thái thanh toán</h3>
        </div>
        <OrderStatusBadge status={order.status} />
      </div>

      <div className="mb-5 divide-y divide-slate-100 rounded-2xl border border-slate-200 bg-white">
        <div className="flex justify-between gap-3 px-4 py-3 text-meta">
          <span className="text-slate-600">Sản phẩm</span>
          <span className="text-right font-semibold text-slate-900">{item?.title}</span>
        </div>
        <div className="flex justify-between gap-3 px-4 py-3 text-meta">
          <span className="text-slate-600">Thời hạn sử dụng</span>
          <span className="font-semibold text-slate-900 tabular">{item ? durationLabel(item.durationDays) : ''}</span>
        </div>
        <div className="flex items-baseline justify-between gap-3 px-4 py-3">
          <span className="text-ui font-semibold text-slate-900">Tổng thanh toán</span>
          <span className="text-h3-lg font-semibold text-slate-900 tabular">{formatDong(order.totalAmount)}</span>
        </div>
      </div>

      {paymentError && (
        <div role="alert" className="mb-3 flex items-center gap-2 rounded-btn border border-red-200 bg-red-50 p-3 text-meta text-red-700">
          <AlertCircle className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" />
          <span>{paymentError}</span>
        </div>
      )}

      <div className="space-y-2.5">
        <div className={`space-y-1 rounded-btn border p-3.5 ${note.box}`}>
          <div className="flex items-center gap-2 text-meta font-semibold">
            {note.icon}
            <span>{copy.title}</span>
          </div>
          <p className="text-caption leading-[18px] opacity-90">{copy.body}</p>
        </div>

        {order.status === 'PAID' && paidAction && (
          <button type="button" onClick={paidAction.onClick} className={buttonClass('primary', 'lg', 'w-full')}>
            {paidAction.label}
          </button>
        )}

        <button disabled={processing} onClick={refreshOrderStatus} className={buttonClass('secondary', 'md', 'w-full')}>
          Làm mới trạng thái đơn hàng
        </button>
        {order.status === 'PENDING' && (
          <button disabled={processing} onClick={cancelOrder} className={buttonClass('ghost', 'md', 'w-full !text-red-700 hover:!bg-red-50')}>
            Hủy đơn hàng
          </button>
        )}
        <button disabled={processing} onClick={close} className={buttonClass('ghost', 'md', 'w-full')}>
          {processing ? 'Đang xử lý…' : 'Đóng cửa sổ'}
        </button>
      </div>
    </Modal>
  );
};
