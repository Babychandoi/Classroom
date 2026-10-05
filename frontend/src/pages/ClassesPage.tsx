import React, { useCallback, useEffect, useId, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Classroom, ClassEvent } from '../types';
import { api } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { LoadingSpinner, ErrorBanner, EmptyState } from '../components/UIStates';
import { ClassCard, isMine } from '../components/ClassCard';
import { Avatar, DateBlock, FilterChip, buttonClass } from '../components/ui';
import { ArrowLeft, BookOpen, CalendarDays, GraduationCap, Lock, MapPin, Plus, Search, Video } from 'lucide-react';

// R16-08: GET /classes is paged (the server caps a page at 100 and defaults to 50). A page shorter than
// the requested size is the last one; anything else offers "Xem thêm lớp học".
export const CLASSES_PAGE_SIZE = 50;
/** A curated rail never shows more than this many cards (03-patterns: "rail curated ≤6 card"). */
export const RAIL_SIZE = 6;
/** The hero search waits this long after the last keystroke before asking the server (GET /classes?q=). */
export const SEARCH_DEBOUNCE_MS = 300;
const SEARCH_MAX_LENGTH = 100;

// D-19: client-side filter on the classes already loaded (the server has no fee filter).
type FeeFilter = 'ALL' | 'FREE' | 'PAID';
const FEE_FILTERS: { key: FeeFilter; label: string }[] = [
  { key: 'ALL', label: 'Tất cả' },
  { key: 'FREE', label: 'Miễn phí' },
  { key: 'PAID', label: 'Trả phí' },
];

const classesUrl = (page: number, size: number, extra: Record<string, string | undefined> = {}) => {
  const params = Object.entries(extra)
    .filter(([, value]) => value)
    .map(([key, value]) => `&${key}=${encodeURIComponent(value as string)}`)
    .join('');
  return `/classes?page=${page}&size=${size}${params}`;
};

/** A PRIVATE class is never listed on the home page (rails, search, catalog): it is only reached through its invite link. */
const isListed = (cls: Classroom) => String(cls.visibility || 'PUBLIC').toUpperCase() !== 'PRIVATE';

const byPopularity = (a: Classroom, b: Classroom) => (b.memberCount ?? 0) - (a.memberCount ?? 0);
const byNewest = (a: Classroom, b: Classroom) => (Date.parse(b.createdAt) || 0) - (Date.parse(a.createdAt) || 0);

const pad = (n: number) => String(n).padStart(2, '0');
const WEEKDAYS = ['Chủ Nhật', 'Thứ Hai', 'Thứ Ba', 'Thứ Tư', 'Thứ Năm', 'Thứ Sáu', 'Thứ Bảy'];

/** "Thứ Năm, 09/10 · 20:00–21:30" (24h clock; the year only when it is not this year). */
function eventWhen(startsAt: string, endsAt: string): string {
  const start = new Date(startsAt);
  const end = new Date(endsAt);
  if (Number.isNaN(start.getTime())) return '';
  const year = start.getFullYear() !== new Date().getFullYear() ? `/${start.getFullYear()}` : '';
  const day = `${WEEKDAYS[start.getDay()]}, ${pad(start.getDate())}/${pad(start.getMonth() + 1)}${year}`;
  const from = `${pad(start.getHours())}:${pad(start.getMinutes())}`;
  if (Number.isNaN(end.getTime())) return `${day} · ${from}`;
  const sameDay = end.toDateString() === start.toDateString();
  const to = sameDay
    ? `${pad(end.getHours())}:${pad(end.getMinutes())}`
    : `${pad(end.getDate())}/${pad(end.getMonth() + 1)} ${pad(end.getHours())}:${pad(end.getMinutes())}`;
  return `${day} · ${from}–${to}`;
}

