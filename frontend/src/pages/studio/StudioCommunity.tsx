import React, { useId, useState } from 'react';
import { useParams, useOutletContext } from 'react-router-dom';
import { api } from '../../api/client';
import { putToObjectStore } from '../../api/upload';
import { hasStudioPermission } from '../../api/permissions';
import { Classroom } from '../../types';
import { AboutSection, AboutSectionsEditor } from '../../components/AboutSections';
import { Button, Card, Field, Input, Select, Textarea } from '../../components/ui';
import { CardHeader, Notice, PageHeader, StudioPage } from './studioUi';
import { FileCheck2, Lock, Send } from 'lucide-react';

/** Inline result line of these forms: one role="status" node, green on success, red when the text is an error. */
const ResultLine: React.FC<{ message: string; ok: boolean }> = ({ message, ok }) => (
  <p role="status" className={`min-h-[18px] text-meta font-medium ${message ? (ok ? 'text-green-800' : 'text-red-600') : ''}`}>
    {message}
  </p>
);

/** Shown instead of a form the viewer is not allowed to submit. */
const NoPermission: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <Card className="flex items-start gap-3">
    <span className="inline-flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-[12px] bg-slate-100 text-slate-500" aria-hidden="true">
      <Lock className="h-5 w-5" strokeWidth={1.75} />
    </span>
    <p className="text-ui text-slate-600">{children}</p>
  </Card>
);

export const StudioFeed: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  // Wildcard-aware ("FEED:*", "*:CREATE"), like the server's AccessPolicy.canManage.
  const canCreate = hasStudioPermission(classroom, 'FEED', 'CREATE');
  const [title, setTitle] = useState('');
  const [contentMarkdown, setContent] = useState('');
  const [visibility, setVisibility] = useState('PUBLIC');
  const [message, setMessage] = useState('');
  const [ok, setOk] = useState(false);
  const [posting, setPosting] = useState(false);
  const titleId = useId();
  const contentId = useId();
  const visibilityId = useId();
  const submit = async (event: React.FormEvent) => {
    event.preventDefault(); setMessage(''); setPosting(true);
    try {
      await api.post(`/classes/${id}/posts`, { title, contentMarkdown, visibility, pinned: false });
      setTitle(''); setContent(''); setOk(true); setMessage('Đã đăng bài.');
    } catch (e) { setOk(false); setMessage(e instanceof Error ? e.message : 'Không thể đăng bài.'); }
    finally { setPosting(false); }
  };
  return (
    <StudioPage width="narrow">
      <PageHeader title="Bảng tin" description="Đăng thông báo và nội dung cho cả lớp. Quyền xuất bản được kiểm tra ở máy chủ." />
      {canCreate ? (
        <Card as="section" aria-labelledby="feed-form-title">
          <form onSubmit={submit} className="space-y-5">
            <CardHeader id="feed-form-title" title="Bài đăng mới" description="Bài đăng hiện ở tab Thảo luận của lớp." />
            <Field label="Tiêu đề" htmlFor={titleId}>
              <Input id={titleId} required value={title} onChange={e => setTitle(e.target.value)} placeholder="VD: Lịch học tuần này" />
            </Field>
            <Field label="Nội dung" htmlFor={contentId} hint="Hỗ trợ Markdown.">
              <Textarea id={contentId} required rows={8} value={contentMarkdown} onChange={e => setContent(e.target.value)} />
            </Field>
            <Field label="Đối tượng" htmlFor={visibilityId}>
              <Select id={visibilityId} value={visibility} onChange={e => setVisibility(e.target.value)} className="sm:max-w-xs">
                <option value="PUBLIC">Mọi người xem được lớp</option>
                <option value="FREE">Thành viên của lớp</option>
                <option value="PRO">Chỉ học viên PRO</option>
              </Select>
            </Field>
            <div className="flex flex-col-reverse gap-3 sm:flex-row sm:items-center sm:justify-between">
              <ResultLine message={message} ok={ok} />
              <Button type="submit" variant="primary" disabled={posting}>
                <Send className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                {posting ? 'Đang đăng...' : 'Đăng bài'}
              </Button>
            </div>
          </form>
        </Card>
      ) : (
        <NoPermission>Bạn cần quyền FEED:CREATE để đăng bài lên bảng tin. Hãy nhờ chủ lớp cấp quyền nếu cần.</NoPermission>
      )}
    </StudioPage>
  );
};

const EMPTY_DOCUMENT = { title: '', description: '', mediaAssetId: '', visibility: 'FREE', targetProductId: '', targetCourseId: '' };

