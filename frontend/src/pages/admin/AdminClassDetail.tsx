import React, { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ExternalLink, LockOpen, PauseCircle } from 'lucide-react';
import { adminApi, AdminClassDetail as Detail } from '../../api/admin';
import { formatDate, formatDateTime } from '../../api/format';
import { Button, Card, buttonClass } from '../../components/ui';
import { ErrorBanner, LoadingSpinner } from '../../components/UIStates';
import { CardHeader, Notice, StudioPage } from '../studio/studioUi';
import { AuditList, BackLink, errorText, InfoItem, Pill, ReasonDialog } from './adminUi';
import { ClassStatusPill, ClassThumb } from './AdminClasses';

/**
 * One class seen by the platform admin: metadata and aggregate counts only (no content bodies), the last audit rows,
 * and Tạm khóa lớp / Mở khóa lớp with a mandatory reason.
 */
export const AdminClassDetail: React.FC = () => {
  const { id = '' } = useParams<{ id: string }>();
  const [data, setData] = useState<Detail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [action, setAction] = useState<'suspend' | 'restore' | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await adminApi.classDetail(id));
    } catch (err) {
      setError(errorText(err, 'Không thể tải thông tin lớp học.'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setDone(null);
    void load();
  }, [id]);

  if (loading && !data) return <LoadingSpinner message="Đang tải lớp học..." />;
  if (!data) {
    return (
      <StudioPage>
        <BackLink to="/admin/classes">Lớp học</BackLink>
        <ErrorBanner message={error || 'Không tìm thấy lớp học.'} onRetry={() => void load()} />
      </StudioPage>
    );
  }

  const suspended = data.status === 'SUSPENDED';
  const counts = data.counts ?? { courses: 0, exams: 0, blogPosts: 0, events: 0, products: 0, paidOrders: 0 };

  const confirm = async (reason: string) => {
    if (action === 'suspend') await adminApi.suspend(data.id, reason);
    else await adminApi.restore(data.id, reason);
    setDone(action === 'suspend'
      ? 'Đã tạm khóa lớp. Thành viên và khách không còn thấy lớp; chủ lớp chỉ xem được ở chế độ chỉ đọc.'
      : 'Đã mở khóa lớp và trả lớp về trạng thái trước khi bị tạm khóa.');
    setAction(null);
    await load();
  };

  return (
    <StudioPage>
      <BackLink to="/admin/classes">Lớp học</BackLink>

      <header className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex min-w-0 items-center gap-4">
          <ClassThumb row={data} size={56} />
          <div className="min-w-0">
            <h1 className="truncate text-h2-sm font-semibold tracking-[-0.3px] text-slate-900 sm:text-h2">{data.title}</h1>
            <p className="truncate text-ui text-slate-600">/{data.slug}</p>
            <div className="mt-1.5 flex flex-wrap gap-1.5">
              <ClassStatusPill status={data.status} />
              <Pill tone={data.visibility === 'PRIVATE' ? 'dark' : 'neutral'}>{data.visibility === 'PRIVATE' ? 'Riêng tư' : 'Công khai'}</Pill>
              <Pill tone={data.accessType === 'PAID' ? 'paid' : 'success'}>{data.accessType === 'PAID' ? 'Trả phí' : 'Miễn phí'}</Pill>
              {data.category && <Pill tone="neutral">{data.category}</Pill>}
            </div>
          </div>
        </div>
        <div className="flex flex-shrink-0 flex-wrap items-center gap-2 sm:pt-1">
          <Link to={`/classes/${data.slug}`} className={buttonClass('secondary', 'md')}>
            <ExternalLink className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
            Mở trang lớp
          </Link>
          {suspended ? (
            <Button variant="primary" onClick={() => setAction('restore')}>
              <LockOpen className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
              Mở khóa lớp
            </Button>
          ) : (
            <Button variant="danger" onClick={() => setAction('suspend')}>
              <PauseCircle className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
              Tạm khóa lớp
            </Button>
          )}
        </div>
      </header>

      {suspended && (
        <Notice tone="warn" role="status">
          <p className="font-semibold">
            Lớp đang tạm khóa{data.suspendedAt ? <span className="tabular"> từ {formatDateTime(data.suspendedAt)}</span> : null}
          </p>
          {data.suspendedReason && <p className="mt-0.5">Lý do: {data.suspendedReason}</p>}
          <p className="mt-0.5">Chỉ chủ lớp còn xem được lớp (chỉ đọc); trang lớp có thể báo "không tìm thấy" với bạn.</p>
        </Notice>
      )}
      {done && <Notice tone="success" role="status">{done}</Notice>}
      {error && <ErrorBanner message={error} onRetry={() => void load()} />}

      <Card>
        <dl className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          <InfoItem label="Chủ lớp">
            <Link to={`/admin/users/${data.owner.id}`} className="hover:text-blue-700">{data.owner.fullName}</Link>
          </InfoItem>
          <InfoItem label="Thành viên">{data.memberCount.toLocaleString('vi-VN')}</InfoItem>
          <InfoItem label="Yêu cầu chờ duyệt">{data.pendingRequestCount.toLocaleString('vi-VN')}</InfoItem>
          <InfoItem label="Ngày tạo">{formatDate(data.createdAt)}</InfoItem>
        </dl>
        <p className="mt-3 truncate text-meta text-slate-600">{data.owner.email}</p>
      </Card>

      <Card>
        <CardHeader title="Nội dung & bán hàng" description="Chỉ số lượng — quản trị nền tảng không xem nội dung, đáp án hay tin nhắn của lớp." />
        <dl className="mt-4 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-6">
          <InfoItem label="Khóa học">{counts.courses.toLocaleString('vi-VN')}</InfoItem>
          <InfoItem label="Bài thi">{counts.exams.toLocaleString('vi-VN')}</InfoItem>
          <InfoItem label="Bài blog">{counts.blogPosts.toLocaleString('vi-VN')}</InfoItem>
          <InfoItem label="Sự kiện">{counts.events.toLocaleString('vi-VN')}</InfoItem>
          <InfoItem label="Sản phẩm">{counts.products.toLocaleString('vi-VN')}</InfoItem>
          <InfoItem label="Đơn đã thanh toán">{counts.paidOrders.toLocaleString('vi-VN')}</InfoItem>
        </dl>
      </Card>

      <Card padded={false} className="overflow-hidden">
        <CardHeader
          title="Nhật ký gần đây"
          description="20 thao tác gần nhất trong lớp này."
          action={<Link to={`/admin/audit?classId=${data.id}`} className="text-meta font-medium text-slate-600 hover:text-slate-900">Xem tất cả</Link>}
          className="px-5 pb-3 pt-4 sm:px-6"
        />
        <div className="border-t border-slate-100">
          <AuditList rows={data.recentAudit ?? []} empty="Chưa có thao tác nào được ghi lại trong lớp này." showClass={false} />
        </div>
      </Card>

      {action === 'suspend' && (
        <ReasonDialog
          title="Tạm khóa lớp học này?"
          description={<>
            <strong className="font-semibold text-slate-900">{data.title}</strong> sẽ bị ẩn với thành viên và khách; không ai tham gia, mua,
            làm bài hay đăng bài mới được. Chủ lớp vẫn xem được lớp ở chế độ chỉ đọc kèm lý do bạn ghi dưới đây.
          </>}
          confirmLabel="Tạm khóa lớp"
          onConfirm={confirm}
          onClose={() => setAction(null)}
        />
      )}
      {action === 'restore' && (
        <ReasonDialog
          title="Mở khóa lớp học này?"
          description={<>
            <strong className="font-semibold text-slate-900">{data.title}</strong> sẽ trở lại trạng thái trước khi bị tạm khóa (đang hoạt động hoặc đã
            lưu trữ).
          </>}
          confirmLabel="Mở khóa lớp"
          tone="primary"
          onConfirm={confirm}
          onClose={() => setAction(null)}
        />
      )}
    </StudioPage>
  );
};
