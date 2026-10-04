import React, { useEffect, useRef, useState } from 'react';
import { CLASS_IMAGE_ACCEPT, classImageProblem } from './classMedia';

// The "image slot" of the Tạo lớp học design: an empty dashed frame you drop an image on (or click to pick), then on
// hover "Đổi ảnh" (pick another) and "Căn ảnh" (drag to reposition). Repositioning produces a CSS object-position
// "x% y%" for object-fit: cover, which is what the server stores as coverPosition / avatarPosition.

export interface PickedImage {
  file: File;
  /** Object URL of `file` for the preview (owned and revoked by the page). */
  url: string;
}

export const CENTER = '50% 50%';

const parsePosition = (position: string): [number, number] => {
  const match = /^(\d{1,3})% (\d{1,3})%$/.exec(position);
  return match ? [Number(match[1]), Number(match[2])] : [50, 50];
};
const clamp = (n: number) => Math.min(100, Math.max(0, Math.round(n)));

const ImageIcon: React.FC<{ size?: number }> = ({ size = 28 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="3" y="3" width="18" height="18" rx="2" />
    <circle cx="8.5" cy="8.5" r="1.5" />
    <path d="m21 15-5-5L5 21" />
  </svg>
);

export const ImageSlot: React.FC<{
  /** Accessible name, e.g. "Ảnh bìa". */
  label: string;
  image: PickedImage | null;
  position: string;
  onPick: (file: File) => void;
  onPositionChange: (position: string) => void;
  /** Caption of the empty state; blank shows the icon only (small avatar slots). */
  placeholder?: string;
  className?: string;
  testId?: string;
}> = ({ label, image, position, onPick, onPositionChange, placeholder = '', className = '', testId }) => {
  const inputRef = useRef<HTMLInputElement>(null);
  const frameRef = useRef<HTMLDivElement>(null);
  const imgRef = useRef<HTMLImageElement>(null);
  const drag = useRef<{ x: number; y: number; pos: [number, number] } | null>(null);
  const [over, setOver] = useState(false);
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Leaving "Căn ảnh": a click outside the slot or Escape.
  useEffect(() => {
    if (!editing) return;
    const onDown = (event: MouseEvent) => {
      if (frameRef.current && !frameRef.current.parentElement?.contains(event.target as Node)) setEditing(false);
    };
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') setEditing(false); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [editing]);

  useEffect(() => { if (!image) setEditing(false); }, [image]);

  const accept = (file?: File | null) => {
    if (!file) return;
    const problem = classImageProblem(file);
    setError(problem);
    if (!problem) onPick(file);
  };

  // How far (px) the covering image overflows the frame on each axis - object-position % moves within that range.
  const overflow = (): [number, number] => {
    const frame = frameRef.current;
    const img = imgRef.current;
    if (!frame || !img || !img.naturalWidth || !img.naturalHeight) return [0, 0];
    const fw = frame.clientWidth;
    const fh = frame.clientHeight;
    const scale = Math.max(fw / img.naturalWidth, fh / img.naturalHeight);
    return [img.naturalWidth * scale - fw, img.naturalHeight * scale - fh];
  };

  const onPointerDown = (event: React.PointerEvent) => {
    if (!editing) return;
    event.preventDefault();
    (event.target as Element).setPointerCapture?.(event.pointerId);
    drag.current = { x: event.clientX, y: event.clientY, pos: parsePosition(position) };
  };
  const onPointerMove = (event: React.PointerEvent) => {
    const start = drag.current;
    if (!editing || !start) return;
    const [ox, oy] = overflow();
    // Dragging the picture right reveals more of its left side, i.e. a smaller x%.
    const x = ox > 0 ? clamp(start.pos[0] - ((event.clientX - start.x) / ox) * 100) : start.pos[0];
    const y = oy > 0 ? clamp(start.pos[1] - ((event.clientY - start.y) / oy) * 100) : start.pos[1];
    onPositionChange(`${x}% ${y}%`);
  };
  const endDrag = () => { drag.current = null; };

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (!editing) return;
    const [x, y] = parsePosition(position);
    const step = event.shiftKey ? 10 : 2;
    const moves: Record<string, [number, number]> = {
      ArrowLeft: [x + step, y], ArrowRight: [x - step, y], ArrowUp: [x, y + step], ArrowDown: [x, y - step],
    };
    const next = moves[event.key];
    if (!next) return;
    event.preventDefault();
    onPositionChange(`${clamp(next[0])}% ${clamp(next[1])}%`);
  };

  const dropProps = {
    onDragOver: (event: React.DragEvent) => { event.preventDefault(); setOver(true); },
    onDragLeave: () => setOver(false),
    onDrop: (event: React.DragEvent) => {
      event.preventDefault();
      setOver(false);
      accept(event.dataTransfer.files?.[0]);
    },
  };

  return (
    <div className={`group relative h-full w-full text-[13px] leading-[1.3] text-slate-900 ${className}`} data-testid={testId}>
      <div
        ref={frameRef}
        {...dropProps}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        onKeyDown={onKeyDown}
        tabIndex={editing ? 0 : -1}
        aria-label={editing ? `${label}: kéo hoặc dùng phím mũi tên để căn ảnh` : undefined}
        className={`absolute inset-0 overflow-hidden bg-[rgba(127,127,127,0.08)] focus:outline-none ${
          over ? 'outline outline-2 -outline-offset-2 outline-slate-900' : ''
        } ${editing ? 'cursor-grab touch-none shadow-[inset_0_0_0_2px_#0F172A] active:cursor-grabbing' : ''}`}
      >
        {image ? (
          <img
            ref={imgRef}
            src={image.url}
            alt={label}
            draggable={false}
            style={{ objectPosition: position }}
            className="h-full w-full select-none object-cover"
          />
        ) : (
          <button
            type="button"
            onClick={() => inputRef.current?.click()}
            aria-label={`${label}: kéo ảnh vào đây hoặc bấm để chọn`}
            className="absolute inset-0 flex cursor-pointer flex-col items-center justify-center gap-1.5 p-3 text-center"
          >
            <span className="opacity-45"><ImageIcon /></span>
            {placeholder.trim() && (
              <>
                <span className="max-w-[90%] font-medium tracking-[0.01em] opacity-75">{placeholder}</span>
                <span className="text-[11px] opacity-75 group-hover:opacity-100">hoặc <u className="underline-offset-2">chọn tệp</u></span>
              </>
            )}
          </button>
        )}
        {!image && <span aria-hidden="true" className="pointer-events-none absolute inset-0 border-[1.5px] border-dashed border-current opacity-35" />}
      </div>

      {image && (
        <div
          className={`absolute right-2 top-2 z-[2] flex gap-1.5 whitespace-nowrap transition-opacity duration-[120ms] ${
            editing ? 'opacity-100' : 'opacity-0 focus-within:opacity-100 group-hover:opacity-100'
          }`}
        >
          {[
            { key: 'replace', text: 'Đổi ảnh', title: 'Đổi ảnh khác', onClick: () => inputRef.current?.click() },
            {
              key: 'edit',
              text: editing ? 'Xong' : 'Căn ảnh',
              title: 'Kéo để căn vị trí ảnh',
              onClick: () => {
                setEditing(!editing);
                if (!editing) requestAnimationFrame(() => frameRef.current?.focus());
              },
            },
          ].map((b) => (
            <button
              key={b.key}
              type="button"
              title={b.title}
              aria-label={`${b.text} - ${label}`}
              aria-pressed={b.key === 'edit' ? editing : undefined}
              onClick={b.onClick}
              className="cursor-pointer rounded-md bg-black/65 px-2.5 py-[5px] text-[11px] leading-none text-white backdrop-blur-md hover:bg-black/80"
            >
              {b.text}
            </button>
          ))}
        </div>
      )}

      {error && (
        <p role="alert" className="pointer-events-none absolute bottom-2 left-2 right-2 z-[2] rounded-[5px] bg-white/85 px-1.5 py-1 text-[11px] text-[#b3261e]">
          {error}
        </p>
      )}

      <input
        ref={inputRef}
        type="file"
        accept={CLASS_IMAGE_ACCEPT}
        hidden
        data-testid={testId ? `${testId}-input` : undefined}
        onChange={(event) => {
          accept(event.target.files?.[0]);
          event.target.value = '';
        }}
      />
    </div>
  );
};
