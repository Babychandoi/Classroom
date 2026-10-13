// Contract shared with the backend (plan R1 §2.3). Keep in sync with the Java DTOs; do not edit shapes unilaterally.
export type QuestionTypeCode = 'Q01'|'Q02'|'Q03'|'Q04'|'Q05'|'Q06'|'Q07'|'Q08'|'Q09'|'Q10'|'Q11'|'Q12'|'Q13'|'Q14'|'Q15'|'Q16'|'Q17'|'Q18'|'Q19'|'Q20'|'Q21'|'Q22'|'Q23'|'Q24'|'Q25'|'Q26'|'Q27'|'Q28';
export type BasicItemTypeCode = 'Q01'|'Q02'|'Q03'|'Q04'|'Q05'|'Q06'|'Q07'|'Q08'|'Q09'|'Q10'|'Q11'|'Q12'|'Q22';
export type ItemTypeCode = Exclude<QuestionTypeCode, 'Q13'|'Q21'>;
export type AdvancedItemTypeCode = Exclude<ItemTypeCode, BasicItemTypeCode>;
export type GroupTypeCode = 'Q13'|'Q21';
export type GradingMode = 'AUTO'|'MANUAL'|'HYBRID'|'PASS_FAIL';
export type AssessmentKind = 'HOMEWORK'|'QUIZ'|'REVIEW'|'WORKSHEET'|'PRACTICE'; // Bài tập|Kiểm tra|Ôn tập|Phiếu học tập|Thực hành
export type TranscriptVisibility = 'NEVER'|'AFTER_SUBMISSION'|'AFTER_DUE';
export type ResultVisibility = 'IMMEDIATE'|'AFTER_DUE'|'MANUAL';
export type Difficulty = 'NB'|'TH'|'VD'|'VDC';
export type PassFail = 'PASS'|'FAIL';
export interface PersonRef { id: string; fullName: string; avatarUrl?: string|null }

export interface AssetRef { assetId: string; type: 'AUDIO'|'IMAGE'|'VIDEO'|'DOCUMENT'; name: string; mime: string; sizeBytes?: number|null; durationMs?: number|null;
  languageCode?: string|null; voice?: string|null; transcript?: string|null; transcriptVisibility?: TranscriptVisibility }
export type ContentBlock =
  | { type: 'TEXT'; text: string } | { type: 'MATH'; latex: string; display?: boolean }
  | { type: 'IMAGE'; assetKey: string; alt?: string } | { type: 'AUDIO'; assetKey: string }
  | { type: 'TABLE'; header: boolean; rows: string[][] };
export interface PlaybackPolicy { maxPlays: number; allowSeek: boolean; allowSpeed: boolean; autoPlay: boolean; pauseBeforeAnswerSec: number }
export interface Normalization { caseInsensitive: boolean; collapseWhitespace: boolean; stripTrailingPunctuation: boolean }
export interface GradingSpec { mode: GradingMode; partialCredit: boolean; stepTable?: number[]|null; rubricId?: string|null; normalization?: Normalization|null }
export interface RecordingPolicy { prepSeconds: number; maxSeconds: number; maxRetakes: number; allowFileFallback: boolean; maxFileMb: number }
export interface IdText { id: string; text: string }
export interface ChoiceOption extends IdText { assetKey?: string|null; audioAssetKey?:string|null }
export interface ImageRegion { id: string; label: string; x: number; y: number; width: number; height: number }
export interface AdvancedInteraction {
  assetKey?: string|null; regions?: ImageRegion[]; labels?: IdText[]; targets?: ImageRegion[];
  cards?: IdText[]; categories?: IdText[]; headers?: string[]; rows?: {id: string; label: string}[];
  cells?: {id: string; row: string; column: number; text: string; editable: boolean}[];
  xLabel?: string; yLabel?: string; min?: number; max?: number; points?: {id: string; x: number; label?: string}[];
  variables?: {id: string; min: number; max: number; step?: number}[]; chemical?: boolean;
  steps?: {id: string; prompt: string; points: number}[];
  width?: number; height?: number; backgroundAssetKey?: string|null;
  maxFileMb?: number; maxFiles?: number; accept?: string; instructions?: string;
  language?: 'python'|'javascript'; starterCode?: string; examples?: {input: string; output: string}[];
  simulation?: string; controls?: {id: string; label: string; min: number; max: number; step: number; initial: number}[]; formula?: string;
}
export interface AdvancedResponse {
  regionId?: string; x?: number; y?: number; labels?: Record<string,string>; categories?: Record<string,string>;
  cells?: Record<string,string>; values?: Record<string,string|number>; text?: string; steps?: Record<string,string>;
  files?: string[]; code?: string; controls?: Record<string,number>;
  drawing?: {x:number;y:number}[][];
}

