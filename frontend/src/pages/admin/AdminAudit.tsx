import React, { useEffect, useId, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { X } from 'lucide-react';
import { adminApi, AUDIT_ACTION_LABEL, AuditRow, Page } from '../../api/admin';
import { Button, Card, Field, Input } from '../../components/ui';
import { ErrorBanner, LoadingSpinner } from '../../components/UIStates';
import { PageHeader, StudioPage } from '../studio/studioUi';
import { AuditList, errorText, Pager } from './adminUi';

const PAGE_SIZE = 50;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** "2026-10-01" picked in the browser's time zone -> the UTC instant of that local midnight (or 23:59:59.999). */
export const dayStartIso = (day: string) => (DATE.test(day) ? new Date(`${day}T00:00:00`).toISOString() : undefined);
export const dayEndIso = (day: string) => (DATE.test(day) ? new Date(`${day}T23:59:59.999`).toISOString() : undefined);

/** Nhật ký: platform-wide audit (GET /admin/audit), newest first, filtered by action / class / actor / date range. */
export const AdminAudit: React.FC = () => {
  const [params, setParams] = useSearchParams();
  const action = params.get('action') ?? '';
  const classId = params.get('classId') ?? '';
  const actorId = params.get('actorId') ?? '';
  const from = params.get('from') ?? '';
  const to = params.get('to') ?? '';
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0);

  const [draft, setDraft] = useState({ action, classId, from, to });
  const [data, setData] = useState<Page<AuditRow> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const ids = { action: useId(), classId: useId(), from: useId(), to: useId(), list: useId() };

  useEffect(() => setDraft({ action, classId, from, to }), [action, classId, from, to]);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await adminApi.audit({
        action: action || undefined,
        classId: classId || undefined,
        actorId: actorId || undefined,
        from: from ? dayStartIso(from) : undefined,
        to: to ? dayEndIso(to) : undefined,
        page,
        size: PAGE_SIZE,
      }));
    } catch (err) {
      setError(errorText(err, 'Không thể tải nhật ký.'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, [action, classId, actorId, from, to, page]);

  const apply = (event: React.FormEvent) => {
    event.preventDefault();
    if (draft.from && draft.to && draft.from > draft.to) {
      setFormError('Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.');
      return;
    }
    setFormError(null);
    const next = new URLSearchParams();
    if (draft.action.trim()) next.set('action', draft.action.trim().toUpperCase());
    if (draft.classId.trim()) next.set('classId', draft.classId.trim());
    if (actorId) next.set('actorId', actorId);
    if (draft.from) next.set('from', draft.from);
    if (draft.to) next.set('to', draft.to);
    setParams(next);
  };

  const clearActor = () => {
    const next = new URLSearchParams(params);
    next.delete('actorId');
    next.delete('page');
    setParams(next);
  };

  const hasFilter = !!(action || classId || actorId || from || to);

  return (
    <StudioPage>
      <PageHeader title="Nhật ký" description="Mọi thao tác quan trọng trên nền tảng: quản trị, phân quyền, chấm điểm, thanh toán và yêu cầu dữ liệu." />

      <Card>
        <form onSubmit={apply} aria-label="Lọc nhật ký" className="grid gap-3 sm:grid-cols-2 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,1.2fr)_minmax(0,1fr)_minmax(0,1fr)]">
          <Field label="Thao tác" htmlFor={ids.action}>
            <Input
              id={ids.action}
              list={ids.list}
              value={draft.action}
              onChange={(e) => setDraft({ ...draft, action: e.target.value })}
              placeholder="Ví dụ: ADMIN_USER_BAN"
              maxLength={80}
            />
            <datalist id={ids.list}>
              {Object.entries(AUDIT_ACTION_LABEL).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
            </datalist>
          </Field>
          <Field label="Mã lớp" htmlFor={ids.classId}>
            <Input
              id={ids.classId}
              value={draft.classId}
              onChange={(e) => setDraft({ ...draft, classId: e.target.value })}
              placeholder="ID lớp học"
              maxLength={64}
            />
          </Field>
          <Field label="Từ ngày" htmlFor={ids.from}>
            <Input id={ids.from} type="date" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
          </Field>
          <Field label="Đến ngày" htmlFor={ids.to}>
            <Input id={ids.to} type="date" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
          </Field>
          <div className="flex flex-wrap items-center gap-2 sm:col-span-2 lg:col-span-4">
            <Button type="submit" variant="primary">Lọc nhật ký</Button>
            {hasFilter && (
              <Button variant="ghost" onClick={() => { setFormError(null); setParams(new URLSearchParams()); }}>Xóa bộ lọc</Button>
            )}
            {actorId && (
              <span className="inline-flex h-8 items-center gap-1.5 rounded-full bg-slate-100 pl-3 pr-1 text-meta font-medium text-slate-900">
                Người thực hiện: <span className="font-mono text-caption">{actorId.slice(0, 8)}</span>
                <button type="button" onClick={clearActor} aria-label="Bỏ lọc theo người thực hiện" className="inline-flex h-6 w-6 items-center justify-center rounded-full text-slate-600 hover:bg-slate-200">
                  <X className="h-3.5 w-3.5" strokeWidth={2} aria-hidden="true" />
                </button>
              </span>
            )}
            {formError && <p role="alert" className="text-meta font-medium text-red-700">{formError}</p>}
          </div>
        </form>
      </Card>

      {error && <ErrorBanner message={error} onRetry={() => void load()} />}

      <Card padded={false} className="overflow-hidden">
        {loading && !data ? (
          <LoadingSpinner message="Đang tải nhật ký..." />
        ) : data ? (
          <div className={loading ? 'opacity-60' : ''} aria-busy={loading}>
            <AuditList
              rows={data.content}
              empty={hasFilter ? 'Không có mục nhật ký nào khớp bộ lọc.' : 'Chưa có mục nhật ký nào.'}
            />
            {data.totalElements > 0 && (
              <Pager
                page={data.page}
                totalPages={data.totalPages}
                totalElements={data.totalElements}
                unit="mục"
                onPage={(p) => {
                  const next = new URLSearchParams(params);
                  if (p > 0) next.set('page', String(p));
                  else next.delete('page');
                  setParams(next);
                }}
              />
            )}
          </div>
        ) : null}
      </Card>
    </StudioPage>
  );
};
