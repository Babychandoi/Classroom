import React from 'react';
import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react';
import { api } from '../api/client';
import { putToObjectStore } from '../api/upload';

export interface AboutSection { title: string; contentMarkdown: string; imageUrl: string; imageAlt: string; mediaAssetId?: string }

export const AboutSectionsEditor: React.FC<{ sections: AboutSection[]; onChange: (sections: AboutSection[]) => void; classId: string }> = ({ sections, onChange, classId }) => {
  const [uploading, setUploading] = React.useState<number | null>(null);
  const [message, setMessage] = React.useState('');
  const upload = async (i: number, file?: File) => {
    if (!file) return;
    if (file.size > 5 * 1024 * 1024) { setMessage('Ảnh tối đa 5 MB.'); return; }
    setUploading(i); setMessage('');
    try {
      const intent = await api.post<{ assetId: string; uploadUrl: string }>(`/classes/${classId}/media/upload-intents`, { filename: file.name, mimeType: file.type, sizeBytes: file.size, purpose: 'ABOUT' });
      await putToObjectStore(intent.uploadUrl, file, 'Không thể tải ảnh');
      await api.post(`/media/${intent.assetId}/complete`);
      update(i, { mediaAssetId: intent.assetId, imageUrl: '' }); setMessage('Ảnh đã tải lên. Nhập mô tả ảnh và lưu để xuất bản.');
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Không thể tải ảnh.'); }
    finally { setUploading(null); }
  };
  const field = 'mt-2 w-full rounded-xl border border-slate-300 bg-white p-3 text-base text-slate-900 focus:outline-none focus:ring-2 focus:ring-indigo-600';
  const update = (i: number, patch: Partial<AboutSection>) => onChange(sections.map((s, n) => n === i ? { ...s, ...patch } : s));
  const move = (i: number, delta: number) => { const next = [...sections]; [next[i], next[i + delta]] = [next[i + delta], next[i]]; onChange(next); };
  return <div className="space-y-8">
    <h3 className="text-lg font-bold">Các mục giới thiệu</h3>
    {message && <p role="status" className="text-sm text-slate-800">{message}</p>}
    {sections.map((section, i) => <fieldset key={i} disabled={uploading !== null} className="space-y-4 border-t border-slate-200 pt-6">
      <legend className="font-bold">Mục {i + 1}</legend>
      <label className="block font-semibold">Tên mục<input required maxLength={100} value={section.title} onChange={e => update(i, { title: e.target.value })} className={field} /></label>
      <label className="block font-semibold">Nội dung mục<textarea required rows={5} maxLength={20000} value={section.contentMarkdown} onChange={e => update(i, { contentMarkdown: e.target.value })} className={field} /></label>
      <label className="block font-semibold">Tải ảnh từ máy<input type="file" accept="image/jpeg,image/png,image/webp,image/gif" disabled={uploading !== null} onChange={e => void upload(i, e.target.files?.[0])} className="mt-2 block w-full text-base" /><span className="mt-2 block text-sm font-normal text-slate-600">JPG, PNG, WebP hoặc GIF, tối đa 5 MB. {uploading === i ? 'Đang tải ảnh...' : section.mediaAssetId ? 'Đã có ảnh được lưu trong lớp.' : ''}</span></label>
      <label className="block font-semibold">Hoặc địa chỉ ảnh HTTPS<input type="url" pattern="https://.*" placeholder="https://…" maxLength={2048} value={section.mediaAssetId ? '' : section.imageUrl || ''} onChange={e => update(i, { imageUrl: e.target.value, mediaAssetId: '' })} className={field} /></label>
      <label className="block font-semibold">Mô tả ảnh<input required={!!section.imageUrl || !!section.mediaAssetId} maxLength={300} value={section.imageAlt || ''} onChange={e => update(i, { imageAlt: e.target.value })} className={field} /></label>
      <div className="flex flex-wrap gap-3">
        <button type="button" disabled={i === 0} onClick={() => move(i, -1)} className="inline-flex items-center gap-2 p-2 disabled:opacity-40"><ArrowUp size={18} />Đưa lên</button>
        <button type="button" disabled={i === sections.length - 1} onClick={() => move(i, 1)} className="inline-flex items-center gap-2 p-2 disabled:opacity-40"><ArrowDown size={18} />Đưa xuống</button>
        <button type="button" onClick={() => onChange(sections.filter((_, n) => n !== i))} className="inline-flex items-center gap-2 p-2 text-red-700"><Trash2 size={18} />Xóa mục</button>
      </div>
    </fieldset>)}
    <button type="button" disabled={uploading !== null || sections.length >= 12} onClick={() => onChange([...sections, { title: '', contentMarkdown: '', imageUrl: '', imageAlt: '' }])} className="inline-flex items-center gap-2 rounded-xl border border-indigo-300 px-4 py-3 font-semibold text-indigo-700 disabled:opacity-40"><Plus size={18} />Thêm mục giới thiệu</button>
  </div>;
};

export const AboutSections: React.FC<{ sections: AboutSection[] }> = ({ sections }) => {
  const [active, setActive] = React.useState(0);
  const prefix = React.useId();
  const selected = Math.min(active, sections.length - 1);
  if (!sections.length) return null;
  return <div className="space-y-5">
    <div role="tablist" aria-label="Các mục giới thiệu lớp" className="flex gap-2 overflow-x-auto border-b border-slate-200 pb-2" onKeyDown={e => {
      const next = e.key === 'ArrowRight' ? (selected + 1) % sections.length : e.key === 'ArrowLeft' ? (selected + sections.length - 1) % sections.length : e.key === 'Home' ? 0 : e.key === 'End' ? sections.length - 1 : null;
      if (next !== null) { e.preventDefault(); setActive(next); document.getElementById(`${prefix}-tab-${next}`)?.focus(); }
    }}>
      {sections.map((s, i) => <button key={i} id={`${prefix}-tab-${i}`} role="tab" aria-selected={selected === i} aria-controls={`${prefix}-panel-${i}`} tabIndex={selected === i ? 0 : -1} onClick={() => setActive(i)} className={`shrink-0 rounded-xl px-4 py-3 font-semibold ${selected === i ? 'bg-indigo-600 text-white' : 'text-slate-700 hover:bg-slate-100'}`}>{s.title}</button>)}
    </div>
    {sections.map((s, i) => <article hidden={selected !== i} key={i} id={`${prefix}-panel-${i}`} role="tabpanel" aria-labelledby={`${prefix}-tab-${i}`} tabIndex={0} className="space-y-5 rounded-2xl border border-slate-200 bg-white p-5 sm:p-8">
      <h3 className="text-xl font-bold">{s.title}</h3>
      {s.imageUrl && <img src={s.imageUrl} alt={s.imageAlt} loading="lazy" referrerPolicy="no-referrer" className="max-h-96 w-full rounded-xl object-contain" />}
      <div className="max-w-prose whitespace-pre-wrap break-words text-base leading-7 text-slate-700">{s.contentMarkdown}</div>
    </article>)}
  </div>;
};
