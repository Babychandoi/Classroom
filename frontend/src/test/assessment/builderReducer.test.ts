import { describe, it, expect } from 'vitest';
import { builderReducer, initialBuilderState, type BuilderState } from '../../features/assessment/model/builderReducer';
import { blankGroup, blankItem, blankSection, findNode, newDocument } from '../../features/assessment/model/doc';
import type { GroupNode } from '../../features/assessment/types';

const META = { title: 'Đề', kind: 'HOMEWORK' as const, grade: 7, subjectCode: 'TOAN', languageCode: null, topicTitle: null };

function loaded(): BuilderState {
  const g: GroupNode = { ...blankGroup('Q13', 'g1'), children: [blankItem('Q01', 'q1'), blankItem('Q03', 'q2')] };
  const doc = newDocument(META, [{ ...blankSection('Phần I', 's1'), nodes: [g, blankItem('Q10', 'q3')] }]);
  return builderReducer(initialBuilderState, { type: 'LOAD', doc, revision: 4 });
}

describe('builder reducer', () => {
  it('LOAD is clean; edits mark dirty and bump the edit sequence', () => {
    const s0 = loaded();
    expect(s0.dirty).toBe(false);
    expect(s0.revision).toBe(4);
    const s1 = builderReducer(s0, { type: 'PATCH_NODE', id: 'q1', patch: { prompt: 'Hỏi gì?' } });
    expect(s1.dirty).toBe(true);
    expect(s1.editSeq).toBe(1);
    expect(findNode(s1.doc!, 'q1').node).toMatchObject({ prompt: 'Hỏi gì?' });
  });

  it('SAVE_OK with the latest seq clears dirty and stores the new revision', () => {
    let s = builderReducer(loaded(), { type: 'PATCH_NODE', id: 'root', patch: { title: 'Mới' } });
    s = builderReducer(s, { type: 'SAVE_START', seq: s.editSeq });
    expect(s.saving).toBe(true);
    s = builderReducer(s, { type: 'SAVE_OK', revision: 5, savedAt: '2026-10-11T08:00:00Z', seq: s.editSeq, issues: [] });
    expect(s.dirty).toBe(false);
    expect(s.saving).toBe(false);
    expect(s.revision).toBe(5);
    expect(s.lastSavedAt).toBe('2026-10-11T08:00:00Z');
  });

  it('an edit made while saving keeps the state dirty after SAVE_OK', () => {
    let s = builderReducer(loaded(), { type: 'PATCH_NODE', id: 'root', patch: { title: 'A' } });
    const seq = s.editSeq;
    s = builderReducer(s, { type: 'SAVE_START', seq });
    s = builderReducer(s, { type: 'PATCH_NODE', id: 'root', patch: { title: 'AB' } });
    s = builderReducer(s, { type: 'SAVE_OK', revision: 5, savedAt: 'x', seq });
    expect(s.dirty).toBe(true);
    expect(s.revision).toBe(5);
  });

  it('CONFLICT records the server revision', () => {
    const s = builderReducer(loaded(), { type: 'CONFLICT', currentRevision: 9 });
    expect(s.conflict).toEqual({ currentRevision: 9 });
  });

  it('ADD_ITEM / ADD_GROUP select the new node; a group cannot go inside a group', () => {
    let s = builderReducer(loaded(), { type: 'ADD_ITEM', parentId: 'g1', itemType: 'Q11' });
    expect(findNode(s.doc!, s.selectedId).kind).toBe('item');
    const before = s;
    s = builderReducer(s, { type: 'ADD_GROUP', parentId: 'g1', groupType: 'Q21' });
    expect(s).toBe(before);
    s = builderReducer(s, { type: 'ADD_GROUP', parentId: 's1', groupType: 'Q21' });
    expect(findNode(s.doc!, s.selectedId).kind).toBe('group');
  });

  it('REMOVE selects the parent; MOVE / REORDER reorder; CHANGE_TYPE converts', () => {
    let s = builderReducer(loaded(), { type: 'SELECT', id: 'q2' });
    s = builderReducer(s, { type: 'REMOVE', id: 'q2' });
    expect(s.selectedId).toBe('g1');
    s = builderReducer(s, { type: 'MOVE', id: 'q3', dir: -1 });
    expect(s.doc!.sections[0].nodes.map((n) => n.id)).toEqual(['q3', 'g1']);
    s = builderReducer(s, { type: 'REORDER', parentId: 's1', fromId: 'q3', toId: 'g1' });
    expect(s.doc!.sections[0].nodes.map((n) => n.id)).toEqual(['g1', 'q3']);
    s = builderReducer(s, { type: 'CHANGE_TYPE', id: 'q3', newType: 'Q12' });
    expect(findNode(s.doc!, 'q3').node).toMatchObject({ type: 'Q12' });
  });

  it('SET_ASSET attaches the audio to the group and clears server issues', () => {
    let s = builderReducer(loaded(), { type: 'ADD_GROUP', parentId: 's1', groupType: 'Q21' });
    const gid = s.selectedId;
    s = builderReducer(s, { type: 'SET_SERVER_ISSUES', issues: [{ nodeId: gid, level: 'ERROR', code: 'LISTENING_AUDIO_MISSING', message: 'x' }] });
    s = builderReducer(s, { type: 'SET_ASSET', key: 'a1', groupId: gid, asset: { assetId: 'm1', type: 'AUDIO', name: 'a.mp3', mime: 'audio/mpeg' } });
    expect(findNode(s.doc!, gid).node).toMatchObject({ audioAssetKey: 'a1' });
    expect(s.doc!.assets.a1.assetId).toBe('m1');
    expect(s.serverIssues).toEqual([]);
  });

  it('DUPLICATE inserts a copy after the original and selects it', () => {
    const s = builderReducer(loaded(), { type: 'DUPLICATE', id: 'q3' });
    const ids = s.doc!.sections[0].nodes.map((n) => n.id);
    expect(ids).toHaveLength(3);
    expect(ids[2]).toBe(s.selectedId);
    expect(ids[2]).not.toBe('q3');
  });
});
