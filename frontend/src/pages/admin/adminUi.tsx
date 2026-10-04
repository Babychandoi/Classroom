import React, { useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowLeft, ChevronLeft, ChevronRight } from 'lucide-react';
import { Modal } from '../../components/Modal';
import { Button, Field, Textarea } from '../../components/ui';
import { formatDateTime } from '../../api/format';
import { AUDIT_ACTION_LABEL, AuditRow, REASON_MAX } from '../../api/admin';
import { EmptyRow, ModalActions } from '../studio/studioUi';

// Shared pieces of the "Quản trị nền tảng" pages. Same grammar as the Studio pages (studioUi.tsx): page header,
// hairline cards, compact tables, calm notices; every write goes through ReasonDialog (the reason is mandatory).

const cx = (...parts: Array<string | false | null | undefined>) => parts.filter(Boolean).join(' ');

/** Error text of a failed call: the server's own message for 400/409 (already Vietnamese), else a fallback. */
export const errorText = (err: unknown, fallback: string) => (err instanceof Error && err.message ? err.message : fallback);

/** "← Người dùng" back link above a detail page. */
export const BackLink: React.FC<{ to: string; children: React.ReactNode }> = ({ to, children }) => (
  <Link to={to} className="inline-flex w-fit items-center gap-1.5 text-meta font-medium text-slate-600 transition-colors duration-micro hover:text-slate-900">
    <ArrowLeft className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
    {children}
  </Link>
);

/** Label / value pair inside a details card. */
export const InfoItem: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div className="min-w-0">
    <dt className="text-caption font-medium text-slate-500">{label}</dt>
    <dd className="mt-0.5 truncate text-ui font-semibold text-slate-900 tabular">{children}</dd>
  </div>
);

/** Status pill with a fixed tone per meaning (never color alone: the label is always written). */
export const Pill: React.FC<{ tone: 'success' | 'danger' | 'warn' | 'neutral' | 'info' | 'dark' | 'paid'; children: React.ReactNode; title?: string }> = ({
  tone, children, title,
}) => (
  <span
    title={title}
    className={cx(
      'inline-flex h-[22px] flex-shrink-0 items-center whitespace-nowrap rounded-full px-2 text-micro font-semibold',
      tone === 'success' && 'bg-green-100 text-green-800',
      tone === 'danger' && 'bg-red-50 text-red-700',
      tone === 'warn' && 'bg-warn-soft text-amber-800',
      tone === 'neutral' && 'bg-slate-100 text-slate-600',
      tone === 'info' && 'bg-blue-100 text-blue-800',
      tone === 'dark' && 'bg-slate-900 text-white',
      tone === 'paid' && 'bg-violet-100 text-violet-800',
    )}
  >
    {children}
  </span>
);

/** "Trang 2/5 · 93 người dùng" + Trước / Sau. Pages are 0-based like the API. */
export const Pager: React.FC<{
  page: number;
  totalPages: number;
  totalElements: number;
  unit: string;
  onPage: (page: number) => void;
}> = ({ page, totalPages, totalElements, unit, onPage }) => (
  <nav aria-label="Phân trang" className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 px-4 py-3 sm:px-5">
    <p className="text-meta text-slate-600 tabular">
      Trang {totalPages === 0 ? 0 : page + 1}/{totalPages} · {totalElements.toLocaleString('vi-VN')} {unit}
    </p>
    <div className="flex items-center gap-2">
      <Button size="sm" onClick={() => onPage(page - 1)} disabled={page <= 0} aria-label="Trang trước">
        <ChevronLeft className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
        Trước
      </Button>
      <Button size="sm" onClick={() => onPage(page + 1)} disabled={page + 1 >= totalPages} aria-label="Trang sau">
        Sau
        <ChevronRight className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
      </Button>
    </div>
  </nav>
);

/**
 * Confirmation dialog of every admin write. The reason (1..500 chars) is required: the confirm button stays
 * disabled until it is filled. A failed call (400 self-ban, 409 last admin, ...) keeps the dialog open and shows
 * the server's message inline.
 */
