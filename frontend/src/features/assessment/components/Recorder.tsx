import React, { useEffect, useRef, useState } from 'react';
import { AlertTriangle, Mic, Play, Square, Trash2, Upload } from 'lucide-react';
import { Button } from '../../../components/ui';
import type { RecordingInfo, RecordingPolicy } from '../types';
import { cx, mmss } from '../format';
import { probeAudioDuration } from '../api';
import { Tag } from './primitives';

export type RecorderState = 'idle' | 'asking' | 'denied' | 'unsupported' | 'prep' | 'recording' | 'uploading' | 'saved' | 'error';

export interface RecorderProps {
  policy: RecordingPolicy;
  /** The saved recording of this item (from the attempt), or null. */
  value: { assetId: string; durationMs: number } | null;
  takesUsed?: number;
  /** Preview / result mode: shows the control but does nothing. */
  preview?: boolean;
  /** recording-intent → PUT → recording-complete. */
  onUpload?: (file: File | Blob, durationMs: number, filename: string) => Promise<RecordingInfo>;
  onDelete?: () => Promise<void>;
  /** Short-lived URL of the saved recording. */
  onRequestUrl?: () => Promise<string>;
}

const PREFERRED_TYPES = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/mp4'];

function pickMime(): string | undefined {
  const MR = (globalThis as { MediaRecorder?: { isTypeSupported?: (t: string) => boolean } }).MediaRecorder;
  if (!MR?.isTypeSupported) return undefined;
  return PREFERRED_TYPES.find((t) => { try { return MR.isTypeSupported!(t); } catch { return false; } });
}

const extFor = (mime: string) => (mime.includes('ogg') ? 'ogg' : mime.includes('mp4') ? 'm4a' : 'webm');

