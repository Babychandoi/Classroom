import { ApiException } from './client';

/**
 * R20-12: what the user sees when the file cannot be sent to the object store (MinIO unreachable / failing) instead of the browser's raw
 * "Failed to fetch". The same wording as the backend's 503 for /media/{id}/complete, so both steps of an upload read the same.
 */
export const UPLOAD_UNAVAILABLE_MESSAGE = 'Kho lưu trữ tệp tạm thời không khả dụng. Vui lòng thử lại sau vài giây.';

const unavailable = () => new ApiException('SERVICE_UNAVAILABLE', UPLOAD_UNAVAILABLE_MESSAGE, undefined, 503);

/**
 * Sends `file` to a presigned object-store URL (step 2 of an upload, between `upload-intents` and `complete`).
 *
 * The presigned URL points straight at MinIO, not at the API, so a MinIO outage never reaches our error envelope: `fetch` just rejects
 * (with a bare `TypeError: Failed to fetch`) or a proxy answers 5xx. Both are transient and retryable and become a 503
 * `ApiException` carrying a Vietnamese "try again" message. Any other non-2xx answer is a definite refusal and keeps `failureMessage`.
 */
export async function putToObjectStore(uploadUrl: string, file: File, failureMessage = 'Tải tệp thất bại'): Promise<void> {
  let response: Response;
  try {
    response = await fetch(uploadUrl, { method: 'PUT', headers: { 'Content-Type': file.type }, body: file });
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err;
    throw unavailable();
  }
  if (response.status >= 500 || response.status === 408 || response.status === 429) throw unavailable();
  if (!response.ok) throw new Error(`${failureMessage} (${response.status})`);
}
