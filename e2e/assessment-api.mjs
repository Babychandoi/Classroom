import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
const BASE=process.env.BASE_URL||'http://127.0.0.1:8080';
if(!['localhost','127.0.0.1'].includes(new URL(BASE).hostname))throw Error('Local server required');
const run=Date.now().toString(36),password='Aa1!'+crypto.randomBytes(12).toString('hex');
if(fs.existsSync('.artifacts/assessment-qa-state.json')){const previous=JSON.parse(fs.readFileSync('.artifacts/assessment-qa-state.json','utf8'));fs.copyFileSync('.artifacts/assessment-qa-state.json',`.artifacts/assessment-qa-state-${previous.run}.json`);}
async function call(token,method,path,body,status=200){const response=await fetch(BASE+'/api/v1'+path,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:body===undefined?undefined:JSON.stringify(body)});const json=await response.json();assert.equal(response.status,status,method+' '+path+': '+json.error?.message);return json.data;}
const meta={schemaVersion:1,title:'Assessment QA '+run,kind:'QUIZ',grade:null,subjectCode:null,languageCode:null,topicTitle:null,assets:{}};
const item=(id,type,prompt,interaction,answerKey,mode='AUTO')=>({kind:'ITEM',id,type,prompt,promptBlocks:[],points:1,grading:{mode,partialCredit:true},interaction,answerKey,tags:['Tính toán'],outcome:'Giải quyết vấn đề'});
const doc={...meta,sections:[{id:'s1',title:'Phần I',instructions:'',shuffle:false,nodes:[
  item('q1','Q01','1+2?',{options:[{id:'a',text:'2'},{id:'b',text:'3'}],shuffleOptions:true},{optionId:'b'}),
  item('q2','Q19','Khai triển (x+1)^2',{},{accepted:['x^2+2*x+1']}),
  item('q3','Q26','In tổng hai số',{language:'python',starterCode:'',examples:[{input:'1 2',output:'3'}]},{tests:[{input:'1 2',output:'3'},{input:'7 8',output:'15'}]})
]}]};
async function main(){
 const owner=await call(null,'POST','/auth/register',{email:`assessment.owner.${run}@example.com`,fullName:'Assessment QA Owner',password});
 const student=await call(null,'POST','/auth/register',{email:`assessment.student.${run}@example.com`,fullName:'Assessment QA Student',password});
 const outsider=await call(null,'POST','/auth/register',{email:`assessment.outsider.${run}@example.com`,fullName:'Assessment QA Outsider',password});
 const classroom=await call(owner.token,'POST','/classes',{title:'Assessment QA '+run,description:'Temporary verification class',category:'Ôn thi',visibility:'PUBLIC',accessType:'FREE'});
 await call(student.token,'POST',`/classes/${classroom.id}/join`);
 const catalog=await call(owner.token,'GET','/assessment-catalog');assert.equal(catalog.types.filter(t=>t.available).length,28);console.log('PASS Full catalog available');
 const assessment=await call(owner.token,'POST',`/classes/${classroom.id}/assessments`,{...meta,document:doc});
 fs.mkdirSync('.artifacts',{recursive:true});fs.writeFileSync('.artifacts/assessment-qa-state.json',JSON.stringify({run,password,ownerEmail:owner.email,studentEmail:student.email,classId:classroom.id,slug:classroom.slug,assessmentId:assessment.id},null,2));
 await call(student.token,'GET',`/assessments/${assessment.id}`,undefined,403);
 await call(outsider.token,'GET',`/assessments/${assessment.id}`,undefined,403);console.log('PASS Authoring permission isolation');
 const valid=await call(owner.token,'POST',`/assessments/${assessment.id}/validate`,{});assert.equal(valid.ready,true);
 const assignment=await call(owner.token,'POST',`/assessments/${assessment.id}/assign`,{revision:1,timeLimitMinutes:30,maxAttempts:2,resultVisibility:'IMMEDIATE',allowLate:false,shuffle:true});
 const starts=await Promise.all(Array.from({length:5},()=>call(student.token,'POST',`/assessment-assignments/${assignment.id}/attempts`)));assert.equal(new Set(starts.map(a=>a.attemptId)).size,1);const attempt=starts[0];
 const learner=JSON.stringify(attempt.document);assert(!learner.includes('answerKey'));assert(!learner.includes('7 8'));console.log('PASS Concurrent start, hidden keys and hidden tests');
 await call(outsider.token,'GET',`/assessment-attempts/${attempt.attemptId}`,undefined,404);
 const response={q1:{optionId:'b'},q2:{text:'(x+1)^2'},q3:{code:'a,b=map(int,input().split());print(a+b)'}};
 const saved=await call(student.token,'PUT',`/assessment-attempts/${attempt.attemptId}/answers`,{revision:attempt.revision,answers:response});
 await call(student.token,'PUT',`/assessment-attempts/${attempt.attemptId}/answers`,{revision:attempt.revision,answers:{q1:{optionId:'a'}}},409);
 const resumed=await call(student.token,'GET',`/assessment-attempts/${attempt.attemptId}`);assert.equal(resumed.revision,saved.revision);assert.deepEqual(resumed.answers,response);console.log('PASS Autosave, resume and stale revision rejection');
 const trial=await call(student.token,'POST',`/assessment-attempts/${attempt.attemptId}/items/q3/run-code`,response.q3);assert.equal(trial.passed,1);assert.equal(trial.total,1);
 const changed=structuredClone(doc);changed.sections[0].nodes[0].answerKey.optionId='a';await call(owner.token,'PUT',`/assessments/${assessment.id}`,{revision:1,document:changed});
 await call(student.token,'POST',`/assessment-attempts/${attempt.attemptId}/submit`);
 let result;for(let i=0;i<45;i++){result=await call(student.token,'GET',`/assessment-attempts/${attempt.attemptId}/result`);if(result.status==='GRADED')break;await new Promise(resolve=>setTimeout(resolve,1000));}
 assert.equal(result.totalScore,3);assert.equal(result.status,'GRADED');assert(!JSON.stringify(result).includes('7 8'));console.log('PASS Immutable assigned key and sandbox grading');
 const ownCopy=await call(student.token,'POST','/privacy/me/export',{password});assert.equal(ownCopy.assessmentAttempts.length,1);assert.equal(ownCopy.assessmentAnswers.length,3);assert.equal(ownCopy.assessmentAttempts[0].total_score,3);assert(!JSON.stringify(ownCopy).includes('grading_json'));assert(!JSON.stringify(ownCopy).includes('answerKey'));const otherCopy=await call(outsider.token,'POST','/privacy/me/export',{password});assert.deepEqual(otherCopy.assessmentAttempts,[]);assert.deepEqual(otherCopy.assessmentAnswers,[]);console.log('PASS Privacy export includes only own assessment work and published grades');
 await call(student.token,'PUT',`/assessment-attempts/${attempt.attemptId}/answers/q1/grade`,{points:0},403);
 const stats=await call(owner.token,'GET',`/assessment-assignments/${assignment.id}/stats`);assert.equal(stats.gradedCount,1);assert.equal(stats.skills.length,2);
 const exportFile=await call(owner.token,'GET',`/assessments/${assessment.id}/export?format=xlsx`);
 const imported=await call(owner.token,'POST',`/classes/${classroom.id}/assessment-import/preview`,{filename:'roundtrip.xlsx',data:exportFile.data});assert.equal(imported.itemCount,3);
 const matrix=await call(owner.token,'POST',`/classes/${classroom.id}/assessment-matrix/preview`,{title:'Matrix',seed:3,cells:[{type:'Q01',count:1,points:2},{type:'Q26',count:1,points:3}]});assert.equal(matrix.ready,true);assert.equal(matrix.totalPoints,5);
 const unavailable=await call(owner.token,'POST',`/classes/${classroom.id}/assessment-matrix/preview`,{title:'Missing',cells:[{type:'Q14',count:3}]});assert.equal(unavailable.ready,false);console.log('PASS Skill reports, XLSX round trip and matrix coverage');
 fs.writeFileSync('.artifacts/assessment-qa-state.json',JSON.stringify({run,password,ownerEmail:owner.email,studentEmail:student.email,classId:classroom.id,slug:classroom.slug,assessmentId:assessment.id,assignmentId:assignment.id,attemptId:attempt.attemptId},null,2));
 console.log('PASS Assessment API verification completed');
}
main().catch(error=>{console.error(error.message);process.exitCode=1;});
