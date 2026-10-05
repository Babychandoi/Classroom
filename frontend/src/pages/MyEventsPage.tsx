import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { CalendarDays, ExternalLink } from 'lucide-react';
import { listMyEvents, ME_PAGE_SIZE } from '../api/me';
import { formatEventPlace, formatEventWhen, hasEnded, type EventScope } from '../api/events';
import { usePagedList } from '../hooks/usePagedList';
import { CancelledBadge, RegistrationDialog, useEventRegistration } from '../components/EventBits';
import { LoadMore, MePage } from '../components/MePageBits';
import { EmptyState, ErrorBanner, LoadingSpinner } from '../components/UIStates';
import { Card, DateBlock, SegmentTabs, buttonClass } from '../components/ui';
import type { ClassEvent } from '../types';

const SCOPES: { key: EventScope; label: string }[] = [
  { key: 'upcoming', label: 'Sắp diễn ra' },
  { key: 'past', label: 'Đã qua' },
  { key: 'all', label: 'Tất cả' },
];

const EMPTY: Record<EventScope, { title: string; description: string }> = {
  upcoming: { title: 'Bạn chưa đăng ký sự kiện nào sắp tới', description: 'Xem lịch sự kiện của các lớp bạn tham gia và đăng ký một buổi phù hợp.' },
  past: { title: 'Chưa có sự kiện đã qua', description: 'Các sự kiện bạn đã đăng ký và đã diễn ra sẽ nằm ở đây.' },
  all: { title: 'Bạn chưa đăng ký sự kiện nào', description: 'Xem lịch sự kiện của các lớp bạn tham gia và đăng ký một buổi phù hợp.' },
};

const EventRow: React.FC<{ event: ClassEvent; onCancel: (event: ClassEvent) => void }> = ({ event, onCancel }) => {
  const detail = `/classes/${event.classSlug}/events/${event.id}`;
  const cancelled = event.status === 'CANCELLED';
  const ended = hasEnded(event);
  return (
    <Card as="article" padded={false} className="flex flex-wrap items-center gap-4 p-4 sm:gap-5 sm:px-6 sm:py-5">
      <DateBlock date={new Date(event.startsAt)} />
      <div className="min-w-[200px] flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="text-h3 font-semibold">
            <Link to={detail} className="text-slate-900 hover:text-blue-600">{event.title}</Link>
          </h3>
          {cancelled && <CancelledBadge />}
        </div>
        {event.classTitle && (
          <p className="mt-0.5 text-meta text-slate-600">
            <Link to={`/classes/${event.classSlug}/feed`} className="font-medium text-slate-900 hover:text-blue-600">{event.classTitle}</Link>
          </p>
        )}
        <p className="mt-0.5 text-ui text-slate-600 tabular">
          {formatEventWhen(event, true)} · {formatEventPlace(event)}
        </p>
      </div>
      <div className="flex w-full flex-wrap items-center gap-x-4 gap-y-2 sm:w-auto sm:flex-shrink-0">
        {event.meetingUrl && !cancelled && (
          <a href={event.meetingUrl} target="_blank" rel="noopener noreferrer" className={buttonClass('secondary', 'md')}>
            <ExternalLink className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
            Vào phòng họp
          </a>
        )}
        {!cancelled && !ended && (
          <button
            type="button"
            onClick={() => onCancel(event)}
            aria-label={`Hủy đăng ký ${event.title}`}
            className="text-ui font-medium text-red-700 hover:text-red-800"
          >
            Hủy đăng ký
          </button>
        )}
        <Link to={detail} aria-label={`Xem chi tiết ${event.title}`} className="text-ui font-medium text-blue-600 hover:text-blue-700">
          Xem chi tiết
        </Link>
      </div>
    </Card>
  );
};

// "Sự kiện tham gia": the events the caller registered for. Cancelling a registration reuses the event pages' confirm dialog;
// a cancelled registration drops out of the list (the server only returns events the caller is registered for).
export const MyEventsPage: React.FC = () => {
  const [scope, setScope] = useState<EventScope>('upcoming');
  const list = usePagedList<ClassEvent>((page) => listMyEvents(scope, page, ME_PAGE_SIZE), ME_PAGE_SIZE, scope);
  const registration = useEventRegistration((updated) => {
    list.setItems((items) => items.filter((e) => e.id !== updated.id));
  });

  let body: React.ReactNode;
  if (list.loading) {
    body = <LoadingSpinner message="Đang tải sự kiện của bạn..." />;
  } else if (list.error) {
    body = <div className="mx-auto max-w-xl"><ErrorBanner message={list.error} onRetry={list.reload} /></div>;
  } else if (list.items.length === 0) {
    body = (
      <EmptyState
        title={EMPTY[scope].title}
        description={EMPTY[scope].description}
        icon={<CalendarDays className="h-6 w-6" strokeWidth={1.7} />}
      >
        <Link to="/me/classes" className={buttonClass('primary', 'md')}>Xem lớp học của tôi</Link>
        <Link to="/classes" className={buttonClass('secondary', 'md')}>Khám phá lớp học</Link>
      </EmptyState>
    );
  } else {
    body = (
      <>
        <ul className="space-y-3 sm:space-y-4">
          {list.items.map((event) => (
            <li key={event.id}><EventRow event={event} onCancel={(e) => registration.ask(e, 'cancel')} /></li>
          ))}
        </ul>
        {list.hasMore && (
          <LoadMore label="Xem thêm sự kiện" loading={list.loadingMore} error={list.moreError} onClick={() => void list.loadMore()} />
        )}
      </>
    );
  }

  return (
    <MePage title="Sự kiện tham gia" description="Các sự kiện bạn đã đăng ký, kèm liên kết phòng họp khi có.">
      <SegmentTabs
        ariaLabel="Lọc sự kiện theo thời gian"
        items={SCOPES}
        value={scope}
        onChange={(key) => setScope(key as EventScope)}
        className="mb-6"
      />
      {registration.error && (
        <div className="mb-4"><ErrorBanner message={registration.error} /></div>
      )}
      {body}
      <RegistrationDialog registration={registration} />
    </MePage>
  );
};
