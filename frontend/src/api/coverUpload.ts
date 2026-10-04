import { api } from './client';
import { putToObjectStore } from './upload';

// Cover images of blog posts and events go through the same media flow as every other Studio upload:
// upload-intent (with a purpose) -> PUT to the presigned object-store URL -> /media/{id}/complete.
// The server only accepts images up to 5 MB for these purposes; checking first saves a pointless round trip.

export const COVER_MAX_BYTES = 5 * 1024 * 1024;
export const COVER_ACCEPT = 'image/jpeg,image/png,image/webp,image/gif';

export type CoverPurpose = 'BLOG' | 'EVENT';

/** The reason `file` cannot be a cover, or null when it is acceptable. */
export function coverFileProblem(file: File): string | null {
  if (!file.type.startsWith('image/')) return 'Ảnh bìa phải là tệp ảnh (JPG, PNG, WebP hoặc GIF).';
  if (file.size > COVER_MAX_BYTES) return 'Ảnh bìa tối đa 5 MB.';
  return null;
}

/** Uploads `file` as a cover of `purpose` in class `classId` and resolves to the media asset id to send as `coverMediaId`. */
export async function uploadCoverImage(classId: string, file: File, purpose: CoverPurpose): Promise<string> {
  const problem = coverFileProblem(file);
  if (problem) throw new Error(problem);
  const intent = await api.post<{ assetId: string; uploadUrl: string }>(`/classes/${classId}/media/upload-intents`, {
    filename: file.name,
    mimeType: file.type,
    sizeBytes: file.size,
    purpose,
  });
  await putToObjectStore(intent.uploadUrl, file, 'Không thể tải ảnh bìa');
  await api.post(`/media/${intent.assetId}/complete`);
  return intent.assetId;
}
