import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ChevronRight, RefreshCw } from 'lucide-react';
import { adminApi, AdminOverview as Overview } from '../../api/admin';
import { formatDong } from '../../api/format';
import { Button, Card } from '../../components/ui';
import { ErrorBanner, LoadingSpinner } from '../../components/UIStates';
import { CardHeader, Notice, PageHeader, StudioPage } from '../studio/studioUi';
import { formatToday } from '../studio/StudioOverview';
import { errorText } from './adminUi';

const num = (n: number | undefined | null) => (n ?? 0).toLocaleString('vi-VN');

/** "2026-10-05" (UTC calendar date from the API) -> "05/10" without any time-zone shift. */
const dayLabel = (date: string) => {
  const [, m, d] = date.split('-');
  return d && m ? `${d}/${m}` : date;
};
const fullDayLabel = (date: string) => {
  const [y, m, d] = date.split('-');
  return d && m && y ? `${d}/${m}/${y}` : date;
};

/** KPI tile: label, big tabular number, one line of context. */
const Tile: React.FC<{ label: string; value: string; sub?: React.ReactNode; to?: string }> = ({ label, value, sub, to }) => {
  const body = (
    <>
      <p className="text-meta text-slate-600">{label}</p>
      <p className="mt-1.5 text-h2 font-semibold tracking-[-0.3px] text-slate-900 tabular">{value}</p>
      {sub && <p className="mt-0.5 text-caption text-slate-500 tabular">{sub}</p>}
    </>
  );
  const className = 'block rounded-2xl border border-slate-200 bg-white px-[18px] py-4 shadow-hairline';
  return to ? (
    <Link to={to} className={`${className} transition-colors duration-micro hover:bg-slate-50`}>{body}</Link>
  ) : (
    <div className={className}>{body}</div>
  );
};

const TileGroup: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => (
  <section aria-label={title}>
    <h2 className="mb-3 text-[16px] font-semibold leading-6 text-slate-900">{title}</h2>
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">{children}</div>
  </section>
);

/**
 * Signups of the last 30 days: one series, thin blue bars anchored to the baseline (2px gaps, 4px rounded tops),
 * a hover/focus value per bar, and the same numbers as a table for screen readers and keyboard users.
 */
