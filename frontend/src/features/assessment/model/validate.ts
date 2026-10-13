import type { AssessmentDocument, AuthoringAssessment, ContentBlock, IssueCode, ItemNode, ValidationIssue } from '../types';
import { isItemType, MANUAL_ONLY_TYPES } from '../catalog';
import { countBlanks, hasAnswerKey } from './doc';

export interface ValidateOptions {
  /** Server-side status of each referenced media asset (AuthoringAssessment.assets, keyed by assetKey). */
  assetStatus?: AuthoringAssessment['assets'];
  /** The subject requires a language (foreign-language subjects). */
  languageRequired?: boolean;
}

/** Vietnamese messages, prototype wording (identical to the backend DocumentValidator). */
export const ISSUE_MESSAGES: Record<IssueCode, string> = {
  NO_ITEMS: 'Đề chưa có câu hỏi nào',
  SECTION_EMPTY: 'Phần chưa có câu hỏi',
  GROUP_EMPTY: 'Nhóm chưa có câu hỏi con',
  LISTENING_AUDIO_MISSING: 'Nhóm bài nghe chưa có tệp nghe',
  TRANSCRIPT_MISSING: 'Nhóm bài nghe chưa có transcript',
  PROMPT_EMPTY: 'Chưa có đề bài',
  POINTS_INVALID: 'Điểm phải lớn hơn 0, tối đa 100 và tối đa 2 chữ số thập phân',
  ANSWER_KEY_MISSING: 'Chấm tự động nhưng chưa có đáp án',
  OPTION_EMPTY: 'Có phương án / ý để trống',
  BLANK_COUNT_MISMATCH: 'Số chỗ trống ___ trong đề không khớp với đáp án',
  AUTO_MODE_UNSUPPORTED: 'Dạng câu này không chấm tự động được — chọn Thủ công',
  RUBRIC_MISSING: 'Chấm thủ công chưa gắn rubric',
  ASSET_MISSING: 'Tệp đính kèm chưa tải lên xong hoặc không còn tồn tại',
  TYPE_UNAVAILABLE: 'Dạng câu chưa hỗ trợ',
  NUMERIC_INVALID: 'Đáp án số không hợp lệ (giá trị, sai số hoặc khoảng)',
  LANGUAGE_REQUIRED: 'Môn ngoại ngữ cần chọn ngôn ngữ',
};

const WARNINGS: IssueCode[] = ['TRANSCRIPT_MISSING', 'RUBRIC_MISSING'];

export function pointsValid(p: unknown): boolean {
  const n = typeof p === 'number' ? p : Number(p);
  return Number.isFinite(n) && n > 0 && n <= 100 && Math.abs(Math.round(n * 100) - n * 100) < 1e-6;
}

