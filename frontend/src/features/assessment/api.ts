import { api } from '../../api/client';
import { putToObjectStore } from '../../api/upload';
import type { ValidationIssue,
  AssessmentDocument, AssessmentSummary, AssignmentDto, AssignmentStats, AssignRequest, AttemptDto, AttemptResult, AuthoringAssessment,
  AutosaveRequest, AutosaveResponse, BankItemSummary, CatalogDto, CreateAssessmentRequest, FinalizeRequest, GradeAnswerRequest,
  GradingAnswer, GradingAttemptDto, HomeworkCard, ItemNode, AssetRef, LearnerDocument, MyAttemptSummary, PlayResponse, RecordingInfo,
  RecordingIntentResponse, Rubric, SaveAssessmentResponse, SignedUrl, SubjectDto, SubmissionRow, ValidateResponse,
} from './types';

// One function per endpoint of plan §2.2 (base /api/v1; ApiResponse unwrapped by api/client).

const enc = encodeURIComponent;

/** `audio/webm;codecs=opus` → `audio/webm` (the server allow-list has bare types). */
export const bareMime = (mime: string) => (mime || '').split(';')[0].trim().toLowerCase();

// ---- Catalog -------------------------------------------------------------------------------------------------------
export const getCatalog = () => api.get<CatalogDto>('/assessment-catalog');
export const getSubjects = (grade: number) => api.get<SubjectDto[]>(`/assessment-catalog/subjects?grade=${grade}`);
export const createRubric=(classId:string,body:Rubric)=>api.post<Rubric>(`/classes/${classId}/rubrics`,body);
export const getRubrics = (classId: string) => api.get<Rubric[]>(`/classes/${classId}/rubrics`);

// ---- Authoring -----------------------------------------------------------------------------------------------------
export const listAssessments = (classId: string, params: { status?: 'DRAFT' | 'ARCHIVED'; q?: string } = {}) => {
  const qs = new URLSearchParams();
  if (params.status) qs.set('status', params.status);
  if (params.q) qs.set('q', params.q);
  const s = qs.toString();
  return api.get<AssessmentSummary[]>(`/classes/${classId}/assessments${s ? `?${s}` : ''}`);
};
export const createAssessment = (classId: string, body: CreateAssessmentRequest) => api.post<AuthoringAssessment>(`/classes/${classId}/assessments`, body);
export const getAssessment = (id: string) => api.get<AuthoringAssessment>(`/assessments/${id}`);
export const saveAssessment = (id: string, revision: number, document: AssessmentDocument) =>
  api.put<SaveAssessmentResponse>(`/assessments/${id}`, { revision, document });
export const validateAssessment = (id: string, document?: AssessmentDocument) =>
  api.post<ValidateResponse>(`/assessments/${id}/validate`, document ? { document } : {});
export const previewAssessment = (id: string) => api.get<LearnerDocument>(`/assessments/${id}/preview`);
export const assessmentMediaUrl = (id: string, assetId: string) => api.get<SignedUrl>(`/assessments/${id}/media/${assetId}/url`);
export const duplicateAssessment = (id: string) => api.post<AuthoringAssessment>(`/assessments/${id}/duplicate`);
export const deleteAssessment = (id: string) => api.delete<unknown>(`/assessments/${id}`);
export const listQuestionBank = (classId: string, params: { q?: string; type?: string; mode?: string; grade?: number; subjectCode?: string; page?: number; size?: number } = {}) => {
  const qs = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') qs.set(k, String(v)); });
  const s = qs.toString();
  return api.get<BankItemSummary[]>(`/classes/${classId}/question-bank${s ? `?${s}` : ''}`);
};
export const getBankItem = (id: string) => api.get<{ item: ItemNode; assets: Record<string, AssetRef>;document:AssessmentDocument }>(`/question-bank-items/${id}`);

// ---- Assign --------------------------------------------------------------------------------------------------------
export const assignAssessment = (id: string, body: AssignRequest) => api.post<AssignmentDto>(`/assessments/${id}/assign`, body);
export const listAssignments = (classId: string) => api.get<AssignmentDto[]>(`/classes/${classId}/assessment-assignments`);
export const patchAssignment = (id: string, body: { dueAt?: string | null; status?: 'CLOSED' | 'OPEN' }) => api.patch<AssignmentDto>(`/assessment-assignments/${id}`, body);
export const listSegments = (classId: string) => api.get<Array<{ id: string; name?: string; title?: string }>>(`/classes/${classId}/segments`);

// ---- Student -------------------------------------------------------------------------------------------------------
export type HomeworkFilter = 'todo' | 'done' | 'upcoming' | 'all';
export const listHomework = (classId: string, filter: HomeworkFilter = 'all') => api.get<HomeworkCard[]>(`/classes/${classId}/homework?filter=${filter}`);
export const startAttempt = (assignmentId: string) => api.post<AttemptDto>(`/assessment-assignments/${assignmentId}/attempts`);
export const getAttempt = (attemptId: string) => api.get<AttemptDto>(`/assessment-attempts/${attemptId}`);
export const autosaveAnswers = (attemptId: string, body: AutosaveRequest, init?: RequestInit) =>
  api.put<AutosaveResponse>(`/assessment-attempts/${attemptId}/answers`, body, init);
