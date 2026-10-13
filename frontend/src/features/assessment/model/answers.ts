import type { ItemResponse, ItemTypeCode, LearnerItem, ChoiceOption, IdText, RecordingPolicy } from '../types';

/** Loose view of every learner interaction shape (the contract's union is awkward to narrow). */
export interface AnyInteraction {
  options?: ChoiceOption[];
  statements?: IdText[];
  left?: IdText[];
  right?: IdText[];
  items?: IdText[];
  blanks?: { id: string }[];
  bank?: string[];
  distractors?: string[];
  dropdowns?: { id: string; options: IdText[] }[];
  maxLength?: number | null;
  unit?: string | null;
  minWords?: number | null;
  maxWords?: number | null;
  recording?: RecordingPolicy;
}

/** Loose view of every response shape. */
export interface AnyResponse {
  optionId?: string;
  optionIds?: string[];
  value?: boolean;
  values?: Record<string, boolean>;
  pairs?: Record<string, string>;
  order?: string[];
  blanks?: Record<string, string>;
  choices?: Record<string, string>;
  text?: string;
  recordingAssetId?: string;
  durationMs?: number;
}

export const ia = (item: Pick<LearnerItem, 'interaction'>): AnyInteraction => item.interaction as AnyInteraction;
export const ar = (r: ItemResponse | null | undefined): AnyResponse => (r ?? {}) as AnyResponse;

const nonEmpty = (o: Record<string, unknown> | undefined) => !!o && Object.values(o).some((v) => v != null && v !== '');

/** Whether a response counts as an answer (blank responses are not saved as answers). */
export function isAnswered(type: ItemTypeCode, response: ItemResponse | null | undefined): boolean {
  if (response == null) return false;
  const r = ar(response);
  switch (type) {
    case 'Q01': return !!r.optionId;
    case 'Q02': return (r.optionIds ?? []).length > 0;
    case 'Q03': return typeof r.value === 'boolean';
    case 'Q04': return nonEmpty(r.values);
    case 'Q05': return nonEmpty(r.pairs);
    case 'Q06': return (r.order ?? []).length > 0;
    case 'Q07': case 'Q08': return nonEmpty(r.blanks) && Object.values(r.blanks ?? {}).some((v) => String(v).trim() !== '');
    case 'Q09': return nonEmpty(r.choices);
    case 'Q10': case 'Q11': case 'Q12': return String(r.text ?? '').trim() !== '';
    case 'Q22': return !!r.recordingAssetId;
    default: return Object.values(response).some(value => Array.isArray(value)?value.length>0:typeof value==='object' && value!==null?Object.values(value).some(v=>v!==null && v!==undefined && v!==''):value!==undefined && value!==null && value!=='');
  }
}

export function answeredCount(items: Pick<LearnerItem, 'id' | 'type'>[], answers: Record<string, ItemResponse | null | undefined>): number {
  return items.filter((it) => isAnswered(it.type, answers[it.id])).length;
}

/** Normalizes an edited response: an empty one becomes null (clears the saved answer). */
export function normalizeResponse(type: ItemTypeCode, response: ItemResponse | null): ItemResponse | null {
  return response != null && isAnswered(type, response) ? response : null;
}
