import React, { useEffect, useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../api/client';
import { formatDateTime } from '../../api/format';
import { Button, Card, FilterChip, Field, Textarea } from '../../components/ui';
import { Modal } from '../../components/Modal';
import { ErrorBanner, LoadingSpinner } from '../../components/UIStates';
import { EmptyRow, ModalActions, Notice, PageHeader, StudioPage } from '../studio/studioUi';
import { errorText, Pill } from './adminUi';

/** A row of GET /privacy/requests (raw column names, as the endpoint returns them). */
export interface PrivacyRequest {
  user_id: string;
  id: string;
  status: string;
  reason?: string | null;
  resolution?: string | null;
  created_at?: string | number | null;
  updated_at?: string | number | null;
}

const STATUS: Record<string, { label: string; tone: 'warn' | 'neutral' | 'danger' | 'success' }> = {
  PENDING: { label: 'Chờ xử lý', tone: 'warn' },
  ON_HOLD: { label: 'Tạm giữ', tone: 'neutral' },
  REJECTED: { label: 'Đã từ chối', tone: 'danger' },
  COMPLETED: { label: 'Đã hoàn tất', tone: 'success' },
};

const RESOLUTIONS: { status: string; label: string }[] = [
  { status: 'ON_HOLD', label: 'Tạm giữ có căn cứ' },
  { status: 'REJECTED', label: 'Từ chối có lý do' },
  { status: 'COMPLETED', label: 'Ẩn danh hóa và đóng tài khoản' },
];

const isOpen = (r: PrivacyRequest) => r.status !== 'COMPLETED' && r.status !== 'REJECTED';
const when = (value?: string | number | null) => {
  if (value === null || value === undefined || value === '') return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : formatDateTime(date.toISOString());
};

const RequestCard: React.FC<{ request: PrivacyRequest; busy: boolean; onResolve: (r: PrivacyRequest, status: string, resolution: string) => void }> = ({
  request, busy, onResolve,
}) => {
  const [resolution, setResolution] = useState('');
  const fieldId = useId();
  const status = STATUS[request.status] ?? { label: request.status, tone: 'neutral' as const };
  const created = when(request.created_at);
  return (
    <li className="space-y-3 px-5 py-4 sm:px-6">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <Pill tone={status.tone} title={request.status}>{status.label}</Pill>
        <Link to={`/admin/users/${request.user_id}`} className="break-all text-ui font-semibold text-slate-900 hover:text-blue-700">
          Người dùng {request.user_id}
        </Link>
        {created && <span className="text-meta text-slate-600 tabular">· gửi lúc {created}</span>}
      </div>
      <p className="break-words text-ui text-slate-600">
        {request.reason ? <>Ghi chú của người dùng: <span className="text-slate-900">{request.reason}</span></> : 'Người dùng không để lại ghi chú.'}
      </p>
      {request.resolution && (
        <p className="break-words rounded-btn bg-slate-50 px-3 py-2 text-meta text-slate-600">Kết quả đã ghi: {request.resolution}</p>
      )}
      {request.status !== 'COMPLETED' && (
        <div className="space-y-2.5">
          <Field label="Kết quả và căn cứ lưu trữ" htmlFor={fieldId} hint="Người dùng sẽ đọc được nội dung này trong mục dữ liệu cá nhân của họ.">
            <Textarea id={fieldId} rows={2} maxLength={2000} value={resolution} onChange={(e) => setResolution(e.target.value)} className="resize-none" />
          </Field>
          <div className="flex flex-wrap gap-2">
            {RESOLUTIONS.map((r) => (
              <Button
                key={r.status}
                size="sm"
                variant={r.status === 'COMPLETED' ? 'danger' : 'secondary'}
                disabled={busy || !resolution.trim()}
                onClick={() => onResolve(request, r.status, resolution.trim())}
              >
                {r.label}
              </Button>
            ))}
          </div>
        </div>
      )}
    </li>
  );
};

/**
 * Yêu cầu dữ liệu: the PLATFORM_ADMIN queue of privacy requests (moved here from DataRightsPanel). Same API:
 * GET /privacy/requests, PUT /privacy/requests/{userId} { status, resolution }.
 */
export const AdminPrivacy: React.FC = () => {
  const [requests, setRequests] = useState<PrivacyRequest[] | null>(null);
  const [filter, setFilter] = useState<'open' | 'all'>('open');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<{ tone: 'success' | 'warn'; text: string } | null>(null);
  const [pendingErase, setPendingErase] = useState<{ request: PrivacyRequest; resolution: string } | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setRequests(await api.get<PrivacyRequest[]>('/privacy/requests'));
    } catch (err) {
      setError(errorText(err, 'Không thể tải yêu cầu dữ liệu.'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const resolve = async (request: PrivacyRequest, status: string, resolution: string) => {
    setBusy(true);
    setMessage(null);
    try {
      await api.put(`/privacy/requests/${request.user_id}`, { status, resolution });
      setMessage({ tone: 'success', text: 'Đã ghi nhận kết quả xử lý.' });
      setPendingErase(null);
      await load();
    } catch (err) {
      setMessage({ tone: 'warn', text: errorText(err, 'Không thể xử lý yêu cầu.') });
      setPendingErase(null);
    } finally {
      setBusy(false);
    }
  };

  const onResolve = (request: PrivacyRequest, status: string, resolution: string) => {
    if (status === 'COMPLETED') setPendingErase({ request, resolution });
    else void resolve(request, status, resolution);
  };

  const all = requests ?? [];
  const visible = filter === 'open' ? all.filter(isOpen) : all;
  const openCount = all.filter(isOpen).length;

  return (
    <StudioPage width="narrow">
      <PageHeader
        title="Yêu cầu dữ liệu"
        description="Yêu cầu xóa tài khoản của người dùng. Kiểm tra nghĩa vụ lưu trữ (chứng từ, đơn hàng) trước khi ẩn danh hóa, và luôn ghi rõ căn cứ."
      />

      <div role="group" aria-label="Lọc yêu cầu" className="flex flex-wrap gap-2">
        <FilterChip selected={filter === 'open'} onClick={() => setFilter('open')}>
          Đang mở <span className="tabular">({openCount})</span>
        </FilterChip>
        <FilterChip selected={filter === 'all'} onClick={() => setFilter('all')}>
          Tất cả <span className="tabular">({all.length})</span>
        </FilterChip>
      </div>

      {message && <Notice tone={message.tone} role="status">{message.text}</Notice>}
      {error && <ErrorBanner message={error} onRetry={() => void load()} />}

      <Card padded={false} className="overflow-hidden">
        {loading && !requests ? (
          <LoadingSpinner message="Đang tải yêu cầu dữ liệu..." />
        ) : visible.length === 0 ? (
          <EmptyRow>{filter === 'open' ? 'Không có yêu cầu nào đang chờ xử lý.' : 'Chưa có yêu cầu dữ liệu nào.'}</EmptyRow>
        ) : (
          <ul aria-label="Yêu cầu dữ liệu" className="divide-y divide-slate-100">
            {visible.map((r) => <RequestCard key={r.id} request={r} busy={busy} onResolve={onResolve} />)}
          </ul>
        )}
      </Card>

      {pendingErase && (
        <Modal title="Ẩn danh hóa và đóng tài khoản?" role="alertdialog" size="md" onClose={busy ? undefined : () => setPendingErase(null)}>
          <p className="text-ui text-slate-600">
            Thông tin cá nhân của người dùng sẽ bị ẩn danh hóa và tài khoản bị đóng. Thao tác này không hoàn tác được. Chứng từ có căn cứ lưu
            trữ vẫn được giữ.
          </p>
          <ModalActions className="mt-5">
            <Button onClick={() => setPendingErase(null)} disabled={busy}>Hủy</Button>
            <Button
              variant="danger"
              disabled={busy}
              onClick={() => void resolve(pendingErase.request, 'COMPLETED', pendingErase.resolution)}
            >
              {busy ? 'Đang xử lý...' : 'Ẩn danh hóa và đóng tài khoản'}
            </Button>
          </ModalActions>
        </Modal>
      )}
    </StudioPage>
  );
};
