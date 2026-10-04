import React from 'react';

// Shared building blocks of the Connecty design system (design-system/02-components.md). Pages compose these
// instead of re-deriving the tokens, so a button / badge / card looks the same on every screen.

const cx = (...parts: Array<string | false | null | undefined>) => parts.filter(Boolean).join(' ');

// ---------------------------------------------------------------------------------------------------------------
// Buttons. Rule 90/9/1: at most one `primary` per viewport; everything else is secondary or a text link.

export type ButtonVariant = 'primary' | 'secondary' | 'tertiary' | 'danger' | 'done' | 'on-dark' | 'ghost';
export type ButtonSize = 'lg' | 'md' | 'sm';

const BUTTON_BASE = 'inline-flex items-center justify-center gap-2 font-semibold whitespace-nowrap transition-colors duration-micro ease-out press disabled:cursor-not-allowed';
const BUTTON_SIZE: Record<ButtonSize, string> = {
  lg: 'h-11 px-[22px] text-body-sm rounded-btn',
  md: 'h-10 px-4 text-ui rounded-btn',
  sm: 'h-8 px-3 text-meta rounded-[10px]',
};
const BUTTON_VARIANT: Record<ButtonVariant, string> = {
  primary: 'bg-blue-600 text-white hover:bg-blue-700 disabled:bg-slate-200 disabled:text-slate-400',
  secondary: 'border border-slate-200 bg-white text-slate-900 hover:bg-slate-100 disabled:text-slate-400 disabled:hover:bg-white',
  tertiary: 'text-blue-600 hover:text-blue-700 !h-auto !px-0 disabled:text-slate-400',
  danger: 'border border-red-200 bg-red-50 text-red-700 hover:bg-red-100 disabled:opacity-60',
  done: 'border border-green-200 bg-green-100 text-green-800 hover:bg-green-200/60',
  'on-dark': 'bg-white text-slate-900 hover:bg-slate-100',
  ghost: 'text-slate-600 hover:bg-slate-100 hover:text-slate-900',
};

export const buttonClass = (variant: ButtonVariant = 'secondary', size: ButtonSize = 'md', extra?: string) =>
  cx(BUTTON_BASE, BUTTON_SIZE[size], BUTTON_VARIANT[variant], extra);

export const Button = React.forwardRef<HTMLButtonElement, React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  size?: ButtonSize;
}>(({ variant = 'secondary', size = 'md', className, type = 'button', ...rest }, ref) => (
  <button ref={ref} type={type} className={buttonClass(variant, size, className)} {...rest} />
));
Button.displayName = 'Button';

// Square 40x40 icon button (search / bell / menu in the top bar).
export const IconButton = React.forwardRef<HTMLButtonElement, React.ButtonHTMLAttributes<HTMLButtonElement> & { label: string }>(
  ({ label, className, type = 'button', children, ...rest }, ref) => (
    <button
      ref={ref}
      type={type}
      aria-label={label}
      title={label}
      className={cx('inline-flex h-10 w-10 items-center justify-center rounded-btn text-slate-600 transition-colors duration-micro hover:bg-slate-100 hover:text-slate-900', className)}
      {...rest}
    >
      {children}
    </button>
  ),
);
IconButton.displayName = 'IconButton';

// ---------------------------------------------------------------------------------------------------------------
// Badges / pills. Always nowrap + no shrink. `tone` follows the semantic palette of 01-foundations.md.

export type BadgeTone = 'free' | 'paid' | 'member' | 'pro' | 'level' | 'warn' | 'neutral' | 'success' | 'danger' | 'info' | 'dark';

const BADGE_TONE: Record<BadgeTone, string> = {
  free: 'bg-green-100 text-green-800',
  paid: 'bg-violet-100 text-violet-800',
  member: 'bg-blue-100 text-blue-800',
  pro: 'bg-amber-100 text-amber-800',
  level: 'bg-slate-100 text-slate-600',
  warn: 'bg-warn-soft text-amber-800',
  neutral: 'bg-slate-100 text-slate-600',
  success: 'bg-green-100 text-green-800',
  danger: 'bg-red-50 text-red-700',
  info: 'bg-sky-100 text-sky-800',
  dark: 'bg-slate-900 text-white',
};

export const Badge: React.FC<{
  tone?: BadgeTone;
  size?: 'md' | 'sm' | 'xs';
  className?: string;
  children: React.ReactNode;
  title?: string;
}> = ({ tone = 'neutral', size = 'md', className, children, title }) => (
  <span
    title={title}
    className={cx(
      'inline-flex flex-shrink-0 items-center gap-1 whitespace-nowrap rounded-full',
      size === 'md' && 'h-[26px] px-2.5 text-caption font-semibold',
      size === 'sm' && 'h-[22px] px-2 text-micro font-semibold',
      size === 'xs' && 'h-[18px] px-1.5 text-micro-xs font-bold uppercase tracking-[0.3px]',
      BADGE_TONE[tone],
      className,
    )}
  >
    {children}
  </span>
);

