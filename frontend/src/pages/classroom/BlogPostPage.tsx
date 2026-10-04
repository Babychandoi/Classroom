import React, { useEffect, useState } from 'react';
import { Link, useOutletContext, useParams } from 'react-router-dom';
import { ChevronLeft, ChevronRight, Lock, MessageSquare, SearchX } from 'lucide-react';
import { ApiException } from '../../api/client';
import { blogPostDate, getBlogPost, listBlogPosts } from '../../api/blog';
import { formatDate } from '../../api/format';
import { BlogCategoryChip, BlogCover } from '../../components/BlogBits';
import { ClassJoinCta } from '../../components/ClassJoinCta';
import { SafeMarkdown } from '../../components/SafeMarkdown';
import { ErrorBanner, LoadingSpinner } from '../../components/UIStates';
import { Avatar, Badge, buttonClass } from '../../components/ui';
import type { BlogPost, Classroom } from '../../types';

// Reading page (design: "OK - Chi tiết bài viết.dc.html"): one column max 680-720, scroll-driven reading progress bar,
// category, H1, lead, author row between hairlines, 16:9 cover, body 17/29 slate-700, then older/newer navigation.
// A MEMBERS post seen by a non-member comes back `locked` without content: show the teaser and the way in.

/** 0..100 - how far the reader has scrolled through the page. */
function useReadingProgress(enabled: boolean): number {
  const [progress, setProgress] = useState(0);
  useEffect(() => {
    if (!enabled) return;
    let frame = 0;
    const update = () => {
      frame = 0;
      const doc = document.documentElement;
      const max = doc.scrollHeight - window.innerHeight;
      setProgress(max > 0 ? Math.min(100, Math.max(0, (window.scrollY / max) * 100)) : 0);
    };
    const onScroll = () => { if (!frame) frame = window.requestAnimationFrame(update); };
    update();
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    return () => {
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', onScroll);
      if (frame) window.cancelAnimationFrame(frame);
    };
  }, [enabled]);
  return progress;
}

const NeighbourLink: React.FC<{ post: BlogPost; slug: string; direction: 'older' | 'newer' }> = ({ post, slug, direction }) => {
  const newer = direction === 'newer';
  return (
    <Link
      to={`/classes/${slug}/blog/${post.id}`}
      className={`flex flex-col gap-1.5 rounded-2xl border px-5 py-4 text-slate-900 transition-colors duration-micro ${
        newer ? 'border-blue-100 bg-tint text-right hover:bg-[#E3EDFC] sm:col-start-2' : 'border-slate-200 bg-white hover:bg-slate-50'
      }`}
    >
      <span className={`inline-flex items-center gap-1.5 text-caption font-semibold uppercase tracking-[0.3px] ${newer ? 'justify-end text-blue-800' : 'text-slate-500'}`}>
        {!newer && <ChevronLeft className="h-3 w-3" strokeWidth={2} aria-hidden="true" />}
        {newer ? 'Bài mới hơn' : 'Bài cũ hơn'}
        {newer && <ChevronRight className="h-3 w-3" strokeWidth={2} aria-hidden="true" />}
      </span>
      <span className="line-clamp-2 text-ui font-semibold">{post.title}</span>
    </Link>
  );
};

