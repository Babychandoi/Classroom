import React, { useEffect, useId, useState } from 'react';
import { useNavigate, useOutletContext } from 'react-router-dom';
import { Classroom, Exam, Question } from '../../types';
import { api } from '../../api/client';
import { fromDatetimeLocalValue, toDatetimeLocalValue } from '../../api/datetime';
import { nextPosition } from '../../api/ordering';
import { LoadingSpinner, ErrorBanner, StatusBadge } from '../../components/UIStates';
import { Modal } from '../../components/Modal';
import { hasAnyStudioPermission, hasCoursePermission, hasStudioPermission } from '../../api/permissions';
import { Badge, Button, Card, Field, Input, Select, Textarea, buttonClass, inputClass } from '../../components/ui';
import { ModalActions, Notice, PageHeader, StudioPage, iconActionClass, rowActionClass } from './studioUi';
import { Plus, FileQuestion, Clock, CheckCircle, Pencil, ArrowUp, ArrowDown, Trash2, PlayCircle } from 'lucide-react';

const AUDIENCE_LABELS: Record<string, string> = {
  ALL: 'Cả lớp',
  PRO: 'Học viên PRO',
  COURSE: 'Theo khóa học',
  SEGMENT: 'Theo nhóm học viên',
  COURSE_SEGMENT: 'Khóa học + nhóm',
};

