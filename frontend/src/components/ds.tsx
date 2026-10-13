import React, { useEffect, useId, useRef, useState } from 'react';
import { Inbox, Info, X } from 'lucide-react';

// D-35 design-system primitives of the new UI (prototype 01-ds-icons-primitives.jsx + 03-ui-kit.jsx), in Tailwind.
// ui.tsx keeps the long-standing building blocks (Button, Card, Badge, inputs); this file adds the prototype-only ones.

const cx = (...parts: Array<string | false | null | undefined>) => parts.filter(Boolean).join(' ');

// ---------------------------------------------------------------------------------------------------------------
// Tag: small status pill (bg / fg / optional dot) from the prototype's tag palette.

export type TagColor = 'gray' | 'green' | 'amber' | 'blue' | 'violet' | 'red' | 'ink' | 'pink' | 'orange';

const TAG_COLOR: Record<TagColor, { pill: string; dot: string }> = {
  gray: { pill: 'bg-slate-100 text-slate-600', dot: 'bg-slate-500' },
  green: { pill: 'bg-[#ECFDF3] text-green-700', dot: 'bg-green-600' },
  amber: { pill: 'bg-amber-50 text-amber-700', dot: 'bg-amber-500' },
  blue: { pill: 'bg-blue-50 text-blue-700', dot: 'bg-blue-600' },
  violet: { pill: 'bg-violet-50 text-violet-700', dot: 'bg-violet-600' },
  red: { pill: 'bg-red-50 text-red-700', dot: 'bg-red-600' },
  ink: { pill: 'bg-slate-900 text-white', dot: 'bg-white' },
  pink: { pill: 'bg-pink-50 text-pink-700', dot: 'bg-pink-600' },
  orange: { pill: 'bg-orange-50 text-orange-700', dot: 'bg-orange-600' },
};

export const Tag: React.FC<{
  color?: TagColor;
  dot?: boolean;
  size?: 'sm' | 'md';
  shape?: 'pill' | 'square';
  className?: string;
  title?: string;
  children: React.ReactNode;
}> = ({ color = 'gray', dot = false, size = 'sm', shape = 'pill', className, title, children }) => (
  <span
    title={title}
    className={cx(
      'inline-flex flex-shrink-0 items-center gap-[5px] whitespace-nowrap font-semibold',
      shape === 'square' ? 'rounded-[5px] px-1.5 py-px text-micro-xs' : 'rounded-full',
      shape === 'pill' && (size === 'md' ? 'px-[9px] py-1 text-caption' : 'px-2 py-0.5 text-micro'),
      TAG_COLOR[color].pill,
      className,
    )}
  >
    {dot && <span aria-hidden="true" className={cx('h-1.5 w-1.5 flex-shrink-0 rounded-full', TAG_COLOR[color].dot)} />}
    {children}
  </span>
);

// ---------------------------------------------------------------------------------------------------------------
// Keyboard helper shared by the radio-like and tab-like strips: arrows / Home / End move between the options.

function useRovingKeys<T extends string>(keys: T[], value: T, onChange: (key: T) => void) {
  const refs = useRef<Record<string, HTMLButtonElement | null>>({});
  const onKeyDown = (event: React.KeyboardEvent) => {
    const i = keys.indexOf(value);
    let next = -1;
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = (i + 1) % keys.length;
    else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = (i - 1 + keys.length) % keys.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = keys.length - 1;
    if (next < 0) return;
    event.preventDefault();
    onChange(keys[next]);
    refs.current[keys[next]]?.focus();
  };
  return { refs, onKeyDown };
}

export type Option<T extends string = string> = { key: T; label: React.ReactNode; disabled?: boolean };

