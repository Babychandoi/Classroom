import React, { useEffect, useId, useRef, useState } from 'react';
import { Link, useOutletContext } from 'react-router-dom';
import { ExternalLink, ImagePlus, Lock, PenLine, Plus } from 'lucide-react';
import {
  blogPostDate,
  createBlogPost,
  deleteBlogPost,
  getBlogPost,
  listBlogPosts,
  publishBlogPost,
  unpublishBlogPost,
  updateBlogPost,
  type BlogPostInput,
  type BlogStatusFilter,
} from '../../api/blog';
import { COVER_ACCEPT, coverFileProblem, uploadCoverImage } from '../../api/coverUpload';
import { formatDate } from '../../api/format';
import { hasStudioPermission } from '../../api/permissions';
import { BlogCategoryChip, BlogCover } from '../../components/BlogBits';
import { Modal } from '../../components/Modal';
import { ErrorBanner, LoadingSpinner } from '../../components/UIStates';
import { ModalActions, Notice, PageHeader, StudioPage } from './studioUi';
import { Badge, Button, Card, Field, Input, SegmentTabs, Select, Textarea, buttonClass } from '../../components/ui';
import type { BlogAudience, BlogPost, Classroom } from '../../types';

// Studio > Blog: list of the class's posts (Tất cả / Nháp / Đã đăng), a create/edit form in a modal (title, excerpt,
// category, audience, markdown content, cover image), publish / unpublish and delete with confirmation.
// Every action is gated on the BLOG grants of the viewer; the server enforces the same rules anyway.

type Filter = 'ALL' | 'DRAFT' | 'PUBLISHED';
const FILTERS: { key: Filter; label: string }[] = [
  { key: 'ALL', label: 'Tất cả' },
  { key: 'DRAFT', label: 'Nháp' },
  { key: 'PUBLISHED', label: 'Đã đăng' },
];
const PAGE_SIZE = 20;

interface FormState {
  title: string;
  excerpt: string;
  category: string;
  audience: BlogAudience;
  contentMarkdown: string;
  coverMediaId: string | null;
  coverPreview: string | null;
}

const emptyForm = (): FormState => ({ title: '', excerpt: '', category: '', audience: 'PUBLIC', contentMarkdown: '', coverMediaId: null, coverPreview: null });

const messageOf = (err: unknown, fallback: string) => (err instanceof Error && err.message ? err.message : fallback);

