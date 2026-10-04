import React from 'react';
import { FileText, Lock } from 'lucide-react';
import { CoverImage, toneFor } from './ui';

// Small pieces shared by the Blog tab, the reading page and the Studio blog list.

/** Category pill tinted by a stable topic tone of the category name (design: "Bắt đầu kênh" chip on every card). */
export const BlogCategoryChip: React.FC<{ category?: string | null; size?: 'sm' | 'md'; className?: string }> = ({ category, size = 'sm', className }) => {
  if (!category) return null;
  return (
    <span
      className={`inline-flex max-w-full flex-shrink-0 items-center truncate whitespace-nowrap rounded-full font-semibold ${
        size === 'md' ? 'h-6 px-2.5 text-caption' : 'h-[22px] px-[9px] text-micro'
      } ${toneFor(category)} ${className ?? ''}`}
    >
      {category}
    </span>
  );
};

/** "Thành viên" lock chip for a post a non-member may see but not read. */
export const MembersOnlyChip: React.FC<{ className?: string }> = ({ className }) => (
  <span className={`inline-flex h-[22px] flex-shrink-0 items-center gap-1 whitespace-nowrap rounded-full bg-blue-100 px-2 text-micro font-semibold text-blue-800 ${className ?? ''}`}>
    <Lock className="h-3 w-3" strokeWidth={2} aria-hidden="true" />
    Thành viên
  </span>
);

/** Cover image, or the topic tile with a stroke icon when the post has none (no off-topic stock photos). */
export const BlogCover: React.FC<{ src?: string | null; seed: string; alt?: string; className?: string; iconSize?: number }> = ({
  src, seed, alt = '', className, iconSize = 40,
}) => (
  <CoverImage
    src={src}
    seed={seed}
    alt={alt}
    className={className}
    icon={<FileText style={{ width: iconSize, height: iconSize }} strokeWidth={1.5} aria-hidden="true" />}
  />
);