// Segmented control: a radio group rendered as a pill track (one choice out of 2-4).
export function Segmented<T extends string>({
  options, value, onChange, ariaLabel, size = 'md', className,
}: {
  options: Option<T>[];
  value: T;
  onChange: (key: T) => void;
  ariaLabel: string;
  size?: 'sm' | 'md';
  className?: string;
}) {
  const enabled = options.filter((o) => !o.disabled).map((o) => o.key);
  const { refs, onKeyDown } = useRovingKeys(enabled, value, onChange);
  return (
    <div role="radiogroup" aria-label={ariaLabel} onKeyDown={onKeyDown} className={cx('inline-flex rounded-btn bg-slate-100 p-1', className)}>
      {options.map((option) => {
        const on = option.key === value;
        return (
          <button
            key={option.key}
            ref={(el) => { refs.current[option.key] = el; }}
            type="button"
            role="radio"
            aria-checked={on}
            tabIndex={on ? 0 : -1}
            disabled={option.disabled}
            onClick={() => onChange(option.key)}
            className={cx(
              'inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-[9px] font-semibold transition-colors duration-micro disabled:cursor-not-allowed disabled:opacity-50',
              size === 'sm' ? 'h-7 px-2.5 text-caption' : 'h-8 px-3 text-meta',
              on ? 'bg-white text-slate-900 shadow-hairline' : 'text-slate-600 hover:text-slate-900',
            )}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}

// Underline tabs (in-page sections). A real tablist: the caller renders the matching panel.
export function UnderlineTabs<T extends string>({
  items, value, onChange, ariaLabel, stretch = false, className, idPrefix,
}: {
  items: (Option<T> & { count?: number })[];
  value: T;
  onChange: (key: T) => void;
  ariaLabel: string;
  stretch?: boolean;
  className?: string;
  /** When set, each tab gets id `${idPrefix}-tab-${key}` and aria-controls `${idPrefix}-panel-${key}`. */
  idPrefix?: string;
}) {
  const { refs, onKeyDown } = useRovingKeys(items.filter((i) => !i.disabled).map((i) => i.key), value, onChange);
  return (
    <div role="tablist" aria-label={ariaLabel} onKeyDown={onKeyDown} className={cx('flex overflow-x-auto border-b border-line scrollbar-none', className)}>
      {items.map((item) => {
        const on = item.key === value;
        return (
          <button
            key={item.key}
            ref={(el) => { refs.current[item.key] = el; }}
            type="button"
            role="tab"
            id={idPrefix ? `${idPrefix}-tab-${item.key}` : undefined}
            aria-controls={idPrefix ? `${idPrefix}-panel-${item.key}` : undefined}
            aria-selected={on}
            tabIndex={on ? 0 : -1}
            disabled={item.disabled}
            onClick={() => onChange(item.key)}
            className={cx(
              'relative -mb-px inline-flex h-11 flex-shrink-0 items-center justify-center gap-1 whitespace-nowrap border-b-2 px-3 text-meta font-semibold transition-colors duration-micro disabled:opacity-50',
              stretch && 'flex-1',
              on ? 'border-slate-900 text-slate-900' : 'border-transparent text-slate-500 hover:text-slate-900',
            )}
          >
            {item.label}
            {item.count != null && <span className="text-caption font-medium text-slate-500 tabular">{item.count}</span>}
          </button>
        );
      })}
    </div>
  );
}

// Filter pills: a row of toggle buttons (selected = ink pill), optionally with a count bubble.
export function FilterPills<T extends string>({
  items, value, onChange, ariaLabel, className,
}: {
  items: (Option<T> & { count?: number; alert?: boolean })[];
  value: T;
  onChange: (key: T) => void;
  ariaLabel?: string;
  className?: string;
}) {
  return (
    <div role="group" aria-label={ariaLabel} className={cx('flex items-center gap-1.5 overflow-x-auto pb-1.5 pt-0.5 scrollbar-none', className)}>
      {items.map((item) => {
        const on = item.key === value;
        return (
          <button
            key={item.key}
            type="button"
            aria-pressed={on}
            disabled={item.disabled}
            onClick={() => onChange(item.key)}
            className={cx(
              'inline-flex h-[30px] flex-shrink-0 items-center whitespace-nowrap rounded-full px-3 text-meta font-semibold transition-colors duration-micro disabled:opacity-50',
              on ? 'bg-slate-900 text-white' : 'bg-slate-100 text-slate-900 hover:bg-slate-200',
            )}
          >
            {item.label}
            {!!item.count && (
              <span
                className={cx(
                  'ml-1.5 inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-full px-[5px] text-micro font-bold tabular',
                  on ? 'bg-white/20 text-white' : item.alert ? 'bg-red-600 text-white' : 'bg-slate-200 text-slate-900',
                )}
              >
                {item.count}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}

// ---------------------------------------------------------------------------------------------------------------
// Page furniture.

export const PageHead: React.FC<{
  title: React.ReactNode;
  eyebrow?: React.ReactNode;
  sub?: React.ReactNode;
  actions?: React.ReactNode;
  as?: 'h1' | 'h2';
  className?: string;
}> = ({ title, eyebrow, sub, actions, as: Heading = 'h1', className }) => (
  <div className={cx('ap-in flex flex-wrap items-end justify-between gap-3.5', className)}>
    <div className="min-w-0 flex-1 basis-[220px]">
      {eyebrow && <p className="mb-1.5 text-micro font-semibold uppercase tracking-[0.6px] text-slate-500">{eyebrow}</p>}
      <Heading className="text-[24px] font-extrabold leading-[30px] tracking-[-0.5px] text-slate-900 sm:text-[34px] sm:leading-[40px] sm:tracking-[-1px]">
        {title}
      </Heading>
      {sub && <p className="mt-1.5 max-w-[640px] text-ui text-slate-500 sm:text-body-sm">{sub}</p>}
    </div>
    {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
  </div>
);

export const KPI: React.FC<{
  label: React.ReactNode;
  value: React.ReactNode;
  sub?: React.ReactNode;
  delta?: string;
  className?: string;
}> = ({ label, value, sub, delta, className }) => (
  <div className={cx('ap-in min-w-0 rounded-card border border-line-card bg-white p-[18px]', className)}>
    <p className="text-meta font-medium text-slate-500">{label}</p>
    <div className="mt-1.5 flex items-baseline gap-2">
      <span className="text-[28px] font-extrabold leading-8 tracking-[-1px] text-slate-900 tabular">{value}</span>
      {delta && <span className={cx('text-caption font-semibold', delta.startsWith('-') ? 'text-red-700' : 'text-green-700')}>{delta}</span>}
    </div>
    {sub && <p className="mt-1 text-caption text-slate-500">{sub}</p>}
  </div>
);

/** Progress ring (0-100). The label defaults to "N%"; `ariaLabel` names the meter for assistive tech. */
export const Ring: React.FC<{
  value: number;
  size?: number;
  stroke?: number;
  colorClass?: string;
  label?: React.ReactNode;
  sub?: React.ReactNode;
  ariaLabel?: string;
}> = ({ value, size = 140, stroke = 12, colorClass = 'text-slate-900', label, sub, ariaLabel }) => {
  const pct = Math.max(0, Math.min(100, value));
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  return (
    <div
      role="meter"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(pct)}
      aria-label={ariaLabel}
      className="relative flex-shrink-0"
      style={{ width: size, height: size }}
    >
      <svg width={size} height={size} className="-rotate-90" aria-hidden="true">
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="#EEF2F7" strokeWidth={stroke} />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          fill="none"
          stroke="currentColor"
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={c}
          strokeDashoffset={c * (1 - pct / 100)}
          className={cx('transition-[stroke-dashoffset] duration-spatial', colorClass)}
        />
      </svg>
      <span className="absolute inset-0 flex flex-col items-center justify-center">
        <span className="font-extrabold tracking-[-1px] text-slate-900 tabular" style={{ fontSize: Math.round(size * 0.24), lineHeight: 1.1 }}>
          {label ?? `${Math.round(pct)}%`}
        </span>
        {sub && <span className="text-caption font-medium text-slate-500">{sub}</span>}
      </span>
    </div>
  );
};

export type Skill = 'gv' | 'listening' | 'reading' | 'speaking' | 'writing' | 'focus';
const SKILL_TILE: Record<Skill, string> = {
  gv: 'bg-skill-gv-bg text-skill-gv-fg',
  listening: 'bg-skill-listening-bg text-skill-listening-fg',
  reading: 'bg-skill-reading-bg text-skill-reading-fg',
  speaking: 'bg-skill-speaking-bg text-skill-speaking-fg',
  writing: 'bg-skill-writing-bg text-skill-writing-fg',
  focus: 'bg-skill-focus-bg text-skill-focus-fg',
};

/** Tinted square tile carrying a stroke icon (one hue per skill / subject). */
export const SkillTile: React.FC<{ skill?: Skill; size?: number; className?: string; children: React.ReactNode }> = ({
  skill = 'focus', size = 36, className, children,
}) => (
  <span
    aria-hidden="true"
    className={cx('inline-flex flex-shrink-0 items-center justify-center', SKILL_TILE[skill], className)}
    style={{ width: size, height: size, borderRadius: size > 32 ? 10 : 8 }}
  >
    {children}
  </span>
);

// ---------------------------------------------------------------------------------------------------------------
// Lists.

export const ListCard: React.FC<React.HTMLAttributes<HTMLDivElement> & { as?: 'div' | 'ul' | 'section' }> = ({
  as: Tag = 'div', className, children, ...rest
}) => (
  <Tag className={cx('overflow-hidden rounded-card border border-line-card bg-white', className)} {...(rest as React.HTMLAttributes<HTMLElement>)}>
    {children}
  </Tag>
);

/** One row of a ListCard. With onClick it is a button (keyboard reachable); `active` marks the selected row. */
export const ListRow: React.FC<{
  onClick?: () => void;
  active?: boolean;
  className?: string;
  children: React.ReactNode;
  ariaLabel?: string;
}> = ({ onClick, active = false, className, children, ariaLabel }) => {
  const classes = cx(
    'flex w-full items-center gap-3 border-b border-line px-[18px] py-3.5 text-left transition-colors duration-micro last:border-b-0',
    active ? 'bg-blue-50' : 'bg-white',
    onClick && !active && 'hover:bg-slate-50',
    className,
  );
  return onClick ? (
    <button type="button" onClick={onClick} aria-current={active ? 'true' : undefined} aria-label={ariaLabel} className={classes}>
      {children}
    </button>
  ) : (
    <div className={classes}>{children}</div>
  );
};

// ---------------------------------------------------------------------------------------------------------------
// Notice: a soft tinted strip (info / warning / success / danger) with an optional action.

const NOTICE_TONE: Record<'amber' | 'blue' | 'green' | 'red' | 'gray', string> = {
  amber: 'bg-amber-50 text-amber-700',
  blue: 'bg-blue-50 text-blue-700',
  green: 'bg-[#ECFDF3] text-green-700',
  red: 'bg-red-50 text-red-700',
  gray: 'bg-slate-100 text-slate-600',
};

export const Notice: React.FC<{
  tone?: keyof typeof NOTICE_TONE;
  icon?: React.ReactNode;
  action?: React.ReactNode;
  role?: 'status' | 'alert' | 'note';
  className?: string;
  children: React.ReactNode;
}> = ({ tone = 'amber', icon, action, role, className, children }) => (
  <div role={role} className={cx('flex flex-wrap items-center gap-3 rounded-2xl px-4 py-3 text-meta font-medium', NOTICE_TONE[tone], className)}>
    <span aria-hidden="true" className="flex-shrink-0">{icon ?? <Info className="h-[18px] w-[18px]" strokeWidth={1.9} />}</span>
    <span className="min-w-[200px] flex-1 text-slate-900">{children}</span>
    {action}
  </div>
);

// ---------------------------------------------------------------------------------------------------------------
// Empty state card (prototype "Empty").

export const Empty: React.FC<{
  title: React.ReactNode;
  sub?: React.ReactNode;
  icon?: React.ReactNode;
  action?: React.ReactNode;
  className?: string;
}> = ({ title, sub, icon, action, className }) => (
  <div className={cx('flex flex-col items-center gap-2 rounded-card border border-line-card bg-white p-9 text-center', className)}>
    <span aria-hidden="true" className="flex h-12 w-12 items-center justify-center rounded-community bg-slate-100 text-slate-500">
      {icon ?? <Inbox className="h-6 w-6" strokeWidth={1.7} />}
    </span>
    <p className="text-h3 font-bold text-slate-900">{title}</p>
    {sub && <p className="max-w-[420px] text-ui text-slate-500">{sub}</p>}
    {action && <div className="mt-2">{action}</div>}
  </div>
);

// ---------------------------------------------------------------------------------------------------------------
// Sheet: a bottom sheet dialog (phones) / centred panel. Escape and the scrim close it.

export const Sheet: React.FC<{
  open: boolean;
  title: React.ReactNode;
  onClose: () => void;
  children: React.ReactNode;
  footer?: React.ReactNode;
  className?: string;
}> = ({ open, title, onClose, children, footer, className }) => {
  const titleId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    panelRef.current?.focus();
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-[90] flex items-end justify-center sm:items-center">
      <div aria-hidden="true" className="absolute inset-0 bg-slate-900/45" onClick={onClose} />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={cx(
          'ap-up relative flex max-h-[85vh] w-full flex-col overflow-hidden rounded-t-section bg-white shadow-modal focus:outline-none sm:max-w-[560px] sm:rounded-section',
          className,
        )}
      >
        <div className="flex items-center justify-between gap-3 border-b border-line px-[18px] py-4">
          <h2 id={titleId} className="text-[16px] font-bold leading-[21px] tracking-[-0.2px] text-slate-900">{title}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Đóng"
            className="inline-flex h-[30px] w-[30px] items-center justify-center rounded-full bg-slate-100 text-slate-600 hover:text-slate-900"
          >
            <X className="h-4 w-4" strokeWidth={2.2} aria-hidden="true" />
          </button>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4 sm:px-[18px]">{children}</div>
        {footer && <div className="border-t border-line px-4 py-3 sm:px-[18px]">{footer}</div>}
      </div>
    </div>
  );
};

// ---------------------------------------------------------------------------------------------------------------
// MasterDetail: list on the left, a sticky 420px detail panel on the right from lg up; a Sheet below lg.

/** True when the media query matches (false when matchMedia is unavailable, e.g. jsdom - callers pick the safe default). */
export function useMediaQuery(query: string, fallback = false): boolean {
  const get = () => (typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(query).matches : fallback);
  const [matches, setMatches] = useState(get);
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const mql = window.matchMedia(query);
    const onChange = () => setMatches(mql.matches);
    onChange();
    mql.addEventListener?.('change', onChange);
    return () => mql.removeEventListener?.('change', onChange);
  }, [query]);
  return matches;
}

export const MasterDetail: React.FC<{
  list: React.ReactNode;
  detail: React.ReactNode;
  open: boolean;
  onClose: () => void;
  title: React.ReactNode;
}> = ({ list, detail, open, onClose, title }) => {
  const wide = useMediaQuery('(min-width: 1024px)', true);
  if (!wide) {
    return (
      <>
        {list}
        <Sheet open={open} title={title} onClose={onClose}>{detail}</Sheet>
      </>
    );
  }
  return (
    <div className={cx('grid items-start gap-4', open ? 'grid-cols-[minmax(0,1fr)_420px]' : 'grid-cols-[minmax(0,1fr)]')}>
      <div className="min-w-0">{list}</div>
      {open && (
        <section aria-label={typeof title === 'string' ? title : undefined} className="ap-slide sticky top-[84px] overflow-hidden rounded-section border border-line-card bg-white">
          <div className="flex items-center justify-between border-b border-line px-[18px] py-4">
            <h2 className="text-[16px] font-bold leading-[21px] tracking-[-0.2px] text-slate-900">{title}</h2>
            <button
              type="button"
              onClick={onClose}
              aria-label="Đóng"
              className="inline-flex h-[30px] w-[30px] items-center justify-center rounded-full bg-slate-100 text-slate-600 hover:text-slate-900"
            >
              <X className="h-4 w-4" strokeWidth={2.2} aria-hidden="true" />
            </button>
          </div>
          <div className="p-[18px]">{detail}</div>
        </section>
      )}
    </div>
  );
};

// Meta row: "a · b · c", each item kept on one line.
export const Meta: React.FC<{ items: React.ReactNode[]; className?: string }> = ({ items, className }) => (
  <div className={cx('flex flex-wrap gap-x-1.5 text-caption text-slate-500', className)}>
    {items.filter(Boolean).map((item, i) => (
      <React.Fragment key={i}>
        {i > 0 && <span aria-hidden="true">·</span>}
        <span className="whitespace-nowrap">{item}</span>
      </React.Fragment>
    ))}
  </div>
);
