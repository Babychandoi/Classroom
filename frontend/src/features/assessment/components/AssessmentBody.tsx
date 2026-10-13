import React, { useEffect, useState } from 'react';
import type { ItemResponse, ItemResult, LearnerAudio, LearnerDocument, LearnerGroup, LearnerItem, PlaybackPolicy, PlayResponse } from '../types';
import { ContentBlocks, RichLine } from './ContentBlocks';
import { QInput, type RecordingHooks } from './QInput';
import { AudioBar } from './AudioBar';
import { TypeChip } from './primitives';
import { fmtPts } from '../format';

export interface AssessmentBodyProps extends RecordingHooks {
  document: LearnerDocument;
  answers: Record<string, ItemResponse|null>;
  onChange?: (id: string, response: ItemResponse|null) => void;
  plays?: Record<string, number>;
  onPlay?: (key: string) => Promise<PlayResponse|{url:string;plays?:number}|null>;
  results?: ItemResult[];
  preview?: boolean;
  onUploadEvidence?: (id:string,file:File) => Promise<string>;
  onEvidenceUrl?: (id:string,assetId:string) => Promise<string>;
  onRunCode?: (id:string,code:string) => Promise<{output:string;passed?:number;total?:number}>;
}
export function AssessmentBody(props: AssessmentBodyProps) {
  const resultById=new Map(props.results?.map(r=>[r.itemId,r]));
  const audio=(key:string)=><ScopedAudio key={key} audio={props.document.audios?.[key]??{assetKey:key,name:'Tệp nghe',durationMs:null,languageCode:null}} {...props}/>;
  const item=(q:LearnerItem)=> <article key={q.id} id={`question-${q.id}`} className="scroll-mt-24 rounded-2xl border border-slate-200 bg-white p-4 sm:p-5">
    <div className="mb-3 flex items-center gap-2"><span className="text-ui font-bold">{q.label}</span><TypeChip code={q.type}/><span className="ml-auto text-meta font-semibold text-slate-500">{fmtPts(q.points)} điểm</span></div>
    {!['Q07','Q08','Q09'].includes(q.type) && <div className="mb-4 text-body-sm font-medium leading-6"><RichLine text={q.prompt}/></div>}
    <ContentBlocks blocks={q.promptBlocks} images={props.document.images} renderAudio={audio}/>
    <div className="mt-3"><QInput item={q} value={props.answers[q.id]} onChange={v=>props.onChange?.(q.id,v)} result={resultById.get(q.id)} preview={props.preview}
      recordings={props.recordings} recordingTakes={props.recordingTakes} onUploadRecording={props.onUploadRecording} onDeleteRecording={props.onDeleteRecording} onRecordingUrl={props.onRecordingUrl}
      images={props.document.images} renderAudio={audio} onUploadEvidence={props.onUploadEvidence} onEvidenceUrl={props.onEvidenceUrl} onRunCode={props.onRunCode}/></div>
    {resultById.get(q.id)?.feedback && <p className="mt-4 rounded-xl bg-blue-50 p-3 text-meta text-blue-900">{resultById.get(q.id)?.feedback}</p>}
    {resultById.get(q.id)?.teacherComment && <p className="mt-3 text-meta"><strong>Nhận xét:</strong> {resultById.get(q.id)?.teacherComment}</p>}
  </article>;
  return <AudioScope audio={props.document.audio} policy={props.document.playback} {...props}><div className="space-y-7">{props.document.sections.map(section=><section key={section.id}>
    <div className="mb-4"><h2 className="text-heading-sm font-bold">{section.title}</h2>{section.instructions && <p className="mt-1 text-meta text-slate-600">{section.instructions}</p>}</div>
    <AudioScope audio={section.audio} policy={section.playback} {...props}><div className="space-y-4">{section.nodes.map(node=>node.kind==='ITEM'?item(node):<Group key={node.id} group={node} renderItem={item} {...props}/>)}</div></AudioScope>
  </section>)}</div></AudioScope>;
}
function Group({group,renderItem,...props}:AssessmentBodyProps & {group:LearnerGroup;renderItem:(q:LearnerItem)=>React.ReactNode}) {
  const [current,setCurrent]=useState(0);
  const children=group.display==='ONE' && !props.results ? group.children.slice(current,current+1):group.children;
  return <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,.85fr)_minmax(0,1.15fr)]">
    <aside className={`rounded-2xl border border-slate-200 bg-white p-4 sm:p-5 ${group.keepStimulusVisible?'lg:sticky lg:top-24':''}`}>
      <div className="mb-2 flex items-center gap-2"><TypeChip code={group.groupType}/><span className="text-caption font-semibold text-slate-500">{group.label}</span></div>
      <h3 className="mb-2 text-title-sm font-bold">{group.title}</h3>
      {group.instructions && <p className="mb-3 text-meta text-slate-600">{group.instructions}</p>}
      <ContentBlocks blocks={group.stimulus} images={props.document.images} renderAudio={key=><ScopedAudio audio={props.document.audios?.[key]??{assetKey:key,name:'Tệp nghe',durationMs:null,languageCode:null}} {...props}/>}/>
    </aside>
    <AudioScope audio={group.audio} policy={group.playback} {...props}><div className="space-y-4">{children.map(renderItem)}{group.display==='ONE' && !props.results && <div className="flex flex-wrap items-center justify-between gap-2">
      <button className="rounded-xl border px-4 py-2 text-ui disabled:opacity-40" disabled={current===0} onClick={()=>setCurrent(i=>i-1)}>Câu trước</button>
      <span className="text-meta">{current+1}/{group.children.length}</span><button className="rounded-xl border px-4 py-2 text-ui disabled:opacity-40" disabled={current>=group.children.length-1} onClick={()=>setCurrent(i=>i+1)}>Câu tiếp</button>
    </div>}</div></AudioScope>
  </div>;
}

