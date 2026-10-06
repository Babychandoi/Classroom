import React, { useEffect, useId, useImperativeHandle, useRef, useState } from 'react';
import {
  Bold, Check, ChevronDown, ChevronRight, Circle, ClipboardCheck, FileText, GripVertical, Heading2, Italic, Link2, List, ListOrdered, Paperclip, Pencil, Play, Plus,
  Trash2, Video,
} from 'lucide-react';
import type { Lesson, LessonAttachment, LessonComponents, Section } from '../../types';
import { api } from '../../api/client';
import { putToObjectStore } from '../../api/upload';
import { nextPosition } from '../../api/ordering';
import { bundleOf, componentChips, formatBytes, hasAnyComponent } from '../../api/lessonComponents';
import { Modal } from '../../components/Modal';
import { SafeMarkdown } from '../../components/SafeMarkdown';
import { Badge, Button, Input, Textarea, Toggle, inputClass } from '../../components/ui';
import { PROVIDER_LABEL, VideoProvider, parseVideoLink, videoLinkError } from '../../api/videoLinks';
import { ModalActions } from './studioUi';

// Step 3 of the course wizard ("Thêm bài học & tài liệu"): the content studio. Three columns on wide screens (structure tree,
// lesson detail, preview + checklist), stacked on phones. A lesson is a bundle of optional components - Video, Nội dung, Tài liệu
// (several files), Bài tập - not one "type". Lesson fields are saved with "Lưu bài học"; documents are persisted the moment they are
// added, renamed, reordered or removed.

export const LESSON_TITLE_MAX = 200;
export const MAX_ATTACHMENTS = 20;

const cx = (...parts: Array<string | false | null | undefined>) => parts.filter(Boolean).join(' ');

interface LessonDraft {
  title: string;
  contentText: string;
  captionsVtt: string;
  /** Uploaded video ('' = none). A new id only becomes the lesson's video when saved. */
  mediaAssetId: string;
  /** Pasted YouTube / Google Drive link ('' = none). The video is one source: this or an uploaded file. */
  videoUrl: string;
  hasAssignment: boolean;
  assignmentInstructions: string;
  durationMinutes: number;
}
type VideoSource = 'NONE' | 'UPLOAD' | VideoProvider;

const draftOf = (l: Lesson): LessonDraft => ({
  title: l.title,
  contentText: l.contentText || '',
  captionsVtt: l.captionsVtt || '',
  mediaAssetId: l.mediaAssetId || '',
  videoUrl: l.videoProvider === 'YOUTUBE' || l.videoProvider === 'GOOGLE_DRIVE' ? l.videoUrl || '' : '',
  hasAssignment: !!l.hasAssignment,
  assignmentInstructions: l.assignmentInstructions || '',
  durationMinutes: l.durationMinutes || 0,
});
const sourceOf = (l: Lesson): VideoSource =>
  l.videoProvider === 'YOUTUBE' || l.videoProvider === 'GOOGLE_DRIVE' ? l.videoProvider : l.mediaAssetId || l.videoProvider === 'UPLOAD' ? 'UPLOAD' : 'NONE';
/** A pasted link and the canonical one the server stores for it are the same source. */
const canonLink = (u: string) => parseVideoLink(u)?.videoUrl ?? u.trim();
const sameDraft = (a: LessonDraft, b: LessonDraft) =>
  a.title === b.title && a.contentText === b.contentText && a.captionsVtt === b.captionsVtt
  && a.mediaAssetId === b.mediaAssetId && canonLink(a.videoUrl) === canonLink(b.videoUrl) && a.hasAssignment === b.hasAssignment
  && a.assignmentInstructions === b.assignmentInstructions && a.durationMinutes === b.durationMinutes;

/** Moves `movedId` next to `targetId` (before or after it) in `ids`. */
export function reorderIds(ids: string[], movedId: string, targetId: string, after: boolean): string[] {
  if (movedId === targetId) return ids;
  const rest = ids.filter((id) => id !== movedId);
  const at = rest.indexOf(targetId);
  if (at < 0) return ids;
  rest.splice(after ? at + 1 : at, 0, movedId);
  return rest;
}

export interface ContentStudioHandle {
  /** Saves the open lesson when it has unsaved edits. Resolves false when saving failed. */
  flush: () => Promise<boolean>;
}

type Confirm =
  | { kind: 'delete-section'; id: string; label: string }
  | { kind: 'delete-lesson'; id: string; label: string }
  | { kind: 'delete-attachment'; id: string; label: string };

/** `sectionId` is the parent list of the dragged row: its section for a lesson, the lesson for a document. */
type Drag = { kind: 'lesson' | 'section' | 'attachment'; id: string; sectionId?: string };

const MD_ACTIONS: { key: string; label: string; icon: React.ReactNode }[] = [
  { key: 'bold', label: 'In đậm', icon: <Bold className="h-4 w-4" strokeWidth={1.75} /> },
  { key: 'italic', label: 'In nghiêng', icon: <Italic className="h-4 w-4" strokeWidth={1.75} /> },
  { key: 'heading', label: 'Tiêu đề', icon: <Heading2 className="h-4 w-4" strokeWidth={1.75} /> },
  { key: 'ul', label: 'Danh sách', icon: <List className="h-4 w-4" strokeWidth={1.75} /> },
  { key: 'ol', label: 'Danh sách số', icon: <ListOrdered className="h-4 w-4" strokeWidth={1.75} /> },
  { key: 'link', label: 'Liên kết', icon: <Link2 className="h-4 w-4" strokeWidth={1.75} /> },
];

/** Applies a Markdown action to the textarea's selection and returns the new text + caret range. */
export function applyMarkdown(text: string, start: number, end: number, key: string): { text: string; start: number; end: number } {
  const sel = text.slice(start, end);
  const wrap = (open: string, close: string, placeholder: string) => {
    const inner = sel || placeholder;
    return { text: text.slice(0, start) + open + inner + close + text.slice(end), start: start + open.length, end: start + open.length + inner.length };
  };
  const linePrefix = (prefixFor: (i: number) => string, placeholder: string) => {
    const lineStart = text.lastIndexOf('\n', start - 1) + 1;
    const block = text.slice(lineStart, end) || placeholder;
    const next = block.split('\n').map((line, i) => prefixFor(i) + line).join('\n');
    return { text: text.slice(0, lineStart) + next + text.slice(end), start: lineStart, end: lineStart + next.length };
  };
  switch (key) {
    case 'bold': return wrap('**', '**', 'chữ đậm');
    case 'italic': return wrap('*', '*', 'chữ nghiêng');
    case 'heading': return linePrefix(() => '## ', 'Tiêu đề');
    case 'ul': return linePrefix(() => '- ', 'Mục danh sách');
    case 'ol': return linePrefix((i) => `${i + 1}. `, 'Mục danh sách');
    case 'link': {
      const label = sel || 'nội dung liên kết';
      const out = `[${label}](https://)`;
      const urlStart = start + label.length + 3;
      return { text: text.slice(0, start) + out + text.slice(end), start: urlStart, end: urlStart + 8 };
    }
    default: return { text, start, end };
  }
}

