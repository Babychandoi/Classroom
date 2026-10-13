import { describe, it, expect } from 'vitest';
import {
  addNode, blankGroup, blankItem, blankSection, convertItemType, countBlanks, deriveSkills, duplicateNode, findNode, indexDocument,
  isValidId, moveNode, newDocument, newId, removeNode, reorderSiblings, syncBlanks, toLearnerItem,
} from '../../features/assessment/model/doc';
import { TYPE_CATALOG } from '../../features/assessment/catalog';
import { isAnswered } from '../../features/assessment/model/answers';
import { rubricScore } from '../../features/assessment/model/rubric';
import type { AssessmentDocument, GroupNode, ItemNode } from '../../features/assessment/types';

const META = { title: 'Đề thử', kind: 'HOMEWORK' as const, grade: 7, subjectCode: 'NGOAI_NGU_1', languageCode: 'en', topicTitle: 'Unit 3' };

function sampleDoc(): AssessmentDocument {
  const g: GroupNode = { ...blankGroup('Q21', 'g1'), children: [blankItem('Q01', 'q1'), blankItem('Q03', 'q2'), blankItem('Q07', 'q3'), blankItem('Q22', 'q4')] };
  const s1 = { ...blankSection('Part I', 's1'), nodes: [g] };
  const q5 = { ...blankItem('Q04', 'q5'), points: 0.5 };
  const q6 = { ...blankItem('Q10', 'q6'), points: 1.5 };
  const s2 = { ...blankSection('Part II', 's2'), nodes: [q5, q6] };
  return newDocument(META, [s1, s2]);
}

describe('type catalog', () => {
  it('lists all 28 supported types', () => {
    expect(TYPE_CATALOG).toHaveLength(28);
    const available = TYPE_CATALOG.filter((t) => t.available).map((t) => t.code);
    expect(available).toHaveLength(28);
    expect(available).toEqual(Array.from({length:28},(_,i)=>`Q${String(i+1).padStart(2,'0')}`));
  });
});

describe('document index', () => {
  it('labels a group "Câu 1–4" and continues numbering; Q04 counts as one item', () => {
    const ix = indexDocument(sampleDoc());
    expect(ix.labels.g1).toBe('Câu 1–4');
    expect(ix.labels.q1).toBe('Câu 1');
    expect(ix.labels.q4).toBe('Câu 4');
    expect(ix.labels.q5).toBe('Câu 5');
    expect(ix.labels.q6).toBe('Câu 6');
    expect(ix.count).toBe(6);
  });

  it('labels a one-child group "Câu n" and an empty group "Nhóm trống"', () => {
    const doc = sampleDoc();
    doc.sections[0].nodes.push({ ...blankGroup('Q13', 'g2'), children: [blankItem('Q01', 'qx')] }, blankGroup('Q13', 'g3'));
    const ix = indexDocument(doc);
    expect(ix.labels.g2).toBe('Câu 5');
    expect(ix.labels.g3).toBe('Nhóm trống');
  });

  it('sums points per group / section / total with 0.5 steps', () => {
    const ix = indexDocument(sampleDoc());
    expect(ix.points.g1).toBe(5); // 1 + 1 + 1 + 2
    expect(ix.sectionPoints.s1).toBe(5);
    expect(ix.sectionPoints.s2).toBe(2); // 0.5 + 1.5
    expect(ix.total).toBe(7);
    const doc = sampleDoc();
    (doc.sections[1].nodes[0] as ItemNode).points = 0.1;
    (doc.sections[1].nodes[1] as ItemNode).points = 0.2;
    expect(indexDocument(doc).sectionPoints.s2).toBe(0.3);
  });
});

