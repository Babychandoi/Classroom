import type {
  AssessmentDocument, AssessmentKind, ContentBlock, GradingMode, GradingSpec, GroupNode, GroupTypeCode, IdText, ItemNode,
  ItemTypeCode, LearnerGroup, LearnerItem, LearnerDocument, LearnerSection, SectionChild, SectionNode,
} from '../types';
import { BLANK_TYPES, MANUAL_ONLY_TYPES, NORMALIZED_TYPES, PARTIAL_TYPES } from '../catalog';
import { sumPoints } from '../format';

// ---------------------------------------------------------------------------------------------------------------
// Ids

const ID_RE = /^[A-Za-z0-9_-]{1,40}$/;
export const isValidId = (id: string) => ID_RE.test(id);

let counter = 0;
/** Client-generated id matching ^[A-Za-z0-9_-]{1,40}$ (contract §2.1). */
export function newId(prefix = 'n'): string {
  counter = (counter + 1) % 1_000_000;
  const rnd = Math.random().toString(36).slice(2, 8);
  return `${prefix}${Date.now().toString(36).slice(-4)}${rnd}${counter.toString(36)}`.slice(0, 40);
}

// ---------------------------------------------------------------------------------------------------------------
// Blank nodes

export const BLANK_TOKEN = '___';
export const countBlanks = (prompt: string | null | undefined) => Math.max(0, String(prompt ?? '').split(BLANK_TOKEN).length - 1);

export function defaultGrading(type: ItemTypeCode): GradingSpec {
  const mode: GradingMode = type==='Q28'?'PASS_FAIL':['Q18','Q20','Q27'].includes(type)?'HYBRID':MANUAL_ONLY_TYPES.includes(type) ? 'MANUAL' : 'AUTO';
  return {
    mode,
    partialCredit: PARTIAL_TYPES.includes(type) && type !== 'Q06',
    stepTable: null,
    rubricId: null,
    normalization: NORMALIZED_TYPES.includes(type) ? { caseInsensitive: true, collapseWhitespace: true, stripTrailingPunctuation: false } : null,
  };
}

const opts = (n: number): { id: string; text: string; assetKey: null }[] =>
  Array.from({ length: n }, (_, i) => ({ id: 'ABCDEFGHIJ'[i], text: '', assetKey: null }));
const idTexts = (prefix: string, n: number): IdText[] => Array.from({ length: n }, (_, i) => ({ id: `${prefix}${i + 1}`, text: '' }));

