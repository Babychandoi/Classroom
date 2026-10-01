// D-19: small display helpers for class-access prices, durations and dates (Vietnamese, dd/MM/yyyy).

const pad = (n: number) => String(n).padStart(2, '0');

/** 199000 -> "199.000đ" (whole dong, vi-VN grouping). */
export function formatDong(amount: number): string {
  return `${new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 0 }).format(Math.round(amount))}đ`;
}

/** ISO instant -> "dd/MM/yyyy" in the browser's time zone ('' when absent or invalid). */
export function formatDate(instant?: string | null): string {
  if (!instant) return '';
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) return '';
  return `${pad(date.getDate())}/${pad(date.getMonth() + 1)}/${date.getFullYear()}`;
}

/** ISO instant -> "dd/MM/yyyy HH:mm". */
export function formatDateTime(instant?: string | null): string {
  if (!instant) return '';
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) return '';
  return `${formatDate(instant)} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** null / 0 / lifetime -> "trọn đời", otherwise "30 ngày". */
export function durationLabel(durationDays?: number | null, lifetime?: boolean | null): string {
  if (lifetime || durationDays == null || durationDays <= 0) return 'trọn đời';
  return `${durationDays} ngày`;
}

/** "199.000đ / 30 ngày" or "199.000đ / trọn đời". */
export function accessPriceLabel(price: number, durationDays?: number | null, lifetime?: boolean | null): string {
  return `${formatDong(price)} / ${durationLabel(durationDays, lifetime)}`;
}

const DAY_MS = 24 * 60 * 60 * 1000;

/** Whole days from `now` until `instant` (rounded up; <= 0 once it has passed). NaN for an absent / invalid instant. */
export function daysUntil(instant?: string | null, now: number = Date.now()): number {
  if (!instant) return Number.NaN;
  const t = new Date(instant).getTime();
  if (Number.isNaN(t)) return Number.NaN;
  return Math.ceil((t - now) / DAY_MS);
}