export const registerPlay = (attemptId: string, assetKey: string) => api.post<PlayResponse>(`/assessment-attempts/${attemptId}/plays`, { assetKey });
export const recordingIntent = (attemptId: string, itemId: string, body: { filename: string; mimeType: string; sizeBytes: number; durationMs: number }) =>
  api.post<RecordingIntentResponse>(`/assessment-attempts/${attemptId}/items/${enc(itemId)}/recording-intent`, body);
export const recordingComplete = (attemptId: string, itemId: string, body: { assetId: string; durationMs: number }) =>
  api.post<RecordingInfo>(`/assessment-attempts/${attemptId}/items/${enc(itemId)}/recording-complete`, body);
export const deleteRecording = (attemptId: string, itemId: string) => api.delete<unknown>(`/assessment-attempts/${attemptId}/items/${enc(itemId)}/recording`);
export const recordingUrl = (attemptId: string, itemId: string) => api.get<SignedUrl>(`/assessment-attempts/${attemptId}/items/${enc(itemId)}/recording-url`);
export const evidenceUrl = (attemptId: string,itemId: string,assetId: string) => api.get<SignedUrl>(`/assessment-attempts/${attemptId}/items/${enc(itemId)}/evidence/${enc(assetId)}/url`);
export async function uploadEvidence(attemptId:string,itemId:string,file:File):Promise<string>{
  const intent=await api.post<RecordingIntentResponse>(`/assessment-attempts/${attemptId}/items/${enc(itemId)}/evidence-intent`,{filename:file.name,mimeType:bareMime(file.type || 'application/octet-stream'),sizeBytes:file.size,durationMs:0});
  await putToObjectStore(intent.uploadUrl,file,'Không thể tải minh chứng');
  await api.post(`/assessment-attempts/${attemptId}/items/${enc(itemId)}/evidence-complete`,{assetId:intent.assetId,durationMs:0});return intent.assetId;
}
export const runCode = (attemptId:string,itemId:string,code:string) => api.post<{output:string;passed:number;total:number}>(`/assessment-attempts/${attemptId}/items/${enc(itemId)}/run-code`,{code});
export const submitAttempt = (attemptId: string) => api.post<AttemptResult>(`/assessment-attempts/${attemptId}/submit`);
export const getAttemptResult = (attemptId: string) => api.get<AttemptResult>(`/assessment-attempts/${attemptId}/result`);
export const reviewAudio = (attemptId:string,assetKey:string) => api.get<SignedUrl>(`/assessment-attempts/${attemptId}/review-audio/${encodeURIComponent(assetKey)}`);
export const myAttempts = (assignmentId: string) => api.get<MyAttemptSummary[]>(`/assessment-assignments/${assignmentId}/my-attempts`);

/** Speaking upload: recording-intent → PUT to the object store → recording-complete. */
export async function uploadRecording(attemptId: string, itemId: string, file: File | Blob, durationMs: number, filename = 'ghi-am.webm'): Promise<RecordingInfo> {
  const mimeType = bareMime(file.type || 'audio/webm');
  const named = file instanceof File ? file : new File([file], filename, { type: mimeType });
  const intent = await recordingIntent(attemptId, itemId, { filename: named.name, mimeType, sizeBytes: named.size, durationMs: Math.round(durationMs) });
  const body = named.type === mimeType ? named : new File([named], named.name, { type: mimeType });
  await putToObjectStore(intent.uploadUrl, body, 'Không thể tải bản ghi âm');
  return recordingComplete(attemptId, itemId, { assetId: intent.assetId, durationMs: Math.round(durationMs) });
}

// ---- Grading -------------------------------------------------------------------------------------------------------
export const listSubmissions = (assignmentId: string) => api.get<SubmissionRow[]>(`/assessment-assignments/${assignmentId}/submissions`);
export const getGrading = (attemptId: string) => api.get<GradingAttemptDto>(`/assessment-attempts/${attemptId}/grading`);
export const gradeAnswer = (attemptId: string, itemId: string, body: GradeAnswerRequest) =>
  api.put<GradingAnswer>(`/assessment-attempts/${attemptId}/answers/${enc(itemId)}/grade`, body);
export const finalizeAttempt = (attemptId: string, body: FinalizeRequest) => api.post<SubmissionRow>(`/assessment-attempts/${attemptId}/finalize`, body);
export const releaseAssignment = (assignmentId: string) => api.post<{ released: number }>(`/assessment-assignments/${assignmentId}/release`);
export const getAssignmentStats = (assignmentId: string) => api.get<AssignmentStats>(`/assessment-assignments/${assignmentId}/stats`);