/** A new, empty item of `type` with the type's default grading (contract §2.1 shapes). */
export function blankItem(type: ItemTypeCode, id: string = newId('q')): ItemNode {
  const base = { kind: 'ITEM' as const, id, prompt: '', promptBlocks: [] as ContentBlock[], points: 1, grading: defaultGrading(type),
    feedback: '', difficulty: null, outcome: '', tags: [] as string[] };
  switch (type) {
    case 'Q01': return { ...base, type, interaction: { options: opts(4), shuffleOptions: true }, answerKey: { optionId: null } };
    case 'Q02': return { ...base, type, interaction: { options: opts(4), shuffleOptions: true }, answerKey: { optionIds: [] } };
    case 'Q03': return { ...base, type, interaction: {}, answerKey: { value: null } };
    case 'Q04': return { ...base, type, interaction: { statements: idTexts('s', 4) }, answerKey: { values: {} } };
    case 'Q05': {
      const left = idTexts('l', 3), right = idTexts('r', 3);
      return { ...base, type, interaction: { left, right }, answerKey: { pairs: Object.fromEntries(left.map((l, i) => [l.id, right[i].id])) } };
    }
    case 'Q06': { const items = idTexts('i', 3); return { ...base, type, interaction: { items }, answerKey: { order: items.map((x) => x.id) } }; }
    case 'Q07': return { ...base, type, prompt: BLANK_TOKEN, interaction: { blanks: [{ id: 'b1' }] }, answerKey: { blanks: { b1: [] } } };
    case 'Q08': return { ...base, type, prompt: BLANK_TOKEN, interaction: { blanks: [{ id: 'b1' }], distractors: [] }, answerKey: { blanks: {} } };
    case 'Q09': return { ...base, type, prompt: BLANK_TOKEN, interaction: { dropdowns: [{ id: 'd1', options: idTexts('o', 2) }] }, answerKey: { choices: {} } };
    case 'Q10': return { ...base, type, interaction: { maxLength: null }, answerKey: { accepted: [] } };
    case 'Q11': return { ...base, type, interaction: { unit: null }, answerKey: { value: null, tolerance: 0, toleranceType: 'ABSOLUTE', range: null, acceptedUnits: [] } };
    case 'Q12': return { ...base, type, interaction: { minWords: null, maxWords: null }, answerKey: {} };
    case 'Q22': return { ...base, type, points: 2, interaction: { recording: { prepSeconds: 15, maxSeconds: 60, maxRetakes: 2, allowFileFallback: true, maxFileMb: 10 } }, answerKey: {} };
    case 'Q14': return {...base,type,interaction:{assetKey:null,regions:[]},answerKey:{regionIds:[]}};
    case 'Q15': return {...base,type,interaction:{assetKey:null,labels:idTexts('l',3),targets:[]},answerKey:{labels:{}}};
    case 'Q16': return {...base,type,interaction:{cards:idTexts('c',4),categories:idTexts('g',2)},answerKey:{categories:{}}};
    case 'Q17': return {...base,type,interaction:{headers:['Cột 1','Cột 2'],rows:[{id:'r1',label:'Hàng 1'}],cells:[{id:'c1',row:'r1',column:0,text:'',editable:true},{id:'c2',row:'r1',column:1,text:'',editable:true}]},answerKey:{cells:{}}};
    case 'Q18': return {...base,type,interaction:{xLabel:'x',yLabel:'y',min:0,max:10,points:[{id:'p1',x:1,label:'Điểm 1'},{id:'p2',x:2,label:'Điểm 2'}]},answerKey:{values:{},tolerance:0}};
    case 'Q19': return {...base,type,interaction:{variables:[{id:'x',min:-10,max:10}],chemical:false},answerKey:{accepted:[]}};
    case 'Q20': return {...base,type,interaction:{steps:[{id:'s1',prompt:'Bước 1',points:1},{id:'s2',prompt:'Bước 2',points:1}]},answerKey:{steps:{}}};
    case 'Q23': return {...base,type,interaction:{width:800,height:500,backgroundAssetKey:null},answerKey:{}};
    case 'Q24': case 'Q25': case 'Q28': return {...base,type,interaction:{maxFileMb:20,maxFiles:3,accept:type==='Q25'?'video/*':'.pdf,.png,.jpg,.docx,.xlsx,.txt',instructions:''},answerKey:{}};
    case 'Q26': return {...base,type,interaction:{language:'python',starterCode:'',examples:[]},answerKey:{tests:[]}};
    case 'Q27': return {...base,type,interaction:{simulation:'FORMULA',formula:'u*t',controls:[{id:'u',label:'Vận tốc',min:0,max:10,step:1,initial:2},{id:'t',label:'Thời gian',min:0,max:10,step:1,initial:3}]},answerKey:{values:{result:6},tolerance:0.01}};
  }
}

export function blankGroup(groupType: GroupTypeCode, id: string = newId('g')): GroupNode {
  return {
    kind: 'GROUP', id, groupType,
    title: groupType === 'Q21' ? 'Bài nghe mới' : 'Ngữ liệu mới',
    instructions: '',
    stimulus: groupType === 'Q13' ? [{ type: 'TEXT', text: '' }] : [],
    audioAssetKey: null,
    playback: { maxPlays: 2, allowSeek: false, allowSpeed: false, autoPlay: false, pauseBeforeAnswerSec: 5 },
    keepStimulusVisible: true, display: 'ALL', shuffleChildren: false, children: [],
  };
}

export function blankSection(title = 'Phần mới', id: string = newId('s')): SectionNode {
  return { id, title, instructions: '', shuffle: false, nodes: [] };
}

export function newDocument(meta: { title: string; kind: AssessmentKind; grade: number | null; subjectCode: string | null; languageCode: string | null; topicTitle: string | null }, sections?: SectionNode[]): AssessmentDocument {
  return { schemaVersion: 1, ...meta, assets: {}, sections: sections ?? [blankSection('Phần I')] };
}

// ---------------------------------------------------------------------------------------------------------------
// Index: labels, points, parents

export interface DocIndex {
  labels: Record<string, string>;
  /** Points per item, group and section id. */
  points: Record<string, number>;
  sectionPoints: Record<string, number>;
  total: number;
  /** Number of items (a Q04 with 4 statements counts 1). */
  count: number;
  /** id → parent id (section id for top-level nodes, group id for children). */
  parentOf: Record<string, string>;
  /** id → section id. */
  sectionOf: Record<string, string>;
  /** Items in canonical order. */
  items: ItemNode[];
}

