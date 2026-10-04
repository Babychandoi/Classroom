import React, { useEffect, useId, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Ban, LockOpen, UserCog } from 'lucide-react';
import { adminApi, AdminUserDetail as Detail, AdminUserRole, CLASS_STATUS_LABEL, USER_ROLE_LABEL } from '../../api/admin';
import { formatDate, formatDateTime } from '../../api/format';
import { useAuth } from '../../context/AuthContext';
import { Avatar, Button, Card, ClassAvatar, Field, Select } from '../../components/ui';
import { ErrorBanner, LoadingSpinner } from '../../components/UIStates';
import { CardHeader, EmptyRow, Notice, StudioPage } from '../studio/studioUi';
import { AuditList, BackLink, errorText, InfoItem, Pill, ReasonDialog } from './adminUi';
import { UserRolePill, UserStatusPill } from './AdminUsers';

type Action = 'ban' | 'unban' | 'role';

/**
 * One account: profile facts, owned classes and the last audit rows about them, with Khóa tài khoản / Mở khóa /
 * Đổi vai trò. Each action asks for a reason; the UI never offers banning or demoting yourself (the server refuses
 * it too, 400) and shows the server's own message for the last-admin rule (409).
 */
export const AdminUserDetail: React.FC = () => {
  const { id = '' } = useParams<{ id: string }>();
  const { user: me } = useAuth();
  const [data, setData] = useState<Detail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [action, setAction] = useState<Action | null>(null);
  const [nextRole, setNextRole] = useState<AdminUserRole>('USER');
  const [done, setDone] = useState<string | null>(null);
  const roleId = useId();

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await adminApi.user(id));
    } catch (err) {
      setError(errorText(err, 'Không thể tải thông tin người dùng.'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setDone(null);
    void load();
  }, [id]);

  if (loading && !data) return <LoadingSpinner message="Đang tải người dùng..." />;
  if (!data) {
    return (
      <StudioPage>
        <BackLink to="/admin/users">Người dùng</BackLink>
        <ErrorBanner message={error || 'Không tìm thấy người dùng.'} onRetry={() => void load()} />
      </StudioPage>
    );
  }

  const isSelf = me?.id === data.id;
  const isAdmin = data.role === 'PLATFORM_ADMIN';
  const deleted = data.status === 'DELETED';

  const openRole = () => {
    setNextRole(isAdmin ? 'USER' : 'PLATFORM_ADMIN');
    setAction('role');
  };

  const confirm = async (reason: string) => {
    if (action === 'ban') await adminApi.ban(data.id, reason);
    else if (action === 'unban') await adminApi.unban(data.id, reason);
    else if (action === 'role') await adminApi.setRole(data.id, nextRole, reason);
    const message =
      action === 'ban' ? 'Đã khóa tài khoản và đăng xuất mọi phiên đăng nhập của người này.'
        : action === 'unban' ? 'Đã mở khóa tài khoản.'
          : `Đã đổi vai trò thành "${USER_ROLE_LABEL[nextRole]}".`;
    setAction(null);
    setDone(message);
    await load();
  };

  return (
    <StudioPage>
      <BackLink to="/admin/users">Người dùng</BackLink>

      <header className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex min-w-0 items-center gap-4">
          <Avatar name={data.fullName} src={data.avatarUrl} size={56} />
          <div className="min-w-0">
            <h1 className="truncate text-h2-sm font-semibold tracking-[-0.3px] text-slate-900 sm:text-h2">{data.fullName}</h1>
            <p className="truncate text-ui text-slate-600">{data.email}</p>
            <div className="mt-1.5 flex flex-wrap gap-1.5">
              <UserRolePill role={data.role} />
              <UserStatusPill status={data.status} />
              {isSelf && <Pill tone="info">Bạn</Pill>}
            </div>
          </div>
        </div>
        <div className="flex flex-shrink-0 flex-wrap items-center gap-2 sm:pt-1">
          {data.status === 'BANNED' ? (
            <Button onClick={() => setAction('unban')}>
              <LockOpen className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
              Mở khóa
            </Button>
          ) : (
            <Button variant="danger" onClick={() => setAction('ban')} disabled={isSelf || deleted}>
              <Ban className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
              Khóa tài khoản
            </Button>
          )}
          <Button onClick={openRole} disabled={(isSelf && isAdmin) || deleted}>
            <UserCog className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
            Đổi vai trò
          </Button>
        </div>
      </header>

      {isSelf && (
        <Notice>Đây là tài khoản của bạn: bạn không thể tự khóa hay tự hạ quyền quản trị của chính mình.</Notice>
      )}
      {deleted && <Notice>Tài khoản đã bị xóa nên không thể khóa hay đổi vai trò.</Notice>}
      {done && <Notice tone="success" role="status">{done}</Notice>}
      {error && <ErrorBanner message={error} onRetry={() => void load()} />}

      <Card>
        <dl className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          <InfoItem label="Ngày tạo">{formatDate(data.createdAt)}</InfoItem>
          <InfoItem label="Đăng nhập gần nhất">{formatDateTime(data.lastLoginAt) || '—'}</InfoItem>
          <InfoItem label="Lớp sở hữu">{data.ownedClassCount.toLocaleString('vi-VN')}</InfoItem>
          <InfoItem label="Lớp đang tham gia">{data.membershipCount.toLocaleString('vi-VN')}</InfoItem>
        </dl>
      </Card>

      <Card padded={false} className="overflow-hidden">
        <CardHeader
          title="Lớp sở hữu"
          description="Khóa tài khoản không ảnh hưởng tới các lớp này — dùng Tạm khóa lớp nếu cần."
          className="px-5 pb-3 pt-4 sm:px-6"
        />
        {data.ownedClasses.length === 0 ? (
          <EmptyRow>Người này chưa tạo lớp học nào.</EmptyRow>
        ) : (
          <ul className="divide-y divide-slate-100 border-t border-slate-100">
            {data.ownedClasses.map((c) => (
              <li key={c.id}>
                <Link to={`/admin/classes/${c.id}`} className="flex items-center gap-3 px-5 py-3 transition-colors duration-micro hover:bg-slate-50 sm:px-6">
                  <ClassAvatar title={c.title} seed={c.id} size={32} />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-ui font-semibold text-slate-900">{c.title}</span>
                    <span className="block truncate text-caption text-slate-500">/{c.slug}</span>
                  </span>
                  <Pill tone={c.status === 'ACTIVE' ? 'success' : c.status === 'SUSPENDED' ? 'warn' : 'neutral'}>
                    {CLASS_STATUS_LABEL[c.status] ?? c.status}
                  </Pill>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card padded={false} className="overflow-hidden">
        <CardHeader
          title="Nhật ký gần đây"
          description="20 thao tác gần nhất do người này thực hiện hoặc liên quan tới họ."
          action={<Link to={`/admin/audit?actorId=${data.id}`} className="text-meta font-medium text-slate-600 hover:text-slate-900">Xem tất cả</Link>}
          className="px-5 pb-3 pt-4 sm:px-6"
        />
        <div className="border-t border-slate-100">
          <AuditList rows={data.recentAudit ?? []} empty="Chưa có thao tác nào được ghi lại." />
        </div>
      </Card>

      {action === 'ban' && (
        <ReasonDialog
          title="Khóa tài khoản này?"
          description={<>
            <strong className="font-semibold text-slate-900">{data.fullName}</strong> sẽ bị đăng xuất khỏi mọi thiết bị và không đăng nhập được
            nữa. Lớp học, đơn hàng và nội dung của họ được giữ nguyên.
          </>}
          confirmLabel="Khóa tài khoản"
          onConfirm={confirm}
          onClose={() => setAction(null)}
        />
      )}
      {action === 'unban' && (
        <ReasonDialog
          title="Mở khóa tài khoản này?"
          description={<><strong className="font-semibold text-slate-900">{data.fullName}</strong> sẽ đăng nhập lại được như bình thường.</>}
          confirmLabel="Mở khóa"
          tone="primary"
          onConfirm={confirm}
          onClose={() => setAction(null)}
        />
      )}
      {action === 'role' && (
        <ReasonDialog
          title="Đổi vai trò"
          description={<>Vai trò hiện tại của <strong className="font-semibold text-slate-900">{data.fullName}</strong>: {USER_ROLE_LABEL[data.role] ?? data.role}.</>}
          confirmLabel="Đổi vai trò"
          tone="primary"
          canConfirm={nextRole !== data.role}
          onConfirm={confirm}
          onClose={() => setAction(null)}
        >
          <Field label="Vai trò mới" htmlFor={roleId} hint="Quản trị nền tảng xem được mọi lớp, người dùng và nhật ký của hệ thống.">
            <Select id={roleId} value={nextRole} onChange={(e) => setNextRole(e.target.value as AdminUserRole)}>
              <option value="USER">Thành viên</option>
              <option value="PLATFORM_ADMIN">Quản trị nền tảng</option>
            </Select>
          </Field>
        </ReasonDialog>
      )}
    </StudioPage>
  );
};
