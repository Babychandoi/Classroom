/**
 * Tiny LaTeX → MathML converter (no dependency). Supported subset: \frac, ^, _, \sqrt (and \sqrt[n]), \cdot, \times, \div, \pm,
 * \le, \ge, \ne, \approx, \infty, \pi and the Greek letters, \left( … \right), \text{…}, braces, digits, letters and operators.
 * Unknown commands render as text (<mtext>). Every piece of user text is escaped, so the output is safe to inject.
 */

const escapeXml = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');

const GREEK: Record<string, string> = {
  alpha: 'α', beta: 'β', gamma: 'γ', delta: 'δ', epsilon: 'ε', varepsilon: 'ε', zeta: 'ζ', eta: 'η', theta: 'θ', vartheta: 'ϑ', iota: 'ι',
  kappa: 'κ', lambda: 'λ', mu: 'μ', nu: 'ν', xi: 'ξ', omicron: 'ο', pi: 'π', rho: 'ρ', sigma: 'σ', tau: 'τ', upsilon: 'υ', phi: 'φ',
  varphi: 'φ', chi: 'χ', psi: 'ψ', omega: 'ω', Gamma: 'Γ', Delta: 'Δ', Theta: 'Θ', Lambda: 'Λ', Xi: 'Ξ', Pi: 'Π', Sigma: 'Σ',
  Phi: 'Φ', Psi: 'Ψ', Omega: 'Ω',
};
const OPERATORS: Record<string, string> = {
  cdot: '·', times: '×', div: '÷', pm: '±', mp: '∓', le: '≤', leq: '≤', ge: '≥', geq: '≥', ne: '≠', neq: '≠', approx: '≈',
  infty: '∞', to: '→', rightarrow: '→', leftarrow: '←', Rightarrow: '⇒', Leftrightarrow: '⇔', in: '∈', notin: '∉', subset: '⊂',
  cup: '∪', cap: '∩', sum: '∑', prod: '∏', int: '∫', angle: '∠', perp: '⊥', parallel: '∥', circ: '∘', degree: '°', ldots: '…',
  cdots: '⋯', equiv: '≡', sim: '∼', forall: '∀', exists: '∃', partial: '∂', nabla: '∇',
};
const FUNCTIONS = new Set(['sin', 'cos', 'tan', 'cot', 'log', 'ln', 'lim', 'max', 'min', 'exp']);
const SPACES: Record<string, string> = { ',': '0.17em', ':': '0.22em', ';': '0.28em', ' ': '0.25em', quad: '1em', qquad: '2em' };

const TEXT_COMMANDS = new Set(['text', 'mathrm', 'textrm', 'mbox']);

type Tok = { t: 'text'; v: string } | { t: 'cmd'; v: string } | { t: 'open' } | { t: 'close' } | { t: 'sup' } | { t: 'sub' } | { t: 'num'; v: string } | { t: 'char'; v: string };

function tokenize(src: string): Tok[] {
  const out: Tok[] = [];
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    if (c === '\\') {
      let j = i + 1;
      if (j < src.length && /[A-Za-z]/.test(src[j])) {
        while (j < src.length && /[A-Za-z]/.test(src[j])) j++;
        const name = src.slice(i + 1, j);
        if (TEXT_COMMANDS.has(name)) {
          // Raw text keeps its spaces: read up to the matching brace.
          let k = j;
          while (k < src.length && /\s/.test(src[k])) k++;
          if (src[k] === '{') {
            let depth = 1;
            let e = k + 1;
            while (e < src.length && depth > 0) { if (src[e] === '{') depth++; else if (src[e] === '}') depth--; if (depth > 0) e++; }
            out.push({ t: 'text', v: src.slice(k + 1, e) });
            i = Math.min(src.length, e + 1);
            continue;
          }
        }
        out.push({ t: 'cmd', v: name });
        i = j;
      } else {
        out.push({ t: 'cmd', v: src[j] ?? '' });
        i = j + 1;
      }
    } else if (c === '{') { out.push({ t: 'open' }); i++; }
    else if (c === '}') { out.push({ t: 'close' }); i++; }
    else if (c === '^') { out.push({ t: 'sup' }); i++; }
    else if (c === '_') { out.push({ t: 'sub' }); i++; }
    else if (/\s/.test(c)) { i++; }
    else if (/[0-9]/.test(c)) {
      let j = i;
      while (j < src.length && /[0-9]/.test(src[j])) j++;
      if ((src[j] === '.' || src[j] === ',') && /[0-9]/.test(src[j + 1] ?? '')) { j++; while (j < src.length && /[0-9]/.test(src[j])) j++; }
      out.push({ t: 'num', v: src.slice(i, j) });
      i = j;
    } else { out.push({ t: 'char', v: c }); i++; }
  }
  return out;
}

