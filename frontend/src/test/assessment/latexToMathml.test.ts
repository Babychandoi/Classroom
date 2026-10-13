import { describe, it, expect } from 'vitest';
import { latexToMathml } from '../../features/assessment/math/latexToMathml';

describe('latexToMathml', () => {
  it('\\frac{1}{2} produces <mfrac>', () => {
    const m = latexToMathml('\\frac{1}{2}');
    expect(m).toContain('<mfrac><mrow><mn>1</mn></mrow><mrow><mn>2</mn></mrow></mfrac>');
    expect(m.startsWith('<math')).toBe(true);
  });

  it('handles scripts, roots, greek and operators', () => {
    expect(latexToMathml('x^2')).toContain('<msup><mi>x</mi><mn>2</mn></msup>');
    expect(latexToMathml('a_i^2')).toContain('<msubsup><mi>a</mi><mi>i</mi><mn>2</mn></msubsup>');
    expect(latexToMathml('\\sqrt{x}')).toContain('<msqrt>');
    expect(latexToMathml('\\sqrt[3]{8}')).toContain('<mroot>');
    expect(latexToMathml('\\pi r^2')).toContain('<mi>π</mi>');
    expect(latexToMathml('a \\le b \\cdot c')).toContain('<mo>≤</mo>');
    expect(latexToMathml('\\left( x \\right)')).toContain('<mo fence="true">(</mo>');
    expect(latexToMathml('\\text{cm}')).toContain('<mtext>cm</mtext>');
    expect(latexToMathml('12,5')).toContain('<mn>12,5</mn>');
  });

  it('renders unknown commands as text and escapes everything', () => {
    expect(latexToMathml('\\unknowncmd')).toContain('<mtext>\\unknowncmd</mtext>');
    const evil = latexToMathml('\\text{<script>alert(1)</script>} < x');
    expect(evil).not.toContain('<script>');
    expect(evil).toContain('&lt;');
  });

  it('survives unbalanced braces', () => {
    expect(() => latexToMathml('}{\\frac{1}')).not.toThrow();
  });
});
