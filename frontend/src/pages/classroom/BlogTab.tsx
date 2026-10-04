import React, { useEffect, useRef, useState } from 'react';
import { Link, useOutletContext } from 'react-router-dom';
import { MessageSquare, PenLine } from 'lucide-react';
import { blogPostDate, listBlogCategories, listBlogPosts } from '../../api/blog';
import { formatDate } from '../../api/format';
import { hasStudioPermission } from '../../api/permissions';
import { BlogCategoryChip, BlogCover, MembersOnlyChip } from '../../components/BlogBits';
import { ErrorBanner, LoadingSpinner } from '../../components/UIStates';
import { Avatar, ClassAvatar, FilterChip, SectionHeader, buttonClass } from '../../components/ui';
import type { BlogPost, Classroom } from '../../types';

// Blog tab (design: "OK - Blog.dc.html"). Newest published post as the big featured card, a one-line category chip row,
// then the remaining posts as a 2-column card grid with cursor paging ("Xem thêm bài viết"). Blocks of the design that
// need data the backend does not keep (search, read counts, newsletter, "continue reading", series progress) are left out.

const PAGE_SIZE = 12;

const metaLine = (post: BlogPost) => `${formatDate(blogPostDate(post))} · ${post.readingMinutes} phút đọc`;

// `primary`: one blue button per viewport - for a visitor the layout's join banner already holds it.
const FeaturedPost: React.FC<{ post: BlogPost; slug: string; primary: boolean }> = ({ post, slug, primary }) => (
  <article className="card-hover grid overflow-hidden rounded-section border border-slate-200 bg-white shadow-hairline md:grid-cols-[minmax(240px,3fr)_minmax(240px,4fr)]">
    <Link to={`/classes/${slug}/blog/${post.id}`} tabIndex={-1} aria-hidden="true" className="block h-[200px] bg-slate-100 md:h-auto md:min-h-[280px]">
      <BlogCover src={post.coverUrl} seed={post.category || post.id} iconSize={46} />
    </Link>
    <div className="flex min-w-0 flex-col p-5 sm:px-8 sm:py-7">
      <div className="flex flex-wrap items-center gap-2">
        <BlogCategoryChip category={post.category} size="md" />
        <span className="inline-flex h-6 items-center rounded-full bg-slate-100 px-2.5 text-caption font-semibold text-slate-600">Bài mới nhất</span>
        {post.locked && <MembersOnlyChip />}
      </div>
      <h3 className="mt-3.5 text-h2-sm font-semibold text-slate-900 sm:text-h2">
        <Link to={`/classes/${slug}/blog/${post.id}`} className="text-slate-900 hover:text-blue-600">{post.title}</Link>
      </h3>
      {post.excerpt && <p className="mt-2 line-clamp-3 text-body-sm text-slate-600">{post.excerpt}</p>}
      <div className="mt-auto flex flex-wrap items-center justify-between gap-4 pt-5">
        <div className="flex min-w-0 items-center gap-2.5">
          <Avatar name={post.author.fullName} src={post.author.avatarUrl} size={28} />
          <span className="min-w-0 text-meta text-slate-600 sm:truncate">
            <strong className="font-semibold text-slate-900">{post.author.fullName}</strong> · <span className="tabular">{metaLine(post)}</span>
          </span>
        </div>
        <Link to={`/classes/${slug}/blog/${post.id}`} className={buttonClass(primary ? 'primary' : 'secondary', 'md')}>Đọc bài</Link>
      </div>
    </div>
  </article>
);

const PostCard: React.FC<{ post: BlogPost; slug: string }> = ({ post, slug }) => (
  <Link
    to={`/classes/${slug}/blog/${post.id}`}
    className="card-hover flex flex-col overflow-hidden rounded-card border border-slate-200 bg-white text-slate-900 shadow-hairline"
  >
    <div className="h-[152px] bg-slate-100">
      <BlogCover src={post.coverUrl} seed={post.category || post.id} />
    </div>
    <div className="flex flex-1 flex-col px-5 pb-5 pt-4">
      <div className="flex flex-wrap items-center gap-1.5">
        <BlogCategoryChip category={post.category} />
        {post.locked && <MembersOnlyChip />}
      </div>
      <h3 className="mt-2.5 line-clamp-2 text-body font-semibold leading-[23px]">{post.title}</h3>
      {post.excerpt && <p className="mt-1.5 line-clamp-2 text-meta leading-5 text-slate-600">{post.excerpt}</p>}
      <div className="mt-auto flex min-w-0 items-center gap-2 pt-3">
        <Avatar name={post.author.fullName} src={post.author.avatarUrl} size={20} />
        <span className="truncate text-caption text-slate-500">
          <span className="font-medium text-slate-600">{post.author.fullName}</span> · <span className="tabular">{metaLine(post)}</span>
        </span>
      </div>
    </div>
  </Link>
);

