import React, { useEffect, useId, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, Post, Comment, CommentPage } from '../../types';
import { api } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { hasStudioPermission } from '../../api/permissions';
import { LoadingSpinner, ErrorBanner, EmptyState, StatusBadge } from '../../components/UIStates';
import { MessageSquare, Send, Pin, Lock, Trash2, MessageCircle, Pencil, X } from 'lucide-react';

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

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      {/* Post Composer: only for active members (or owner/staff) — a non-member viewing a
          PUBLIC feed must not see authoring UI the server would reject (R4-09). */}
      {user && isMember && (
        <div className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm">
          <div className="flex items-center space-x-3 mb-3">
            <div className="w-9 h-9 rounded-full bg-indigo-100 flex items-center justify-center text-indigo-700 font-bold text-sm">
              {user.fullName ? user.fullName[0].toUpperCase() : 'U'}
            </div>
            <div>
              <span className="text-sm font-bold text-slate-800 block leading-tight">{user.fullName}</span>
              <span className="text-xs text-slate-500">Chia sẻ thảo luận cùng lớp học</span>
            </div>
          </div>

          <form onSubmit={handleCreatePost} className="space-y-3">
            <input
              type="text"
              required
              value={newTitle}
              onChange={(e) => setNewTitle(e.target.value)}
              placeholder="Tiêu đề bài viết..."
              className="w-full px-3.5 py-2 bg-slate-50 border border-slate-200 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 font-semibold"
            />

            <textarea
              rows={3}
              required
              value={newContent}
              onChange={(e) => setNewContent(e.target.value)}
              placeholder="Bạn muốn chia sẻ điều gì với thầy cô và bạn bè?..."
              className="w-full px-3.5 py-2 bg-slate-50 border border-slate-200 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />

            <div className="flex flex-wrap items-center justify-between gap-3 pt-2">
              <div className="flex items-center space-x-2">
                <label htmlFor={visibilityFieldId} className="text-xs font-semibold text-slate-500">Đối tượng xem:</label>
                <select
                  id={visibilityFieldId}
                  value={newVisibility}
                  onChange={(e) => setNewVisibility(e.target.value as any)}
                  className="px-2.5 py-1 bg-slate-50 border border-slate-200 rounded-lg text-xs font-medium focus:outline-none focus:ring-2 focus:ring-indigo-500"
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
                className="inline-flex items-center space-x-1.5 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold transition shadow-sm disabled:opacity-50"
              >
                <Send className="w-3.5 h-3.5" />
                <span>{posting ? 'Đang đăng...' : 'Đăng bài'}</span>
              </button>
            </div>
          </form>
        </div>
      )}

      {/* Posts Stream */}
      {loading && <LoadingSpinner message="Đang tải bài viết..." />}
      {error && <ErrorBanner message={error} onRetry={fetchFirstPage} />}

      {!loading && !error && posts.length === 0 && (
        <EmptyState
          title="Bảng tin chưa có bài viết nào"
          description="Hãy là người đầu tiên đặt câu hỏi hoặc chia sẻ tài liệu với lớp!"
        />
      )}

      <div className="space-y-5">
        {posts.map((post) => (
          <article
            key={post.id}
            className="bg-white rounded-2xl border border-slate-200 p-6 shadow-sm overflow-hidden"
          >
            {/* Header */}
            <div className="flex items-start justify-between mb-3">
              <div className="flex items-center space-x-3">
                <div className="w-10 h-10 rounded-full bg-slate-100 flex items-center justify-center font-bold text-slate-700 text-sm overflow-hidden border border-slate-200">
                  {post.authorAvatarUrl ? (
                    <img src={post.authorAvatarUrl} alt={post.authorName} className="w-full h-full object-cover" />
                  ) : (
                    <span>{post.authorName ? post.authorName[0].toUpperCase() : 'U'}</span>
                  )}
                </div>
                <div>
                  <h4 className="text-sm font-bold text-slate-900 leading-tight">{post.authorName || 'Thành viên'}</h4>
                  <span className="text-[11px] text-slate-500">
                    {new Date(post.createdAt).toLocaleString('vi-VN')}
                  </span>
                </div>
              </div>

              <div className="flex items-center space-x-2">
                {post.pinned && (
                  <span className="inline-flex items-center space-x-1 px-2 py-0.5 rounded-full text-xs font-bold bg-indigo-50 text-indigo-700 border border-indigo-200">
                    <Pin className="w-3 h-3" />
                    <span>Ghim</span>
                  </span>
                )}
                {post.visibility === 'PRO' && <StatusBadge status="PRO" />}
                {post.visibility === 'PRODUCT_OWNER' && (
                  <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-purple-100 text-purple-700">
                    Khóa trả phí
                  </span>
                )}

                {(() => {
                  // Mirrors FeedService.updatePost/deletePost: the author may self-manage an
                  // ordinary post, but a pinned/PUBLIC/PRO/PRODUCT_OWNER/SEGMENT post is managed
                  // content and needs the matching FEED grant even for its own author (R4-09).
                  const isSelfManageableAuthor = user?.id === post.authorId && isMember && !requiresFeedManagement(post);
                  const canEditThis = isSelfManageableAuthor || canManageFeedEdit;
                  const canDeleteThis = isSelfManageableAuthor || canManageFeedDelete;
                  return (
                    <>
                      {canEditThis && (
                        <button
                          onClick={() => {
                            setEditingPost(post.id);
                            setPostEdits((prev) => ({ ...prev, [post.id]: { title: post.title, contentMarkdown: post.contentMarkdown } }));
                          }}
                          className="p-1 text-slate-500 hover:text-indigo-600 rounded transition"
                          title="Sửa bài viết"
                        >
                          <Pencil className="w-4 h-4" />
                        </button>
                      )}
                      {canDeleteThis && (
                        <button
                          onClick={() => handleDeletePost(post.id)}
                          className="p-1 text-slate-500 hover:text-rose-600 rounded transition"
                          title="Xóa bài viết"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      )}
                    </>
                  );
                })()}
              </div>
            </div>

            {/* Content */}
            {editingPost === post.id ? (
              <div className="space-y-2 mb-4">
                <input aria-label="Tiêu đề bài viết" value={postEdits[post.id]?.title ?? post.title} onChange={(e) => setPostEdits((prev) => ({ ...prev, [post.id]: { ...prev[post.id], title: e.target.value, contentMarkdown: prev[post.id]?.contentMarkdown ?? post.contentMarkdown } }))} className="w-full px-3 py-2 border rounded-lg" />
                <textarea aria-label="Nội dung bài viết" value={postEdits[post.id]?.contentMarkdown ?? post.contentMarkdown} onChange={(e) => setPostEdits((prev) => ({ ...prev, [post.id]: { title: prev[post.id]?.title ?? post.title, contentMarkdown: e.target.value } }))} className="w-full px-3 py-2 border rounded-lg" />
                <button onClick={() => handleUpdatePost(post.id)} className="px-3 py-1 bg-indigo-600 text-white rounded-lg">Lưu</button>
                <button onClick={() => setEditingPost(null)} aria-label="Hủy sửa bài" className="ml-2 px-3 py-1 border rounded-lg"><X className="inline w-4 h-4" /> Hủy</button>
              </div>
            ) : <><h3 className="text-base font-bold text-slate-900 mb-2">{post.title}</h3><div className="text-sm text-slate-700 whitespace-pre-line leading-relaxed mb-4">{post.contentMarkdown}</div></>}

            {/* Comments List */}
            <div className="pt-4 border-t border-slate-100">
              <div className="flex items-center space-x-1.5 text-xs font-semibold text-slate-500 mb-3">
                <MessageCircle className="w-4 h-4" />
                <span>{post.commentCount ?? post.comments?.length ?? 0} bình luận</span>
              </div>

              {(post.commentCount ?? 0) > (post.comments?.length ?? 0) && (
                <div className="mb-3 pl-3">
                  <button
                    type="button"
                    onClick={() => handleLoadOlderComments(post)}
                    disabled={loadingOlder[post.id]}
                    className="text-xs font-semibold text-indigo-600 hover:text-indigo-800 disabled:opacity-50"
                  >
                    {loadingOlder[post.id]
                      ? 'Đang tải bình luận...'
                      : `Xem thêm bình luận (${post.commentCount - (post.comments?.length ?? 0)})`}
                  </button>
                </div>
              )}

              {post.comments && post.comments.length > 0 && (
                <div className="space-y-2.5 mb-4 pl-3 border-l-2 border-slate-100">
                  {post.comments.map((c) => (
                    <div key={c.id} className="bg-slate-50 rounded-xl p-3 text-xs">
                      <div className="flex justify-between items-center mb-1">
                        <span className="font-bold text-slate-800">{c.authorName}</span>
                        <span className="text-[10px] text-slate-500">
                          {new Date(c.createdAt).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })}
                        </span>
                      </div>
                       {editingComment === c.id ? (
                         <div className="space-y-2">
                           <input aria-label="Nội dung bình luận" value={commentEdits[c.id] ?? c.content} onChange={(e) => setCommentEdits((prev) => ({ ...prev, [c.id]: e.target.value }))} className="w-full px-2 py-1 border rounded" />
                           <button onClick={() => handleUpdateComment(c.id)} className="px-2 py-1 bg-indigo-600 text-white rounded">Lưu</button>
                           <button onClick={() => setEditingComment(null)} className="ml-2 px-2 py-1 border rounded">Hủy</button>
                         </div>
                       ) : <div className="flex justify-between gap-2"><p className="text-slate-600 leading-normal">{c.content}</p><div className="flex items-center space-x-1 shrink-0">{user?.id === c.authorId && <button aria-label="Sửa bình luận" onClick={() => { setEditingComment(c.id); setCommentEdits((prev) => ({ ...prev, [c.id]: c.content })); }} className="text-slate-500 hover:text-indigo-600"><Pencil className="w-3 h-3" /></button>}{(user?.id === c.authorId || canManageFeedDelete) && <button aria-label="Xóa bình luận" onClick={() => handleDeleteComment(post.id, c.id)} className="text-slate-500 hover:text-rose-600"><Trash2 className="w-3 h-3" /></button>}</div></div>}
                    </div>
                  ))}
                </div>
              )}

              {/* Add Comment Box: only for active members (or owner/staff) — matches
                  FeedService.addComment's accessPolicy.enforceMember check (R4-09). */}
              {user && isMember && (
                <div className="flex space-x-2">
                  <input
                    type="text"
                    value={commentInputs[post.id] || ''}
                    onChange={(e) =>
                      setCommentInputs({ ...commentInputs, [post.id]: e.target.value })
                    }
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') handleAddComment(post.id);
                    }}
                    placeholder="Viết bình luận của bạn..."
                    className="flex-1 px-3 py-1.5 bg-slate-50 border border-slate-200 rounded-xl text-xs focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  />
                  <button
                    onClick={() => handleAddComment(post.id)}
                    disabled={commenting[post.id]}
                    className="px-3 py-1.5 bg-slate-900 hover:bg-indigo-600 text-white rounded-xl text-xs font-bold transition disabled:opacity-50"
                  >
                    Gửi
                  </button>
                </div>
              )}
            </div>
          </article>
        ))}
      </div>

      {!loading && !error && hasNext && (
        <div className="flex justify-center pt-2">
          <button
            onClick={handleLoadMore}
            disabled={loadingMore}
            className="px-4 py-2 bg-white border border-slate-200 text-slate-700 rounded-xl text-xs font-bold shadow-sm hover:bg-slate-50 transition disabled:opacity-50"
          >
            {loadingMore ? 'Đang tải...' : 'Xem thêm'}
          </button>
        </div>
      )}
    </div>
  );
};
