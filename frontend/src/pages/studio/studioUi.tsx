import React from 'react';
import { CheckCircle2, Info, TriangleAlert } from 'lucide-react';

// Shared presentation pieces of the "Xưởng" (Studio) pages, built from the Connecty tokens in components/ui.tsx.
// No design file exists for most Studio pages, so they all follow the same grammar: page header (title + one-line
// description + ONE primary action on the right), content in hairline cards, compact tables, calm inline notices.

const cx = (...parts: Array<string | false | null | undefined>) => parts.filter(Boolean).join(' ');

/** Page body width (~1080px); the 32px / 16px gutter comes from StudioLayout's <main>. */
export const StudioPage: React.FC<{ className?: string; width?: 'default' | 'narrow'; children: React.ReactNode }> = ({
  className, width = 'default', children,
}) => (
  // Narrow pages keep the same left edge as the others (title does not jump when switching pages); only the
  // content column is narrower.
  <div className={cx('mx-auto w-full max-w-[1080px]', className)}>
    {/* grid gap, not space-y: a Modal rendered as a child must not pick up a sibling margin (it shifted the fixed
        overlay down by 24px and left a white strip above it). */}
    <div className={cx('grid grid-cols-[minmax(0,1fr)] gap-6', width === 'narrow' && 'max-w-[760px]')}>{children}</div>
  </div>
);

export const PageHeader: React.FC<{
  title: React.ReactNode;
  description?: React.ReactNode;
  action?: React.ReactNode;
  children?: React.ReactNode;
}> = ({ title, description, action, children }) => (
  <header className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
    <div className="min-w-0">
      <h1 className="text-h2-sm font-semibold tracking-[-0.3px] text-slate-900 sm:text-h2">{title}</h1>
      {description && <p className="mt-1 max-w-[640px] text-ui text-slate-600">{description}</p>}
      {children}
    </div>
    {action && <div className="flex flex-shrink-0 flex-wrap items-center gap-2 sm:pt-1">{action}</div>}
  </header>
);

/** Card section heading: 16px title + optional 13px description, action on the right. */
export const CardHeader: React.FC<{
  title: React.ReactNode;
  description?: React.ReactNode;
  action?: React.ReactNode;
  id?: string;
  icon?: React.ReactNode;
  className?: string;
}> = ({ title, description, action, id, icon, className }) => (
  <div className={cx('flex flex-wrap items-start justify-between gap-3', className)}>
    <div className="min-w-0">
      <h2 id={id} className="flex items-center gap-2 text-[16px] font-semibold leading-6 text-slate-900">
        {icon}
        {title}
      </h2>
      {description && <p className="mt-0.5 text-meta text-slate-600">{description}</p>}
    </div>
    {action && <div className="flex flex-shrink-0 flex-wrap items-center gap-2">{action}</div>}
  </div>
);

/** Inline notice: success (green), warning (amber), info (slate). Never color-only - each tone has an icon. */
export const Notice: React.FC<{
  tone?: 'success' | 'warn' | 'info';
  role?: 'status' | 'alert' | 'note' | 'group';
  className?: string;
  children: React.ReactNode;
  'aria-label'?: string;
}> = ({ tone = 'info', role, className, children, ...rest }) => {
  const Icon = tone === 'success' ? CheckCircle2 : tone === 'warn' ? TriangleAlert : Info;
  return (
    <div
      role={role}
      aria-label={rest['aria-label']}
      className={cx(
        'flex items-start gap-2.5 rounded-btn border px-3.5 py-3 text-meta',
        tone === 'success' && 'border-green-200 bg-green-50 text-green-800',
        tone === 'warn' && 'border-amber-200 bg-warn-soft text-amber-900',
        tone === 'info' && 'border-slate-200 bg-slate-50 text-slate-600',
        className,
      )}
    >
      <Icon className="mt-0.5 h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" />
      <div className="min-w-0 flex-1">{children}</div>
    </div>
  );
};

/** Compact data table header cell: 12px uppercase eyebrow, slate-500. */
export const thClass = 'px-4 py-2.5 text-left text-caption font-semibold uppercase tracking-[0.5px] text-slate-500';
export const tdClass = 'px-4 py-3 align-top text-ui text-slate-900';

/** Small inline text action inside rows ("Sửa", "Gỡ bán"...). 32px tall hit area, tone by meaning. */
export const rowActionClass = (tone: 'default' | 'danger' | 'warn' | 'success' = 'default') =>
  cx(
    'inline-flex h-8 items-center gap-1.5 rounded-[10px] px-2.5 text-meta font-semibold transition-colors duration-micro disabled:cursor-not-allowed disabled:opacity-40',
    tone === 'default' && 'text-slate-600 hover:bg-slate-100 hover:text-slate-900',
    tone === 'danger' && 'text-red-700 hover:bg-red-50',
    tone === 'warn' && 'text-amber-800 hover:bg-warn-soft',
    tone === 'success' && 'text-green-800 hover:bg-green-50',
  );

/** Square icon-only row action (reorder arrows, delete...). */
export const iconActionClass = (tone: 'default' | 'danger' = 'default') =>
  cx(
    'inline-flex h-8 w-8 items-center justify-center rounded-[10px] transition-colors duration-micro disabled:cursor-not-allowed disabled:opacity-30',
    tone === 'default' ? 'text-slate-500 hover:bg-slate-100 hover:text-slate-900' : 'text-slate-500 hover:bg-red-50 hover:text-red-600',
  );

/** Footer row of a Modal form: secondary "Hủy" left of the primary action. */
export const ModalActions: React.FC<{ className?: string; children: React.ReactNode }> = ({ className, children }) => (
  <div className={cx('flex flex-col-reverse gap-2 pt-2 sm:flex-row sm:justify-end', className)}>{children}</div>
);

/** Radio-card (buy-box grammar of the design): selected = 2px blue border on tint. */
export const radioCardClass = (selected: boolean, enabled = true) =>
  cx(
    'flex items-start gap-3 rounded-[14px] p-3.5 transition-colors duration-micro',
    enabled ? 'cursor-pointer' : 'cursor-not-allowed opacity-70',
    selected ? 'border-2 border-blue-600 bg-tint' : 'border border-slate-200 bg-white hover:bg-slate-50',
  );

/** Visually-quiet empty row inside a card/list. */
export const EmptyRow: React.FC<{ children: React.ReactNode; className?: string }> = ({ children, className }) => (
  <p className={cx('px-5 py-8 text-center text-ui text-slate-500', className)}>{children}</p>
);