export const StudioBlog: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom; refreshClassroom: () => Promise<void> }>();
  const can = (action: string) => hasStudioPermission(classroom, 'BLOG', action);
  const canCreate = can('CREATE');
  const canEdit = can('EDIT');
  const canPublish = can('PUBLISH');
  const canDelete = can('DELETE');
  // Any BLOG grant may list drafts (status=DRAFT|ALL); without one the server answers 403, so only published posts are asked for.
  const canSeeDrafts = can('VIEW') || canCreate || canEdit || canPublish || canDelete;

  const [filter, setFilter] = useState<Filter>(canSeeDrafts ? 'ALL' : 'PUBLISHED');
  const [posts, setPosts] = useState<BlogPost[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const [editing, setEditing] = useState<{ id: string | null } | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [loadingPost, setLoadingPost] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState<BlogPost | null>(null);
  const seq = useRef(0);
  const formId = useId();

  const status: BlogStatusFilter = filter === 'ALL' ? 'ALL' : filter === 'DRAFT' ? 'DRAFT' : 'PUBLISHED';

  const load = async () => {
    const mine = ++seq.current;
    setLoading(true);
    setError(null);
    try {
      const page = await listBlogPosts(classroom.id, { status: canSeeDrafts ? status : 'PUBLISHED', size: PAGE_SIZE });
      if (mine !== seq.current) return;
      setPosts(page?.items ?? []);
      setNextCursor(page?.nextCursor ?? null);
    } catch (err) {
      if (mine === seq.current) setError(messageOf(err, 'Không thể tải danh sách bài viết.'));
    } finally {
      if (mine === seq.current) setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [classroom.id, filter]);

  const loadMore = async () => {
    if (!nextCursor) return;
    const mine = seq.current;
    setLoadingMore(true);
    try {
      const page = await listBlogPosts(classroom.id, { status: canSeeDrafts ? status : 'PUBLISHED', size: PAGE_SIZE, cursor: nextCursor });
      if (mine !== seq.current) return;
      setPosts((current) => [...current, ...(page?.items ?? []).filter((p) => !current.some((c) => c.id === p.id))]);
      setNextCursor(page?.nextCursor ?? null);
    } catch (err) {
      setActionError(messageOf(err, 'Không thể tải thêm bài viết.'));
    } finally {
      setLoadingMore(false);
    }
  };

  const openCreate = () => {
    setForm(emptyForm());
    setFormError(null);
    setEditing({ id: null });
  };

  // List rows carry no content (contentMarkdown is null in list responses): read the full post before editing.
  const openEdit = async (post: BlogPost) => {
    setFormError(null);
    setEditing({ id: post.id });
    setForm({
      title: post.title,
      excerpt: post.excerpt ?? '',
      category: post.category ?? '',
      audience: post.audience,
      contentMarkdown: '',
      coverMediaId: post.coverMediaId ?? null,
      coverPreview: post.coverUrl ?? null,
    });
    setLoadingPost(true);
    try {
      const full = await getBlogPost(post.id);
      setForm((current) => ({ ...current, contentMarkdown: full.contentMarkdown ?? '', coverPreview: full.coverUrl ?? current.coverPreview }));
    } catch (err) {
      setFormError(messageOf(err, 'Không thể tải nội dung bài viết.'));
    } finally {
      setLoadingPost(false);
    }
  };

  const closeForm = () => { if (!saving && !uploading) setEditing(null); };

  const onCover = async (file?: File) => {
    if (!file) return;
    const problem = coverFileProblem(file);
    if (problem) { setFormError(problem); return; }
    setUploading(true);
    setFormError(null);
    try {
      const assetId = await uploadCoverImage(classroom.id, file, 'BLOG');
      let preview: string | null = null;
      try { preview = URL.createObjectURL(file); } catch { preview = null; }
      setForm((current) => ({ ...current, coverMediaId: assetId, coverPreview: preview }));
    } catch (err) {
      setFormError(messageOf(err, 'Không thể tải ảnh bìa.'));
    } finally {
      setUploading(false);
    }
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!editing) return;
    const title = form.title.trim();
    if (!title) { setFormError('Hãy nhập tiêu đề bài viết.'); return; }
    setSaving(true);
    setFormError(null);
    try {
      if (editing.id) {
        // PUT: every field optional - send them all; an emptied excerpt/category is sent as "" so it is cleared.
        const body: Partial<BlogPostInput> = {
          title,
          excerpt: form.excerpt.trim(),
          category: form.category.trim(),
          contentMarkdown: form.contentMarkdown,
          audience: form.audience,
        };
        if (form.coverMediaId) body.coverMediaId = form.coverMediaId;
        const saved = await updateBlogPost(editing.id, body);
        if (saved) setPosts((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        setNotice('Đã lưu thay đổi.');
      } else {
        // Content is optional for a draft (publishing an empty post is refused by the server with a clear 400).
        const body: BlogPostInput = { title, audience: form.audience };
        if (form.contentMarkdown.trim()) body.contentMarkdown = form.contentMarkdown;
        if (form.excerpt.trim()) body.excerpt = form.excerpt.trim();
        if (form.category.trim()) body.category = form.category.trim();
        if (form.coverMediaId) body.coverMediaId = form.coverMediaId;
        await createBlogPost(classroom.id, body);
        setNotice(canPublish ? 'Đã lưu bản nháp. Bấm "Đăng bài" khi bạn sẵn sàng.' : 'Đã lưu bản nháp. Người có quyền đăng bài sẽ xuất bản.');
        void load();
      }
      setEditing(null);
    } catch (err) {
      setFormError(messageOf(err, 'Không thể lưu bài viết.'));
    } finally {
      setSaving(false);
    }
  };

  const togglePublish = async (post: BlogPost) => {
    setBusyId(post.id);
    setActionError(null);
    setNotice(null);
    try {
      const updated = post.status === 'PUBLISHED' ? await unpublishBlogPost(post.id) : await publishBlogPost(post.id);
      if (updated) {
        // In a filtered view the row moves to the other tab.
        setPosts((list) => (filter === 'ALL' ? list.map((p) => (p.id === updated.id ? updated : p)) : list.filter((p) => p.id !== updated.id)));
      }
      setNotice(post.status === 'PUBLISHED' ? 'Đã gỡ bài về bản nháp.' : 'Đã đăng bài. Thành viên lớp đọc được ngay trong tab Blog.');
    } catch (err) {
      setActionError(messageOf(err, 'Không thể đổi trạng thái bài viết.'));
    } finally {
      setBusyId(null);
    }
  };

  const remove = async () => {
    if (!confirmDelete) return;
    const post = confirmDelete;
    setBusyId(post.id);
    setActionError(null);
    try {
      await deleteBlogPost(post.id);
      setPosts((list) => list.filter((p) => p.id !== post.id));
      setNotice('Đã xóa bài viết.');
      setConfirmDelete(null);
    } catch (err) {
      setActionError(messageOf(err, 'Không thể xóa bài viết.'));
      setConfirmDelete(null);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <StudioPage>
      <PageHeader
        title="Blog"
        description="Viết bài cho tab Blog của lớp. Bài mới luôn là bản nháp cho đến khi được đăng."
        action={canCreate ? (
          <Button variant="primary" onClick={openCreate}>
            <Plus className="h-4 w-4" strokeWidth={2} aria-hidden="true" />
            Viết bài mới
          </Button>
        ) : undefined}
      />

      {canSeeDrafts && (
        <SegmentTabs ariaLabel="Lọc theo trạng thái" items={FILTERS} value={filter} onChange={(key) => setFilter(key as Filter)} />
      )}

      {notice && <Notice tone="success" role="status">{notice}</Notice>}
      {actionError && <ErrorBanner message={actionError} />}

      <Card padded={false}>
        {loading ? (
          <LoadingSpinner message="Đang tải bài viết..." />
        ) : error ? (
          <div className="p-5"><ErrorBanner message={error} onRetry={load} /></div>
        ) : posts.length === 0 ? (
          <div className="flex flex-col items-center p-10 text-center">
            <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-community bg-slate-100 text-slate-500">
              <PenLine className="h-6 w-6" strokeWidth={1.7} aria-hidden="true" />
            </div>
            <h2 className="text-h3 font-semibold text-slate-900">{filter === 'DRAFT' ? 'Không có bản nháp nào' : filter === 'PUBLISHED' ? 'Chưa có bài nào được đăng' : 'Lớp chưa có bài viết nào'}</h2>
            <p className="mt-1 max-w-sm text-ui text-slate-600">Bài đầu tiên nên giải một câu hỏi thành viên hay gặp — ngắn, cụ thể, đọc xong làm được ngay.</p>
            {canCreate && <Button variant="secondary" className="mt-5" onClick={openCreate}>Viết bài đầu tiên</Button>}
          </div>
        ) : (
          <ul className="divide-y divide-slate-100">
            {posts.map((post) => (
              <li key={post.id} className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:gap-4 sm:px-6">
                <div className="flex min-w-0 flex-1 items-center gap-4">
                  <div className="h-12 w-[76px] flex-shrink-0 overflow-hidden rounded-thumb bg-slate-100">
                    <BlogCover src={post.coverUrl} seed={post.category || post.id} iconSize={20} />
                  </div>
                  <div className="min-w-0">
                    <p className="truncate text-ui font-semibold text-slate-900">{post.title}</p>
                    <div className="mt-1 flex flex-wrap items-center gap-1.5 text-caption text-slate-500">
                      {post.status === 'PUBLISHED' ? <Badge tone="success" size="sm">Đã đăng</Badge> : <Badge tone="warn" size="sm">Nháp</Badge>}
                      {post.audience === 'MEMBERS' && <Badge tone="member" size="sm"><Lock className="h-3 w-3" strokeWidth={2} aria-hidden="true" />Thành viên</Badge>}
                      <BlogCategoryChip category={post.category} />
                      <span className="tabular">{formatDate(blogPostDate(post))}</span>
                      <span aria-hidden="true">·</span>
                      <span className="truncate">{post.author.fullName}</span>
                    </div>
                  </div>
                </div>
                <div className="flex flex-shrink-0 flex-wrap items-center gap-2 sm:justify-end">
                  {post.status === 'PUBLISHED' && (
                    <Link to={`/classes/${classroom.slug}/blog/${post.id}`} className={buttonClass('ghost', 'sm')} aria-label={`Xem bài ${post.title}`}>
                      <ExternalLink className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />Xem
                    </Link>
                  )}
                  {canEdit && <Button size="sm" onClick={() => void openEdit(post)} aria-label={`Sửa bài ${post.title}`}>Sửa</Button>}
                  {canPublish && (
                    <Button size="sm" disabled={busyId === post.id} onClick={() => void togglePublish(post)}>
                      {post.status === 'PUBLISHED' ? 'Gỡ xuống' : 'Đăng bài'}
                    </Button>
                  )}
                  {canDelete && (
                    <Button size="sm" variant="danger" disabled={busyId === post.id} onClick={() => setConfirmDelete(post)} aria-label={`Xóa bài ${post.title}`}>Xóa</Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {nextCursor && !loading && (
        <div className="text-center">
          <Button onClick={loadMore} disabled={loadingMore}>{loadingMore ? 'Đang tải...' : 'Xem thêm'}</Button>
        </div>
      )}

      {editing && (
        <Modal title={editing.id ? 'Sửa bài viết' : 'Viết bài mới'} onClose={closeForm} size="lg">
          <form id={formId} onSubmit={submit} className="space-y-4" noValidate>
            {loadingPost && <p role="status" className="text-meta text-slate-500">Đang tải nội dung bài viết...</p>}
            <Field label="Tiêu đề" htmlFor={`${formId}-title`}>
              <Input id={`${formId}-title`} required maxLength={200} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
            </Field>
            <Field label="Tóm tắt" htmlFor={`${formId}-excerpt`} hint="Hiện trên thẻ bài viết, tối đa 300 ký tự.">
              <Textarea id={`${formId}-excerpt`} rows={2} maxLength={300} value={form.excerpt} onChange={(e) => setForm({ ...form, excerpt: e.target.value })} />
            </Field>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Chuyên mục" htmlFor={`${formId}-category`} hint="Ví dụ: Bắt đầu, Mẹo học, Câu chuyện.">
                <Input id={`${formId}-category`} maxLength={60} value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })} />
              </Field>
              <Field label="Ai được đọc" htmlFor={`${formId}-audience`}>
                <Select id={`${formId}-audience`} value={form.audience} onChange={(e) => setForm({ ...form, audience: e.target.value as BlogAudience })}>
                  <option value="PUBLIC">Mọi người xem được lớp</option>
                  <option value="MEMBERS">Chỉ thành viên lớp</option>
                </Select>
              </Field>
            </div>
            <Field label="Nội dung" htmlFor={`${formId}-content`} hint="Hỗ trợ # tiêu đề, - danh sách, **chữ đậm**, [liên kết](https://...).">
              <Textarea
                id={`${formId}-content`}
                rows={12}
                maxLength={100000}
                disabled={loadingPost}
                value={form.contentMarkdown}
                onChange={(e) => setForm({ ...form, contentMarkdown: e.target.value })}
                className="font-mono text-meta"
              />
            </Field>
            <div className="space-y-1.5">
              <span className="block text-meta font-semibold text-slate-900">Ảnh bìa</span>
              <div className="flex flex-wrap items-center gap-4">
                <div className="h-[72px] w-32 overflow-hidden rounded-thumb border border-slate-200 bg-slate-100">
                  <BlogCover src={form.coverPreview} seed={form.category || form.title || 'blog'} iconSize={22} alt="Xem trước ảnh bìa" />
                </div>
                <label className={buttonClass('secondary', 'md', uploading ? 'pointer-events-none opacity-60' : 'cursor-pointer')}>
                  <ImagePlus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                  {uploading ? 'Đang tải ảnh...' : form.coverMediaId ? 'Đổi ảnh bìa' : 'Chọn ảnh bìa'}
                  <input
                    type="file"
                    accept={COVER_ACCEPT}
                    aria-label="Tải ảnh bìa"
                    className="sr-only"
                    disabled={uploading}
                    onChange={(e) => { void onCover(e.target.files?.[0]); e.target.value = ''; }}
                  />
                </label>
              </div>
              <p className="text-caption text-slate-500">JPG, PNG, WebP hoặc GIF, tối đa 5 MB. Không có ảnh, thẻ bài dùng ô màu theo chuyên mục.</p>
            </div>
            {formError && <p role="alert" className="rounded-btn bg-red-50 px-3.5 py-2.5 text-meta text-red-700">{formError}</p>}
            <ModalActions className="border-t border-slate-100 pt-4">
              <Button onClick={closeForm} disabled={saving || uploading}>Hủy</Button>
              <Button type="submit" variant="primary" disabled={saving || uploading || loadingPost}>
                {saving ? 'Đang lưu...' : editing.id ? 'Lưu thay đổi' : 'Lưu bản nháp'}
              </Button>
            </ModalActions>
          </form>
        </Modal>
      )}

      {confirmDelete && (
        <Modal title="Xóa bài viết?" onClose={() => setConfirmDelete(null)} size="sm" role="alertdialog">
          <p className="text-ui text-slate-600">“{confirmDelete.title}” sẽ bị xóa vĩnh viễn và không khôi phục được.</p>
          <ModalActions className="mt-5">
            <Button onClick={() => setConfirmDelete(null)}>Giữ lại</Button>
            <Button variant="danger" onClick={() => void remove()} disabled={busyId === confirmDelete.id}>Xóa bài viết</Button>
          </ModalActions>
        </Modal>
      )}
    </StudioPage>
  );
};