function ScopedAudio({audio,policy,onPlaybackState,...props}:AssessmentBodyProps & {audio:LearnerAudio;policy?:PlaybackPolicy;onPlaybackState?:(state:'playing'|'ended'|'error')=>void}) {
  return <AudioBar name={audio.name} durationMs={audio.durationMs} languageCode={audio.languageCode} policy={props.results?{maxPlays:0,allowSeek:true,allowSpeed:true,autoPlay:false,pauseBeforeAnswerSec:0}:audio.playback??policy}
    mode={props.preview || props.results?'preview':'student'} plays={props.plays?.[audio.assetKey]??0} disabled={!props.onPlay}
    onPlaybackState={onPlaybackState} onRequestPlay={()=>props.onPlay?.(audio.assetKey)??Promise.resolve(null)}/>;
}
function AudioScope({audio,policy,children,...props}:AssessmentBodyProps & {audio?:LearnerAudio|null;policy?:PlaybackPolicy;children:React.ReactNode}) {
  const effective=audio?.playback??policy;
  const [waiting,setWaiting]=useState(false),[until,setUntil]=useState(0),[remaining,setRemaining]=useState(0);
  useEffect(()=>{if(!until)return;const tick=()=>{const left=Math.max(0,Math.ceil((until-Date.now())/1000));setRemaining(left);if(!left){setWaiting(false);setUntil(0);}};tick();const timer=setInterval(tick,250);return()=>clearInterval(timer);},[until]);
  const state=(value:'playing'|'ended'|'error')=>{if(props.results)return;if(value==='playing' && (effective?.pauseBeforeAnswerSec??0)>0){setUntil(0);setWaiting(true);}else if(value==='ended' && (effective?.pauseBeforeAnswerSec??0)>0)setUntil(Date.now()+effective!.pauseBeforeAnswerSec*1000);else if(value==='error'){setWaiting(false);setUntil(0);}};
  return <div className="space-y-4">{audio && <ScopedAudio audio={audio} policy={policy} onPlaybackState={state} {...props}/>}
    {waiting && <p role="status" className="rounded-xl bg-amber-50 p-3 text-meta text-amber-900">{remaining?`Bắt đầu trả lời sau ${remaining} giây.`:'Nghe xong để bắt đầu trả lời.'}</p>}
    <fieldset disabled={waiting} className="min-w-0 space-y-4">{children}</fieldset>
  </div>;
}