class Parser {
  private pos = 0;
  constructor(private toks: Tok[]) {}

  /** Parses everything, skipping stray closing braces at the top level. */
  parseAll(): string[] {
    const out: string[] = [];
    while (this.pos < this.toks.length) {
      out.push(...this.parseList());
      if (this.pos < this.toks.length) this.pos += 1;
    }
    return out;
  }

  private peek(): Tok | undefined { return this.toks[this.pos]; }
  private next(): Tok | undefined { return this.toks[this.pos++]; }

  /** Parses atoms until a closing brace, `\right`, or the end. */
  parseList(stopAtRight = false): string[] {
    const out: string[] = [];
    while (this.pos < this.toks.length) {
      const tk = this.peek()!;
      if (tk.t === 'close') break;
      if (stopAtRight && tk.t === 'cmd' && tk.v === 'right') break;
      out.push(this.parseScripted());
    }
    return out;
  }

  private parseScripted(): string {
    let base = this.parseAtom();
    let sub: string | null = null;
    let sup: string | null = null;
    for (let k = 0; k < 2; k++) {
      const tk = this.peek();
      if (tk?.t === 'sub' && sub == null) { this.next(); sub = this.parseAtom(); }
      else if (tk?.t === 'sup' && sup == null) { this.next(); sup = this.parseAtom(); }
    }
    if (sub != null && sup != null) base = `<msubsup>${base}${sub}${sup}</msubsup>`;
    else if (sub != null) base = `<msub>${base}${sub}</msub>`;
    else if (sup != null) base = `<msup>${base}${sup}</msup>`;
    return base;
  }

  private group(): string {
    const tk = this.peek();
    if (tk?.t === 'open') {
      this.next();
      const inner = this.parseList();
      if (this.peek()?.t === 'close') this.next();
      return `<mrow>${inner.join('')}</mrow>`;
    }
    return this.parseAtom();
  }

  /** Raw text of a `{…}` group (for \text). */
  private rawGroup(): string {
    if (this.peek()?.t !== 'open') return '';
    this.next();
    let depth = 1;
    const parts: string[] = [];
    while (this.pos < this.toks.length) {
      const tk = this.next()!;
      if (tk.t === 'open') { depth++; parts.push('{'); continue; }
      if (tk.t === 'close') { depth--; if (depth === 0) break; parts.push('}'); continue; }
      if (tk.t === 'cmd') parts.push(tk.v.length === 1 && !/[A-Za-z]/.test(tk.v) ? tk.v : `\\${tk.v}`);
      else if (tk.t === 'sup') parts.push('^');
      else if (tk.t === 'sub') parts.push('_');
      else parts.push(tk.v);
    }
    return parts.join(' ').replace(/\s+/g, ' ').trim();
  }

