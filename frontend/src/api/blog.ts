import { api } from './client';
import type { BlogAudience, BlogPost, BlogPostPage } from '../types';

// Blog endpoints (docs/API-BLOG-EVENTS.md "Blog"). Thin wrappers so the pages and their tests agree on the exact URLs.

export type BlogStatusFilter = 'PUBLISHED' | 'DRAFT' | 'ALL';

export interface BlogListQuery {
  category?: string | null;
  cursor?: string | null;
  size?: number;
  /** Only callers with a BLOG grant may ask for drafts; omitted = published posts only. */
  status?: BlogStatusFilter;
}

export function blogListUrl(classId: string, query: BlogListQuery = {}): string {
  const params = new URLSearchParams();
  if (query.category) params.set('category', query.category);
  if (query.cursor) params.set('cursor', query.cursor);
  if (query.size) params.set('size', String(query.size));
  if (query.status && query.status !== 'PUBLISHED') params.set('status', query.status);
  const qs = params.toString();
  return `/classes/${classId}/blog-posts${qs ? `?${qs}` : ''}`;
}

export const listBlogPosts = (classId: string, query: BlogListQuery = {}) => api.get<BlogPostPage>(blogListUrl(classId, query));

export const listBlogCategories = (classId: string) => api.get<string[]>(`/classes/${classId}/blog-categories`);

export const getBlogPost = (postId: string) => api.get<BlogPost>(`/blog-posts/${postId}`);

export interface BlogPostInput {
  title: string;
  excerpt?: string | null;
  category?: string | null;
  contentMarkdown?: string | null;
  coverMediaId?: string | null;
  audience: BlogAudience;
}

export const createBlogPost = (classId: string, body: BlogPostInput) => api.post<BlogPost>(`/classes/${classId}/blog-posts`, body);
export const updateBlogPost = (postId: string, body: Partial<BlogPostInput>) => api.put<BlogPost>(`/blog-posts/${postId}`, body);
export const publishBlogPost = (postId: string) => api.post<BlogPost>(`/blog-posts/${postId}/publish`);
export const unpublishBlogPost = (postId: string) => api.post<BlogPost>(`/blog-posts/${postId}/unpublish`);
export const deleteBlogPost = (postId: string) => api.delete<unknown>(`/blog-posts/${postId}`);

/** The date a reader sees on a post: when it was published, or (for a draft) when it was last edited. */
export const blogPostDate = (post: Pick<BlogPost, 'publishedAt' | 'updatedAt' | 'createdAt'>) =>
  post.publishedAt || post.updatedAt || post.createdAt;
