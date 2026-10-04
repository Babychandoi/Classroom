import React, { useEffect, useState } from 'react';
import { Link, useLocation, useOutletContext, useParams } from 'react-router-dom';
import { ChevronLeft, ExternalLink, LogIn, SearchX } from 'lucide-react';
import { ApiException } from '../../api/client';
import {
  formatClock,
  formatEventDay,
  formatEventPlace,
  formatEventTimeRange,
  formatEventWhen,
  getEvent,
  listClassEvents,
  registrationGate,
} from '../../api/events';
import { useAuth } from '../../context/AuthContext';
import { ClassJoinCta } from '../../components/ClassJoinCta';
import {
  CancelledBadge,
  EventTile,
  EventValueList,
  OverlayChip,
  RegisteredBadge,
  RegistrationDialog,
  useEventRegistration,
} from '../../components/EventBits';
import { SafeMarkdown } from '../../components/SafeMarkdown';
import { ErrorBanner, LoadingSpinner } from '../../components/UIStates';
import { Avatar, Badge, Card, DateBlock, buttonClass } from '../../components/ui';
import type { ClassEvent, Classroom } from '../../types';

// Event page (design: "Cộng đồng - Chi tiết sự kiện.dc.html"): hero, then main column (time, title, description, who it is
// for / what you take away, host) + a sticky registration card on the right. On a phone the registration card comes right
// after the title. The meeting link is only shown when the server returned it (registered people and managers).

