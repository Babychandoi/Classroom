import React, { useEffect, useId, useImperativeHandle, useRef, useState } from 'react';
import {
  Bold, Check, ChevronDown, ChevronRight, Circle, FileText, GripVertical, Heading2, Italic, Link2, List, ListOrdered, Pencil, Play, Plus, Trash2, Video,
} from 'lucide-react';
import type { Lesson, Section } from '../../types';
import { api } from '../../api/client';
import { putToObjectStore } from '../../api/upload';
import { nextPosition } from '../../api/ordering';
import { Modal } from '../../components/Modal';
import { SafeMarkdown } from '../../components/SafeMarkdown';
import { Badge, Button, Input, Select, Textarea, Toggle, inputClass } from '../../components/ui';
import { ModalActions } from './studioUi';

// Step 3 of the course wizard ("Thêm bài học & tài liệu"): the content studio. Three columns on wide screens (structure tree,
// lesson detail, preview + checklist), stacked on phones. It talks to the same section / lesson endpoints the old inline editor
// used; the host only supplies the data and `onChanged` (re-read the course).

export const LESSON_TYPE_LABELS: Record<string, string> = { VIDEO: 'Video', TEXT: 'Văn bản', DOCUMENT: 'Tài liệu', ASSIGNMENT: 'Bài tập' };
type LessonType = Lesson['type'];
export const LESSON_TITLE_MAX = 200;

const cx = (...parts: Array<string | false | null | undefined>) => parts.filter(Boolean).join(' ');

interface LessonDraft {
  title: string;
  type: LessonType;
  contentText: string;
  captionsVtt: string;
  /** '' = no file. A new id only becomes the lesson's file when saved. */
  mediaAssetId: string;
  durationMinutes: number;
}
const draftOf = (l: Lesson): LessonDraft => ({
  title: l.title, type: l.type, contentText: l.contentText || '', captionsVtt: l.captionsVtt || '', mediaAssetId: l.mediaAssetId || '', durationMinutes: l.durationMinutes || 0,
});
const sameDraft = (a: LessonDraft, b: LessonDraft) =>
  a.title === b.title && a.type === b.type && a.contentText === b.contentText && a.captionsVtt === b.captionsVtt
  && a.mediaAssetId === b.mediaAssetId && a.durationMinutes === b.durationMinutes;

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
  | { kind: 'delete-lesson'; id: string; label: string };

type Drag = { kind: 'lesson' | 'section'; id: string; sectionId?: string };

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

