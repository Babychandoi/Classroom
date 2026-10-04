import React, { useEffect, useId, useRef, useState } from 'react';
import { Link, useOutletContext } from 'react-router-dom';
import { CalendarPlus, ExternalLink, ImagePlus, Plus, Trash2, Users } from 'lucide-react';
import { COVER_ACCEPT, coverFileProblem, uploadCoverImage } from '../../api/coverUpload';
import { fromDatetimeLocalValue, toDatetimeLocalValue } from '../../api/datetime';
import {
  cancelEvent,
  createEvent,
  deleteEvent,
  formatEventPlace,
  formatEventWhen,
  formatRegistered,
  listClassEvents,
  listEventRegistrants,
  updateEvent,
  type EventInput,
} from '../../api/events';
import { formatDateTime } from '../../api/format';
import { hasStudioPermission } from '../../api/permissions';
import { CancelledBadge, EventTile } from '../../components/EventBits';
import { Modal } from '../../components/Modal';
import { ErrorBanner, LoadingSpinner } from '../../components/UIStates';
import { ModalActions, Notice, PageHeader, StudioPage } from './studioUi';
import { Avatar, Badge, Button, Card, DateBlock, Field, Input, SegmentTabs, Select, Textarea, buttonClass } from '../../components/ui';
import type { ClassEvent, Classroom, EventFormat, EventRegistrant } from '../../types';

// Studio > Sự kiện: upcoming / past lists, create & edit in a modal (title, description, who it is for, takeaways <= 8,
// format, place, meeting link, start / end, capacity, audience, cover image), cancel, delete, and the registrant list.
// datetime-local values are converted with api/datetime.ts so a save never shifts the schedule by the UTC offset (R16-04).

type Scope = 'upcoming' | 'past';
const MAX_TAKEAWAYS = 8;
const WEEK_MS = 7 * 24 * 60 * 60 * 1000;

interface FormState {
  title: string;
  description: string;
  forWhom: string;
  takeaways: string[];
  format: EventFormat;
  location: string;
  meetingUrl: string;
  startsAt: string;
  endsAt: string;
  capacity: string;
  audience: 'PUBLIC' | 'MEMBERS';
  coverMediaId: string | null;
  coverPreview: string | null;
}

const emptyForm = (): FormState => ({
  title: '', description: '', forWhom: '', takeaways: [''], format: 'ONLINE', location: '', meetingUrl: '',
  startsAt: '', endsAt: '', capacity: '', audience: 'MEMBERS', coverMediaId: null, coverPreview: null,
});

const formFromEvent = (e: ClassEvent): FormState => ({
  title: e.title,
  description: e.description ?? '',
  forWhom: e.forWhom ?? '',
  takeaways: e.takeaways?.length ? [...e.takeaways] : [''],
  format: e.format,
  location: e.location ?? '',
  meetingUrl: e.meetingUrl ?? '',
  startsAt: toDatetimeLocalValue(e.startsAt),
  endsAt: toDatetimeLocalValue(e.endsAt),
  capacity: e.capacity ? String(e.capacity) : '',
  audience: e.audience,
  coverMediaId: e.coverMediaId ?? null,
  coverPreview: e.coverUrl ?? null,
});

const messageOf = (err: unknown, fallback: string) => (err instanceof Error && err.message ? err.message : fallback);

