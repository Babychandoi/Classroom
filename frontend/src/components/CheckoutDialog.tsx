import React from 'react';
import { AlertCircle } from 'lucide-react';
import { Modal } from './Modal';
import { StatusBadge } from './UIStates';
import { ORDER_STATUS_COPY, TONE_CLASSES } from '../api/checkout';
import { durationLabel } from '../api/format';
import type { Checkout } from '../hooks/useCheckout';

interface CheckoutDialogProps {
  checkout: Checkout;
  /** Extra primary action once the order is PAID (e.g. "Vào lớp học" on the invite page). */
  paidAction?: { label: string; onClick: () => void };
}

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

  return (
    <Modal size="md" ariaLabel="Trạng thái thanh toán" onClose={close}>
      <div className="flex justify-between items-start mb-4">
        <div>
          <span className="text-[11px] font-mono font-bold text-indigo-600 uppercase">
            Mã đơn: {order.orderNumber}
          </span>
          <h3 className="text-xl font-bold text-slate-900 mt-0.5">Trạng thái thanh toán</h3>
        </div>
        <StatusBadge status={order.status} />
      </div>

      <div className="bg-slate-50 p-4 rounded-2xl border border-slate-100 mb-6 space-y-2 text-xs">
        <div className="flex justify-between gap-3">
          <span className="text-slate-500">Sản phẩm:</span>
          <span className="font-bold text-slate-800 text-right">{item?.title}</span>
        </div>
        <div className="flex justify-between">
          <span className="text-slate-500">Thời hạn sử dụng:</span>
          <span className="font-bold text-slate-800">{item ? durationLabel(item.durationDays) : ''}</span>
        </div>
        <div className="flex justify-between pt-2 border-t border-slate-200 text-sm">
          <span className="font-bold text-slate-700">Tổng thanh toán:</span>
          <span className="font-black text-indigo-600">{new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' }).format(order.totalAmount)}</span>
        </div>
      </div>

      {paymentError && (
        <div role="alert" className="mb-3 p-3 bg-rose-50 border border-rose-200 rounded-xl text-xs text-rose-700 flex items-center space-x-2">
          <AlertCircle className="w-4 h-4 text-rose-600 flex-shrink-0" />
          <span>{paymentError}</span>
        </div>
      )}

      <div className="space-y-3">
        <div className={`p-3.5 border rounded-xl text-xs space-y-1 ${TONE_CLASSES[copy.tone]}`}>
          <div className="font-bold flex items-center space-x-1.5">
            <AlertCircle className="w-4 h-4 flex-shrink-0" />
            <span>{copy.title}</span>
          </div>
          <p className="text-[11px] leading-relaxed opacity-90">{copy.body}</p>
        </div>

        {order.status === 'PAID' && paidAction && (
          <button
            type="button"
            onClick={paidAction.onClick}
            className="w-full py-2.5 text-center text-sm font-bold text-white bg-emerald-600 hover:bg-emerald-700 rounded-xl shadow-sm transition"
          >
            {paidAction.label}
          </button>
        )}

        <button
          disabled={processing}
          onClick={refreshOrderStatus}
          className="w-full py-2.5 text-center text-xs font-semibold text-indigo-700 hover:text-indigo-900 disabled:opacity-50"
        >
          Làm mới trạng thái đơn hàng
        </button>
        {order.status === 'PENDING' && (
          <button
            disabled={processing}
            onClick={cancelOrder}
            className="w-full py-2.5 text-center text-xs font-semibold text-rose-600 hover:text-rose-800 disabled:opacity-50"
          >
            Hủy đơn hàng
          </button>
        )}
        <button
          disabled={processing}
          onClick={close}
          className="w-full py-2.5 text-center text-xs font-semibold text-slate-500 hover:text-slate-800 disabled:opacity-50"
        >
          {processing ? 'Đang xử lý…' : 'Đóng cửa sổ'}
        </button>
      </div>
    </Modal>
  );
};
