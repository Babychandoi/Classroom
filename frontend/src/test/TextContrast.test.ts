import { describe, expect, it } from 'vitest';

// WCAG 2 AA (1.4.3): body/meta text needs 4.5:1. On the light surfaces of the Connecty design (#FFFFFF / #F8FAFC / #F1F5F9)
// slate-400 (#94A3B8) is only ~2.5:1 and red-600 on red-50 is 4.41:1 - axe-core flags both as "serious" (e2e/a11y.js,
// e2e/e2e5.js). Text uses slate-500 or darker (4.76:1 on white); slate-400 stays fine for icons and borders.
// The one allowed exception is text on the dark video placeholder (bg-black), where slate-400 is the readable shade.

// Every component source as raw text (Vite glob import; paths are relative to this test, e.g. '../pages/LoginPage.tsx').
const SOURCES = import.meta.glob<string>(['../**/*.tsx', '!../test/**'], { query: '?raw', import: 'default', eager: true });
const ALLOWED = new Set(['pages/classroom/LessonViewPage.tsx:Vui lòng quay lại sau ít phút.']);

/** Lines where a lower-case JSX element (p, span, h3, ... - not an icon component) carries the given text colour class. */
function offenders(pattern: RegExp, { jsxOnly = true } = {}): string[] {
  const found: string[] = [];
  for (const [file, source] of Object.entries(SOURCES)) {
    const rel = file.replace(/^\.\.\//, '');
    source.split('\n').forEach((line: string, i: number) => {
      if ((jsxOnly && !/<[a-z][a-z0-9]*\b[^>]*className=/.test(line)) || !pattern.test(line)) return;
      if (/aria-hidden="true"/.test(line)) return; // decorative (icon holder), not text
      const text = line.replace(/<[^>]*>/g, '').trim();
      if (ALLOWED.has(`${rel}:${text}`)) return;
      found.push(`${rel}:${i + 1}: ${line.trim().slice(0, 140)}`);
    });
  }
  return found;
}

describe('text colour contrast (WCAG AA)', () => {
  it('scans the component sources', () => {
    expect(Object.keys(SOURCES).length).toBeGreaterThan(40);
    expect(Object.keys(SOURCES)).toContain('../pages/classroom/LessonViewPage.tsx');
  });

  it('no text element uses slate-400 (2.5:1 on light surfaces)', () => {
    expect(offenders(/<[a-z][a-z0-9]*\b[^>]*(?<![:\w-])text-slate-400(?![\w-])/)).toEqual([]);
  });

  it('no text uses the same light greys as arbitrary hex values (#94A3B8 / #B4BCC8 / #CBD5E1)', () => {
    // e.g. the ghost text of the /classes/new live preview; `placeholder:` variants are not text content.
    // Also class strings kept in variables (not only on the JSX line itself).
    expect(offenders(/(?<![:\w-])text-\[#(94A3B8|B4BCC8|CBD5E1)\]/i, { jsxOnly: false })).toEqual([]);
  });

  it('no text element uses green-600 (3.3:1 on white) - success text is green-700/800', () => {
    expect(offenders(/<[a-z][a-z0-9]*\b[^>]*(?<![:\w-])text-green-600(?![\w-])/)).toEqual([]);
  });

  it('the leaderboard points unit stays readable on the highlighted own row (slate-600, not slate-500 on the tint)', () => {
    const source = SOURCES['../pages/classroom/LeaderboardTab.tsx'];
    expect(source).toContain('<span className="ml-1 text-caption text-slate-600">{unit}</span>');
  });

  it('error text on a red-50 surface is red-700, not red-600 (4.41:1)', () => {
    expect(offenders(/^(?=.*(?<![:\w-])bg-red-50(?![\w-])).*(?<![:\w-])text-red-600(?![\w-])/)).toEqual([]);
  });
});
