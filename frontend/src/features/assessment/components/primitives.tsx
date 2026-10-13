import React, { useRef, useState } from 'react';
import { BookOpen, Check, FileText, Headphones, Mic, PenLine, Plus, X, Zap, type LucideIcon } from 'lucide-react';
import { typeMeta, MODE_LABELS, SKILL_LABELS, type Skill } from '../catalog';
import type { GradingMode } from '../types';
import { cx } from '../format';

// Small building blocks of the Question Builder (prototype 14-qb-primitives.jsx), Tailwind stock palette only.

export type TagColor = 'gray' | 'green' | 'amber' | 'blue' | 'violet' | 'red' | 'ink';
const TAG: Record<TagColor, { box: string; dot: string }> = {
  gray: { box: 'bg-slate-100 text-slate-600', dot: 'bg-slate-500' },
  green: { box: 'bg-green-50 text-green-700', dot: 'bg-green-600' },
  amber: { box: 'bg-amber-50 text-amber-700', dot: 'bg-amber-500' },
  blue: { box: 'bg-blue-50 text-blue-700', dot: 'bg-blue-600' },
  violet: { box: 'bg-violet-50 text-violet-700', dot: 'bg-violet-600' },
  red: { box: 'bg-red-50 text-red-700', dot: 'bg-red-600' },
  ink: { box: 'bg-slate-900 text-white', dot: 'bg-white' },
};

export const Tag: React.FC<{ label: React.ReactNode; color?: TagColor; dot?: boolean; square?: boolean; className?: string }> = ({ label, color = 'gray', dot, square, className }) => (
  <span className={cx('inline-flex h-6 flex-none items-center gap-1.5 whitespace-nowrap px-2.5 text-caption font-semibold', square ? 'rounded-md' : 'rounded-full', TAG[color].box, className)}>
    {dot && <span aria-hidden="true" className={cx('h-1.5 w-1.5 rounded-full', TAG[color].dot)} />}
    {label}
  </span>
);

export const TypeChip: React.FC<{ code: string; full?: boolean; className?: string }> = ({ code, full, className }) => {
  const t = typeMeta(code);
  return (
    <span
      title={t.name}
      className={cx('inline-flex h-[22px] flex-none items-center gap-1.5 whitespace-nowrap rounded-md px-2 text-caption font-semibold',
        t.container ? 'bg-slate-900 text-white' : 'bg-slate-100 text-slate-600', className)}
    >
      <span className="font-mono text-micro">{code}</span>
      {full && <span>{t.name}</span>}
    </span>
  );
};

const MODE_COLOR: Record<GradingMode, TagColor> = { AUTO: 'green', MANUAL: 'amber', HYBRID: 'blue', PASS_FAIL: 'violet' };
export const ModeTag: React.FC<{ mode: GradingMode | null | undefined }> = ({ mode }) => (mode ? <Tag label={MODE_LABELS[mode]} color={MODE_COLOR[mode]} /> : null);

/** Segmented control with radiogroup semantics (arrow keys move the choice). */
export function Seg<T extends string | number | boolean | null>({ items, value, onChange, size = 'md', label, disabled, className }: {
  items: [T, React.ReactNode][];
  value: T | undefined;
  onChange?: (v: T) => void;
  size?: 'sm' | 'md';
  label: string;
  disabled?: boolean;
  className?: string;
}) {
  const refs = useRef<Array<HTMLButtonElement | null>>([]);
  const idx = items.findIndex(([k]) => k === value);
  const onKey = (e: React.KeyboardEvent, i: number) => {
    if (!onChange || disabled) return;
    let j = -1;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') j = (i + 1) % items.length;
    if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') j = (i - 1 + items.length) % items.length;
    if (j < 0) return;
    e.preventDefault();
    onChange(items[j][0]);
    refs.current[j]?.focus();
  };
  return (
    <div role="radiogroup" aria-label={label} aria-disabled={disabled || undefined} className={cx('inline-flex flex-wrap gap-0.5 rounded-full bg-slate-100 p-[3px]', className)}>
      {items.map(([k, l], i) => {
        const on = k === value;
        return (
          <button
            key={String(k)}
            ref={(el) => { refs.current[i] = el; }}
            type="button"
            role="radio"
            aria-checked={on}
            tabIndex={on || (idx < 0 && i === 0) ? 0 : -1}
            disabled={disabled}
            onClick={() => onChange?.(k)}
            onKeyDown={(e) => onKey(e, i)}
            className={cx('whitespace-nowrap rounded-full px-3 transition-colors disabled:cursor-not-allowed',
              size === 'sm' ? 'h-6 text-caption' : 'h-[30px] text-meta',
              on ? 'bg-white font-semibold text-slate-900 shadow-sm' : 'font-medium text-slate-600 hover:text-slate-900')}
          >
            {l}
          </button>
        );
      })}
    </div>
  );
}

