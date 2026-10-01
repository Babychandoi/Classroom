// Shared checkout pieces (StoreTab, the class paywall, the invite page): the order-status copy and the idempotency key generator.
// The flow itself lives in hooks/useCheckout.ts, the dialog in components/CheckoutDialog.tsx.

// R13-06 (FR-07/TC-17): modal copy depends on the order's actual status instead of always
// claiming "đang chờ xác nhận" — a PAID/FAILED/REFUNDED/CANCELLED order was already resolved one
// way or the other and must not still tell the buyer it is pending.
export type OrderTone = 'amber' | 'emerald' | 'rose' | 'slate';

export const ORDER_STATUS_COPY: Record<string, { title: string; body: string; tone: OrderTone }> = {
  PENDING: {
    title: 'Thanh toán đang chờ xác nhận',
    body: 'Đơn hàng sẽ ở trạng thái chờ xác nhận. Quản trị lớp xử lý xác nhận, người mua không thể tự đánh dấu đã thanh toán.',
    tone: 'amber',
  },
  PAID: {
    title: 'Đã thanh toán thành công',
    body: 'Đơn hàng đã được xác nhận thanh toán; quyền truy cập tương ứng đã được cấp cho tài khoản của bạn.',
    tone: 'emerald',
  },
  FAILED: {
    title: 'Thanh toán thất bại',
    body: 'Giao dịch thanh toán cho đơn hàng này không thành công. Vui lòng thử mua lại hoặc liên hệ quản trị lớp.',
    tone: 'rose',
  },
  REFUNDED: {
    title: 'Đã hoàn tiền',
    body: 'Đơn hàng đã được hoàn tiền; quyền truy cập cấp từ đơn hàng này đã bị thu hồi.',
    tone: 'slate',
  },
  CANCELLED: {
    title: 'Đã hủy đơn hàng',
    body: 'Đơn hàng này đã bị hủy và sẽ không được xử lý thêm.',
    tone: 'slate',
  },
};

export const TONE_CLASSES: Record<OrderTone, string> = {
  amber: 'bg-amber-50 border-amber-200 text-amber-800',
  emerald: 'bg-emerald-50 border-emerald-200 text-emerald-800',
  rose: 'bg-rose-50 border-rose-200 text-rose-800',
  slate: 'bg-slate-50 border-slate-200 text-slate-700',
};

// crypto.randomUUID() is only defined in secure contexts (HTTPS/localhost); fall back to a
// manually assembled RFC 4122 v4-ish UUID elsewhere so checkout still works over plain HTTP.
export const generateIdempotencyKey = (): string => {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    try {
      return crypto.randomUUID();
    } catch {
      // fall through to the manual generator below
    }
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16);
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
};
