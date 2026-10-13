import React, { useEffect, useRef, useState } from 'react';
import { Lock, Pause, Play } from 'lucide-react';
import { ApiException } from '../../../api/client';
import type { PlaybackPolicy } from '../types';
import { cx, mmss } from '../format';

export interface AudioBarProps {
  name: string | null;
  durationMs?: number | null;
  languageCode?: string | null;
  policy?: PlaybackPolicy | null;
  /** student: counts plays (server-enforced); teacher: "Nghe thử · không tính lượt"; preview: like a student, nothing is sent. */
  mode: 'student' | 'teacher' | 'preview';
  /** Plays already used (student). */
  plays?: number;
  /**
   * Called when a new play starts. Resolves to a short-lived URL (and the server's play counter for students). A 409 ApiException means
   * the plays are exhausted. Return null when there is nothing to play (preview without a file).
   */
  onRequestPlay: () => Promise<{ url: string; plays?: number } | null>;
  compact?: boolean;
  disabled?: boolean;
  onPlaybackState?: (state: 'playing'|'ended'|'error') => void;
}

/** Real `<audio>` player that enforces the playback policy on the client (play count is enforced by the server). */
export const AudioBar: React.FC<AudioBarProps> = ({ name, durationMs, languageCode, policy, mode, plays = 0, onRequestPlay, compact, disabled, onPlaybackState }) => {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [url, setUrl] = useState<string | null>(null);
  const [playing, setPlaying] = useState(false);
  const [busy, setBusy] = useState(false);
  const [t, setT] = useState(0);
  const [dur, setDur] = useState((durationMs ?? 0) / 1000);
  const [rate, setRate] = useState(1);
  const [used, setUsed] = useState(plays);
  const [exhausted, setExhausted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [ended, setEnded] = useState(true);
  const autoStarted = useRef(false);

  useEffect(() => { setUsed(plays); }, [plays]);
  useEffect(() => { if (audioRef.current) audioRef.current.playbackRate = rate; }, [rate, url]);

  const teacher = mode === 'teacher';
  const max = policy?.maxPlays ?? 0;
  const left = max > 0 ? Math.max(0, max - used) : Infinity;
  const locked = !teacher && (exhausted || (max > 0 && left <= 0)) && !playing && ended;
  const canSeek = teacher || !!policy?.allowSeek;
  const canSpeed = teacher || !!policy?.allowSpeed;
  const pauseBlocked = !teacher && !policy?.allowSeek && playing;

  const startAudio = (src: string) => {
    const el = audioRef.current;
    if (!el) return;
    if (el.src !== src) el.src = src;
    try { el.currentTime = 0; } catch { /* not seekable yet */ }
    el.playbackRate = rate;
    try {
      const p = el.play() as Promise<void> | undefined;
      if (p && typeof p.catch === 'function') p.catch(() => { setPlaying(false); setError('Trình duyệt yêu cầu bấm Phát để bắt đầu.'); onPlaybackState?.('error'); });
    } catch { /* jsdom / autoplay policy */ }
    setPlaying(true);
    setEnded(false);
    onPlaybackState?.('playing');
  };

  const onPress = async () => {
    if (disabled || busy || locked) return;
    const el = audioRef.current;
    if (playing) {
      if (pauseBlocked) return;
      el?.pause();
      setPlaying(false);
      return;
    }
    if (!ended && url) { // resume a paused play (allowed only when pausing is)
      try { void el?.play(); } catch { /* ignore */ }
      setPlaying(true);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const r = await onRequestPlay();
      if (!r) { setError('Chưa có tệp nghe'); return; }
      if (typeof r.plays === 'number') setUsed(r.plays); else if (!teacher) setUsed((u) => u + 1);
      setUrl(r.url);
      startAudio(r.url);
    } catch (err) {
      if (err instanceof ApiException && err.status === 409) { setExhausted(true); setError(null); }
      else setError(err instanceof Error ? err.message : 'Không phát được tệp nghe');
    } finally {
      setBusy(false);
    }
  };

  const status = teacher
    ? 'Nghe thử · không tính lượt'
    : max > 0 ? (left > 0 && !exhausted ? `Còn ${left}/${max} lượt nghe` : 'Đã hết lượt nghe') : 'Nghe không giới hạn';
  const shownDur = dur || (durationMs ?? 0) / 1000;
  const pressRef=useRef(onPress); pressRef.current=onPress;
  useEffect(()=>{if(policy?.autoPlay && !disabled && !autoStarted.current){autoStarted.current=true;void pressRef.current();}},[policy?.autoPlay,disabled]);

  return (
    <div className={cx('flex flex-col gap-2 bg-slate-900 text-white', compact ? 'rounded-2xl p-3' : 'rounded-2xl p-3.5')}>
      <audio
        ref={audioRef}
        preload="none"
        onTimeUpdate={(e) => setT(e.currentTarget.currentTime)}
        onLoadedMetadata={(e) => { if (Number.isFinite(e.currentTarget.duration)) setDur(e.currentTarget.duration); }}
        onEnded={() => { setPlaying(false); setEnded(true); onPlaybackState?.('ended'); }}
        onError={() => {setError('Không đọc được tệp nghe.');setPlaying(false);setEnded(true);onPlaybackState?.('error');}}
        onPause={() => setPlaying(false)}
        onSeeking={(e) => { if (!canSeek && Math.abs(e.currentTarget.currentTime - t) > 1) e.currentTarget.currentTime = t; }}
        className="hidden"
      />
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={onPress}
          disabled={disabled || locked || busy}
          aria-label={locked ? 'Đã hết lượt nghe' : playing ? (pauseBlocked ? 'Đang phát · không cho dừng giữa chừng' : 'Tạm dừng') : 'Phát tệp nghe'}
          title={pauseBlocked ? 'Không cho dừng giữa chừng' : undefined}
          className={cx('flex h-11 w-11 flex-none items-center justify-center rounded-full text-slate-900',
            locked ? 'bg-white/15 text-white' : 'bg-white hover:bg-slate-100', (pauseBlocked || busy) && 'cursor-default')}
        >
          {locked ? <Lock className="h-[18px] w-[18px]" aria-hidden="true" /> : playing ? <Pause className="h-[18px] w-[18px]" aria-hidden="true" /> : <Play className="h-[18px] w-[18px]" aria-hidden="true" />}
        </button>
        <div className="min-w-0 flex-1">
          <div className="truncate text-meta font-semibold">{name || 'Chưa có tệp nghe'}</div>
          {canSeek ? (
            <input
              type="range"
              min={0}
              max={Math.max(1, shownDur)}
              step={0.1}
              value={Math.min(t, Math.max(1, shownDur))}
              aria-label="Vị trí phát"
              disabled={!url}
              onChange={(e) => { const v = Number(e.target.value); setT(v); if (audioRef.current) audioRef.current.currentTime = v; }}
              className="mt-2 h-1.5 w-full cursor-pointer accent-amber-500"
            />
          ) : (
            <div className="relative mt-2 h-1.5 cursor-not-allowed rounded-full bg-white/20" aria-hidden="true">
              <div className="h-full rounded-full bg-amber-500 transition-[width] duration-200" style={{ width: `${shownDur ? Math.min(100, (t / shownDur) * 100) : 0}%` }} />
            </div>
          )}
        </div>
        <span className="flex-none font-mono text-caption text-slate-300">{mmss(t)} / {mmss(shownDur)}</span>
      </div>
      <div className="flex flex-wrap items-center gap-2 text-caption text-slate-300">
        <span role="status" className={cx('font-semibold', !teacher && (exhausted || (max > 0 && left <= 0)) ? 'text-amber-400' : 'text-slate-300')}>{status}</span>
        {!canSeek && <span>· Không tua</span>}
        {languageCode && <span>· {languageCode}</span>}
        {error && <span className="text-red-300">· {error}</span>}
        <span className="flex-1" />
        {canSpeed && [0.75, 1].map((r) => (
          <button key={r} type="button" aria-pressed={rate === r} onClick={() => setRate(r)}
            className={cx('h-[22px] rounded-full px-2 font-mono text-micro font-semibold text-white', rate === r ? 'bg-white/20' : 'hover:bg-white/10')}>
            {String(r).replace('.', ',')}×
          </button>
        ))}
      </div>
    </div>
  );
};
