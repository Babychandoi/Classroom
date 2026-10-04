import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { CalendarCheck, Check, MapPin, Video, X } from 'lucide-react';
import {
  cancelEventRegistration,
  formatEventPlace,
  formatEventWhen,
  formatRegistered,
  registerForEvent,
  registrationErrorMessage,
  type RegistrationGate,
} from '../api/events';
import { Modal } from './Modal';
import { Avatar, Badge, DateBlock, buttonClass, toneFor } from './ui';
import type { ClassEvent } from '../types';

// Pieces shared by the Events tab and the event page (design: "Cộng đồng - Sự kiện", "Cộng đồng - Chi tiết sự kiện",
// "Popup - Đăng ký sự kiện lặp lại" reduced to a single-occurrence confirmation). No countdowns anywhere (design rule).

/** Cover image, or a topic tile with a stroke icon (video for online, map pin for in-person). */

/** A full event payload (the cancel endpoint may answer with only `{ id, isRegistered }`). */
const isFullEvent = (value: unknown): value is ClassEvent =>
  !!value && typeof value === 'object' && typeof (value as ClassEvent).title === 'string';

export const EventTile: React.FC<{ event: ClassEvent; className?: string; iconSize?: number; label?: boolean }> = ({
  event, className, iconSize = 22, label = true,
}) => {
  const [failed, setFailed] = useState(false);
  if (event.coverUrl && !failed) {
    return <img src={event.coverUrl} alt="" onError={() => setFailed(true)} className={`h-full w-full object-cover ${className ?? ''}`} />;
  }
  const Icon = event.format === 'ONLINE' ? Video : MapPin;
  return (
    <div aria-hidden="true" className={`flex h-full w-full flex-col items-center justify-center gap-2.5 ${toneFor(event.id)} ${className ?? ''}`}>
      <span className="inline-flex items-center justify-center rounded-full bg-white/60" style={{ width: iconSize * 2.4, height: iconSize * 2.4 }}>
        <Icon style={{ width: iconSize, height: iconSize }} strokeWidth={1.6} />
      </span>
      {label && <span className="text-meta font-semibold uppercase tracking-[0.4px]">{event.format === 'ONLINE' ? 'Sự kiện trực tuyến' : 'Sự kiện trực tiếp'}</span>}
    </div>
  );
};

/** White pill laid over a cover ("Online · Zoom"). */
export const OverlayChip: React.FC<{ children: React.ReactNode; className?: string }> = ({ children, className }) => (
  <span className={`inline-flex h-6 max-w-full items-center truncate rounded-full bg-white/[0.92] px-2.5 text-caption font-semibold text-slate-900 ${className ?? ''}`}>
    {children}
  </span>
);

export const RegisteredBadge: React.FC = () => (
  <Badge tone="success" size="sm"><span aria-hidden="true" className="h-[5px] w-[5px] rounded-full bg-green-600" />Bạn đã đăng ký</Badge>
);

export const CancelledBadge: React.FC = () => <Badge tone="danger" size="sm">Đã hủy</Badge>;

const Tick: React.FC = () => <Check className="mt-[3px] h-[15px] w-[15px] flex-shrink-0 text-green-600" strokeWidth={2.2} aria-hidden="true" />;

/** "Dành cho ai / mang về gì" with green ticks. `limit` caps the takeaways on a card. */
export const EventValueList: React.FC<{ event: ClassEvent; limit?: number; className?: string }> = ({ event, limit, className }) => {
  const takeaways = (event.takeaways || []).filter(Boolean);
  const shown = limit ? takeaways.slice(0, limit) : takeaways;
  if (!event.forWhom && shown.length === 0) return null;
  return (
    <ul className={`space-y-1.5 text-ui text-slate-600 ${className ?? ''}`}>
      {event.forWhom && (
        <li className="flex gap-2"><Tick /><span><span className="font-medium text-slate-900">Dành cho: </span>{event.forWhom}</span></li>
      )}
      {shown.map((item, i) => (
        <li key={i} className="flex gap-2"><Tick /><span>{item}</span></li>
      ))}
    </ul>
  );
};

export const HostLine: React.FC<{ event: ClassEvent; size?: number }> = ({ event, size = 22 }) => (
  <div className="flex min-w-0 items-center gap-2">
    <Avatar name={event.host.fullName} src={event.host.avatarUrl} size={size} />
    <span className="truncate text-meta text-slate-600">Chủ trì: <strong className="font-semibold text-slate-900">{event.host.fullName}</strong></span>
  </div>
);