const MarkdownEditor: React.FC<{ id: string; value: string; onChange: (v: string) => void; label: string; placeholder: string; disabled: boolean; rows?: number; testId?: string }> = ({
  id, value, onChange, label, placeholder, disabled, rows = 8, testId = 'markdown-preview',
}) => {
  const ref = useRef<HTMLTextAreaElement>(null);
  const [preview, setPreview] = useState(false);
  const run = (key: string) => {
    const el = ref.current;
    if (!el) return;
    const out = applyMarkdown(value, el.selectionStart, el.selectionEnd, key);
    onChange(out.text);
    requestAnimationFrame(() => { el.focus(); el.setSelectionRange(out.start, out.end); });
  };
  return (
    <div className="space-y-1.5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <label htmlFor={id} className="block text-meta font-semibold text-slate-900">{label}</label>
        <button
          type="button" aria-pressed={preview} onClick={() => setPreview((p) => !p)}
          className="h-8 rounded-[10px] px-2.5 text-meta font-semibold text-blue-600 hover:bg-tint"
        >
          {preview ? 'Quay lại soạn thảo' : 'Xem trước nội dung'}
        </button>
      </div>
      {!preview && (
        <div role="toolbar" aria-label="Định dạng văn bản" className="flex flex-wrap gap-1 rounded-btn border border-slate-200 bg-slate-50 p-1">
          {MD_ACTIONS.map((a) => (
            <button
              key={a.key} type="button" aria-label={a.label} title={a.label} disabled={disabled} onClick={() => run(a.key)}
              className="inline-flex h-8 w-8 items-center justify-center rounded-[8px] text-slate-600 hover:bg-white hover:text-slate-900 disabled:opacity-40"
            >
              {a.icon}
            </button>
          ))}
        </div>
      )}
      {preview ? (
        <div data-testid={testId} className="min-h-[120px] rounded-input border border-slate-200 bg-white px-3.5 py-3">
          {value.trim() ? <SafeMarkdown source={value} size="body" /> : <p className="text-ui text-slate-500">Chưa có nội dung để xem trước.</p>}
        </div>
      ) : (
        <Textarea ref={ref} id={id} rows={rows} value={value} disabled={disabled} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
      )}
      <p className="text-caption text-slate-500">Hỗ trợ định dạng Markdown đơn giản: **đậm**, *nghiêng*, ## tiêu đề, danh sách và liên kết https.</p>
    </div>
  );
};


const FILE_INPUT_CLASS = 'block w-full text-meta text-slate-600 file:mr-3 file:h-9 file:cursor-pointer file:rounded-[10px] file:border file:border-solid file:border-slate-200 file:bg-white file:px-3 file:text-meta file:font-semibold file:text-slate-900 hover:file:bg-slate-100';

/** Small component icons next to a lesson in the tree (the lesson has no single type). */
const ComponentIcons: React.FC<{ bundle: LessonComponents }> = ({ bundle }) => {
  const chips = componentChips(bundle);
  if (chips.length === 0) return null;
  return (
    <span className="flex flex-shrink-0 items-center gap-1 pr-1 text-slate-500" title={chips.join(', ')}>
      {bundle.video && <Video className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />}
      {bundle.content && <FileText className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />}
      {bundle.attachments > 0 && <Paperclip className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />}
      {bundle.assignment && <ClipboardCheck className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />}
      <span className="sr-only">{chips.join(', ')}</span>
    </span>
  );
};

const ComponentCard: React.FC<{ title: string; present: boolean; label: string; children: React.ReactNode; hint?: React.ReactNode }> = ({ title, present, label, children, hint }) => (
  <section aria-label={title} className="space-y-4 rounded-card border border-slate-200 bg-white p-4 shadow-hairline sm:p-5">
    <div className="flex flex-wrap items-center justify-between gap-2">
      <h3 className="text-[16px] font-semibold leading-6 text-slate-900">{title}</h3>
      <Badge tone={present ? 'success' : 'neutral'} size="sm">{present ? 'Đã có' : 'Chưa có'}<span className="sr-only"> {label}</span></Badge>
    </div>
    {hint && <p className="-mt-2 text-caption text-slate-500">{hint}</p>}
    {children}
  </section>
);