export const StudioExams: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const navigate = useNavigate();
  // R6-01: ExamService.createExam scopes the EXAM:CREATE check to targetCourseId when the chosen
  // audienceScope is COURSE/COURSE_SEGMENT (rbacScope), and to null (class-wide) otherwise — so a
  // course-scoped-only staff member can create a COURSE-scoped exam but not an ALL/PRO/SEGMENT
  // one. The single "create exam" modal covers every scope, so the button is shown whenever the
  // staff member has EITHER kind of EXAM:CREATE grant; the server makes the final per-scope call
  // when the form is submitted (mirroring how the modal's other scope-dependent validation is
  // already server-enforced).
  const canCreateExam = hasAnyStudioPermission(classroom, 'EXAM', 'CREATE');
  // R7-03: a staff member without the class-wide EXAM:CREATE grant can only ever have
  // ExamService.createExam succeed for a COURSE/COURSE_SEGMENT-scoped exam targeting a course
  // they hold a scoped EXAM:CREATE grant on (createExam's rbacScope resolution). The modal must
  // not offer ALL/PRO/SEGMENT (always class-wide) or a course they have no grant on, since the
  // server would just reject the submission.
  const hasClassWideCreateExam = hasStudioPermission(classroom, 'EXAM', 'CREATE');

  const [exams, setExams] = useState<Exam[]>([]);
  const [courses, setCourses] = useState<Array<{ id: string; title: string }>>([]);
  const [segments, setSegments] = useState<Array<{ id: string; name?: string; title?: string }>>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // New Exam Modal
  const [showExamModal, setShowExamModal] = useState(false);
  const [examTitle, setExamTitle] = useState('');
  const [examDesc, setExamDesc] = useState('');
  const [durationMinutes, setDurationMinutes] = useState(45);
  const [attemptLimit, setAttemptLimit] = useState(1);
  const [scheduleStart, setScheduleStart] = useState('');
  const [scheduleEnd, setScheduleEnd] = useState('');
  const [audienceScope, setAudienceScope] = useState<'ALL' | 'PRO' | 'COURSE' | 'SEGMENT' | 'COURSE_SEGMENT'>('ALL');
  const [audienceOperator, setAudienceOperator] = useState<'AND' | 'OR'>('AND');
  const [targetCourseId, setTargetCourseId] = useState('');
  const [targetSegmentId, setTargetSegmentId] = useState('');
  const [authoringExamId, setAuthoringExamId] = useState<string | null>(null);
  const examTitleId = useId();
  const durationId = useId();
  const attemptLimitId = useId();
  const scheduleStartId = useId();
  const scheduleEndId = useId();
  const audienceScopeId = useId();
  const examDescId = useId();
  const [questionText, setQuestionText] = useState('');
  const [questionType, setQuestionType] = useState<'MULTIPLE_CHOICE' | 'ESSAY'>('MULTIPLE_CHOICE');
  const [answerKey, setAnswerKey] = useState('A');
  const [optionTexts, setOptionTexts] = useState(['', '', '', '']);
  const [savingExam, setSavingExam] = useState(false);
  const [createExamError, setCreateExamError] = useState<string | null>(null);
  // Deleting a question, closing and archiving an exam ask first (they used to use window.confirm).
  const [confirmAction, setConfirmAction] = useState<
    | { kind: 'delete-question'; examId: string; questionId: string }
    | { kind: 'close'; examId: string }
    | { kind: 'archive'; examId: string }
    | null
  >(null);
  const [confirmPending, setConfirmPending] = useState(false);

  // R13-01: exam config edit + question list edit/delete/reorder (DRAFT only)
  const [examDetails, setExamDetails] = useState<Record<string, Exam>>({});
  const [detailsError, setDetailsError] = useState<Record<string, string>>({});

  const [editingExam, setEditingExam] = useState<Exam | null>(null);
  const [editExamTitle, setEditExamTitle] = useState('');
  const [editExamDesc, setEditExamDesc] = useState('');
  const [editDuration, setEditDuration] = useState(45);
  const [editAttemptLimit, setEditAttemptLimit] = useState(1);
  const [editScheduleStart, setEditScheduleStart] = useState('');
  const [editScheduleEnd, setEditScheduleEnd] = useState('');
  const [savingEditExam, setSavingEditExam] = useState(false);
  const [editExamError, setEditExamError] = useState<string | null>(null);
  const editExamTitleId = useId();
  const editDurationId = useId();
  const editAttemptLimitId = useId();
  const editScheduleStartId = useId();
  const editScheduleEndId = useId();
  const editExamDescId = useId();

  const [editingQuestionId, setEditingQuestionId] = useState<string | null>(null);
  const [editQuestionText, setEditQuestionText] = useState('');
  const [editQuestionType, setEditQuestionType] = useState<'MULTIPLE_CHOICE' | 'ESSAY'>('MULTIPLE_CHOICE');
  const [editAnswerKey, setEditAnswerKey] = useState('A');
  const [editOptionTexts, setEditOptionTexts] = useState(['', '', '', '']);
  const [editPoints, setEditPoints] = useState(10);
  const [questionActionError, setQuestionActionError] = useState<Record<string, string>>({});

  const fetchExams = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.get<Exam[]>(`/classes/${classroom.id}/exams`);
      setExams(data || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải danh sách kỳ thi');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchExams();
    api.get<Array<{ id: string; title: string }>>(`/classes/${classroom.id}/courses`).then(setCourses).catch(() => setCourses([]));
    api.get<Array<{ id: string; name?: string; title?: string }>>(`/classes/${classroom.id}/segments`).then(setSegments).catch(() => setSegments([]));
  }, [classroom.id]);

  // R7-03: the courses a course-scoped-only staff member may actually target when creating an
  // exam — only those they hold EXAM:CREATE on (class-wide or scoped to that course).
  const allowedCourses = hasClassWideCreateExam
    ? courses
    : courses.filter((course) => hasCoursePermission(classroom, 'EXAM', 'CREATE', course.id));

  const openCreateExamModal = () => {
    if (!hasClassWideCreateExam) {
      // Scoped-only staff can only ever create a COURSE-scoped exam; default straight to it with
      // the first course they're allowed to target, instead of the class-wide ALL default that
      // the server would reject for them.
      setAudienceScope('COURSE');
      setTargetCourseId(allowedCourses[0]?.id ?? '');
    } else {
      setAudienceScope('ALL');
      setTargetCourseId('');
    }
    setCreateExamError(null);
    setShowExamModal(true);
  };

  const handleCreateExam = async (e: React.FormEvent) => {
    e.preventDefault();
    setSavingExam(true);
    setCreateExamError(null);
    try {
      await api.post(`/classes/${classroom.id}/exams`, {
        title: examTitle,
        description: examDesc,
        durationMinutes,
        attemptLimit,
        scheduleStart: fromDatetimeLocalValue(scheduleStart),
        scheduleEnd: fromDatetimeLocalValue(scheduleEnd),
        audienceScope,
        audienceRuleVersion: 1,
        audienceOperator,
        targetCourseId: ['COURSE', 'COURSE_SEGMENT'].includes(audienceScope) ? targetCourseId : null,
        targetSegmentId: ['SEGMENT', 'COURSE_SEGMENT'].includes(audienceScope) ? targetSegmentId : null,
        passScore: 50,
      });
      setShowExamModal(false);
      setExamTitle('');
      setExamDesc('');
      setScheduleStart('');
      setScheduleEnd('');
      await fetchExams();
    } catch (err: any) {
      setCreateExamError(err.message || 'Tạo kỳ thi thất bại');
    } finally {
      setSavingExam(false);
    }
  };

  const handleAddQuestion = async (examId: string) => {
    const options = questionType === 'MULTIPLE_CHOICE'
      ? optionTexts.map((optionText, index) => ({ optionKey: String.fromCharCode(65 + index), optionText: optionText.trim(), position: index })).filter((option) => option.optionText)
      : [];
    if (!questionText.trim() || (questionType === 'MULTIPLE_CHOICE' && (options.length < 2 || !options.some((option) => option.optionKey === answerKey)))) {
      setError('Câu trắc nghiệm cần ít nhất hai lựa chọn và đáp án đúng phải nằm trong các lựa chọn.');
      return;
    }
    try {
      await api.post(`/exams/${examId}/questions`, {
        question: { questionText: questionText.trim(), type: questionType, points: 10, position: nextPosition(examDetails[examId]?.questions), answerKey: questionType === 'MULTIPLE_CHOICE' ? answerKey : null },
        options,
      });
      setQuestionText('');
      setOptionTexts(['', '', '', '']);
      setError(null);
      await fetchExams();
      // R17-03: fetchExams only refreshes the exam list (question count); the authoring panel renders
      // the questions from the per-exam detail, so reload it or the new question stays invisible.
      await loadExamDetails(examId);
    } catch (err: any) { setError(err.message || 'Không thể thêm câu hỏi'); }
  };

  const loadExamDetails = async (examId: string) => {
    try {
      const detail = await api.get<Exam>(`/exams/${examId}`);
      setExamDetails((current) => ({ ...current, [examId]: detail }));
      setDetailsError((current) => { const next = { ...current }; delete next[examId]; return next; });
    } catch (err: any) {
      setDetailsError((current) => ({ ...current, [examId]: err.message || 'Không thể tải chi tiết kỳ thi' }));
    }
  };

  const openEditExam = (exam: Exam) => {
    setEditingExam(exam);
    setEditExamTitle(exam.title);
    setEditExamDesc(exam.description || '');
    setEditDuration(exam.durationMinutes);
    setEditAttemptLimit(exam.attemptLimit);
    setEditScheduleStart(toDatetimeLocalValue(exam.scheduleStart)); // R16-04: local wall clock, not UTC
    setEditScheduleEnd(toDatetimeLocalValue(exam.scheduleEnd));
    setEditExamError(null);
  };

  const handleUpdateExam = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingExam) return;
    setSavingEditExam(true);
    setEditExamError(null);
    try {
      await api.put(`/exams/${editingExam.id}`, {
        title: editExamTitle,
        description: editExamDesc,
        durationMinutes: editDuration,
        attemptLimit: editAttemptLimit,
        scheduleStart: fromDatetimeLocalValue(editScheduleStart),
        scheduleEnd: fromDatetimeLocalValue(editScheduleEnd),
      });
      setEditingExam(null);
      await fetchExams();
      await loadExamDetails(editingExam.id);
    } catch (err: any) {
      setEditExamError(err.message || 'Cập nhật kỳ thi thất bại');
    } finally {
      setSavingEditExam(false);
    }
  };

  const openEditQuestion = (question: Question) => {
    setEditingQuestionId(question.id);
    setEditQuestionText(question.questionText);
    setEditQuestionType(question.type === 'ESSAY' ? 'ESSAY' : 'MULTIPLE_CHOICE');
    setEditPoints(question.points);
    if (question.options && question.options.length > 0) {
      const sorted = [...question.options].sort((a, b) => a.position - b.position);
      setEditOptionTexts([0, 1, 2, 3].map((i) => sorted[i]?.optionText || ''));
      // R15-02: start from the key the server stored (it used to be reset to the first option, so
      // an untouched "Lưu câu hỏi" silently changed a 'C' answer to 'A'). The save payload re-keys
      // options by their slot (A..D), so the stored key is translated to the slot of the option
      // that carries it.
      const stored = question.answerKey?.trim().toUpperCase();
      if (stored) {
        const slot = sorted.slice(0, 4).findIndex((o) => o.optionKey?.trim().toUpperCase() === stored);
        setEditAnswerKey(slot >= 0 ? String.fromCharCode(65 + slot) : 'A');
      } else {
        // The key is withheld from editors without answer-key access. Defaulting to "A" here would
        // silently overwrite the real key on save, so force an explicit choice instead.
        setEditAnswerKey('');
      }
    } else {
      setEditOptionTexts(['', '', '', '']);
      setEditAnswerKey('A');
    }
    setQuestionActionError((current) => { const next = { ...current }; delete next[question.id]; return next; });
  };

  const handleUpdateQuestion = async (examId: string) => {
    if (!editingQuestionId) return;
    const options = editQuestionType === 'MULTIPLE_CHOICE'
      ? editOptionTexts.map((optionText, index) => ({ optionKey: String.fromCharCode(65 + index), optionText: optionText.trim(), position: index })).filter((option) => option.optionText)
      : [];
    if (!editQuestionText.trim() || (editQuestionType === 'MULTIPLE_CHOICE' && (options.length < 2 || !options.some((option) => option.optionKey === editAnswerKey)))) {
      setQuestionActionError((current) => ({ ...current, [editingQuestionId]: 'Câu trắc nghiệm cần ít nhất hai lựa chọn và đáp án đúng phải nằm trong các lựa chọn.' }));
      return;
    }
    try {
      await api.put(`/questions/${editingQuestionId}`, {
        question: { questionText: editQuestionText.trim(), type: editQuestionType, points: editPoints, position: 0, answerKey: editQuestionType === 'MULTIPLE_CHOICE' ? editAnswerKey : null },
        options,
      });
      setEditingQuestionId(null);
      await loadExamDetails(examId);
      await fetchExams();
    } catch (err: any) {
      setQuestionActionError((current) => ({ ...current, [editingQuestionId]: err.message || 'Không thể cập nhật câu hỏi' }));
    }
  };

  const handleDeleteQuestion = async (examId: string, questionId: string) => {
    try {
      await api.delete(`/questions/${questionId}`);
      await loadExamDetails(examId);
      await fetchExams();
    } catch (err: any) {
      setQuestionActionError((current) => ({ ...current, [questionId]: err.message || 'Không thể xóa câu hỏi' }));
    }
  };

  const moveQuestion = async (examId: string, questions: Question[], index: number, direction: -1 | 1) => {
    const targetIndex = index + direction;
    if (targetIndex < 0 || targetIndex >= questions.length) return;
    const reordered = [...questions];
    const [moved] = reordered.splice(index, 1);
    reordered.splice(targetIndex, 0, moved);
    try {
      await api.put(`/exams/${examId}/questions/reorder`, reordered.map((q) => q.id));
      await loadExamDetails(examId);
    } catch (err: any) {
      setDetailsError((current) => ({ ...current, [examId]: err.message || 'Không thể sắp xếp lại câu hỏi' }));
    }
  };

  // R14-15: "Chạy thử" only navigates. The attempt page (?preview=1) is the single place that
  // starts the preview attempt - the server resumes an existing IN_PROGRESS preview attempt for the
  // same user+exam instead of creating another - so a permission/eligibility rejection is shown
  // there. Starting one here as well used to create a second, stranded preview attempt per click.
  const runPreview = (examId: string) => {
    setError(null);
    navigate(`/classes/${classroom.slug}/exams/${examId}/attempt?preview=1`);
  };

  const handlePublish = async (examId: string) => {
    setError(null);
    try {
      await api.post(`/exams/${examId}/publish`, {});
      setAuthoringExamId(null);
      await fetchExams();
    } catch (err: any) { setError(err.message || 'Không thể công bố kỳ thi'); }
  };

  // R13-03: SRS §5 exam state machine — PUBLISHED/OPEN -> CLOSED -> ARCHIVED.
  const handleCloseExam = async (examId: string) => {
    setError(null);
    try {
      await api.post(`/exams/${examId}/close`, {});
      await fetchExams();
    } catch (err: any) { setError(err.message || 'Không thể đóng kỳ thi'); }
  };

  const handleArchiveExam = async (examId: string) => {
    setError(null);
    try {
      await api.post(`/exams/${examId}/archive`, {});
      await fetchExams();
    } catch (err: any) { setError(err.message || 'Không thể lưu trữ kỳ thi'); }
  };

  const runConfirmed = async () => {
    if (!confirmAction) return;
    setConfirmPending(true);
    try {
      if (confirmAction.kind === 'delete-question') await handleDeleteQuestion(confirmAction.examId, confirmAction.questionId);
      else if (confirmAction.kind === 'close') await handleCloseExam(confirmAction.examId);
      else await handleArchiveExam(confirmAction.examId);
    } finally {
      setConfirmPending(false);
      setConfirmAction(null);
    }
  };

  const optionLetter = (i: number) => String.fromCharCode(65 + i);

  return (
    <StudioPage>
      <PageHeader
        title="Thi"
        description="Tạo đề thi, chọn đối tượng tham gia và số lượt làm bài, rồi công bố cho lớp."
        action={canCreateExam && (
          <Button variant="primary" size="md" onClick={openCreateExamModal}>
            <Plus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
            <span>Tạo kỳ thi mới</span>
          </Button>
        )}
      />

      {loading && <LoadingSpinner message="Đang tải kỳ thi..." />}
      {error && <ErrorBanner message={error} onRetry={fetchExams} />}

      {!loading && !error && exams.length === 0 && (
        <Card className="text-center">
          <p className="text-ui text-slate-600">Lớp chưa có kỳ thi nào. {canCreateExam ? 'Một bài kiểm tra 10 câu trắc nghiệm là cách nhẹ nhàng để học viên tự đo tiến độ.' : ''}</p>
        </Card>
      )}

      <div className="grid grid-cols-1 items-start gap-4 lg:grid-cols-2">
        {exams.map((exam) => {
          const canEditExam = hasCoursePermission(classroom, 'EXAM', 'EDIT', exam.targetCourseId ?? '');
          const canPublishExam = hasCoursePermission(classroom, 'EXAM', 'PUBLISH', exam.targetCourseId ?? '');
          return (
          <Card key={exam.id} as="article" className="flex flex-col">
            <div className="flex flex-wrap items-center gap-1.5">
              <StatusBadge status={exam.status} />
              <Badge tone="neutral" size="sm">{AUDIENCE_LABELS[exam.audienceScope] ?? exam.audienceScope}</Badge>
            </div>

            <h3 className="mt-3 text-h3 font-semibold text-slate-900">{exam.title}</h3>
            <p className="mt-1 line-clamp-2 text-meta text-slate-600">{exam.description || 'Chưa có mô tả'}</p>

            <dl className="mt-4 grid grid-cols-3 gap-2 border-t border-slate-100 pt-3 text-caption">
              <div>
                <dt className="text-slate-500">Thời gian</dt>
                <dd className="mt-0.5 inline-flex items-center gap-1 text-ui font-semibold text-slate-900 tabular">
                  <Clock className="h-3.5 w-3.5 text-slate-400" strokeWidth={1.75} aria-hidden="true" />{exam.durationMinutes} phút
                </dd>
              </div>
              <div>
                <dt className="text-slate-500">Lượt làm bài</dt>
                <dd className="mt-0.5 text-ui font-semibold text-slate-900 tabular">{exam.attemptLimit}</dd>
              </div>
              <div>
                <dt className="text-slate-500">Số câu hỏi</dt>
                {/* R7-04: exam.questions is only populated (by ExamService.getExamDetails) for a
                    caller with EXAM:EDIT; getExamsByClass (which feeds this list) never includes
                    it, so exam.questions?.length silently showed 0 for everyone else. questionCount
                    is the safe metadata field meant for exactly this list view. */}
                <dd className="mt-0.5 text-ui font-semibold text-slate-900 tabular">{exam.questionCount ?? 0}</dd>
              </div>
            </dl>

            {/* One action row: preview/config (EXAM:EDIT) plus close/archive (EXAM:PUBLISH) for a published exam. */}
            {canEditExam || (canPublishExam && ['PUBLISHED', 'OPEN', 'CLOSED'].includes(exam.status)) ? (
              <div className="mt-3 flex flex-wrap items-center gap-1 border-t border-slate-100 pt-3">
                {exam.status === 'DRAFT' && canEditExam && (
                  <button type="button" onClick={() => openEditExam(exam)} className={rowActionClass()}><Pencil className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />Sửa cấu hình</button>
                )}
                {/* R13-04: "Chạy thử" opens the attempt page in preview mode. canEnterExam/enforceEnterExam
                    authorizes a preview for OWNER or staff holding EXAM:PREVIEW/EXAM:EDIT, and preview is
                    allowed even while the exam is still DRAFT — gate the button the same way. */}
                {canEditExam && (
                  <button type="button" onClick={() => runPreview(exam.id)} className={rowActionClass()}><PlayCircle className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />Chạy thử</button>
                )}
                {(exam.status === 'PUBLISHED' || exam.status === 'OPEN') && canPublishExam && (
                  <Button size="sm" variant="secondary" className="ml-auto" onClick={() => setConfirmAction({ kind: 'close', examId: exam.id })}>Đóng kỳ thi</Button>
                )}
                {exam.status === 'CLOSED' && canPublishExam && (
                  <Button size="sm" variant="secondary" className="ml-auto" onClick={() => setConfirmAction({ kind: 'archive', examId: exam.id })}>Lưu trữ</Button>
                )}
              </div>
            ) : null}

            {exam.status === 'DRAFT' && canEditExam && (
              <div className="mt-3 space-y-3 border-t border-slate-100 pt-3">
                {authoringExamId === exam.id ? <>
                  {detailsError[exam.id] && <p role="alert" className="text-meta font-medium text-red-600">{detailsError[exam.id]}</p>}
                  {(examDetails[exam.id]?.questions ?? []).length > 0 && (
                    <ul className="divide-y divide-slate-100 overflow-hidden rounded-2xl border border-slate-200">
                      {(examDetails[exam.id]?.questions ?? []).map((question, index, allQuestions) => (
                        <li key={question.id} className="px-3 py-2">
                          {editingQuestionId === question.id ? (
                            <div className="space-y-2 py-1">
                              {questionActionError[question.id] && <p role="alert" className="text-meta font-medium text-red-600">{questionActionError[question.id]}</p>}
                              <textarea aria-label="Nội dung câu hỏi (sửa)" value={editQuestionText} onChange={(e) => setEditQuestionText(e.target.value)} className={inputClass('py-2.5')} rows={2} />
                              <div className="flex flex-wrap gap-2">
                                <select aria-label="Loại câu hỏi (sửa)" value={editQuestionType} onChange={(e) => setEditQuestionType(e.target.value as typeof editQuestionType)} className={inputClass('h-10 w-auto pr-8')}>
                                  <option value="MULTIPLE_CHOICE">Trắc nghiệm</option><option value="ESSAY">Tự luận</option>
                                </select>
                                <label className="inline-flex items-center gap-2 text-caption font-semibold text-slate-600">Điểm <input aria-label="Điểm câu hỏi (sửa)" type="number" min={1} value={editPoints} onChange={(e) => setEditPoints(parseInt(e.target.value) || 1)} className={inputClass('h-10 w-20 tabular')} /></label>
                              </div>
                              {editQuestionType === 'MULTIPLE_CHOICE' && <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                                {editOptionTexts.map((text, i) => <input key={i} aria-label={`Lựa chọn ${optionLetter(i)} (sửa)`} value={text} onChange={(e) => setEditOptionTexts((old) => old.map((value, idx) => idx === i ? e.target.value : value))} placeholder={`Lựa chọn ${optionLetter(i)}`} className={inputClass('h-10')} />)}
                                <select aria-label="Đáp án đúng (sửa)" value={editAnswerKey} onChange={(e) => setEditAnswerKey(e.target.value)} className={inputClass('h-10 pr-8')}>{editAnswerKey === '' && <option value="" disabled>Chọn đáp án đúng</option>}{['A','B','C','D'].map((key) => <option key={key} value={key}>{key} đúng</option>)}</select>
                              </div>}
                              <div className="flex gap-2">
                                <Button size="sm" variant="primary" onClick={() => handleUpdateQuestion(exam.id)}>Lưu câu hỏi</Button>
                                <Button size="sm" variant="secondary" onClick={() => setEditingQuestionId(null)}>Hủy</Button>
                              </div>
                            </div>
                          ) : (
                            <div className="flex items-center justify-between gap-2">
                              <span className="line-clamp-1 text-meta text-slate-900">{index + 1}. {question.questionText} ({question.points} điểm · {question.type === 'ESSAY' ? 'Tự luận' : 'Trắc nghiệm'})</span>
                              <span className="flex shrink-0 items-center">
                                <button type="button" aria-label={`Chuyển câu hỏi ${index + 1} lên trên`} disabled={index === 0} onClick={() => moveQuestion(exam.id, allQuestions, index, -1)} className={iconActionClass()}><ArrowUp className="h-3.5 w-3.5" strokeWidth={1.75} /></button>
                                <button type="button" aria-label={`Chuyển câu hỏi ${index + 1} xuống dưới`} disabled={index === allQuestions.length - 1} onClick={() => moveQuestion(exam.id, allQuestions, index, 1)} className={iconActionClass()}><ArrowDown className="h-3.5 w-3.5" strokeWidth={1.75} /></button>
                                <button type="button" onClick={() => openEditQuestion(question)} className={rowActionClass()}>Sửa</button>
                                <button type="button" aria-label={`Xóa câu hỏi ${index + 1}`} onClick={() => setConfirmAction({ kind: 'delete-question', examId: exam.id, questionId: question.id })} className={iconActionClass('danger')}><Trash2 className="h-3.5 w-3.5" strokeWidth={1.75} /></button>
                              </span>
                            </div>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                  <div className="space-y-2 rounded-2xl border border-slate-200 bg-slate-50 p-3">
                    <p className="text-caption font-semibold uppercase tracking-[0.5px] text-slate-500">Câu hỏi mới</p>
                    <textarea aria-label="Nội dung câu hỏi" value={questionText} onChange={(e) => setQuestionText(e.target.value)} placeholder="Nội dung câu hỏi" className={inputClass('py-2.5')} rows={2} />
                    <select aria-label="Loại câu hỏi" value={questionType} onChange={(e) => setQuestionType(e.target.value as typeof questionType)} className={inputClass('h-10 w-auto pr-8')}>
                      <option value="MULTIPLE_CHOICE">Trắc nghiệm</option><option value="ESSAY">Tự luận</option>
                    </select>
                    {questionType === 'MULTIPLE_CHOICE' && <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                      {optionTexts.map((text, index) => <input key={index} aria-label={`Lựa chọn ${optionLetter(index)}`} value={text} onChange={(e) => setOptionTexts((old) => old.map((value, i) => i === index ? e.target.value : value))} placeholder={`Lựa chọn ${optionLetter(index)}`} className={inputClass('h-10')} />)}
                      <select aria-label="Đáp án đúng" value={answerKey} onChange={(e) => setAnswerKey(e.target.value)} className={inputClass('h-10 pr-8')}>{['A','B','C','D'].map((key) => <option key={key} value={key}>{key} đúng</option>)}</select>
                    </div>}
                    <Button size="sm" variant="secondary" onClick={() => handleAddQuestion(exam.id)}>
                      <FileQuestion className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />Thêm câu hỏi
                    </Button>
                  </div>
                </> : <button type="button" onClick={() => { setAuthoringExamId(exam.id); void loadExamDetails(exam.id); }} className={buttonClass('tertiary', 'sm')}>Soạn câu hỏi và công bố</button>}
              </div>
            )}
            {/* Non-DRAFT: questions are frozen (ExamService.updateQuestion/deleteQuestion/reorderQuestions
                all reject once the exam is published), so show a read-only explanation instead of
                offering controls that would just 400. */}
            {exam.status !== 'DRAFT' && canEditExam && (
              <p className="mt-2 text-caption text-slate-500">
                Câu hỏi và cấu hình đã bị khóa vì kỳ thi không còn ở trạng thái DRAFT. Dùng "Chạy thử" để kiểm tra đề, hoặc sửa điểm bài đã chấm ở mục Chấm bài.
              </p>
            )}
            {/* R8-03: publishing is a distinct, more consequential action than editing draft
                questions — gate it purely on EXAM:PUBLISH (mirroring ExamService.publishExam's own
                enforceManage check), independent of the EDIT-gated authoring block above. A staff
                member with PUBLISH but no EDIT grant must still be able to publish a drafted exam. */}
            {exam.status === 'DRAFT' && canPublishExam && (
              <div className="mt-3 flex flex-wrap items-center gap-3 border-t border-slate-100 pt-3">
                <Button size="sm" variant="done" onClick={() => handlePublish(exam.id)} disabled={!exam.questionCount}>
                  <CheckCircle className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" /><span>Công bố</span>
                </Button>
                {!exam.questionCount && <span className="text-caption text-slate-500">Thêm ít nhất một câu hỏi để công bố.</span>}
              </div>
            )}
          </Card>
          );
        })}
      </div>

      {/* Create Exam Modal */}
      {showExamModal && (
        <Modal size="lg" title="Tạo kỳ thi mới" onClose={() => setShowExamModal(false)}>
          <form onSubmit={handleCreateExam} className="space-y-5">
            {createExamError && <ErrorBanner message={createExamError} />}
            <Field label="Tên kỳ thi" htmlFor={examTitleId}>
              <Input id={examTitleId} type="text" required value={examTitle} onChange={(e) => setExamTitle(e.target.value)} placeholder="VD: Kiểm tra chuyên đề Số học" />
            </Field>

            <div className="grid grid-cols-2 gap-3">
              <Field label="Thời gian (phút)" htmlFor={durationId}>
                <Input id={durationId} type="number" min={5} max={180} value={durationMinutes} onChange={(e) => setDurationMinutes(parseInt(e.target.value) || 45)} className="tabular" />
              </Field>
              <Field label="Số lượt làm bài" htmlFor={attemptLimitId}>
                <Input id={attemptLimitId} type="number" min={1} max={10} value={attemptLimit} onChange={(e) => setAttemptLimit(parseInt(e.target.value) || 1)} className="tabular" />
              </Field>
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Bắt đầu mở đề (Tùy chọn)" htmlFor={scheduleStartId}>
                <Input id={scheduleStartId} type="datetime-local" value={scheduleStart} onChange={(e) => setScheduleStart(e.target.value)} className="tabular" />
              </Field>
              <Field label="Đóng đề thi (Tùy chọn)" htmlFor={scheduleEndId}>
                <Input id={scheduleEndId} type="datetime-local" value={scheduleEnd} onChange={(e) => setScheduleEnd(e.target.value)} className="tabular" />
              </Field>
            </div>

            <Field label="Đối tượng được tham gia" htmlFor={audienceScopeId}>
              <Select id={audienceScopeId} value={audienceScope} onChange={(e) => setAudienceScope(e.target.value as any)}>
                {/* R7-03: ExamService.createExam only ever resolves a course-scoped EXAM:CREATE
                    check (rbacScope) for COURSE/COURSE_SEGMENT audiences; ALL/PRO/SEGMENT always
                    require the class-wide grant. A staff member without that class-wide grant
                    would only ever get a 400 from these, so they are hidden rather than offered. */}
                {hasClassWideCreateExam && <option value="ALL">Toàn bộ thành viên lớp (ALL)</option>}
                {hasClassWideCreateExam && <option value="PRO">Chỉ dành cho học viên PRO (PRO)</option>}
                <option value="COURSE">Chỉ dành cho học viên đã mua khóa học (COURSE)</option>
                {hasClassWideCreateExam && <option value="SEGMENT">Theo nhóm học viên phân khúc (SEGMENT)</option>}
                <option value="COURSE_SEGMENT">Kết hợp khóa học và nhóm (COURSE_SEGMENT)</option>
              </Select>
              {['COURSE', 'COURSE_SEGMENT'].includes(audienceScope) && <Select required aria-label="Khóa học mục tiêu" value={targetCourseId} onChange={(e) => setTargetCourseId(e.target.value)} className="mt-2">
                <option value="">Chọn khóa học</option>{allowedCourses.map((course) => <option key={course.id} value={course.id}>{course.title}</option>)}
              </Select>}
              {['SEGMENT', 'COURSE_SEGMENT'].includes(audienceScope) && <Select required aria-label="Nhóm học viên mục tiêu" value={targetSegmentId} onChange={(e) => setTargetSegmentId(e.target.value)} className="mt-2">
                <option value="">Chọn nhóm học viên</option>{segments.map((segment) => <option key={segment.id} value={segment.id}>{segment.name || segment.title || segment.id}</option>)}
              </Select>}
              {audienceScope === 'COURSE_SEGMENT' && <Select aria-label="Cách kết hợp điều kiện" value={audienceOperator} onChange={(e) => setAudienceOperator(e.target.value as 'AND' | 'OR')} className="mt-2"><option value="AND">Phải thỏa cả hai điều kiện (AND)</option><option value="OR">Thỏa một trong hai điều kiện (OR)</option></Select>}
            </Field>

            <Field label="Mô tả kỳ thi" htmlFor={examDescId}>
              <Textarea id={examDescId} rows={3} value={examDesc} onChange={(e) => setExamDesc(e.target.value)} placeholder="Quy định và hướng dẫn làm bài..." />
            </Field>

            <ModalActions>
              <Button variant="secondary" onClick={() => setShowExamModal(false)}>Hủy</Button>
              <Button type="submit" variant="primary" disabled={savingExam}>{savingExam ? 'Đang tạo...' : 'Tạo kỳ thi'}</Button>
            </ModalActions>
          </form>
        </Modal>
      )}

      {/* Edit Exam Config Modal (DRAFT only) — audienceScope/targetCourseId/targetSegmentId are
          frozen after creation (ExamService.updateExam never touches them), so this form only
          covers the fields the backend actually accepts. */}
      {editingExam && (
        <Modal size="lg" title="Sửa cấu hình kỳ thi" onClose={() => setEditingExam(null)}>
          <form onSubmit={handleUpdateExam} className="space-y-5">
            {editExamError && <ErrorBanner message={editExamError} />}
            <Field label="Tên kỳ thi" htmlFor={editExamTitleId}>
              <Input id={editExamTitleId} type="text" required value={editExamTitle} onChange={(e) => setEditExamTitle(e.target.value)} />
            </Field>

            <div className="grid grid-cols-2 gap-3">
              <Field label="Thời gian (phút)" htmlFor={editDurationId}>
                <Input id={editDurationId} type="number" min={5} max={180} value={editDuration} onChange={(e) => setEditDuration(parseInt(e.target.value) || 45)} className="tabular" />
              </Field>
              <Field label="Số lượt làm bài" htmlFor={editAttemptLimitId}>
                <Input id={editAttemptLimitId} type="number" min={1} max={10} value={editAttemptLimit} onChange={(e) => setEditAttemptLimit(parseInt(e.target.value) || 1)} className="tabular" />
              </Field>
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Bắt đầu mở đề (Tùy chọn)" htmlFor={editScheduleStartId}>
                <Input id={editScheduleStartId} type="datetime-local" value={editScheduleStart} onChange={(e) => setEditScheduleStart(e.target.value)} className="tabular" />
              </Field>
              <Field label="Đóng đề thi (Tùy chọn)" htmlFor={editScheduleEndId}>
                <Input id={editScheduleEndId} type="datetime-local" value={editScheduleEnd} onChange={(e) => setEditScheduleEnd(e.target.value)} className="tabular" />
              </Field>
            </div>

            <Field label="Mô tả kỳ thi" htmlFor={editExamDescId}>
              <Textarea id={editExamDescId} rows={3} value={editExamDesc} onChange={(e) => setEditExamDesc(e.target.value)} />
            </Field>

            <Notice tone="info">
              Đối tượng tham gia (audience) và khóa học/nhóm liên kết được cố định từ lúc tạo và không thể sửa sau đó.
            </Notice>

            <ModalActions>
              <Button variant="secondary" onClick={() => setEditingExam(null)}>Hủy</Button>
              <Button type="submit" variant="primary" disabled={savingEditExam}>{savingEditExam ? 'Đang lưu...' : 'Lưu thay đổi'}</Button>
            </ModalActions>
          </form>
        </Modal>
      )}

      {confirmAction && (
        <Modal
          size="sm"
          role="alertdialog"
          title={confirmAction.kind === 'delete-question' ? 'Xóa câu hỏi?' : confirmAction.kind === 'close' ? 'Đóng kỳ thi?' : 'Lưu trữ kỳ thi?'}
          onClose={() => setConfirmAction(null)}
        >
          <p className="text-ui text-slate-600">
            {confirmAction.kind === 'delete-question'
              ? 'Xóa câu hỏi này khỏi đề thi?'
              : confirmAction.kind === 'close'
                ? 'Đóng kỳ thi này? Không ai có thể bắt đầu lượt thi mới sau khi đóng.'
                : 'Lưu trữ kỳ thi đã đóng này?'}
          </p>
          <ModalActions className="mt-5">
            <Button variant="secondary" onClick={() => setConfirmAction(null)}>Hủy</Button>
            <Button variant="danger" disabled={confirmPending} onClick={runConfirmed}>
              {confirmPending ? 'Đang xử lý...' : confirmAction.kind === 'delete-question' ? 'Xóa câu hỏi' : confirmAction.kind === 'close' ? 'Đóng kỳ thi' : 'Lưu trữ'}
            </Button>
          </ModalActions>
        </Modal>
      )}
    </StudioPage>
  );
};
