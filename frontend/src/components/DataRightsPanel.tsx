import React, { useEffect, useState } from 'react';
import { api } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { Link } from 'react-router-dom';

interface PrivacyRequest { user_id: string; id: string; status: string; reason: string; resolution: string }
interface ExportPage { hasMore: boolean; nextOffset: number | null; [key: string]: unknown }
export const DataRightsPanel: React.FC = () => {
  const { user } = useAuth();
  const [password, setPassword] = useState('');
  const [reason, setReason] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [requests, setRequests] = useState<PrivacyRequest[]>([]);
  const [adminRequests, setAdminRequests] = useState<PrivacyRequest[]>([]);
  const [resolution, setResolution] = useState('');
  const load = async () => {
    try { setRequests(await api.get<PrivacyRequest[]>('/privacy/me/requests')); if (user?.role === 'PLATFORM_ADMIN') setAdminRequests(await api.get<PrivacyRequest[]>('/privacy/requests')); }
    catch (e) { setMessage(e instanceof Error ? e.message : 'Không thể tải yêu cầu dữ liệu.'); }
  };
  useEffect(() => { void load(); }, [user?.id]);
  const act = async (erase: boolean) => {
    if (!password) { setMessage('Nhập mật khẩu hiện tại để xác nhận.'); return; }
    setBusy(true); setMessage('');
    try {
      if (erase) { await api.post('/privacy/me/deletion-requests', { password, reason }); setMessage('Đã tiếp nhận yêu cầu. Quản trị viên sẽ kiểm tra nghĩa vụ lưu trữ trước khi xử lý.'); await load(); }
      else {
        const pages: ExportPage[] = []; let offset = 0;
        for (;;) {
          const page = await api.post<ExportPage>(`/privacy/me/export?offset=${offset}`, { password }); pages.push(page);
          if (!page.hasMore || page.nextOffset === null) break;
          offset = page.nextOffset;
        }
        const url = URL.createObjectURL(new Blob([JSON.stringify({ pages }, null, 2)], { type: 'application/json' }));
        const link = document.createElement('a'); link.href = url; link.download = 'du-lieu-ca-nhan.json'; link.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000); setMessage('Đã tải bản sao dữ liệu. Hãy lưu tệp ở nơi riêng tư.');
      }
      setPassword('');
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Không thể xử lý yêu cầu. Thử lại sau.'); }
    finally { setBusy(false); }
  };
  const resolve = async (request: PrivacyRequest, status: string) => {
    setBusy(true);
    try { await api.put(`/privacy/requests/${request.user_id}`, { status, resolution }); setMessage('Đã ghi nhận kết quả xử lý.'); await load(); }
    catch (e) { setMessage(e instanceof Error ? e.message : 'Không thể xử lý yêu cầu.'); }
    finally { setBusy(false); }
  };
  return <section className="space-y-5 rounded-2xl border border-slate-200 bg-white p-6">
    <h2 className="text-xl font-bold">Dữ liệu cá nhân của bạn</h2>
    <p className="text-sm leading-6 text-slate-600">Tải thông tin tài khoản, thành viên, kết quả thi và đơn hàng, hoặc gửi yêu cầu xóa thông tin tài khoản. Chứng từ và hồ sơ có căn cứ lưu trữ sẽ được quản trị viên giải thích trong kết quả xử lý. <Link to="/privacy" className="font-semibold text-indigo-700 underline">Chính sách dữ liệu</Link></p>
    <label className="block font-semibold">Mật khẩu hiện tại<input autoComplete="current-password" type="password" maxLength={200} value={password} onChange={e => setPassword(e.target.value)} className="mt-2 w-full rounded-xl border border-slate-300 p-3 text-base" /></label>
    <label className="block font-semibold">Ghi chú cho yêu cầu xóa<textarea rows={3} maxLength={2000} value={reason} onChange={e => setReason(e.target.value)} className="mt-2 w-full rounded-xl border border-slate-300 p-3 text-base" /></label>
    <div className="flex flex-wrap gap-3"><button type="button" disabled={busy} onClick={() => void act(false)} className="rounded-xl bg-indigo-600 px-4 py-3 font-semibold text-white disabled:opacity-50">{busy ? 'Đang xử lý...' : 'Tải dữ liệu của tôi'}</button><button type="button" disabled={busy || requests.some(r => ['PENDING', 'ON_HOLD'].includes(r.status))} onClick={() => void act(true)} className="rounded-xl border border-red-300 px-4 py-3 font-semibold text-red-700 disabled:opacity-50">Gửi yêu cầu xóa tài khoản</button></div>
    {message && <p role="status" className="text-sm text-slate-800">{message}</p>}
    {requests.map(r => <p key={r.id} className="text-sm">Yêu cầu {r.id}: {r.status}{r.resolution && ` — ${r.resolution}`}</p>)}
    {user?.role === 'PLATFORM_ADMIN' && <div className="space-y-4 border-t border-slate-200 pt-5"><h3 className="font-bold">Xử lý yêu cầu dữ liệu</h3><label className="block">Kết quả và căn cứ lưu trữ<textarea maxLength={2000} value={resolution} onChange={e => setResolution(e.target.value)} className="mt-2 w-full rounded border p-3" /></label>{adminRequests.filter(r => r.status !== 'COMPLETED').map(r => <div key={r.id} className="space-y-3"><p className="break-words text-sm">{r.user_id} · {r.status} · {r.reason}</p><div className="flex flex-wrap gap-2">{[['ON_HOLD', 'Tạm giữ có căn cứ'], ['REJECTED', 'Từ chối có lý do'], ['COMPLETED', 'Ẩn danh hóa và đóng tài khoản']].map(([status, label]) => <button key={status} type="button" disabled={busy || !resolution.trim()} onClick={() => void resolve(r, status)} className="rounded border border-slate-300 px-3 py-2 disabled:opacity-50">{label}</button>)}</div></div>)}</div>}
  </section>;
};