export function groupLabel(start: number, end: number): string {
  if (end < start) return 'Nhóm trống';
  return start === end ? `Câu ${start}` : `Câu ${start}–${end}`;
}

export function indexDocument(doc: AssessmentDocument): DocIndex {
  const labels: Record<string, string> = {};
  const points: Record<string, number> = {};
  const sectionPoints: Record<string, number> = {};
  const parentOf: Record<string, string> = {};
  const sectionOf: Record<string, string> = {};
  const items: ItemNode[] = [];
  let n = 0;
  const visitItem = (item: ItemNode, parent: string, section: string) => {
    n += 1;
    labels[item.id] = `Câu ${n}`;
    points[item.id] = Number(item.points) || 0;
    parentOf[item.id] = parent;
    sectionOf[item.id] = section;
    items.push(item);
  };
  for (const s of doc.sections) {
    const sp: number[] = [];
    for (const node of s.nodes) {
      if (node.kind === 'GROUP') {
        const start = n + 1;
        node.children.forEach((c) => visitItem(c, node.id, s.id));
        labels[node.id] = groupLabel(start, n);
        points[node.id] = sumPoints(node.children.map((c) => Number(c.points) || 0));
        parentOf[node.id] = s.id;
        sectionOf[node.id] = s.id;
      } else {
        visitItem(node, s.id, s.id);
      }
      sp.push(points[node.id]);
    }
    sectionPoints[s.id] = sumPoints(sp);
    points[s.id] = sectionPoints[s.id];
    sectionOf[s.id] = s.id;
  }
  return { labels, points, sectionPoints, total: sumPoints(doc.sections.map((s) => sectionPoints[s.id])), count: n, parentOf, sectionOf, items };
}

// ---------------------------------------------------------------------------------------------------------------
// Find / patch / remove / add

export type Found =
  | { kind: 'root'; node: AssessmentDocument }
  | { kind: 'section'; node: SectionNode }
  | { kind: 'group'; node: GroupNode; section: SectionNode }
  | { kind: 'item'; node: ItemNode; section: SectionNode; group: GroupNode | null }
  | { kind: 'none'; node: null };

export function findNode(doc: AssessmentDocument, id: string | null | undefined): Found {
  if (!id || id === 'root') return { kind: 'root', node: doc };
  for (const s of doc.sections) {
    if (s.id === id) return { kind: 'section', node: s };
    for (const nd of s.nodes) {
      if (nd.id === id) return nd.kind === 'GROUP' ? { kind: 'group', node: nd, section: s } : { kind: 'item', node: nd, section: s, group: null };
      if (nd.kind === 'GROUP') {
        for (const c of nd.children) if (c.id === id) return { kind: 'item', node: c, section: s, group: nd };
      }
    }
  }
  return { kind: 'none', node: null };
}

/** Maps every section / node / child; `fn` returns the replacement, `null` to remove, or `undefined` to keep. */
function mapTree(doc: AssessmentDocument, fn: (node: SectionNode | SectionChild) => SectionNode | SectionChild | null | undefined): AssessmentDocument {
  const mapChildren = (list: ItemNode[]): ItemNode[] => {
    const out: ItemNode[] = [];
    for (const c of list) {
      const r = fn(c);
      if (r === null) continue;
      out.push((r === undefined ? c : r) as ItemNode);
    }
    return out;
  };
  const mapNodes = (list: SectionChild[]): SectionChild[] => {
    const out: SectionChild[] = [];
    for (const nd of list) {
      const r = fn(nd);
      if (r === null) continue;
      const x = (r === undefined ? nd : r) as SectionChild;
      out.push(x.kind === 'GROUP' ? { ...x, children: mapChildren(x.children) } : x);
    }
    return out;
  };
  const sections: SectionNode[] = [];
  for (const s of doc.sections) {
    const r = fn(s);
    if (r === null) continue;
    const x = (r === undefined ? s : r) as SectionNode;
    sections.push({ ...x, nodes: mapNodes(x.nodes) });
  }
  return { ...doc, sections };
}

/** Shallow-merges `patch` into the node with `id` ('root' patches the document metadata). */
export function patchNode(doc: AssessmentDocument, id: string, patch: Record<string, unknown>): AssessmentDocument {
  if (id === 'root') return { ...doc, ...(patch as Partial<AssessmentDocument>) };
  return mapTree(doc, (nd) => (nd.id === id ? ({ ...nd, ...patch } as SectionNode | SectionChild) : undefined));
}