/** Speaking recorder (Q22): Ghi âm → Dừng → tải lên; microphone-denied guidance with a file fallback. */
export const Recorder: React.FC<RecorderProps> = ({ policy, value, takesUsed = 0, preview, onUpload, onDelete, onRequestUrl }) => {
  const [state, setState] = useState<RecorderState>(value ? 'saved' : 'idle');
  const [sec, setSec] = useState(0);
  const [prep, setPrep] = useState(0);
  const [takes, setTakes] = useState(takesUsed);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(value);
  const [playUrl, setPlayUrl] = useState<string | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const recRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const startedRef = useRef(0);
  const fileRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => { setSaved(value); if (value) setState((s) => (s === 'idle' ? 'saved' : s)); }, [value]);
  useEffect(() => { setTakes(takesUsed); }, [takesUsed]);
  useEffect(() => () => { streamRef.current?.getTracks().forEach((t) => t.stop()); }, []);

  const left = policy.maxRetakes + 1 - takes;

  // Prep countdown, then record.
  useEffect(() => {
    if (state !== 'prep') return undefined;
    if (prep <= 0) { beginRecording(); return undefined; }
    const id = setTimeout(() => setPrep((p) => p - 1), 1000);
    return () => clearTimeout(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state, prep]);

  // Recording timer, auto-stop at maxSeconds.
  useEffect(() => {
    if (state !== 'recording') return undefined;
    const id = setInterval(() => {
      const s = Math.floor((Date.now() - startedRef.current) / 1000);
      setSec(s);
      if (s >= policy.maxSeconds) stop();
    }, 250);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state, policy.maxSeconds]);

  const upload = async (blob: File | Blob, durationMs: number, filename: string) => {
    if (!onUpload) return;
    if (blob.size > policy.maxFileMb * 1024 * 1024) { setError(`Bản ghi vượt quá ${policy.maxFileMb} MB.`); setState(saved ? 'saved' : 'error'); return; }
    setState('uploading');
    setError(null);
    try {
      const info = await onUpload(blob, durationMs, filename);
      setSaved({ assetId: info.assetId, durationMs: info.durationMs });
      setTakes(info.takesUsed);
      setPlayUrl(null);
      setState('saved');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không tải được bản ghi âm');
      setState(saved ? 'saved' : 'error');
    }
  };

  const beginRecording = () => {
    const stream = streamRef.current;
    if (!stream) { setState('idle'); return; }
    const mimeType = pickMime();
    let rec: MediaRecorder;
    try {
      rec = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream);
    } catch {
      setState('unsupported');
      return;
    }
    chunksRef.current = [];
    rec.ondataavailable = (e) => { if (e.data && e.data.size > 0) chunksRef.current.push(e.data); };
    rec.onstop = () => {
      stream.getTracks().forEach((t) => t.stop());
      streamRef.current = null;
      const type = rec.mimeType || mimeType || 'audio/webm';
      const blob = new Blob(chunksRef.current, { type });
      const ms = Math.max(1000, Date.now() - startedRef.current);
      void upload(blob, ms, `ghi-am.${extFor(type)}`);
    };
    recRef.current = rec;
    startedRef.current = Date.now();
    setSec(0);
    rec.start(1000);
    setState('recording');
  };

  const stop = () => {
    const rec = recRef.current;
    if (rec && rec.state !== 'inactive') rec.stop();
    recRef.current = null;
  };

  const start = async () => {
    if (preview || left <= 0) return;
    setError(null);
    const md = typeof navigator !== 'undefined' ? navigator.mediaDevices : undefined;
    if (!md?.getUserMedia || typeof (globalThis as { MediaRecorder?: unknown }).MediaRecorder === 'undefined') { setState('unsupported'); return; }
    setState('asking');
    try {
      streamRef.current = await md.getUserMedia({ audio: true });
    } catch (err) {
      const name = (err as { name?: string })?.name;
      setState(name === 'NotFoundError' || name === 'OverconstrainedError' ? 'unsupported' : 'denied');
      return;
    }
    if (policy.prepSeconds > 0) { setPrep(policy.prepSeconds); setState('prep'); } else beginRecording();
  };

  const onPickFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    if (!file.type.startsWith('audio/')) { setError('Hãy chọn một tệp âm thanh (MP3, M4A, WAV, OGG, WebM).'); return; }
    const ms = (await probeAudioDuration(file)) ?? 0;
    if (ms > 0 && ms > (policy.maxSeconds + 5) * 1000) { setError(`Bản ghi dài hơn ${policy.maxSeconds} giây.`); return; }
    await upload(file, ms || 1000, file.name);
  };

  const remove = async () => {
    if (!onDelete) return;
    try { await onDelete(); setSaved(null); setPlayUrl(null); setState('idle'); } catch (err) { setError(err instanceof Error ? err.message : 'Không xóa được bản ghi'); }
  };

  const playSaved = async () => {
    if (!onRequestUrl) return;
    try { setPlayUrl(await onRequestUrl()); } catch (err) { setError(err instanceof Error ? err.message : 'Không phát được bản ghi'); }
  };

  const fallback = policy.allowFileFallback && !preview && (
    <>
      <input ref={fileRef} type="file" accept="audio/*" className="hidden" aria-label="Chọn tệp ghi âm" onChange={onPickFile} />
      <Button variant="secondary" onClick={() => fileRef.current?.click()} disabled={left <= 0}>
        <Upload className="h-4 w-4" aria-hidden="true" />Tải tệp ghi âm thay thế
      </Button>
    </>
  );

  const box = (children: React.ReactNode) => (
    <div className="flex flex-col gap-3 rounded-2xl border border-slate-200 bg-slate-50 p-3.5" aria-live="polite">
      {children}
      {error && <p role="alert" className="text-caption font-medium text-red-700">{error}</p>}
    </div>
  );

  if (state === 'denied' || state === 'unsupported') {
    return box(
      <>
        <div className="flex items-start gap-2.5">
          <AlertTriangle className="mt-0.5 h-5 w-5 flex-none text-red-700" aria-hidden="true" />
          <div>
            <div className="text-ui font-bold text-slate-900">{state === 'denied' ? 'Trình duyệt chưa cho dùng microphone' : 'Thiết bị không ghi âm được trên trình duyệt này'}</div>
            <p className="mt-1 text-meta text-slate-600">
              {state === 'denied'
                ? 'Bấm biểu tượng ổ khóa cạnh thanh địa chỉ → Microphone → Cho phép, rồi tải lại trang. Bài làm đã được lưu nháp.'
                : 'Không tìm thấy microphone hoặc trình duyệt không hỗ trợ ghi âm. Hãy thử trình duyệt khác, hoặc ghi âm bằng điện thoại rồi tải tệp lên.'}
            </p>
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="primary" onClick={() => setState(saved ? 'saved' : 'idle')}>Thử lại</Button>
          {fallback}
        </div>
      </>,
    );
  }

  if (state === 'prep') {
    return box(
      <div className="flex items-center gap-3.5">
        <span className="flex h-14 w-14 flex-none items-center justify-center rounded-full bg-slate-900 text-h2-sm font-extrabold text-white" aria-hidden="true">{prep}</span>
        <div className="flex-1"><div className="text-body-sm font-bold">Chuẩn bị…</div><div className="text-caption text-slate-500">Ghi âm tự bắt đầu sau {prep} giây</div></div>
        <Button variant="secondary" onClick={() => setPrep(0)}>Bắt đầu ngay</Button>
      </div>,
    );
  }

  if (state === 'recording') {
    return box(
      <div className="flex items-center gap-3.5">
        <button type="button" onClick={stop} aria-label="Dừng ghi âm" className="flex h-14 w-14 flex-none items-center justify-center rounded-full border-[3px] border-red-600 bg-white">
          <Square className="h-[18px] w-[18px] fill-red-600 text-red-600" aria-hidden="true" />
        </button>
        <div className="flex h-8 flex-1 items-center gap-[3px] overflow-hidden" aria-hidden="true">
          {Array.from({ length: 28 }, (_, i) => <span key={i} className="max-w-[4px] flex-1 animate-pulse rounded-sm bg-red-600" style={{ height: `${25 + ((i * 53) % 75)}%` }} />)}
        </div>
        <span className="flex-none font-mono text-ui font-bold text-red-600" role="timer">{mmss(sec)} / {mmss(policy.maxSeconds)}</span>
      </div>,
    );
  }

  if (state === 'uploading') {
    return box(<div role="status" className="text-meta font-semibold text-slate-700">Đang tải bản ghi lên…</div>);
  }

  if (state === 'saved' && saved) {
    return box(
      <>
        <div className="flex items-center gap-3">
          <button type="button" onClick={playSaved} disabled={!onRequestUrl} aria-label="Nghe lại bản ghi" className="flex h-11 w-11 flex-none items-center justify-center rounded-full bg-slate-900 text-white disabled:opacity-60">
            <Play className="h-[18px] w-[18px]" aria-hidden="true" />
          </button>
          {playUrl ? <audio src={playUrl} controls autoPlay className="h-9 min-w-0 flex-1" /> : <div className="h-8 flex-1 rounded-md bg-[repeating-linear-gradient(90deg,#CBD5E1_0_3px,transparent_3px_6px)] opacity-70" aria-hidden="true" />}
          <span className="flex-none font-mono text-meta font-semibold text-slate-600">{mmss(saved.durationMs / 1000)}</span>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Tag label="Đã ghi âm · đã lưu" color="green" dot />
          <span className="flex-1" />
          {!preview && onDelete && (
            <Button variant="ghost" size="sm" onClick={remove} aria-label="Xóa bản ghi"><Trash2 className="h-4 w-4" aria-hidden="true" /></Button>
          )}
          {!preview && (
            <Button variant="secondary" size="sm" disabled={left <= 0} onClick={start}>{left > 0 ? `Ghi lại (còn ${left})` : 'Hết lượt ghi lại'}</Button>
          )}
        </div>
      </>,
    );
  }

  return box(
    <>
      <div className="flex items-center gap-3.5">
        <button
          type="button"
          onClick={start}
          disabled={preview || left <= 0 || state === 'asking'}
          aria-label="Bắt đầu ghi âm"
          className={cx('flex h-14 w-14 flex-none items-center justify-center rounded-full bg-red-600 text-white shadow-[0_0_0_6px_rgba(220,38,38,.12)]', (preview || left <= 0) && 'cursor-default opacity-80')}
        >
          <Mic className="h-6 w-6" aria-hidden="true" />
        </button>
        <div className="min-w-0 flex-1">
          <div className="text-body-sm font-bold text-slate-900">{state === 'asking' ? 'Đang xin quyền microphone…' : left <= 0 ? 'Đã hết lượt ghi âm' : 'Bấm để ghi âm'}</div>
          <div className="text-caption text-slate-500">
            {policy.prepSeconds ? `${policy.prepSeconds} giây chuẩn bị · ` : ''}Tối đa {policy.maxSeconds} giây · ghi lại được {policy.maxRetakes} lần
          </div>
        </div>
      </div>
      {!preview && policy.allowFileFallback && state === 'error' && <div className="flex flex-wrap gap-2">{fallback}</div>}
    </>,
  );
};
