import React from 'react';

// A deliberately tiny Markdown renderer for blog posts and event descriptions. It never injects HTML: every piece of
// text becomes a React text node, so whatever an author types (<script>, <img onerror>, javascript: links) is shown
// as plain text. Supported: #/##/### headings, paragraphs, "- " / "* " lists, "1. " lists, "> " quotes, **bold**,
// and [links](https://...) - only http(s) targets become links; anything else stays literal text.

type Block =
  | { kind: 'h'; level: 1 | 2 | 3; text: string }
  | { kind: 'p'; text: string }
  | { kind: 'ul' | 'ol'; items: string[] }
  | { kind: 'quote'; text: string };

const HEADING = /^(#{1,3})\s+(.*)$/;
const BULLET = /^\s*[-*]\s+(.*)$/;
const ORDERED = /^\s*\d+[.)]\s+(.*)$/;
const QUOTE = /^>\s?(.*)$/;

export function parseMarkdownBlocks(source: string): Block[] {
  const lines = source.replace(/\r\n?/g, '\n').split('\n');
  const blocks: Block[] = [];
  let paragraph: string[] = [];
  let list: { kind: 'ul' | 'ol'; items: string[] } | null = null;
  let quote: string[] = [];

  const flush = () => {
    if (paragraph.length) blocks.push({ kind: 'p', text: paragraph.join(' ') });
    if (list) blocks.push(list);
    if (quote.length) blocks.push({ kind: 'quote', text: quote.join(' ') });
    paragraph = [];
    list = null;
    quote = [];
  };

  for (const raw of lines) {
    const line = raw.trimEnd();
    if (!line.trim()) {
      flush();
      continue;
    }
    const heading = HEADING.exec(line.trim());
    if (heading) {
      flush();
      blocks.push({ kind: 'h', level: heading[1].length as 1 | 2 | 3, text: heading[2].trim() });
      continue;
    }
    const bullet = BULLET.exec(line);
    const ordered = bullet ? null : ORDERED.exec(line);
    if (bullet || ordered) {
      const kind = bullet ? 'ul' : 'ol';
      if (!list || list.kind !== kind) {
        flush();
        list = { kind, items: [] };
      }
      list.items.push((bullet ?? ordered)![1].trim());
      continue;
    }
    const q = QUOTE.exec(line.trim());
    if (q) {
      if (!quote.length) flush();
      quote.push(q[1].trim());
      continue;
    }
    if (list || quote.length) flush();
    paragraph.push(line.trim());
  }
  flush();
  return blocks;
}

const INLINE = /\*\*(.+?)\*\*|\*([^*\s](?:[^*]*[^*\s])?)\*|\[([^\]]+)\]\(([^)\s]+)\)/g;
const SAFE_URL = /^https?:\/\//i;

/** Inline pass: **bold**, *italic* and http(s) links; everything else is literal text. */
export function renderInline(text: string, keyPrefix = 'i'): React.ReactNode[] {
  const out: React.ReactNode[] = [];
  let last = 0;
  let n = 0;
  INLINE.lastIndex = 0;
  for (let match = INLINE.exec(text); match; match = INLINE.exec(text)) {
    if (match.index > last) out.push(text.slice(last, match.index));
    const key = `${keyPrefix}-${n++}`;
    if (match[1] !== undefined) {
      out.push(<strong key={key} className="font-semibold text-slate-900">{match[1]}</strong>);
    } else if (match[2] !== undefined) {
      out.push(<em key={key}>{match[2]}</em>);
    } else if (SAFE_URL.test(match[4])) {
      out.push(
        <a key={key} href={match[4]} target="_blank" rel="noopener noreferrer nofollow" className="font-medium text-blue-600 underline-offset-2 hover:text-blue-700 hover:underline">
          {match[3]}
        </a>,
      );
    } else {
      out.push(match[0]);
    }
    last = match.index + match[0].length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

export const SafeMarkdown: React.FC<{ source: string; className?: string; size?: 'reading' | 'body' }> = ({ source, className, size = 'reading' }) => {
  const blocks = parseMarkdownBlocks(source);
  const text = size === 'reading' ? 'text-reading' : 'text-body';
  return (
    <div className={`break-words text-slate-700 ${className ?? ''}`}>
      {blocks.map((block, i) => {
        const first = i === 0;
        const key = `b${i}`;
        switch (block.kind) {
          case 'h':
            if (block.level === 1)
              return <h2 key={key} className={`${first ? '' : 'mt-9'} text-h2 font-semibold text-slate-900`}>{renderInline(block.text, key)}</h2>;
            if (block.level === 2)
              return <h3 key={key} className={`${first ? '' : 'mt-8'} text-h2-sm font-semibold text-slate-900`}>{renderInline(block.text, key)}</h3>;
            return <h4 key={key} className={`${first ? '' : 'mt-6'} text-h3 font-semibold text-slate-900`}>{renderInline(block.text, key)}</h4>;
          case 'ul':
          case 'ol': {
            const List = block.kind === 'ul' ? 'ul' : 'ol';
            return (
              <List key={key} className={`${first ? '' : 'mt-4'} space-y-2.5 pl-6 ${text} ${block.kind === 'ul' ? 'list-disc' : 'list-decimal'} marker:text-slate-400`}>
                {block.items.map((item, j) => <li key={j}>{renderInline(item, `${key}-${j}`)}</li>)}
              </List>
            );
          }
          case 'quote':
            return (
              <blockquote key={key} className={`${first ? '' : 'mt-6'} rounded-2xl border border-slate-200 bg-white px-6 py-5 text-body font-medium text-slate-900`}>
                {renderInline(block.text, key)}
              </blockquote>
            );
          default:
            return <p key={key} className={`${first ? '' : 'mt-4'} ${text}`}>{renderInline(block.text, key)}</p>;
        }
      })}
    </div>
  );
};
