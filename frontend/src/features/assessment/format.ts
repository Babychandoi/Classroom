/** Points with a Vietnamese decimal comma and at most 2 decimals: 1.5 → "1,5", 10 → "10". */
export function fmtPts(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return '—';
  return (Math.round(value * 100) / 100).toString().replace('.', ',');
}

/** Rounds to 2 decimals (HALF_UP for positives), the same as the backend's BigDecimal scale 2. */
export function round2(value: number): number {
  return Math.round((value + Number.EPSILON) * 100) / 100;
}

/** Sums points without float drift (works in hundredths). */
export function sumPoints(values: number[]): number {
  let cents = 0;
  for (const v of values) cents += Math.round((Number(v) || 0) * 100);
  return cents / 100;
}

export function mmss(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

/** "HH:mm" of an instant in local time. */
export function hhmm(instant: string | number | Date | null | undefined): string {
  if (instant == null) return '';
  const d = new Date(instant);
  if (Number.isNaN(d.getTime())) return '';
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/** "20:00 · 17/10" */
export function shortDateTime(instant: string | null | undefined): string {
  if (!instant) return '';
  const d = new Date(instant);
  if (Number.isNaN(d.getTime())) return '';
  return `${hhmm(d)} · ${String(d.getDate()).padStart(2, '0')}/${String(d.getMonth() + 1).padStart(2, '0')}`;
}

/** Value for <input type="datetime-local"> from an ISO instant (local time), or ''. */
export function toLocalInput(instant: string | null | undefined): string {
  if (!instant) return '';
  const d = new Date(instant);
  if (Number.isNaN(d.getTime())) return '';
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** ISO instant from a datetime-local value, or null. */
export function fromLocalInput(value: string): string | null {
  if (!value) return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

export function wordCount(text: string | null | undefined): number {
  const t = String(text ?? '').trim();
  return t ? t.split(/\s+/).length : 0;
}

export const ABC = 'ABCDEFGHIJ';
export const abcd = 'abcdefghij';

export const cx = (...parts: Array<string | false | null | undefined>) => parts.filter(Boolean).join(' ');
