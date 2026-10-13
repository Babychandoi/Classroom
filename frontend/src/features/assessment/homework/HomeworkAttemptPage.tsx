import { useCallback,useEffect,useRef,useState } from 'react';
import { Link,useNavigate,useParams,useSearchParams } from 'react-router-dom';
import { ArrowLeft,Check,CloudOff,Clock } from 'lucide-react';
import { useAuth } from '../../../context/AuthContext';
import * as service from '../api';
import type { AttemptDto,ItemResponse } from '../types';
import { AssessmentBody } from '../components/AssessmentBody';
import { AssessmentDialog } from '../components/AssessmentDialog';
import { answeredCount } from '../model/answers';
import { ASSESSMENT_PATHS } from '../routes';
import { mmss } from '../format';

export function HomeworkAttemptPage(){
  const {slug='',assignmentId=''}=useParams(),[search]=useSearchParams(),navigate=useNavigate(),{user}=useAuth();
  const [conflict,setConflict]=useState(false),[recovery,setRecovery]=useState<Record<string,ItemResponse|null>|null>(null),[attempt,setAttempt]=useState<AttemptDto|null>(null),[answers,setAnswers]=useState<Record<string,ItemResponse|null>>({}),[error,setError]=useState(''),[loading,setLoading]=useState(true),[saved,setSaved]=useState(true),[confirm,setConfirm]=useState(false),[submitting,setSubmitting]=useState(false),[remaining,setRemaining]=useState<number|null>(null);
  const state=useRef<AttemptDto|null>(null),pending=useRef<Record<string,ItemResponse|null>>({}),flight=useRef<Promise<void>|null>(null),storage=useRef(''),clockOffset=useRef(0),expired=useRef(false);
  const showResult=useCallback((id:string)=>navigate(ASSESSMENT_PATHS.homeworkResult(slug,assignmentId,id),{replace:true}),[slug,assignmentId,navigate]);
  useEffect(()=>{let live=true;setLoading(true);setError('');const id=search.get('attempt');
    (id?service.getAttempt(id):service.startAttempt(assignmentId)).then(data=>{
      if(!live)return;if(data.status!=='IN_PROGRESS'){showResult(data.attemptId);return;}
      state.current=data;clockOffset.current=Date.parse(data.serverNow)-Date.now();storage.current=`assessment-answer-draft:${user?.id}:${data.attemptId}`;
      let local:Record<string,ItemResponse|null>={};try{const raw=localStorage.getItem(storage.current);if(raw){const parsed=JSON.parse(raw);if(parsed.savedAt>Date.now()-86400000 && parsed.answers && typeof parsed.answers==='object'){if(parsed.revision===data.revision)local=parsed.answers;else setRecovery(parsed.answers);}}}catch{/* unavailable storage */}
      const known=new Set(data.document.sections.flatMap(s=>s.nodes.flatMap(n=>n.kind==='ITEM'?[n.id]:n.children.map(c=>c.id))));
      local=Object.fromEntries(Object.entries(local).filter(([key])=>known.has(key)));pending.current=local;setSaved(Object.keys(local).length===0);setAnswers({...data.answers,...local});setAttempt(data);
    }).catch(e=>{if(live)setError(e.message);}).finally(()=>{if(live)setLoading(false);});return()=>{live=false;};
  },[assignmentId,search,user?.id,showResult]);
  const cache=()=>{try{if(storage.current){if(Object.keys(pending.current).length)localStorage.setItem(storage.current,JSON.stringify({savedAt:Date.now(),revision:state.current?.revision,answers:pending.current}));else localStorage.removeItem(storage.current);}}catch{/* server autosave still works */}};
  const flush=useCallback(async()=>{
    if(flight.current)await flight.current;
    if(!state.current || Object.keys(pending.current).length===0)return;
    if(state.current.revision<0)throw Error('Bài làm đã thay đổi ở phiên khác. Hãy tải lại và kiểm tra bản nháp trên máy.');
    const patch={...pending.current},data=state.current;
    const promise=(async()=>{try{
      const result=await service.autosaveAnswers(data.attemptId,{revision:data.revision,answers:patch});
      data.revision=result.revision;for(const [key,value]of Object.entries(patch))if(JSON.stringify(pending.current[key])===JSON.stringify(value))delete pending.current[key];cache();setSaved(Object.keys(pending.current).length===0);setError('');
    }catch(e){setSaved(false);cache();if(e instanceof Error && e.message.includes('ATTEMPT_REVISION_CONFLICT')){setConflict(true);if(state.current)state.current.revision=-1;}throw e;}finally{flight.current=null;}})();flight.current=promise;await promise;
  },[]);
  useEffect(()=>{const tick=setInterval(()=>{void flush().catch(e=>setError(e.message||'Mất kết nối. Câu trả lời được giữ trên máy và sẽ lưu lại khi kết nối trở lại.'));},1200);
    const online=()=>{void flush().catch(e=>setError(e.message));};window.addEventListener('online',online);
    return()=>{clearInterval(tick);window.removeEventListener('online',online);};
  },[flush]);
  useEffect(()=>{if(!attempt?.deadlineAt)return;const tick=()=>{const left=Math.max(0,Math.ceil((Date.parse(attempt.deadlineAt!)-Date.now()-clockOffset.current)/1000));setRemaining(left);if(left===0 && !expired.current){expired.current=true;
      void (async()=>{try{if(flight.current)await flight.current;const result=await service.submitAttempt(attempt.attemptId);try{localStorage.removeItem(storage.current);}catch{/* noop */}showResult(result.attemptId);}catch(e){setError(e instanceof Error?e.message:'Không thể xác nhận bài hết giờ');}})();
    }};tick();const timer=setInterval(tick,1000);return()=>clearInterval(timer);},[attempt?.attemptId,attempt?.deadlineAt,showResult]);
  const change=(id:string,response:ItemResponse|null)=>{pending.current[id]=response;setAnswers(old=>({...old,[id]:response}));setSaved(false);cache();};
  const submit=async()=>{if(!attempt)return;setSubmitting(true);setError('');try{while(Object.keys(pending.current).length)await flush();const result=await service.submitAttempt(attempt.attemptId);try{localStorage.removeItem(storage.current);}catch{/* noop */}showResult(result.attemptId);}catch(e){setError(e instanceof Error?e.message:'Không thể nộp bài');}finally{setSubmitting(false);}};
  if(loading)return <p role="status" className="p-6">Đang mở bài tập…</p>;
  if(!attempt)return <div role="alert" className="p-6 text-red-800">{error || 'Không tìm thấy bài tập'}<Link className="ml-3 underline" to={ASSESSMENT_PATHS.classHomework(slug)}>Về bài tập</Link></div>;
  const items=attempt.document.sections.flatMap(s=>s.nodes.flatMap(n=>n.kind==='ITEM'?[n]:n.children)),count=answeredCount(items,answers);
  return <div className="min-h-screen bg-slate-100"><header className="sticky top-0 z-30 flex flex-wrap items-center gap-3 border-b border-slate-200 bg-white px-4 py-3">
    <Link aria-label="Về danh sách bài tập" className="flex h-10 w-10 items-center justify-center rounded-full bg-slate-100" to={ASSESSMENT_PATHS.classHomework(slug)}><ArrowLeft size={18}/></Link>
    <div className="min-w-0 flex-1"><h1 className="truncate text-title-sm font-bold">{attempt.document.title}</h1><p className="text-caption text-slate-500">Lượt {attempt.attemptNo} · {count}/{items.length} câu đã trả lời</p></div>
    <span aria-live="polite" className={`flex items-center gap-1 text-caption ${saved?'text-green-700':'text-amber-700'}`}>{saved?<Check size={14}/>:<CloudOff size={14}/>} {saved?'Đã lưu':'Chưa đồng bộ'}</span>
    {remaining!=null && <span className={`flex items-center gap-1.5 rounded-full px-3 py-2 font-mono text-ui ${remaining<60?'bg-red-50 text-red-800':'bg-slate-100'}`}><Clock size={15}/>{mmss(remaining)}</span>}
    <button className="rounded-full bg-slate-900 px-5 py-2.5 text-ui font-semibold text-white disabled:opacity-40" disabled={submitting || remaining===0} onClick={()=>setConfirm(true)}>Nộp bài</button>
  </header>
    <main className="mx-auto max-w-6xl p-4 sm:p-6">{error && <div role="alert" className="mb-4 rounded-xl bg-amber-50 p-4 text-meta text-amber-900">{error}<button className="ml-2 font-semibold underline" onClick={()=>void flush().catch(e=>setError(e.message))}>Lưu lại</button></div>}
      {conflict && <p className="mb-4 rounded-xl border border-amber-300 bg-amber-50 p-3 text-meta">Phiên khác đã cập nhật bài làm. Bản nháp được giữ trên máy.<button className="ml-2 underline" onClick={()=>window.location.reload()}>Tải lại để đối chiếu</button></p>}
      {recovery && <div className="mb-4 rounded-xl border border-amber-300 p-3 text-meta">Có bản nháp chưa đồng bộ từ phiên cũ.<button className="ml-2 underline" onClick={()=>{const known=new Set(items.map(i=>i.id));for(const [id,response]of Object.entries(recovery))if(known.has(id))change(id,response);setRecovery(null);}}>Khôi phục câu trả lời vào phiên hiện tại</button><button className="ml-3 underline" onClick={()=>{setRecovery(null);localStorage.removeItem(storage.current);}}>Bỏ qua</button></div>}
      <AssessmentBody document={attempt.document} answers={answers} onChange={change} plays={attempt.plays} recordings={attempt.recordings} recordingTakes={attempt.recordingTakes}
        onPlay={async key=>{const result=await service.registerPlay(attempt.attemptId,key);setAttempt(a=>a?{...a,plays:{...a.plays,[key]:result.plays}}:a);return result;}}
        onUploadRecording={async(id,file,ms,name)=>{await flush();const info=await service.uploadRecording(attempt.attemptId,id,file,ms,name);if(info.revision!=null && state.current)state.current.revision=info.revision;setAttempt(a=>a?{...a,recordings:{...a.recordings,[id]:info}}:a);return info;}}
        onDeleteRecording={async id=>{await flush();await service.deleteRecording(attempt.attemptId,id);const latest=await service.getAttempt(attempt.attemptId);state.current=latest;setAttempt(latest);}}
        onRecordingUrl={async id=>(await service.recordingUrl(attempt.attemptId,id)).url}
        onUploadEvidence={(id,file)=>service.uploadEvidence(attempt.attemptId,id,file)} onEvidenceUrl={async(id,asset)=>(await service.evidenceUrl(attempt.attemptId,id,asset)).url}
        onRunCode={(id,code)=>service.runCode(attempt.attemptId,id,code)}/>
    </main>{confirm && <AssessmentDialog title="Nộp bài tập?" onClose={()=>setConfirm(false)} footer={<><button className="rounded-full border px-4 py-2 text-ui" onClick={()=>setConfirm(false)}>Tiếp tục làm</button><button disabled={submitting} className="rounded-full bg-slate-900 px-5 py-2 text-ui font-bold text-white" onClick={()=>void submit()}>{submitting?'Đang nộp…':'Xác nhận nộp'}</button></>}><p className="text-body-sm">Bạn đã trả lời {count}/{items.length} câu.{count<items.length?` Còn ${items.length-count} câu chưa trả lời.`:''} Bài đã nộp sẽ được chấm theo chính sách của giáo viên.</p></AssessmentDialog>}
  </div>;
}

export default HomeworkAttemptPage;
