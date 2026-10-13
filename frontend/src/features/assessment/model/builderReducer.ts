import type { AssessmentDocument, AssetRef, GroupTypeCode, ItemNode, ItemTypeCode, SectionNode, ValidationIssue } from '../types';
import {
  addNode, addSection, blankGroup, blankItem, blankSection, convertItemType, duplicateNode, findNode, moveNode, parentIdOf, patchNode,
  removeNode, reorderSiblings, replaceItem, syncBlanks,
} from './doc';

export interface BuilderState {
  doc: AssessmentDocument | null;
  revision: number;
  selectedId: string;
  dirty: boolean;
  /** Bumped by every edit; a save carries the seq it sent so a save finishing after newer edits keeps `dirty`. */
  editSeq: number;
  saving: boolean;
  saveError: string | null;
  conflict: { currentRevision: number | null } | null;
  lastSavedAt: string | null;
  /** Issues returned by the last save / validate; cleared by any edit (the client validator covers the edited state). */
  serverIssues: ValidationIssue[];
}

export type BuilderAction =
  | { type: 'LOAD'; doc: AssessmentDocument; revision: number; savedAt?: string | null; selectedId?: string }
  | { type: 'SELECT'; id: string }
  | { type: 'REPLACE_DOCUMENT'; doc: AssessmentDocument }
  | { type: 'PATCH_NODE'; id: string; patch: Record<string, unknown> }
  | { type: 'REPLACE_ITEM'; id: string; item: ItemNode }
  | { type: 'ADD_SECTION'; section?: SectionNode }
  | { type: 'ADD_ITEM'; parentId: string; itemType: ItemTypeCode; item?: ItemNode }
  | { type: 'ADD_GROUP'; parentId: string; groupType: GroupTypeCode }
  | { type: 'REMOVE'; id: string }
  | { type: 'DUPLICATE'; id: string }
  | { type: 'REORDER'; parentId: string; fromId: string; toId: string }
  | { type: 'MOVE'; id: string; dir: -1 | 1 }
  | { type: 'CHANGE_TYPE'; id: string; newType: ItemTypeCode }
  | { type: 'SET_ASSET'; key: string; asset: AssetRef | null; groupId?: string | null }
  | { type: 'SAVE_START'; seq: number }
  | { type: 'SAVE_OK'; revision: number; savedAt: string; seq: number; issues?: ValidationIssue[] }
  | { type: 'SAVE_FAIL'; error: string }
  | { type: 'CONFLICT'; currentRevision: number | null }
  | { type: 'SET_SERVER_ISSUES'; issues: ValidationIssue[] };

export const initialBuilderState: BuilderState = {
  doc: null, revision: 0, selectedId: 'root', dirty: false, editSeq: 0, saving: false, saveError: null, conflict: null, lastSavedAt: null, serverIssues: [],
};

const edit = (state: BuilderState, doc: AssessmentDocument, selectedId = state.selectedId): BuilderState =>
  doc === state.doc ? { ...state, selectedId } : { ...state, doc, selectedId, dirty: true, editSeq: state.editSeq + 1, serverIssues: [] };

export function builderReducer(state: BuilderState, action: BuilderAction): BuilderState {
  if (action.type === 'LOAD') {
    return { ...initialBuilderState, doc: action.doc, revision: action.revision, lastSavedAt: action.savedAt ?? null, selectedId: action.selectedId ?? 'root' };
  }
  const doc = state.doc;
  if (!doc) return state;
  switch (action.type) {
    case 'REPLACE_DOCUMENT': return edit(state,action.doc,'root');
    case 'SELECT':
      return { ...state, selectedId: findNode(doc, action.id).kind === 'none' ? 'root' : action.id };
    case 'PATCH_NODE': {
      let next = patchNode(doc, action.id, action.patch);
      if ('prompt' in action.patch) {
        const f = findNode(next, action.id);
        if (f.kind === 'item') next = replaceItem(next, action.id, syncBlanks(f.node));
      }
      return edit(state, next);
    }
    case 'REPLACE_ITEM':
      return edit(state, replaceItem(doc, action.id, syncBlanks(action.item)));
    case 'ADD_SECTION': {
      const s = action.section ?? blankSection(`Phần ${doc.sections.length + 1}`);
      return edit(state, addSection(doc, s), s.id);
    }
    case 'ADD_ITEM': {
      const item = action.item ?? blankItem(action.itemType);
      const next = addNode(doc, action.parentId, item);
      return next === doc ? state : edit(state, next, item.id);
    }
    case 'ADD_GROUP': {
      const f = findNode(doc, action.parentId);
      if (f.kind !== 'section') return state; // a group never goes inside a group
      const g = blankGroup(action.groupType);
      return edit(state, addNode(doc, action.parentId, g), g.id);
    }
    case 'REMOVE': {
      const parent = parentIdOf(doc, action.id);
      if (!parent) return state;
      const next = removeNode(doc, action.id);
      const sel = state.selectedId === action.id || findNode(next, state.selectedId).kind === 'none' ? parent : state.selectedId;
      return edit(state, next, sel);
    }
    case 'DUPLICATE': {
      const r = duplicateNode(doc, action.id);
      return r.newId ? edit(state, r.doc, r.newId) : state;
    }
    case 'REORDER':
      return edit(state, reorderSiblings(doc, action.parentId, action.fromId, action.toId));
    case 'MOVE':
      return edit(state, moveNode(doc, action.id, action.dir));
    case 'CHANGE_TYPE': {
      const f = findNode(doc, action.id);
      if (f.kind !== 'item') return state;
      return edit(state, replaceItem(doc, action.id, convertItemType(f.node, action.newType).item));
    }
    case 'SET_ASSET': {
      const assets = { ...doc.assets };
      if (action.asset) assets[action.key] = action.asset; else delete assets[action.key];
      let next: AssessmentDocument = { ...doc, assets };
      if (action.groupId) next = patchNode(next, action.groupId, { audioAssetKey: action.asset ? action.key : null });
      return edit(state, next);
    }
    case 'SAVE_START':
      return { ...state, saving: true, saveError: null };
    case 'SAVE_OK':
      return {
        ...state, saving: false, saveError: null, conflict: null, revision: action.revision, lastSavedAt: action.savedAt,
        dirty: state.editSeq !== action.seq, serverIssues: state.editSeq === action.seq ? action.issues ?? [] : state.serverIssues,
      };
    case 'SAVE_FAIL':
      return { ...state, saving: false, saveError: action.error };
    case 'CONFLICT':
      return { ...state, saving: false, conflict: { currentRevision: action.currentRevision } };
    case 'SET_SERVER_ISSUES':
      return { ...state, serverIssues: action.issues };
    default:
      return state;
  }
}