export const EventDetailPage: React.FC = () => {
  const { eventId } = useParams<{ eventId: string }>();
  const { classroom, refreshClassroom } = useOutletContext<{ classroom: Classroom; refreshClassroom: () => Promise<void> }>();
  const { user } = useAuth();
  const location = useLocation();

  const [event, setEvent] = useState<ClassEvent | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [nextUp, setNextUp] = useState<ClassEvent[]>([]);

  const load = async () => {
    if (!eventId) return;
    setLoading(true);
    setError(null);
    setNotFound(false);
    try {
      setEvent(await getEvent(eventId));
    } catch (err) {
      setEvent(null);
      if (err instanceof ApiException && err.status === 404) setNotFound(true);
      else setError(err instanceof Error && err.message ? err.message : 'Không thể tải sự kiện.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [eventId, user?.id, classroom.isMember]);

  useEffect(() => {
    let alive = true;
    listClassEvents(classroom.id, 'upcoming')
      .then((list) => { if (alive) setNextUp((list ?? []).filter((e) => e.id !== eventId && e.status === 'SCHEDULED').slice(0, 3)); })
      .catch(() => { if (alive) setNextUp([]); });
    return () => { alive = false; };
  }, [classroom.id, eventId]);

  const registration = useEventRegistration(setEvent, (e) => { getEvent(e.id).then(setEvent).catch(() => undefined); });
  const eventsPath = `/classes/${classroom.slug}/events`;

  const backLink = (
    <Link to={eventsPath} className="inline-flex items-center gap-2 text-ui font-medium text-slate-600 hover:text-slate-900">
      <ChevronLeft className="h-4 w-4" strokeWidth={2} aria-hidden="true" />
      Tất cả sự kiện
    </Link>
  );

  if (loading) return <LoadingSpinner message="Đang tải sự kiện..." />;

  if (notFound) {
    return (
      <div className="mx-auto max-w-[1040px] space-y-4">
        {backLink}
        <div role="alert" className="flex flex-col items-center rounded-card border border-slate-200 bg-white p-10 text-center shadow-hairline">
          <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-community bg-slate-100 text-slate-500">
            <SearchX className="h-6 w-6" strokeWidth={1.7} aria-hidden="true" />
          </div>
          <h1 className="text-h3 font-semibold text-slate-900">Không tìm thấy sự kiện</h1>
          <p className="mt-1 max-w-sm text-ui text-slate-600">Sự kiện có thể đã bị xóa. Xem các sự kiện khác của lớp học.</p>
          <Link to={eventsPath} className={buttonClass('secondary', 'md', 'mt-5')}>Về Sự kiện</Link>
        </div>
      </div>
    );
  }

  if (error || !event) {
    return (
      <div className="mx-auto max-w-[1040px] space-y-4">
        {backLink}
        <ErrorBanner message={error || 'Không thể tải sự kiện.'} onRetry={load} />
      </div>
    );
  }

  const gate = registrationGate(event, classroom, !!user);
  const cancelled = event.status === 'CANCELLED';

  const meetingButton = event.meetingUrl && !cancelled && gate !== 'ended' ? (
    <a href={event.meetingUrl} target="_blank" rel="noopener noreferrer" className={buttonClass('primary', 'lg', 'w-full')}>
      <ExternalLink className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
      Vào phòng họp
    </a>
  ) : null;

  let action: React.ReactNode;
  switch (gate) {
    case 'open':
      action = <button type="button" onClick={() => registration.ask(event, 'register')} className={buttonClass('primary', 'lg', 'w-full')}>Đăng ký tham dự</button>;
      break;
    case 'registered':
      action = (
        <>
          {meetingButton}
          <button type="button" onClick={() => registration.ask(event, 'cancel')} className={buttonClass('secondary', 'lg', `w-full ${meetingButton ? 'mt-2' : ''}`)}>
            Hủy đăng ký
          </button>
          <p className="mt-2.5 text-center text-caption text-slate-500">Hủy đăng ký bất cứ lúc nào — nhường chỗ cho người khác.</p>
        </>
      );
      break;
    case 'full':
      action = (
        <>
          {meetingButton}
          <button type="button" disabled className={buttonClass('secondary', 'lg', `w-full ${meetingButton ? 'mt-2' : ''}`)}>Đã đủ chỗ</button>
          <p className="mt-2.5 text-center text-caption text-slate-500">Sự kiện đã nhận đủ người. Nếu có người hủy, chỗ trống sẽ mở lại tại đây.</p>
        </>
      );
      break;
    case 'guest':
      action = (
        <>
          <Link to="/login" state={{ from: location }} className={buttonClass('secondary', 'lg', 'w-full')}>
            <LogIn className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
            Đăng nhập để đăng ký
          </Link>
          <p className="mt-2.5 text-center text-caption text-slate-500">Bạn sẽ quay lại đúng trang này sau khi đăng nhập.</p>
        </>
      );
      break;
    case 'join':
      action = (
        <>
          <p className="mb-3 text-meta text-slate-600">Sự kiện dành cho thành viên lớp học. Tham gia lớp để giữ chỗ.</p>
          <ClassJoinCta classroom={classroom} refreshClassroom={refreshClassroom} joinLabel="Tham gia lớp để đăng ký" className="w-full [&>button]:w-full" />
        </>
      );
      break;
    case 'cancelled':
      action = <p role="status" className="rounded-btn bg-red-50 px-4 py-3 text-ui text-red-700">Sự kiện này đã bị hủy. Theo dõi các sự kiện khác của lớp học.</p>;
      break;
    default:
      action = <p role="status" className="rounded-btn bg-slate-100 px-4 py-3 text-ui text-slate-600">Sự kiện đã diễn ra.</p>;
  }
  // Managers get the meeting link without registering; show it for them too (once) when the gate did not already.
  // Secondary when the card already has its primary action ("Đăng ký tham dự"): one blue button per card.
  const managerMeeting = gate !== 'registered' && gate !== 'full' && event.meetingUrl && !cancelled && gate !== 'ended' ? (
    <a href={event.meetingUrl} target="_blank" rel="noopener noreferrer" className={buttonClass(gate === 'open' ? 'secondary' : 'primary', 'lg', 'w-full')}>
      <ExternalLink className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
      Vào phòng họp
    </a>
  ) : null;

  return (
    <div className="mx-auto max-w-[1040px]">
      {backLink}

      <section aria-label="Ảnh sự kiện" className="relative mt-4 overflow-hidden rounded-section shadow-lift">
        <div className="h-[200px] bg-slate-900 sm:h-[320px]">
          <EventTile event={event} iconSize={30} />
        </div>
        <span className="absolute left-4 right-28 top-4 flex"><OverlayChip>{formatEventPlace(event)}</OverlayChip></span>
        <span className="absolute right-4 top-4">
          {cancelled ? <CancelledBadge /> : event.isRegistered ? <RegisteredBadge /> : null}
        </span>
      </section>

      <div className="mt-6 grid gap-6 sm:mt-7 lg:grid-cols-[minmax(0,1fr)_340px] lg:gap-x-8">
        <header className="min-w-0 lg:col-start-1 lg:row-start-1">
          <p className="text-ui font-semibold text-blue-600 tabular">{formatEventWhen(event, true)}</p>
          <h1 className="mt-2 text-[23px] font-semibold leading-[31px] tracking-[-0.4px] text-slate-900 sm:text-h1">{event.title}</h1>
          <div className="mt-2 flex flex-wrap items-center gap-1.5">
            {event.audience === 'MEMBERS' ? <Badge tone="member" size="sm">Dành cho thành viên</Badge> : <Badge tone="neutral" size="sm">Mở cho mọi người</Badge>}
          </div>
          {event.description && <SafeMarkdown source={event.description} size="body" className="mt-3 [&_p]:text-slate-600" />}
        </header>

        <aside className="flex min-w-0 flex-col gap-5 lg:sticky lg:top-24 lg:col-start-2 lg:row-span-2 lg:row-start-1 lg:self-start">
          <section aria-label="Đăng ký tham dự" className="rounded-card border border-slate-200 bg-white p-6 shadow-e1">
            <div className="flex gap-3.5">
              <DateBlock date={new Date(event.startsAt)} />
              <div className="min-w-0">
                <p className="text-ui font-semibold text-slate-900">{formatEventDay(event.startsAt, true)}</p>
                <p className="mt-0.5 text-meta text-slate-600 tabular">{formatEventTimeRange(event.startsAt, event.endsAt)} · {formatEventPlace(event)}</p>
                <p className="mt-0.5 text-caption text-slate-500">{event.audience === 'MEMBERS' ? 'Dành cho thành viên lớp học' : 'Ai xem được lớp học đều có thể đăng ký'}</p>
              </div>
            </div>
            <p className="mt-4 text-meta text-slate-600 tabular">
              <strong className="font-semibold text-slate-900">{event.registeredCount.toLocaleString('vi-VN')} người</strong> đã đăng ký
              {event.capacity ? <span> · tối đa {event.capacity.toLocaleString('vi-VN')} chỗ</span> : null}
            </p>
            <div className="mt-4">
              {managerMeeting && <div className="mb-2">{managerMeeting}</div>}
              {action}
            </div>
            {/* When the re-read state already says it (full / cancelled / ended), the 409 sentence is only announced. */}
            {registration.error && (
              <p role="alert" className={gate === 'full' || gate === 'cancelled' || gate === 'ended' ? 'sr-only' : 'mt-3 text-meta text-red-600'}>{registration.error}</p>
            )}
          </section>

          {nextUp.length > 0 && (
            <section className="rounded-card border border-slate-200 bg-white px-6 py-5 shadow-hairline">
              <h2 className="text-body-sm font-semibold text-slate-900">Tiếp theo trong lớp học</h2>
              <ul className="mt-3.5 space-y-3">
                {nextUp.map((e) => {
                  const d = new Date(e.startsAt);
                  return (
                    <li key={e.id}>
                      <Link to={`/classes/${classroom.slug}/events/${e.id}`} className="flex items-center gap-3 text-slate-900 hover:text-blue-600">
                        <span className="flex h-11 w-10 flex-shrink-0 flex-col items-center justify-center rounded-[11px] bg-slate-100">
                          <span className="text-micro-xs font-semibold uppercase text-slate-600">{['CN', 'Th 2', 'Th 3', 'Th 4', 'Th 5', 'Th 6', 'Th 7'][d.getDay()]}</span>
                          <span className="text-body-sm font-semibold tabular">{String(d.getDate()).padStart(2, '0')}</span>
                        </span>
                        <span className="min-w-0">
                          <span className="block truncate text-meta font-semibold">{e.title}</span>
                          <span className="block text-caption text-slate-500 tabular">{formatClock(d)} · {formatEventPlace(e)}</span>
                        </span>
                      </Link>
                    </li>
                  );
                })}
              </ul>
            </section>
          )}
        </aside>

        <div className="min-w-0 space-y-6 lg:col-start-1 lg:row-start-2">
          {(event.forWhom || (event.takeaways || []).length > 0) && (
            <Card as="section" className="sm:px-7">
              <h2 className="text-h3 font-semibold text-slate-900">Dành cho ai &amp; bạn mang về gì</h2>
              <EventValueList event={event} className="mt-4" />
            </Card>
          )}
          <Card as="section" className="flex flex-wrap items-center gap-4 sm:px-7">
            <Avatar name={event.host.fullName} src={event.host.avatarUrl} size={56} />
            <div className="min-w-[200px] flex-1">
              <p className="text-body-sm font-semibold text-slate-900">Dẫn dắt bởi {event.host.fullName}</p>
              <p className="mt-0.5 text-meta text-slate-600">Người chủ trì · {classroom.title}</p>
            </div>
          </Card>
        </div>
      </div>

      <RegistrationDialog registration={registration} />
    </div>
  );
};