/** Client-side twin of the backend `DocumentValidator.issues` (same codes; messages may be refined by the server). */
export function validateDocument(doc: AssessmentDocument, options: ValidateOptions = {}): ValidationIssue[] {
  const out: ValidationIssue[] = [];
  const push = (nodeId: string, code: IssueCode, message?: string) =>
    out.push({ nodeId, code, level: WARNINGS.includes(code) ? 'WARNING' : 'ERROR', message: message ?? ISSUE_MESSAGES[code] });

  const assetOk = (key: string | null | undefined): boolean => {
    if (!key) return false;
    if (!doc.assets[key]) return false;
    const st = options.assetStatus?.[key];
    return !st || st.status === 'UPLOADED';
  };
  const checkBlocks = (nodeId: string, blocks: ContentBlock[] | undefined) => {
    for (const b of blocks ?? []) {
      if ((b.type === 'IMAGE' || b.type === 'AUDIO') && !assetOk(b.assetKey)) { push(nodeId, 'ASSET_MISSING'); return; }
    }
  };

  if(doc.audioAssetKey && !assetOk(doc.audioAssetKey))push('root','ASSET_MISSING');
  if (options.languageRequired && !doc.languageCode) push('root', 'LANGUAGE_REQUIRED');

  let items = 0;
  const checkItem = (it: ItemNode) => {
    items += 1;
    const type = (it as { type: string }).type;
    if (!isItemType(type)) { push(it.id, 'TYPE_UNAVAILABLE'); return; }
    if (!String(it.prompt ?? '').replace(/_{3}/g, '').trim() && !(it.promptBlocks ?? []).length) push(it.id, 'PROMPT_EMPTY');
    if (!pointsValid(it.points)) push(it.id, 'POINTS_INVALID');
    checkBlocks(it.id, it.promptBlocks);
    const mode = it.grading?.mode ?? 'AUTO';
    if (MANUAL_ONLY_TYPES.includes(it.type)) {
      if (mode === 'AUTO') push(it.id, 'AUTO_MODE_UNSUPPORTED');
      if ((mode === 'MANUAL' || mode === 'PASS_FAIL') && !it.grading.rubricId) push(it.id, 'RUBRIC_MISSING');
      return;
    }
    // Structure of the interaction.
    switch (it.type) {
      case 'Q01': case 'Q02':
        if (it.interaction.options.length < 2 || it.interaction.options.some((o) => !o.text.trim() && !o.assetKey && !o.audioAssetKey)) push(it.id, 'OPTION_EMPTY');
        it.interaction.options.forEach((o) => { if (o.assetKey && !assetOk(o.assetKey) || o.audioAssetKey && !assetOk(o.audioAssetKey)) push(it.id, 'ASSET_MISSING'); });
        break;
      case 'Q04': if (!it.interaction.statements.length || it.interaction.statements.some((s) => !s.text.trim())) push(it.id, 'OPTION_EMPTY'); break;
      case 'Q05': if (it.interaction.left.length < 2 || [...it.interaction.left, ...it.interaction.right].some((s) => !s.text.trim())) push(it.id, 'OPTION_EMPTY'); break;
      case 'Q06': if (it.interaction.items.length < 2 || it.interaction.items.some((s) => !s.text.trim())) push(it.id, 'OPTION_EMPTY'); break;
      case 'Q07': case 'Q08':
        if (countBlanks(it.prompt) !== it.interaction.blanks.length || it.interaction.blanks.length === 0) push(it.id, 'BLANK_COUNT_MISMATCH');
        break;
      case 'Q09':
        if (countBlanks(it.prompt) !== it.interaction.dropdowns.length || it.interaction.dropdowns.length === 0) push(it.id, 'BLANK_COUNT_MISMATCH');
        if (it.interaction.dropdowns.some((d) => d.options.length < 2 || d.options.some((o) => !o.text.trim()))) push(it.id, 'OPTION_EMPTY');
        break;
      case 'Q11': {
        const k = it.answerKey;
        const bad = (k.value != null && !Number.isFinite(k.value)) || !(Number.isFinite(k.tolerance) && k.tolerance >= 0)
          || (k.range != null && !(Number.isFinite(k.range.min) && Number.isFinite(k.range.max) && k.range.min <= k.range.max));
        if (bad) push(it.id, 'NUMERIC_INVALID');
        break;
      }
      default: break;
    }
    if ((mode === 'AUTO' || mode === 'HYBRID') && !hasAnswerKey(it)) push(it.id, 'ANSWER_KEY_MISSING');
  };

  for (const s of doc.sections) {
    if(s.audioAssetKey && !assetOk(s.audioAssetKey))push(s.id,'ASSET_MISSING');
    if (!s.nodes.length) push(s.id, 'SECTION_EMPTY', `${s.title || 'Phần'}: phần chưa có câu hỏi`);
    for (const nd of s.nodes) {
      if (nd.kind === 'GROUP') {
        const name = nd.title || (nd.groupType === 'Q21' ? 'Bài nghe' : 'Ngữ liệu');
        if (nd.groupType !== 'Q13' && nd.groupType !== 'Q21') push(nd.id, 'TYPE_UNAVAILABLE');
        if (!nd.children.length) push(nd.id, 'GROUP_EMPTY', `Nhóm "${name}" chưa có câu hỏi con`);
        if (nd.groupType === 'Q21') {
          if (!nd.audioAssetKey || !doc.assets[nd.audioAssetKey]) push(nd.id, 'LISTENING_AUDIO_MISSING', `Nhóm "${name}" chưa có tệp nghe`);
          else {
            if (!assetOk(nd.audioAssetKey)) push(nd.id, 'ASSET_MISSING');
            if (!String(doc.assets[nd.audioAssetKey].transcript ?? '').trim()) push(nd.id, 'TRANSCRIPT_MISSING', `Nhóm "${name}" chưa có transcript`);
          }
        }
        checkBlocks(nd.id, nd.stimulus);
        nd.children.forEach(checkItem);
      } else {
        checkItem(nd);
      }
    }
  }
  if (items === 0) push('root', 'NO_ITEMS');
  return out;
}

/** Client issues merged with the server's (server message wins for the same node + code). */
export function mergeIssues(client: ValidationIssue[], server: ValidationIssue[] | null | undefined): ValidationIssue[] {
  const byKey = new Map<string, ValidationIssue>();
  for (const i of client) byKey.set(`${i.nodeId}|${i.code}`, i);
  for (const i of server ?? []) byKey.set(`${i.nodeId}|${i.code}`, i);
  return [...byKey.values()].sort((a, b) => (a.level === b.level ? 0 : a.level === 'ERROR' ? -1 : 1));
}

export const errorCount = (issues: ValidationIssue[]) => issues.filter((i) => i.level === 'ERROR').length;