describe('tree editing', () => {
  it('reorders within a group and the labels follow', () => {
    const doc = reorderSiblings(sampleDoc(), 'g1', 'q4', 'q1');
    const g = doc.sections[0].nodes[0] as GroupNode;
    expect(g.children.map((c) => c.id)).toEqual(['q4', 'q1', 'q2', 'q3']);
    const ix = indexDocument(doc);
    expect(ix.labels.q4).toBe('Câu 1');
    expect(ix.labels.q1).toBe('Câu 2');
  });

  it('a cross-parent reorder is a no-op', () => {
    const doc = sampleDoc();
    expect(reorderSiblings(doc, 'g1', 'q5', 'q1')).toBe(doc);
    expect(reorderSiblings(doc, 's2', 'q1', 'q5')).toBe(doc);
  });

  it('moves up / down among siblings and stops at the edges', () => {
    const doc = sampleDoc();
    const down = moveNode(doc, 'q1', 1);
    expect((down.sections[0].nodes[0] as GroupNode).children.map((c) => c.id)).toEqual(['q2', 'q1', 'q3', 'q4']);
    expect(moveNode(doc, 'q1', -1)).toBe(doc);
    const sections = moveNode(doc, 's2', -1);
    expect(sections.sections.map((s) => s.id)).toEqual(['s2', 's1']);
  });

  it('duplicates a group with fresh, unique ids', () => {
    const { doc, newId: copyId } = duplicateNode(sampleDoc(), 'g1');
    expect(copyId).toBeTruthy();
    const ids: string[] = [];
    doc.sections.forEach((s) => { ids.push(s.id); s.nodes.forEach((n) => { ids.push(n.id); if (n.kind === 'GROUP') n.children.forEach((c) => ids.push(c.id)); }); });
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids.every(isValidId)).toBe(true);
    expect(doc.sections[0].nodes[1].id).toBe(copyId);
  });

  it('never nests a group inside a group, removes nodes', () => {
    const doc = sampleDoc();
    expect(addNode(doc, 'g1', blankGroup('Q13'))).toBe(doc);
    const added = addNode(doc, 'g1', blankItem('Q10', 'q9'));
    expect(findNode(added, 'q9').kind).toBe('item');
    expect(findNode(removeNode(added, 'q9'), 'q9').kind).toBe('none');
  });

  it('newId matches the id pattern', () => {
    for (let i = 0; i < 50; i++) expect(isValidId(newId('q'))).toBe(true);
  });
});

describe('type conversion', () => {
  it('Q01 → Q02 keeps options and the key', () => {
    const q = blankItem('Q01', 'q1') as Extract<ItemNode, { type: 'Q01' }>;
    q.interaction.options = q.interaction.options.map((o, i) => ({ ...o, text: `Opt ${i}` }));
    q.answerKey = { optionId: 'B' };
    const { item, lostKey } = convertItemType(q, 'Q02');
    expect(item.type).toBe('Q02');
    expect(lostKey).toBe(false);
    if (item.type !== 'Q02') throw new Error('type');
    expect(item.interaction.options.map((o) => o.text)).toEqual(['Opt 0', 'Opt 1', 'Opt 2', 'Opt 3']);
    expect(item.answerKey.optionIds).toEqual(['B']);
    expect(item.id).toBe('q1');
  });

  it('Q01 → Q12 drops the key (lostKey) and keeps prompt / points / feedback', () => {
    const q = { ...blankItem('Q01', 'q1'), prompt: 'Câu hỏi', points: 2, feedback: 'Giải thích' } as Extract<ItemNode, { type: 'Q01' }>;
    q.answerKey = { optionId: 'A' };
    const { item, lostKey } = convertItemType(q, 'Q12');
    expect(lostKey).toBe(true);
    expect(item.type).toBe('Q12');
    expect(item.prompt).toBe('Câu hỏi');
    expect(item.points).toBe(2);
    expect(item.feedback).toBe('Giải thích');
    expect(item.grading.mode).toBe('MANUAL');
  });

  it('Q07 → Q10 keeps the accepted answers', () => {
    const q = { ...blankItem('Q07', 'q1'), prompt: 'The train leaves at ___.' } as Extract<ItemNode, { type: 'Q07' }>;
    q.answerKey = { blanks: { b1: ['9:15', 'nine fifteen'] } };
    const { item, lostKey } = convertItemType(q, 'Q10');
    expect(lostKey).toBe(false);
    if (item.type !== 'Q10') throw new Error('type');
    expect(item.answerKey.accepted).toEqual(['9:15', 'nine fifteen']);
    expect(item.prompt).not.toContain('___');
  });

  it('syncBlanks follows the number of ___ in the prompt', () => {
    const q = { ...blankItem('Q07', 'q1'), prompt: 'A ___ b ___ c ___' };
    const synced = syncBlanks(q);
    if (synced.type !== 'Q07') throw new Error('type');
    expect(countBlanks(synced.prompt)).toBe(3);
    expect(synced.interaction.blanks).toHaveLength(3);
    expect(new Set(synced.interaction.blanks.map((b) => b.id)).size).toBe(3);
    const fewer = syncBlanks({ ...synced, prompt: 'only ___' });
    if (fewer.type !== 'Q07') throw new Error('type');
    expect(fewer.interaction.blanks.map((b) => b.id)).toEqual(['b1']);
    expect(Object.keys(fewer.answerKey.blanks)).toEqual(['b1']);
    const dd = syncBlanks({ ...blankItem('Q09'), prompt: 'We ___ there. It ___ fun.' });
    if (dd.type !== 'Q09') throw new Error('type');
    expect(dd.interaction.dropdowns).toHaveLength(2);
  });
});