type Parameterized<T> = T & { variables?: AdvancedInteraction['variables'] };
interface ItemBase { kind: 'ITEM'; id: string; prompt: string; promptBlocks?: ContentBlock[]; points: number; grading: GradingSpec;
  feedback?: string|null; difficulty?: Difficulty|null; outcome?: string|null; tags?: string[] }
export type ItemNode =
  | (ItemBase & { type: 'Q01'; interaction: { options: ChoiceOption[]; shuffleOptions: boolean }; answerKey: { optionId: string|null } })
  | (ItemBase & { type: 'Q02'; interaction: { options: ChoiceOption[]; shuffleOptions: boolean }; answerKey: { optionIds: string[] } })
  | (ItemBase & { type: 'Q03'; interaction: Record<string, never>; answerKey: { value: boolean|null } })
  | (ItemBase & { type: 'Q04'; interaction: { statements: IdText[] }; answerKey: { values: Record<string, boolean> } })
  | (ItemBase & { type: 'Q05'; interaction: { left: IdText[]; right: IdText[] }; answerKey: { pairs: Record<string, string> } })
  | (ItemBase & { type: 'Q06'; interaction: { items: IdText[] }; answerKey: { order: string[] } })
  | (ItemBase & { type: 'Q07'; interaction: { blanks: { id: string }[] }; answerKey: { blanks: Record<string, string[]> } })
  | (ItemBase & { type: 'Q08'; interaction: { blanks: { id: string }[]; distractors: string[] }; answerKey: { blanks: Record<string, string> } })
  | (ItemBase & { type: 'Q09'; interaction: { dropdowns: { id: string; options: IdText[] }[] }; answerKey: { choices: Record<string, string> } })
  | (ItemBase & { type: 'Q10'; interaction: { maxLength?: number|null }; answerKey: { accepted: string[] } })
  | (ItemBase & { type: 'Q11'; interaction: Parameterized<{ unit?: string|null }>; answerKey: { expression?:string; value: number|null; tolerance: number; toleranceType: 'ABSOLUTE'|'RELATIVE_PERCENT'; range?: { min: number; max: number }|null; acceptedUnits?: string[] } })
  | (ItemBase & { type: 'Q12'; interaction: { minWords?: number|null; maxWords?: number|null }; answerKey: Record<string, never> })
  | (ItemBase & { type: 'Q22'; interaction: { recording: RecordingPolicy }; answerKey: Record<string, never> })
  | (ItemBase & { type: AdvancedItemTypeCode; interaction: AdvancedInteraction; answerKey: Record<string, unknown> });
export interface GroupNode { kind: 'GROUP'; id: string; groupType: GroupTypeCode; title: string; instructions: string; stimulus: ContentBlock[];
  audioAssetKey?: string|null; playback: PlaybackPolicy; keepStimulusVisible: boolean; display: 'ALL'|'ONE'; shuffleChildren: boolean; children: ItemNode[] }
export type SectionChild = GroupNode | ItemNode;
export interface AudioScope {audioAssetKey?:string|null;playback?:PlaybackPolicy}
export interface SectionNode extends AudioScope { id: string; title: string; instructions: string; shuffle: boolean; nodes: SectionChild[] }
export interface AssessmentDocument extends AudioScope { schemaVersion: 1; title: string; kind: AssessmentKind; grade: number|null; subjectCode: string|null;
  languageCode: string|null; topicTitle: string|null; assets: Record<string, AssetRef>; sections: SectionNode[] }