export const BlogPostPage: React.FC = () => {
  const { postId } = useParams<{ postId: string }>();
  const { classroom, refreshClassroom } = useOutletContext<{ classroom: Classroom; refreshClassroom: () => Promise<void> }>();
  const [post, setPost] = useState<BlogPost | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [neighbours, setNeighbours] = useState<{ older?: BlogPost; newer?: BlogPost }>({});

  const load = async () => {
    if (!postId) return;
    setLoading(true);
    setError(null);
    setNotFound(false);
    try {
      setPost(await getBlogPost(postId));
    } catch (err) {
      setPost(null);
      if (err instanceof ApiException && err.status === 404) setNotFound(true);
      else setError(err instanceof Error && err.message ? err.message : 'Không thể tải bài viết.');
    } finally {
      setLoading(false);
    }
  };

  // Re-read after joining the class (classroom.isMember flips): a locked post then comes back with its content.
  useEffect(() => { void load(); }, [postId, classroom.isMember]);
  useEffect(() => { window.scrollTo?.(0, 0); }, [postId]);

  // Older / newer neighbours from the first page of the list - one cheap request, silently skipped when it fails.
  useEffect(() => {
    if (!post || post.status !== 'PUBLISHED') { setNeighbours({}); return; }
    let alive = true;
    listBlogPosts(classroom.id, { size: 50 })
      .then((page) => {
        if (!alive) return;
        const items = page?.items ?? [];
        const index = items.findIndex((p) => p.id === post.id);
        setNeighbours(index < 0 ? {} : { newer: items[index - 1], older: items[index + 1] });
      })
      .catch(() => { if (alive) setNeighbours({}); });
    return () => { alive = false; };
  }, [post?.id, post?.status, classroom.id]);

  const progress = useReadingProgress(!!post && !post.locked);
  const blogPath = `/classes/${classroom.slug}/blog`;

  const backLink = (
    <Link to={blogPath} className="inline-flex items-center gap-2 text-ui font-medium text-slate-600 hover:text-slate-900">
      <ChevronLeft className="h-4 w-4" strokeWidth={1.8} aria-hidden="true" />
      Blog
    </Link>
  );

  if (loading) return <LoadingSpinner message="Đang tải bài viết..." />;

  if (notFound) {
    return (
      <div className="mx-auto max-w-reading space-y-4">
        {backLink}
        <div role="alert" className="flex flex-col items-center rounded-card border border-slate-200 bg-white p-10 text-center shadow-hairline">
          <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-community bg-slate-100 text-slate-500">
            <SearchX className="h-6 w-6" strokeWidth={1.7} aria-hidden="true" />
          </div>
          <h1 className="text-h3 font-semibold text-slate-900">Không tìm thấy bài viết</h1>
          <p className="mt-1 max-w-sm text-ui text-slate-600">Bài viết có thể đã bị gỡ hoặc chưa được đăng. Hãy quay lại Blog để đọc các bài khác.</p>
          <Link to={blogPath} className={buttonClass('secondary', 'md', 'mt-5')}>Về Blog</Link>
        </div>
      </div>
    );
  }

  if (error || !post) {
    return (
      <div className="mx-auto max-w-reading space-y-4">
        {backLink}
        <ErrorBanner message={error || 'Không thể tải bài viết.'} onRetry={load} />
      </div>
    );
  }

  return (
    <>
      {!post.locked && (
        <div
          role="progressbar"
          aria-label="Tiến độ đọc"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={Math.round(progress)}
          className="fixed inset-x-0 top-16 z-[45] h-[3px] bg-slate-100"
        >
          <div className="h-full rounded-r-full bg-blue-600" style={{ width: `${progress}%` }} />
        </div>
      )}

      <article className="mx-auto max-w-reading pb-12">
        {backLink}

        <header className="mt-6 sm:mt-8">
          <div className="flex flex-wrap items-center gap-2">
            <BlogCategoryChip category={post.category} size="md" />
            {post.status === 'DRAFT' && <Badge tone="warn" size="sm">Bản nháp — chỉ người quản lý thấy</Badge>}
            {post.audience === 'MEMBERS' && (
              <Badge tone="member" size="sm"><Lock className="h-3 w-3" strokeWidth={2} aria-hidden="true" />Dành cho thành viên</Badge>
            )}
          </div>
          <h1 className="mt-4 text-pretty text-[23px] font-semibold leading-[31px] tracking-[-0.4px] text-slate-900 sm:text-[34px] sm:leading-[44px] sm:tracking-[-0.6px]">
            {post.title}
          </h1>
          {post.excerpt && <p className="mt-3 text-body-sm text-slate-600 sm:text-[18px] sm:leading-7">{post.excerpt}</p>}
          <div className="mt-5 flex items-center gap-3 border-y border-slate-200 py-4">
            <Avatar name={post.author.fullName} src={post.author.avatarUrl} size={40} />
            <div className="min-w-0">
              <p className="truncate text-ui font-semibold text-slate-900">{post.author.fullName}</p>
              <p className="text-meta text-slate-500 tabular">{formatDate(blogPostDate(post))} · {post.readingMinutes} phút đọc</p>
            </div>
          </div>
        </header>

        {post.coverUrl && (
          <figure className="mt-7 overflow-hidden rounded-card bg-slate-100">
            <div className="aspect-video">
              <BlogCover src={post.coverUrl} seed={post.category || post.id} alt={`Ảnh bìa bài viết: ${post.title}`} />
            </div>
          </figure>
        )}

        {post.locked ? (
          <section aria-labelledby="locked-title" className="mt-8">
            {/* Skeleton of the content the reader is missing - no real text is available to a non-member. */}
            <div aria-hidden="true" className="space-y-3 rounded-2xl bg-slate-50 p-6">
              {[100, 94, 97, 60].map((w, i) => (
                <div key={i} className={`h-3 rounded-full ${i % 2 ? 'bg-line-soft' : 'bg-slate-200'}`} style={{ width: `${w}%` }} />
              ))}
            </div>
            <div className="mt-5 flex flex-col items-center rounded-card border border-slate-200 bg-white p-8 text-center shadow-hairline">
              <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-community bg-blue-100 text-blue-800">
                <Lock className="h-6 w-6" strokeWidth={1.7} aria-hidden="true" />
              </div>
              <h2 id="locked-title" className="text-h3-lg font-semibold text-slate-900">Bài viết dành cho thành viên lớp</h2>
              <p className="mt-1 max-w-md text-ui text-slate-600">
                Tham gia {classroom.title} để đọc trọn bài này và mọi bài viết dành cho thành viên.
              </p>
              <ClassJoinCta
                classroom={classroom}
                refreshClassroom={refreshClassroom}
                signInLabel="Đăng nhập để đọc tiếp"
                joinLabel="Tham gia lớp để đọc tiếp"
                className="mt-5"
              />
            </div>
          </section>
        ) : (
          <SafeMarkdown source={post.contentMarkdown || ''} className="mt-8" />
        )}

        {(neighbours.older || neighbours.newer) && (
          <nav aria-label="Bài trước và bài sau" className="mt-10 grid gap-3 border-t border-slate-200 pt-6 sm:grid-cols-2 sm:gap-4">
            {neighbours.older && <NeighbourLink post={neighbours.older} slug={classroom.slug} direction="older" />}
            {neighbours.newer && <NeighbourLink post={neighbours.newer} slug={classroom.slug} direction="newer" />}
          </nav>
        )}

        <section className="mt-8 flex flex-col gap-4 rounded-card border border-slate-200 bg-slate-50 p-6 sm:flex-row sm:items-start">
          <Avatar name={post.author.fullName} src={post.author.avatarUrl} size={56} />
          <div className="min-w-0 flex-1">
            <p className="text-body-sm font-semibold text-slate-900">{post.author.fullName}</p>
            <p className="mt-0.5 text-meta text-slate-600">Người viết · {classroom.title}</p>
            <p className="mt-2.5 text-ui text-slate-700">Đọc xong còn thắc mắc? Đặt câu hỏi trong Thảo luận để người viết và cả lớp cùng trả lời.</p>
          </div>
          <Link to={`/classes/${classroom.slug}/feed`} className={buttonClass('secondary', 'md', 'self-start')}>
            <MessageSquare className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
            Vào Thảo luận
          </Link>
        </section>
      </article>
    </>
  );
};
