import React, { useCallback, useEffect, useId, useRef, useState } from 'react';
import { ClassInvite, Classroom, InviteStatus } from '../../types';
import { api } from '../../api/client';
import { formatDate } from '../../api/format';
import { ErrorBanner, LoadingSpinner } from '../../components/UIStates';
import { Modal } from '../../components/Modal';
import { Badge, BadgeTone, Button, Card, Field, Input, Select, inputClass } from '../../components/ui';
import { CardHeader, ModalActions, Notice, rowActionClass, thClass } from './studioUi';
import { Check, Copy, Link2, Plus } from 'lucide-react';

const EXPIRY_OPTIONS = [
  { value: '', label: 'Không hết hạn' },
  { value: '1', label: '1 ngày' },
  { value: '7', label: '7 ngày' },
  { value: '30', label: '30 ngày' },
];
const DAY_MS = 24 * 60 * 60 * 1000;
const MAX_USES_LIMIT = 100000;

const STATUS_VIEW: Record<InviteStatus, { label: string; tone: BadgeTone }> = {
  ACTIVE: { label: 'Đang hiệu lực', tone: 'success' },
  REVOKED: { label: 'Đã thu hồi', tone: 'danger' },
  EXPIRED: { label: 'Hết hạn', tone: 'neutral' },
  EXHAUSTED: { label: 'Hết lượt', tone: 'warn' },
};

/** The link a person opens to join: `${origin}/join/${code}` (the page itself - pages/JoinByInvitePage.tsx - is what uses the code). */
export const inviteLink = (code: string) => `${window.location.origin}/join/${code}`;

/** Clipboard API where the browser offers it (secure contexts), otherwise the old select + execCommand('copy') route. */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // fall through to the textarea route (permission denied / insecure context)
  }
  try {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    const ok = typeof document.execCommand === 'function' && document.execCommand('copy');
    area.remove();
    return !!ok;
  } catch {
    return false;
  }
}

/**
 * D-19: "Mời thành viên" on the Studio members page (owner or MEMBER:EDIT). Creates invite links, shows the full link ONCE, lists
 * every invite (only its last 4 characters - the server never gives the code back) and revokes them.
 */
