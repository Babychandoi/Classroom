import React, { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useOutletContext } from 'react-router-dom';
import { CalendarPlus, ExternalLink, MapPin, Video } from 'lucide-react';
import {
  formatEventDay,
  formatEventPlace,
  formatEventWhen,
  formatRegistered,
  getEvent,
  listClassEvents,
  registrationGate,
} from '../../api/events';
import { hasStudioPermission } from '../../api/permissions';
import { useAuth } from '../../context/AuthContext';
import {
  CancelledBadge,
  EventCard,
  EventTile,
  OverlayChip,
  RegisteredBadge,
  RegistrationDialog,
  useEventRegistration,
} from '../../components/EventBits';
import { ErrorBanner, LoadingSpinner } from '../../components/UIStates';
import { DateBlock, SectionHeader, buttonClass } from '../../components/ui';
import type { ClassEvent, Classroom } from '../../types';

// Events tab (design: "Cộng đồng - Sự kiện.dc.html"): the events you registered for first, a featured banner for the next
// event, the upcoming grid, then "Đã diễn ra". Cancelled events keep their card with a badge. No countdowns (design rule).
// The design's recordings / weekly-rhythm blocks need data the backend does not keep and are left out.

export const EventsTab: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom; refreshClassroom: () => Promise<void> }>();
  const { user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const canCreate = hasStudioPermission(classroom, 'EVENT', 'CREATE');

  const [upcoming, setUpcoming] = useState<ClassEvent[]>([]);
  const [past, setPast] = useState<ClassEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const [next, done] = await Promise.all([
        listClassEvents(classroom.id, 'upcoming'),
        listClassEvents(classroom.id, 'past').catch(() => [] as ClassEvent[]),
      ]);
      setUpcoming(next ?? []);
      setPast(done ?? []);
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : 'Không thể tải sự kiện.');
    } finally {
      setLoading(false);
    }
  };

  // Keyed on the person too: registration state (isRegistered / meetingUrl) differs per viewer.
  useEffect(() => { void load(); }, [classroom.id, user?.id, classroom.isMember]);

  const replace = (updated: ClassEvent) => setUpcoming((list) => list.map((e) => (e.id === updated.id ? updated : e)));
  const registration = useEventRegistration(replace, (event) => {
    // A 409 means our copy is stale (now full / cancelled / ended): re-read that event.
    getEvent(event.id).then(replace).catch(() => undefined);
  });
  const signIn = () => navigate('/login', { state: { from: location } });

  if (loading) return <LoadingSpinner message="Đang tải sự kiện..." />;
  if (error) return <ErrorBanner message={error} onRetry={load} />;

  const signedIn = !!user;
  const mine = upcoming.filter((e) => e.isRegistered && e.status === 'SCHEDULED');
  const others = upcoming.filter((e) => !mine.includes(e));
  const featured = others.find((e) => e.status === 'SCHEDULED');
  const grid = others.filter((e) => e !== featured);
  const studioPath = `/studio/classes/${classroom.id}/events`;
  const detailPath = (e: ClassEvent) => `/classes/${classroom.slug}/events/${e.id}`;

  return (
    <>
    <div className="space-y-8 sm:space-y-12">
      <SectionHeader
        title="Sự kiện"
        description="Buổi trực tuyến và gặp mặt của lớp học. Giờ hiển thị theo múi giờ trên thiết bị của bạn."
        action={canCreate ? (
          <Link to={studioPath} className={buttonClass('secondary', 'md')}>
            <CalendarPlus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
            Tạo sự kiện
          </Link>
        ) : undefined}
      />

      {mine.length > 0 && (
        <section aria-labelledby="events-mine" className="space-y-3">
          <h2 id="events-mine" className="text-h2-sm font-semibold text-slate-900">Bạn đã đăng ký</h2>
          {mine.map((event) => (
            <article key={event.id} className="flex flex-wrap items-center gap-4 rounded-card border border-slate-200 bg-white p-4 shadow-hairline sm:gap-5 sm:px-6 sm:py-5">
              <DateBlock date={new Date(event.startsAt)} />
              <div className="min-w-[200px] flex-1">
                <div className="flex flex-wrap items-center gap-2.5">
                  <h3 className="text-h3 font-semibold">
                    <Link to={detailPath(event)} className="text-slate-900 hover:text-blue-600">{event.title}</Link>
                  </h3>
                  <RegisteredBadge />
                </div>
                <p className="mt-0.5 text-ui text-slate-600 tabular">
                  {formatEventWhen(event)} · {formatEventPlace(event)} · cùng {event.host.fullName}
                </p>
              </div>
              <div className="flex flex-shrink-0 flex-wrap items-center gap-3">
                {event.meetingUrl && (
                  <a href={event.meetingUrl} target="_blank" rel="noopener noreferrer" className={buttonClass('secondary', 'md')}>
                    <ExternalLink className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                    Vào phòng họp
                  </a>
                )}
                <Link to={detailPath(event)} className="text-ui font-medium text-blue-600 hover:text-blue-700">Xem chi tiết</Link>
              </div>
            </article>
          ))}
        </section>
      )}

      {featured && (
        <section aria-label="Sự kiện sắp tới nổi bật" className="relative overflow-hidden rounded-section shadow-lift">
          <div className="h-[300px] bg-slate-900 sm:h-[340px]">
            {featured.coverUrl ? (
              <EventTile event={featured} />
            ) : (
              <div aria-hidden="true" className="hidden h-full w-full justify-center pt-16 sm:flex">
                <span className="inline-flex h-[72px] w-[72px] items-center justify-center rounded-full bg-white/[0.12] text-white">
                  {featured.format === 'ONLINE'
                    ? <Video className="h-[30px] w-[30px]" strokeWidth={1.6} />
                    : <MapPin className="h-[30px] w-[30px]" strokeWidth={1.6} />}
                </span>
              </div>
            )}
          </div>
          <div aria-hidden="true" className="absolute inset-0 bg-[linear-gradient(180deg,rgba(15,23,42,0.08)_30%,rgba(15,23,42,0.78)_100%)]" />
          <div className="absolute inset-x-0 bottom-0 flex flex-wrap items-end justify-between gap-4 p-5 sm:gap-6 sm:px-9 sm:py-8">
            <div className="min-w-0 max-w-[640px]">
              <OverlayChip>{formatEventPlace(featured)} · {featured.isFull ? 'Đã đủ chỗ' : 'Mở đăng ký'}</OverlayChip>
              <h2 className="mt-3 text-[23px] font-semibold leading-[31px] tracking-[-0.4px] text-white sm:text-h1">{featured.title}</h2>
              <p className="mt-1.5 text-ui text-white/85 sm:text-body-sm tabular">
                {formatEventWhen(featured)} · cùng {featured.host.fullName} · {formatRegistered(featured)}
              </p>
            </div>
            <Link to={detailPath(featured)} className={buttonClass('on-dark', 'lg')}>Xem &amp; đăng ký</Link>
          </div>
        </section>
      )}

      {upcoming.length === 0 ? (
        <div className="flex flex-col items-center rounded-card border border-slate-200 bg-white p-10 text-center shadow-hairline sm:p-12">
          <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-community bg-slate-100 text-slate-500">
            <CalendarPlus className="h-6 w-6" strokeWidth={1.7} aria-hidden="true" />
          </div>
          <h3 className="text-h3 font-semibold text-slate-900">Chưa có sự kiện sắp tới</h3>
          <p className="mt-1 max-w-sm text-ui text-slate-600">
            {canCreate
              ? 'Một buổi hỏi đáp 60 phút là cách nhanh nhất để lớp gặp nhau — tạo sự kiện, thành viên đăng ký ngay tại đây.'
              : 'Khi lớp có buổi mới, bạn sẽ đăng ký được ở đây. Trong lúc chờ, ghé Thảo luận để trao đổi cùng mọi người.'}
          </p>
          {canCreate ? (
            <Link to={studioPath} className={buttonClass('primary', 'md', 'mt-5')}>Tạo sự kiện đầu tiên</Link>
          ) : (
            <Link to={`/classes/${classroom.slug}/feed`} className={buttonClass('secondary', 'md', 'mt-5')}>Vào Thảo luận</Link>
          )}
        </div>
      ) : grid.length > 0 && (
        <section aria-labelledby="events-upcoming">
          <div className="mb-4 sm:mb-5">
            <h2 id="events-upcoming" className="text-h2-sm font-semibold text-slate-900 sm:text-h2">Sắp diễn ra</h2>
          </div>
          <div className="grid gap-3 sm:gap-6 md:grid-cols-2">
            {grid.map((event) => (
              <EventCard
                key={event.id}
                event={event}
                slug={classroom.slug}
                gate={registrationGate(event, classroom, signedIn)}
                onRegister={(e) => registration.ask(e, 'register')}
                onSignIn={signIn}
                error={registration.errorFor === event.id ? registration.error : null}
              />
            ))}
          </div>
        </section>
      )}

      {registration.error && registration.errorFor && !grid.some((e) => e.id === registration.errorFor) && (
        <ErrorBanner message={registration.error} />
      )}

      {past.length > 0 && (
        <section aria-labelledby="events-past">
          <div className="mb-4 sm:mb-5">
            <h2 id="events-past" className="text-h2-sm font-semibold text-slate-900 sm:text-h2">Đã diễn ra</h2>
            <p className="mt-1 text-ui text-slate-600 sm:text-body-sm">Các buổi lớp học đã tổ chức.</p>
          </div>
          <div className="grid gap-3 sm:grid-cols-2 sm:gap-6 lg:grid-cols-3">
            {past.map((event) => (
              <Link
                key={event.id}
                to={detailPath(event)}
                className="card-hover flex flex-col overflow-hidden rounded-card border border-slate-200 bg-white text-slate-900 shadow-hairline"
              >
                <div className="relative h-[152px] bg-slate-100">
                  <EventTile event={event} label={false} className="saturate-[0.9]" />
                  {event.status === 'CANCELLED' && <span className="absolute left-3 top-3"><CancelledBadge /></span>}
                </div>
                <div className="px-5 pb-[18px] pt-4">
                  <h3 className="truncate text-body-sm font-semibold">{event.title}</h3>
                  <p className="mt-0.5 text-meta text-slate-500 tabular">
                    {formatEventDay(event.startsAt, true)} · {event.registeredCount.toLocaleString('vi-VN')} người đăng ký
                  </p>
                </div>
              </Link>
            ))}
          </div>
        </section>
      )}

    </div>
    {/* Outside the space-y container: a sibling margin would shift the fixed overlay down. */}
    <RegistrationDialog registration={registration} />
    </>
  );
};