export function replaceItem(doc: AssessmentDocument, id: string, item: ItemNode): AssessmentDocument {
  return mapTree(doc, (nd) => (nd.id === id ? item : undefined));
}

export function removeNode(doc: AssessmentDocument, id: string): AssessmentDocument {
  return mapTree(doc, (nd) => (nd.id === id ? null : undefined));
}

/** Appends `node` to a section (any node) or a group (items only — a group never contains a group). */
export function addNode(doc: AssessmentDocument, parentId: string, node: SectionChild): AssessmentDocument {
  const f = findNode(doc, parentId);
  if (f.kind === 'section') return { ...doc, sections: doc.sections.map((s) => (s.id === parentId ? { ...s, nodes: [...s.nodes, node] } : s)) };
  if (f.kind === 'group' && node.kind === 'ITEM') {
    return mapTree(doc, (nd) => (nd.id === parentId && 'kind' in nd && nd.kind === 'GROUP' ? { ...nd, children: [...nd.children, node] } : undefined));
  }
  return doc;
}

export function addSection(doc: AssessmentDocument, section: SectionNode = blankSection()): AssessmentDocument {
  return { ...doc, sections: [...doc.sections, section] };
}

function cloneItem(item: ItemNode): ItemNode {
  return { ...(JSON.parse(JSON.stringify(item)) as ItemNode), id: newId('q') };
}

/** Copies a node (fresh ids for it and all its children) right after the original. Returns the new doc and the copy's id. */
export function duplicateNode(doc: AssessmentDocument, id: string): { doc: AssessmentDocument; newId: string | null } {
  const f = findNode(doc, id);
  if (f.kind === 'item') {
    const copy = cloneItem(f.node);
    const insertAfter = <T extends { id: string }>(list: T[]): T[] => list.flatMap((x) => (x.id === id ? [x, copy as unknown as T] : [x]));
    const next = { ...doc, sections: doc.sections.map((s) => ({
      ...s,
      nodes: f.group ? s.nodes.map((nd) => (nd.id === f.group!.id && nd.kind === 'GROUP' ? { ...nd, children: insertAfter(nd.children) } : nd)) : insertAfter(s.nodes),
    })) };
    return { doc: next, newId: copy.id };
  }
  if (f.kind === 'group') {
    const g = JSON.parse(JSON.stringify(f.node)) as GroupNode;
    const copy: GroupNode = { ...g, id: newId('g'), title: g.title ? `${g.title} (bản sao)` : g.title, children: g.children.map((c) => ({ ...c, id: newId('q') })) };
    const next = { ...doc, sections: doc.sections.map((s) => ({ ...s, nodes: s.nodes.flatMap((nd) => (nd.id === id ? [nd, copy] : [nd])) })) };
    return { doc: next, newId: copy.id };
  }
  if (f.kind === 'section') {
    const s0 = JSON.parse(JSON.stringify(f.node)) as SectionNode;
    const copy: SectionNode = {
      ...s0, id: newId('s'), title: `${s0.title} (bản sao)`,
      nodes: s0.nodes.map((nd) => (nd.kind === 'GROUP' ? { ...nd, id: newId('g'), children: nd.children.map((c) => ({ ...c, id: newId('q') })) } : { ...nd, id: newId('q') })),
    };
    return { doc: { ...doc, sections: doc.sections.flatMap((s) => (s.id === id ? [s, copy] : [s])) }, newId: copy.id };
  }
  return { doc, newId: null };
}

function moveInList<T extends { id: string }>(list: T[], fromId: string, toId: string): T[] | null {
  const f = list.findIndex((x) => x.id === fromId);
  const t = list.findIndex((x) => x.id === toId);
  if (f < 0 || t < 0 || f === t) return null;
  const b = [...list];
  const [x] = b.splice(f, 1);
  b.splice(t, 0, x);
  return b;
}

/**
 * Moves `fromId` to the position of `toId` among the children of `parentId` ('root' = sections). Both must be siblings under that
 * parent; anything else (cross-parent drag) is a no-op and returns the same document.
 */
export function reorderSiblings(doc: AssessmentDocument, parentId: string, fromId: string, toId: string): AssessmentDocument {
  if (parentId === 'root') {
    const moved = moveInList(doc.sections, fromId, toId);
    return moved ? { ...doc, sections: moved } : doc;
  }
  let changed = false;
  const sections = doc.sections.map((s) => {
    if (s.id === parentId) {
      const moved = moveInList(s.nodes, fromId, toId);
      if (!moved) return s;
      changed = true;
      return { ...s, nodes: moved };
    }
    let touched = false;
    const nodes = s.nodes.map((nd) => {
      if (nd.kind !== 'GROUP' || nd.id !== parentId) return nd;
      const moved = moveInList(nd.children, fromId, toId);
      if (!moved) return nd;
      touched = true;
      return { ...nd, children: moved };
    });
    if (!touched) return s;
    changed = true;
    return { ...s, nodes };
  });
  return changed ? { ...doc, sections } : doc;
}