export type ItemResponse =
  | { optionId: string } | { optionIds: string[] } | { value: boolean } | { values: Record<string, boolean> }
  | { pairs: Record<string, string> } | { order: string[] } | { blanks: Record<string, string> }
  | { choices: Record<string, string> } | { text: string } | { recordingAssetId: string; durationMs: number } | AdvancedResponse;

export type IssueCode = 'SECTION_EMPTY'|'GROUP_EMPTY'|'LISTENING_AUDIO_MISSING'|'TRANSCRIPT_MISSING'|'PROMPT_EMPTY'|'POINTS_INVALID'|'ANSWER_KEY_MISSING'
  |'OPTION_EMPTY'|'BLANK_COUNT_MISMATCH'|'AUTO_MODE_UNSUPPORTED'|'RUBRIC_MISSING'|'ASSET_MISSING'|'TYPE_UNAVAILABLE'|'NUMERIC_INVALID'|'LANGUAGE_REQUIRED'|'NO_ITEMS';
export interface ValidationIssue { nodeId: string; level: 'ERROR'|'WARNING'; code: IssueCode; message: string }

// Catalog
export interface TypeMeta { code: QuestionTypeCode; key: string; name: string; description: string; family: 'A'|'B'|'C'; phase: 'P0'|'P1'|'P2';
  defaultMode: GradingMode|null; container: boolean; available: boolean }
export interface SubjectDto { code: string; name: string; kind: 'SUBJECT'|'COMPONENT'|'ACTIVITY'|'TOPIC_CLUSTER'; integrated: boolean; groupLabel: string|null;
  parentCode: string|null; componentCodes: string[]; grades: number[]; languageRequired: boolean; defaultLanguageCode: string|null;
  suggestedTypes: QuestionTypeCode[]; sortOrder: number }
export interface Rubric { id: string; title: string; levels: string[]; criteria: { id: string; title: string; weight: number }[]; system: boolean }
export interface CatalogDto { levels: { code: 'TH'|'THCS'|'THPT'; name: string; grades: number[] }[]; subjects: SubjectDto[];
  languages: { code: string; name: string; voices: string[] }[]; types: TypeMeta[]; rubrics: Rubric[] }

// Authoring
export interface AssignmentSettings { targetSegmentId?: string|null; openAt?: string|null; dueAt?: string|null; timeLimitMinutes: number;
  maxAttempts: number;canRetry?:boolean; resultVisibility: ResultVisibility; allowLate: boolean; shuffle: boolean }
export interface AssignRequest extends AssignmentSettings { revision: number }
export interface AssignmentCounts { students: number; submitted: number; inProgress: number; pendingGrading: number; graded: number }
export interface AssignmentDto extends AssignmentSettings { id: string; assessmentId: string; classId: string; title: string; kind: AssessmentKind;
  versionId: string; versionNo: number; status: 'OPEN'|'CLOSED'|'CANCELLED'; itemCount: number; totalPoints: number; createdAt: string;
  assignedBy: PersonRef; counts: AssignmentCounts }
export interface AssessmentSummary { id: string; classId: string; title: string; kind: AssessmentKind; grade: number|null; subjectCode: string|null;
  subjectName: string|null; status: 'DRAFT'|'ARCHIVED'; revision: number; latestVersionNo: number; itemCount: number; totalPoints: number;
  updatedAt: string; createdBy: PersonRef; skills: string[]; assignments: AssignmentDto[] }
export interface AuthoringAssessment { id: string; classId: string; status: 'DRAFT'|'ARCHIVED'; revision: number; latestVersionNo: number; updatedAt: string;
  document: AssessmentDocument; assets: Record<string, { assetId: string; status: 'UPLOADED'|'PENDING'|'MISSING'; mime: string; sizeBytes: number }>;
  canEdit: boolean; canAssign: boolean }
export interface CreateAssessmentRequest { title: string; kind: AssessmentKind; grade: number|null; subjectCode: string|null; languageCode: string|null;
  topicTitle: string|null; document?: AssessmentDocument|null }