/** Client-side checks mirroring the contract; returns the payload or a message to show. */
export function buildEventPayload(form: FormState): { body: EventInput } | { error: string } {
  const title = form.title.trim();
  if (!title) return { error: 'Hãy nhập tên sự kiện.' };
  const startsAt = fromDatetimeLocalValue(form.startsAt);
  const endsAt = fromDatetimeLocalValue(form.endsAt);
  if (!startsAt || !endsAt) return { error: 'Hãy chọn thời gian bắt đầu và kết thúc.' };
  const span = new Date(endsAt).getTime() - new Date(startsAt).getTime();
  if (span <= 0) return { error: 'Thời gian kết thúc phải sau thời gian bắt đầu.' };
  if (span > WEEK_MS) return { error: 'Một sự kiện kéo dài tối đa 7 ngày.' };
  const meetingUrl = form.meetingUrl.trim();
  if (meetingUrl && !/^https?:\/\/\S+$/i.test(meetingUrl)) return { error: 'Link phòng họp phải bắt đầu bằng http:// hoặc https://.' };
  let capacity: number | null = null;
  if (form.capacity.trim()) {
    capacity = Number(form.capacity);
    if (!Number.isInteger(capacity) || capacity < 1 || capacity > 100000) return { error: 'Số chỗ phải là số nguyên từ 1 đến 100.000 (để trống nếu không giới hạn).' };
  }
  const takeaways = form.takeaways.map((t) => t.trim()).filter(Boolean);
  if (takeaways.length > MAX_TAKEAWAYS) return { error: `Tối đa ${MAX_TAKEAWAYS} điều mang về.` };
  const body: EventInput = {
    title,
    description: form.description.trim() || null,
    forWhom: form.forWhom.trim() || null,
    takeaways,
    format: form.format,
    location: form.location.trim() || null,
    meetingUrl: meetingUrl || null,
    startsAt,
    endsAt,
    capacity,
    audience: form.audience,
  };
  if (form.coverMediaId) body.coverMediaId = form.coverMediaId;
  return { body };
}