// "● Hoạt động hôm nay" - a live dot followed by its text.
export const LiveDot: React.FC<{ children?: React.ReactNode; className?: string }> = ({ children, className }) => (
  <span className={cx('inline-flex items-center gap-1.5', className)}>
    <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-green-600" />
    {children}
  </span>
);

// ---------------------------------------------------------------------------------------------------------------
// Surfaces.

export const Card: React.FC<React.HTMLAttributes<HTMLDivElement> & {
  as?: 'div' | 'section' | 'article' | 'aside';
  padded?: boolean;
  hover?: boolean;
  radius?: 'card' | 'lg' | 'md';
}> = ({ as: Tag = 'div', padded = true, hover = false, radius = 'card', className, children, ...rest }) => (
  <Tag
    className={cx(
      'border border-slate-200 bg-white shadow-hairline',
      radius === 'card' && 'rounded-card',
      radius === 'lg' && 'rounded-2xl',
      radius === 'md' && 'rounded-btn',
      padded && 'p-5 sm:p-6',
      hover && 'card-hover',
      className,
    )}
    {...rest}
  >
    {children}
  </Tag>
);

// Page width: 1200px container with a 32px gutter on desktop and 16px on phones.
export const Container: React.FC<React.HTMLAttributes<HTMLDivElement> & { width?: 'default' | 'reading' | 'wide' }> = ({
  width = 'default', className, children, ...rest
}) => (
  <div
    className={cx(
      'mx-auto w-full px-4 sm:px-8',
      width === 'default' && 'max-w-container',
      width === 'reading' && 'max-w-[784px]',
      width === 'wide' && 'max-w-[1280px]',
      className,
    )}
    {...rest}
  >
    {children}
  </div>
);

// Uppercase eyebrow label (<= 3 words).
export const Eyebrow: React.FC<{ children: React.ReactNode; className?: string; as?: 'p' | 'span' | 'h2' | 'h3' }> = ({
  children, className, as: Tag = 'p',
}) => (
  <Tag className={cx('text-caption font-semibold uppercase tracking-[0.6px] text-slate-500', className)}>{children}</Tag>
);

// Section heading row: title (+ optional description) on the left, a "Xem tất cả"-style action on the right.
export const SectionHeader: React.FC<{
  title: React.ReactNode;
  description?: React.ReactNode;
  action?: React.ReactNode;
  as?: 'h1' | 'h2' | 'h3';
  size?: 'h1' | 'h2' | 'h2-sm' | 'h3';
  className?: string;
  id?: string;
}> = ({ title, description, action, as: Tag = 'h2', size = 'h2-sm', className, id }) => (
  <div className={cx('flex items-end justify-between gap-4', className)}>
    <div className="min-w-0">
      <Tag
        id={id}
        className={cx(
          'font-semibold text-slate-900',
          size === 'h1' && 'text-h2 sm:text-h1',
          size === 'h2' && 'text-h2-sm sm:text-h2',
          size === 'h2-sm' && 'text-h2-sm',
          size === 'h3' && 'text-h3-lg',
        )}
      >
        {title}
      </Tag>
      {description && <p className="mt-1 text-ui text-slate-600">{description}</p>}
    </div>
    {action && <div className="flex flex-shrink-0 items-center gap-2">{action}</div>}
  </div>
);

// ---------------------------------------------------------------------------------------------------------------
// Avatars. A PERSON is always round; a CLASS (community) is a rounded square - never round.

const TOPIC_TONES = [
  'bg-blue-100 text-blue-800',
  'bg-green-100 text-green-800',
  'bg-amber-100 text-amber-800',
  'bg-pink-100 text-pink-800',
  'bg-sky-100 text-sky-800',
  'bg-violet-100 text-violet-800',
  'bg-red-100 text-red-800',
  'bg-slate-200 text-slate-700',
];

const hashString = (value: string) => {
  let h = 0;
  for (let i = 0; i < value.length; i++) h = (h * 31 + value.charCodeAt(i)) | 0;
  return Math.abs(h);
};

/** Stable topic tone (bg + text classes) for a seed such as a class id or a name. */
export const toneFor = (seed: string | undefined | null) => TOPIC_TONES[hashString(seed || '?') % TOPIC_TONES.length];

const initialOf = (name?: string | null) => {
  const trimmed = (name || '').trim();
  if (!trimmed) return '?';
  const words = trimmed.split(/\s+/);
  return (words[words.length - 1][0] || '?').toUpperCase();
};