export const CourseContentStudio = React.forwardRef<ContentStudioHandle, {
  classId: string;
  courseId: string;
  sections: Section[];
  canEdit: boolean;
  onChanged: () => Promise<void> | void;
  /** Reports whether the open lesson has unsaved edits (the wizard folds it into its leave confirmation). */
  onDirtyChange?: (dirty: boolean) => void;
}>(({ classId, courseId, sections, canEdit, onChanged, onDirtyChange }, handleRef) => {
  const ids = useId();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [draft, setDraft] = useState<LessonDraft | null>(null);
  const [saving, setSaving] = useState(false);
  const [savedAt, setSavedAt] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadingDoc, setUploadingDoc] = useState(false);
  const [mediaName, setMediaName] = useState('');
  const [source, setSource] = useState<VideoSource>('NONE');
  const [switchTo, setSwitchTo] = useState<VideoSource | null>(null);
  const [errors, setErrors] = useState<{ tree?: string; lesson?: string; docs?: string }>({});
  const [addingSection, setAddingSection] = useState(false);
  const [newSectionTitle, setNewSectionTitle] = useState('');
  const [addingLessonIn, setAddingLessonIn] = useState<string | null>(null);
  const [newLessonTitle, setNewLessonTitle] = useState('');
  const [editingSectionId, setEditingSectionId] = useState<string | null>(null);
  const [editSectionTitle, setEditSectionTitle] = useState('');
  const [editingAttId, setEditingAttId] = useState<string | null>(null);
  const [editAttTitle, setEditAttTitle] = useState('');
  const [confirm, setConfirm] = useState<Confirm | null>(null);
  const [pending, setPending] = useState(false);
  const [drag, setDrag] = useState<Drag | null>(null);
  const [over, setOver] = useState<{ id: string; after: boolean } | null>(null);
  const [announce, setAnnounce] = useState('');
  const focusHandle = useRef<string | null>(null);

  const allLessons = sections.flatMap((s) => s.lessons ?? []);
  const lesson = allLessons.find((l) => l.id === selectedId) ?? null;
  const attachments: LessonAttachment[] = lesson?.attachments ?? [];
  const dirty = !!(lesson && draft && !sameDraft(draft, draftOf(lesson)));
  const lessonCount = sections.filter((s) => !s.archived).reduce((n, s) => n + (s.lessons ?? []).filter((l) => !l.archived).length, 0);
  const sectionCount = sections.filter((s) => !s.archived).length;

  useEffect(() => { onDirtyChange?.(dirty); }, [dirty, onDirtyChange]);

  // A different lesson (or a lesson that disappeared) resets the form to what the server has.
  useEffect(() => {
    if (!lesson) { setDraft(null); return; }
    setDraft(draftOf(lesson));
    setMediaName('');
    setSource(sourceOf(lesson));
    setSwitchTo(null);
    setEditingAttId(null);
    setSavedAt(null);
    setErrors((e) => ({ ...e, lesson: undefined, docs: undefined }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId]);

  // After a reload the lesson may have changed on the server (archive toggle, saved edits): keep an untouched form in step with it.
  useEffect(() => {
    if (lesson && draft && !dirty) {
      const fresh = draftOf(lesson);
      if (!sameDraft(draft, fresh)) setDraft(fresh);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lesson]);

  useEffect(() => {
    if (selectedId && !lesson) setSelectedId(null);
  }, [selectedId, lesson]);

  useEffect(() => {
    const id = focusHandle.current;
    if (!id) return;
    focusHandle.current = null;
    document.querySelector<HTMLElement>(`[data-handle="${id}"]`)?.focus();
  }, [sections]);

  const fail = (scope: 'tree' | 'lesson' | 'docs', message: string | null) => setErrors((e) => ({ ...e, [scope]: message ?? undefined }));

  const linkSource = source === 'YOUTUBE' || source === 'GOOGLE_DRIVE' ? source : null;
  const linkErr = draft && linkSource ? videoLinkError(draft.videoUrl, linkSource) : null;
  const link = draft && linkSource ? parseVideoLink(draft.videoUrl) : null;

  const saveLesson = async (): Promise<boolean> => {
    if (!lesson || !draft || !canEdit) return true;
    if (!draft.title.trim()) { fail('lesson', 'Nhập tên bài học trước khi lưu.'); return false; }
    if (linkErr) { fail('lesson', 'Sửa liên kết video trước khi lưu.'); return false; }
    if (draft.hasAssignment && !draft.assignmentInstructions.trim()) { fail('lesson', 'Nhập yêu cầu bài tập hoặc tắt “Có bài tập” trước khi lưu.'); return false; }
    setSaving(true);
    fail('lesson', null);
    try {
      await api.put(`/lessons/${lesson.id}`, {
        title: draft.title.trim(),
        contentText: draft.contentText,
        captionsVtt: draft.captionsVtt,
        // One video source: a blank media id detaches the upload, a blank videoUrl clears the link (null would leave it untouched).
        mediaAssetId: source === 'UPLOAD' ? draft.mediaAssetId : '',
        videoUrl: linkSource ? draft.videoUrl.trim() : '',
        hasAssignment: draft.hasAssignment,
        assignmentInstructions: draft.assignmentInstructions,
        durationMinutes: draft.durationMinutes,
      });
      await onChanged();
      setSavedAt(new Date().toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }));
      return true;
    } catch (err: any) {
      fail('lesson', err.message || 'Cập nhật bài học thất bại');
      return false;
    } finally {
      setSaving(false);
    }
  };

  useImperativeHandle(handleRef, () => ({ flush: async () => (dirty ? saveLesson() : true) }));

  const selectLesson = async (id: string) => {
    if (id === selectedId) return;
    if (dirty && !(await saveLesson())) return;
    setSelectedId(id);
  };

  /** Intent -> PUT to the object store -> complete; resolves the media asset id. */
  const uploadAsset = async (file: File): Promise<string> => {
    const intent = await api.post<{ assetId: string; uploadUrl: string }>(`/classes/${classId}/media/upload-intents`, {
      filename: file.name, mimeType: file.type, sizeBytes: file.size, purpose: 'LESSON', scopeCourseId: courseId,
    });
    await putToObjectStore(intent.uploadUrl, file, 'Tải file thất bại');
    await api.post(`/media/${intent.assetId}/complete`);
    return intent.assetId;
  };

  const uploadVideo = async (file?: File) => {
    if (!file || !draft) return;
    setUploading(true);
    fail('lesson', null);
    try {
      const assetId = await uploadAsset(file);
      setDraft((d) => (d ? { ...d, mediaAssetId: assetId } : d));
      setMediaName(file.name);
    } catch (err: any) {
      fail('lesson', err.message || 'Không thể tải media');
    } finally {
      setUploading(false);
    }
  };

  // ---- documents: persisted immediately -------------------------------------------------------------------------------
  const addDocuments = async (files: FileList | null) => {
    if (!lesson || !files || files.length === 0) return;
    setUploadingDoc(true);
    fail('docs', null);
    let count = attachments.length;
    try {
      for (const file of Array.from(files)) {
        if (count >= MAX_ATTACHMENTS) { fail('docs', `Mỗi bài học tối đa ${MAX_ATTACHMENTS} tài liệu.`); break; }
        const assetId = await uploadAsset(file);
        await api.post(`/lessons/${lesson.id}/attachments`, { mediaAssetId: assetId, title: file.name });
        count += 1;
      }
    } catch (err: any) {
      fail('docs', err.message || 'Không thể tải tài liệu');
    } finally {
      setUploadingDoc(false);
      await onChanged();
    }
  };

  const renameAttachment = async () => {
    if (!lesson || !editingAttId || !editAttTitle.trim()) return;
    fail('docs', null);
    try {
      await api.patch(`/lessons/${lesson.id}/attachments/${editingAttId}`, { title: editAttTitle.trim() });
      setEditingAttId(null);
      await onChanged();
    } catch (err: any) { fail('docs', err.message || 'Không thể đổi tên tài liệu'); }
  };

  // ---- tree actions --------------------------------------------------------------------------------------------------
  const addSection = async () => {
    if (!newSectionTitle.trim()) return;
    fail('tree', null);
    try {
      await api.post(`/courses/${courseId}/sections`, { title: newSectionTitle, position: nextPosition(sections) });
      setNewSectionTitle('');
      setAddingSection(false);
      await onChanged();
    } catch (err: any) { fail('tree', err.message || 'Tạo danh mục thất bại'); }
  };

  /** Creating a lesson takes just a title: an empty bundle you then fill component by component. */
  const addLesson = async (section: Section) => {
    if (!newLessonTitle.trim()) return;
    fail('tree', null);
    if (dirty && !(await saveLesson())) return;
    try {
      const created = await api.post<Lesson>(`/sections/${section.id}/lessons`, {
        title: newLessonTitle, contentText: '', captionsVtt: '', position: nextPosition(section.lessons),
      });
      setNewLessonTitle('');
      setAddingLessonIn(null);
      await onChanged();
      if (created?.id) setSelectedId(created.id);
    } catch (err: any) { fail('tree', err.message || 'Tạo bài học thất bại'); }
  };

  const renameSection = async () => {
    if (!editingSectionId || !editSectionTitle.trim()) return;
    fail('tree', null);
    try {
      await api.put(`/sections/${editingSectionId}`, { title: editSectionTitle });
      setEditingSectionId(null);
      await onChanged();
    } catch (err: any) { fail('tree', err.message || 'Cập nhật danh mục thất bại'); }
  };

  const archiveSection = async (section: Section) => {
    fail('tree', null);
    try {
      await api.post(`/sections/${section.id}/archive?archived=${!section.archived}`);
      setEditingSectionId(null);
      await onChanged();
    } catch (err: any) { fail('tree', err.message || 'Thao tác thất bại'); }
  };

  const toggleDraftState = async (next: boolean) => {
    if (!lesson) return;
    fail('lesson', null);
    try {
      await api.post(`/lessons/${lesson.id}/archive?archived=${next}`);
      await onChanged();
    } catch (err: any) { fail('lesson', err.message || 'Thao tác thất bại'); }
  };

  const runConfirm = async (c: Confirm) => {
    const scope = c.kind === 'delete-lesson' ? 'lesson' : c.kind === 'delete-attachment' ? 'docs' : 'tree';
    setPending(true);
    try {
      if (c.kind === 'delete-section') await api.delete(`/sections/${c.id}`);
      else if (c.kind === 'delete-attachment') await api.delete(`/lessons/${lesson?.id}/attachments/${c.id}`);
      else await api.delete(`/lessons/${c.id}`);
      if (c.kind === 'delete-lesson' && c.id === selectedId) setSelectedId(null);
      await onChanged();
      fail(scope, null);
    } catch (err: any) {
      fail(scope, err.message || 'Thao tác thất bại');
    } finally {
      setPending(false);
      setConfirm(null);
    }
  };

  // ---- reordering (drag and drop + Alt+arrow on the handle) --------------------------------------------------------------
  const sendOrder = async (kind: Drag['kind'], parentId: string | undefined, order: string[], movedId: string) => {
    const scope = kind === 'attachment' ? 'docs' : 'tree';
    fail(scope, null);
    try {
      if (kind === 'section') await api.put(`/courses/${courseId}/sections/reorder`, order);
      else if (kind === 'attachment') await api.put(`/lessons/${parentId}/attachments/reorder`, { ids: order });
      else await api.put(`/sections/${parentId}/lessons/reorder`, order);
      focusHandle.current = movedId;
      setAnnounce(`Đã chuyển đến vị trí ${order.indexOf(movedId) + 1} trên ${order.length}`);
      await onChanged();
    } catch (err: any) {
      fail(scope, err.message || (kind === 'section' ? 'Không thể sắp xếp lại danh mục' : kind === 'attachment' ? 'Không thể sắp xếp lại tài liệu' : 'Không thể sắp xếp lại bài học'));
    }
  };

  const moveBy = (kind: Drag['kind'], parentId: string | undefined, list: { id: string }[], id: string, delta: -1 | 1) => {
    const index = list.findIndex((x) => x.id === id);
    const target = index + delta;
    if (index < 0 || target < 0 || target >= list.length) return;
    void sendOrder(kind, parentId, reorderIds(list.map((x) => x.id), id, list[target].id, delta > 0), id);
  };

  const handleKey = (e: React.KeyboardEvent, kind: Drag['kind'], parentId: string | undefined, list: { id: string }[], id: string) => {
    if (!canEdit || !e.altKey || (e.key !== 'ArrowUp' && e.key !== 'ArrowDown')) return;
    e.preventDefault();
    moveBy(kind, parentId, list, id, e.key === 'ArrowUp' ? -1 : 1);
  };

  const dragProps = (kind: Drag['kind'], id: string, parentId: string | undefined, list: { id: string }[]) => ({
    draggable: canEdit,
    onDragStart: (e: React.DragEvent) => {
      e.stopPropagation();
      setDrag({ kind, id, sectionId: parentId });
      e.dataTransfer.effectAllowed = 'move';
      e.dataTransfer.setData('text/plain', id);
    },
    onDragOver: (e: React.DragEvent) => {
      if (!drag || drag.kind !== kind || drag.id === id || (kind !== 'section' && drag.sectionId !== parentId)) return;
      e.preventDefault();
      e.stopPropagation();
      const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
      setOver({ id, after: e.clientY > rect.top + rect.height / 2 });
    },
    onDrop: (e: React.DragEvent) => {
      if (!drag || drag.kind !== kind || drag.id === id) return;
      e.preventDefault();
      e.stopPropagation();
      const after = over?.id === id ? over.after : false;
      const order = reorderIds(list.map((x) => x.id), drag.id, id, after);
      setDrag(null);
      setOver(null);
      if (order.join() !== list.map((x) => x.id).join()) void sendOrder(kind, parentId, order, drag.id);
    },
    onDragEnd: () => { setDrag(null); setOver(null); },
  });
  const dropMark = (id: string) => (over?.id === id ? (over.after ? 'shadow-[inset_0_-2px_0_#2563EB]' : 'shadow-[inset_0_2px_0_#2563EB]') : '');

  const toggleCollapsed = (id: string) => setCollapsed((cur) => { const next = new Set(cur); if (next.has(id)) next.delete(id); else next.add(id); return next; });

  const wantsSource = (to: VideoSource) => {
    if (!draft || to === source) return;
    const discards = source === 'UPLOAD' ? !!draft.mediaAssetId : source !== 'NONE' ? !!draft.videoUrl.trim() : false;
    if (discards) setSwitchTo(to);
    else setSource(to);
  };
  const confirmSource = () => {
    if (!switchTo) return;
    setDraft((d) => (d ? { ...d, mediaAssetId: '', videoUrl: '' } : d));
    setMediaName('');
    setSource(switchTo);
    setSwitchTo(null);
  };
  const sourceLabel = (src: VideoSource) => (src === 'NONE' ? 'Không có' : src === 'UPLOAD' ? 'Tải lên' : PROVIDER_LABEL[src]);

  // ---- render --------------------------------------------------------------------------------------------------------
  const card = 'rounded-card border border-slate-200 bg-white p-4 shadow-hairline sm:p-5';

  const tree = (
    <section aria-label="Cấu trúc nội dung" className={card}>
      <div className="mb-3">
        <h3 className="text-[16px] font-semibold leading-6 text-slate-900">Cấu trúc nội dung</h3>
        <p className="mt-0.5 text-meta text-slate-600 tabular">{sectionCount} danh mục • {lessonCount} bài học</p>
      </div>
      {errors.tree && <p role="alert" className="mb-2 text-meta font-medium text-red-600">{errors.tree}</p>}
      <div className="space-y-3">
        {sections.map((section, si) => {
          const lessons = section.lessons ?? [];
          const open = !collapsed.has(section.id);
          const panelId = `${ids}-s-${section.id}`;
          return (
            <div
              key={section.id} className={cx('rounded-2xl border border-slate-200', drag?.id === section.id && 'opacity-50', dropMark(section.id))}
              {...dragProps('section', section.id, undefined, sections)} draggable={canEdit && editingSectionId !== section.id && addingLessonIn !== section.id}
            >
              {editingSectionId === section.id ? (
                <div className="flex flex-col gap-2 rounded-t-2xl bg-slate-50 p-3">
                  <input
                    aria-label="Tên danh mục" value={editSectionTitle} onChange={(e) => setEditSectionTitle(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void renameSection(); } }}
                    className={inputClass('h-10')}
                  />
                  <div className="flex flex-wrap gap-2">
                    <Button size="sm" variant="primary" onClick={renameSection}>Lưu</Button>
                    <Button size="sm" variant="secondary" onClick={() => setEditingSectionId(null)}>Hủy</Button>
                    <Button size="sm" variant="secondary" onClick={() => archiveSection(section)}>{section.archived ? 'Khôi phục danh mục' : 'Lưu trữ danh mục'}</Button>
                  </div>
                </div>
              ) : (
                <div className="flex items-center gap-1 rounded-t-2xl bg-slate-50 px-2 py-2">
                  {canEdit && (
                    <button
                      type="button" data-handle={section.id} onKeyDown={(e) => handleKey(e, 'section', undefined, sections, section.id)}
                      aria-label={`Sắp xếp danh mục "${section.title}": kéo thả, hoặc nhấn Alt + mũi tên lên/xuống`}
                      className="inline-flex h-8 w-6 flex-shrink-0 cursor-grab items-center justify-center rounded-[8px] text-slate-500 hover:bg-white focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-600"
                    >
                      <GripVertical className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                    </button>
                  )}
                  <button
                    type="button" aria-expanded={open} aria-controls={panelId} onClick={() => toggleCollapsed(section.id)}
                    className="flex min-w-0 flex-1 items-center gap-1.5 rounded-[8px] px-1 py-1 text-left hover:bg-white"
                  >
                    {open ? <ChevronDown className="h-4 w-4 flex-shrink-0 text-slate-500" strokeWidth={1.75} aria-hidden="true" /> : <ChevronRight className="h-4 w-4 flex-shrink-0 text-slate-500" strokeWidth={1.75} aria-hidden="true" />}
                    <span className="line-clamp-2 break-words text-ui font-semibold text-slate-900">Danh mục {si + 1}: {section.title}</span>
                    {section.archived && <Badge tone="warn" size="xs" className="ml-1">Đã lưu trữ</Badge>}
                  </button>
                  {canEdit && (
                    <>
                      <button
                        type="button" aria-label={`Sửa danh mục "${section.title}"`} title="Sửa tên danh mục"
                        onClick={() => { setEditingSectionId(section.id); setEditSectionTitle(section.title); fail('tree', null); }}
                        className="inline-flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-[8px] text-slate-500 hover:bg-white hover:text-slate-900"
                      >
                        <Pencil className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                      </button>
                      <button
                        type="button" aria-label={`Xóa danh mục "${section.title}"`} title="Xóa danh mục"
                        onClick={() => setConfirm({ kind: 'delete-section', id: section.id, label: `xóa danh mục "${section.title}"` })}
                        className="inline-flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-[8px] text-slate-500 hover:bg-red-50 hover:text-red-600"
                      >
                        <Trash2 className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                      </button>
                    </>
                  )}
                </div>
              )}
              {open && (
                <div id={panelId} className="px-2 pb-2 pt-1">
                  <ul>
                    {lessons.map((l) => (
                      <li
                        key={l.id}
                        className={cx('flex items-center gap-1 rounded-[10px]', selectedId === l.id ? 'bg-tint' : 'hover:bg-slate-50', drag?.id === l.id && 'opacity-50', dropMark(l.id))}
                        {...dragProps('lesson', l.id, section.id, lessons)}
                      >
                        {canEdit && (
                          <button
                            type="button" data-handle={l.id} onKeyDown={(e) => handleKey(e, 'lesson', section.id, lessons, l.id)}
                            aria-label={`Sắp xếp bài học "${l.title}": kéo thả, hoặc nhấn Alt + mũi tên lên/xuống`}
                            className="inline-flex h-9 w-6 flex-shrink-0 cursor-grab items-center justify-center rounded-[8px] text-slate-500 hover:bg-white focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-600"
                          >
                            <GripVertical className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                          </button>
                        )}
                        <button
                          type="button" onClick={() => void selectLesson(l.id)} aria-current={selectedId === l.id ? 'true' : undefined}
                          className="flex min-h-[36px] min-w-0 flex-1 items-center gap-2 rounded-[8px] px-1.5 py-1 text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-600"
                        >
                          <span className="truncate text-ui text-slate-900">{l.title}</span>
                          {l.archived && <Badge tone="warn" size="xs" className="flex-shrink-0">Nháp</Badge>}
                        </button>
                        <ComponentIcons bundle={bundleOf(l)} />
                      </li>
                    ))}
                  </ul>
                  {lessons.length === 0 && <p className="px-2 py-1.5 text-meta text-slate-500">Danh mục chưa có bài học.</p>}
                  {canEdit && (addingLessonIn === section.id ? (
                    <div className="mt-1 flex flex-col gap-2 rounded-[10px] bg-slate-50 p-2">
                      <input
                        autoFocus aria-label="Tên bài học mới" value={newLessonTitle} maxLength={LESSON_TITLE_MAX} placeholder="Tên bài học"
                        onChange={(e) => setNewLessonTitle(e.target.value)}
                        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void addLesson(section); } if (e.key === 'Escape') setAddingLessonIn(null); }}
                        className={inputClass('h-10')}
                      />
                      <div className="flex gap-2">
                        <Button size="sm" variant="primary" onClick={() => addLesson(section)}>Tạo bài</Button>
                        <Button size="sm" variant="secondary" onClick={() => setAddingLessonIn(null)}>Hủy</Button>
                      </div>
                    </div>
                  ) : (
                    <button
                      type="button" onClick={() => { setAddingLessonIn(section.id); setNewLessonTitle(''); }}
                      className="mt-1 inline-flex h-9 items-center gap-1.5 rounded-[10px] px-2 text-meta font-semibold text-blue-600 hover:bg-tint"
                    >
                      <Plus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />Thêm bài học
                    </button>
                  ))}
                </div>
              )}
            </div>
          );
        })}
        {sections.length === 0 && <p className="rounded-btn bg-slate-50 px-3.5 py-3 text-meta text-slate-500">Khóa học chưa có danh mục nào. Bấm “Thêm danh mục” để bắt đầu, rồi thêm bài học vào danh mục.</p>}
        {canEdit && (addingSection ? (
          <div className="flex flex-col gap-2 rounded-2xl border border-blue-100 bg-tint p-3">
            <input
              autoFocus aria-label="Tên danh mục mới" value={newSectionTitle} placeholder="Tên danh mục (VD: Chương 1: Giới thiệu)"
              onChange={(e) => setNewSectionTitle(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void addSection(); } if (e.key === 'Escape') setAddingSection(false); }}
              className={inputClass('h-10')}
            />
            <div className="flex gap-2">
              <Button size="sm" variant="primary" onClick={addSection}>Lưu danh mục</Button>
              <Button size="sm" variant="secondary" onClick={() => setAddingSection(false)}>Hủy</Button>
            </div>
          </div>
        ) : (
          <button
            type="button" onClick={() => setAddingSection(true)}
            className="flex h-11 w-full items-center justify-center gap-1.5 rounded-2xl border border-dashed border-slate-300 text-ui font-semibold text-slate-600 hover:border-blue-300 hover:bg-tint hover:text-blue-600"
          >
            <Plus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />Thêm danh mục
          </button>
        ))}
      </div>
      {canEdit && <p className="mt-3 text-caption text-slate-500">Kéo thả để sắp xếp danh mục, bài học (hoặc nhấn Alt + mũi tên khi đang chọn biểu tượng ⋮⋮).</p>}
      <p role="status" aria-live="polite" className="sr-only">{announce}</p>
    </section>
  );

  const titleId = `${ids}-title`;
  const durId = `${ids}-dur`;
  const bodyId = `${ids}-body`;
  const vttId = `${ids}-vtt`;
  const fileId = `${ids}-file`;
  const linkId = `${ids}-link`;
  const docsId = `${ids}-docs`;
  const taskId = `${ids}-task`;
  const disabled = !canEdit || saving;

  // What the lesson contains right now: unsaved video / content / assignment edits plus the documents already on the server.
  const live: LessonComponents | null = draft ? {
    video: source === 'UPLOAD' ? !!draft.mediaAssetId : !!link && !linkErr,
    videoProvider: source === 'UPLOAD' ? (draft.mediaAssetId ? 'UPLOAD' : null) : link && !linkErr ? link.provider : null,
    content: !!draft.contentText.trim(),
    attachments: attachments.length,
    assignment: draft.hasAssignment,
  } : null;

  const detail = !lesson || !draft || !live ? (
    <section aria-label="Chi tiết bài học" className={cx(card, 'flex min-h-[220px] flex-col items-center justify-center text-center')}>
      <span aria-hidden="true" className="mb-3 inline-flex h-12 w-12 items-center justify-center rounded-community bg-slate-100 text-slate-500"><FileText className="h-6 w-6" strokeWidth={1.5} /></span>
      <h3 className="text-[16px] font-semibold text-slate-900">Chi tiết bài học</h3>
      <p className="mt-1 max-w-sm text-meta text-slate-600">Chọn một bài học ở cột bên trái để sửa nội dung, hoặc thêm bài học mới vào một danh mục.</p>
    </section>
  ) : (
    <div className="space-y-4">
      <section aria-label="Chi tiết bài học" className={cx(card, 'space-y-4')}>
        <h3 className="text-[16px] font-semibold leading-6 text-slate-900">Chi tiết bài học</h3>
        <div className="space-y-1.5">
          <div className="flex items-baseline justify-between gap-3">
            <label htmlFor={titleId} className="block text-meta font-semibold text-slate-900">Tên bài học <span className="text-red-600" aria-hidden="true">*</span></label>
            <span className="text-caption text-slate-500 tabular" aria-hidden="true">{draft.title.length}/{LESSON_TITLE_MAX}</span>
          </div>
          <Input id={titleId} value={draft.title} maxLength={LESSON_TITLE_MAX} disabled={disabled} onChange={(e) => setDraft({ ...draft, title: e.target.value })} />
        </div>
        <div className="space-y-1.5 sm:max-w-[220px]">
          <label htmlFor={durId} className="block text-meta font-semibold text-slate-900">Thời lượng (phút)</label>
          <Input id={durId} type="number" min={0} className="tabular" value={draft.durationMinutes} disabled={disabled} onChange={(e) => setDraft({ ...draft, durationMinutes: parseInt(e.target.value) || 0 })} />
        </div>
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl bg-slate-50 px-4 py-3">
          <div className="min-w-0">
            <p className="text-ui font-semibold text-slate-900">Nháp</p>
            <p className="text-meta text-slate-600">Bài nháp bị ẩn với học viên; bỏ chọn để hiển thị.</p>
          </div>
          <Toggle label="Nháp (ẩn với học viên)" checked={!!lesson.archived} disabled={!canEdit} onChange={(v) => void toggleDraftState(v)} />
        </div>
        {canEdit && (
          <div className="flex justify-end">
            <Button variant="danger" size="sm" onClick={() => setConfirm({ kind: 'delete-lesson', id: lesson.id, label: `xóa bài học "${lesson.title}"` })}>
              <Trash2 className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />Xoá bài học
            </Button>
          </div>
        )}
        <p className="text-caption text-slate-500">Một bài học có thể gồm video, nội dung, tài liệu và bài tập. Thêm thành phần nào tùy bạn; cần ít nhất một thành phần để khóa học xuất bản được.</p>
      </section>

      <ComponentCard title="Video" present={live.video} label="video">
        <div role="tablist" aria-label="Nguồn video" className="inline-flex flex-wrap rounded-btn bg-slate-100 p-1">
          {(['NONE', 'UPLOAD', 'YOUTUBE', 'GOOGLE_DRIVE'] as const).map((src, i, all) => (
            <button
              key={src} type="button" role="tab" id={`${ids}-src-${src}`} aria-selected={source === src} tabIndex={source === src ? 0 : -1} disabled={disabled}
              onClick={() => wantsSource(src)}
              onKeyDown={(e) => {
                if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
                e.preventDefault();
                const next = all[(i + (e.key === 'ArrowRight' ? 1 : all.length - 1)) % all.length];
                wantsSource(next);
                document.getElementById(`${ids}-src-${next}`)?.focus();
              }}
              className={cx('h-9 rounded-[9px] px-3.5 text-meta font-semibold', source === src ? 'bg-white text-slate-900 shadow-hairline' : 'text-slate-600 hover:text-slate-900')}
            >
              {sourceLabel(src)}
            </button>
          ))}
        </div>
        {source === 'NONE' && <p className="text-meta text-slate-500">Bài học này chưa có video. Chọn một nguồn nếu bạn muốn thêm.</p>}
        {source === 'UPLOAD' && (
          <>
            <div className="space-y-1.5">
              <label htmlFor={fileId} className="block text-meta font-semibold text-slate-900">Video bài học (tải lên)</label>
              <input id={fileId} type="file" accept="video/*" disabled={disabled || uploading} onChange={(e) => uploadVideo(e.target.files?.[0])} className={FILE_INPUT_CLASS} />
              <p className="text-caption text-slate-500">Video được tải lên kho lưu trữ của hệ thống. Muốn dùng video có sẵn, chọn YouTube hoặc Google Drive.</p>
            </div>
            <FileState uploading={uploading} assetId={draft.mediaAssetId} name={mediaName} url={lesson.mediaAssetId === draft.mediaAssetId ? lesson.mediaDownloadUrl : undefined} onRemove={() => setDraft({ ...draft, mediaAssetId: '' })} disabled={disabled} />
          </>
        )}
        {linkSource && (
          <div className="space-y-2">
            <div className="space-y-1.5">
              <label htmlFor={linkId} className="block text-meta font-semibold text-slate-900">Liên kết video {PROVIDER_LABEL[linkSource]}</label>
              <Input
                id={linkId} inputMode="url" autoComplete="off" value={draft.videoUrl} disabled={disabled} aria-invalid={!!linkErr} aria-describedby={`${linkId}-hint`}
                placeholder={linkSource === 'YOUTUBE' ? 'https://www.youtube.com/watch?v=…' : 'https://drive.google.com/file/d/…/view'}
                onChange={(e) => setDraft({ ...draft, videoUrl: e.target.value })}
              />
              {linkErr ? (
                <p id={`${linkId}-hint`} role="alert" className="text-caption text-red-600">{linkErr}</p>
              ) : (
                <p id={`${linkId}-hint`} className="text-caption text-slate-500">
                  {linkSource === 'YOUTUBE'
                    ? 'Dán liên kết video YouTube (watch, youtu.be, shorts, embed). Video được phát bằng youtube-nocookie.com.'
                    : 'Dán liên kết tệp video trên Google Drive và đặt quyền chia sẻ “Bất kỳ ai có liên kết”, nếu không học viên sẽ không xem được.'}
                </p>
              )}
            </div>
            {link && !linkErr && (
              <div className="aspect-video w-full max-w-[520px] overflow-hidden rounded-2xl bg-black">
                <iframe
                  title={`Xem trước video ${PROVIDER_LABEL[link.provider]}`} src={link.embedUrl} loading="lazy"
                  allow="autoplay; encrypted-media; picture-in-picture; fullscreen" referrerPolicy="strict-origin-when-cross-origin"
                  sandbox="allow-scripts allow-same-origin allow-presentation allow-popups" className="h-full w-full border-0"
                />
              </div>
            )}
          </div>
        )}
        {source !== 'NONE' && (
          <div className="space-y-1.5">
            <label htmlFor={vttId} className="block text-meta font-semibold text-slate-900">Phụ đề WebVTT</label>
            <Textarea id={vttId} rows={4} maxLength={1000000} className="font-mono text-meta" value={draft.captionsVtt} disabled={disabled}
              placeholder={'WEBVTT\n\n00:00.000 --> 00:05.000\nLời giảng và âm thanh cần thiết'} onChange={(e) => setDraft({ ...draft, captionsVtt: e.target.value })} />
            <p className="text-caption text-slate-500">Cung cấp lời thoại, người nói và âm thanh cần thiết. Bản chép lời và mô tả hình ảnh viết ở thẻ “Nội dung” bên dưới.</p>
          </div>
        )}
      </ComponentCard>

      <ComponentCard title="Nội dung" present={live.content} label="nội dung">
        <MarkdownEditor
          id={bodyId} value={draft.contentText} disabled={disabled} onChange={(v) => setDraft({ ...draft, contentText: v })}
          label="Nội dung bài học" placeholder="Soạn nội dung bài học, hoặc bản chép lời và mô tả hình ảnh của video..."
        />
      </ComponentCard>

      <ComponentCard
        title="Tài liệu" present={live.attachments > 0} label="tài liệu"
        hint="Tài liệu được lưu ngay khi bạn thêm, đổi tên, sắp xếp hoặc xóa; không cần bấm “Lưu bài học”."
      >
        {attachments.length > 0 ? (
          <ul className="divide-y divide-slate-100 rounded-2xl border border-slate-200">
            {attachments.map((att) => (
              <li
                key={att.id} className={cx('flex items-center gap-1 px-2 py-2', drag?.id === att.id && 'opacity-50', dropMark(att.id))}
                {...dragProps('attachment', att.id, lesson.id, attachments)}
              >
                {canEdit && (
                  <button
                    type="button" data-handle={att.id} onKeyDown={(e) => handleKey(e, 'attachment', lesson.id, attachments, att.id)}
                    aria-label={`Sắp xếp tài liệu "${att.title}": kéo thả, hoặc nhấn Alt + mũi tên lên/xuống`}
                    className="inline-flex h-9 w-6 flex-shrink-0 cursor-grab items-center justify-center rounded-[8px] text-slate-500 hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-600"
                  >
                    <GripVertical className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                  </button>
                )}
                {editingAttId === att.id ? (
                  <div className="flex min-w-0 flex-1 flex-wrap items-center gap-2">
                    <input
                      aria-label="Tên tài liệu" value={editAttTitle} maxLength={200} autoFocus onChange={(e) => setEditAttTitle(e.target.value)}
                      onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void renameAttachment(); } if (e.key === 'Escape') setEditingAttId(null); }}
                      className={inputClass('h-9 min-w-[140px] flex-1')}
                    />
                    <Button size="sm" variant="primary" onClick={renameAttachment}>Lưu tên</Button>
                    <Button size="sm" variant="secondary" onClick={() => setEditingAttId(null)}>Hủy</Button>
                  </div>
                ) : (
                  <>
                    <Paperclip className="h-4 w-4 flex-shrink-0 text-slate-500" strokeWidth={1.75} aria-hidden="true" />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-ui font-medium text-slate-900">{att.title}</span>
                      <span className="block truncate text-caption text-slate-500 tabular">{[att.fileName && att.fileName !== att.title ? att.fileName : null, formatBytes(att.sizeBytes)].filter(Boolean).join(' · ')}</span>
                    </span>
                    {canEdit && (
                      <>
                        <button
                          type="button" aria-label={`Đổi tên tài liệu "${att.title}"`} title="Đổi tên"
                          onClick={() => { setEditingAttId(att.id); setEditAttTitle(att.title); }}
                          className="inline-flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-[8px] text-slate-500 hover:bg-slate-100 hover:text-slate-900"
                        >
                          <Pencil className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                        </button>
                        <button
                          type="button" aria-label={`Xóa tài liệu "${att.title}"`} title="Xóa tài liệu"
                          onClick={() => setConfirm({ kind: 'delete-attachment', id: att.id, label: `xóa tài liệu "${att.title}" khỏi bài học` })}
                          className="inline-flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-[8px] text-slate-500 hover:bg-red-50 hover:text-red-600"
                        >
                          <Trash2 className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                        </button>
                      </>
                    )}
                  </>
                )}
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-meta text-slate-500">Chưa có tài liệu nào.</p>
        )}
        {errors.docs && <p role="alert" className="text-meta font-medium text-red-600">{errors.docs}</p>}
        {canEdit && (
          <div className="space-y-1.5">
            <label htmlFor={docsId} className="block text-meta font-semibold text-slate-900">Thêm tài liệu ({attachments.length}/{MAX_ATTACHMENTS})</label>
            <input
              id={docsId} type="file" multiple disabled={uploadingDoc || attachments.length >= MAX_ATTACHMENTS}
              onChange={(e) => { const files = e.target.files; void addDocuments(files).then(() => { if (e.target) e.target.value = ''; }); }}
              className={FILE_INPUT_CLASS}
            />
            {uploadingDoc && <p role="status" className="text-meta text-slate-600">Đang tải tài liệu lên...</p>}
          </div>
        )}
      </ComponentCard>

      <ComponentCard title="Bài tập" present={live.assignment} label="bài tập">
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl bg-slate-50 px-4 py-3">
          <div className="min-w-0">
            <p className="text-ui font-semibold text-slate-900">Có bài tập</p>
            <p className="text-meta text-slate-600">Học viên nộp bài làm bằng văn bản; bạn chấm ở mục Chấm bài.</p>
          </div>
          <Toggle label="Có bài tập" checked={draft.hasAssignment} disabled={disabled} onChange={(v) => setDraft({ ...draft, hasAssignment: v })} />
        </div>
        {draft.hasAssignment && (
          <MarkdownEditor
            id={taskId} rows={6} testId="assignment-preview" value={draft.assignmentInstructions} disabled={disabled}
            onChange={(v) => setDraft({ ...draft, assignmentInstructions: v })}
            label="Yêu cầu bài tập" placeholder="Mô tả yêu cầu, tiêu chí chấm và hạn nộp..."
          />
        )}
      </ComponentCard>

      <section aria-label="Lưu bài học" className={cx(card, 'space-y-3')}>
        {errors.lesson && <p role="alert" className="text-meta font-medium text-red-600">{errors.lesson}</p>}
        {canEdit && (
          <div className="flex flex-wrap items-center justify-end gap-3">
            <span role="status" aria-live="polite" className="text-caption text-slate-600">
              {saving ? 'Đang lưu...' : dirty ? 'Có thay đổi chưa lưu' : savedAt ? `Đã lưu lúc ${savedAt}` : ''}
            </span>
            <Button variant="primary" size="md" disabled={saving || uploading || !dirty || !!linkErr} onClick={() => void saveLesson()}>Lưu bài học</Button>
          </div>
        )}
      </section>
    </div>
  );

  const chips = live ? componentChips(live) : [];
  const checks = draft && live ? [
    { ok: !!draft.title.trim(), label: 'Đã nhập tiêu đề bài học' },
    { ok: hasAnyComponent(live), label: 'Đã thêm ít nhất một thành phần' },
    ...(draft.hasAssignment ? [{ ok: !!draft.assignmentInstructions.trim(), label: 'Đã nhập yêu cầu bài tập' }] : []),
  ] : [];

  const side = (
    <div className="space-y-4">
      <section aria-label="Xem trước bài học" className={card}>
        <h3 className="mb-3 text-[16px] font-semibold leading-6 text-slate-900">Xem trước bài học</h3>
        <div data-testid="lesson-preview" className="flex aspect-video w-full flex-col items-center justify-center rounded-2xl bg-slate-900 p-4 text-center text-white">
          {draft && live ? (
            <>
              {live.video ? <Play className="mb-2 h-8 w-8" strokeWidth={1.5} aria-hidden="true" /> : <FileText className="mb-2 h-8 w-8" strokeWidth={1.5} aria-hidden="true" />}
              <p className="line-clamp-2 text-ui font-semibold">{draft.title.trim() || 'Chưa đặt tên bài học'}</p>
            </>
          ) : (
            <p className="text-meta text-slate-300">Chọn một bài học để xem trước.</p>
          )}
        </div>
        {draft && (
          <div className="mt-3 flex flex-wrap gap-1.5">
            {lesson?.archived && <Badge tone="warn" size="sm">Nháp</Badge>}
            {chips.map((c) => <Badge key={c} tone="info" size="sm">{c}</Badge>)}
            {draft.durationMinutes > 0 && <Badge tone="level" size="sm">{draft.durationMinutes} phút</Badge>}
            {chips.length === 0 && <Badge tone="neutral" size="sm">Chưa có thành phần nào</Badge>}
          </div>
        )}
      </section>
      <section aria-label="Danh sách kiểm tra" className={card}>
        <h3 className="mb-3 text-[16px] font-semibold leading-6 text-slate-900">Danh sách kiểm tra</h3>
        {draft ? (
          <ul className="space-y-2">
            {checks.map((c) => (
              <li key={c.label} className="flex items-center gap-2.5 text-ui text-slate-900">
                <span aria-hidden="true" className={cx('inline-flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-full', c.ok ? 'bg-green-100 text-green-800' : 'border border-slate-300 text-slate-400')}>
                  {c.ok ? <Check className="h-3 w-3" strokeWidth={3} /> : <Circle className="h-2 w-2" strokeWidth={0} />}
                </span>
                {c.label}
                <span className="sr-only">{c.ok ? ': đạt' : ': chưa có'}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-meta text-slate-600">Chọn một bài học để xem các mục cần hoàn thành.</p>
        )}
      </section>
    </div>
  );

  return (
    <>
      <div className="grid grid-cols-[minmax(0,1fr)] gap-5 min-[1100px]:grid-cols-[320px_minmax(0,1fr)_300px] min-[1100px]:items-start">
        {tree}
        {detail}
        {side}
      </div>
      {switchTo && (
        <Modal size="md" title="Đổi nguồn video?" role="alertdialog" onClose={() => setSwitchTo(null)}>
          <p className="text-ui text-slate-600">
            Mỗi bài học chỉ dùng một nguồn video. Đổi sang {switchTo === 'UPLOAD' ? 'tải lên' : switchTo === 'NONE' ? 'không có video' : PROVIDER_LABEL[switchTo]} sẽ bỏ {source === 'UPLOAD' ? 'video đã tải lên' : source === 'NONE' ? 'video' : `liên kết ${PROVIDER_LABEL[source]}`} khỏi bài học này (áp dụng khi bạn bấm “Lưu bài học”).
          </p>
          <ModalActions className="mt-5">
            <Button variant="secondary" onClick={() => setSwitchTo(null)}>Giữ nguồn hiện tại</Button>
            <Button variant="danger" onClick={confirmSource}>Đổi nguồn video</Button>
          </ModalActions>
        </Modal>
      )}
      {confirm && (
        <Modal size="md" title="Xác nhận thao tác" role="alertdialog" onClose={() => setConfirm(null)}>
          <p className="text-ui text-slate-600">Bạn có chắc chắn muốn {confirm.label}?</p>
          <ModalActions className="mt-5">
            <Button variant="secondary" onClick={() => setConfirm(null)}>Hủy</Button>
            <Button variant="danger" disabled={pending} onClick={() => runConfirm(confirm)}>{pending ? 'Đang xử lý...' : 'Xác nhận'}</Button>
          </ModalActions>
        </Modal>
      )}
    </>
  );
});
CourseContentStudio.displayName = 'CourseContentStudio';

const FileState: React.FC<{ uploading: boolean; assetId: string; name: string; url?: string; onRemove: () => void; disabled: boolean }> = ({
  uploading, assetId, name, url, onRemove, disabled,
}) => {
  if (uploading) return <p className="text-meta text-slate-600">Đang tải lên...</p>;
  if (!assetId) return <p className="text-meta text-slate-500">Chưa có tệp nào.</p>;
  return (
    <p className="flex flex-wrap items-center gap-2 text-meta font-medium text-green-800">
      <Video className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
      {name ? `Đã tải tệp: ${name} (bấm Lưu bài học để gắn)` : 'Đã gắn tệp'}
      {url && <a href={url} target="_blank" rel="noopener noreferrer" className="font-semibold text-blue-600 underline">Xem tệp</a>}
      <button type="button" disabled={disabled} onClick={onRemove} className="font-semibold text-red-700 underline disabled:opacity-40">Gỡ tệp</button>
    </p>
  );
};