export const StudioEvents: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom; refreshClassroom: () => Promise<void> }>();
  const can = (action: string) => hasStudioPermission(classroom, 'EVENT', action);
  const canCreate = can('CREATE');
  const canEdit = can('EDIT');
  const canDelete = can('DELETE');
  const canSeeRegistrants = can('VIEW') || canEdit;

  const [scope, setScope] = useState<Scope>('upcoming');
  const [events, setEvents] = useState<ClassEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const [editing, setEditing] = useState<{ id: string | null } | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [confirm, setConfirm] = useState<{ event: ClassEvent; kind: 'cancel' | 'delete' } | null>(null);
  const [registrants, setRegistrants] = useState<{ event: ClassEvent; list: EventRegistrant[] | null; error: string | null } | null>(null);
  const seq = useRef(0);
  const formId = useId();

  const load = async () => {
    const mine = ++seq.current;
    setLoading(true);
    setError(null);
    try {
      const list = await listClassEvents(classroom.id, scope);
      if (mine === seq.current) setEvents(list ?? []);
    } catch (err) {
      if (mine === seq.current) setError(messageOf(err, 'Không thể tải sự kiện.'));
    } finally {
      if (mine === seq.current) setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [classroom.id, scope]);

  const openCreate = () => { setForm(emptyForm()); setFormError(null); setEditing({ id: null }); };
  const openEdit = (e: ClassEvent) => { setForm(formFromEvent(e)); setFormError(null); setEditing({ id: e.id }); };
  const closeForm = () => { if (!saving && !uploading) setEditing(null); };

  const onCover = async (file?: File) => {
    if (!file) return;
    const problem = coverFileProblem(file);
    if (problem) { setFormError(problem); return; }
    setUploading(true);
    setFormError(null);
    try {
      const assetId = await uploadCoverImage(classroom.id, file, 'EVENT');
      let preview: string | null = null;
      try { preview = URL.createObjectURL(file); } catch { preview = null; }
      setForm((current) => ({ ...current, coverMediaId: assetId, coverPreview: preview }));
    } catch (err) {
      setFormError(messageOf(err, 'Không thể tải ảnh bìa.'));
    } finally {
      setUploading(false);
    }
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!editing) return;
    const built = buildEventPayload(form);
    if ('error' in built) { setFormError(built.error); return; }
    setSaving(true);
    setFormError(null);
    try {
      if (editing.id) {
        await updateEvent(editing.id, built.body);
        setNotice('Đã lưu thay đổi sự kiện.');
      } else {
        await createEvent(classroom.id, built.body);
        setNotice('Đã tạo sự kiện. Thành viên có thể đăng ký ngay trong tab Sự kiện.');
      }
      setEditing(null);
      void load();
    } catch (err) {
      setFormError(messageOf(err, 'Không thể lưu sự kiện.'));
    } finally {
      setSaving(false);
    }
  };

  const runConfirm = async () => {
    if (!confirm) return;
    const { event, kind } = confirm;
    setBusyId(event.id);
    setActionError(null);
    setNotice(null);
    try {
      if (kind === 'cancel') {
        const updated = await cancelEvent(event.id);
        setEvents((list) => list.map((e) => (e.id === event.id ? (updated ?? { ...e, status: 'CANCELLED' }) : e)));
        setNotice('Đã hủy sự kiện. Danh sách đăng ký vẫn được giữ.');
      } else {
        await deleteEvent(event.id);
        setEvents((list) => list.filter((e) => e.id !== event.id));
        setNotice('Đã xóa sự kiện.');
      }
    } catch (err) {
      setActionError(messageOf(err, kind === 'cancel' ? 'Không thể hủy sự kiện.' : 'Không thể xóa sự kiện.'));
    } finally {
      setBusyId(null);
      setConfirm(null);
    }
  };

  const openRegistrants = async (event: ClassEvent) => {
    setRegistrants({ event, list: null, error: null });
    try {
      const list = await listEventRegistrants(event.id);
      setRegistrants({ event, list: list ?? [], error: null });
    } catch (err) {
      setRegistrants({ event, list: [], error: messageOf(err, 'Không thể tải danh sách đăng ký.') });
    }
  };

  const setTakeaway = (i: number, value: string) => setForm((f) => ({ ...f, takeaways: f.takeaways.map((t, n) => (n === i ? value : t)) }));

  return (
    <StudioPage>
      <PageHeader
        title="Sự kiện"
        description="Lên lịch buổi trực tuyến hoặc gặp mặt cho lớp và theo dõi ai đã đăng ký."
        action={canCreate ? (
          <Button variant="primary" onClick={openCreate}>
            <Plus className="h-4 w-4" strokeWidth={2} aria-hidden="true" />
            Tạo sự kiện
          </Button>
        ) : undefined}
      />

      <SegmentTabs
        ariaLabel="Thời gian"
        items={[{ key: 'upcoming', label: 'Sắp tới' }, { key: 'past', label: 'Đã qua' }]}
        value={scope}
        onChange={(key) => setScope(key as Scope)}
      />

      {notice && <Notice tone="success" role="status">{notice}</Notice>}
      {actionError && <ErrorBanner message={actionError} />}

      <Card padded={false}>
        {loading ? (
          <LoadingSpinner message="Đang tải sự kiện..." />
        ) : error ? (
          <div className="p-5"><ErrorBanner message={error} onRetry={load} /></div>
        ) : events.length === 0 ? (
          <div className="flex flex-col items-center p-10 text-center">
            <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-community bg-slate-100 text-slate-500">
              <CalendarPlus className="h-6 w-6" strokeWidth={1.7} aria-hidden="true" />
            </div>
            <h2 className="text-h3 font-semibold text-slate-900">{scope === 'upcoming' ? 'Chưa có sự kiện sắp tới' : 'Chưa có sự kiện đã diễn ra'}</h2>
            <p className="mt-1 max-w-sm text-ui text-slate-600">Một buổi hỏi đáp 60 phút mỗi tuần giữ nhịp cho cả lớp — tạo xong, thành viên đăng ký ngay trong tab Sự kiện.</p>
            {canCreate && scope === 'upcoming' && <Button className="mt-5" onClick={openCreate}>Tạo sự kiện đầu tiên</Button>}
          </div>
        ) : (
          <ul className="divide-y divide-slate-100">
            {events.map((event) => (
              <li key={event.id} className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:gap-4 sm:px-6">
                <div className="flex min-w-0 flex-1 items-start gap-4">
                  <DateBlock date={new Date(event.startsAt)} />
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <p className="truncate text-ui font-semibold text-slate-900">{event.title}</p>
                      {event.status === 'CANCELLED' && <CancelledBadge />}
                      {event.audience === 'MEMBERS' ? <Badge tone="member" size="sm">Thành viên</Badge> : <Badge tone="neutral" size="sm">Công khai</Badge>}
                    </div>
                    <p className="mt-0.5 text-meta text-slate-600 tabular">{formatEventWhen(event)} · {formatEventPlace(event)}</p>
                    <p className="text-meta text-slate-500 tabular">{formatRegistered(event)} · chủ trì {event.host.fullName}</p>
                  </div>
                </div>
                <div className="flex flex-shrink-0 flex-wrap items-center gap-2 sm:justify-end">
                  <Link to={`/classes/${classroom.slug}/events/${event.id}`} className={buttonClass('ghost', 'sm')} aria-label={`Xem trang sự kiện ${event.title}`}>
                    <ExternalLink className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />Xem
                  </Link>
                  {canSeeRegistrants && (
                    <Button size="sm" onClick={() => void openRegistrants(event)} aria-label={`Người đăng ký ${event.title}`}>
                      <Users className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />Người đăng ký
                    </Button>
                  )}
                  {canEdit && <Button size="sm" onClick={() => openEdit(event)} aria-label={`Sửa sự kiện ${event.title}`}>Sửa</Button>}
                  {canEdit && event.status === 'SCHEDULED' && scope === 'upcoming' && (
                    <Button size="sm" disabled={busyId === event.id} onClick={() => setConfirm({ event, kind: 'cancel' })} aria-label={`Hủy sự kiện ${event.title}`}>Hủy</Button>
                  )}
                  {canDelete && (
                    <Button size="sm" variant="danger" disabled={busyId === event.id} onClick={() => setConfirm({ event, kind: 'delete' })} aria-label={`Xóa sự kiện ${event.title}`}>Xóa</Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {editing && (
        <Modal title={editing.id ? 'Sửa sự kiện' : 'Tạo sự kiện'} onClose={closeForm} size="lg">
          <form onSubmit={submit} className="space-y-4" noValidate>
            <Field label="Tên sự kiện" htmlFor={`${formId}-title`}>
              <Input id={`${formId}-title`} required maxLength={200} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
            </Field>
            <Field label="Mô tả" htmlFor={`${formId}-desc`} hint="Buổi này diễn ra thế nào, cần chuẩn bị gì.">
              <Textarea id={`${formId}-desc`} rows={4} maxLength={20000} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
            </Field>
            <Field label="Dành cho ai" htmlFor={`${formId}-for`}>
              <Input id={`${formId}-for`} maxLength={500} placeholder="Ví dụ: người mới bắt đầu, đã xong Bài 3" value={form.forWhom} onChange={(e) => setForm({ ...form, forWhom: e.target.value })} />
            </Field>
            <fieldset className="space-y-2">
              <legend className="text-meta font-semibold text-slate-900">Bạn mang về gì <span className="font-normal text-slate-500">(tối đa {MAX_TAKEAWAYS})</span></legend>
              {form.takeaways.map((t, i) => (
                <div key={i} className="flex items-center gap-2">
                  <Input
                    aria-label={`Điều mang về ${i + 1}`}
                    maxLength={200}
                    value={t}
                    onChange={(e) => setTakeaway(i, e.target.value)}
                  />
                  <button
                    type="button"
                    onClick={() => setForm((f) => ({ ...f, takeaways: f.takeaways.length > 1 ? f.takeaways.filter((_, n) => n !== i) : [''] }))}
                    aria-label={`Xóa điều mang về ${i + 1}`}
                    className="inline-flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-btn text-slate-500 hover:bg-slate-100 hover:text-slate-900"
                  >
                    <Trash2 className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                  </button>
                </div>
              ))}
              <Button
                size="sm"
                variant="ghost"
                disabled={form.takeaways.length >= MAX_TAKEAWAYS}
                onClick={() => setForm((f) => ({ ...f, takeaways: [...f.takeaways, ''] }))}
              >
                <Plus className="h-4 w-4" strokeWidth={2} aria-hidden="true" />Thêm một điều
              </Button>
            </fieldset>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Hình thức" htmlFor={`${formId}-format`}>
                <Select id={`${formId}-format`} value={form.format} onChange={(e) => setForm({ ...form, format: e.target.value as EventFormat })}>
                  <option value="ONLINE">Trực tuyến</option>
                  <option value="OFFLINE">Trực tiếp</option>
                </Select>
              </Field>
              <Field label={form.format === 'ONLINE' ? 'Nền tảng' : 'Địa điểm'} htmlFor={`${formId}-location`}>
                <Input
                  id={`${formId}-location`}
                  maxLength={300}
                  placeholder={form.format === 'ONLINE' ? 'Ví dụ: Zoom, Google Meet' : 'Địa chỉ nơi gặp mặt'}
                  value={form.location}
                  onChange={(e) => setForm({ ...form, location: e.target.value })}
                />
              </Field>
            </div>
            <Field label="Link phòng họp" htmlFor={`${formId}-url`} hint="Chỉ người đã đăng ký và người quản lý nhìn thấy.">
              <Input id={`${formId}-url`} type="url" inputMode="url" placeholder="https://" value={form.meetingUrl} onChange={(e) => setForm({ ...form, meetingUrl: e.target.value })} />
            </Field>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Bắt đầu" htmlFor={`${formId}-start`}>
                <Input id={`${formId}-start`} type="datetime-local" required value={form.startsAt} onChange={(e) => setForm({ ...form, startsAt: e.target.value })} />
              </Field>
              <Field label="Kết thúc" htmlFor={`${formId}-end`}>
                <Input id={`${formId}-end`} type="datetime-local" required value={form.endsAt} onChange={(e) => setForm({ ...form, endsAt: e.target.value })} />
              </Field>
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Số chỗ" htmlFor={`${formId}-cap`} hint="Để trống nếu không giới hạn.">
                <Input id={`${formId}-cap`} type="number" min={1} max={100000} inputMode="numeric" value={form.capacity} onChange={(e) => setForm({ ...form, capacity: e.target.value })} />
              </Field>
              <Field label="Ai được đăng ký" htmlFor={`${formId}-aud`}>
                <Select id={`${formId}-aud`} value={form.audience} onChange={(e) => setForm({ ...form, audience: e.target.value as 'PUBLIC' | 'MEMBERS' })}>
                  <option value="MEMBERS">Chỉ thành viên lớp</option>
                  <option value="PUBLIC">Mọi người xem được lớp</option>
                </Select>
              </Field>
            </div>
            <div className="space-y-1.5">
              <span className="block text-meta font-semibold text-slate-900">Ảnh bìa</span>
              <div className="flex flex-wrap items-center gap-4">
                <div className="h-[72px] w-32 overflow-hidden rounded-thumb border border-slate-200 bg-slate-100">
                  {form.coverPreview
                    ? <img src={form.coverPreview} alt="Xem trước ảnh bìa" className="h-full w-full object-cover" />
                    : <div aria-hidden="true" className="flex h-full items-center justify-center text-slate-400"><ImagePlus className="h-5 w-5" strokeWidth={1.5} /></div>}
                </div>
                <label className={buttonClass('secondary', 'md', uploading ? 'pointer-events-none opacity-60' : 'cursor-pointer')}>
                  <ImagePlus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                  {uploading ? 'Đang tải ảnh...' : form.coverMediaId ? 'Đổi ảnh bìa' : 'Chọn ảnh bìa'}
                  <input
                    type="file"
                    accept={COVER_ACCEPT}
                    aria-label="Tải ảnh bìa"
                    className="sr-only"
                    disabled={uploading}
                    onChange={(e) => { void onCover(e.target.files?.[0]); e.target.value = ''; }}
                  />
                </label>
              </div>
              <p className="text-caption text-slate-500">JPG, PNG, WebP hoặc GIF, tối đa 5 MB.</p>
            </div>
            {formError && <p role="alert" className="rounded-btn bg-red-50 px-3.5 py-2.5 text-meta text-red-700">{formError}</p>}
            <ModalActions className="border-t border-slate-100 pt-4">
              <Button onClick={closeForm} disabled={saving || uploading}>Hủy</Button>
              <Button type="submit" variant="primary" disabled={saving || uploading}>
                {saving ? 'Đang lưu...' : editing.id ? 'Lưu thay đổi' : 'Tạo sự kiện'}
              </Button>
            </ModalActions>
          </form>
        </Modal>
      )}

      {confirm && (
        <Modal title={confirm.kind === 'cancel' ? 'Hủy sự kiện?' : 'Xóa sự kiện?'} onClose={() => setConfirm(null)} size="sm" role="alertdialog">
          <p className="text-ui text-slate-600">
            {confirm.kind === 'cancel'
              ? `“${confirm.event.title}” sẽ hiện nhãn Đã hủy và không nhận đăng ký nữa. Danh sách người đã đăng ký vẫn được giữ.`
              : `“${confirm.event.title}” và toàn bộ lượt đăng ký sẽ bị xóa vĩnh viễn.`}
          </p>
          <ModalActions className="mt-5">
            <Button onClick={() => setConfirm(null)}>Giữ lại</Button>
            <Button variant="danger" onClick={() => void runConfirm()} disabled={busyId === confirm.event.id}>
              {confirm.kind === 'cancel' ? 'Hủy sự kiện' : 'Xóa sự kiện'}
            </Button>
          </ModalActions>
        </Modal>
      )}

      {registrants && (
        <Modal title="Người đăng ký" onClose={() => setRegistrants(null)} size="md">
          <div className="-mt-2 mb-4 flex items-center gap-3">
            <div className="h-10 w-16 flex-shrink-0 overflow-hidden rounded-[8px]"><EventTile event={registrants.event} label={false} iconSize={14} /></div>
            <p className="min-w-0 text-meta text-slate-600"><span className="font-semibold text-slate-900">{registrants.event.title}</span> · {formatRegistered(registrants.event)}</p>
          </div>
          {registrants.list === null ? (
            <LoadingSpinner message="Đang tải danh sách..." />
          ) : registrants.error ? (
            <ErrorBanner message={registrants.error} onRetry={() => void openRegistrants(registrants.event)} />
          ) : registrants.list.length === 0 ? (
            <p className="rounded-btn bg-slate-50 px-4 py-6 text-center text-ui text-slate-600">Chưa có ai đăng ký. Chia sẻ trang sự kiện trong Thảo luận để mọi người biết.</p>
          ) : (
            <ul className="divide-y divide-slate-100">
              {registrants.list.map((r) => (
                <li key={r.user.id} className="flex items-center gap-3 py-2.5">
                  <Avatar name={r.user.fullName} src={r.user.avatarUrl} size={32} />
                  <span className="min-w-0 flex-1 truncate text-ui font-medium text-slate-900">{r.user.fullName}</span>
                  <span className="flex-shrink-0 text-caption text-slate-500 tabular">{formatDateTime(r.registeredAt)}</span>
                </li>
              ))}
            </ul>
          )}
          <ModalActions className="mt-5">
            <Button onClick={() => setRegistrants(null)}>Đóng</Button>
          </ModalActions>
        </Modal>
      )}
    </StudioPage>
  );
};
