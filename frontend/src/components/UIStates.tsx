import React from 'react';
import { AlertCircle, Lock, Inbox, AlertTriangle } from 'lucide-react';

export const LoadingSpinner: React.FC<{ message?: string }> = ({ message = 'Đang tải dữ liệu...' }) => (
  <div className="flex flex-col items-center justify-center p-12 space-y-3" role="status" aria-live="polite">
    <div className="w-10 h-10 border-4 border-indigo-200 border-t-indigo-600 rounded-full animate-spin"></div>
    <p className="text-sm font-medium text-slate-500">{message}</p>
  </div>
);

export const EmptyState: React.FC<{
  title?: string;
  description?: string;
  actionText?: string;
  onAction?: () => void;
}> = ({
  title = 'Chưa có dữ liệu',
  description = 'Hiện tại chưa có nội dung nào trong mục này.',
  actionText,
  onAction,
}) => (
  <div className="flex flex-col items-center justify-center text-center p-12 bg-white rounded-xl border border-slate-200 shadow-sm">
    <div className="w-12 h-12 rounded-full bg-slate-100 flex items-center justify-center text-slate-400 mb-4">
      <Inbox className="w-6 h-6" />
    </div>
    <h3 className="text-base font-semibold text-slate-800 mb-1">{title}</h3>
    <p className="text-sm text-slate-500 max-w-sm mb-4">{description}</p>
    {actionText && onAction && (
      <button
        onClick={onAction}
        className="px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition"
      >
        {actionText}
      </button>
    )}
  </div>
);

export const ErrorBanner: React.FC<{ message: string; onRetry?: () => void }> = ({ message, onRetry }) => (
  <div className="p-4 bg-rose-50 border border-rose-200 rounded-xl flex items-start space-x-3 text-rose-800" role="alert">
    <AlertCircle className="w-5 h-5 flex-shrink-0 mt-0.5" />
    <div className="flex-1">
      <p className="text-sm font-medium">{message}</p>
      {onRetry && (
        <button
          onClick={onRetry}
          className="mt-2 text-xs font-semibold text-rose-700 underline hover:text-rose-900"
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
  <div className="flex flex-col items-center justify-center text-center p-12 bg-amber-50/60 rounded-xl border border-amber-200 shadow-sm">
    <div className="w-14 h-14 rounded-full bg-amber-100 flex items-center justify-center text-amber-600 mb-4">
      <Lock className="w-7 h-7" />
    </div>
    <h3 className="text-lg font-bold text-slate-900 mb-1">{title}</h3>
    <p className="text-sm text-slate-600 max-w-md mb-6">{message}</p>
    {actionText && onAction && (
      <button
        onClick={onAction}
        className="px-5 py-2.5 bg-amber-600 text-white text-sm font-semibold rounded-lg hover:bg-amber-700 shadow-sm transition"
      >
        {actionText}
      </button>
    )}
  </div>
);

export const StatusBadge: React.FC<{ status: string }> = ({ status }) => {
  const s = status.toUpperCase();

  switch (s) {
    case 'PAID':
    case 'ACTIVE':
    case 'PUBLISHED':
      return <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-100 text-emerald-800">{s}</span>;
    case 'PENDING':
    case 'IN_PROGRESS':
    case 'DRAFT':
      return <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-amber-100 text-amber-800">{s}</span>;
    case 'FAILED':
    case 'CANCELLED':
    case 'BANNED':
      return <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-rose-100 text-rose-800">{s}</span>;
    case 'REFUNDED':
    case 'REVOKED':
    case 'EXPIRED':
      return <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-purple-100 text-purple-800">{s}</span>;
    case 'PRO':
      return <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-bold bg-amber-500 text-white shadow-sm">PRO</span>;
    case 'OWNER':
      return <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-bold bg-indigo-600 text-white">CHỦ NHIỆM</span>;
    case 'STAFF':
      return <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-blue-100 text-blue-800">TRỢ GIẢNG</span>;
    default:
      return <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-slate-100 text-slate-700">{s}</span>;
  }
};