export interface SaveAssessmentRequest { revision: number; document: AssessmentDocument }
export interface SaveAssessmentResponse { revision: number; updatedAt: string; itemCount: number; totalPoints: number; issues: ValidationIssue[] }
export interface ValidateResponse { issues: ValidationIssue[]; ready: boolean }
export interface BankItemSummary { id: string; assessmentId: string; assessmentTitle: string; itemId: string; typeCode: ItemTypeCode; gradingMode: GradingMode;
  promptExcerpt: string; groupTitle: string|null; maxPoints: number; grade: number|null; subjectCode: string|null; updatedAt: string }

// Learner
export type LearnerInteraction =
  | { options: ChoiceOption[] } | Record<string, never> | { statements: IdText[] } | { left: IdText[]; right: IdText[] } | { items: IdText[] }
  | { blanks: { id: string }[] } | { blanks: { id: string }[]; bank: string[] } | { dropdowns: { id: string; options: IdText[] }[] }
  | { maxLength?: number|null } | { unit?: string|null } | { minWords?: number|null; maxWords?: number|null } | { recording: RecordingPolicy } | AdvancedInteraction;
export interface LearnerItem { kind: 'ITEM'; id: string; type: ItemTypeCode; label: string; prompt: string; promptBlocks: ContentBlock[];
  points: number; gradingMode: GradingMode; interaction: LearnerInteraction }
export interface LearnerAudio { playback?:PlaybackPolicy; assetKey: string; name: string; durationMs: number|null; languageCode: string|null }
export interface LearnerGroup { kind: 'GROUP'; id: string; groupType: GroupTypeCode; label: string; title: string; instructions: string;
  stimulus: ContentBlock[]; audio: LearnerAudio|null; playback: PlaybackPolicy; keepStimulusVisible: boolean; display: 'ALL'|'ONE';
  points: number; children: LearnerItem[] }
export interface LearnerSection { audio?:LearnerAudio|null;playback?:PlaybackPolicy; id: string; title: string; instructions: string; points: number; nodes: (LearnerGroup|LearnerItem)[] }
export interface LearnerDocument { audios?:Record<string,LearnerAudio>; audio?:LearnerAudio|null;playback?:PlaybackPolicy; title: string; kind: AssessmentKind; grade: number|null; subjectCode: string|null; subjectName: string|null;
  languageCode: string|null; itemCount: number; totalPoints: number; sections: LearnerSection[];
  images: Record<string, { url: string; alt?: string|null }> /* assetKey -> signed URL (10 min) */ }

export type HomeworkStatus = 'UPCOMING'|'NOT_STARTED'|'IN_PROGRESS'|'SUBMITTED'|'GRADED'|'RETURNED'|'MISSED'|'CLOSED';
export interface HomeworkCard { assignmentId: string; title: string; kind: AssessmentKind; status: HomeworkStatus; openAt: string|null; dueAt: string|null;
  itemCount: number; totalPoints: number; timeLimitMinutes: number; answeredCount: number; attemptId: string|null; attemptsUsed: number;
  maxAttempts: number;canRetry?:boolean; score: number|null; skills: string[] }
export interface RecordingInfo { assetId: string; durationMs: number; takesUsed: number; revision?: number }
export interface AttemptDto { attemptId: string; assignmentId: string; attemptNo: number; status: 'IN_PROGRESS'|'SUBMITTED'|'GRADED'|'RETURNED';
  startedAt: string; deadlineAt: string|null; serverNow: string; revision: number; resultVisibility: ResultVisibility; document: LearnerDocument;
  answers: Record<string, ItemResponse>; plays: Record<string, number> /* assetKey */; recordings: Record<string, RecordingInfo> /* itemId */; recordingTakes?:Record<string,number> }
