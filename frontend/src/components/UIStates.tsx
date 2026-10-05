import React from 'react';
import { AlertCircle, Lock, Inbox } from 'lucide-react';

// Loading / empty / error / forbidden states in the Connecty style: calm, hairline surfaces, and every
// empty or error state says what to do next instead of leaving a blank screen.

export const LoadingSpinner: React.FC<{ message?: string }> = ({ message = 'Đang tải dữ liệu...' }) => (
  <div className="flex flex-col items-center justify-center space-y-3 p-12" role="status" aria-live="polite">
    <div className="h-8 w-8 animate-spin rounded-full border-[3px] border-slate-200 border-t-blue-600"></div>
    <p className="text-meta font-medium text-slate-500">{message}</p>
  </div>
);

export const EmptyState: React.FC<{
  title?: string;
  description?: string;
  actionText?: string;
  onAction?: () => void;
  icon?: React.ReactNode;
  /** Extra actions (links) under the button, e.g. a primary and a secondary call to action. */
  children?: React.ReactNode;
}> = ({
  title = 'Chưa có dữ liệu',
  description = 'Hiện tại chưa có nội dung nào trong mục này.',
  actionText,
  onAction,
  icon,
  children,
}) => (
  <div className="flex flex-col items-center justify-center rounded-card border border-slate-200 bg-white p-10 text-center shadow-hairline sm:p-12">
    <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-community bg-slate-100 text-slate-500">
      {icon ?? <Inbox className="h-6 w-6" strokeWidth={1.7} />}
    </div>
    <h3 className="mb-1 text-h3 font-semibold text-slate-900">{title}</h3>
    <p className="mb-5 max-w-sm text-ui text-slate-600">{description}</p>
    {actionText && onAction && (
      <button
        type="button"
        onClick={onAction}
        className="inline-flex h-10 items-center rounded-btn border border-slate-200 bg-white px-4 text-ui font-semibold text-slate-900 transition-colors duration-micro hover:bg-slate-100 press"
      >
        {actionText}
      </button>
    )}
    {children && <div className="flex flex-wrap items-center justify-center gap-3">{children}</div>}
  </div>
);

export const ErrorBanner: React.FC<{ message: string; onRetry?: () => void }> = ({ message, onRetry }) => (
  <div className="flex items-start space-x-3 rounded-2xl border border-red-200 bg-red-50 p-4 text-red-700" role="alert">
    <AlertCircle className="mt-0.5 h-5 w-5 flex-shrink-0" strokeWidth={1.8} />
    <div className="flex-1">
      <p className="text-ui font-medium">{message}</p>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-2 text-meta font-semibold text-red-700 underline underline-offset-2 hover:text-red-800"
        >
          Thử lại
        </button>
      )}
    </div>
  </div>
);

export const ForbiddenState: React.FC<{
  title?: string;
  message?: string;
  actionText?: string;
  onAction?: () => void;
}> = ({
  title = 'Quyền truy cập bị giới hạn',
  message = 'Nội dung này yêu cầu quyền thành viên hoặc tài khoản PRO.',
  actionText,
  onAction,
}) => (
  <div className="flex flex-col items-center justify-center rounded-card border border-slate-200 bg-white p-10 text-center shadow-hairline sm:p-12">
    <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-community bg-slate-100 text-slate-600">
      <Lock className="h-6 w-6" strokeWidth={1.7} />
    </div>
    <h3 className="mb-1 text-h3-lg font-semibold text-slate-900">{title}</h3>
    <p className="mb-6 max-w-md text-ui text-slate-600">{message}</p>
    {actionText && onAction && (
      <button
        type="button"
        onClick={onAction}
        className="inline-flex h-11 items-center rounded-btn bg-blue-600 px-[22px] text-body-sm font-semibold text-white transition-colors duration-micro hover:bg-blue-700 press"
      >
        {actionText}
      </button>
    )}
  </div>
);

const pill = 'inline-flex h-[22px] flex-shrink-0 items-center whitespace-nowrap rounded-full px-2 text-micro font-semibold';

// Status codes from the API, shown in Vietnamese (the raw code stays available as the tooltip).
const STATUS_BADGES: Record<string, { label: string; tone: string }> = {
  PAID: { label: 'Đã thanh toán', tone: 'bg-green-100 text-green-800' },
  ACTIVE: { label: 'Đang hoạt động', tone: 'bg-green-100 text-green-800' },
  PUBLISHED: { label: 'Đã đăng', tone: 'bg-green-100 text-green-800' },
  OPEN: { label: 'Đang mở', tone: 'bg-green-100 text-green-800' },
  PENDING: { label: 'Chờ xác nhận', tone: 'bg-warn-soft text-amber-800' },
  IN_PROGRESS: { label: 'Đang làm', tone: 'bg-warn-soft text-amber-800' },
  GRADING: { label: 'Đang chấm', tone: 'bg-warn-soft text-amber-800' },
  SUBMITTED: { label: 'Đã nộp', tone: 'bg-blue-100 text-blue-800' },
  DRAFT: { label: 'Nháp', tone: 'bg-slate-100 text-slate-600' },
  CLOSED: { label: 'Đã đóng', tone: 'bg-slate-100 text-slate-600' },
  ARCHIVED: { label: 'Đã lưu trữ', tone: 'bg-slate-100 text-slate-600' },
  FAILED: { label: 'Thất bại', tone: 'bg-red-50 text-red-700' },
  CANCELLED: { label: 'Đã hủy', tone: 'bg-red-50 text-red-700' },
  BANNED: { label: 'Bị khóa', tone: 'bg-red-50 text-red-700' },
  BLOCKED: { label: 'Đã chặn', tone: 'bg-red-50 text-red-700' },
  REMOVED: { label: 'Đã xóa khỏi lớp', tone: 'bg-slate-100 text-slate-600' },
  REFUNDED: { label: 'Đã hoàn tiền', tone: 'bg-violet-100 text-violet-800' },
  REVOKED: { label: 'Đã thu hồi', tone: 'bg-violet-100 text-violet-800' },
  EXPIRED: { label: 'Đã hết hạn', tone: 'bg-violet-100 text-violet-800' },
  PRO: { label: 'PRO', tone: 'bg-amber-100 text-amber-800' },
  OWNER: { label: 'Chủ lớp', tone: 'bg-blue-100 text-blue-800' },
  STAFF: { label: 'Trợ giảng', tone: 'bg-sky-100 text-sky-800' },
  STUDENT: { label: 'Học viên', tone: 'bg-slate-100 text-slate-600' },
};

export const StatusBadge: React.FC<{ status: string }> = ({ status }) => {
  const s = status.toUpperCase();
  const badge = STATUS_BADGES[s];
  return (
    <span title={s} className={`${pill} ${badge ? badge.tone : 'bg-slate-100 text-slate-600'}`}>
      {badge ? badge.label : s}
    </span>
  );
};
