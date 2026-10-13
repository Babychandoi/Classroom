import type { AssessmentDocument, PlaybackPolicy, SectionNode } from '../types';
import { newId } from './doc';

/** Rebase identities and asset keys; retain the selected child's shared context. */
export function bankFragment(document:AssessmentDocument):{section:SectionNode;assets:AssessmentDocument['assets']} {
  const keys=new Map(Object.keys(document.assets).map(key=>[key,newId('asset')]));
  const copy=(value:unknown):unknown=>{
    if(Array.isArray(value))return value.map(copy);
    if(value && typeof value==='object')return Object.fromEntries(Object.entries(value).map(([key,v])=>[key,
      ['assetKey','audioAssetKey','backgroundAssetKey'].includes(key)&&typeof v==='string'?keys.get(v)??v:copy(v)]));
    return value;
  };
  const section=copy(document.sections[0]) as SectionNode;section.id=newId('s');
  section.nodes.forEach(node=>{node.id=newId(node.kind==='GROUP'?'g':'q');if(node.kind==='GROUP')node.children.forEach(q=>q.id=newId('q'));});
  if(document.audioAssetKey){
    const rootAudio=keys.get(document.audioAssetKey)!;
    if(!section.audioAssetKey){section.audioAssetKey=rootAudio;section.playback=document.playback;}
    else if(section.audioAssetKey!==rootAudio){
      section.playback=strictPolicy(section.playback,document.playback);
      for(const node of section.nodes){const block={type:'AUDIO' as const,assetKey:rootAudio};if(node.kind==='GROUP')node.stimulus=[block,...node.stimulus];else node.promptBlocks=[block,...node.promptBlocks??[]];}
    }
  }
  const assets=Object.fromEntries(Object.entries(document.assets).map(([key,a])=>[keys.get(key)!,a]));
  return {section,assets};
}
function strictPolicy(a?:PlaybackPolicy,b?:PlaybackPolicy):PlaybackPolicy {
  const limits=[a?.maxPlays??0,b?.maxPlays??0].filter(n=>n>0);
  return {maxPlays:limits.length?Math.min(...limits):0,allowSeek:!!a?.allowSeek&&!!b?.allowSeek,allowSpeed:!!a?.allowSpeed&&!!b?.allowSpeed,autoPlay:!!a?.autoPlay||!!b?.autoPlay,pauseBeforeAnswerSec:Math.max(a?.pauseBeforeAnswerSec??0,b?.pauseBeforeAnswerSec??0)};
}
