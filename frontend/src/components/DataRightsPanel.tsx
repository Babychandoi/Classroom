import React, { useEffect, useState } from 'react';
import { api } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { Link } from 'react-router-dom';
import { Badge, buttonClass, inputClass } from './ui';

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
  const pending = requests.some(r => ['PENDING', 'ON_HOLD'].includes(r.status));
  return <section aria-labelledby="data-rights-title" className="space-y-5 rounded-card border border-slate-200 bg-white p-5 shadow-hairline sm:p-7">
    <div>
      <h2 id="data-rights-title" className="text-h3-lg font-semibold text-slate-900">Dữ liệu cá nhân của bạn</h2>
      <p className="mt-1.5 text-ui leading-[22px] text-slate-600">Tải thông tin tài khoản, thành viên, kết quả thi và đơn hàng, hoặc gửi yêu cầu xóa thông tin tài khoản. Chứng từ và hồ sơ có căn cứ lưu trữ sẽ được quản trị viên giải thích trong kết quả xử lý. <Link to="/privacy" className="font-medium text-blue-600 hover:text-blue-700">Chính sách dữ liệu</Link></p>
    </div>
    <label className="block text-meta font-semibold text-slate-900">Mật khẩu hiện tại<input autoComplete="current-password" type="password" maxLength={200} value={password} onChange={e => setPassword(e.target.value)} className={inputClass('mt-1.5 h-11 font-normal')} /></label>
    <label className="block text-meta font-semibold text-slate-900">Ghi chú cho yêu cầu xóa<textarea rows={3} maxLength={2000} value={reason} onChange={e => setReason(e.target.value)} className={inputClass('mt-1.5 resize-none py-2.5 font-normal leading-[22px]')} /></label>
    <div className="flex flex-wrap gap-3"><button type="button" disabled={busy} onClick={() => void act(false)} className={buttonClass('secondary', 'md')}>{busy ? 'Đang xử lý...' : 'Tải dữ liệu của tôi'}</button><button type="button" disabled={busy || pending} onClick={() => void act(true)} className={buttonClass('danger', 'md')}>Gửi yêu cầu xóa tài khoản</button></div>
    <p className="text-caption text-slate-500">Cả hai thao tác đều cần mật khẩu hiện tại để xác nhận đúng là bạn.</p>
    {message && <p role="status" className="rounded-btn bg-slate-100 px-3.5 py-2.5 text-meta text-slate-900">{message}</p>}
    {requests.length > 0 && <ul className="divide-y divide-slate-100 rounded-btn border border-slate-200">{requests.map(r => <li key={r.id} className="flex flex-wrap items-center gap-2 px-3.5 py-2.5 text-meta text-slate-600"><span className="min-w-0 break-all">Yêu cầu {r.id}:</span><Badge tone={r.status === 'COMPLETED' ? 'success' : r.status === 'REJECTED' ? 'danger' : 'warn'} size="sm">{r.status}</Badge>{r.resolution && <span className="w-full text-slate-600">— {r.resolution}</span>}</li>)}</ul>}
    {user?.role === 'PLATFORM_ADMIN' && <div className="space-y-4 border-t border-slate-100 pt-5"><h3 className="text-h3 font-semibold text-slate-900">Xử lý yêu cầu dữ liệu</h3><label className="block text-meta font-semibold text-slate-900">Kết quả và căn cứ lưu trữ<textarea maxLength={2000} value={resolution} onChange={e => setResolution(e.target.value)} className={inputClass('mt-1.5 resize-none py-2.5 font-normal leading-[22px]')} /></label>{adminRequests.filter(r => r.status !== 'COMPLETED').map(r => <div key={r.id} className="space-y-3 rounded-btn border border-slate-200 p-3.5"><p className="break-words text-meta text-slate-600">{r.user_id} · {r.status} · {r.reason}</p><div className="flex flex-wrap gap-2">{[['ON_HOLD', 'Tạm giữ có căn cứ'], ['REJECTED', 'Từ chối có lý do'], ['COMPLETED', 'Ẩn danh hóa và đóng tài khoản']].map(([status, label]) => <button key={status} type="button" disabled={busy || !resolution.trim()} onClick={() => void resolve(r, status)} className={buttonClass('secondary', 'sm')}>{label}</button>)}</div></div>)}</div>}
  </section>;
};