// ---------------------------------------------------------------------------------------------------------------
// Registration flow: confirm in a dialog, then call the API; 409 (full / cancelled / ended) becomes a friendly message.

export interface EventRegistration {
  pending: { event: ClassEvent; mode: 'register' | 'cancel' } | null;
  busy: boolean;
  error: string | null;
  errorFor: string | null;
  ask: (event: ClassEvent, mode: 'register' | 'cancel') => void;
  close: () => void;
  confirm: () => Promise<void>;
}

export function useEventRegistration(onUpdated: (event: ClassEvent) => void, onConflict?: (event: ClassEvent) => void): EventRegistration {
  const [pending, setPending] = useState<EventRegistration['pending']>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [errorFor, setErrorFor] = useState<string | null>(null);

  const confirm = async () => {
    if (!pending) return;
    const { event, mode } = pending;
    setBusy(true);
    setError(null);
    setErrorFor(null);
    try {
      const updated = mode === 'register' ? await registerForEvent(event.id) : await cancelEventRegistration(event.id);
      setPending(null);
      if (isFullEvent(updated)) {
        onUpdated(updated);
      } else if (mode === 'cancel') {
        // The seat was freed but the class is no longer visible to the caller, so the server only answers
        // { id, isRegistered: false } (docs/API.md section 11): update the card we already have.
        onUpdated({
          ...event,
          isRegistered: false,
          meetingUrl: null,
          registeredCount: Math.max(0, event.registeredCount - 1),
          isFull: false,
        });
      }
    } catch (err) {
      setPending(null);
      setError(mode === 'register' ? registrationErrorMessage(err) : err instanceof Error && err.message ? err.message : 'Không thể hủy đăng ký. Vui lòng thử lại.');
      setErrorFor(event.id);
      onConflict?.(event);
    } finally {
      setBusy(false);
    }
  };

  return {
    pending,
    busy,
    error,
    errorFor,
    ask: (event, mode) => { setError(null); setErrorFor(null); setPending({ event, mode }); },
    close: () => { if (!busy) setPending(null); },
    confirm,
  };
}

/** The confirmation dialog of `useEventRegistration`. */
export const RegistrationDialog: React.FC<{ registration: EventRegistration }> = ({ registration }) => {
  const { pending, busy, close, confirm } = registration;
  if (!pending) return null;
  const { event, mode } = pending;
  const registering = mode === 'register';
  return (
    <Modal onClose={close} ariaLabel={registering ? 'Xác nhận đăng ký sự kiện' : 'Xác nhận hủy đăng ký'} size="md" role={registering ? 'dialog' : 'alertdialog'}>
      <div className="relative">
        <button
          type="button"
          onClick={close}
          aria-label="Đóng"
          className="absolute -right-1 -top-1 inline-flex h-8 w-8 items-center justify-center rounded-full bg-slate-100 text-slate-600 hover:bg-slate-200"
        >
          <X className="h-4 w-4" strokeWidth={2} aria-hidden="true" />
        </button>
        <span className="inline-flex h-[26px] items-center gap-1.5 rounded-full bg-tint px-[11px] text-caption font-semibold text-blue-800">
          <CalendarCheck className="h-[13px] w-[13px]" strokeWidth={1.8} aria-hidden="true" />
          Sự kiện của lớp học
        </span>
        <h2 className="mt-3.5 pr-8 text-[22px] font-semibold leading-[29px] tracking-[-0.3px] text-slate-900">
          {registering ? 'Đăng ký tham dự sự kiện?' : 'Hủy đăng ký sự kiện này?'}
        </h2>
        <p className="mt-1.5 text-ui text-slate-600">
          {registering
            ? 'Bạn sẽ được giữ một chỗ. Hủy đăng ký bất cứ lúc nào để nhường chỗ cho người khác.'
            : 'Chỗ của bạn sẽ được nhường cho người khác. Bạn có thể đăng ký lại nếu sự kiện còn chỗ.'}
        </p>
        <div className="mt-5 flex items-start gap-3 rounded-2xl border border-slate-200 p-4">
          <DateBlock date={new Date(event.startsAt)} />
          <div className="min-w-0">
            <p className="text-body-sm font-semibold text-slate-900">{event.title}</p>
            <p className="mt-0.5 text-meta text-slate-600 tabular">{formatEventWhen(event)}</p>
            <p className="text-meta text-slate-600">{formatEventPlace(event)}</p>
          </div>
        </div>
        <div className="mt-6 flex gap-3">
          <button type="button" onClick={close} disabled={busy} className={buttonClass('secondary', 'lg', 'flex-1')}>
            {registering ? 'Để sau' : 'Giữ chỗ'}
          </button>
          <button type="button" onClick={() => void confirm()} disabled={busy} className={buttonClass(registering ? 'primary' : 'danger', 'lg', 'flex-[1.6]')}>
            {registering && <Check className="h-4 w-4" strokeWidth={2.4} aria-hidden="true" />}
            {busy ? 'Đang xử lý...' : registering ? 'Đăng ký' : 'Hủy đăng ký'}
          </button>
        </div>
      </div>
    </Modal>
  );
};

