import React from 'react';
import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react';
import { buttonClass, inputClass } from './ui';
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
  const field = inputClass('mt-1.5 h-11 font-normal');
  const area = inputClass('mt-1.5 py-2.5 font-normal leading-[22px]');
  const label = 'block text-meta font-semibold text-slate-900';
  const update = (i: number, patch: Partial<AboutSection>) => onChange(sections.map((s, n) => n === i ? { ...s, ...patch } : s));
  const move = (i: number, delta: number) => { const next = [...sections]; [next[i], next[i + delta]] = [next[i + delta], next[i]]; onChange(next); };
  const icon = { size: 16, strokeWidth: 1.75, 'aria-hidden': true } as const;
  return <div className="space-y-6">
    <div>
      <h3 className="text-h3 font-semibold text-slate-900">Các mục giới thiệu</h3>
      <p className="mt-0.5 text-meta text-slate-600">Mỗi mục là một tab trên trang Giới thiệu (tối đa 12 mục).</p>
    </div>
    {message && <p role="status" className="rounded-btn bg-slate-100 px-3.5 py-2.5 text-meta text-slate-900">{message}</p>}
    {sections.map((section, i) => <fieldset key={i} disabled={uploading !== null} className="space-y-4 rounded-2xl border border-slate-200 p-4 sm:p-5">
      <legend className="px-1 text-meta font-semibold text-slate-600">Mục {i + 1}</legend>
      <label className={label}>Tên mục<input required maxLength={100} value={section.title} onChange={e => update(i, { title: e.target.value })} className={field} /></label>
      <label className={label}>Nội dung mục<textarea required rows={5} maxLength={20000} value={section.contentMarkdown} onChange={e => update(i, { contentMarkdown: e.target.value })} className={area} /></label>
      <label className={label}>Tải ảnh từ máy<input type="file" accept="image/jpeg,image/png,image/webp,image/gif" disabled={uploading !== null} onChange={e => void upload(i, e.target.files?.[0])} className="mt-1.5 block w-full text-meta font-normal text-slate-600 file:mr-3 file:h-9 file:rounded-[10px] file:border file:border-slate-200 file:bg-white file:px-3 file:text-meta file:font-semibold file:text-slate-900" /><span className="mt-1.5 block text-caption font-normal text-slate-600">JPG, PNG, WebP hoặc GIF, tối đa 5 MB. {uploading === i ? 'Đang tải ảnh...' : section.mediaAssetId ? 'Đã có ảnh được lưu trong lớp.' : ''}</span></label>
      <label className={label}>Hoặc địa chỉ ảnh HTTPS<input type="url" pattern="https://.*" placeholder="https://…" maxLength={2048} value={section.mediaAssetId ? '' : section.imageUrl || ''} onChange={e => update(i, { imageUrl: e.target.value, mediaAssetId: '' })} className={field} /></label>
      <label className={label}>Mô tả ảnh<input required={!!section.imageUrl || !!section.mediaAssetId} maxLength={300} value={section.imageAlt || ''} onChange={e => update(i, { imageAlt: e.target.value })} className={field} /></label>
      <div className="flex flex-wrap gap-2">
        <button type="button" disabled={i === 0} onClick={() => move(i, -1)} className={buttonClass('ghost', 'sm')}><ArrowUp {...icon} />Đưa lên</button>
        <button type="button" disabled={i === sections.length - 1} onClick={() => move(i, 1)} className={buttonClass('ghost', 'sm')}><ArrowDown {...icon} />Đưa xuống</button>
        <button type="button" onClick={() => onChange(sections.filter((_, n) => n !== i))} className={buttonClass('ghost', 'sm', '!text-red-700 hover:!bg-red-50')}><Trash2 {...icon} />Xóa mục</button>
      </div>
    </fieldset>)}
    <button type="button" disabled={uploading !== null || sections.length >= 12} onClick={() => onChange([...sections, { title: '', contentMarkdown: '', imageUrl: '', imageAlt: '' }])} className={buttonClass('secondary', 'md')}><Plus {...icon} />Thêm mục giới thiệu</button>
  </div>;
};

export const AboutSections: React.FC<{ sections: AboutSection[] }> = ({ sections }) => {
  const [active, setActive] = React.useState(0);
  const prefix = React.useId();
  const selected = Math.min(active, sections.length - 1);
  if (!sections.length) return null;
  return <div className="space-y-4">
    <div role="tablist" aria-label="Các mục giới thiệu lớp" className="flex gap-2 overflow-x-auto p-0.5 scrollbar-none" onKeyDown={e => {
      const next = e.key === 'ArrowRight' ? (selected + 1) % sections.length : e.key === 'ArrowLeft' ? (selected + sections.length - 1) % sections.length : e.key === 'Home' ? 0 : e.key === 'End' ? sections.length - 1 : null;
      if (next !== null) { e.preventDefault(); setActive(next); document.getElementById(`${prefix}-tab-${next}`)?.focus(); }
    }}>
      {sections.map((s, i) => <button key={i} id={`${prefix}-tab-${i}`} role="tab" aria-selected={selected === i} aria-controls={`${prefix}-panel-${i}`} tabIndex={selected === i ? 0 : -1} onClick={() => setActive(i)} className={`inline-flex h-9 shrink-0 items-center whitespace-nowrap rounded-full px-3.5 text-meta font-semibold transition-colors duration-micro ${selected === i ? 'bg-slate-900 text-white' : 'border border-slate-200 bg-white text-slate-600 hover:bg-slate-100 hover:text-slate-900'}`}>{s.title}</button>)}
    </div>
    {sections.map((s, i) => <article hidden={selected !== i} key={i} id={`${prefix}-panel-${i}`} role="tabpanel" aria-labelledby={`${prefix}-tab-${i}`} tabIndex={0} className="space-y-4 rounded-card border border-slate-200 bg-white p-5 shadow-hairline sm:px-7 sm:py-6">
      <h3 className="text-h3-lg font-semibold text-slate-900">{s.title}</h3>
      {s.imageUrl && <img src={s.imageUrl} alt={s.imageAlt} loading="lazy" referrerPolicy="no-referrer" className="max-h-96 w-full rounded-2xl bg-slate-100 object-contain" />}
      <div className="max-w-reading whitespace-pre-wrap break-words text-body-sm leading-6 text-slate-600">{s.contentMarkdown}</div>
    </article>)}
  </div>;
};
