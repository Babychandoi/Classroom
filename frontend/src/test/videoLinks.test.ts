import { describe, expect, it } from 'vitest';
import { parseVideoLink, videoLinkError } from '../api/videoLinks';

const ID = 'dQw4w9WgXcQ';
describe('parseVideoLink (mirror of the server VideoLinkParser)', () => {
  it.each([
    `https://www.youtube.com/watch?v=${ID}&t=42s&list=PL1&si=abc`,
    `https://youtube.com/watch?v=${ID}`,
    `https://m.youtube.com/watch?v=${ID}`,
    `https://youtu.be/${ID}?si=xyz`,
    `https://www.youtube.com/embed/${ID}`,
    `https://www.youtube.com/shorts/${ID}`,
    `https://www.youtube.com/live/${ID}`,
    `https://www.youtube.com/v/${ID}`,
    `https://www.youtube-nocookie.com/embed/${ID}`,
    `  https://youtu.be/${ID}  `,
  ])('accepts YouTube %s', (url) => {
    expect(parseVideoLink(url)).toEqual({
      provider: 'YOUTUBE', id: ID, embedUrl: `https://www.youtube-nocookie.com/embed/${ID}`, videoUrl: `https://www.youtube.com/watch?v=${ID}`,
    });
  });

  it.each([
    'https://drive.google.com/file/d/1AbCdEfGhIj/view?usp=sharing',
    'https://drive.google.com/file/d/1AbCdEfGhIj/preview',
    'https://drive.google.com/file/d/1AbCdEfGhIj/edit',
    'https://drive.google.com/open?id=1AbCdEfGhIj',
    'https://drive.google.com/uc?id=1AbCdEfGhIj&export=download',
    'https://drive.google.com/file/u/1/d/1AbCdEfGhIj/view',
  ])('accepts Google Drive %s', (url) => {
    expect(parseVideoLink(url)).toMatchObject({
      provider: 'GOOGLE_DRIVE', id: '1AbCdEfGhIj', embedUrl: 'https://drive.google.com/file/d/1AbCdEfGhIj/preview', videoUrl: 'https://drive.google.com/file/d/1AbCdEfGhIj/view',
    });
  });

  it.each([
    '', 'not a url', `http://youtu.be/${ID}`, `javascript:alert(1)`, `https://youtube.com.evil.example/watch?v=${ID}`,
    `https://evilyoutube.com/watch?v=${ID}`, `https://user:pw@www.youtube.com/watch?v=${ID}`, `https://www.youtube.com:8443/watch?v=${ID}`,
    'https://www.youtube.com/watch?v=short', 'https://www.youtube.com/playlist?list=PL12345678901',
    'https://drive.google.com/drive/folders/1AbCdEfGhIj', 'https://docs.google.com/file/d/1AbCdEfGhIj/view', 'https://drive.google.com/file/d/ab/view',
    'https://vimeo.com/123456789',
  ])('rejects %s', (url) => {
    expect(parseVideoLink(url)).toBeNull();
  });

  it('gives friendly messages per source tab', () => {
    expect(videoLinkError('', 'YOUTUBE')).toBeNull();
    expect(videoLinkError(`https://youtu.be/${ID}`, 'YOUTUBE')).toBeNull();
    expect(videoLinkError(`http://youtu.be/${ID}`, 'YOUTUBE')).toMatch(/https/);
    expect(videoLinkError('https://vimeo.com/1', 'YOUTUBE')).toMatch(/YouTube không hợp lệ/);
    expect(videoLinkError('https://vimeo.com/1', 'GOOGLE_DRIVE')).toMatch(/Google Drive không hợp lệ/);
    expect(videoLinkError(`https://youtu.be/${ID}`, 'GOOGLE_DRIVE')).toMatch(/tab YouTube/);
  });
});