export const Avatar: React.FC<{
  name?: string | null;
  src?: string | null;
  size?: number;
  className?: string;
  ring?: boolean;
}> = ({ name, src, size = 32, className, ring = false }) => {
  const [failed, setFailed] = React.useState(false);
  const style = { width: size, height: size, fontSize: Math.max(10, Math.round(size * 0.42)) };
  const ringClass = ring ? 'ring-2 ring-white' : '';
  if (src && !failed) {
    return (
      <img
        src={src}
        alt={name ? `Ảnh đại diện của ${name}` : ''}
        onError={() => setFailed(true)}
        style={style}
        className={cx('flex-shrink-0 rounded-full object-cover', ringClass, className)}
      />
    );
  }
  return (
    <span
      aria-hidden={name ? undefined : true}
      title={name || undefined}
      style={style}
      className={cx('inline-flex flex-shrink-0 items-center justify-center rounded-full font-semibold', toneFor(name), ringClass, className)}
    >
      {initialOf(name)}
    </span>
  );
};

export const ClassAvatar: React.FC<{
  title: string;
  seed?: string;
  src?: string | null;
  size?: number;
  className?: string;
  bordered?: boolean;
}> = ({ title, seed, src, size = 48, className, bordered = false }) => {
  const radius = size >= 44 ? 14 : size >= 32 ? 10 : 8;
  const style = { width: size, height: size, borderRadius: radius, fontSize: Math.max(11, Math.round(size * 0.4)) };
  const border = bordered ? 'border-[3px] border-white' : '';
  if (src) {
    return <img src={src} alt="" style={style} className={cx('flex-shrink-0 object-cover', border, className)} />;
  }
  return (
    <span
      aria-hidden="true"
      style={style}
      className={cx('inline-flex flex-shrink-0 items-center justify-center font-bold', toneFor(seed || title), border, className)}
    >
      {(title.trim()[0] || '?').toUpperCase()}
    </span>
  );
};

// Overlapping row of member avatars with a "+N" pill.
export const Facepile: React.FC<{ names: string[]; total?: number; size?: number; className?: string }> = ({
  names, total, size = 28, className,
}) => {
  const extra = total !== undefined ? total - names.length : 0;
  return (
    <div className={cx('flex items-center', className)}>
      {names.map((name, i) => (
        <Avatar key={`${name}-${i}`} name={name} size={size} ring className={i === 0 ? '' : '-ml-2'} />
      ))}
      {extra > 0 && (
        <span className="-ml-2 inline-flex h-7 items-center rounded-full bg-slate-100 px-2 text-micro font-semibold text-slate-600 ring-2 ring-white tabular">
          +{formatCompact(extra)}
        </span>
      )}
    </div>
  );
};

/** 2340 -> "2.340"; 12450 -> "12,4K" (Vietnamese number style). */
export function formatCompact(n: number): string {
  if (n >= 1000000) return `${(n / 1000000).toFixed(1).replace('.', ',').replace(',0', '')}M`;
  if (n >= 10000) return `${(n / 1000).toFixed(1).replace('.', ',').replace(',0', '')}K`;
  return n.toLocaleString('vi-VN');
}

// ---------------------------------------------------------------------------------------------------------------
// Cover / tile. When there is no on-topic image the design uses a colored tile with a stroke icon instead.

export const CoverImage: React.FC<{
  src?: string | null;
  seed: string;
  alt?: string;
  className?: string;
  icon?: React.ReactNode;
}> = ({ src, seed, alt = '', className, icon }) => {
  const [failed, setFailed] = React.useState(false);
  if (src && !failed) {
    return <img src={src} alt={alt} onError={() => setFailed(true)} className={cx('h-full w-full object-cover', className)} />;
  }
  return (
    <div aria-hidden="true" className={cx('flex h-full w-full items-center justify-center', toneFor(seed), className)}>
      {icon}
    </div>
  );
};

// ---------------------------------------------------------------------------------------------------------------
// Progress, toggle.

export const ProgressBar: React.FC<{ value: number; max?: number; className?: string; onDark?: boolean; label?: string }> = ({
  value, max = 100, className, onDark = false, label,
}) => {
  const pct = max > 0 ? Math.max(0, Math.min(100, (value / max) * 100)) : 0;
  return (
    <div
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={value}
      aria-label={label}
      className={cx('h-1.5 w-full overflow-hidden rounded-full', onDark ? 'bg-white/20' : 'bg-slate-100', className)}
    >
      <div className="h-full rounded-full bg-blue-600 transition-[width] duration-state" style={{ width: `${pct}%` }} />
    </div>
  );
};

