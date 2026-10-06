import type { Lesson, LessonComponents } from '../types';

// A lesson is a bundle of optional components (video, content, documents, assignment), not one "type". The server sends
// `components`; payloads from before that change only carry the legacy `type`, so the bundle is derived from it as a fallback.

export function bundleOf(lesson: Lesson): LessonComponents {
  if (lesson.components) return lesson.components;
  const legacy = lesson.type;
  const external = lesson.videoProvider === 'YOUTUBE' || lesson.videoProvider === 'GOOGLE_DRIVE';
  const video = external || (!!lesson.mediaAssetId && legacy !== 'DOCUMENT') || legacy === 'VIDEO';
  const attachments = lesson.attachments?.length ?? (legacy === 'DOCUMENT' && lesson.mediaAssetId ? 1 : 0);
  return {
    video,
    videoProvider: external ? (lesson.videoProvider as 'YOUTUBE' | 'GOOGLE_DRIVE') : video && lesson.mediaAssetId ? 'UPLOAD' : null,
    content: !!lesson.contentText?.trim(),
    attachments,
    assignment: !!lesson.hasAssignment || legacy === 'ASSIGNMENT',
  };
}

export const providerName = (provider: LessonComponents['videoProvider']) =>
  provider === 'YOUTUBE' ? 'YouTube' : provider === 'GOOGLE_DRIVE' ? 'Google Drive' : null;

/** "Có video · YouTube", "2 tài liệu"... for the chips next to a lesson. */
export function componentChips(b: LessonComponents): string[] {
  const chips: string[] = [];
  if (b.video) chips.push(`Có video${b.videoProvider && b.videoProvider !== 'UPLOAD' ? ` · ${providerName(b.videoProvider)}` : ''}`);
  if (b.content) chips.push('Có nội dung');
  if (b.attachments > 0) chips.push(`${b.attachments} tài liệu`);
  if (b.assignment) chips.push('Có bài tập');
  return chips;
}

export const hasAnyComponent = (b: LessonComponents) => b.video || b.content || b.attachments > 0 || b.assignment;

export function formatBytes(bytes?: number): string {
  if (!bytes || bytes < 0) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1).replace('.', ',')} MB`;
}