export const InviteManager: React.FC<{ classroom: Classroom }> = ({ classroom }) => {
  const [invites, setInvites] = useState<ClassInvite[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [expiryDays, setExpiryDays] = useState('');
  const [maxUses, setMaxUses] = useState('');
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  // The full code of a just-created invite lives only here, only while the dialog is open.
  const [created, setCreated] = useState<ClassInvite | null>(null);
  const [copied, setCopied] = useState<'idle' | 'done' | 'failed'>('idle');
  const linkInputRef = useRef<HTMLTextAreaElement>(null);
  // The dialogs open while the submit button is disabled (focus has already fallen back to <body>), so the Modal cannot restore focus
  // to its opener; put it back on "Tạo liên kết mời" ourselves once a dialog has closed.
  const createButtonRef = useRef<HTMLButtonElement>(null);
  const restoreFocus = useRef(false);

  const [revokeTarget, setRevokeTarget] = useState<ClassInvite | null>(null);
  const [revoking, setRevoking] = useState(false);
  const [revokeError, setRevokeError] = useState<string | null>(null);

  const expiryId = useId();
  const maxUsesId = useId();
  const linkId = useId();
  const isPublic = classroom.visibility !== 'PRIVATE';

  const load = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.get<ClassInvite[]>(`/classes/${classroom.id}/invites`);
      setInvites(data || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải danh sách liên kết mời');
    } finally {
      setLoading(false);
    }
  }, [classroom.id]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!created && !revokeTarget && restoreFocus.current) {
      restoreFocus.current = false;
      createButtonRef.current?.focus();
    }
  }, [created, revokeTarget]);

  const parsedMaxUses = maxUses.trim() === '' ? null : /^\d+$/.test(maxUses.trim()) ? Number(maxUses.trim()) : NaN;
  const maxUsesInvalid = parsedMaxUses !== null && (!Number.isInteger(parsedMaxUses) || parsedMaxUses < 1 || parsedMaxUses > MAX_USES_LIMIT);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (maxUsesInvalid) {
      setCreateError(`Số lượt dùng phải là số nguyên từ 1 đến ${MAX_USES_LIMIT.toLocaleString('vi-VN')}, hoặc để trống nếu không giới hạn.`);
      return;
    }
    setCreating(true);
    setCreateError(null);
    try {
      const body: { expiresAt?: string; maxUses?: number } = {};
      if (expiryDays) body.expiresAt = new Date(Date.now() + Number(expiryDays) * DAY_MS).toISOString();
      if (parsedMaxUses !== null) body.maxUses = parsedMaxUses;
      const invite = await api.post<ClassInvite>(`/classes/${classroom.id}/invites`, body);
      setCopied('idle');
      setCreated(invite);
      // Enable the button again right away: the dialog hands focus back to it when it closes, and a disabled button cannot take focus.
      setCreating(false);
      await load();
    } catch (err: any) {
      setCreateError(err.message || 'Không thể tạo liên kết mời');
    } finally {
      setCreating(false);
    }
  };

  const closeCreated = () => {
    // Drop the code from memory: the list only ever has the last 4 characters.
    restoreFocus.current = true;
    setCreated(null);
    setCopied('idle');
  };

  const handleCopy = async () => {
    if (!created?.code) return;
    const ok = await copyText(inviteLink(created.code));
    if (!ok) linkInputRef.current?.select();
    setCopied(ok ? 'done' : 'failed');
  };

  const handleRevoke = async () => {
    if (!revokeTarget) return;
    setRevoking(true);
    setRevokeError(null);
    try {
      await api.delete(`/classes/${classroom.id}/invites/${revokeTarget.id}`);
      restoreFocus.current = true; // the row's own button is gone once it is revoked
      setRevokeTarget(null);
      await load();
    } catch (err: any) {
      setRevokeError(err.message || 'Không thể thu hồi liên kết mời');
    } finally {
      setRevoking(false);
    }
  };

  return (
    <>
    <Card as="section" aria-labelledby="invite-section-title" className="space-y-5">
      <CardHeader
        id="invite-section-title"
        icon={<Link2 className="h-[18px] w-[18px] text-slate-500" strokeWidth={1.75} aria-hidden="true" />}
        title="Mời thành viên"
        description={isPublic
          ? 'Lớp đang công khai nên liên kết mời chỉ là tùy chọn (ai cũng có thể vào lớp từ danh sách khám phá). Liên kết vẫn hữu ích để gửi trực tiếp cho một nhóm người.'
          : 'Lớp đang riêng tư: liên kết mời là cách duy nhất để người mới vào lớp. Mỗi liên kết có thể đặt hạn dùng và số lượt dùng tối đa.'}
      />

      <form onSubmit={handleCreate} noValidate className="grid gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
        <Field label="Hạn dùng" htmlFor={expiryId}>
          <Select id={expiryId} value={expiryDays} onChange={(e) => setExpiryDays(e.target.value)}>
            {EXPIRY_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </Select>
        </Field>
        <Field label="Số lượt dùng tối đa" htmlFor={maxUsesId}>
          <Input
            id={maxUsesId}
            type="number"
            inputMode="numeric"
            min={1}
            max={MAX_USES_LIMIT}
            value={maxUses}
            onChange={(e) => setMaxUses(e.target.value)}
            placeholder="Không giới hạn"
            className="tabular"
          />
        </Field>
        <Button ref={createButtonRef} type="submit" variant="secondary" size="lg" disabled={creating} className="!text-ui">
          <Plus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
          <span>{creating ? 'Đang tạo...' : 'Tạo liên kết mời'}</span>
        </Button>
      </form>
      {createError && <ErrorBanner message={createError} />}

      {loading && <LoadingSpinner message="Đang tải liên kết mời..." />}
      {error && <ErrorBanner message={error} onRetry={load} />}

      {!loading && !error && invites.length === 0 && (
        <p className="py-3 text-center text-meta text-slate-500">Chưa có liên kết mời nào.</p>
      )}

      {!loading && !error && invites.length > 0 && (
        <div className="relative overflow-x-auto rounded-2xl border border-slate-200">
          <table className="w-full text-left">
            <caption className="sr-only">Danh sách liên kết mời của lớp</caption>
            <thead className="border-b border-slate-200 bg-slate-50">
              <tr>
                <th scope="col" className={thClass}>Liên kết</th>
                <th scope="col" className={thClass}>Đã dùng · Hạn dùng</th>
                <th scope="col" className={thClass}><span className="sr-only">Thao tác</span></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {invites.map((invite) => {
                const view = STATUS_VIEW[invite.status] ?? { label: invite.status, tone: 'neutral' as BadgeTone };
                return (
                  <tr key={invite.id}>
                    {/* three columns (not five) so the list and its "Thu hồi" button fit a 390px phone without sideways scrolling */}
                    <td className="px-4 py-3 align-top">
                      <Badge tone={view.tone} size="sm">{view.label}</Badge>
                      <div className="mt-1 font-mono text-meta text-slate-900">…{invite.codeHint}</div>
                    </td>
                    <td className="px-4 py-3 align-top text-meta text-slate-600 tabular">
                      <div className="text-slate-900">{invite.usedCount} / {invite.maxUses != null ? invite.maxUses : 'không giới hạn'}</div>
                      <div className="mt-1">{invite.expiresAt ? `Hạn ${formatDate(invite.expiresAt)}` : 'Không hết hạn'}</div>
                    </td>
                    <td className="whitespace-nowrap px-2 py-3 text-right align-top">
                      {invite.status !== 'REVOKED' && (
                        <button
                          type="button"
                          onClick={() => { setRevokeError(null); setRevokeTarget(invite); }}
                          aria-label={`Thu hồi liên kết mời …${invite.codeHint}`}
                          className={rowActionClass('danger')}
                        >
                          Thu hồi
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

    </Card>

      {/* outside the Card: its space-y margin would otherwise land on the fixed overlay */}
      {created?.code && (
        <Modal size="md" title="Liên kết mời đã được tạo" onClose={closeCreated}>
          <div className="space-y-4">
            <Notice tone="warn" role="note">
              <strong className="font-semibold">Chỉ hiển thị một lần.</strong> Hãy sao chép và lưu liên kết ngay bây giờ: sau khi đóng cửa sổ này hệ thống không thể hiện lại mã
              (chỉ thu hồi và tạo liên kết mới). Ai có liên kết đều vào được lớp, hãy chỉ gửi cho người bạn muốn mời.
            </Notice>
            <Field label="Liên kết mời" htmlFor={linkId}>
              {/* a textarea, not an input: the link is ~60 characters and must be readable in full, wrapped, before it is copied */}
              <textarea
                id={linkId}
                ref={linkInputRef}
                readOnly
                rows={3}
                value={inviteLink(created.code)}
                onFocus={(e) => e.currentTarget.select()}
                className={inputClass('resize-none break-all bg-slate-50 py-2.5 font-mono text-meta')}
              />
            </Field>
            <p role="status" aria-live="polite" className={`min-h-[18px] text-meta font-medium ${copied === 'failed' ? 'text-red-600' : 'text-green-800'}`}>
              {copied === 'done' && 'Đã sao chép liên kết vào bộ nhớ tạm.'}
              {copied === 'failed' && 'Không thể tự sao chép. Liên kết đã được chọn sẵn, hãy nhấn Ctrl+C.'}
            </p>
            <ModalActions>
              <Button variant="secondary" onClick={closeCreated}>Đã lưu, đóng</Button>
              <Button variant="primary" onClick={handleCopy}>
                {copied === 'done' ? <Check className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" /> : <Copy className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />}
                <span>Sao chép liên kết</span>
              </Button>
            </ModalActions>
          </div>
        </Modal>
      )}

      {revokeTarget && (
        <Modal size="sm" title="Thu hồi liên kết mời?" role="alertdialog" onClose={() => setRevokeTarget(null)}>
          <p className="text-ui text-slate-600">
            Liên kết mời …{revokeTarget.codeHint} sẽ ngừng dùng được ngay. Những người đã vào lớp bằng liên kết này không bị ảnh hưởng.
          </p>
          {revokeError && <p role="alert" className="mt-2 text-meta font-medium text-red-600">{revokeError}</p>}
          <ModalActions className="mt-5">
            <Button variant="secondary" onClick={() => setRevokeTarget(null)}>Hủy</Button>
            <Button variant="danger" disabled={revoking} onClick={handleRevoke}>
              {revoking ? 'Đang thu hồi...' : 'Thu hồi liên kết'}
            </Button>
          </ModalActions>
        </Modal>
      )}
    </>
  );
};