// Event card (02-components "Card sự kiện"): calendar column + title + host (face + name) + full time + class +
// registered count + one secondary CTA. No countdown.
const EventCard: React.FC<{ event: ClassEvent; className?: string }> = ({ event, className = '' }) => {
  const start = new Date(event.startsAt);
  const href = `/classes/${event.classSlug ?? ''}/events/${event.id}`;
  return (
    <article className={`card-hover relative flex flex-col rounded-card border border-slate-200 bg-white p-4 shadow-hairline sm:p-5 ${className}`}>
      <div className="flex items-start gap-4">
        {!Number.isNaN(start.getTime()) && <DateBlock date={start} />}
        <div className="min-w-0 flex-1">
          <h3 className="line-clamp-2 text-h3 font-semibold text-slate-900 sm:text-h3-lg">{event.title}</h3>
          {event.classTitle && <p className="mt-0.5 truncate text-meta text-slate-600">{event.classTitle}</p>}
        </div>
      </div>
      <div className="mt-3 flex min-w-0 items-center gap-2">
        <Avatar name={event.host?.fullName} src={event.host?.avatarUrl} size={22} />
        <span className="truncate text-meta text-slate-600">
          Chủ trì: <strong className="font-semibold text-slate-900">{event.host?.fullName}</strong>
        </span>
      </div>
      <p className="mt-2.5 flex items-center gap-2 text-ui font-medium text-slate-900">
        <CalendarDays className="h-4 w-4 flex-shrink-0 text-slate-600" strokeWidth={1.75} aria-hidden="true" />
        <span className="tabular">{eventWhen(event.startsAt, event.endsAt)}</span>
      </p>
      <p className="mt-1 flex items-center gap-2 text-meta text-slate-600">
        {event.format === 'ONLINE'
          ? <Video className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" />
          : <MapPin className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" />}
        <span className="truncate">
          {event.format === 'ONLINE' ? 'Trực tuyến' : 'Trực tiếp'}
          {event.location ? ` · ${event.location}` : ''}
        </span>
      </p>
      <div className="mt-auto flex items-center justify-between gap-3 pt-4">
        <span className="whitespace-nowrap text-meta text-slate-600 tabular">{(event.registeredCount ?? 0) > 0 ? `${(event.registeredCount ?? 0).toLocaleString('vi-VN')} đã đăng ký` : 'Chưa ai đăng ký'}</span>
        <Link to={href} className={buttonClass('secondary', 'md', "flex-shrink-0 after:absolute after:inset-0 after:content-['']")}>
          Xem &amp; đăng ký
        </Link>
      </div>
    </article>
  );
};

// A rail: horizontal scroll strip on phones (cards 280px wide, 12px gap), a 3-column grid from `sm` up.
const Rail: React.FC<{
  id: string;
  title: string;
  description?: string;
  onSeeAll?: () => void;
  seeAllLabel?: string;
  children: React.ReactNode;
}> = ({ id, title, description, onSeeAll, seeAllLabel, children }) => (
  <section aria-labelledby={id}>
    <div className="mb-2.5 flex items-baseline justify-between gap-4 sm:mb-6 sm:items-end">
      <div className="min-w-0">
        <h2 id={id} className="text-[17px] font-semibold leading-[23px] tracking-[-0.2px] text-slate-900 sm:text-h2">{title}</h2>
        {description && <p className="mt-1 hidden text-body-sm text-slate-600 sm:block">{description}</p>}
      </div>
      {onSeeAll && (
        <button type="button" onClick={onSeeAll} aria-label={seeAllLabel} className="flex-shrink-0 whitespace-nowrap text-caption font-semibold text-blue-600 hover:text-blue-700 sm:text-ui sm:font-medium">
          Xem tất cả
        </button>
      )}
    </div>
    <div className="scrollbar-none -mx-4 flex snap-x gap-3 overflow-x-auto px-4 pb-1 sm:mx-0 sm:grid sm:grid-cols-2 sm:gap-6 sm:overflow-visible sm:px-0 sm:pb-0 lg:grid-cols-3">
      {children}
    </div>
  </section>
);
const RAIL_ITEM = 'w-[280px] flex-shrink-0 snap-start sm:w-auto';

export const ClassesPage: React.FC = () => {
  // R17-01: wait for the session bootstrap before the first fetch - GET /classes answers differently for
  // a signed-in person (their role/membership), so fetching first as a guest just to redo it moments
  // later doubled the request and flashed the wrong state.
  const { user, isLoading: authLoading } = useAuth();
  const navigate = useNavigate();

  const [classes, setClasses] = useState<Classroom[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [nextPage, setNextPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);

  // Home = hero + curated rails. "Xem tất cả" (or a search) switches to the full paged catalog below the hero.
  const [showAll, setShowAll] = useState(false);
  const [searchInput, setSearchInput] = useState('');
  const [query, setQuery] = useState('');
  const [popular, setPopular] = useState<Classroom[] | null>(null);
  const [newest, setNewest] = useState<Classroom[] | null>(null);
  const [events, setEvents] = useState<ClassEvent[]>([]);
  const requestSeq = useRef(0);

  const [feeFilter, setFeeFilter] = useState<FeeFilter>('ALL');
  const searchId = useId();

  const fetchClasses = useCallback(async () => {
    // A newer request (e.g. the next keystroke of a search) supersedes this one; its answer is dropped.
    const seq = ++requestSeq.current;
    try {
      setLoading(true);
      setError(null);
      const data = (await api.get<Classroom[]>(classesUrl(0, CLASSES_PAGE_SIZE, { q: query || undefined }))) || [];
      if (seq !== requestSeq.current) return;
      setClasses(data.filter(isListed));
      setNextPage(1);
      setHasMore(data.length >= CLASSES_PAGE_SIZE);
    } catch (err: any) {
      if (seq !== requestSeq.current) return;
      setError(err.message || 'Không thể tải danh sách lớp học');
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, [query]);

  const loadMoreClasses = async () => {
    const seq = requestSeq.current;
    try {
      setLoadingMore(true);
      setError(null);
      const data = (await api.get<Classroom[]>(classesUrl(nextPage, CLASSES_PAGE_SIZE, { q: query || undefined }))) || [];
      if (seq !== requestSeq.current) return;
      // De-dup by id: a class created meanwhile can shift rows across a page boundary.
      setClasses((current) => {
        const seen = new Set(current.map((c) => c.id));
        return [...current, ...data.filter((c) => isListed(c) && !seen.has(c.id))];
      });
      setNextPage(nextPage + 1);
      setHasMore(data.length >= CLASSES_PAGE_SIZE);
    } catch (err: any) {
      setError(err.message || 'Không thể tải thêm lớp học');
    } finally {
      setLoadingMore(false);
    }
  };

  useEffect(() => {
    if (authLoading) return;
    void fetchClasses();
  }, [user?.id, authLoading, fetchClasses]);

  // Curated rails (sort=popular / sort=newest) and the cross-class upcoming events. A rail whose request fails
  // falls back to the loaded catalog; the events rail simply hides.
  useEffect(() => {
    if (authLoading) return;
    let cancelled = false;
    const loadRail = (sort: 'popular' | 'newest', set: (v: Classroom[] | null) => void) =>
      api.get<Classroom[]>(classesUrl(0, RAIL_SIZE, { sort }))
        .then((data) => { if (!cancelled) set((data || []).filter(isListed)); })
        .catch(() => { if (!cancelled) set(null); });
    void loadRail('popular', setPopular);
    void loadRail('newest', setNewest);
    api.get<ClassEvent[]>(`/events/upcoming?size=${RAIL_SIZE}`)
      .then((data) => {
        if (cancelled) return;
        // Only well-formed upcoming events of a class we can link to make it onto the rail.
        setEvents(Array.isArray(data) ? data.filter((e) => e && e.id && e.title && e.startsAt && e.classSlug) : []);
      })
      .catch(() => { if (!cancelled) setEvents([]); });
    return () => { cancelled = true; };
  }, [user?.id, authLoading]);

  // Debounced hero search: the server matches title/description (q, <= 100 chars).
  useEffect(() => {
    const next = searchInput.trim().slice(0, SEARCH_MAX_LENGTH);
    if (next === query) return;
    const timer = window.setTimeout(() => setQuery(next), SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [searchInput, query]);

  const submitSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setQuery(searchInput.trim().slice(0, SEARCH_MAX_LENGTH));
  };

  const openAll = () => {
    setShowAll(true);
    // The rails collapse into the catalog; bring the top of the page (search + catalog header) back into view.
    window.requestAnimationFrame(() => document.getElementById('classes-top')?.scrollIntoView?.({ block: 'start' }));
  };

  const backHome = () => {
    setShowAll(false);
    setSearchInput('');
    setQuery('');
    setFeeFilter('ALL');
  };

  const catalogMode = showAll || query !== '';
  const visibleClasses = classes.filter((cls) => feeFilter === 'ALL' || (cls.accessType ?? 'FREE') === feeFilter);

  // Rails (home only). Server-sorted when the sort param is supported; sorted here as well so an older server
  // that ignores `sort` still gives the right order.
  const mineRail = user ? classes.filter(isMine).slice(0, RAIL_SIZE) : [];
  const popularRail = [...(popular?.length ? popular : classes)].sort(byPopularity).slice(0, RAIL_SIZE);
  const popularIds = new Set(popularRail.map((c) => c.id));
  const newestCandidates = [...(newest?.length ? newest : classes)].sort(byNewest).slice(0, RAIL_SIZE);
  // With few classes both rails would hold the same cards - show "Mới mở" only when it adds something.
  const newestRail = newestCandidates.some((c) => !popularIds.has(c.id)) ? newestCandidates : [];

  // Full name: the last word alone misreads compound given names ("Lê Tự Do" -> "Do").
  const heroTitle = user?.fullName?.trim() ? `Chào ${user.fullName.trim()}, hôm nay bạn học gì?` : user ? 'Chào bạn, hôm nay bạn học gì?' : 'Tìm đúng lớp học để đi xa hơn.';
  const heroSub = 'Người dẫn dắt rõ mặt, hoạt động thật, lộ trình phù hợp — bạn không đi một mình.';

  return (
    <div id="classes-top" className="w-full">
      {/* Hero: one message about the person, one search (the only primary button on screen). */}
      <section className={`px-4 text-center sm:px-8 ${catalogMode ? 'pb-8 pt-8 sm:pb-10 sm:pt-12' : 'pb-8 pt-6 sm:pb-[72px] sm:pt-[88px]'}`}>
        {!catalogMode && (
          <>
            <h1 className="mx-auto max-w-[720px] text-[23px] font-semibold leading-[30px] tracking-[-0.4px] text-slate-900 sm:text-display">
              {heroTitle}
            </h1>
            <p className="mx-auto mt-2 max-w-[560px] text-meta text-slate-600 sm:mt-4 sm:text-body">{heroSub}</p>
          </>
        )}
        <form
          role="search"
          onSubmit={submitSearch}
          className={`mx-auto flex max-w-[600px] items-center gap-2 rounded-2xl border border-slate-200 bg-white p-1.5 shadow-hairline focus-within:border-blue-600 ${catalogMode ? '' : 'mt-5 sm:mt-8'}`}
        >
          <Search className="ml-2.5 h-5 w-5 flex-shrink-0 text-slate-600 sm:ml-3.5" strokeWidth={1.5} aria-hidden="true" />
          <label htmlFor={searchId} className="sr-only">Tìm lớp học</label>
          <input
            id={searchId}
            type="search"
            value={searchInput}
            maxLength={SEARCH_MAX_LENGTH}
            onChange={(e) => setSearchInput(e.target.value)}
            placeholder="Tìm lớp học, chủ đề, người dẫn dắt…"
            className="min-w-0 flex-1 border-none bg-transparent px-1 py-2.5 text-body-sm text-slate-900 outline-none placeholder:text-slate-400 focus:outline-none focus-visible:outline-none"
          />
          <button type="submit" className={buttonClass('primary', 'lg', 'h-10 rounded-[10px] px-4 text-ui sm:h-11 sm:px-[22px] sm:text-body-sm')}>
            Tìm kiếm
          </button>
        </form>
      </section>

      <div className="mx-auto w-full max-w-container px-4 pb-16 sm:px-8 sm:pb-24">
        {catalogMode ? (
          <section aria-labelledby="catalog-title">
            <button
              type="button"
              onClick={backHome}
              className="mb-4 inline-flex items-center gap-1.5 text-ui font-medium text-slate-600 transition-colors duration-micro hover:text-slate-900"
            >
              <ArrowLeft className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
              Về trang chủ
            </button>
            <div className="mb-6 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
              <div className="min-w-0">
                <h2 id="catalog-title" className="truncate text-h2-sm font-semibold text-slate-900 sm:text-h2">
                  {query ? `Kết quả cho “${query}”` : 'Tất cả lớp học'}
                </h2>
                <p className="mt-1 text-ui text-slate-600">
                  {loading ? 'Đang tải…' : <span className="tabular">{visibleClasses.length.toLocaleString('vi-VN')}{hasMore ? '+' : ''} lớp học</span>}
                </p>
              </div>
              {/* D-19: fee filter chips (client-side, on the classes already loaded) */}
              <div role="group" aria-label="Lọc theo học phí" className="flex items-center gap-2">
                {FEE_FILTERS.map((chip) => (
                  <FilterChip key={chip.key} selected={feeFilter === chip.key} onClick={() => setFeeFilter(chip.key)}>
                    {chip.label}
                  </FilterChip>
                ))}
              </div>
            </div>

            {loading && <LoadingSpinner message="Đang tải danh sách lớp..." />}
            {error && <ErrorBanner message={error} onRetry={fetchClasses} />}

            {!loading && !error && classes.length === 0 && (
              query ? (
                <EmptyState
                  icon={<Search className="h-6 w-6" strokeWidth={1.7} />}
                  title="Không tìm thấy lớp phù hợp"
                  description="Thử một từ khóa ngắn hơn, hoặc xem tất cả lớp học đang mở."
                  actionText="Xóa tìm kiếm"
                  onAction={() => { setSearchInput(''); setQuery(''); }}
                />
              ) : (
                <EmptyState
                  icon={<BookOpen className="h-6 w-6" strokeWidth={1.7} />}
                  title="Chưa có lớp học nào"
                  description={user ? 'Hãy tạo lớp học đầu tiên ngay bây giờ!' : 'Khi có lớp mở, chúng sẽ hiện ở đây.'}
                  actionText={user ? 'Tạo lớp học' : undefined}
                  onAction={user ? () => navigate('/classes/new') : undefined}
                />
              )
            )}

            {!loading && !error && classes.length > 0 && visibleClasses.length === 0 && (
              <div className="rounded-card border border-slate-200 bg-white p-10 text-center shadow-hairline" data-testid="filter-empty">
                <h3 className="text-h3 font-semibold text-slate-900">Không có lớp nào phù hợp bộ lọc</h3>
                <p className="mt-1 text-ui text-slate-600">
                  Bộ lọc chỉ áp dụng cho các lớp đã tải{hasMore ? '; hãy bấm "Xem thêm lớp học" để tải thêm.' : '.'}
                </p>
                <button type="button" onClick={() => setFeeFilter('ALL')} className={buttonClass('secondary', 'md', 'mt-5')}>
                  Xem tất cả lớp
                </button>
              </div>
            )}

            {!loading && (
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 sm:gap-6 lg:grid-cols-3">
                {visibleClasses.map((cls) => <ClassCard key={cls.id} cls={cls} />)}
              </div>
            )}

            {hasMore && !loading && (
              <div className="mt-8 flex justify-center">
                <button type="button" onClick={loadMoreClasses} disabled={loadingMore} className={buttonClass('secondary', 'md')}>
                  {loadingMore ? 'Đang tải...' : 'Xem thêm lớp học'}
                </button>
              </div>
            )}
          </section>
        ) : (
          <div className="space-y-10 sm:space-y-[72px]">
            {loading && <LoadingSpinner message="Đang tải danh sách lớp..." />}
            {error && <ErrorBanner message={error} onRetry={fetchClasses} />}

            {!loading && !error && classes.length === 0 && (
              <EmptyState
                icon={<BookOpen className="h-6 w-6" strokeWidth={1.7} />}
                title="Chưa có lớp học nào"
                description={user ? 'Hãy tạo lớp học đầu tiên ngay bây giờ!' : 'Khi có lớp mở, chúng sẽ hiện ở đây.'}
                  actionText={user ? 'Tạo lớp học' : undefined}
                  onAction={user ? () => navigate('/classes/new') : undefined}
              />
            )}

            {!loading && !error && classes.length > 0 && (
              <>
                {mineRail.length > 0 && (
                  <Rail id="rail-mine" title="Lớp của bạn" description="Những lớp bạn đang dẫn dắt hoặc theo học." onSeeAll={openAll} seeAllLabel="Xem tất cả lớp của bạn">
                    {mineRail.map((cls) => <ClassCard key={cls.id} cls={cls} className={RAIL_ITEM} />)}
                  </Rail>
                )}

                {popularRail.length > 0 && <Rail id="rail-popular" title="Phổ biến" description="Nhiều thành viên tham gia và học cùng nhau nhất." onSeeAll={openAll} seeAllLabel="Xem tất cả lớp phổ biến">
                  {popularRail.map((cls) => <ClassCard key={cls.id} cls={cls} className={RAIL_ITEM} />)}
                </Rail>}
              </>
            )}

            {events.length > 0 && (
              <Rail id="rail-events" title="Sự kiện sắp tới" description="Buổi học và gặp gỡ do chính người dẫn dắt lớp tổ chức.">
                {events.slice(0, RAIL_SIZE).map((event) => <EventCard key={event.id} event={event} className={RAIL_ITEM} />)}
              </Rail>
            )}

            {!loading && !error && newestRail.length > 0 && (
              <Rail id="rail-newest" title="Mới mở" description="Lớp học vừa mở cửa — vào sớm để cùng định hình nhịp lớp." onSeeAll={openAll} seeAllLabel="Xem tất cả lớp mới mở">
                {newestRail.map((cls) => <ClassCard key={cls.id} cls={cls} className={RAIL_ITEM} />)}
              </Rail>
            )}

            {!loading && !error && classes.length > 0 && (
              <div className="flex justify-center">
                <button type="button" onClick={openAll} className={buttonClass('secondary', 'md')}>
                  Xem tất cả lớp học
                </button>
              </div>
            )}

            {/* Creator band (dark panel) - its CTA opens the existing create-class dialog. */}
            <section aria-labelledby="creator-title" className="flex flex-col items-start gap-5 rounded-section bg-slate-900 px-5 py-6 sm:flex-row sm:items-center sm:justify-between sm:gap-8 sm:px-12 sm:py-11">
              <div className="max-w-[620px]">
                <h2 id="creator-title" className="text-h3 font-semibold text-white sm:text-h2">Bạn có kiến thức muốn chia sẻ?</h2>
                <p className="mt-1.5 text-meta text-slate-300 sm:mt-2.5 sm:text-body-sm">
                  Tạo lớp học bạn thật sự sở hữu — với thành viên, bài học và kỳ thi của riêng bạn.
                </p>
              </div>
              {user ? (
                <Link to="/classes/new" className={buttonClass('on-dark', 'lg')}>
                  <Plus className="h-4 w-4" strokeWidth={2} aria-hidden="true" />
                  Tạo lớp học mới
                </Link>
              ) : (
                <Link to="/login" state={{ from: { pathname: '/classes/new' } }} className={buttonClass('on-dark', 'lg')}>
                  Đăng nhập để tạo lớp
                </Link>
              )}
            </section>
          </div>
        )}
      </div>

    </div>
  );
};
