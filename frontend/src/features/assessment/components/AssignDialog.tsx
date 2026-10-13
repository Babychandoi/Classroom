import { useEffect,useState } from 'react';
import type { AssignmentDto,AssignmentSettings,ResultVisibility } from '../types';
import { assignAssessment,listSegments,validateAssessment } from '../api';
import { AssessmentDialog } from './AssessmentDialog';

export function AssignDialog({id,classId,title,revision,onClose,onDone}:{id:string;classId:string;title:string;revision:number;onClose:()=>void;onDone:(assignment:AssignmentDto)=>void}) {
  const [segments,setSegments]=useState<{id:string;name?:string;title?:string}[]>([]),[settings,setSettings]=useState<AssignmentSettings>({targetSegmentId:null,openAt:null,dueAt:null,timeLimitMinutes:0,maxAttempts:1,resultVisibility:'MANUAL',allowLate:false,shuffle:true}),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  useEffect(()=>{let live=true;listSegments(classId).then(rows=>{if(live)setSegments(rows);}).catch(()=>{/* all class still available */});return()=>{live=false;};},[classId]);
  const send=async()=>{setBusy(true);setError('');try{const check=await validateAssessment(id);if(!check.ready){setError(check.issues.filter(i=>i.level==='ERROR').map(i=>i.message).join(' · '));return;}onDone(await assignAssessment(id,{...settings,revision}));}catch(e){setError(e instanceof Error?e.message:'Không thể giao bài');}finally{setBusy(false);}};
  const input='mt-1 w-full rounded-xl border border-slate-200 p-2.5 text-ui';
  return <AssessmentDialog title="Giao bài" onClose={onClose} footer={<><button className="rounded-full border px-4 py-2 text-ui" disabled={busy} onClick={onClose}>Hủy</button><button disabled={busy} className="rounded-full bg-slate-900 px-5 py-2 text-ui font-bold text-white disabled:opacity-40" onClick={()=>void send()}>{busy?'Đang giao…':'Giao bài'}</button></>}>
    <div className="space-y-4"><p className="text-title-sm font-semibold">{title}</p>{error && <p role="alert" className="rounded-xl bg-red-50 p-3 text-meta text-red-800">{error}</p>}
      <label className="block text-meta font-semibold">Giao cho<select className={input} value={settings.targetSegmentId??''} onChange={e=>setSettings(s=>({...s,targetSegmentId:e.target.value||null}))}><option value="">Cả lớp</option>{segments.map(s=><option key={s.id} value={s.id}>{s.name||s.title}</option>)}</select></label>
      <div className="grid gap-3 sm:grid-cols-2">{(['openAt','dueAt']as const).map(field=><label className="text-meta font-semibold" key={field}>{field==='openAt'?'Mở lúc':'Hạn nộp'}<input className={input} type="datetime-local" onChange={e=>setSettings(s=>({...s,[field]:e.target.value?new Date(e.target.value).toISOString():null}))}/></label>)}</div>
      <div className="grid grid-cols-2 gap-3"><label className="text-meta font-semibold">Thời gian làm (phút)<input className={input} type="number" min={0} max={1440} value={settings.timeLimitMinutes} onChange={e=>setSettings(s=>({...s,timeLimitMinutes:Number(e.target.value)}))}/><span className="text-caption font-normal text-slate-500">0 = không giới hạn</span></label><label className="text-meta font-semibold">Số lượt làm<input className={input} type="number" min={0} max={100} value={settings.maxAttempts} onChange={e=>setSettings(s=>({...s,maxAttempts:Number(e.target.value)}))}/><span className="text-caption font-normal text-slate-500">0 = không giới hạn</span></label></div>
      <label className="block text-meta font-semibold">Công bố điểm và đáp án<select className={input} value={settings.resultVisibility} onChange={e=>setSettings(s=>({...s,resultVisibility:e.target.value as ResultVisibility}))}><option value="MANUAL">Khi giáo viên trả bài</option><option value="AFTER_DUE">Sau hạn nộp</option><option value="IMMEDIATE">Ngay khi nộp</option></select></label>
      <label className="flex items-center gap-2 text-ui"><input type="checkbox" checked={settings.shuffle} onChange={e=>setSettings(s=>({...s,shuffle:e.target.checked}))}/>Trộn theo cấu hình của từng phần và nhóm</label>
      <label className="flex items-center gap-2 text-ui"><input type="checkbox" checked={settings.allowLate} onChange={e=>setSettings(s=>({...s,allowLate:e.target.checked}))}/>Cho phép nộp muộn</label>
      <p className="rounded-xl bg-slate-100 p-3 text-meta text-slate-600">Đề và rubric được lưu thành phiên bản cố định khi giao. Các lượt làm dùng cùng phiên bản này.</p>
    </div>
  </AssessmentDialog>;
}