/** Parent id of a node ('root' for sections), or null when absent. */
export function parentIdOf(doc: AssessmentDocument, id: string): string | null {
  const f = findNode(doc, id);
  if (f.kind === 'section') return 'root';
  if (f.kind === 'group') return f.section.id;
  if (f.kind === 'item') return f.group ? f.group.id : f.section.id;
  return null;
}

export function siblingsOf(doc: AssessmentDocument, id: string): { id: string }[] {
  const f = findNode(doc, id);
  if (f.kind === 'section') return doc.sections;
  if (f.kind === 'group') return f.section.nodes;
  if (f.kind === 'item') return f.group ? f.group.children : f.section.nodes;
  return [];
}

/** Moves a node one step up (-1) or down (+1) among its siblings. */
export function moveNode(doc: AssessmentDocument, id: string, dir: -1 | 1): AssessmentDocument {
  const parent = parentIdOf(doc, id);
  if (!parent) return doc;
  const sibs = siblingsOf(doc, id);
  const i = sibs.findIndex((x) => x.id === id);
  const j = i + dir;
  if (i < 0 || j < 0 || j >= sibs.length) return doc;
  return reorderSiblings(doc, parent, id, sibs[j].id);
}

// ---------------------------------------------------------------------------------------------------------------
// Answer keys

/** Whether an item has a complete answer key (Q12/Q22 never need one). */
export function hasAnswerKey(item: ItemNode): boolean {
  switch (item.type) {
    case 'Q01': return !!item.answerKey.optionId && item.interaction.options.some((o) => o.id === item.answerKey.optionId);
    case 'Q02': return item.answerKey.optionIds.length > 0;
    case 'Q03': return item.answerKey.value === true || item.answerKey.value === false;
    case 'Q04': return item.interaction.statements.length > 0 && item.interaction.statements.every((s) => typeof item.answerKey.values[s.id] === 'boolean');
    case 'Q05': return item.interaction.left.length > 0 && item.interaction.left.every((l) => !!item.answerKey.pairs[l.id]);
    case 'Q06': return item.interaction.items.length > 1 && item.answerKey.order.length === item.interaction.items.length;
    case 'Q07': return item.interaction.blanks.length > 0 && item.interaction.blanks.every((b) => (item.answerKey.blanks[b.id] ?? []).some((v) => v.trim()));
    case 'Q08': return item.interaction.blanks.length > 0 && item.interaction.blanks.every((b) => !!(item.answerKey.blanks[b.id] ?? '').trim());
    case 'Q09': return item.interaction.dropdowns.length > 0 && item.interaction.dropdowns.every((d) => d.options.some((o) => o.id === item.answerKey.choices[d.id]));
    case 'Q10': return item.answerKey.accepted.some((v) => v.trim());
    case 'Q11': return !!item.answerKey.expression?.trim() && !!item.interaction.variables?.length || typeof item.answerKey.value === 'number' && Number.isFinite(item.answerKey.value)
      || (!!item.answerKey.range && Number.isFinite(item.answerKey.range.min) && Number.isFinite(item.answerKey.range.max));
    case 'Q12': case 'Q22': return true;
    default: return MANUAL_ONLY_TYPES.includes(item.type) || Object.keys(item.answerKey).length > 0;
  }
}

