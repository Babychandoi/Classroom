// Client-side mirror of the backend VideoLinkParser (docs/API-VIDEO-LINKS.md): the same accepted shapes, so authors get an
// instant, friendly answer. It is only a convenience - the server parses again and its 400 text is shown too.

export type VideoProvider = 'YOUTUBE' | 'GOOGLE_DRIVE';

export interface ParsedVideoLink {
  provider: VideoProvider;
  id: string;
  /** What the viewer iframe loads. */
  embedUrl: string;
  /** Canonical page of the video. */
  videoUrl: string;
}

export const PROVIDER_LABEL: Record<VideoProvider, string> = { YOUTUBE: 'YouTube', GOOGLE_DRIVE: 'Google Drive' };

export const UNSUPPORTED_LINK_MESSAGE = 'Chỉ hỗ trợ liên kết video YouTube hoặc Google Drive (dạng https://…).';

const YOUTUBE_HOSTS = new Set(['youtube.com', 'www.youtube.com', 'm.youtube.com', 'youtu.be', 'youtube-nocookie.com', 'www.youtube-nocookie.com']);
const YOUTUBE_ID = /^[A-Za-z0-9_-]{11}$/;
const DRIVE_ID = /^[A-Za-z0-9_-]{6,128}$/;

function parseYoutube(url: URL): string | null {
  const parts = url.pathname.split('/').filter(Boolean);
  let id: string | undefined;
  if (url.hostname === 'youtu.be') id = parts[0];
  else if (parts[0] === 'watch') id = url.searchParams.get('v') ?? undefined;
  else if (['embed', 'shorts', 'live', 'v'].includes(parts[0])) id = parts[1];
  return id && YOUTUBE_ID.test(id) ? id : null;
}

function parseDrive(url: URL): string | null {
  const parts = url.pathname.split('/').filter(Boolean);
  let id: string | undefined;
  if (parts[0] === 'file') {
    // /file/d/ID/(view|preview|edit) or /file/u/<n>/d/ID/...
    const d = parts[1] === 'd' ? 2 : parts[1] === 'u' && /^\d+$/.test(parts[2] ?? '') && parts[3] === 'd' ? 4 : -1;
    if (d > 0) id = parts[d];
  } else if (parts[0] === 'open' || parts[0] === 'uc') {
    id = url.searchParams.get('id') ?? undefined;
  }
  return id && DRIVE_ID.test(id) ? id : null;
}

/** Parses a pasted link; null when it is not one of the accepted shapes (https only, no userinfo, no custom port). */
export function parseVideoLink(raw: string): ParsedVideoLink | null {
  const text = raw.replace(/\s+/g, '');
  if (!text) return null;
  let url: URL;
  try { url = new URL(text); } catch { return null; }
  if (url.protocol !== 'https:' || url.username || url.password || url.port) return null;
  const host = url.hostname.toLowerCase();
  if (YOUTUBE_HOSTS.has(host)) {
    const id = parseYoutube(url);
    return id ? { provider: 'YOUTUBE', id, embedUrl: `https://www.youtube-nocookie.com/embed/${id}`, videoUrl: `https://www.youtube.com/watch?v=${id}` } : null;
  }
  if (host === 'drive.google.com') {
    const id = parseDrive(url);
    return id ? { provider: 'GOOGLE_DRIVE', id, embedUrl: `https://drive.google.com/file/d/${id}/preview`, videoUrl: `https://drive.google.com/file/d/${id}/view` } : null;
  }
  return null;
}

/** Friendly message for the chosen source tab, or null when the text is empty or a valid link of that provider. */
export function videoLinkError(raw: string, expected: VideoProvider): string | null {
  if (!raw.trim()) return null;
  if (/^http:\/\//i.test(raw.trim())) return 'Liên kết phải bắt đầu bằng https://.';
  const parsed = parseVideoLink(raw);
  if (!parsed) return expected === 'YOUTUBE'
    ? 'Liên kết YouTube không hợp lệ. Dán dạng https://www.youtube.com/watch?v=… hoặc https://youtu.be/…'
    : 'Liên kết Google Drive không hợp lệ. Dán dạng https://drive.google.com/file/d/…/view (không dùng thư mục).';
  if (parsed.provider !== expected) return `Đây là liên kết ${PROVIDER_LABEL[parsed.provider]}, hãy chọn tab ${PROVIDER_LABEL[parsed.provider]}.`;
  return null;
}