describe('skills, learner projection, answers, rubric', () => {
  it('derives skills like the backend', () => {
    expect(deriveSkills(sampleDoc())).toEqual(['listening', 'speaking']);
    expect(deriveSkills(newDocument(META, [{ ...blankSection('x'), nodes: [blankItem('Q01')] }]))).toEqual(['gv']);
  });

  it('the learner projection never carries the key or feedback', () => {
    const q = { ...blankItem('Q01', 'q1'), feedback: 'SECRET_FEEDBACK' } as Extract<ItemNode, { type: 'Q01' }>;
    q.answerKey = { optionId: 'C' };
    const json = JSON.stringify(toLearnerItem(q, 'Câu 1'));
    expect(json).not.toContain('answerKey');
    expect(json).not.toContain('SECRET_FEEDBACK');
    const q8 = { ...blankItem('Q08'), prompt: '___ and ___' } as Extract<ItemNode, { type: 'Q08' }>;
    const synced = syncBlanks(q8) as Extract<ItemNode, { type: 'Q08' }>;
    synced.answerKey = { blanks: { b1: 'go', b2: 'went' } };
    synced.interaction.distractors = ['gone', 'go'];
    const li = toLearnerItem(synced, 'Câu 2');
    expect((li.interaction as { bank: string[] }).bank).toEqual(['go', 'go', 'gone', 'went']);
  });

  it('isAnswered per type', () => {
    expect(isAnswered('Q01', { optionId: 'A' })).toBe(true);
    expect(isAnswered('Q02', { optionIds: [] })).toBe(false);
    expect(isAnswered('Q03', { value: false })).toBe(true);
    expect(isAnswered('Q07', { blanks: { b1: '  ' } })).toBe(false);
    expect(isAnswered('Q12', { text: 'Bài viết' })).toBe(true);
    expect(isAnswered('Q22', null)).toBe(false);
  });

  it('rubric score uses Σ weight × level/(L−1) × points', () => {
    const rubric = { levels: ['0', '1', '2', '3'], criteria: [{ id: 'a', title: 'A', weight: 30 }, { id: 'b', title: 'B', weight: 25 }, { id: 'c', title: 'C', weight: 20 }, { id: 'd', title: 'D', weight: 25 }] };
    // (0.30·3/3 + 0.25·2/3 + 0.20·3/3 + 0.25·1/3) · 2 = (0.30 + 0.1667 + 0.20 + 0.0833) · 2 = 1.5
    expect(rubricScore(rubric, { a: 3, b: 2, c: 3, d: 1 }, 2)).toBe(1.5);
    expect(rubricScore(rubric, {}, 2)).toBe(0);
  });
});