/** Whether any part of an answer key has been filled in (used to warn before an incompatible type change). */
export function hasAnyKeyContent(item: ItemNode): boolean {
  switch (item.type) {
    case 'Q01': return !!item.answerKey.optionId;
    case 'Q02': return item.answerKey.optionIds.length > 0;
    case 'Q03': return item.answerKey.value != null;
    case 'Q04': return Object.keys(item.answerKey.values).length > 0;
    case 'Q05': return item.interaction.left.some((l) => l.text.trim()) || item.interaction.right.some((r) => r.text.trim());
    case 'Q06': return item.interaction.items.some((x) => x.text.trim());
    case 'Q07': return Object.values(item.answerKey.blanks).some((v) => v.length > 0);
    case 'Q08': return Object.values(item.answerKey.blanks).some((v) => !!v);
    case 'Q09': return Object.keys(item.answerKey.choices).length > 0;
    case 'Q10': return item.answerKey.accepted.length > 0;
    case 'Q11': return item.answerKey.value != null || !!item.answerKey.range;
    case 'Q12': case 'Q22': return false;
    default: return Object.keys(item.answerKey).length > 0;
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Type conversion

const CHOICE_FAMILY: ItemTypeCode[] = ['Q01', 'Q02'];
const TEXT_FAMILY: ItemTypeCode[] = ['Q07', 'Q08', 'Q10'];

/** Accepted text answers per blank of a Q07/Q08/Q10 item. */
function acceptedLists(item: ItemNode): string[][] {
  if (item.type === 'Q07') return item.interaction.blanks.map((b) => item.answerKey.blanks[b.id] ?? []);
  if (item.type === 'Q08') return item.interaction.blanks.map((b) => (item.answerKey.blanks[b.id] ? [item.answerKey.blanks[b.id]] : []));
  if (item.type === 'Q10') return [item.answerKey.accepted];
  return [];
}

/**
 * Converts an item to another type. Q01↔Q02 keep the options and key; Q07↔Q08↔Q10 keep the accepted answers; any other change
 * resets interaction and key and keeps prompt, points, feedback, difficulty, outcome and tags. `lostKey` = a filled key was dropped.
 */
export function convertItemType(item: ItemNode, newType: ItemTypeCode): { item: ItemNode; lostKey: boolean } {
  if (item.type === newType) return { item, lostKey: false };
  const blank = blankItem(newType, item.id);
  const keep = {
    id: item.id, prompt: item.prompt, promptBlocks: item.promptBlocks ?? [], points: item.points, feedback: item.feedback ?? '',
    difficulty: item.difficulty ?? null, outcome: item.outcome ?? '', tags: item.tags ?? [],
  };
  const grading: GradingSpec = { ...blank.grading, rubricId: blank.grading.mode !== 'AUTO' ? item.grading.rubricId ?? null : null };

  if (CHOICE_FAMILY.includes(item.type) && CHOICE_FAMILY.includes(newType) && (item.type === 'Q01' || item.type === 'Q02')) {
    const options = item.interaction.options;
    const shuffleOptions = item.interaction.shuffleOptions;
    if (newType === 'Q02') {
      const optionIds = item.type === 'Q01' ? (item.answerKey.optionId ? [item.answerKey.optionId] : []) : item.answerKey.optionIds;
      return { item: { ...blank, ...keep, grading, type: 'Q02', interaction: { options, shuffleOptions }, answerKey: { optionIds } } as ItemNode, lostKey: false };
    }
    const ids = item.type === 'Q02' ? item.answerKey.optionIds : [];
    return { item: { ...blank, ...keep, grading, type: 'Q01', interaction: { options, shuffleOptions }, answerKey: { optionId: ids[0] ?? null } } as ItemNode, lostKey: ids.length > 1 };
  }

  if (TEXT_FAMILY.includes(item.type) && TEXT_FAMILY.includes(newType)) {
    const lists = acceptedLists(item);
    if (newType === 'Q10') {
      const prompt = item.prompt.split(BLANK_TOKEN).join('…');
      return { item: { ...blank, ...keep, prompt, grading: { ...grading, normalization: item.grading.normalization ?? grading.normalization }, type: 'Q10', interaction: { maxLength: null }, answerKey: { accepted: lists[0] ?? [] } } as ItemNode, lostKey: lists.length > 1 };
    }
    let prompt = item.prompt;
    if (countBlanks(prompt) === 0) prompt = prompt ? `${prompt} ${BLANK_TOKEN}` : BLANK_TOKEN;
    const n = Math.max(1, countBlanks(prompt));
    const blanks = Array.from({ length: n }, (_, i) => ({ id: `b${i + 1}` }));
    const normalization = item.grading.normalization ?? grading.normalization;
    if (newType === 'Q07') {
      const key = Object.fromEntries(blanks.map((b, i) => [b.id, lists[i] ?? []]));
      return { item: { ...blank, ...keep, prompt, grading: { ...grading, normalization }, type: 'Q07', interaction: { blanks }, answerKey: { blanks: key } } as ItemNode, lostKey: false };
    }
    const key: Record<string, string> = {};
    let lost = false;
    blanks.forEach((b, i) => { const l = lists[i] ?? []; if (l[0]) key[b.id] = l[0]; if (l.length > 1) lost = true; });
    const distractors = item.type === 'Q08' ? item.interaction.distractors : [];
    return { item: { ...blank, ...keep, prompt, grading: { ...grading, normalization }, type: 'Q08', interaction: { blanks, distractors }, answerKey: { blanks: key } } as ItemNode, lostKey: lost };
  }

  return { item: { ...blank, ...keep, grading } as ItemNode, lostKey: hasAnyKeyContent(item) };
}

/** Whether converting `from` → `to` keeps the answer key without asking. */
export function isCompatibleConversion(from: ItemTypeCode, to: ItemTypeCode): boolean {
  return (CHOICE_FAMILY.includes(from) && CHOICE_FAMILY.includes(to)) || (TEXT_FAMILY.includes(from) && TEXT_FAMILY.includes(to));
}

/** Makes the blanks / dropdowns of a Q07/Q08/Q09 follow the number of `___` in the prompt (existing ids kept, extra keys pruned). */
export function syncBlanks(item: ItemNode): ItemNode {
  if (!BLANK_TYPES.includes(item.type)) return item;
  const n = countBlanks(item.prompt);
  const nextId = (prefix: string, used: Set<string>) => { let k = 1; while (used.has(`${prefix}${k}`)) k += 1; const id = `${prefix}${k}`; used.add(id); return id; };
  if (item.type === 'Q07' || item.type === 'Q08') {
    const cur = item.interaction.blanks;
    if (cur.length === n) return item;
    const used = new Set(cur.map((b) => b.id));
    const blanks = cur.slice(0, n);
    while (blanks.length < n) blanks.push({ id: nextId('b', used) });
    const keep = new Set(blanks.map((b) => b.id));
    if (item.type === 'Q07') {
      const key = Object.fromEntries(blanks.map((b) => [b.id, item.answerKey.blanks[b.id] ?? []]));
      return { ...item, interaction: { ...item.interaction, blanks }, answerKey: { blanks: key } };
    }
    const key = Object.fromEntries(Object.entries(item.answerKey.blanks).filter(([k]) => keep.has(k)));
    return { ...item, interaction: { ...item.interaction, blanks }, answerKey: { blanks: key } };
  }
  if (item.type === 'Q09') {
    const cur = item.interaction.dropdowns;
    if (cur.length === n) return item;
    const used = new Set(cur.map((d) => d.id));
    const dropdowns = cur.slice(0, n);
    while (dropdowns.length < n) dropdowns.push({ id: nextId('d', used), options: idTexts('o', 2) });
    const keep = new Set(dropdowns.map((d) => d.id));
    const choices = Object.fromEntries(Object.entries(item.answerKey.choices).filter(([k]) => keep.has(k)));
    return { ...item, interaction: { dropdowns }, answerKey: { choices } };
  }
  return item;
}

// ---------------------------------------------------------------------------------------------------------------
// Skills, learner projection

/** Same derivation as the backend (plan §2.3 notes). */
export function deriveSkills(doc: AssessmentDocument): string[] {
  let listening = false, reading = false, speaking = false, writing = false;
  for (const s of doc.sections) {
    for (const nd of s.nodes) {
      const list = nd.kind === 'GROUP' ? nd.children : [nd];
      if (nd.kind === 'GROUP' && nd.groupType === 'Q21') listening = true;
      if (nd.kind === 'GROUP' && nd.groupType === 'Q13') reading = true;
      for (const it of list) {
        if (it.type === 'Q22') speaking = true;
        if (it.type === 'Q12') writing = true;
      }
    }
  }
  const out: string[] = [];
  if (listening) out.push('listening');
  if (reading) out.push('reading');
  if (speaking) out.push('speaking');
  if (writing) out.push('writing');
  return out.length ? out : ['gv'];
}

/** Public projection of an item (no key, feedback, rubric…) — the client-side twin of the backend LearnerDocumentMapper, for previews. */
export function toLearnerItem(item: ItemNode, label: string): LearnerItem {
  const base = { kind: 'ITEM' as const, id: item.id, type: item.type, label, prompt: item.prompt, promptBlocks: item.promptBlocks ?? [], points: item.points, gradingMode: item.grading.mode };
  switch (item.type) {
    case 'Q01': case 'Q02': return { ...base, interaction: { options: item.interaction.options.map((o) => ({ id: o.id, text: o.text, assetKey: o.assetKey ?? null,audioAssetKey:o.audioAssetKey??null })) } };
    case 'Q03': return { ...base, interaction: {} };
    case 'Q04': return { ...base, interaction: { statements: item.interaction.statements.map((s) => ({ id: s.id, text: s.text })) } };
    case 'Q05': return { ...base, interaction: { left: item.interaction.left, right: item.interaction.right } };
    case 'Q06': return { ...base, interaction: { items: item.interaction.items } };
    case 'Q07': return { ...base, interaction: { blanks: item.interaction.blanks } };
    case 'Q08': {
      const words = [...Object.values(item.answerKey.blanks), ...item.interaction.distractors].filter((w) => w && w.trim());
      return { ...base, interaction: { blanks: item.interaction.blanks, bank: words.sort((a, b) => a.localeCompare(b, 'vi')) } };
    }
    case 'Q09': return { ...base, interaction: { dropdowns: item.interaction.dropdowns } };
    case 'Q10': return { ...base, interaction: { maxLength: item.interaction.maxLength ?? null } };
    case 'Q11': return { ...base, interaction: { unit: item.interaction.unit ?? null } };
    case 'Q12': return { ...base, interaction: { minWords: item.interaction.minWords ?? null, maxWords: item.interaction.maxWords ?? null } };
    case 'Q22': return { ...base, interaction: { recording: item.interaction.recording } };
    default: return {...base,interaction:structuredClone(item.interaction)};
  }
}

/** Unshuffled learner projection of a whole document (local preview fallback; the real preview comes from the server). */
export function toLearnerDocument(doc: AssessmentDocument, subjectName: string | null = null): LearnerDocument {
  const ix = indexDocument(doc);
  const audio=(key?:string|null)=>{const a=key?doc.assets[key]:undefined;return a && key?{assetKey:key,name:a.name,durationMs:a.durationMs??null,languageCode:a.languageCode??null}:null;};
  const sections: LearnerSection[] = doc.sections.map((s) => ({
    id: s.id, title: s.title, instructions: s.instructions,audio:audio(s.audioAssetKey),playback:s.playback, points: ix.sectionPoints[s.id] ?? 0,
    nodes: s.nodes.map((nd): LearnerGroup | LearnerItem => {
      if (nd.kind === 'ITEM') return toLearnerItem(nd, ix.labels[nd.id]);
      const asset = nd.audioAssetKey ? doc.assets[nd.audioAssetKey] : undefined;
      return {
        kind: 'GROUP', id: nd.id, groupType: nd.groupType, label: ix.labels[nd.id], title: nd.title, instructions: nd.instructions,
        stimulus: nd.stimulus, audio: asset && nd.audioAssetKey ? { assetKey: nd.audioAssetKey, name: asset.name, durationMs: asset.durationMs ?? null, languageCode: asset.languageCode ?? null } : null,
        playback: nd.playback, keepStimulusVisible: nd.keepStimulusVisible, display: nd.display, points: ix.points[nd.id] ?? 0,
        children: nd.children.map((c) => toLearnerItem(c, ix.labels[c.id])),
      };
    }),
  }));
  return {
    title: doc.title, kind: doc.kind, grade: doc.grade, subjectCode: doc.subjectCode, subjectName, languageCode: doc.languageCode,
    itemCount: ix.count, totalPoints: ix.total, sections,audio:audio(doc.audioAssetKey),playback:doc.playback, images: {}, audios:Object.fromEntries(Object.entries(doc.assets).filter(([,a])=>a.type==='AUDIO').map(([key])=>[key,audio(key)!])),
  };
}

/** Every asset key referenced by the document (groups' audio, blocks, options). */
export function referencedAssetKeys(doc: AssessmentDocument): Set<string> {
  const keys = new Set<string>();
  if(doc.audioAssetKey)keys.add(doc.audioAssetKey);doc.sections.forEach(s=>{if(s.audioAssetKey)keys.add(s.audioAssetKey);});
  const blocks = (list: ContentBlock[] | undefined) => (list ?? []).forEach((b) => { if ((b.type === 'IMAGE' || b.type === 'AUDIO') && b.assetKey) keys.add(b.assetKey); });
  const item = (it: ItemNode) => {
    blocks(it.promptBlocks);
    if (it.type === 'Q01' || it.type === 'Q02') it.interaction.options.forEach((o) => { if (o.assetKey) keys.add(o.assetKey);if(o.audioAssetKey)keys.add(o.audioAssetKey); });
  };
  for (const s of doc.sections) for (const nd of s.nodes) {
    if (nd.kind === 'GROUP') { if (nd.audioAssetKey) keys.add(nd.audioAssetKey); blocks(nd.stimulus); nd.children.forEach(item); } else item(nd);
  }
  return keys;
}