const MarkdownEditor: React.FC<{ id: string; value: string; onChange: (v: string) => void; label: string; placeholder: string; disabled: boolean }> = ({
  id, value, onChange, label, placeholder, disabled,
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
        <div data-testid="markdown-preview" className="min-h-[120px] rounded-input border border-slate-200 bg-white px-3.5 py-3">
          {value.trim() ? <SafeMarkdown source={value} size="body" /> : <p className="text-ui text-slate-500">Chưa có nội dung để xem trước.</p>}
        </div>
      ) : (
        <Textarea ref={ref} id={id} rows={8} value={value} disabled={disabled} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
      )}
      <p className="text-caption text-slate-500">Hỗ trợ định dạng Markdown đơn giản: **đậm**, *nghiêng*, ## tiêu đề, danh sách và liên kết https.</p>
    </div>
  );
};

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
  const [tab, setTab] = useState<'content' | 'docs'>('content');
  const [saving, setSaving] = useState(false);
  const [savedAt, setSavedAt] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [mediaName, setMediaName] = useState('');
  const [errors, setErrors] = useState<{ tree?: string; lesson?: string }>({});
  const [addingSection, setAddingSection] = useState(false);
  const [newSectionTitle, setNewSectionTitle] = useState('');
  const [addingLessonIn, setAddingLessonIn] = useState<string | null>(null);
  const [newLessonTitle, setNewLessonTitle] = useState('');
  const [editingSectionId, setEditingSectionId] = useState<string | null>(null);
  const [editSectionTitle, setEditSectionTitle] = useState('');
  const [confirm, setConfirm] = useState<Confirm | null>(null);
  const [pending, setPending] = useState(false);
  const [drag, setDrag] = useState<Drag | null>(null);
  const [over, setOver] = useState<{ id: string; after: boolean } | null>(null);
  const [announce, setAnnounce] = useState('');
  const focusHandle = useRef<string | null>(null);

  const allLessons = sections.flatMap((s) => s.lessons ?? []);
  const lesson = allLessons.find((l) => l.id === selectedId) ?? null;
  const dirty = !!(lesson && draft && !sameDraft(draft, draftOf(lesson)));
  const lessonCount = sections.filter((s) => !s.archived).reduce((n, s) => n + (s.lessons ?? []).filter((l) => !l.archived).length, 0);
  const sectionCount = sections.filter((s) => !s.archived).length;

  useEffect(() => { onDirtyChange?.(dirty); }, [dirty, onDirtyChange]);

  // A different lesson (or a lesson that disappeared) resets the form to what the server has.
  useEffect(() => {
    if (!lesson) { setDraft(null); return; }
    setDraft(draftOf(lesson));
    setMediaName('');
    setTab('content');
    setSavedAt(null);
    setErrors((e) => ({ ...e, lesson: undefined }));
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

  const fail = (scope: 'tree' | 'lesson', message: string | null) => setErrors((e) => ({ ...e, [scope]: message ?? undefined }));

  const saveLesson = async (): Promise<boolean> => {
    if (!lesson || !draft || !canEdit) return true;
    if (!draft.title.trim()) { fail('lesson', 'Nhập tên bài học trước khi lưu.'); return false; }
    setSaving(true);
    fail('lesson', null);
    try {
      await api.put(`/lessons/${lesson.id}`, {
        title: draft.title.trim(),
        type: draft.type,
        contentText: draft.contentText,
        captionsVtt: draft.captionsVtt,
        // A blank id detaches the file; null would leave it untouched.
        mediaAssetId: draft.mediaAssetId,
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

  const uploadFile = async (file?: File) => {
    if (!file || !draft) return;
    setUploading(true);
    fail('lesson', null);
    try {
      const intent = await api.post<{ assetId: string; uploadUrl: string }>(`/classes/${classId}/media/upload-intents`, {
        filename: file.name, mimeType: file.type, sizeBytes: file.size, purpose: 'LESSON', scopeCourseId: courseId,
      });
      await putToObjectStore(intent.uploadUrl, file, 'Tải file thất bại');
      await api.post(`/media/${intent.assetId}/complete`);
      setDraft((d) => (d ? { ...d, mediaAssetId: intent.assetId } : d));
      setMediaName(file.name);
    } catch (err: any) {
      fail('lesson', err.message || 'Không thể tải media');
    } finally {
      setUploading(false);
    }
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

  const addLesson = async (section: Section) => {
    if (!newLessonTitle.trim()) return;
    fail('tree', null);
    if (dirty && !(await saveLesson())) return;
    try {
      const created = await api.post<Lesson>(`/sections/${section.id}/lessons`, {
        title: newLessonTitle, type: 'VIDEO', contentText: '', captionsVtt: '', mediaAssetId: null, position: nextPosition(section.lessons),
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
    setPending(true);
    try {
      if (c.kind === 'delete-section') await api.delete(`/sections/${c.id}`);
      else await api.delete(`/lessons/${c.id}`);
      if (c.kind === 'delete-lesson' && c.id === selectedId) setSelectedId(null);
      await onChanged();
      fail(c.kind === 'delete-lesson' ? 'lesson' : 'tree', null);
    } catch (err: any) {
      fail(c.kind === 'delete-lesson' ? 'lesson' : 'tree', err.message || 'Thao tác thất bại');
    } finally {
      setPending(false);
      setConfirm(null);
    }
  };

  // ---- reordering (drag and drop + Alt+arrow on the handle) --------------------------------------------------------------
  const sendOrder = async (kind: 'lesson' | 'section', sectionId: string | undefined, order: string[], movedId: string) => {
    fail('tree', null);
    try {
      if (kind === 'section') await api.put(`/courses/${courseId}/sections/reorder`, order);
      else await api.put(`/sections/${sectionId}/lessons/reorder`, order);
      focusHandle.current = movedId;
      setAnnounce(`Đã chuyển đến vị trí ${order.indexOf(movedId) + 1} trên ${order.length}`);
      await onChanged();
    } catch (err: any) {
      fail('tree', err.message || (kind === 'section' ? 'Không thể sắp xếp lại danh mục' : 'Không thể sắp xếp lại bài học'));
    }
  };

  const moveBy = (kind: 'lesson' | 'section', sectionId: string | undefined, list: { id: string }[], id: string, delta: -1 | 1) => {
    const index = list.findIndex((x) => x.id === id);
    const target = index + delta;
    if (index < 0 || target < 0 || target >= list.length) return;
    void sendOrder(kind, sectionId, reorderIds(list.map((x) => x.id), id, list[target].id, delta > 0), id);
  };

  const handleKey = (e: React.KeyboardEvent, kind: 'lesson' | 'section', sectionId: string | undefined, list: { id: string }[], id: string) => {
    if (!canEdit || !e.altKey || (e.key !== 'ArrowUp' && e.key !== 'ArrowDown')) return;
    e.preventDefault();
    moveBy(kind, sectionId, list, id, e.key === 'ArrowUp' ? -1 : 1);
  };

  const dragProps = (kind: 'lesson' | 'section', id: string, sectionId: string | undefined, list: { id: string }[]) => ({
    draggable: canEdit,
    onDragStart: (e: React.DragEvent) => {
      e.stopPropagation();
      setDrag({ kind, id, sectionId });
      e.dataTransfer.effectAllowed = 'move';
      e.dataTransfer.setData('text/plain', id);
    },
    onDragOver: (e: React.DragEvent) => {
      if (!drag || drag.kind !== kind || drag.id === id || (kind === 'lesson' && drag.sectionId !== sectionId)) return;
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
      if (order.join() !== list.map((x) => x.id).join()) void sendOrder(kind, sectionId, order, drag.id);
    },
    onDragEnd: () => { setDrag(null); setOver(null); },
  });
  const dropMark = (id: string) => (over?.id === id ? (over.after ? 'shadow-[inset_0_-2px_0_#2563EB]' : 'shadow-[inset_0_2px_0_#2563EB]') : '');

  const toggleCollapsed = (id: string) => setCollapsed((cur) => { const next = new Set(cur); if (next.has(id)) next.delete(id); else next.add(id); return next; });

  // ---- render --------------------------------------------------------------------------------------------------------
  const card = 'rounded-card border border-slate-200 bg-white p-4 shadow-hairline sm:p-5';
  const hasContent = !!(draft && (draft.contentText.trim() || draft.mediaAssetId || draft.captionsVtt.trim()));
  const hasMedia = !!draft?.mediaAssetId;
  const tabs = draft && draft.type !== 'VIDEO' ? ([['content', 'Nội dung'], ['docs', 'Tài liệu']] as const) : ([['content', 'Nội dung']] as const);

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
            <div key={section.id} className={cx('rounded-2xl border border-slate-200', drag?.id === section.id && 'opacity-50', dropMark(section.id))} {...dragProps('section', section.id, undefined, sections)} draggable={canEdit && editingSectionId !== section.id && addingLessonIn !== section.id}>
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
  const typeId = `${ids}-type`;
  const durId = `${ids}-dur`;
  const bodyId = `${ids}-body`;
  const vttId = `${ids}-vtt`;
  const fileId = `${ids}-file`;
  const disabled = !canEdit || saving;

  const detail = !lesson || !draft ? (
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
            <label htmlFor={titleId} className="block text-meta font-semibold text-slate-900">A. Tên bài học <span className="text-red-600" aria-hidden="true">*</span></label>
            <span className="text-caption text-slate-500 tabular" aria-hidden="true">{draft.title.length}/{LESSON_TITLE_MAX}</span>
          </div>
          <Input id={titleId} value={draft.title} maxLength={LESSON_TITLE_MAX} disabled={disabled} onChange={(e) => setDraft({ ...draft, title: e.target.value })} />
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-1.5">
            <label htmlFor={typeId} className="block text-meta font-semibold text-slate-900">Loại bài học</label>
            <Select id={typeId} value={draft.type} disabled={disabled} onChange={(e) => { setDraft({ ...draft, type: e.target.value as LessonType }); setTab('content'); }}>
              <option value="VIDEO">Video</option><option value="TEXT">Văn bản</option><option value="DOCUMENT">Tài liệu</option><option value="ASSIGNMENT">Bài tập</option>
            </Select>
          </div>
          <div className="space-y-1.5">
            <label htmlFor={durId} className="block text-meta font-semibold text-slate-900">Thời lượng (phút)</label>
            <Input id={durId} type="number" min={0} className="tabular" value={draft.durationMinutes} disabled={disabled} onChange={(e) => setDraft({ ...draft, durationMinutes: parseInt(e.target.value) || 0 })} />
          </div>
        </div>
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl bg-slate-50 px-4 py-3">
          <div className="min-w-0">
            <p className="text-ui font-semibold text-slate-900">B. Nháp</p>
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
      </section>

      <section aria-label="Thêm nội dung" className={cx(card, 'space-y-4')}>
        <h3 className="text-[16px] font-semibold leading-6 text-slate-900">C. Thêm nội dung</h3>
        <div role="tablist" aria-label="Loại nội dung" className="flex gap-1 border-b border-slate-200">
          {tabs.map(([key, label], i) => (
            <button
              key={key} type="button" role="tab" id={`${ids}-tab-${key}`} aria-selected={tab === key} aria-controls={`${ids}-panel-${key}`} tabIndex={tab === key ? 0 : -1}
              onClick={() => setTab(key)}
              onKeyDown={(e) => {
                if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
                  e.preventDefault();
                  const next = tabs[(i + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length][0];
                  setTab(next);
                  document.getElementById(`${ids}-tab-${next}`)?.focus();
                }
              }}
              className={cx('-mb-px h-10 border-b-2 px-4 text-ui font-semibold', tab === key ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-600 hover:text-slate-900')}
            >
              {label}
            </button>
          ))}
        </div>

        {tab === 'content' && (
          <div role="tabpanel" id={`${ids}-panel-content`} aria-labelledby={`${ids}-tab-content`} className="space-y-4">
            {draft.type === 'VIDEO' ? (
              <>
                <div className="space-y-1.5">
                  <label htmlFor={fileId} className="block text-meta font-semibold text-slate-900">Video bài học (tải lên)</label>
                  <input id={fileId} type="file" accept="video/*" disabled={disabled || uploading} onChange={(e) => uploadFile(e.target.files?.[0])}
                    className="block w-full text-meta text-slate-600 file:mr-3 file:h-9 file:cursor-pointer file:rounded-[10px] file:border file:border-solid file:border-slate-200 file:bg-white file:px-3 file:text-meta file:font-semibold file:text-slate-900 hover:file:bg-slate-100" />
                  <p className="text-caption text-slate-500">Video được tải lên kho lưu trữ của hệ thống. Chưa hỗ trợ dán liên kết YouTube, Vimeo hoặc Bunny.</p>
                </div>
                <FileState uploading={uploading} assetId={draft.mediaAssetId} name={mediaName} url={lesson.mediaAssetId === draft.mediaAssetId ? lesson.mediaDownloadUrl : undefined} onRemove={() => setDraft({ ...draft, mediaAssetId: '' })} disabled={disabled} />
                <div className="space-y-1.5">
                  <label htmlFor={bodyId} className="block text-meta font-semibold text-slate-900">Bản chép lời và mô tả hình ảnh của video</label>
                  <Textarea id={bodyId} rows={5} value={draft.contentText} disabled={disabled} placeholder="Bản chép lời và mô tả hình ảnh của video" onChange={(e) => setDraft({ ...draft, contentText: e.target.value })} />
                </div>
                <div className="space-y-1.5">
                  <label htmlFor={vttId} className="block text-meta font-semibold text-slate-900">Phụ đề WebVTT</label>
                  <Textarea id={vttId} rows={5} maxLength={1000000} className="font-mono text-meta" value={draft.captionsVtt} disabled={disabled}
                    placeholder={'WEBVTT\n\n00:00.000 --> 00:05.000\nLời giảng và âm thanh cần thiết'} onChange={(e) => setDraft({ ...draft, captionsVtt: e.target.value })} />
                  <p className="text-caption text-slate-500">Cung cấp lời thoại, người nói và âm thanh cần thiết; mô tả hình ảnh trong nội dung bài học.</p>
                </div>
              </>
            ) : (
              <MarkdownEditor
                id={bodyId} value={draft.contentText} disabled={disabled} onChange={(v) => setDraft({ ...draft, contentText: v })}
                label={draft.type === 'ASSIGNMENT' ? 'Yêu cầu bài tập' : draft.type === 'DOCUMENT' ? 'Mô tả tài liệu' : 'Nội dung bài viết'}
                placeholder={draft.type === 'ASSIGNMENT' ? 'Mô tả yêu cầu, tiêu chí chấm và hạn nộp...' : 'Soạn nội dung bài học...'}
              />
            )}
          </div>
        )}

        {tab === 'docs' && draft.type !== 'VIDEO' && (
          <div role="tabpanel" id={`${ids}-panel-docs`} aria-labelledby={`${ids}-tab-docs`} className="space-y-3">
            <div className="space-y-1.5">
              <label htmlFor={fileId} className="block text-meta font-semibold text-slate-900">{draft.type === 'DOCUMENT' ? 'Tài liệu (PDF)' : 'Tệp đính kèm'}</label>
              <input id={fileId} type="file" accept={draft.type === 'DOCUMENT' ? 'application/pdf,.pdf' : undefined} disabled={disabled || uploading} onChange={(e) => uploadFile(e.target.files?.[0])}
                className="block w-full text-meta text-slate-600 file:mr-3 file:h-9 file:cursor-pointer file:rounded-[10px] file:border file:border-solid file:border-slate-200 file:bg-white file:px-3 file:text-meta file:font-semibold file:text-slate-900 hover:file:bg-slate-100" />
              <p className="text-caption text-slate-500">Mỗi bài học đính kèm được một tệp.</p>
            </div>
            <FileState uploading={uploading} assetId={draft.mediaAssetId} name={mediaName} url={lesson.mediaAssetId === draft.mediaAssetId ? lesson.mediaDownloadUrl : undefined} onRemove={() => setDraft({ ...draft, mediaAssetId: '' })} disabled={disabled} />
          </div>
        )}

        {errors.lesson && <p role="alert" className="text-meta font-medium text-red-600">{errors.lesson}</p>}
        {canEdit && (
          <div className="flex flex-wrap items-center justify-end gap-3 border-t border-slate-100 pt-4">
            <span role="status" aria-live="polite" className="text-caption text-slate-600">
              {saving ? 'Đang lưu...' : dirty ? 'Có thay đổi chưa lưu' : savedAt ? `Đã lưu lúc ${savedAt}` : ''}
            </span>
            <Button variant="primary" size="md" disabled={saving || uploading || !dirty} onClick={() => void saveLesson()}>Lưu bài học</Button>
          </div>
        )}
      </section>
    </div>
  );

  const metaChips: string[] = [];
  if (draft) {
    if (draft.type === 'VIDEO' && hasMedia) metaChips.push('Có video');
    if (draft.type !== 'VIDEO' && hasMedia) metaChips.push('Có tệp đính kèm');
    if (draft.contentText.trim()) metaChips.push(draft.type === 'VIDEO' ? 'Có bản chép lời' : 'Có bài viết');
    if (draft.captionsVtt.trim()) metaChips.push('Có phụ đề');
    if (draft.durationMinutes > 0) metaChips.push(`${draft.durationMinutes} phút`);
  }
  const checks = draft ? [
    { ok: !!draft.title.trim(), label: 'Đã nhập tiêu đề bài học' },
    { ok: hasContent, label: 'Đã thêm nội dung' },
  ] : [];

  const side = (
    <div className="space-y-4">
      <section aria-label="Xem trước bài học" className={card}>
        <h3 className="mb-3 text-[16px] font-semibold leading-6 text-slate-900">Xem trước bài học</h3>
        <div data-testid="lesson-preview" className="flex aspect-video w-full flex-col items-center justify-center rounded-2xl bg-slate-900 p-4 text-center text-white">
          {draft ? (
            <>
              {draft.type === 'VIDEO' ? <Play className="mb-2 h-8 w-8" strokeWidth={1.5} aria-hidden="true" /> : <FileText className="mb-2 h-8 w-8" strokeWidth={1.5} aria-hidden="true" />}
              <p className="line-clamp-2 text-ui font-semibold">{draft.title.trim() || 'Chưa đặt tên bài học'}</p>
            </>
          ) : (
            <p className="text-meta text-slate-300">Chọn một bài học để xem trước.</p>
          )}
        </div>
        {draft && (
          <div className="mt-3 flex flex-wrap gap-1.5">
            <Badge tone="level" size="sm">{LESSON_TYPE_LABELS[draft.type]}</Badge>
            {lesson?.archived && <Badge tone="warn" size="sm">Nháp</Badge>}
            {metaChips.map((c) => <Badge key={c} tone="info" size="sm">{c}</Badge>)}
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
