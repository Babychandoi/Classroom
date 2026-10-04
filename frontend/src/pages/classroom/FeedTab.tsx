import React, { useEffect, useId, useState } from 'react';
import { useOutletContext, Link } from 'react-router-dom';
import { Classroom, Post, Comment, CommentPage } from '../../types';
import { api } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { hasStudioPermission } from '../../api/permissions';
import { LoadingSpinner, ErrorBanner, EmptyState } from '../../components/UIStates';
import { Avatar, Badge, buttonClass, inputClass } from '../../components/ui';
import { SafeMarkdown } from '../../components/SafeMarkdown';
import { CalendarDays, Globe, MessageCircle, Pencil, Pin, Send, Star, Trash2, X } from 'lucide-react';

const PAGE_SIZE = 10;
/** R20-03: how many older comments one "Xem thêm bình luận" click loads (the feed itself embeds only the latest few per post). */
const OLDER_COMMENTS_PAGE = 20;

interface FeedPage {
  posts: Post[];
  nextCursor: string | null;
  hasNext: boolean;
}

/** Mirrors FeedService.requiresFeedManagement: targeted or pinned posts are managed content, so
 * even the author needs a current FEED management grant to edit/delete them. */
function requiresFeedManagement(post: Post): boolean {
  const managedVisibility = ['PUBLIC', 'PRO', 'PRODUCT_OWNER', 'SEGMENT'];
  return post.pinned || managedVisibility.includes(post.visibility);
}