export const StudioDocuments: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const canCreate = hasStudioPermission(classroom, 'DOCUMENT', 'CREATE');
  const [form, setForm] = useState(EMPTY_DOCUMENT);
  const [message, setMessage] = useState('');
  const [ok, setOk] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [fileName, setFileName] = useState('');
  const ids = { title: useId(), description: useId(), file: useId(), visibility: useId(), product: useId(), course: useId() };
  const upload = async (file?: File) => {
    if (!file || !id) return;
    setUploading(true); setMessage('');
    try {
      const intent = await api.post<{ assetId: string; uploadUrl: string }>(`/classes/${id}/media/upload-intents`, { filename: file.name, mimeType: file.type, sizeBytes: file.size, purpose: 'DOCUMENT' });
      await putToObjectStore(intent.uploadUrl, file, 'Tải tệp thất bại');
      await api.post(`/media/${intent.assetId}/complete`);
      setForm(current => ({ ...current, mediaAssetId: intent.assetId }));
      setFileName(file.name);
      setOk(true); setMessage(`Đã tải lên ${file.name}.`);
    } catch (e) { setOk(false); setMessage(e instanceof Error ? e.message : 'Không thể tải tệp.'); }
    finally { setUploading(false); }
  };
  const submit = async (event: React.FormEvent) => {
    event.preventDefault(); setMessage('');
    try {
      await api.post(`/classes/${id}/documents`, { ...form, targetProductId: form.visibility === 'PRODUCT_OWNER' ? form.targetProductId || null : null, targetCourseId: form.visibility === 'PRODUCT_OWNER' ? form.targetCourseId || null : null });
      setForm(EMPTY_DOCUMENT); setFileName('');
      setOk(true); setMessage('Đã tạo tài liệu.');
    } catch (e) { setOk(false); setMessage(e instanceof Error ? e.message : 'Không thể tạo tài liệu.'); }
  };
  return (
    <StudioPage width="narrow">
      <PageHeader title="Tài liệu" description="Tệp được lưu riêng tư; máy chủ kiểm tra quyền xem và chỉ phát đường dẫn có hạn ngắn." />
      {canCreate ? (
        <Card as="section" aria-labelledby="document-form-title">
          <form onSubmit={submit} className="space-y-5">
            <CardHeader id="document-form-title" title="Thêm tài liệu" description="Tải tệp lên trước, rồi đặt tên và quyền xem." />
            <Field label="Tệp" htmlFor={ids.file} hint={uploading ? 'Đang tải tệp lên...' : 'PDF, slide, bảng tính hoặc tệp nén.'}>
              <input
                id={ids.file}
                required
                type="file"
                disabled={uploading}
                onChange={e => void upload(e.target.files?.[0])}
                className="block w-full text-meta text-slate-600 file:mr-3 file:h-10 file:cursor-pointer file:rounded-btn file:border file:border-solid file:border-slate-200 file:bg-white file:px-4 file:text-ui file:font-semibold file:text-slate-900 hover:file:bg-slate-100"
              />
            </Field>
            {form.mediaAssetId && (
              <Notice tone="success">
                <span className="inline-flex items-center gap-1.5">
                  <FileCheck2 className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                  Tệp đã tải lên và sẵn sàng đính kèm{fileName ? `: ${fileName}` : ''}.
                </span>
              </Notice>
            )}
            <Field label="Tên tài liệu" htmlFor={ids.title}>
              <Input id={ids.title} required value={form.title} onChange={e => setForm({ ...form, title: e.target.value })} />
            </Field>
            <Field label="Mô tả" htmlFor={ids.description}>
              <Input id={ids.description} value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} />
            </Field>
            <Field label="Quyền xem" htmlFor={ids.visibility}>
              <Select id={ids.visibility} value={form.visibility} onChange={e => setForm({ ...form, visibility: e.target.value, targetProductId: '', targetCourseId: '' })} className="sm:max-w-xs">
                <option value="FREE">Mọi thành viên</option>
                <option value="PRO">Chỉ học viên PRO</option>
                <option value="PRODUCT_OWNER">Người đã mua sản phẩm / khóa học</option>
              </Select>
            </Field>
            {form.visibility === 'PRODUCT_OWNER' && (
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label="Mã sản phẩm (chọn một trong hai)" htmlFor={ids.product}>
                  <Input id={ids.product} value={form.targetProductId} onChange={e => setForm({ ...form, targetProductId: e.target.value, targetCourseId: '' })} className="font-mono" />
                </Field>
                <Field label="Mã khóa học" htmlFor={ids.course}>
                  <Input id={ids.course} value={form.targetCourseId} onChange={e => setForm({ ...form, targetCourseId: e.target.value, targetProductId: '' })} className="font-mono" />
                </Field>
              </div>
            )}
            <div className="flex flex-col-reverse gap-3 sm:flex-row sm:items-center sm:justify-between">
              <ResultLine message={message} ok={ok} />
              <Button type="submit" variant="primary" disabled={uploading || !form.mediaAssetId}>Tạo tài liệu</Button>
            </div>
          </form>
        </Card>
      ) : (
        <NoPermission>Bạn cần quyền DOCUMENT:CREATE để thêm tài liệu. Hãy nhờ chủ lớp cấp quyền nếu cần.</NoPermission>
      )}
    </StudioPage>
  );
};