export const BlogTab: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom; refreshClassroom: () => Promise<void> }>();
  const canWrite = hasStudioPermission(classroom, 'BLOG', 'CREATE');

  const [categories, setCategories] = useState<string[]>([]);
  const [category, setCategory] = useState<string | null>(null);
  const [posts, setPosts] = useState<BlogPost[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [moreError, setMoreError] = useState<string | null>(null);
  // Drops replies of a category that is no longer selected (fast chip clicking).
  const seq = useRef(0);

  useEffect(() => {
    listBlogCategories(classroom.id)
      .then((list) => setCategories(Array.isArray(list) ? list.filter(Boolean) : []))
      .catch(() => setCategories([]));
  }, [classroom.id]);

  const load = async () => {
    const mine = ++seq.current;
    setLoading(true);
    setError(null);
    setMoreError(null);
    try {
      const page = await listBlogPosts(classroom.id, { category, size: PAGE_SIZE });
      if (mine !== seq.current) return;
      setPosts(page?.items ?? []);
      setNextCursor(page?.nextCursor ?? null);
    } catch (err) {
      if (mine !== seq.current) return;
      setError(err instanceof Error && err.message ? err.message : 'Không thể tải bài viết.');
    } finally {
      if (mine === seq.current) setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [classroom.id, category]);

  const loadMore = async () => {
    if (!nextCursor) return;
    const mine = seq.current;
    setLoadingMore(true);
    setMoreError(null);
    try {
      const page = await listBlogPosts(classroom.id, { category, cursor: nextCursor, size: PAGE_SIZE });
      if (mine !== seq.current) return;
      setPosts((current) => {
        const seen = new Set(current.map((p) => p.id));
        return [...current, ...(page?.items ?? []).filter((p) => !seen.has(p.id))];
      });
      setNextCursor(page?.nextCursor ?? null);
    } catch (err) {
      if (mine === seq.current) setMoreError(err instanceof Error && err.message ? err.message : 'Không thể tải thêm bài viết.');
    } finally {
      setLoadingMore(false);
    }
  };

  // The featured card is the newest post of the whole blog - only while no category filter is applied.
  const featured = !category ? posts[0] : undefined;
  const rest = featured ? posts.slice(1) : posts;
  const studioBlogPath = `/studio/classes/${classroom.id}/blog`;

  return (
    <div className="space-y-6">
      <SectionHeader
        title="Blog"
        description="Bài viết từ người dẫn dắt lớp học — đọc theo chuyên mục bạn quan tâm."
        action={canWrite ? (
          <Link to={studioBlogPath} className={buttonClass('secondary', 'md')}>
            <PenLine className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
            Viết bài
          </Link>
        ) : undefined}
      />

      {categories.length > 0 && (
        <div role="group" aria-label="Chuyên mục" className="scrollbar-none -mx-4 flex items-center gap-2 overflow-x-auto px-4 py-0.5 sm:mx-0 sm:px-0.5">
          <FilterChip selected={category === null} onClick={() => setCategory(null)}>Tất cả</FilterChip>
          {categories.map((c) => (
            <FilterChip key={c} selected={category === c} onClick={() => setCategory(c)}>{c}</FilterChip>
          ))}
        </div>
      )}

      <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="min-w-0 space-y-8">
          {loading ? (
            <LoadingSpinner message="Đang tải bài viết..." />
          ) : error ? (
            <ErrorBanner message={error} onRetry={load} />
          ) : posts.length === 0 ? (
            <div className="flex flex-col items-center rounded-card border border-slate-200 bg-white p-10 text-center shadow-hairline sm:p-12">
              <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-community bg-slate-100 text-slate-500">
                <PenLine className="h-6 w-6" strokeWidth={1.7} aria-hidden="true" />
              </div>
              <h3 className="text-h3 font-semibold text-slate-900">
                {category ? `Chưa có bài viết trong “${category}”` : 'Lớp học chưa có bài viết nào'}
              </h3>
              <p className="mt-1 max-w-sm text-ui text-slate-600">
                {canWrite
                  ? 'Bài đầu tiên nên giải một vướng mắc cụ thể mà thành viên hay hỏi — viết xong, đăng là cả lớp đọc được.'
                  : 'Khi người dẫn dắt đăng bài, bạn sẽ đọc được ở đây. Trong lúc chờ, ghé Thảo luận để hỏi điều bạn đang vướng.'}
              </p>
              {category ? (
                <button type="button" onClick={() => setCategory(null)} className={buttonClass('secondary', 'md', 'mt-5')}>Xem tất cả bài viết</button>
              ) : canWrite ? (
                <Link to={studioBlogPath} className={buttonClass('primary', 'md', 'mt-5')}>Viết bài đầu tiên</Link>
              ) : (
                <Link to={`/classes/${classroom.slug}/feed`} className={buttonClass('secondary', 'md', 'mt-5')}>Vào Thảo luận</Link>
              )}
            </div>
          ) : (
            <>
              {featured && <FeaturedPost post={featured} slug={classroom.slug} primary={!!(classroom.isMember || classroom.isOwner)} />}
              {rest.length > 0 && (
                <section aria-labelledby="blog-latest" className="space-y-3.5">
                  <h3 id="blog-latest" className="text-h2-sm font-semibold text-slate-900">{category ? category : 'Bài mới nhất'}</h3>
                  <div className="grid gap-3 sm:grid-cols-2 sm:gap-4">
                    {rest.map((post) => <PostCard key={post.id} post={post} slug={classroom.slug} />)}
                  </div>
                </section>
              )}
              {moreError && <ErrorBanner message={moreError} onRetry={loadMore} />}
              {nextCursor && (
                <div className="text-center">
                  <button type="button" onClick={loadMore} disabled={loadingMore} className={buttonClass('secondary', 'lg')}>
                    {loadingMore ? 'Đang tải...' : 'Xem thêm bài viết'}
                  </button>
                </div>
              )}
            </>
          )}
        </div>

        <aside className="flex min-w-0 flex-col gap-5 lg:sticky lg:top-[120px]">
          <section className="rounded-card border border-slate-200 bg-white p-[22px] shadow-hairline">
            <div className="flex items-center gap-3">
              <ClassAvatar title={classroom.title} seed={classroom.id} src={classroom.coverUrl ?? classroom.coverImageUrl} size={44} />
              <div className="min-w-0">
                <p className="truncate text-body-sm font-semibold text-slate-900">{classroom.title}</p>
                <p className="text-meta text-slate-600 tabular">{classroom.memberCount.toLocaleString('vi-VN')} thành viên</p>
              </div>
            </div>
            {classroom.ownerName && (
              <div className="mt-3.5 flex items-center gap-2 border-t border-slate-100 pt-3.5 text-meta text-slate-600">
                <Avatar name={classroom.ownerName} src={classroom.ownerAvatarUrl} size={22} />
                <span className="min-w-0 truncate">Dẫn dắt bởi <strong className="font-semibold text-slate-900">{classroom.ownerName}</strong></span>
              </div>
            )}
          </section>
          <section className="rounded-card border border-slate-200 bg-white p-[22px] shadow-hairline">
            <h3 className="text-body-sm font-semibold text-slate-900">Đọc xong còn thắc mắc?</h3>
            <p className="mt-1.5 text-meta leading-[19px] text-slate-600">Đặt câu hỏi trong Thảo luận — người dẫn dắt và các thành viên cùng trả lời.</p>
            <Link to={`/classes/${classroom.slug}/feed`} className="mt-2.5 inline-flex items-center gap-1.5 text-meta font-medium text-blue-600 hover:text-blue-700">
              <MessageSquare className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
              Vào Thảo luận
            </Link>
          </section>
        </aside>
      </div>
    </div>
  );
};
