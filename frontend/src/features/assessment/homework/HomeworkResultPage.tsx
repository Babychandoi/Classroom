import { useEffect,useState } from 'react';
import { Link,useParams,useSearchParams } from 'react-router-dom';
import type { AttemptResult,MyAttemptSummary } from '../types';
import { getAttemptResult,myAttempts,recordingUrl,evidenceUrl,reviewAudio } from '../api';
import { AssessmentBody } from '../components/AssessmentBody';
import { ASSESSMENT_PATHS } from '../routes';
import { fmtPts } from '../format';

export function HomeworkResultPage(){
  const {slug='',assignmentId=''}=useParams(),[search,setSearch]=useSearchParams();const [result,setResult]=useState<AttemptResult|null>(null),[history,setHistory]=useState<MyAttemptSummary[]>([]),[error,setError]=useState(''),[loading,setLoading]=useState(true);
  useEffect(()=>{let live=true;setLoading(true);setError('');void(async()=>{try{const list=await myAttempts(assignmentId);if(!live)return;setHistory(list);const id=search.get('attempt')||list.find(a=>a.status!=='IN_PROGRESS')?.attemptId;if(!id){setError('Bạn chưa nộp bài tập này.');return;}const data=await getAttemptResult(id);if(live)setResult(data);}catch(e){if(live)setError(e instanceof Error?e.message:'Không thể tải kết quả');}finally{if(live)setLoading(false);}})();return()=>{live=false;};},[assignmentId,search]);
  useEffect(()=>{if(!result || result.status!=='SUBMITTED')return;const timer=setInterval(()=>{void getAttemptResult(result.attemptId).then(setResult).catch(()=>{/* retry next poll */});},5000);return()=>clearInterval(timer);},[result?.attemptId,result?.status]);
  if(loading)return <p role="status">Đang tải kết quả…</p>;
  return <div className="space-y-5"><Link className="text-meta font-semibold text-blue-700" to={ASSESSMENT_PATHS.classHomework(slug)}>← Bài tập</Link>{error && <p role="alert" className="rounded-xl bg-red-50 p-4 text-red-800">{error}</p>}{result && <>
    <div className="rounded-2xl border border-slate-200 bg-white p-5"><div className="flex flex-wrap items-start justify-between gap-4"><div><h1 className="text-heading font-bold">{result.document.title}</h1><p className="mt-1 text-meta text-slate-500">Nộp lúc {new Date(result.submittedAt).toLocaleString('vi-VN')}{result.late?' · Nộp muộn':''}</p></div><select aria-label="Chọn lượt làm" className="rounded-xl border p-2 text-ui" value={result.attemptId} onChange={e=>setSearch({attempt:e.target.value})}>{history.filter(a=>a.status!=='IN_PROGRESS').map(a=><option key={a.attemptId} value={a.attemptId}>Lượt {a.attemptNo}{a.totalScore!=null?` · ${a.totalScore} điểm`:''}</option>)}</select></div>
      <div className="my-5 flex flex-wrap items-baseline gap-2"><strong className="text-4xl">{result.visibility.scores && result.totalScore!=null?fmtPts(result.totalScore):'—'}</strong><span className="text-slate-500">/{fmtPts(result.maxScore)} điểm</span>{result.passFail && <span className="ml-3 rounded-full bg-slate-100 px-3 py-1 text-meta font-bold">{result.passFail==='PASS'?'Đạt':'Chưa đạt'}</span>}</div>
      {!result.visibility.scores && <p className="rounded-xl bg-blue-50 p-3 text-meta text-blue-900">Giáo viên chưa công bố điểm và đáp án.</p>}
      {result.pendingManualCount>0 && <p className="mt-2 text-meta text-amber-800">Còn {result.pendingManualCount} câu đang chấm tự động hoặc chờ giáo viên chấm.</p>}
      {result.teacherComment && <p className="mt-3 text-body-sm"><strong>Nhận xét:</strong> {result.teacherComment}</p>}
    </div>
    {result.skills?.length ? <section className="rounded-2xl border bg-white p-5"><h2 className="mb-4 text-title-sm font-bold">Kỹ năng của bạn</h2>{result.skills.map(skill=><div className="mb-3" key={skill.skill}><div className="flex justify-between gap-3 text-meta"><span>{skill.skill}</span><strong>{fmtPts(skill.masteryPercent)}% · {fmtPts(skill.earned)}/{fmtPts(skill.possible)}đ</strong></div><progress aria-label={`Mức đạt ${skill.skill}`} className="h-2 w-full accent-slate-900" max={100} value={skill.masteryPercent??0}/></div>)}</section>:null}
    <AssessmentBody onPlay={result.visibility.answers?async key=>({url:(await reviewAudio(result.attemptId,key)).url}):undefined} document={result.document} answers={Object.fromEntries(result.items.map(i=>[i.itemId,i.response]))} results={result.items}
      onRecordingUrl={async id=>(await recordingUrl(result.attemptId,id)).url} onEvidenceUrl={async(id,asset)=>(await evidenceUrl(result.attemptId,id,asset)).url}/>
    {Object.entries(result.transcripts).map(([id,text])=><div key={id} className="rounded-xl border bg-white p-4"><h2 className="mb-2 font-bold">Lời bài nghe</h2><p className="whitespace-pre-wrap text-body-sm">{text}</p></div>)}
  </>}</div>;
}

export default HomeworkResultPage;
