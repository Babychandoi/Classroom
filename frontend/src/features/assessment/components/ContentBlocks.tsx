import React from 'react';
import { SafeMarkdown } from '../../../components/SafeMarkdown';
import { latexToMathml } from '../math/latexToMathml';
import type { ContentBlock } from '../types';
import { cx } from '../format';

/** LaTeX rendered as MathML (the converter escapes all user text, so injecting its output is safe). */
export const MathText: React.FC<{ latex: string; display?: boolean; className?: string }> = ({ latex, display, className }) => (
  <span
    className={cx(display ? 'my-2 block overflow-x-auto text-center' : 'inline-block align-middle', className)}
    // eslint-disable-next-line react/no-danger
    dangerouslySetInnerHTML={{ __html: latexToMathml(latex, display) }}
  />
);

/**
 * Text with inline math: `$…$` segments become MathML, the rest stays plain text (prompts and options are short lines).
 * `\$` keeps a literal dollar sign.
 */
export const RichLine: React.FC<{ text: string; className?: string }> = ({ text, className }) => {
  const parts: React.ReactNode[] = [];
  const re = /(?<!\\)\$([^$]+?)(?<!\\)\$/g;
  let last = 0;
  let m: RegExpExecArray | null;
  let k = 0;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) parts.push(text.slice(last, m.index).replace(/\\\$/g, '$'));
    parts.push(<MathText key={`m${k++}`} latex={m[1]} />);
    last = m.index + m[0].length;
  }
  if (last < text.length) parts.push(text.slice(last).replace(/\\\$/g, '$'));
  return <span className={cx('whitespace-pre-wrap', className)}>{parts}</span>;
};

export interface ContentBlocksProps {
  blocks: ContentBlock[] | null | undefined;
  /** assetKey → signed image URL (LearnerDocument.images or the teacher's media URLs). */
  images?: Record<string, { url: string; alt?: string | null }>;
  compact?: boolean;
  renderAudio?: (assetKey: string) => React.ReactNode;
}

/** Renders stimulus / prompt blocks: TEXT (safe Markdown), MATH, IMAGE (signed URL), TABLE. AUDIO blocks are rendered by AudioBar. */
export const ContentBlocks: React.FC<ContentBlocksProps> = ({ blocks, images, compact, renderAudio }) => {
  if (!blocks || blocks.length === 0) return null;
  return (
    <div className="flex flex-col gap-3">
      {blocks.map((b, i) => {
        switch (b.type) {
          case 'TEXT':
            return b.text.trim() ? (
              <div key={i} className={cx('rounded-xl bg-slate-50', compact ? 'p-3.5' : 'p-4')}>
                <SafeMarkdown source={b.text} size="body" className="text-slate-900" />
              </div>
            ) : null;
          case 'MATH':
            return <MathText key={i} latex={b.latex} display={b.display ?? true} />;
          case 'IMAGE': {
            const img = images?.[b.assetKey];
            return img ? (
              <img key={i} src={img.url} alt={b.alt || img.alt || 'Hình minh họa'} className="max-h-[420px] max-w-full rounded-xl border border-slate-200 object-contain" loading="lazy" />
            ) : (
              <div key={i} className="rounded-xl border border-dashed border-slate-300 bg-slate-50 p-4 text-center text-meta text-slate-500">Ảnh chưa sẵn sàng</div>
            );
          }
          case 'TABLE':
            return (
              <div key={i} className="overflow-x-auto">
                <table className="min-w-full border-collapse overflow-hidden rounded-xl border border-slate-200 text-meta">
                  <tbody>
                    {b.rows.map((row, r) => (
                      <tr key={r} className={r === 0 && b.header ? 'bg-slate-100 font-semibold' : 'bg-white'}>
                        {row.map((cell, c) => (r === 0 && b.header
                          ? <th key={c} scope="col" className="border border-slate-200 px-3 py-2 text-left">{cell}</th>
                          : <td key={c} className="border border-slate-200 px-3 py-2">{cell}</td>))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            );
          case 'AUDIO':
            return <div key={i}>{renderAudio?.(b.assetKey) ?? <p className="text-meta text-slate-500">Tệp nghe</p>}</div>;
          default:
            return null;
        }
      })}
    </div>
  );
};
