import React, { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, Post } from '../../types';
import { api } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { LoadingSpinner, ErrorBanner, EmptyState, StatusBadge } from '../../components/UIStates';
import { MessageSquare, Send, Pin, Lock, Trash2, MessageCircle, Pencil, X } from 'lucide-react';

export const FeedTab: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const { user } = useAuth();

  const [posts, setPosts] = useState<Post[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // New Post Form
  const [newTitle, setNewTitle] = useState('');
  const [newContent, setNewContent] = useState('');
  const [newVisibility, setNewVisibility] = useState<'PUBLIC' | 'FREE' | 'PRO'>('PUBLIC');
  const [posting, setPosting] = useState(false);

  // Comment Form state: map of postId -> comment text
  const [commentInputs, setCommentInputs] = useState<Record<string, string>>({});
  const [commenting, setCommenting] = useState<Record<string, boolean>>({});
  const [editingPost, setEditingPost] = useState<string | null>(null);
  const [postEdits, setPostEdits] = useState<Record<string, { title: string; contentMarkdown: string }>>({});
  const [editingComment, setEditingComment] = useState<string | null>(null);
  const [commentEdits, setCommentEdits] = useState<Record<string, string>>({});

  const fetchPosts = async () => {
    try {
      setLoading(true);
      const data = await api.get<Post[]>(`/classes/${classroom.id}/posts`);
      setPosts(data || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải bảng tin');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchPosts();
  }, [classroom.id, user]);

  const handleCreatePost = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTitle.trim() || !newContent.trim()) return;

    setPosting(true);
    try {
      await api.post<Post>(`/classes/${classroom.id}/posts`, {
        title: newTitle,
        contentMarkdown: newContent,
        visibility: newVisibility,
      });
      setNewTitle('');
      setNewContent('');
      await fetchPosts();
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
      await api.post(`/posts/${postId}/comments`, { content: text });
      setCommentInputs((prev) => ({ ...prev, [postId]: '' }));
      await fetchPosts();
    } catch (err: any) {
      alert(err.message || 'Bình luận thất bại');
    } finally {
      setCommenting((prev) => ({ ...prev, [postId]: false }));
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
      await api.put(`/posts/${postId}`, edit);
      setEditingPost(null);
      await fetchPosts();
    } catch (err: any) {
      alert(err.message || 'Sửa bài thất bại');
    }
  };

  const handleUpdateComment = async (commentId: string) => {
    const content = commentEdits[commentId];
    if (!content?.trim()) return;
    try {
      await api.put(`/comments/${commentId}`, { content });
      setEditingComment(null);
      await fetchPosts();
    } catch (err: any) {
      alert(err.message || 'Sửa bình luận thất bại');
    }
  };

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      {/* Post Composer */}
      {user && (
        <div className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm">
          <div className="flex items-center space-x-3 mb-3">
            <div className="w-9 h-9 rounded-full bg-indigo-100 flex items-center justify-center text-indigo-700 font-bold text-sm">
              {user.fullName ? user.fullName[0].toUpperCase() : 'U'}
            </div>
            <div>
              <span className="text-sm font-bold text-slate-800 block leading-tight">{user.fullName}</span>
              <span className="text-xs text-slate-400">Chia sẻ thảo luận cùng lớp học</span>
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
                <span className="text-xs font-semibold text-slate-500">Đối tượng xem:</span>
                <select
                  value={newVisibility}
                  onChange={(e) => setNewVisibility(e.target.value as any)}
                  className="px-2.5 py-1 bg-slate-50 border border-slate-200 rounded-lg text-xs font-medium focus:outline-none focus:ring-2 focus:ring-indigo-500"
                >
                  <option value="PUBLIC">Mọi người (Public)</option>
                  <option value="FREE">Thành viên lớp (Free)</option>
                  {(classroom.userRole === 'OWNER' || classroom.userRole === 'STAFF') && (
                    <option value="PRO">Chỉ hội viên PRO ⭐</option>
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
      {error && <ErrorBanner message={error} onRetry={fetchPosts} />}

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
                  <span className="text-[11px] text-slate-400">
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

                {(user?.id === post.authorId || classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('FEED:EDIT')) && (
                  <>
                  <button
                    onClick={() => {
                      setEditingPost(post.id);
                      setPostEdits((prev) => ({ ...prev, [post.id]: { title: post.title, contentMarkdown: post.contentMarkdown } }));
                    }}
                    className="p-1 text-slate-400 hover:text-indigo-600 rounded transition"
                    title="Sửa bài viết"
                  >
                    <Pencil className="w-4 h-4" />
                  </button>
                  <button
                    onClick={() => handleDeletePost(post.id)}
                    className="p-1 text-slate-300 hover:text-rose-600 rounded transition"
                    title="Xóa bài viết"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                  </>
                )}
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
                <span>{post.comments?.length || 0} bình luận</span>
              </div>

              {post.comments && post.comments.length > 0 && (
                <div className="space-y-2.5 mb-4 pl-3 border-l-2 border-slate-100">
                  {post.comments.map((c) => (
                    <div key={c.id} className="bg-slate-50 rounded-xl p-3 text-xs">
                      <div className="flex justify-between items-center mb-1">
                        <span className="font-bold text-slate-800">{c.authorName}</span>
                        <span className="text-[10px] text-slate-400">
                          {new Date(c.createdAt).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })}
                        </span>
                      </div>
                       {editingComment === c.id ? (
                         <div className="space-y-2">
                           <input aria-label="Nội dung bình luận" value={commentEdits[c.id] ?? c.content} onChange={(e) => setCommentEdits((prev) => ({ ...prev, [c.id]: e.target.value }))} className="w-full px-2 py-1 border rounded" />
                           <button onClick={() => handleUpdateComment(c.id)} className="px-2 py-1 bg-indigo-600 text-white rounded">Lưu</button>
                           <button onClick={() => setEditingComment(null)} className="ml-2 px-2 py-1 border rounded">Hủy</button>
                         </div>
                       ) : <div className="flex justify-between gap-2"><p className="text-slate-600 leading-normal">{c.content}</p>{user?.id === c.authorId && <button aria-label="Sửa bình luận" onClick={() => { setEditingComment(c.id); setCommentEdits((prev) => ({ ...prev, [c.id]: c.content })); }} className="text-slate-400 hover:text-indigo-600"><Pencil className="w-3 h-3" /></button>}</div>}
                    </div>
                  ))}
                </div>
              )}

              {/* Add Comment Box */}
              {user && (
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
    </div>
  );
};