export const Toggle: React.FC<{
  checked: boolean;
  onChange: (next: boolean) => void;
  label: string;
  disabled?: boolean;
  id?: string;
}> = ({ checked, onChange, label, disabled, id }) => (
  <button
    id={id}
    type="button"
    role="switch"
    aria-checked={checked}
    aria-label={label}
    disabled={disabled}
    onClick={() => onChange(!checked)}
    className={cx(
      'relative inline-flex h-6 w-10 flex-shrink-0 items-center rounded-full transition-colors duration-state disabled:opacity-50',
      checked ? 'bg-blue-600' : 'bg-slate-200',
    )}
  >
    <span className={cx('inline-block h-5 w-5 rounded-full bg-white shadow-hairline transition-transform duration-state', checked ? 'translate-x-[18px]' : 'translate-x-0.5')} />
  </button>
);

// ---------------------------------------------------------------------------------------------------------------
// Form controls: 44px high, radius 12, hairline border, blue focus ring.

export const inputClass = (extra?: string) =>
  cx(
    'block w-full rounded-input border border-slate-200 bg-white px-3.5 text-ui text-slate-900 transition-colors duration-micro',
    'placeholder:text-slate-400 hover:border-slate-300 focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-600/20',
    'disabled:bg-slate-100 disabled:text-slate-400',
    extra,
  );

export const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(({ className, ...rest }, ref) => (
  <input ref={ref} className={inputClass(cx('h-11', className))} {...rest} />
));
Input.displayName = 'Input';

export const Textarea = React.forwardRef<HTMLTextAreaElement, React.TextareaHTMLAttributes<HTMLTextAreaElement>>(({ className, ...rest }, ref) => (
  <textarea ref={ref} className={inputClass(cx('py-2.5 leading-[22px]', className))} {...rest} />
));
Textarea.displayName = 'Textarea';

export const Select = React.forwardRef<HTMLSelectElement, React.SelectHTMLAttributes<HTMLSelectElement>>(({ className, children, ...rest }, ref) => (
  <select ref={ref} className={inputClass(cx('h-11 pr-8', className))} {...rest}>
    {children}
  </select>
));
Select.displayName = 'Select';

export const Field: React.FC<{
  label: React.ReactNode;
  htmlFor?: string;
  hint?: React.ReactNode;
  error?: React.ReactNode;
  className?: string;
  children: React.ReactNode;
}> = ({ label, htmlFor, hint, error, className, children }) => (
  <div className={cx('space-y-1.5', className)}>
    <label htmlFor={htmlFor} className="block text-meta font-semibold text-slate-900">{label}</label>
    {children}
    {error ? <p className="text-caption text-red-600">{error}</p> : hint ? <p className="text-caption text-slate-500">{hint}</p> : null}
  </div>
);

// Underline tab strip inside a page (e.g. "Thành viên tích cực | Top"), aria-pressed buttons.
export const SegmentTabs: React.FC<{
  items: { key: string; label: React.ReactNode }[];
  value: string;
  onChange: (key: string) => void;
  className?: string;
  ariaLabel?: string;
}> = ({ items, value, onChange, className, ariaLabel }) => (
  <div role="group" aria-label={ariaLabel} className={cx('inline-flex rounded-btn bg-slate-100 p-1', className)}>
    {items.map((item) => (
      <button
        key={item.key}
        type="button"
        aria-pressed={item.key === value}
        onClick={() => onChange(item.key)}
        className={cx(
          'h-8 rounded-[9px] px-3 text-meta font-semibold transition-colors duration-micro',
          item.key === value ? 'bg-white text-slate-900 shadow-hairline' : 'text-slate-600 hover:text-slate-900',
        )}
      >
        {item.label}
      </button>
    ))}
  </div>
);

// Filter chips: selected = dark pill, others white with a hairline border.
export const FilterChip: React.FC<React.ButtonHTMLAttributes<HTMLButtonElement> & { selected?: boolean }> = ({
  selected = false, className, type = 'button', ...rest
}) => (
  <button
    type={type}
    aria-pressed={selected}
    className={cx(
      'inline-flex h-9 flex-shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full px-3.5 text-meta font-semibold transition-colors duration-micro',
      selected ? 'bg-slate-900 text-white' : 'border border-slate-200 bg-white text-slate-600 hover:bg-slate-100 hover:text-slate-900',
      className,
    )}
    {...rest}
  />
);

// Event-card calendar column ("Th 5" / "09").
export const DateBlock: React.FC<{ date: Date; className?: string }> = ({ date, className }) => {
  const weekday = ['CN', 'Th 2', 'Th 3', 'Th 4', 'Th 5', 'Th 6', 'Th 7'][date.getDay()];
  return (
    <div className={cx('flex h-14 w-[52px] flex-shrink-0 flex-col items-center justify-center rounded-community bg-slate-100', className)}>
      <span className="text-micro font-semibold uppercase text-slate-600">{weekday}</span>
      <span className="text-h2-sm font-semibold leading-6 text-slate-900 tabular">{String(date.getDate()).padStart(2, '0')}</span>
    </div>
  );
};