export const SetRow: React.FC<{ label: React.ReactNode; sub?: React.ReactNode; children?: React.ReactNode; top?: boolean; htmlFor?: string }> = ({ label, sub, children, top, htmlFor }) => (
  <div className={cx('flex gap-3 border-t border-slate-100 py-3', top ? 'items-start' : 'items-center')}>
    <div className="min-w-0 flex-1">
      {htmlFor ? <label htmlFor={htmlFor} className="block text-meta font-semibold text-slate-900">{label}</label> : <div className="text-meta font-semibold text-slate-900">{label}</div>}
      {sub && <div className="mt-0.5 text-caption text-slate-500">{sub}</div>}
    </div>
    <div className="flex-none">{children}</div>
  </div>
);

/** Number input accepting "1,5" or "1.5"; calls onChange with a number (NaN when not a number). */
export const NumBox: React.FC<{ value: number | null | undefined; onChange: (v: number) => void; suffix?: string; width?: number; label: string; id?: string; step?: string; min?: number }> = ({ value, onChange, suffix, width = 72, label, id, min }) => {
  const [text, setText] = useState<string | null>(null);
  const shown = text ?? (value == null || Number.isNaN(value) ? '' : String(value).replace('.', ','));
  return (
    <span className="inline-flex items-center gap-1.5">
      <input
        id={id}
        aria-label={label}
        inputMode="decimal"
        value={shown}
        onChange={(e) => {
          const raw = e.target.value;
          setText(raw);
          const n = raw.trim() === '' ? NaN : Number(raw.replace(',', '.'));
          onChange(min != null && Number.isFinite(n) && n < min ? min : n);
        }}
        onBlur={() => setText(null)}
        style={{ width }}
        className="h-[34px] rounded-[10px] border border-slate-200 px-2.5 text-right text-ui font-semibold tabular-nums text-slate-900 focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-600/20"
      />
      {suffix && <span className="text-caption font-medium text-slate-500">{suffix}</span>}
    </span>
  );
};

/** Chip list editor ("accepted answers"); Enter or blur adds. */
export const Chips: React.FC<{ values: string[]; onChange: (v: string[]) => void; placeholder?: string; label: string }> = ({ values, onChange, placeholder, label }) => {
  const [t, setT] = useState('');
  const add = () => {
    const v = t.trim();
    if (v && !values.includes(v)) onChange([...values, v]);
    setT('');
  };
  return (
    <div className="flex min-h-[40px] flex-wrap items-center gap-1.5 rounded-xl border border-slate-200 p-1.5">
      {values.map((v, i) => (
        <span key={`${v}-${i}`} className="inline-flex h-7 items-center gap-1 rounded-full bg-green-50 pl-2.5 pr-1 text-meta font-semibold text-green-700">
          {v}
          <button type="button" aria-label={`Bỏ "${v}"`} onClick={() => onChange(values.filter((_, j) => j !== i))} className="rounded-full p-0.5 hover:bg-green-100">
            <X className="h-3 w-3" strokeWidth={2.4} aria-hidden="true" />
          </button>
        </span>
      ))}
      <input
        aria-label={label}
        value={t}
        onChange={(e) => setT(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }}
        onBlur={add}
        placeholder={values.length ? 'Thêm đáp án chấp nhận…' : placeholder}
        className="h-7 min-w-[140px] flex-1 border-0 bg-transparent text-ui outline-none"
      />
    </div>
  );
};

export const RowAdd: React.FC<{ children: React.ReactNode; onClick: () => void; disabled?: boolean }> = ({ children, onClick, disabled }) => (
  <button type="button" onClick={onClick} disabled={disabled}
    className="inline-flex h-[34px] items-center gap-1.5 self-start rounded-full border border-dashed border-slate-300 bg-white px-3.5 text-meta font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50">
    <Plus className="h-3.5 w-3.5" strokeWidth={2.2} aria-hidden="true" />{children}
  </button>
);