export const ReasonDialog: React.FC<{
  title: string;
  description: React.ReactNode;
  confirmLabel: string;
  tone?: 'danger' | 'primary';
  /** Extra fields above the reason (e.g. the role picker). */
  children?: React.ReactNode;
  /** Extra validity of those fields (default true). */
  canConfirm?: boolean;
  onConfirm: (reason: string) => Promise<void>;
  onClose: () => void;
}> = ({ title, description, confirmLabel, tone = 'danger', children, canConfirm = true, onConfirm, onClose }) => {
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const reasonId = useId();
  const trimmed = reason.trim();

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!trimmed || !canConfirm || busy) return;
    setBusy(true);
    setError(null);
    try {
      await onConfirm(trimmed);
    } catch (err) {
      setError(errorText(err, 'Không thể thực hiện thao tác. Thử lại sau.'));
      setBusy(false);
    }
  };

  return (
    <Modal title={title} onClose={busy ? undefined : onClose} role="alertdialog" size="md">
      <form onSubmit={submit} className="space-y-4">
        <div className="text-ui text-slate-600">{description}</div>
        {children}
        <Field
          label="Lý do (bắt buộc)"
          htmlFor={reasonId}
          hint={`Lý do được ghi vào nhật ký quản trị. ${trimmed.length}/${REASON_MAX} ký tự.`}
        >
          <Textarea
            id={reasonId}
            rows={3}
            required
            maxLength={REASON_MAX}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="Ví dụ: Vi phạm điều khoản sử dụng, đã nhắc nhở 2 lần"
            className="resize-none"
          />
        </Field>
        {error && (
          <p role="alert" className="rounded-btn border border-red-200 bg-red-50 px-3.5 py-2.5 text-meta font-medium text-red-700">
            {error}
          </p>
        )}
        <ModalActions>
          <Button onClick={onClose} disabled={busy}>Hủy</Button>
          <Button type="submit" variant={tone === 'danger' ? 'danger' : 'primary'} disabled={!trimmed || !canConfirm || busy}>
            {busy ? 'Đang xử lý...' : confirmLabel}
          </Button>
        </ModalActions>
      </form>
    </Modal>
  );
};

// ---------------------------------------------------------------------------------------------------------------
// Audit rows. `details` is rendered as plain key / value text (React escapes it) - never as HTML.

const DETAIL_LABEL: Record<string, string> = {
  reason: 'Lý do',
  role: 'Vai trò',
  previousRole: 'Vai trò trước',
  status: 'Trạng thái',
  previousStatus: 'Trạng thái trước',
  resolution: 'Kết quả',
};

export const auditActionLabel = (action: string) => AUDIT_ACTION_LABEL[action] ?? action;

const detailValue = (value: unknown): string => {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
};

export const AuditDetails: React.FC<{ details: AuditRow['details'] }> = ({ details }) => {
  const entries = details && typeof details === 'object' ? Object.entries(details) : [];
  if (entries.length === 0) return null;
  return (
    <dl className="mt-2 grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 rounded-btn bg-slate-50 px-3 py-2 text-meta">
      {entries.map(([key, value]) => (
        <React.Fragment key={key}>
          <dt className="font-medium text-slate-600">{DETAIL_LABEL[key] ?? key}</dt>
          <dd className="break-words text-slate-900">{detailValue(value)}</dd>
        </React.Fragment>
      ))}
    </dl>
  );
};

/** Audit rows as a list (time + action + who + where + key/value details). */
export const AuditList: React.FC<{ rows: AuditRow[]; empty: string; showClass?: boolean }> = ({ rows, empty, showClass = true }) => {
  if (rows.length === 0) return <EmptyRow>{empty}</EmptyRow>;
  return (
    <ul className="divide-y divide-slate-100">
      {rows.map((row) => (
        <li key={row.id} className="grid gap-1 px-4 py-3.5 sm:grid-cols-[148px_minmax(0,1fr)] sm:gap-4 sm:px-5">
          <p className="text-meta text-slate-600 tabular">{formatDateTime(row.createdAt)}</p>
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
              <span className="text-ui font-semibold text-slate-900">{auditActionLabel(row.action)}</span>
              {AUDIT_ACTION_LABEL[row.action] && (
                <span className="font-mono text-micro text-slate-500">{row.action}</span>
              )}
            </div>
            <p className="mt-0.5 text-meta text-slate-600">
              {row.actor ? (
                <>
                  Bởi <Link to={`/admin/users/${row.actor.id}`} className="font-medium text-slate-900 hover:text-blue-700">{row.actor.fullName}</Link>
                  <span className="text-slate-500"> · {row.actor.email}</span>
                </>
              ) : (
                'Bởi hệ thống'
              )}
              {row.targetType === 'CLASS' && row.classId && row.targetId === row.classId && showClass ? null : ' · '}
              {row.targetType === 'CLASS' && row.classId && row.targetId === row.classId && showClass ? null : row.targetType === 'USER' && row.targetId ? (
                <Link to={`/admin/users/${row.targetId}`} className="font-medium text-slate-900 underline-offset-2 hover:text-blue-700 hover:underline">Người dùng {row.targetId.slice(0, 8)}</Link>
              ) : (
                <span className="font-mono text-caption">{row.targetType}{row.targetId ? `: ${row.targetId.slice(0, 8)}` : ''}</span>
              )}
              {showClass && row.classId && (
                <>
                  {' · '}
                  <Link to={`/admin/classes/${row.classId}`} className="font-medium text-slate-900 underline-offset-2 hover:text-blue-700 hover:underline">
                    {row.classTitle || `Lớp ${row.classId.slice(0, 8)}`}
                  </Link>
                </>
              )}
            </p>
            <AuditDetails details={row.details} />
          </div>
        </li>
      ))}
    </ul>
  );
};
