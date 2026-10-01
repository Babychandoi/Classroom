import React, { useState } from 'react';
import { useParams, useOutletContext } from 'react-router-dom';
import { api } from '../../api/client';
import { putToObjectStore } from '../../api/upload';
import { Classroom } from '../../types';
import { AboutSection, AboutSectionsEditor } from '../../components/AboutSections';

export const StudioFeed: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const canCreate = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('FEED:CREATE');
  const [title, setTitle] = useState('');
  const [contentMarkdown, setContent] = useState('');
  const [visibility, setVisibility] = useState('PUBLIC');
  const [message, setMessage] = useState('');
  const submit = async (event: React.FormEvent) => {
    event.preventDefault(); setMessage('');
    try { await api.post(`/classes/${id}/posts`, { title, contentMarkdown, visibility, pinned: false }); setTitle(''); setContent(''); setMessage('Đã đăng bài.'); }
    catch (e) { setMessage(e instanceof Error ? e.message : 'Không thể đăng bài.'); }
  };
  return <section className="max-w-3xl space-y-5"><h1 className="text-2xl font-bold">Quản lý bảng tin</h1><p className="text-sm text-slate-600">Tạo thông báo và nội dung cho lớp. Quyền xuất bản được xác thực ở máy chủ.</p><form onSubmit={submit} className="space-y-3 rounded-xl bg-white p-5 shadow"><label className="block">Tiêu đề<input required value={title} onChange={e => setTitle(e.target.value)} className="mt-1 w-full rounded border p-2" /></label><label className="block">Nội dung<textarea required rows={8} value={contentMarkdown} onChange={e => setContent(e.target.value)} className="mt-1 w-full rounded border p-2" /></label><label className="block">Đối tượng<select value={visibility} onChange={e => setVisibility(e.target.value)} className="mt-1 rounded border p-2"><option>PUBLIC</option><option>FREE</option><option>PRO</option></select></label>{canCreate && <button className="rounded bg-indigo-600 px-4 py-2 font-semibold text-white">Đăng bài</button>}{message && <p role="status">{message}</p>}</form></section>;
};

export const StudioDocuments: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const canCreate = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('DOCUMENT:CREATE');
  const [form, setForm] = useState({ title: '', description: '', mediaAssetId: '', visibility: 'FREE', targetProductId: '', targetCourseId: '' });
  const [message, setMessage] = useState('');
  const [uploading, setUploading] = useState(false);
  const upload = async (file?: File) => {
    if (!file || !id) return;
    setUploading(true); setMessage('');
    try {
      const intent = await api.post<{ assetId: string; uploadUrl: string }>(`/classes/${id}/media/upload-intents`, { filename: file.name, mimeType: file.type, sizeBytes: file.size, purpose: 'DOCUMENT' });
      await putToObjectStore(intent.uploadUrl, file, 'Tải tệp thất bại');
      await api.post(`/media/${intent.assetId}/complete`);
      setForm(current => ({ ...current, mediaAssetId: intent.assetId }));
      setMessage(`Đã tải lên ${file.name}.`);
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Không thể tải tệp.'); }
    finally { setUploading(false); }
  };
  const submit = async (event: React.FormEvent) => {
    event.preventDefault(); setMessage('');
    try { await api.post(`/classes/${id}/documents`, { ...form, targetProductId: form.visibility === 'PRODUCT_OWNER' ? form.targetProductId || null : null, targetCourseId: form.visibility === 'PRODUCT_OWNER' ? form.targetCourseId || null : null }); setForm({ title: '', description: '', mediaAssetId: '', visibility: 'FREE', targetProductId: '', targetCourseId: '' }); setMessage('Đã tạo tài liệu.'); }
    catch (e) { setMessage(e instanceof Error ? e.message : 'Không thể tạo tài liệu.'); }
  };
  return <section className="max-w-3xl space-y-5"><h1 className="text-2xl font-bold">Quản lý tài liệu</h1><p className="text-sm text-slate-600">Chọn tệp để tải lên riêng tư; máy chủ kiểm tra quyền và chỉ phát URL ký hạn ngắn.</p><form onSubmit={submit} className="space-y-3 rounded-xl bg-white p-5 shadow"><label className="block">Tên tài liệu<input required value={form.title} onChange={e=>setForm({...form,title:e.target.value})} className="mt-1 w-full rounded border p-2" /></label><label className="block">Mô tả<input value={form.description} onChange={e=>setForm({...form,description:e.target.value})} className="mt-1 w-full rounded border p-2" /></label><label className="block">Tệp<input required type="file" onChange={e=>void upload(e.target.files?.[0])} className="mt-1 block w-full" /></label>{form.mediaAssetId && <p className="text-sm">Tệp đã tải lên và sẵn sàng đính kèm.</p>}<label className="block">Quyền xem<select value={form.visibility} onChange={e=>setForm({...form,visibility:e.target.value,targetProductId:'',targetCourseId:''})} className="mt-1 rounded border p-2"><option>FREE</option><option>PRO</option><option>PRODUCT_OWNER</option></select></label>{form.visibility === 'PRODUCT_OWNER' && <><label className="block">Mã sản phẩm (chọn một trong hai)<input value={form.targetProductId} onChange={e=>setForm({...form,targetProductId:e.target.value,targetCourseId:''})} className="mt-1 w-full rounded border p-2" /></label><label className="block">Mã khóa học<input value={form.targetCourseId} onChange={e=>setForm({...form,targetCourseId:e.target.value,targetProductId:''})} className="mt-1 w-full rounded border p-2" /></label></>}{canCreate && <button disabled={uploading || !form.mediaAssetId} className="rounded bg-indigo-600 px-4 py-2 font-semibold text-white disabled:opacity-50">Tạo tài liệu</button>}{message && <p role="status">{message}</p>}</form></section>;
};

