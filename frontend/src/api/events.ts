import { api, ApiException } from './client';
import type { ClassEvent, Classroom, EventFormat, EventRegistrant } from '../types';

// Event endpoints + display helpers (docs/API-BLOG-EVENTS.md "Events"). Times are shown in the browser's time zone,
// 24h clock, dd/mm dates - the same convention as api/format.ts.

export type EventScope = 'upcoming' | 'past' | 'all';

export const listClassEvents = (classId: string, scope: EventScope) =>
  api.get<ClassEvent[]>(`/classes/${classId}/events?scope=${scope}`);
export const getEvent = (eventId: string) => api.get<ClassEvent>(`/events/${eventId}`);
export const registerForEvent = (eventId: string) => api.post<ClassEvent>(`/events/${eventId}/registrations`);
/** Usually the updated event; only `{ id, isRegistered: false }` when the class is no longer visible to the caller. */
export const cancelEventRegistration = (eventId: string) =>
  api.delete<ClassEvent | Pick<ClassEvent, 'id' | 'isRegistered'>>(`/events/${eventId}/registrations/me`);
export const listEventRegistrants = (eventId: string) => api.get<EventRegistrant[]>(`/events/${eventId}/registrations`);

export interface EventInput {
  title: string;
  description?: string | null;
  forWhom?: string | null;
  takeaways?: string[];
  format: EventFormat;
  location?: string | null;
  meetingUrl?: string | null;
  startsAt: string;
  endsAt: string;
  capacity?: number | null;
  hostUserId?: string | null;
  coverMediaId?: string | null;
  audience: 'PUBLIC' | 'MEMBERS';
}

export const createEvent = (classId: string, body: EventInput) => api.post<ClassEvent>(`/classes/${classId}/events`, body);
export const updateEvent = (eventId: string, body: Partial<EventInput>) => api.put<ClassEvent>(`/events/${eventId}`, body);
export const cancelEvent = (eventId: string) => api.post<ClassEvent>(`/events/${eventId}/cancel`);
export const deleteEvent = (eventId: string) => api.delete<unknown>(`/events/${eventId}`);

// ---------------------------------------------------------------------------------------------------------------
// Formatting

const pad = (n: number) => String(n).padStart(2, '0');
const WEEKDAYS = ['Chủ Nhật', 'Thứ Hai', 'Thứ Ba', 'Thứ Tư', 'Thứ Năm', 'Thứ Sáu', 'Thứ Bảy'];

const sameDay = (a: Date, b: Date) =>
  a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();

/** "20:00" */
export const formatClock = (d: Date) => `${pad(d.getHours())}:${pad(d.getMinutes())}`;

/** "Thứ Năm, 16/07" (+ "/2026" with `withYear`). */
export function formatEventDay(instant: string, withYear = false): string {
  const d = new Date(instant);
  if (Number.isNaN(d.getTime())) return '';
  return `${WEEKDAYS[d.getDay()]}, ${pad(d.getDate())}/${pad(d.getMonth() + 1)}${withYear ? `/${d.getFullYear()}` : ''}`;
}

/** "20:00–21:30", or "08:30 – Chủ Nhật, 20/07 17:00" when the event runs past midnight. */
export function formatEventTimeRange(startsAt: string, endsAt: string): string {
  const s = new Date(startsAt);
  const e = new Date(endsAt);
  if (Number.isNaN(s.getTime()) || Number.isNaN(e.getTime())) return '';
  if (sameDay(s, e)) return `${formatClock(s)}–${formatClock(e)}`;
  return `${formatClock(s)} – ${formatEventDay(endsAt)} ${formatClock(e)}`;
}

/** "Thứ Năm, 16/07 · 20:00–21:30" */
export function formatEventWhen(event: Pick<ClassEvent, 'startsAt' | 'endsAt'>, withYear = false): string {
  return `${formatEventDay(event.startsAt, withYear)} · ${formatEventTimeRange(event.startsAt, event.endsAt)}`;
}

/** "Online · Zoom" / "Trực tiếp · 12 Lý Thường Kiệt" / "Online". */
export function formatEventPlace(event: Pick<ClassEvent, 'format' | 'location'>): string {
  const kind = event.format === 'ONLINE' ? 'Online' : 'Trực tiếp';
  const where = (event.location || '').trim();
  return where ? `${kind} · ${where}` : kind;
}

/** "74 đã đăng ký" / "74/120 đã đăng ký". */
export function formatRegistered(event: Pick<ClassEvent, 'registeredCount' | 'capacity'>): string {
  const count = event.registeredCount.toLocaleString('vi-VN');
  return event.capacity ? `${count}/${event.capacity.toLocaleString('vi-VN')} đã đăng ký` : `${count} đã đăng ký`;
}

export const hasEnded = (event: Pick<ClassEvent, 'endsAt'>, now: number = Date.now()) => new Date(event.endsAt).getTime() < now;

// ---------------------------------------------------------------------------------------------------------------
// Who may register

export type RegistrationGate =
  | 'cancelled'   // the event was cancelled
  | 'ended'       // it is over
  | 'registered'  // already in
  | 'full'        // no seats left
  | 'guest'       // must sign in first
  | 'join'        // must become an (active) member first
  | 'open';       // may register right now

const isActiveMember = (classroom: Classroom) =>
  !!classroom.isMember || !!classroom.isOwner || classroom.userRole === 'OWNER' || classroom.userRole === 'STAFF';

/**
 * Mirrors the server rule of POST /events/{id}/registrations: a MEMBERS event, and any event of a PAID or PRIVATE class, needs an
 * active member; a PUBLIC event of a PUBLIC FREE class is open to any signed-in person who can see the class.
 */
export function registrationGate(event: ClassEvent, classroom: Classroom, signedIn: boolean, now: number = Date.now()): RegistrationGate {
  if (event.status === 'CANCELLED') return 'cancelled';
  if (hasEnded(event, now)) return 'ended';
  if (event.isRegistered) return 'registered';
  if (event.isFull) return 'full';
  if (!signedIn) return 'guest';
  const needsMembership = event.audience === 'MEMBERS' || classroom.accessType === 'PAID' || classroom.visibility === 'PRIVATE';
  if (needsMembership && !isActiveMember(classroom)) return 'join';
  return 'open';
}

/** A sentence a person can act on for a failed register call (409 full / cancelled / ended, 403 not a member). */
export function registrationErrorMessage(err: unknown): string {
  if (err instanceof ApiException) {
    const text = (err.message || '').toLowerCase();
    if (err.status === 409) {
      if (text.includes('đủ chỗ') || text.includes('full')) return 'Sự kiện đã đủ chỗ. Bạn có thể theo dõi các buổi tiếp theo của lớp học.';
      if (text.includes('hủy') || text.includes('cancel')) return 'Sự kiện này đã bị hủy nên không nhận đăng ký nữa.';
      if (text.includes('kết thúc') || text.includes('ended') || text.includes('diễn ra')) return 'Sự kiện đã kết thúc nên không nhận đăng ký nữa.';
      return err.message || 'Không thể đăng ký sự kiện lúc này.';
    }
    if (err.status === 403) return err.message || 'Sự kiện này chỉ dành cho thành viên lớp học. Hãy tham gia lớp để đăng ký.';
    if (err.status === 401) return 'Bạn cần đăng nhập để đăng ký sự kiện.';
    return err.message || 'Không thể đăng ký sự kiện lúc này. Vui lòng thử lại.';
  }
  return err instanceof Error && err.message ? err.message : 'Không thể đăng ký sự kiện lúc này. Vui lòng thử lại.';
}
