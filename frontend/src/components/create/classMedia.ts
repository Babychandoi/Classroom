import { api } from '../../api/client';
import { putToObjectStore } from '../../api/upload';

// Class cover / avatar uploads go through the usual media flow: upload-intent (purpose CLASS_COVER or CLASS_AVATAR)
// -> PUT to the presigned object-store URL -> /media/{id}/complete. Images only, at most 5 MB (checked here first
// so a wrong file never costs a round trip).

export const CLASS_IMAGE_MAX_BYTES = 5 * 1024 * 1024;
export const CLASS_IMAGE_ACCEPT = 'image/jpeg,image/png,image/webp,image/gif';

export type ClassImagePurpose = 'CLASS_COVER' | 'CLASS_AVATAR';

/** Why `file` cannot be a class image, or null when it is fine. */
export function classImageProblem(file: File): string | null {
  if (!file.type.startsWith('image/')) return 'Chỉ nhận tệp ảnh (JPG, PNG, WebP hoặc GIF).';
  if (file.size > CLASS_IMAGE_MAX_BYTES) return 'Ảnh tối đa 5 MB.';
  return null;
}

/** Uploads `file` for class `classId` and resolves to the media asset id (coverMediaId / avatarMediaId). */
export async function uploadClassImage(classId: string, file: File, purpose: ClassImagePurpose): Promise<string> {
  const problem = classImageProblem(file);
  if (problem) throw new Error(problem);
  const intent = await api.post<{ assetId: string; uploadUrl: string }>(`/classes/${classId}/media/upload-intents`, {
    filename: file.name,
    mimeType: file.type,
    sizeBytes: file.size,
    purpose,
  });
  await putToObjectStore(intent.uploadUrl, file, 'Không thể tải ảnh lên');
  await api.post(`/media/${intent.assetId}/complete`);
  return intent.assetId;
}

/** The fixed category list of docs/API-CREATE-CLASS.md, used when GET /classes/categories is not available. */
export const FALLBACK_CATEGORIES = [
  'Nấu ăn', 'Ăn chay', 'Sức khoẻ', 'Chạy bộ', 'Thể hình', 'YouTube',
  'Kinh doanh', 'Tiếng Anh', 'Ôn thi', 'AI', 'Âm nhạc', 'Phát triển bản thân',
];

/** "199000" / "199.000" / "199k" -> "199.000" (digits only, vi-VN grouping, at most 9 digits); '' when empty. */
export function formatPriceInput(raw: string): string {
  const digits = raw.replace(/\D/g, '').replace(/^0+/, '').slice(0, 9);
  return digits ? Number(digits).toLocaleString('vi-VN') : '';
}

export const priceValue = (formatted: string): number => Number(formatted.replace(/\D/g, '')) || 0;