export const StudioAbout: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const canEdit = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('ABOUT:EDIT');
  const [sections, setSections] = useState<AboutSection[]>([]);
  const [version, setVersion] = useState(1);
  const [saving, setSaving] = useState(false);
  const [contentMarkdown, setContent] = useState(''); const [rulesMarkdown, setRules] = useState(''); const [message, setMessage] = useState('');
  const load = async () => { try { const about = await api.get<{contentMarkdown:string;rulesMarkdown:string;sections?:AboutSection[];publishedVersion:number}>(`/classes/${id}/about`); setContent(about.contentMarkdown || ''); setRules(about.rulesMarkdown || ''); setSections(about.sections || []); setVersion(about.publishedVersion); } catch (e) { setMessage(e instanceof Error ? e.message : 'Không thể tải giới thiệu.'); } };
  React.useEffect(() => { void load(); }, [id]);
  const save = async (event: React.FormEvent) => { event.preventDefault(); setSaving(true); try { const result = await api.put<{publishedVersion:number}>(`/classes/${id}/about`, {contentMarkdown,rulesMarkdown,sections:sections.map(s=>({...s,imageUrl:s.mediaAssetId?'':s.imageUrl})),publishedVersion:version}); setVersion(result.publishedVersion); setMessage('Đã lưu thông tin lớp.'); } catch(e) { setMessage(e instanceof Error ? e.message : 'Không thể lưu.'); } finally { setSaving(false); } };
  return <section className="max-w-3xl space-y-5"><h1 className="text-2xl font-bold">Giới thiệu & nội quy</h1><form onSubmit={save} className="space-y-6 rounded-xl bg-white p-5 shadow"><label className="block">Giới thiệu<textarea maxLength={100000} rows={8} value={contentMarkdown} onChange={e=>setContent(e.target.value)} className="mt-1 w-full rounded border p-2" /></label><AboutSectionsEditor classId={id!} sections={sections} onChange={setSections} /><label className="block">Nội quy<textarea maxLength={100000} rows={8} value={rulesMarkdown} onChange={e=>setRules(e.target.value)} className="mt-1 w-full rounded border p-2" /></label>{canEdit && <button disabled={saving} className="rounded bg-indigo-600 px-4 py-2 font-semibold text-white disabled:opacity-50">{saving ? 'Đang lưu...' : 'Lưu'}</button>}{message && <p role="status">{message}</p>}</form></section>;
};
