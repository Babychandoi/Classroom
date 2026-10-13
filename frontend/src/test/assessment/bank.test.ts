import { describe,it,expect } from 'vitest';
import { bankFragment } from '../../features/assessment/model/bank';
import { newDocument,blankGroup,blankItem,blankSection,referencedAssetKeys,toLearnerDocument } from '../../features/assessment/model/doc';

describe('question bank context',()=>{
  it('preserves passage, child and audio policy while avoiding source identities and asset collisions',()=>{
    const group={...blankGroup('Q21','group'),audioAssetKey:'audio',children:[blankItem('Q01','question')]};
    const document=newDocument({title:'Ngữ liệu',kind:'QUIZ',grade:null,subjectCode:null,languageCode:null,topicTitle:null},[{...blankSection('Phần','section'),nodes:[group]}]);
    document.assets.audio={assetId:'one',type:'AUDIO',name:'Bài nghe',mime:'audio/wav',transcript:'Secret transcript'};
    const copied=bankFragment(document);const next={...document,assets:copied.assets,sections:[copied.section]};
    expect(copied.section.id).not.toBe('section');expect(copied.section.nodes[0].id).not.toBe('group');
    const g=copied.section.nodes[0];expect(g.kind).toBe('GROUP');if(g.kind!=='GROUP')throw Error('Missing context');
    expect(g.children).toHaveLength(1);expect(g.children[0].id).not.toBe('question');expect(g.audioAssetKey).not.toBe('audio');expect(next.assets[g.audioAssetKey!].assetId).toBe('one');
    expect(referencedAssetKeys(next).has(g.audioAssetKey!)).toBe(true);expect(JSON.stringify(toLearnerDocument(next))).not.toContain('Secret transcript');
  });
});