export const StudioAbout: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const canEdit = hasStudioPermission(classroom, 'ABOUT', 'EDIT');
  const [sections, setSections] = useState<AboutSection[]>([]);
  const [version, setVersion] = useState(1);
  const [saving, setSaving] = useState(false);
  const [contentMarkdown, setContent] = useState(''); const [rulesMarkdown, setRules] = useState(''); const [message, setMessage] = useState('');
  const [ok, setOk] = useState(false);
  const contentId = useId();
  const rulesId = useId();
  const load = async () => { try { const about = await api.get<{contentMarkdown:string;rulesMarkdown:string;sections?:AboutSection[];publishedVersion:number}>(`/classes/${id}/about`); setContent(about.contentMarkdown || ''); setRules(about.rulesMarkdown || ''); setSections(about.sections || []); setVersion(about.publishedVersion); } catch (e) { setOk(false); setMessage(e instanceof Error ? e.message : 'Không thể tải giới thiệu.'); } };
  React.useEffect(() => { if (canEdit) void load(); }, [id, canEdit]);
  const save = async (event: React.FormEvent) => { event.preventDefault(); setSaving(true); try { const result = await api.put<{publishedVersion:number}>(`/classes/${id}/about`, {contentMarkdown,rulesMarkdown,sections:sections.map(s=>({...s,imageUrl:s.mediaAssetId?'':s.imageUrl})),publishedVersion:version}); setVersion(result.publishedVersion); setOk(true); setMessage('Đã lưu thông tin lớp.'); } catch(e) { setOk(false); setMessage(e instanceof Error ? e.message : 'Không thể lưu.'); } finally { setSaving(false); } };
  return (
    <StudioPage width="narrow">
      <PageHeader title="Giới thiệu & nội quy" description="Trang Giới thiệu là nơi người mới quyết định có tham gia lớp hay không: lớp dành cho ai, học được gì, nội quy ra sao." />
      {canEdit ? (
        <form onSubmit={save} className="space-y-5">
          <Card as="section" aria-labelledby="about-intro-title" className="space-y-4">
            <CardHeader id="about-intro-title" title="Giới thiệu" description="Lớp dành cho ai, học xong làm được gì, ai đồng hành." />
            <Field label="Nội dung giới thiệu" htmlFor={contentId} hint="Hỗ trợ Markdown.">
              <Textarea id={contentId} maxLength={100000} rows={8} value={contentMarkdown} onChange={e => setContent(e.target.value)} />
            </Field>
          </Card>
          <Card as="section">
            <AboutSectionsEditor classId={id!} sections={sections} onChange={setSections} />
          </Card>
          <Card as="section" aria-labelledby="about-rules-title" className="space-y-4">
            <CardHeader id="about-rules-title" title="Nội quy" description="Vài quy tắc ngắn, rõ ràng giúp lớp giữ được không khí tử tế." />
            <Field label="Nội dung nội quy" htmlFor={rulesId} hint="Hỗ trợ Markdown.">
              <Textarea id={rulesId} maxLength={100000} rows={8} value={rulesMarkdown} onChange={e => setRules(e.target.value)} />
            </Field>
          </Card>
          <div className="flex flex-col-reverse gap-3 sm:flex-row sm:items-center sm:justify-between">
            <ResultLine message={message} ok={ok} />
            <Button type="submit" variant="primary" disabled={saving}>{saving ? 'Đang lưu...' : 'Lưu'}</Button>
          </div>
        </form>
      ) : (
        <NoPermission>Bạn cần quyền ABOUT:EDIT để sửa trang giới thiệu và nội quy. Hãy nhờ chủ lớp cấp quyền nếu cần.</NoPermission>
      )}
    </StudioPage>
  );
};
