import { useEffect,useState } from 'react';
import { Link,useOutletContext,useParams } from 'react-router-dom';
import { ClipboardList,Clock,ArrowRight } from 'lucide-react';
import type { Classroom } from '../../../types';
import type { HomeworkCard } from '../types';
import { listHomework,type HomeworkFilter } from '../api';
import { KIND_LABELS } from '../catalog';
import { Tag,Seg } from '../components/primitives';
import { ASSESSMENT_PATHS } from '../routes';

const STATUS:Record<string,string>={NOT_STARTED:'Chưa làm',IN_PROGRESS:'Đang làm',SUBMITTED:'Chờ chấm',GRADED:'Đã chấm',RETURNED:'Đã trả bài',UPCOMING:'Chưa mở',MISSED:'Quá hạn',CLOSED:'Đã đóng'};
export function HomeworkTab(){
  const {classroom}=useOutletContext<{classroom:Classroom}>();const {slug=''}=useParams();
  const [filter,setFilter]=useState<HomeworkFilter>('all'),[items,setItems]=useState<HomeworkCard[]>([]),[loading,setLoading]=useState(true),[error,setError]=useState(''),[retry,setRetry]=useState(0);
  useEffect(()=>{let live=true;setLoading(true);setError('');listHomework(classroom.id,filter).then(rows=>{if(live)setItems(rows);}).catch(e=>{if(live)setError(e.message);}).finally(()=>{if(live)setLoading(false);});return()=>{live=false;};},[classroom.id,filter,retry]);
  return <div className="space-y-5"><div className="flex flex-wrap items-center justify-between gap-3"><div><h1 className="text-heading font-bold">Bài tập</h1><p className="mt-1 text-meta text-slate-500">Luyện tập, nộp bài và theo dõi nhận xét của giáo viên.</p></div><Seg label="Lọc bài tập" value={filter} onChange={setFilter} items={[["all","Tất cả"],["todo","Cần làm"],["done","Đã nộp"],["upcoming","Sắp mở"]]}/></div>
    {error && <div role="alert" className="rounded-xl bg-red-50 p-4 text-red-800">{error} <button className="ml-2 underline" onClick={()=>setRetry(n=>n+1)}>Thử lại</button></div>}
    {loading?<p role="status">Đang tải bài tập…</p>:items.length===0?<div className="rounded-2xl border border-slate-200 bg-white p-10 text-center"><ClipboardList className="mx-auto mb-3 text-slate-500"/><h2 className="font-bold">Chưa có bài tập ở mục này</h2><p className="mt-1 text-meta text-slate-500">Bài giáo viên giao sẽ xuất hiện tại đây.</p></div>:<div className="grid gap-4 xl:grid-cols-2">{items.map(item=>{
      const done=['SUBMITTED','GRADED','RETURNED'].includes(item.status),blocked=['UPCOMING','MISSED','CLOSED'].includes(item.status);
      return <article key={item.assignmentId} className="rounded-2xl border border-slate-200 bg-white p-5"><div className="flex items-start gap-3"><span className="flex h-11 w-11 flex-none items-center justify-center rounded-xl bg-slate-100"><ClipboardList size={20}/></span><div className="min-w-0 flex-1"><div className="mb-1 flex flex-wrap gap-2"><Tag label={KIND_LABELS[item.kind]}/><Tag label={STATUS[item.status]} color={done?'green':item.status==='IN_PROGRESS'?'blue':'gray'}/></div><h2 className="text-title font-bold">{item.title}</h2></div></div>
        <div className="my-4 flex flex-wrap gap-x-4 gap-y-1 text-meta text-slate-500"><span>{item.itemCount} câu · {item.totalPoints} điểm</span>{item.timeLimitMinutes>0 && <span>{item.timeLimitMinutes} phút</span>}<span>Lượt {item.attemptsUsed}/{item.maxAttempts || '∞'}</span></div>
        {item.dueAt && <p className="flex items-center gap-1.5 text-meta text-slate-600"><Clock size={14}/> Hạn nộp {new Date(item.dueAt).toLocaleString('vi-VN')}</p>}
        <div className="mt-4 flex items-center justify-between border-t border-slate-100 pt-4"><span className="text-meta font-semibold">{item.score!=null?`${item.score}/${item.totalPoints} điểm`:item.status==='IN_PROGRESS'?`Đã trả lời ${item.answeredCount}/${item.itemCount}`:blocked?STATUS[item.status]:''}</span>
          {done && item.canRetry && <Link className="rounded-full border px-3 py-2 text-ui font-semibold" to={ASSESSMENT_PATHS.homeworkAttempt(slug,item.assignmentId)}>Làm lượt mới</Link>}
          {!blocked && <Link className="inline-flex items-center gap-2 rounded-full bg-slate-900 px-4 py-2 text-ui font-semibold text-white" to={done?ASSESSMENT_PATHS.homeworkResult(slug,item.assignmentId,item.attemptId):ASSESSMENT_PATHS.homeworkAttempt(slug,item.assignmentId)}>{done?'Xem bài':item.status==='IN_PROGRESS'?'Tiếp tục':'Làm bài'}<ArrowRight size={15}/></Link>}
        </div></article>;
    })}</div>}
  </div>;
}

export default HomeworkTab;
