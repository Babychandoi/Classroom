// R16-04: <input type="datetime-local"> holds a *wall-clock* string in the browser's own time zone
// ("2026-10-01T15:00"), while the API speaks UTC instants ("2026-10-01T08:00:00Z"). The two
// conversions must therefore be exact inverses of each other:
//
//   API instant --toDatetimeLocalValue--> input value --fromDatetimeLocalValue--> API instant
//
// The edit form used `new Date(instant).toISOString().slice(0, 16)` to pre-fill (a UTC wall clock)
// but `new Date(inputValue).toISOString()` to save (parsed as LOCAL time), so every untouched save
// moved the exam schedule by the browser's UTC offset (7 hours in Vietnam).

const pad = (n: number): string => String(n).padStart(2, '0');

/** UTC instant from the API -> `YYYY-MM-DDTHH:mm` in the browser's local time zone ('' if absent/invalid). */
export function toDatetimeLocalValue(instant?: string | null): string {
  if (!instant) return '';
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) return '';
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

/** `datetime-local` value (browser-local wall clock) -> UTC ISO instant for the API (null when empty/invalid). */
export function fromDatetimeLocalValue(value?: string | null): string | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}