// ---------------------------------------------------------------------------------------------------------------
// Event card (upcoming grid).

export const EventCard: React.FC<{
  event: ClassEvent;
  slug: string;
  gate: RegistrationGate;
  onRegister: (event: ClassEvent) => void;
  onSignIn: () => void;
  error?: string | null;
}> = ({ event, slug, gate, onRegister, onSignIn, error }) => {
  const detail = `/classes/${slug}/events/${event.id}`;
  let cta: React.ReactNode = null;
  if (gate === 'registered') {
    cta = (
      <Link to={detail} className={buttonClass('done', 'md')}>
        <Check className="h-4 w-4" strokeWidth={2.2} aria-hidden="true" />Đã đăng ký
      </Link>
    );
  } else if (gate === 'full') {
    cta = <button type="button" disabled className={buttonClass('secondary', 'md')}>Đã đủ chỗ</button>;
  } else if (gate === 'open') {
    cta = <button type="button" onClick={() => onRegister(event)} className={buttonClass('secondary', 'md')} aria-label={`Đăng ký ${event.title}`}>Đăng ký</button>;
  } else if (gate === 'guest') {
    cta = <button type="button" onClick={onSignIn} className={buttonClass('secondary', 'md')} aria-label={`Đăng ký ${event.title}`}>Đăng ký</button>;
  } else if (gate === 'join') {
    cta = <Link to={detail} className={buttonClass('secondary', 'md')} aria-label={`Đăng ký ${event.title}`}>Đăng ký</Link>;
  }

  return (
    <article className="card-hover flex flex-col overflow-hidden rounded-card border border-slate-200 bg-white shadow-hairline">
      <Link to={detail} tabIndex={-1} aria-hidden="true" className="relative block h-40 bg-slate-100 sm:h-[184px]">
        <EventTile event={event} />
        <span className="absolute inset-x-3.5 top-3.5 flex gap-1.5">
          <OverlayChip>{formatEventPlace(event)}</OverlayChip>
        </span>
      </Link>
      <div className="flex flex-1 flex-col p-4 sm:px-6 sm:pb-6 sm:pt-5">
        <div className="flex items-start gap-3.5">
          <DateBlock date={new Date(event.startsAt)} />
          <div className="min-w-0 flex-1">
            <p className="text-meta font-semibold text-blue-600 tabular">{formatEventWhen(event)}</p>
            <h3 className="mt-1 text-h3-lg font-semibold text-slate-900">
              <Link to={detail} className="text-slate-900 hover:text-blue-600">{event.title}</Link>
            </h3>
            <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
              {event.status === 'CANCELLED' && <CancelledBadge />}
              {event.isRegistered && event.status !== 'CANCELLED' && <RegisteredBadge />}
              {event.audience === 'MEMBERS' && <Badge tone="member" size="sm">Thành viên</Badge>}
            </div>
          </div>
        </div>
        <div className="mt-3"><HostLine event={event} /></div>
        <EventValueList event={event} limit={2} className="mt-3" />
        <div className="mt-auto flex items-center justify-between gap-3 pt-4">
          <span className="text-meta text-slate-600 tabular">{formatRegistered(event)}</span>
          {cta}
        </div>
        {error && <p role="alert" className={gate === 'full' || gate === 'cancelled' || gate === 'ended' ? 'sr-only' : 'mt-3 text-meta text-red-600'}>{error}</p>}
      </div>
    </article>
  );
};