  private parseAtom(): string {
    const tk = this.next();
    if (!tk) return '<mrow></mrow>';
    switch (tk.t) {
      case 'open': {
        const inner = this.parseList();
        if (this.peek()?.t === 'close') this.next();
        return `<mrow>${inner.join('')}</mrow>`;
      }
      case 'close': return '';
      case 'sup': case 'sub': return '<mrow></mrow>';
      case 'num': return `<mn>${escapeXml(tk.v)}</mn>`;
      case 'text': return `<mtext>${escapeXml(tk.v)}</mtext>`;
      case 'char': {
        if (/[A-Za-z]/.test(tk.v)) return `<mi>${escapeXml(tk.v)}</mi>`;
        if (tk.v === "'") return '<mo>′</mo>';
        return `<mo>${escapeXml(tk.v === '-' ? '−' : tk.v === '*' ? '·' : tk.v)}</mo>`;
      }
      case 'cmd': return this.command(tk.v);
    }
  }

  private command(name: string): string {
    if (name === 'frac' || name === 'dfrac' || name === 'tfrac') {
      const a = this.group();
      const b = this.group();
      return `<mfrac>${a}${b}</mfrac>`;
    }
    if (name === 'sqrt') {
      let index: string | null = null;
      const tk = this.peek();
      if (tk?.t === 'char' && tk.v === '[') {
        this.next();
        const parts: string[] = [];
        while (this.pos < this.toks.length) {
          const t2 = this.peek()!;
          if (t2.t === 'char' && t2.v === ']') { this.next(); break; }
          parts.push(this.parseScripted());
        }
        index = `<mrow>${parts.join('')}</mrow>`;
      }
      const body = this.group();
      return index ? `<mroot>${body}${index}</mroot>` : `<msqrt>${body}</msqrt>`;
    }
    if (TEXT_COMMANDS.has(name)) return `<mtext>${escapeXml(this.rawGroup())}</mtext>`;
    if (name === 'left') {
      const open = this.delimiter();
      const inner = this.parseList(true);
      let close = '';
      if (this.peek()?.t === 'cmd' && (this.peek() as { v: string }).v === 'right') { this.next(); close = this.delimiter(); }
      return `<mrow>${open ? `<mo fence="true">${escapeXml(open)}</mo>` : ''}${inner.join('')}${close ? `<mo fence="true">${escapeXml(close)}</mo>` : ''}</mrow>`;
    }
    if (name === 'right') return '';
    if (GREEK[name]) return `<mi>${GREEK[name]}</mi>`;
    if (OPERATORS[name]) return `<mo>${OPERATORS[name]}</mo>`;
    if (FUNCTIONS.has(name)) return `<mi mathvariant="normal">${name}</mi>`;
    if (SPACES[name]) return `<mspace width="${SPACES[name]}"></mspace>`;
    if (name === '{' || name === '}' || name === '%' || name === '$' || name === '&' || name === '#' || name === '_') return `<mo>${escapeXml(name)}</mo>`;
    if (name === '\\') return '<mspace linebreak="newline"></mspace>';
    return `<mtext>${escapeXml(`\\${name}`)}</mtext>`;
  }

  private delimiter(): string {
    const tk = this.next();
    if (!tk) return '';
    if (tk.t === 'char') return tk.v === '.' ? '' : tk.v;
    if (tk.t === 'cmd') return tk.v === '{' ? '{' : tk.v === '}' ? '}' : tk.v === 'langle' ? '⟨' : tk.v === 'rangle' ? '⟩' : tk.v === '|' ? '‖' : '';
    return '';
  }

}

/** Converts a LaTeX expression to a MathML string (`<math>` element). */
export function latexToMathml(latex: string, display = false): string {
  const src = String(latex ?? '');
  const parts = new Parser(tokenize(src)).parseAll();
  return `<math xmlns="http://www.w3.org/1998/Math/MathML" display="${display ? 'block' : 'inline'}"><semantics><mrow>${parts.join('')}</mrow><annotation encoding="application/x-tex">${escapeXml(src)}</annotation></semantics></math>`;
}
