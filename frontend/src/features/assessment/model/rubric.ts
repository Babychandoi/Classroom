import type { Rubric } from '../types';
import { round2 } from '../format';

/**
 * Rubric score, the same formula as the backend RubricScorer (plan §2.1):
 * Σ_c (weight_c / 100) × (level_c / (L − 1)) × points, rounded to 2 decimals. Missing criteria count 0.
 */
export function rubricScore(rubric: Pick<Rubric, 'levels' | 'criteria'>, levels: Record<string, number | null | undefined>, maxPoints: number): number {
  const top = rubric.levels.length - 1;
  if (top < 0) return 0;
  let total = 0;
  for (const c of rubric.criteria) {
    const lv = levels[c.id];
    if (lv == null || lv < 0) continue;
    total += (c.weight / 100) * (top===0?1:Math.min(lv, top) / top) * maxPoints;
  }
  return round2(total);
}

export function rubricComplete(rubric: Pick<Rubric, 'criteria'>, levels: Record<string, number | null | undefined>): boolean {
  return rubric.criteria.every((c) => levels[c.id] != null);
}

/** The three system rubrics (stable ids; the server sends the same rows). Used only as a fallback when the API is unavailable. */
export const SYSTEM_RUBRICS: Rubric[] = [
  { id: 'rubric-speaking-basic', title: 'Speaking cơ bản (lớp 6–9)', levels: ['Chưa đạt', 'Đạt một phần', 'Đạt', 'Tốt'], system: true,
    criteria: [{ id: 'task', title: 'Đáp ứng yêu cầu', weight: 30 }, { id: 'pron', title: 'Phát âm', weight: 25 }, { id: 'fluency', title: 'Trôi chảy', weight: 20 }, { id: 'lexgram', title: 'Từ vựng & ngữ pháp', weight: 25 }] },
  { id: 'rubric-writing-email', title: 'Viết email ngắn', levels: ['Chưa đạt', 'Đạt một phần', 'Đạt', 'Tốt'], system: true,
    criteria: [{ id: 'content', title: 'Nội dung & đủ ý', weight: 40 }, { id: 'layout', title: 'Bố cục email', weight: 20 }, { id: 'vocab', title: 'Từ vựng', weight: 20 }, { id: 'grammar', title: 'Ngữ pháp & chính tả', weight: 20 }] },
  { id: 'rubric-project-group', title: 'Dự án nhóm', levels: ['Chưa đạt', 'Đạt'], system: true,
    criteria: [{ id: 'product', title: 'Sản phẩm', weight: 50 }, { id: 'present', title: 'Trình bày', weight: 30 }, { id: 'collab', title: 'Hợp tác', weight: 20 }] },
];