export interface AutosaveRequest { revision: number; answers: Record<string, ItemResponse|null> }
export interface AutosaveResponse { revision: number; savedAt: string; answeredCount: number }
export interface PlayRequest { assetKey: string }
export interface PlayResponse { assetKey: string; plays: number; maxPlays: number; remaining: number|null; url: string; expiresAt: string }
export interface RecordingIntentRequest { filename: string; mimeType: string; sizeBytes: number; durationMs: number }
export interface RecordingIntentResponse { assetId: string; uploadUrl: string; method: 'PUT' }
export interface RecordingCompleteRequest { assetId: string; durationMs: number }
export interface SignedUrl { assetId: string; url: string; expiresAt: string }
export type ItemResultStatus = 'CORRECT'|'PARTIAL'|'WRONG'|'BLANK'|'PENDING'|'GRADED';
export interface ItemResult { itemId: string; label: string; status: ItemResultStatus; points: number|null; maxPoints: number; response: ItemResponse|null;
  correctResponse?: ItemResponse|null; subResults?: Record<string, boolean>|null; feedback?: string|null; teacherComment?: string|null; passFail?: PassFail|null }
export interface AttemptResult { attemptId: string; assignmentId: string; status: 'SUBMITTED'|'GRADED'|'RETURNED'; submittedAt: string; late: boolean;
  skills?:SkillStat[];
  visibility: { scores: boolean; answers: boolean; transcripts: boolean }; autoScore: number|null; manualScore: number|null; totalScore: number|null;
  maxScore: number; autoMaxScore: number; pendingManualCount: number; passFail: PassFail|null; teacherComment: string|null;
  document: LearnerDocument; items: ItemResult[]; transcripts: Record<string, string> /* assetKey */ }
export interface MyAttemptSummary { attemptId: string; attemptNo: number; status: AttemptDto['status']; startedAt: string; submittedAt: string|null; totalScore: number|null }

// Grading
export type SubmissionStatus = 'NOT_STARTED'|'IN_PROGRESS'|'SUBMITTED'|'GRADED'|'RETURNED';
export interface SubmissionRow { student: PersonRef; attemptId: string|null; attemptNo: number|null; status: SubmissionStatus; submittedAt: string|null;
  late: boolean; autoScore: number|null; manualScore: number|null; totalScore: number|null; maxScore: number; pendingManualCount: number; passFail: PassFail|null }
export interface GradingAnswer { itemId: string; label: string; typeCode: ItemTypeCode; mode: GradingMode; maxPoints: number; response: ItemResponse|null;
  autoPoints: number|null; manualPoints: number|null; finalPoints: number|null; status: 'UNANSWERED'|'AUTO_GRADED'|'PENDING_MANUAL'|'GRADED';
  correctness: 'CORRECT'|'PARTIAL'|'WRONG'|'BLANK'|'MANUAL'; subResults: Record<string, boolean>|null; rubricLevels: Record<string, number>|null;
  passFail: PassFail|null; comment: string|null; recording: { assetId: string; durationMs: number }|null }
export interface GradingAttemptDto { attemptId: string; assignmentId: string; student: PersonRef; status: SubmissionStatus; submittedAt: string|null;
  autoScore: number|null; manualScore: number|null; totalScore: number|null; maxScore: number; document: AssessmentDocument;
  labels: Record<string, string>; displayOrder: string[] /* itemIds in this student's order */; answers: GradingAnswer[]; rubrics: Rubric[];
  teacherComment: string|null; passFail: PassFail|null }
export interface SkillStat {skill:string;earned:number;possible:number;responses:number;masteryPercent:number|null}
export interface GradeAnswerRequest { points?: number|null; rubricLevels?: Record<string, number>|null; passFail?: PassFail|null; comment?: string|null }
export interface FinalizeRequest { teacherComment?: string|null; passFail?: PassFail|null; release: boolean }
export interface NodeStat { id: string; label: string; avgPoints: number|null; maxPoints: number }
export interface ItemStat extends NodeStat { typeCode: ItemTypeCode; sectionId: string; groupId: string|null; correctRate: number|null; answeredCount: number; pendingManual: number }
export interface AssignmentStats { assignmentId: string; studentCount: number; submittedCount: number; inProgressCount: number; notStartedCount: number;
  gradedCount: number; pendingGradingCount: number; avgScore: number|null; maxScore: number; avgDurationSec: number|null;
  items: ItemStat[]; groups: NodeStat[]; sections: NodeStat[]; skills?:SkillStat[];students?:{student:PersonRef;attemptId:string;skills:SkillStat[]}[] }