export const SignupsChart: React.FC<{ days: Overview['signupsByDay'] }> = ({ days }) => {
  const max = Math.max(1, ...days.map((d) => d.count));
  const total = days.reduce((sum, d) => sum + d.count, 0);
  const peak = days.reduce<Overview['signupsByDay'][number] | null>((best, d) => (!best || d.count > best.count ? d : best), null);
  const [showTable, setShowTable] = useState(false);

  return (
    <Card>
      <CardHeader
        title="Người đăng ký mới · 30 ngày"
        description={
          <span className="tabular">
            {num(total)} tài khoản mới{peak && peak.count > 0 ? ` · nhiều nhất ${num(peak.count)} vào ${fullDayLabel(peak.date)}` : ''}
          </span>
        }
        action={
          <Button size="sm" variant="ghost" aria-expanded={showTable} onClick={() => setShowTable((v) => !v)}>
            {showTable ? 'Xem biểu đồ' : 'Xem dạng bảng'}
          </Button>
        }
      />
      {showTable ? (
        <div className="mt-4 max-h-[260px] overflow-y-auto rounded-btn border border-slate-200">
          <table className="w-full text-ui">
            <caption className="sr-only">Số người đăng ký mới theo ngày, 30 ngày gần nhất</caption>
            <thead className="sticky top-0 bg-slate-50">
              <tr>
                <th scope="col" className="px-4 py-2 text-left text-caption font-semibold uppercase tracking-[0.5px] text-slate-500">Ngày</th>
                <th scope="col" className="px-4 py-2 text-right text-caption font-semibold uppercase tracking-[0.5px] text-slate-500">Người đăng ký</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {days.map((d) => (
                <tr key={d.date}>
                  <td className="px-4 py-2 text-slate-600 tabular">{fullDayLabel(d.date)}</td>
                  <td className="px-4 py-2 text-right font-semibold text-slate-900 tabular">{num(d.count)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <figure className="mt-5" aria-label={`Biểu đồ cột: ${num(total)} người đăng ký mới trong 30 ngày`}>
          <div className="relative">
            {/* Recessive grid: top value line + baseline. */}
            <div aria-hidden="true" className="pointer-events-none absolute inset-x-0 top-0 flex items-center gap-2">
              <span className="w-6 text-right text-micro text-slate-500 tabular">{num(max)}</span>
              <span className="h-px flex-1 border-t border-dashed border-slate-200" />
            </div>
            <ul className="ml-8 flex h-[150px] items-end gap-[2px] border-b border-slate-200 pt-2">
              {days.map((d) => (
                <li key={d.date} className="group relative flex h-full flex-1 items-end">
                  <span
                    tabIndex={0}
                    aria-label={`${fullDayLabel(d.date)}: ${d.count} người đăng ký`}
                    className="block w-full rounded-t-[4px] bg-blue-600 outline-none transition-colors duration-micro group-hover:bg-blue-700 focus-visible:ring-2 focus-visible:ring-blue-600 focus-visible:ring-offset-2"
                    style={{ height: d.count > 0 ? `${Math.max(3, (d.count / max) * 100)}%` : '0px' }}
                  />
                  <span
                    role="tooltip"
                    className="pointer-events-none absolute bottom-full left-1/2 z-10 mb-1.5 hidden -translate-x-1/2 whitespace-nowrap rounded-[8px] bg-slate-900 px-2 py-1 text-caption font-semibold text-white shadow-lift tabular group-hover:block group-focus-within:block"
                  >
                    {dayLabel(d.date)} · {num(d.count)}
                  </span>
                </li>
              ))}
            </ul>
            {days.length > 0 && (
              <div aria-hidden="true" className="ml-8 mt-1.5 flex justify-between text-micro text-slate-500 tabular">
                <span>{dayLabel(days[0].date)}</span>
                <span>{dayLabel(days[Math.floor(days.length / 2)].date)}</span>
                <span>{dayLabel(days[days.length - 1].date)}</span>
              </div>
            )}
          </div>
        </figure>
      )}
    </Card>
  );
};

/** Platform dashboard (GET /admin/overview): what needs attention first, then the counts. */
export const AdminOverview: React.FC = () => {
  const [data, setData] = useState<Overview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await adminApi.overview());
    } catch (err) {
      setError(errorText(err, 'Không thể tải số liệu tổng quan.'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const outboxIssue = data && (data.outbox.pending > 0 || data.outbox.deadLetter > 0);
  const attention = data
    ? [
        { label: 'Yêu cầu dữ liệu đang mở', value: data.privacy.openRequests, to: '/admin/privacy' },
        { label: 'Lớp đang tạm khóa', value: data.classes.suspended, to: '/admin/classes?status=SUSPENDED' },
        { label: 'Tài khoản bị khóa', value: data.users.banned, to: '/admin/users?status=BANNED' },
        { label: 'Yêu cầu tham gia lớp chờ duyệt', value: data.members.pendingRequests },
      ]
    : [];

  return (
    <StudioPage>
      <div>
        <p className="text-meta text-slate-500 tabular">{formatToday(new Date())}</p>
        <div className="mt-1.5">
          <PageHeader
            title="Tổng quan nền tảng"
            description="Sức khỏe toàn hệ thống: người dùng, lớp học, nội dung, doanh thu và hàng đợi cần xử lý."
            action={
              <Button onClick={() => void load()} disabled={loading}>
                <RefreshCw className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                Làm mới
              </Button>
            }
          />
        </div>
      </div>

      {error && <ErrorBanner message={error} onRetry={() => void load()} />}
      {loading && !data && <LoadingSpinner message="Đang tải số liệu..." />}

      {data && (
        <>
          {outboxIssue && (
            <Notice tone="warn" role="alert">
              <p className="font-semibold">Hàng đợi sự kiện (outbox) đang bị dồn</p>
              <p className="mt-0.5 tabular">
                {num(data.outbox.pending)} sự kiện chờ gửi · {num(data.outbox.deadLetter)} sự kiện lỗi (dead letter). Email và thông báo có thể đến
                chậm — hãy kiểm tra tiến trình gửi và nhật ký máy chủ.
              </p>
            </Notice>
          )}

          <div className="grid grid-cols-[minmax(0,1fr)] gap-6 xl:grid-cols-[minmax(0,1fr)_320px]">
            <div className="grid min-w-0 grid-cols-[minmax(0,1fr)] gap-6">
              <SignupsChart days={data.signupsByDay ?? []} />

              <TileGroup title="Người dùng">
                <Tile label="Tổng tài khoản" value={num(data.users.total)} sub={`+${num(data.users.newLast7Days)} trong 7 ngày`} to="/admin/users" />
                <Tile label="Đang hoạt động" value={num(data.users.active)} sub={`+${num(data.users.newLast30Days)} trong 30 ngày`} />
                <Tile label="Bị khóa" value={num(data.users.banned)} sub={`${num(data.users.deleted)} đã xóa`} to="/admin/users?status=BANNED" />
                <Tile label="Quản trị nền tảng" value={num(data.users.admins)} to="/admin/users?role=PLATFORM_ADMIN" />
              </TileGroup>

              <TileGroup title="Lớp học">
                <Tile label="Tổng lớp học" value={num(data.classes.total)} sub={`+${num(data.classes.newLast7Days)} trong 7 ngày`} to="/admin/classes" />
                <Tile label="Đang hoạt động" value={num(data.classes.active)} sub={`${num(data.classes.archived)} đã lưu trữ`} />
                <Tile label="Công khai / riêng tư" value={`${num(data.classes.public)} / ${num(data.classes.private)}`} />
                <Tile label="Lớp trả phí" value={num(data.classes.paid)} to="/admin/classes?accessType=PAID" />
              </TileGroup>

              <TileGroup title="Thành viên & nội dung">
                <Tile label="Thành viên đang học" value={num(data.members.activeMemberships)} sub="lượt tham gia lớp" />
                <Tile label="Khóa học" value={num(data.content.courses)} />
                <Tile label="Bài thi đã công bố" value={num(data.content.publishedExams)} />
                <Tile label="Bài blog đã đăng" value={num(data.content.blogPostsPublished)} sub={`${num(data.content.upcomingEvents)} sự kiện sắp tới`} />
              </TileGroup>
            </div>

            <aside className="grid min-w-0 content-start gap-6">
              {/* Revenue: the dark panel of the Studio dashboard. */}
              <section aria-label="Doanh thu 30 ngày" className="rounded-card bg-slate-900 p-[22px]">
                <p className="text-meta font-semibold text-slate-300">Doanh thu 30 ngày</p>
                <p className="mt-2.5 text-[28px] font-semibold leading-9 tracking-[-0.4px] text-white tabular">{formatDong(data.commerce.revenueLast30Days)}</p>
                <p className="mt-0.5 text-caption text-slate-300">Tổng các đơn đã thanh toán, không tính hoàn tiền</p>
                <dl className="mt-4 grid gap-2 border-t border-white/10 pt-3.5 text-meta">
                  <div className="flex items-baseline justify-between gap-3">
                    <dt className="text-slate-300">Đơn đã thanh toán</dt>
                    <dd className="font-semibold text-white tabular">{num(data.commerce.paidOrdersLast30Days)}</dd>
                  </div>
                  <div className="flex items-baseline justify-between gap-3">
                    <dt className="text-slate-300">Đơn chờ thanh toán</dt>
                    <dd className="font-semibold text-white tabular">{num(data.commerce.pendingOrders)}</dd>
                  </div>
                </dl>
              </section>

              <Card padded={false} className="overflow-hidden">
                <CardHeader title="Cần chú ý" className="px-5 pb-2 pt-4" />
                <ul>
                  {attention.map((item) => {
                    const row = (
                      <>
                        <span className="min-w-0 flex-1 text-meta text-slate-600">{item.label}</span>
                        <span className={`text-ui font-semibold tabular ${item.value > 0 ? 'text-slate-900' : 'text-slate-500'}`}>{num(item.value)}</span>
                        {item.to && <ChevronRight className="h-3.5 w-3.5 flex-shrink-0 text-slate-400" strokeWidth={2} aria-hidden="true" />}
                      </>
                    );
                    return (
                      <li key={item.label} className="border-t border-slate-100">
                        {item.to ? (
                          <Link to={item.to} className="flex min-h-[44px] items-center gap-3 px-5 py-2.5 transition-colors duration-micro hover:bg-slate-50">
                            {row}
                          </Link>
                        ) : (
                          <div className="flex min-h-[44px] items-center gap-3 px-5 py-2.5">{row}</div>
                        )}
                      </li>
                    );
                  })}
                </ul>
              </Card>

              <Card>
                <CardHeader title="Hàng đợi sự kiện" description="Outbox gửi email, thông báo và đồng bộ." />
                <dl className="mt-3 grid gap-2 text-meta">
                  <div className="flex items-baseline justify-between gap-3">
                    <dt className="text-slate-600">Chờ gửi</dt>
                    <dd className="font-semibold text-slate-900 tabular">{num(data.outbox.pending)}</dd>
                  </div>
                  <div className="flex items-baseline justify-between gap-3">
                    <dt className="text-slate-600">Lỗi (dead letter)</dt>
                    <dd className={`font-semibold tabular ${data.outbox.deadLetter > 0 ? 'text-red-700' : 'text-slate-900'}`}>{num(data.outbox.deadLetter)}</dd>
                  </div>
                </dl>
                {!outboxIssue && <p className="mt-3 text-caption text-green-800">Không có sự kiện tồn đọng.</p>}
              </Card>
            </aside>
          </div>
        </>
      )}
    </StudioPage>
  );
};
