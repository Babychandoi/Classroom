import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { StudioDocuments } from '../pages/studio/StudioCommunity';
import { putToObjectStore, UPLOAD_UNAVAILABLE_MESSAGE } from '../api/upload';
import { ApiException } from '../api/client';
import type { Classroom } from '../types';

/**
 * R20-12: with MinIO unreachable the presigned PUT rejects with the browser's bare "Failed to fetch" and /media/{id}/complete answers 503.
 * Both must reach the teacher as a retryable Vietnamese message, never the raw browser text.
 */

const classroom = { id: 'class-1', ownerId: 'o', slug: 'demo', title: 'Demo', status: 'ACTIVE', memberCount: 1, userRole: 'OWNER', createdAt: '' } as Classroom;

vi.mock('react-router-dom', () => ({
  useOutletContext: () => ({ classroom }),
  useParams: () => ({ id: 'class-1' }),
}));

const file = new File(['%PDF-1.4'], 'bai-giang.pdf', { type: 'application/pdf' });

describe('putToObjectStore (R20-12)', () => {
  afterEach(() => vi.restoreAllMocks());

  it('turns a network failure ("Failed to fetch") into a retryable 503 ApiException with a Vietnamese message', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'));
    const error = await putToObjectStore('http://localhost:9000/put', file).catch((e) => e);
    expect(error).toBeInstanceOf(ApiException);
    expect(error.status).toBe(503);
    expect(error.message).toBe(UPLOAD_UNAVAILABLE_MESSAGE);
    expect(error.message).not.toMatch(/failed to fetch/i);
  });

  it('treats a 5xx from the store or its proxy the same way', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 502 }));
    await expect(putToObjectStore('http://x/put', file)).rejects.toMatchObject({ status: 503, message: UPLOAD_UNAVAILABLE_MESSAGE });
  });

  it('keeps a definite refusal (403 expired URL) as a plain failure with the status', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 403 }));
    await expect(putToObjectStore('http://x/put', file, 'Tải tệp thất bại')).rejects.toThrow('Tải tệp thất bại (403)');
  });

  it('resolves on success and sends the file with its content type', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 200 }));
    await expect(putToObjectStore('http://x/put', file)).resolves.toBeUndefined();
    expect(spy).toHaveBeenCalledWith('http://x/put', expect.objectContaining({ method: 'PUT', body: file }));
  });

  it('does not swallow a caller-initiated abort', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new DOMException('aborted', 'AbortError'));
    await expect(putToObjectStore('http://x/put', file)).rejects.toMatchObject({ name: 'AbortError' });
  });
});

describe('StudioDocuments upload UI (R20-12)', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('shows the retry message, not "Failed to fetch", when the store is unreachable during the PUT', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/upload-intents')) {
        return new Response(JSON.stringify({ success: true, data: { assetId: 'a1', uploadUrl: 'http://localhost:9000/put' } }), { status: 200 });
      }
      throw new TypeError('Failed to fetch');
    });
    const { container } = render(<StudioDocuments />);
    fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [file] } });
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(UPLOAD_UNAVAILABLE_MESSAGE));
    expect(screen.getByRole('status')).not.toHaveTextContent(/failed to fetch/i);
  });

  it('shows the backend 503 message when /complete reports the store unavailable', async () => {
    const backendMessage = 'Kho lưu trữ tệp tạm thời không khả dụng; vui lòng thử lại sau vài giây.';
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();
      if (url.includes('/upload-intents')) {
        return new Response(JSON.stringify({ success: true, data: { assetId: 'a1', uploadUrl: 'http://localhost:9000/put' } }), { status: 200 });
      }
      if (url.includes('/complete')) {
        return new Response(JSON.stringify({ success: false, error: { code: 'SERVICE_UNAVAILABLE', message: backendMessage, requestId: 'r1' } }), { status: 503, headers: { 'Retry-After': '5' } });
      }
      return new Response('', { status: 200 });
    });
    const { container } = render(<StudioDocuments />);
    fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [file] } });
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(backendMessage));
  });
});
