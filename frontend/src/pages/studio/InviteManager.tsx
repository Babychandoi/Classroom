import React, { useCallback, useEffect, useId, useRef, useState } from 'react';
import { ClassInvite, Classroom, InviteStatus } from '../../types';
import { api } from '../../api/client';
import { formatDate } from '../../api/format';
import { ErrorBanner, LoadingSpinner } from '../../components/UIStates';
import { Modal } from '../../components/Modal';
import { Check, Copy, Link2, Plus, TriangleAlert } from 'lucide-react';

const EXPIRY_OPTIONS = [
  { value: '', label: 'Không hết hạn' },
  { value: '1', label: '1 ngày' },
  { value: '7', label: '7 ngày' },
  { value: '30', label: '30 ngày' },
];
const DAY_MS = 24 * 60 * 60 * 1000;
const MAX_USES_LIMIT = 100000;

const STATUS_VIEW: Record<InviteStatus, { label: string; className: string }> = {
  ACTIVE: { label: 'Đang hiệu lực', className: 'bg-emerald-100 text-emerald-800' },
  REVOKED: { label: 'Đã thu hồi', className: 'bg-rose-100 text-rose-800' },
  EXPIRED: { label: 'Hết hạn', className: 'bg-slate-200 text-slate-800' },
  EXHAUSTED: { label: 'Hết lượt', className: 'bg-amber-100 text-amber-900' },
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
    <section aria-labelledby="invite-section-title" className="bg-white rounded-2xl border border-slate-200 shadow-sm p-5 space-y-4">
      <div>
        <h2 id="invite-section-title" className="text-sm font-bold text-slate-900 flex items-center gap-2">
          <Link2 className="w-4 h-4 text-indigo-600" aria-hidden="true" />
          Mời thành viên
        </h2>
        <p className="text-xs text-slate-600 mt-0.5">
          {isPublic
            ? 'Lớp đang công khai nên liên kết mời chỉ là tùy chọn (ai cũng có thể vào lớp từ danh sách khám phá). Liên kết vẫn hữu ích để gửi trực tiếp cho một nhóm người.'
            : 'Lớp đang riêng tư: liên kết mời là cách duy nhất để người mới vào lớp. Mỗi liên kết có thể đặt hạn dùng và số lượt dùng tối đa.'}
        </p>
      </div>

      <form onSubmit={handleCreate} noValidate className="grid gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
        <div>
          <label htmlFor={expiryId} className="block text-xs font-semibold text-slate-700 uppercase">Hạn dùng</label>
          <select
            id={expiryId}
            value={expiryDays}
            onChange={(e) => setExpiryDays(e.target.value)}
            className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-sm"
          >
            {EXPIRY_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </div>
        <div>
          <label htmlFor={maxUsesId} className="block text-xs font-semibold text-slate-700 uppercase">Số lượt dùng tối đa</label>
          <input
            id={maxUsesId}
            type="number"
            inputMode="numeric"
            min={1}
            max={MAX_USES_LIMIT}
            value={maxUses}
            onChange={(e) => setMaxUses(e.target.value)}
            placeholder="Không giới hạn"
            className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-sm"
          />
        </div>
        <button
          ref={createButtonRef}
          type="submit"
          disabled={creating}
          className="inline-flex items-center justify-center space-x-1.5 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold shadow-sm transition disabled:opacity-50"
        >
          <Plus className="w-4 h-4" aria-hidden="true" />
          <span>{creating ? 'Đang tạo...' : 'Tạo liên kết mời'}</span>
        </button>
      </form>
      {createError && <ErrorBanner message={createError} />}

      {loading && <LoadingSpinner message="Đang tải liên kết mời..." />}
      {error && <ErrorBanner message={error} onRetry={load} />}

      {!loading && !error && invites.length === 0 && (
        <p className="text-xs text-slate-500 text-center py-3">Chưa có liên kết mời nào.</p>
      )}

      {!loading && !error && invites.length > 0 && (
        <div className="relative overflow-x-auto rounded-xl border border-slate-200">
          <table className="w-full text-left text-xs">
            <caption className="sr-only">Danh sách liên kết mời của lớp</caption>
            <thead className="bg-slate-50 text-slate-600">
              <tr>
                <th scope="col" className="px-3 py-2 font-semibold">Liên kết</th>
                <th scope="col" className="px-3 py-2 font-semibold">Đã dùng · Hạn dùng</th>
                <th scope="col" className="px-3 py-2 font-semibold"><span className="sr-only">Thao tác</span></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {invites.map((invite) => {
                const view = STATUS_VIEW[invite.status] ?? { label: invite.status, className: 'bg-slate-100 text-slate-700' };
                return (
                  <tr key={invite.id}>
                    {/* three columns (not five) so the list and its "Thu hồi" button fit a 390px phone without sideways scrolling */}
                    <td className="px-3 py-2.5 align-top">
                      <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold whitespace-nowrap ${view.className}`}>{view.label}</span>
                      <div className="mt-1 font-mono text-slate-800">…{invite.codeHint}</div>
                    </td>
                    <td className="px-3 py-2.5 align-top text-slate-700">
                      <div>{invite.usedCount} / {invite.maxUses != null ? invite.maxUses : 'không giới hạn'}</div>
                      <div className="mt-1 text-slate-600">{invite.expiresAt ? `Hạn ${formatDate(invite.expiresAt)}` : 'Không hết hạn'}</div>
                    </td>
                    <td className="px-2 py-2.5 align-top text-right whitespace-nowrap">
                      {invite.status !== 'REVOKED' && (
                        <button
                          type="button"
                          onClick={() => { setRevokeError(null); setRevokeTarget(invite); }}
                          aria-label={`Thu hồi liên kết mời …${invite.codeHint}`}
                          className="px-2.5 py-1.5 text-rose-700 hover:bg-rose-50 rounded-lg font-bold"
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

      {created?.code && (
        <Modal size="md" title="Liên kết mời đã được tạo" onClose={closeCreated}>
          <div className="space-y-4">
            <div role="note" className="flex items-start gap-2 p-3 bg-amber-50 border border-amber-200 rounded-xl text-xs text-amber-900">
              <TriangleAlert className="w-4 h-4 flex-shrink-0 mt-0.5" aria-hidden="true" />
              <span>
                <strong>Chỉ hiển thị một lần.</strong> Hãy sao chép và lưu liên kết ngay bây giờ: sau khi đóng cửa sổ này hệ thống không thể hiện lại mã
                (chỉ thu hồi và tạo liên kết mới). Ai có liên kết đều vào được lớp, hãy chỉ gửi cho người bạn muốn mời.
              </span>
            </div>
            <div>
              <label htmlFor={linkId} className="block text-xs font-semibold text-slate-700 uppercase">Liên kết mời</label>
              {/* a textarea, not an input: the link is ~60 characters and must be readable in full, wrapped, before it is copied */}
              <textarea
                id={linkId}
                ref={linkInputRef}
                readOnly
                rows={3}
                value={inviteLink(created.code)}
                onFocus={(e) => e.currentTarget.select()}
                className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs font-mono break-all resize-none"
              />
            </div>
            <p role="status" aria-live="polite" className={`text-xs font-semibold min-h-[1rem] ${copied === 'failed' ? 'text-rose-700' : 'text-emerald-700'}`}>
              {copied === 'done' && 'Đã sao chép liên kết vào bộ nhớ tạm.'}
              {copied === 'failed' && 'Không thể tự sao chép. Liên kết đã được chọn sẵn, hãy nhấn Ctrl+C.'}
            </p>
            <div className="flex justify-end space-x-2">
              <button
                type="button"
                onClick={handleCopy}
                className="inline-flex items-center space-x-1.5 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold"
              >
                {copied === 'done' ? <Check className="w-4 h-4" aria-hidden="true" /> : <Copy className="w-4 h-4" aria-hidden="true" />}
                <span>Sao chép liên kết</span>
              </button>
              <button
                type="button"
                onClick={closeCreated}
                className="px-4 py-2 border border-slate-300 text-slate-700 rounded-xl text-xs font-semibold"
              >
                Đã lưu, đóng
              </button>
            </div>
          </div>
        </Modal>
      )}

      {revokeTarget && (
        <Modal size="sm" title="Thu hồi liên kết mời?" role="alertdialog" onClose={() => setRevokeTarget(null)}>
          <p className="text-sm text-slate-600">
            Liên kết mời …{revokeTarget.codeHint} sẽ ngừng dùng được ngay. Những người đã vào lớp bằng liên kết này không bị ảnh hưởng.
          </p>
          {revokeError && <p role="alert" className="mt-2 text-xs font-semibold text-rose-700">{revokeError}</p>}
          <div className="flex justify-end space-x-2 mt-4">
            <button
              type="button"
              onClick={() => setRevokeTarget(null)}
              className="px-4 py-2 border border-slate-300 text-slate-700 rounded-xl text-xs font-semibold"
            >
              Hủy
            </button>
            <button
              type="button"
              disabled={revoking}
              onClick={handleRevoke}
              className="px-4 py-2 bg-rose-600 hover:bg-rose-700 text-white rounded-xl text-xs font-bold disabled:opacity-50"
            >
              {revoking ? 'Đang thu hồi...' : 'Thu hồi liên kết'}
            </button>
          </div>
        </Modal>
      )}
    </section>
  );
};
