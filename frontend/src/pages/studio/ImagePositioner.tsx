import React, { useEffect, useId, useRef, useState } from 'react';
import { Button } from '../../components/ui';
import { Move } from 'lucide-react';

// "Căn ảnh": which part of an image a fixed-ratio frame shows, stored as a CSS object-position "x% y%"
// (API-CREATE-CLASS §1 - integers 0..100, null = centre).

export interface ImagePoint { x: number; y: number }

const clamp = (n: number) => Math.max(0, Math.min(100, Math.round(n)));

/** "30% 70%" -> {x: 30, y: 70}; anything else (null, malformed) -> centre. */
export function parsePosition(value?: string | null): ImagePoint {
  const m = /^(\d{1,3})% (\d{1,3})%$/.exec((value || '').trim());
  if (!m) return { x: 50, y: 50 };
  return { x: clamp(Number(m[1])), y: clamp(Number(m[2])) };
}

export const formatPosition = ({ x, y }: ImagePoint) => `${clamp(x)}% ${clamp(y)}%`;

/**
 * Drag the preview (or use the two sliders / arrow keys) to choose the visible part, then "Lưu vị trí".
 * Dragging the picture right shows more of its left side, so x moves opposite to the pointer.
 */
export const ImagePositioner: React.FC<{
  src: string;
  value?: string | null;
  /** Frame of the preview, e.g. "aspect-[3/1] w-full" for a cover or "h-32 w-32" for an avatar. */
  frameClassName: string;
  label: string;
  saving?: boolean;
  onSave: (position: string) => void | Promise<void>;
  onCancel: () => void;
}> = ({ src, value, frameClassName, label, saving = false, onSave, onCancel }) => {
  const [point, setPoint] = useState<ImagePoint>(() => parsePosition(value));
  const drag = useRef<{ startX: number; startY: number; from: ImagePoint; w: number; h: number } | null>(null);
  const xId = useId();
  const yId = useId();

  useEffect(() => setPoint(parsePosition(value)), [value]);

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    drag.current = { startX: e.clientX, startY: e.clientY, from: point, w: rect.width || 1, h: rect.height || 1 };
    e.currentTarget.setPointerCapture?.(e.pointerId);
  };
  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const d = drag.current;
    if (!d) return;
    setPoint({ x: clamp(d.from.x - ((e.clientX - d.startX) / d.w) * 100), y: clamp(d.from.y - ((e.clientY - d.startY) / d.h) * 100) });
  };
  const endDrag = () => { drag.current = null; };
  const onKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    const step = e.shiftKey ? 10 : 2;
    const moves: Record<string, ImagePoint> = {
      ArrowLeft: { x: point.x - step, y: point.y }, ArrowRight: { x: point.x + step, y: point.y },
      ArrowUp: { x: point.x, y: point.y - step }, ArrowDown: { x: point.x, y: point.y + step },
    };
    if (moves[e.key]) { e.preventDefault(); setPoint({ x: clamp(moves[e.key].x), y: clamp(moves[e.key].y) }); }
  };

  return (
    <div className="space-y-3 rounded-2xl border border-blue-100 bg-tint p-3.5" role="group" aria-label={label}>
      <div
        className={`relative cursor-grab touch-none select-none overflow-hidden rounded-[14px] border border-slate-200 bg-white active:cursor-grabbing focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-600 ${frameClassName}`}
        tabIndex={0}
        aria-label={`${label}: kéo ảnh hoặc dùng phím mũi tên`}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        onKeyDown={onKeyDown}
        data-testid="position-preview"
      >
        <img src={src} alt="" draggable={false} className="pointer-events-none h-full w-full object-cover" style={{ objectPosition: formatPosition(point) }} />
        <span className="pointer-events-none absolute left-2 top-2 inline-flex items-center gap-1 rounded-full bg-slate-900/60 px-2 py-0.5 text-micro font-semibold text-white">
          <Move className="h-3 w-3" strokeWidth={2} aria-hidden="true" />Kéo để căn
        </span>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <label htmlFor={xId} className="text-caption font-semibold text-slate-600">
          Ngang <span className="tabular text-slate-500">{point.x}%</span>
          <input id={xId} type="range" min={0} max={100} value={point.x} onChange={(e) => setPoint({ ...point, x: clamp(Number(e.target.value)) })} className="mt-1 block w-full accent-blue-600" />
        </label>
        <label htmlFor={yId} className="text-caption font-semibold text-slate-600">
          Dọc <span className="tabular text-slate-500">{point.y}%</span>
          <input id={yId} type="range" min={0} max={100} value={point.y} onChange={(e) => setPoint({ ...point, y: clamp(Number(e.target.value)) })} className="mt-1 block w-full accent-blue-600" />
        </label>
      </div>
      <div className="flex flex-wrap justify-end gap-2">
        <Button size="sm" variant="ghost" onClick={() => setPoint({ x: 50, y: 50 })}>Về giữa</Button>
        <Button size="sm" variant="secondary" onClick={onCancel}>Hủy</Button>
        <Button size="sm" variant="primary" disabled={saving} onClick={() => onSave(formatPosition(point))}>{saving ? 'Đang lưu...' : 'Lưu vị trí'}</Button>
      </div>
    </div>
  );
};
