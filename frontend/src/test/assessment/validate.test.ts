import { describe, it, expect } from 'vitest';
import { blankGroup, blankItem, blankSection, newDocument } from '../../features/assessment/model/doc';
import { mergeIssues, validateDocument } from '../../features/assessment/model/validate';
import type { AssessmentDocument, IssueCode, ItemNode, SectionChild } from '../../features/assessment/types';

const META = { title: 'Đề', kind: 'HOMEWORK' as const, grade: 7, subjectCode: 'TOAN', languageCode: null, topicTitle: null };
const docWith = (...nodes: SectionChild[]): AssessmentDocument => newDocument(META, [{ ...blankSection('Phần I', 's1'), nodes }]);
const codes = (doc: AssessmentDocument, opts = {}) => validateDocument(doc, opts).map((i) => i.code);

function goodQ01(id = 'q1'): ItemNode {
  const q = blankItem('Q01', id) as Extract<ItemNode, { type: 'Q01' }>;
  return { ...q, prompt: 'Câu hỏi?', interaction: { ...q.interaction, options: q.interaction.options.map((o, i) => ({ ...o, text: `P${i}` })) }, answerKey: { optionId: 'A' } };
}

describe('client validator', () => {
  it('a complete Q01 has no issues', () => {
    expect(validateDocument(docWith(goodQ01()))).toEqual([]);
  });

  it('NO_ITEMS and SECTION_EMPTY', () => {
    const c = codes(docWith());
    expect(c).toContain('NO_ITEMS');
    expect(c).toContain('SECTION_EMPTY');
  });

  it('GROUP_EMPTY, LISTENING_AUDIO_MISSING, TRANSCRIPT_MISSING (warning)', () => {
    expect(codes(docWith(blankGroup('Q21', 'g1')))).toEqual(expect.arrayContaining(['GROUP_EMPTY', 'LISTENING_AUDIO_MISSING']));
    const doc = docWith({ ...blankGroup('Q21', 'g1'), audioAssetKey: 'a1', children: [goodQ01()] });
    doc.assets.a1 = { assetId: 'm1', type: 'AUDIO', name: 'a.mp3', mime: 'audio/mpeg', transcript: '' };
    const issues = validateDocument(doc);
    expect(issues.map((i) => i.code)).toEqual(['TRANSCRIPT_MISSING']);
    expect(issues[0].level).toBe('WARNING');
  });

  it('PROMPT_EMPTY, POINTS_INVALID, ANSWER_KEY_MISSING, OPTION_EMPTY', () => {
    const q = blankItem('Q01', 'q1');
    const c = codes(docWith({ ...q, points: 0 }));
    expect(c).toEqual(expect.arrayContaining(['PROMPT_EMPTY', 'POINTS_INVALID', 'ANSWER_KEY_MISSING', 'OPTION_EMPTY']));
    expect(codes(docWith({ ...goodQ01(), points: 1.005 }))).toContain('POINTS_INVALID');
    expect(codes(docWith({ ...goodQ01(), points: 101 }))).toContain('POINTS_INVALID');
    const msg = validateDocument(docWith({ ...goodQ01(), answerKey: { optionId: null } } as ItemNode)).find((i) => i.code === 'ANSWER_KEY_MISSING');
    expect(msg?.message).toBe('Chấm tự động nhưng chưa có đáp án');
  });

  it('BLANK_COUNT_MISMATCH', () => {
    const q = { ...blankItem('Q07', 'q1'), prompt: 'A ___ and ___' }; // 2 blanks in the prompt, 1 in the interaction
    expect(codes(docWith(q))).toContain('BLANK_COUNT_MISMATCH');
  });

  it('AUTO_MODE_UNSUPPORTED and RUBRIC_MISSING', () => {
    const essay = { ...blankItem('Q12', 'q1'), prompt: 'Viết đoạn văn' };
    expect(codes(docWith({ ...essay, grading: { ...essay.grading, mode: 'AUTO' } }))).toContain('AUTO_MODE_UNSUPPORTED');
    const issues = validateDocument(docWith(essay));
    expect(issues.map((i) => i.code)).toEqual(['RUBRIC_MISSING']);
    expect(issues[0].level).toBe('WARNING');
    expect(codes(docWith({ ...essay, grading: { ...essay.grading, rubricId: 'rubric-writing-email' } }))).toEqual([]);
  });

  it('ASSET_MISSING when an asset is not uploaded', () => {
    const doc = docWith({ ...blankGroup('Q21', 'g1'), audioAssetKey: 'a1', children: [goodQ01()] });
    doc.assets.a1 = { assetId: 'm1', type: 'AUDIO', name: 'a.mp3', mime: 'audio/mpeg', transcript: 'Tom: hi' };
    expect(codes(doc, { assetStatus: { a1: { assetId: 'm1', status: 'PENDING', mime: 'audio/mpeg', sizeBytes: 1 } } })).toContain('ASSET_MISSING');
    const img = { ...goodQ01('q2'), promptBlocks: [{ type: 'IMAGE' as const, assetKey: 'nope' }] };
    expect(codes(docWith(img))).toContain('ASSET_MISSING');
  });

  it('P1 types are available after the advanced interaction rollout', () => {
    const p1 = { ...goodQ01(), type: 'Q14' } as unknown as ItemNode;
    expect(codes(docWith(p1))).not.toContain('TYPE_UNAVAILABLE');
  });

  it('NUMERIC_INVALID', () => {
    const q = { ...blankItem('Q11', 'q1'), prompt: 'Tính' } as Extract<ItemNode, { type: 'Q11' }>;
    const bad = { ...q, answerKey: { ...q.answerKey, value: 12.5, tolerance: -1 } };
    expect(codes(docWith(bad))).toContain('NUMERIC_INVALID');
    const range = { ...q, answerKey: { ...q.answerKey, value: null, range: { min: 5, max: 1 } } };
    expect(codes(docWith(range))).toContain('NUMERIC_INVALID');
    expect(codes(docWith({ ...q, answerKey: { ...q.answerKey, value: 12.5 } }))).toEqual([]);
  });

  it('LANGUAGE_REQUIRED', () => {
    expect(codes(docWith(goodQ01()), { languageRequired: true })).toContain('LANGUAGE_REQUIRED' as IssueCode);
  });

  it('merges server issues over client ones, errors first', () => {
    const merged = mergeIssues(
      [{ nodeId: 'q1', level: 'WARNING', code: 'RUBRIC_MISSING', message: 'client' }],
      [{ nodeId: 'q1', level: 'WARNING', code: 'RUBRIC_MISSING', message: 'server' }, { nodeId: 'g1', level: 'ERROR', code: 'ASSET_MISSING', message: 'x' }],
    );
    expect(merged.map((i) => i.message)).toEqual(['x', 'server']);
  });
});