/** Round / square "this is correct" toggle used by the answer editors. */
export const Mark: React.FC<{ on: boolean; square?: boolean; onClick: () => void; label: string }> = ({ on, square, onClick, label }) => (
  <button type="button" role={square ? 'checkbox' : 'radio'} aria-checked={on} aria-label={label} onClick={onClick}
    className={cx('flex h-7 w-7 flex-none items-center justify-center text-white', square ? 'rounded-lg' : 'rounded-full',
      on ? 'bg-green-600' : 'border-[1.5px] border-slate-300 bg-white hover:border-slate-400')}>
    {on && <Check className="h-3.5 w-3.5" strokeWidth={2.8} aria-hidden="true" />}
  </button>
);

export const Notice: React.FC<{ tone?: 'amber' | 'red' | 'blue' | 'gray'; children: React.ReactNode; action?: React.ReactNode; role?: 'alert' | 'status' }> = ({ tone = 'gray', children, action, role }) => {
  const c = { amber: 'border-amber-200 bg-amber-50 text-amber-800', red: 'border-red-200 bg-red-50 text-red-700', blue: 'border-blue-200 bg-blue-50 text-blue-800', gray: 'border-slate-200 bg-slate-50 text-slate-700' }[tone];
  return (
    <div role={role} className={cx('flex flex-wrap items-center gap-3 rounded-xl border px-3.5 py-2.5 text-meta', c)}>
      <div className="min-w-0 flex-1">{children}</div>
      {action}
    </div>
  );
};

const SKILL_STYLE: Record<Skill, { box: string; Icon: LucideIcon }> = {
  gv: { box: 'bg-violet-50 text-violet-700', Icon: BookOpen },
  listening: { box: 'bg-amber-50 text-amber-700', Icon: Headphones },
  reading: { box: 'bg-blue-50 text-blue-700', Icon: FileText },
  speaking: { box: 'bg-green-50 text-green-700', Icon: Mic },
  writing: { box: 'bg-pink-50 text-pink-700', Icon: PenLine },
  focus: { box: 'bg-slate-100 text-slate-900', Icon: Zap },
};

export const SkillTile: React.FC<{ skill: string; size?: number }> = ({ skill, size = 32 }) => {
  const s = SKILL_STYLE[(skill as Skill) in SKILL_STYLE ? (skill as Skill) : 'focus'];
  return (
    <span title={SKILL_LABELS[(skill as Skill)] ?? skill} style={{ width: size, height: size }} className={cx('flex flex-none items-center justify-center rounded-lg', s.box)}>
      <s.Icon className="h-1/2 w-1/2" strokeWidth={1.9} />
    </span>
  );
};

export const SkillStack: React.FC<{ skills: string[]; size?: number }> = ({ skills, size = 30 }) => (
  <div className="flex" aria-label={`Kỹ năng: ${skills.map((s) => SKILL_LABELS[s as Skill] ?? s).join(', ')}`}>
    {(skills.length ? skills : ['gv']).slice(0, 4).map((s, i) => (
      <span key={s} className={cx('flex rounded-[10px] border-2 border-white', i > 0 && '-ml-2')}><SkillTile skill={s} size={size} /></span>
    ))}
  </div>
);

/** Small label above a control. */
export const Lbl: React.FC<{ children: React.ReactNode; htmlFor?: string; id?: string }> = ({ children, htmlFor, id }) =>
  htmlFor ? <label id={id} htmlFor={htmlFor} className="mb-1.5 block text-caption font-semibold text-slate-600">{children}</label>
    : <div id={id} className="mb-1.5 text-caption font-semibold text-slate-600">{children}</div>;

export const Hint: React.FC<{ children: React.ReactNode }> = ({ children }) => <p className="mt-1.5 text-caption text-slate-500">{children}</p>;

/** Score ring (SVG), value/max. */
export const Ring: React.FC<{ value: number; max: number; size?: number; label: React.ReactNode; sub?: React.ReactNode }> = ({ value, max, size = 132, label, sub }) => {
  const pct = max > 0 ? Math.max(0, Math.min(1, value / max)) : 0;
  const r = (size - 14) / 2;
  const c = 2 * Math.PI * r;
  return (
    <div className="relative flex-none" style={{ width: size, height: size }}>
      <svg width={size} height={size} aria-hidden="true">
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="#EEF2F7" strokeWidth={12} />
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="#B45309" strokeWidth={12} strokeLinecap="round"
          strokeDasharray={`${c * pct} ${c}`} transform={`rotate(-90 ${size / 2} ${size / 2})`} />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center text-center">
        <span className="text-h2 font-extrabold text-slate-900">{label}</span>
        {sub && <span className="text-caption text-slate-500">{sub}</span>}
      </div>
    </div>
  );
};