// ---- Teacher media (existing media endpoints, purpose ASSESSMENT) --------------------------------------------------
export const AUDIO_MIME = ['audio/mpeg', 'audio/mp3', 'audio/mp4', 'audio/x-m4a', 'audio/m4a', 'audio/wav', 'audio/x-wav', 'audio/wave', 'audio/ogg', 'audio/webm'];
export const IMAGE_MIME = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];
export const AUDIO_MAX_BYTES = 50 * 1024 * 1024;
export const IMAGE_MAX_BYTES = 5 * 1024 * 1024;

/** Why `file` cannot be uploaded as an assessment asset of `kind`, or null. */
export function assetFileProblem(file: File, kind: 'AUDIO' | 'IMAGE'): string | null {
  const mime = bareMime(file.type);
  if (kind === 'AUDIO') {
    if (!AUDIO_MIME.includes(mime)) return 'Tệp nghe phải là MP3, M4A, WAV, OGG hoặc WebM.';
    if (file.size > AUDIO_MAX_BYTES) return 'Tệp nghe tối đa 50 MB.';
  } else {
    if (!IMAGE_MIME.includes(mime)) return 'Ảnh phải là JPG, PNG, WebP hoặc GIF.';
    if (file.size > IMAGE_MAX_BYTES) return 'Ảnh tối đa 5 MB.';
  }
  return null;
}

/** Uploads a teacher asset (audio/image) and resolves to the media asset id. */
export async function uploadAssessmentAsset(classId: string, file: File, kind: 'AUDIO' | 'IMAGE'): Promise<{ assetId: string; mime: string }> {
  const problem = assetFileProblem(file, kind);
  if (problem) throw new Error(problem);
  const mimeType = bareMime(file.type) === 'audio/mp3' ? 'audio/mpeg' : bareMime(file.type);
  const intent = await api.post<{ assetId: string; uploadUrl: string }>(`/classes/${classId}/media/upload-intents`, {
    purpose: 'ASSESSMENT', filename: file.name, mimeType, sizeBytes: file.size,
  });
  const body = file.type === mimeType ? file : new File([file], file.name, { type: mimeType });
  await putToObjectStore(intent.uploadUrl, body, kind === 'AUDIO' ? 'Không thể tải tệp nghe' : 'Không thể tải ảnh');
  await api.post(`/media/${intent.assetId}/complete`);
  return { assetId: intent.assetId, mime: mimeType };
}

/** Duration (ms) of an audio file read in the browser, or null when it cannot be decoded. */
export function probeAudioDuration(file: File): Promise<number | null> {
  return new Promise((resolve) => {
    try {
      if (typeof URL.createObjectURL !== 'function' || typeof Audio === 'undefined') { resolve(null); return; }
      const url = URL.createObjectURL(file);
      const audio = new Audio();
      const done = (v: number | null) => { try { URL.revokeObjectURL(url); } catch { /* ignore */ } resolve(v); };
      audio.preload = 'metadata';
      audio.onloadedmetadata = () => done(Number.isFinite(audio.duration) ? Math.round(audio.duration * 1000) : null);
      audio.onerror = () => done(null);
      setTimeout(() => done(null), 4000);
      audio.src = url;
    } catch {
      resolve(null);
    }
  });
}

export interface ExportFile {data:string;mime:string;filename:string;warnings:string[]}
export const exportAssessment=(id:string,format:string)=>api.get<ExportFile>(`/assessments/${id}/export?format=${enc(format)}`);
export const importTemplate=(classId:string,format:string)=>api.get<ExportFile>(`/classes/${classId}/assessment-import/template?format=${enc(format)}`);
export const previewMatrix=(classId:string,body:unknown)=>api.post<{document:AssessmentDocument;coverage:{requested:number;selected:number}[];ready:boolean}>(`/classes/${classId}/assessment-matrix/preview`,body);
export type EmbeddedImportAssets=Record<string,{name:string;mime:string;bytes:string}>;
export async function previewImport(classId:string,file:File){
  if(file.size>10*1024*1024)throw Error('Tệp import tối đa 10 MB');
  const bytes=new Uint8Array(await file.arrayBuffer());let binary='';for(let i=0;i<bytes.length;i+=8192)binary+=String.fromCharCode(...bytes.subarray(i,i+8192));
  return api.post<{document:AssessmentDocument;warnings:string[];issues:ValidationIssue[];embeddedAssets:EmbeddedImportAssets}>(`/classes/${classId}/assessment-import/preview`,{filename:file.name,data:btoa(binary)});
}
export function downloadFile(file:ExportFile){const bytes=Uint8Array.from(atob(file.data),c=>c.charCodeAt(0)),url=URL.createObjectURL(new Blob([bytes],{type:file.mime})),a=document.createElement('a');a.href=url;a.download=file.filename;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
export const questionSuggestions=(classId:string,outcome:string,type='',grade:number|null=null,subjectCode:string|null=null)=>api.get<BankItemSummary[]>(`/classes/${classId}/question-suggestions?outcome=${enc(outcome)}&type=${enc(type)}${grade?`&grade=${grade}`:''}${subjectCode?`&subjectCode=${enc(subjectCode)}`:''}`);
