import { useState } from 'react';
import type { Rubric } from '../types';
import { createRubric } from '../api';
import { AssessmentDialog } from './AssessmentDialog';
import { Field,fieldClass } from '../builder/QuestionForm';

export function RubricEditor({classId,source,onClose,onCreated}:{classId:string;source?:Rubric;onClose:()=>void;onCreated:(rubric:Rubric)=>void}) {
  const [title,setTitle]=useState(source?`${source.title} (bản riêng)`:'Rubric của lớp'),[levels,setLevels]=useState(source?.levels.join('\n')??'Chưa đạt\nĐạt một phần\nĐạt\nTốt'),[criteria,setCriteria]=useState(source?.criteria.map(c=>({...c}))??[{id:'c1',title:'Đáp ứng yêu cầu',weight:60},{id:'c2',title:'Trình bày',weight:40}]),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  const total=criteria.reduce((sum,c)=>sum+c.weight,0);
  const save=async()=>{setBusy(true);setError('');try{const rubric=await createRubric(classId,{id:'',title,levels:levels.split('\n').map(v=>v.trim()).filter(Boolean),criteria,system:false});onCreated(rubric);}catch(e){setError(e instanceof Error?e.message:'Không lưu được rubric');}finally{setBusy(false);}};
  return <AssessmentDialog title="Tạo rubric của lớp" onClose={onClose} footer={<button disabled={busy || Math.abs(total-100)>0.0001} className="rounded-full bg-slate-900 px-4 py-2 font-semibold text-white disabled:opacity-40" onClick={()=>void save()}>{busy?'Đang lưu…':'Lưu rubric mới'}</button>}>
    <div className="space-y-4">{error && <p role="alert" className="text-red-800">{error}</p>}<Field label="Tên rubric"><input className={fieldClass} maxLength={200} value={title} onChange={e=>setTitle(e.target.value)}/></Field>
      <Field label="Các mức, mỗi dòng một mức, từ thấp đến cao"><textarea className={fieldClass} rows={4} value={levels} onChange={e=>setLevels(e.target.value)}/></Field>
      {criteria.map((c,i)=><div key={c.id} className="grid grid-cols-[1fr_90px_40px] items-end gap-2"><Field label={`Tiêu chí ${i+1}`}><input className={fieldClass} maxLength={200} value={c.title} onChange={e=>setCriteria(old=>old.map((v,j)=>j===i?{...v,title:e.target.value}:v))}/></Field><Field label="Trọng số %"><input className={fieldClass} type="number" min={0.01} max={100} step={0.01} value={c.weight} onChange={e=>setCriteria(old=>old.map((v,j)=>j===i?{...v,weight:Number(e.target.value)}:v))}/></Field><button aria-label={`Xóa tiêu chí ${i+1}`} className="h-11 rounded-xl border text-red-700" onClick={()=>setCriteria(old=>old.filter((_,j)=>j!==i))}>×</button></div>)}
      <button className="text-ui font-semibold text-blue-700" disabled={criteria.length>=30} onClick={()=>setCriteria(old=>[...old,{id:`c${crypto.randomUUID().slice(0,8)}`,title:'',weight:0}])}>+ Thêm tiêu chí</button>
      <p className={total===100?'text-meta text-green-800':'text-meta text-amber-800'}>Tổng trọng số: {total}% / 100%.</p><p className="text-caption text-slate-500">Rubric được lưu thành bản riêng. Bài đã giao giữ rubric tại thời điểm giao.</p>
    </div>
  </AssessmentDialog>;
}