export const FeedTab: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const { user } = useAuth();
  const visibilityFieldId = useId();

  const [posts, setPosts] = useState<Post[]>([]);
  const [hasNext, setHasNext] = useState(false);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // New Post Form
  const [newTitle, setNewTitle] = useState('');
  const [newContent, setNewContent] = useState('');
  const [newVisibility, setNewVisibility] = useState<'PUBLIC' | 'FREE' | 'PRO'>('FREE');
  const [posting, setPosting] = useState(false);

  // Comment Form state: map of postId -> comment text
  const [commentInputs, setCommentInputs] = useState<Record<string, string>>({});
  const [commenting, setCommenting] = useState<Record<string, boolean>>({});
  const [loadingOlder, setLoadingOlder] = useState<Record<string, boolean>>({});
  const [editingPost, setEditingPost] = useState<string | null>(null);
  const [postEdits, setPostEdits] = useState<Record<string, { title: string; contentMarkdown: string }>>({});
  const [editingComment, setEditingComment] = useState<string | null>(null);
  const [commentEdits, setCommentEdits] = useState<Record<string, string>>({});
  // R4-09: same grants AccessPolicy.canManage checks server-side, including "*" wildcard staff
  // permissions (e.g. "FEED:*"), so the UI never shows an action the server would refuse.
  const canManageFeedCreate = hasStudioPermission(classroom, 'FEED', 'CREATE');
  const canManageFeedEdit = hasStudioPermission(classroom, 'FEED', 'EDIT');
  const canManageFeedDelete = hasStudioPermission(classroom, 'FEED', 'DELETE');
  // Only an active member (or the owner/staff) may post, comment or see the composer — a
  // non-member viewing a PUBLIC feed must not be shown authoring UI the server would reject.
  const isMember = Boolean(classroom.isMember || classroom.isOwner || classroom.userRole === 'OWNER');

  /** De-dups by id when appending a "Xem thêm" batch — a post already on screen (e.g. one that
   * shifted pages because of a concurrent insert/delete) is never rendered twice (R5-01/R5-03). */
  const appendDedup = (prev: Post[], incoming: Post[]) => {
    const seen = new Set(prev.map((p) => p.id));
    const fresh = incoming.filter((p) => !seen.has(p.id));
    return [...prev, ...fresh];
  };

  const fetchFirstPage = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.get<FeedPage>(`/classes/${classroom.id}/posts?size=${PAGE_SIZE}`);
      setPosts(data?.posts || []);
      setHasNext(Boolean(data?.hasNext));
      setNextCursor(data?.nextCursor ?? null);
    } catch (err: any) {
      setError(err.message || 'Không thể tải bảng tin');
    } finally {
      setLoading(false);
    }
  };

  const handleLoadMore = async () => {
    if (!nextCursor) return;
    try {
      setLoadingMore(true);
      setError(null);
      const data = await api.get<FeedPage>(`/classes/${classroom.id}/posts?cursor=${encodeURIComponent(nextCursor)}&size=${PAGE_SIZE}`);
      setPosts((prev) => appendDedup(prev, data?.posts || []));
      setHasNext(Boolean(data?.hasNext));
      setNextCursor(data?.nextCursor ?? null);
    } catch (err: any) {
      setError(err.message || 'Không thể tải bảng tin');
    } finally {
      setLoadingMore(false);
    }
  };

  useEffect(() => {
    fetchFirstPage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classroom.id, user]);

  const handleCreatePost = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTitle.trim() || !newContent.trim()) return;

    setPosting(true);
    try {
      const created = await api.post<Post>(`/classes/${classroom.id}/posts`, {
        title: newTitle,
        contentMarkdown: newContent,
        visibility: newVisibility,
      });
      setNewTitle('');
      setNewContent('');
      // Insert the new post in place instead of refetching from the start: a plain member's post
      // is always unpinned, so it belongs right above the first unpinned post (after any pinned
      // ones) — matching the server's (pinned DESC, createdAt DESC, id DESC) ordering.
      if (created) {
        setPosts((prev) => {
          const firstUnpinnedIdx = prev.findIndex((p) => !p.pinned);
          const insertAt = created.pinned ? 0 : (firstUnpinnedIdx === -1 ? prev.length : firstUnpinnedIdx);
          return [...prev.slice(0, insertAt), created, ...prev.slice(insertAt)];
        });
      }
    } catch (err: any) {
      alert(err.message || 'Đăng bài thất bại');
    } finally {
      setPosting(false);
    }
  };

  const handleAddComment = async (postId: string) => {
    const text = commentInputs[postId];
    if (!text || !text.trim()) return;

    setCommenting((prev) => ({ ...prev, [postId]: true }));
    try {
      const comment = await api.post<Comment>(`/posts/${postId}/comments`, { content: text });
      setCommentInputs((prev) => ({ ...prev, [postId]: '' }));
      if (comment) {
        setPosts((prev) =>
          prev.map((p) =>
            p.id === postId
              ? { ...p, comments: [...(p.comments || []), comment], commentCount: (p.commentCount || 0) + 1 }
              : p
          )
        );
      }
    } catch (err: any) {
      alert(err.message || 'Bình luận thất bại');
    } finally {
      setCommenting((prev) => ({ ...prev, [postId]: false }));
    }
  };

  /**
   * R20-03: the feed embeds only the latest few comments per post plus the post's total `commentCount`. "Xem thêm bình luận"
   * loads the page of comments immediately older than the oldest one on screen and prepends it (de-duplicated by id).
   */
  const handleLoadOlderComments = async (post: Post) => {
    const oldest = post.comments && post.comments.length > 0 ? post.comments[0] : null;
    setLoadingOlder((prev) => ({ ...prev, [post.id]: true }));
    try {
      const query = oldest
        ? `?before=${encodeURIComponent(oldest.id)}&size=${OLDER_COMMENTS_PAGE}`
        : `?size=${OLDER_COMMENTS_PAGE}`;
      const data = await api.get<CommentPage>(`/posts/${post.id}/comments${query}`);
      const older = data?.comments || [];
      setPosts((prev) =>
        prev.map((p) => {
          if (p.id !== post.id) return p;
          const have = new Set((p.comments || []).map((c) => c.id));
          return { ...p, comments: [...older.filter((c) => !have.has(c.id)), ...(p.comments || [])] };
        })
      );
    } catch (err: any) {
      alert(err.message || 'Không thể tải thêm bình luận');
    } finally {
      setLoadingOlder((prev) => ({ ...prev, [post.id]: false }));
    }
  };

  const handleDeletePost = async (postId: string) => {
    if (!confirm('Bạn có chắc muốn xóa bài viết này không?')) return;
    try {
      await api.delete(`/posts/${postId}`);
      setPosts(posts.filter((p) => p.id !== postId));
    } catch (err: any) {
      alert(err.message || 'Xóa bài thất bại');
    }
  };

  const handleUpdatePost = async (postId: string) => {
    const edit = postEdits[postId];
    if (!edit?.title.trim() || !edit.contentMarkdown.trim()) return;
    try {
      const updated = await api.put<Post>(`/posts/${postId}`, edit);
      setEditingPost(null);
      if (updated) {
        setPosts((prev) => prev.map((p) => (p.id === postId ? { ...p, ...updated, comments: p.comments } : p)));
      }
    } catch (err: any) {
      alert(err.message || 'Sửa bài thất bại');
    }
  };

  const handleUpdateComment = async (commentId: string) => {
    const content = commentEdits[commentId];
    if (!content?.trim()) return;
    try {
      const updated = await api.put<Comment>(`/comments/${commentId}`, { content });
      setEditingComment(null);
      if (updated) {
        setPosts((prev) =>
          prev.map((p) => ({
            ...p,
            comments: p.comments?.map((c) => (c.id === commentId ? { ...c, ...updated } : c)),
          }))
        );
      }
    } catch (err: any) {
      alert(err.message || 'Sửa bình luận thất bại');
    }
  };

  const handleDeleteComment = async (postId: string, commentId: string) => {
    if (!confirm('Bạn có chắc muốn xóa bình luận này không?')) return;
    try {
      await api.delete(`/comments/${commentId}`);
      setPosts((prev) =>
        prev.map((p) =>
          p.id === postId
            ? { ...p, comments: p.comments?.filter((c) => c.id !== commentId), commentCount: Math.max(0, p.commentCount - 1) }
            : p
        )
      );
    } catch (err: any) {
      alert(err.message || 'Xóa bình luận thất bại');
    }
  };

  const firstName = (user?.fullName || '').trim().split(/\s+/).pop() || '';
  const created = monthYear(classroom.createdAt);
  const card = 'rounded-card border border-slate-200 bg-white shadow-hairline';
  const iconBtn = 'inline-flex h-8 w-8 items-center justify-center rounded-full text-slate-400 transition-colors duration-micro hover:bg-slate-100 hover:text-slate-900';

  return (
    <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_360px]">
      <div className="min-w-0 space-y-5">
        {/* Post Composer: only for active members (or owner/staff) — a non-member viewing a
            PUBLIC feed must not see authoring UI the server would reject (R4-09). */}
        {user && isMember && (
          <section aria-label="Tạo bài viết" className={`px-4 py-4 sm:px-5 ${card}`}>
            <form onSubmit={handleCreatePost}>
              <div className="flex items-start gap-3">
                <Avatar name={user.fullName} size={40} />
                <div className="min-w-0 flex-1 space-y-2">
                  <input
                    type="text"
                    required
                    value={newTitle}
                    onChange={(e) => setNewTitle(e.target.value)}
                    placeholder="Tiêu đề bài viết..."
                    aria-label="Tiêu đề bài viết mới"
                    className="h-11 w-full rounded-full bg-slate-100 px-[18px] text-body-sm font-semibold text-slate-900 transition-colors duration-micro placeholder:font-normal placeholder:text-slate-400 hover:bg-slate-200/60 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-600/30"
                  />
                  <textarea
                    rows={3}
                    required
                    value={newContent}
                    onChange={(e) => setNewContent(e.target.value)}
                    placeholder={firstName ? `${firstName} ơi, bạn muốn hỏi hay chia sẻ điều gì với lớp?` : 'Bạn muốn hỏi hay chia sẻ điều gì với lớp?'}
                    aria-label="Nội dung bài viết mới"
                    className={inputClass('py-2.5 leading-[22px]')}
                  />
                </div>
              </div>

              <div className="mt-3 flex flex-wrap items-center justify-between gap-3 sm:pl-[52px]">
                <div className="flex items-center gap-2">
                  <label htmlFor={visibilityFieldId} className="text-meta font-medium text-slate-600">Đối tượng xem:</label>
                  <select
                    id={visibilityFieldId}
                    value={newVisibility}
                    onChange={(e) => setNewVisibility(e.target.value as any)}
                    className="h-9 rounded-[10px] border border-slate-200 bg-white px-2.5 text-meta font-medium text-slate-900 focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-600/20"
                  >
                    <option value="FREE">Thành viên lớp (Free)</option>
                    {canManageFeedCreate && (
                      <>
                        <option value="PUBLIC">Mọi người (Public)</option>
                        <option value="PRO">Chỉ hội viên PRO ⭐</option>
                      </>
                    )}
                  </select>
                </div>

                <button
                  type="submit"
                  disabled={posting}
                  className="inline-flex h-[38px] items-center gap-1.5 rounded-full bg-blue-600 px-[18px] text-ui font-semibold text-white transition-colors duration-micro hover:bg-blue-700 disabled:bg-slate-200 disabled:text-slate-400 press"
                >
                  <Send className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                  <span>{posting ? 'Đang đăng...' : 'Đăng bài'}</span>
                </button>
              </div>
            </form>
          </section>
        )}

        {/* Posts Stream */}
        {loading && <LoadingSpinner message="Đang tải bài viết..." />}
        {error && <ErrorBanner message={error} onRetry={fetchFirstPage} />}

        {!loading && !error && posts.length === 0 && (
          <EmptyState
            title="Chưa có bài thảo luận nào"
            description={isMember ? 'Hãy là người đầu tiên đặt câu hỏi hoặc chia sẻ tài liệu với lớp!' : 'Khi lớp có bài thảo luận công khai, bạn sẽ thấy ở đây.'}
          />
        )}

        {posts.map((post) => {
          // Mirrors FeedService.updatePost/deletePost: the author may self-manage an
          // ordinary post, but a pinned/PUBLIC/PRO/PRODUCT_OWNER/SEGMENT post is managed
          // content and needs the matching FEED grant even for its own author (R4-09).
          const isSelfManageableAuthor = user?.id === post.authorId && isMember && !requiresFeedManagement(post);
          const canEditThis = isSelfManageableAuthor || canManageFeedEdit;
          const canDeleteThis = isSelfManageableAuthor || canManageFeedDelete;
          const total = post.commentCount ?? post.comments?.length ?? 0;
          const hidden = (post.commentCount ?? 0) - (post.comments?.length ?? 0);
          return (
            <article key={post.id} className={card}>
              {post.pinned && (
                <div className="flex items-center gap-2 border-b border-slate-100 px-5 py-3">
                  <Pin className="h-[13px] w-[13px] fill-blue-600 text-blue-600" aria-hidden="true" />
                  <span className="text-caption font-semibold tracking-[0.2px] text-blue-600">Bài viết đã ghim</span>
                </div>
              )}

              {/* Header */}
              <div className="flex items-start gap-3 px-4 pt-4 sm:px-5">
                <Avatar name={post.authorName || 'Thành viên'} src={post.authorAvatarUrl} size={44} />
                <div className="min-w-0 flex-1">
                  <p className="flex flex-wrap items-center gap-2 text-body-sm font-semibold text-slate-900">
                    <span className="truncate">{post.authorName || 'Thành viên'}</span>
                    {post.authorId && post.authorId === classroom.ownerId && <Badge tone="member" size="xs" className="!normal-case !text-micro !font-semibold">Chủ lớp</Badge>}
                  </p>
                  <p className="mt-px text-meta text-slate-500 tabular">{postTime(post.createdAt)}</p>
                </div>
                <div className="flex flex-shrink-0 items-center gap-1">
                  {post.visibility === 'PRO' && (
                    <Badge tone="pro" size="sm">
                      <Star className="h-3 w-3 fill-current" aria-hidden="true" />
                      PRO
                    </Badge>
                  )}
                  {post.visibility === 'PRODUCT_OWNER' && <Badge tone="paid" size="sm">Khóa trả phí</Badge>}
                  {post.visibility === 'PUBLIC' && (
                    <Badge tone="neutral" size="sm">
                      <Globe className="h-3 w-3" strokeWidth={2} aria-hidden="true" />
                      Công khai
                    </Badge>
                  )}
                  {canEditThis && (
                    <button
                      type="button"
                      onClick={() => {
                        setEditingPost(post.id);
                        setPostEdits((prev) => ({ ...prev, [post.id]: { title: post.title, contentMarkdown: post.contentMarkdown } }));
                      }}
                      className={iconBtn}
                      title="Sửa bài viết"
                      aria-label="Sửa bài viết"
                    >
                      <Pencil className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                    </button>
                  )}
                  {canDeleteThis && (
                    <button
                      type="button"
                      onClick={() => handleDeletePost(post.id)}
                      className={`${iconBtn} hover:!bg-red-50 hover:!text-red-700`}
                      title="Xóa bài viết"
                      aria-label="Xóa bài viết"
                    >
                      <Trash2 className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                    </button>
                  )}
                </div>
              </div>

              {/* Content */}
              <div className="mt-3 px-4 sm:px-5">
                {editingPost === post.id ? (
                  <div className="space-y-2">
                    <input aria-label="Tiêu đề bài viết" value={postEdits[post.id]?.title ?? post.title} onChange={(e) => setPostEdits((prev) => ({ ...prev, [post.id]: { ...prev[post.id], title: e.target.value, contentMarkdown: prev[post.id]?.contentMarkdown ?? post.contentMarkdown } }))} className={inputClass('h-11 font-semibold')} />
                    <textarea aria-label="Nội dung bài viết" rows={4} value={postEdits[post.id]?.contentMarkdown ?? post.contentMarkdown} onChange={(e) => setPostEdits((prev) => ({ ...prev, [post.id]: { title: prev[post.id]?.title ?? post.title, contentMarkdown: e.target.value } }))} className={inputClass('py-2.5 leading-[22px]')} />
                    <div className="flex gap-2">
                      <button type="button" onClick={() => handleUpdatePost(post.id)} className={buttonClass('primary', 'sm')}>Lưu</button>
                      <button type="button" onClick={() => setEditingPost(null)} aria-label="Hủy sửa bài" className={buttonClass('secondary', 'sm')}><X className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" /> Hủy</button>
                    </div>
                  </div>
                ) : (
                  <>
                    <h3 className="text-h3 font-semibold text-slate-900">{post.title}</h3>
                    <SafeMarkdown source={post.contentMarkdown} size="body" className="mt-1 text-slate-900 [&_li]:text-body-sm [&_p]:text-body-sm [&_p]:leading-6" />
                  </>
                )}
              </div>

              <div className="mt-3 flex items-center justify-end gap-3 px-4 sm:px-5">
                <span className="inline-flex items-center gap-1.5 text-meta text-slate-500">
                  <MessageCircle className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                  <span className="tabular">{total} bình luận</span>
                </span>
              </div>

              {/* Comments (the whole block is left out for a visitor when there is nothing to show) */}
              {!(hidden <= 0 && !(post.comments && post.comments.length > 0) && !(user && isMember)) ? (
              <div className="mx-4 mt-3 border-t border-slate-100 pb-4 pt-3 sm:mx-5">
                {hidden > 0 && (
                  <button
                    type="button"
                    onClick={() => handleLoadOlderComments(post)}
                    disabled={loadingOlder[post.id]}
                    className="mb-3 text-meta font-medium text-slate-600 transition-colors duration-micro hover:text-slate-900 disabled:text-slate-400"
                  >
                    {loadingOlder[post.id]
                      ? 'Đang tải bình luận...'
                      : `Xem thêm bình luận (${post.commentCount - (post.comments?.length ?? 0)})`}
                  </button>
                )}

                {post.comments && post.comments.length > 0 && (
                  <ul className="mb-3 space-y-3">
                    {post.comments.map((c) => (
                      <li key={c.id} className="flex items-start gap-2.5">
                        <Avatar name={c.authorName} src={c.authorAvatarUrl} size={32} />
                        <div className="min-w-0 flex-1">
                          {editingComment === c.id ? (
                            <div className="space-y-2">
                              <input aria-label="Nội dung bình luận" value={commentEdits[c.id] ?? c.content} onChange={(e) => setCommentEdits((prev) => ({ ...prev, [c.id]: e.target.value }))} className={inputClass('h-10')} />
                              <div className="flex gap-2">
                                <button type="button" onClick={() => handleUpdateComment(c.id)} className={buttonClass('primary', 'sm')}>Lưu</button>
                                <button type="button" onClick={() => setEditingComment(null)} className={buttonClass('secondary', 'sm')}>Hủy</button>
                              </div>
                            </div>
                          ) : (
                            <>
                              <div className="inline-block max-w-full rounded-2xl bg-slate-100 px-3.5 py-2.5">
                                <p className="flex flex-wrap items-center gap-2">
                                  <span className="text-meta font-semibold text-slate-900">{c.authorName}</span>
                                  {c.authorId && c.authorId === classroom.ownerId && <Badge tone="member" size="xs" className="!normal-case !text-micro !font-semibold">Chủ lớp</Badge>}
                                </p>
                                <p className="mt-0.5 whitespace-pre-line break-words text-ui leading-[21px] text-slate-900">{c.content}</p>
                              </div>
                              <div className="mt-1 flex items-center gap-3.5 pl-3.5">
                                <span className="text-caption text-slate-500 tabular">{postTime(c.createdAt)}</span>
                                {user?.id === c.authorId && (
                                  <button type="button" aria-label="Sửa bình luận" onClick={() => { setEditingComment(c.id); setCommentEdits((prev) => ({ ...prev, [c.id]: c.content })); }} className="text-caption font-semibold text-slate-600 hover:text-slate-900">
                                    Sửa
                                  </button>
                                )}
                                {(user?.id === c.authorId || canManageFeedDelete) && (
                                  <button type="button" aria-label="Xóa bình luận" onClick={() => handleDeleteComment(post.id, c.id)} className="text-caption font-semibold text-slate-600 hover:text-red-600">
                                    Xóa
                                  </button>
                                )}
                              </div>
                            </>
                          )}
                        </div>
                      </li>
                    ))}
                  </ul>
                )}

                {/* Add Comment Box: only for active members (or owner/staff) — matches
                    FeedService.addComment's accessPolicy.enforceMember check (R4-09). */}
                {user && isMember && (
                  <div className="flex items-center gap-2.5">
                    <Avatar name={user.fullName} size={32} />
                    <input
                      type="text"
                      value={commentInputs[post.id] || ''}
                      onChange={(e) => setCommentInputs({ ...commentInputs, [post.id]: e.target.value })}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') handleAddComment(post.id);
                      }}
                      placeholder="Viết bình luận của bạn..."
                      aria-label={`Bình luận cho bài “${post.title}”`}
                      className="h-10 min-w-0 flex-1 rounded-full bg-slate-100 px-3.5 text-ui text-slate-900 placeholder:text-slate-400 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-600/30"
                    />
                    <button
                      type="button"
                      onClick={() => handleAddComment(post.id)}
                      disabled={commenting[post.id]}
                      className="h-10 flex-shrink-0 rounded-full px-3.5 text-ui font-semibold text-blue-600 transition-colors duration-micro hover:bg-tint disabled:text-slate-400"
                    >
                      Gửi
                    </button>
                  </div>
                )}
              </div>
              ) : <div className="pb-4" />}
            </article>
          );
        })}

        {!loading && !error && hasNext && (
          <div className="flex justify-center pt-1">
            <button type="button" onClick={handleLoadMore} disabled={loadingMore} className={buttonClass('secondary', 'md')}>
              {loadingMore ? 'Đang tải...' : 'Xem thêm'}
            </button>
          </div>
        )}
      </div>

      {/* Right rail: the class at a glance, from real data only. */}
      <aside className="min-w-0 space-y-4 lg:sticky lg:top-[128px]">
        <section className={`p-5 ${card}`}>
          <h2 className="text-ui font-semibold text-slate-900">Về lớp học</h2>
          {classroom.description && <p className="mt-2 line-clamp-4 text-meta text-slate-600">{classroom.description}</p>}
          {classroom.ownerName && (
            <div className="mt-3 flex items-center gap-2.5">
              <Avatar name={classroom.ownerName} src={classroom.ownerAvatarUrl} size={32} />
              <p className="min-w-0 text-meta text-slate-600">
                Dẫn dắt bởi <strong className="font-semibold text-slate-900">{classroom.ownerName}</strong>
              </p>
            </div>
          )}
          <dl className="mt-4 space-y-2 border-t border-slate-100 pt-3 text-meta">
            <div className="flex justify-between gap-3">
              <dt className="text-slate-600">Thành viên</dt>
              <dd className="font-semibold text-slate-900 tabular">{(classroom.memberCount ?? 0).toLocaleString('vi-VN')}</dd>
            </div>
            <div className="flex justify-between gap-3">
              <dt className="text-slate-600">Hình thức</dt>
              <dd className="font-semibold text-slate-900">
                {classroom.visibility === 'PRIVATE' ? 'Riêng tư' : 'Công khai'} · {classroom.accessType === 'PAID' ? 'Trả phí' : 'Miễn phí'}
              </dd>
            </div>
            {created && (
              <div className="flex justify-between gap-3">
                <dt className="text-slate-600">Hoạt động từ</dt>
                <dd className="font-semibold text-slate-900 tabular">{created}</dd>
              </div>
            )}
          </dl>
          <Link to={`/classes/${classroom.slug}/about`} className="mt-3 inline-block text-meta font-medium text-blue-600 hover:text-blue-700">
            Đọc giới thiệu và nội quy
          </Link>
        </section>

        {(classroom.upcomingEventCount ?? 0) > 0 && (
          <section className={`p-5 ${card}`}>
            <div className="flex items-baseline justify-between gap-3">
              <h2 className="text-ui font-semibold text-slate-900">Sự kiện sắp diễn ra</h2>
              <Link to={`/classes/${classroom.slug}/events`} className="text-caption font-medium text-blue-600 hover:text-blue-700">Xem lịch</Link>
            </div>
            <p className="mt-2 flex items-center gap-2 text-meta text-slate-600">
              <CalendarDays className="h-4 w-4 text-slate-400" strokeWidth={1.75} aria-hidden="true" />
              <span className="tabular">{classroom.upcomingEventCount} sự kiện sắp tới trong lớp</span>
            </p>
          </section>
        )}
      </aside>
    </div>
  );
};

const pad2 = (n: number) => String(n).padStart(2, '0');

/** "Hôm nay lúc 09:12" / "Hôm qua lúc 21:40" / "04/07/2026 lúc 16:20" (24h clock). */
function postTime(instant: string): string {
  const d = new Date(instant);
  if (Number.isNaN(d.getTime())) return '';
  const time = `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
  const today = new Date();
  const yesterday = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1);
  if (d.toDateString() === today.toDateString()) return `Hôm nay lúc ${time}`;
  if (d.toDateString() === yesterday.toDateString()) return `Hôm qua lúc ${time}`;
  return `${pad2(d.getDate())}/${pad2(d.getMonth() + 1)}/${d.getFullYear()} lúc ${time}`;
}

/** ISO instant -> "mm/yyyy" ('' when absent). */
function monthYear(instant?: string | null): string {
  if (!instant) return '';
  const d = new Date(instant);
  if (Number.isNaN(d.getTime())) return '';
  return `${pad2(d.getMonth() + 1)}/${d.getFullYear()}`;
}
