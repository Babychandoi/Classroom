import React, { useEffect, useId, useState } from 'react';
import { useNavigate, useOutletContext } from 'react-router-dom';
import { Classroom, Exam, Question } from '../../types';
import { api } from '../../api/client';
import { fromDatetimeLocalValue, toDatetimeLocalValue } from '../../api/datetime';
import { nextPosition } from '../../api/ordering';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { Modal } from '../../components/Modal';
import { hasAnyStudioPermission, hasCoursePermission, hasStudioPermission } from '../../api/permissions';
import { Award, Plus, FileQuestion, Clock, CheckCircle, Pencil, ArrowUp, ArrowDown, Trash2, PlayCircle } from 'lucide-react';

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
    setShowExamModal(true);
  };

  const handleCreateExam = async (e: React.FormEvent) => {
    e.preventDefault();
    setSavingExam(true);
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
      alert(err.message || 'Tạo kỳ thi thất bại');
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
    if (!window.confirm('Xóa câu hỏi này khỏi đề thi?')) return;
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
    if (!window.confirm('Đóng kỳ thi này? Không ai có thể bắt đầu lượt thi mới sau khi đóng.')) return;
    setError(null);
    try {
      await api.post(`/exams/${examId}/close`, {});
      await fetchExams();
    } catch (err: any) { setError(err.message || 'Không thể đóng kỳ thi'); }
  };

  const handleArchiveExam = async (examId: string) => {
    if (!window.confirm('Lưu trữ kỳ thi đã đóng này?')) return;
    setError(null);
    try {
      await api.post(`/exams/${examId}/archive`, {});
      await fetchExams();
    } catch (err: any) { setError(err.message || 'Không thể lưu trữ kỳ thi'); }
  };

  return (
    <div className="max-w-5xl mx-auto space-y-6">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-black text-slate-900 tracking-tight">Quản lý Kỳ thi & Khảo sát</h1>
          <p className="text-xs text-slate-600">Tạo đề thi, cấu hình đối tượng tham gia và số lượt làm bài</p>
        </div>

        {canCreateExam && (
          <button
            onClick={openCreateExamModal}
            className="inline-flex items-center space-x-1.5 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold shadow-sm transition"
          >
            <Plus className="w-4 h-4" />
            <span>Tạo kỳ thi mới</span>
          </button>
        )}
      </div>

      {loading && <LoadingSpinner message="Đang tải kỳ thi..." />}
      {error && <ErrorBanner message={error} onRetry={fetchExams} />}

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {exams.map((exam) => (
          <div key={exam.id} className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm">
            <div className="flex items-center justify-between mb-2">
              <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-indigo-50 text-indigo-700 uppercase">
                {exam.audienceScope}
              </span>
              <span className="text-xs text-slate-500 flex items-center space-x-1">
                <Clock className="w-3 h-3" />
                <span>{exam.durationMinutes} phút</span>
              </span>
            </div>

            <h3 className="text-base font-bold text-slate-900 mb-1">{exam.title}</h3>
            <p className="text-xs text-slate-500 mb-4 line-clamp-2">{exam.description || 'Chưa có mô tả'}</p>

            <div className="text-[11px] text-slate-500 flex justify-between pt-3 border-t border-slate-100">
              <span>Lượt làm bài: {exam.attemptLimit}</span>
              {/* R7-04: exam.questions is only populated (by ExamService.getExamDetails) for a
                  caller with EXAM:EDIT; getExamsByClass (which feeds this list) never includes
                  it, so exam.questions?.length silently showed 0 for everyone else. questionCount
                  is the safe metadata field meant for exactly this list view. */}
              <span>Số câu hỏi: {exam.questionCount ?? 0}</span>
            </div>

            <div className="mt-3 flex flex-wrap gap-3 border-t border-slate-100 pt-3">
              {exam.status === 'DRAFT' && hasCoursePermission(classroom, 'EXAM', 'EDIT', exam.targetCourseId ?? '') && (
                <button onClick={() => openEditExam(exam)} className="inline-flex items-center gap-1 text-xs font-bold text-indigo-700"><Pencil className="w-3.5 h-3.5" />Sửa cấu hình</button>
              )}
              {/* R13-04: "Chạy thử" opens the attempt page in preview mode. canEnterExam/enforceEnterExam
                  authorizes a preview for OWNER or staff holding EXAM:PREVIEW/EXAM:EDIT, and preview is
                  allowed even while the exam is still DRAFT — gate the button the same way. */}
              {hasCoursePermission(classroom, 'EXAM', 'EDIT', exam.targetCourseId ?? '') && (
                <button onClick={() => runPreview(exam.id)} className="inline-flex items-center gap-1 text-xs font-bold text-emerald-700"><PlayCircle className="w-3.5 h-3.5" />Chạy thử</button>
              )}
            </div>

            {exam.status === 'DRAFT' && hasCoursePermission(classroom, 'EXAM', 'EDIT', exam.targetCourseId ?? '') && (
              <div className="mt-4 space-y-3 border-t border-slate-100 pt-3">
                {authoringExamId === exam.id ? <>
                  {detailsError[exam.id] && <p className="text-xs font-semibold text-red-600">{detailsError[exam.id]}</p>}
                  {(examDetails[exam.id]?.questions ?? []).length > 0 && (
                    <ul className="space-y-2">
                      {(examDetails[exam.id]?.questions ?? []).map((question, index, allQuestions) => (
                        <li key={question.id} className="rounded-lg border border-slate-200 p-2">
                          {editingQuestionId === question.id ? (
                            <div className="space-y-2">
                              {questionActionError[question.id] && <p className="text-xs font-semibold text-red-600">{questionActionError[question.id]}</p>}
                              <textarea aria-label="Nội dung câu hỏi (sửa)" value={editQuestionText} onChange={(e) => setEditQuestionText(e.target.value)} className="w-full rounded-lg border p-2 text-sm" />
                              <div className="flex gap-2">
                                <select aria-label="Loại câu hỏi (sửa)" value={editQuestionType} onChange={(e) => setEditQuestionType(e.target.value as typeof editQuestionType)} className="rounded-lg border p-2 text-sm">
                                  <option value="MULTIPLE_CHOICE">Trắc nghiệm</option><option value="ESSAY">Tự luận</option>
                                </select>
                                <label className="text-sm">Điểm <input aria-label="Điểm câu hỏi (sửa)" type="number" min={1} value={editPoints} onChange={(e) => setEditPoints(parseInt(e.target.value) || 1)} className="w-20 rounded-lg border p-2 text-sm" /></label>
                              </div>
                              {editQuestionType === 'MULTIPLE_CHOICE' && <div className="grid grid-cols-2 gap-2">
                                {editOptionTexts.map((text, i) => <input key={i} aria-label={`Lựa chọn ${String.fromCharCode(65 + i)} (sửa)`} value={text} onChange={(e) => setEditOptionTexts((old) => old.map((value, idx) => idx === i ? e.target.value : value))} placeholder={`Lựa chọn ${String.fromCharCode(65 + i)}`} className="rounded-lg border p-2 text-sm" />)}
                                <select aria-label="Đáp án đúng (sửa)" value={editAnswerKey} onChange={(e) => setEditAnswerKey(e.target.value)} className="rounded-lg border p-2 text-sm">{editAnswerKey === '' && <option value="" disabled>Chọn đáp án đúng</option>}{['A','B','C','D'].map((key) => <option key={key} value={key}>{key} đúng</option>)}</select>
                              </div>}
                              <div className="flex gap-2">
                                <button onClick={() => handleUpdateQuestion(exam.id)} className="rounded-lg bg-indigo-600 px-3 py-2 text-xs font-bold text-white">Lưu câu hỏi</button>
                                <button onClick={() => setEditingQuestionId(null)} className="rounded-lg border border-slate-300 px-3 py-2 text-xs font-semibold text-slate-700">Hủy</button>
                              </div>
                            </div>
                          ) : (
                            <div className="flex items-center justify-between gap-2">
                              <span className="text-xs text-slate-700 line-clamp-1">{index + 1}. {question.questionText} ({question.points} điểm · {question.type})</span>
                              <span className="flex items-center gap-1 shrink-0">
                                <button type="button" aria-label={`Chuyển câu hỏi ${index + 1} lên trên`} disabled={index === 0} onClick={() => moveQuestion(exam.id, allQuestions, index, -1)} className="text-slate-500 hover:text-indigo-700 disabled:opacity-30"><ArrowUp className="w-3.5 h-3.5" /></button>
                                <button type="button" aria-label={`Chuyển câu hỏi ${index + 1} xuống dưới`} disabled={index === allQuestions.length - 1} onClick={() => moveQuestion(exam.id, allQuestions, index, 1)} className="text-slate-500 hover:text-indigo-700 disabled:opacity-30"><ArrowDown className="w-3.5 h-3.5" /></button>
                                <button type="button" onClick={() => openEditQuestion(question)} className="text-[11px] font-bold text-indigo-600 hover:text-indigo-800">Sửa</button>
                                <button type="button" onClick={() => handleDeleteQuestion(exam.id, question.id)} className="text-slate-500 hover:text-red-600"><Trash2 className="w-3.5 h-3.5" /></button>
                              </span>
                            </div>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                  <textarea aria-label="Nội dung câu hỏi" value={questionText} onChange={(e) => setQuestionText(e.target.value)} placeholder="Nội dung câu hỏi" className="w-full rounded-lg border p-2 text-sm" />
                  <select aria-label="Loại câu hỏi" value={questionType} onChange={(e) => setQuestionType(e.target.value as typeof questionType)} className="rounded-lg border p-2 text-sm">
                    <option value="MULTIPLE_CHOICE">Trắc nghiệm</option><option value="ESSAY">Tự luận</option>
                  </select>
                  {questionType === 'MULTIPLE_CHOICE' && <div className="grid grid-cols-2 gap-2">
                    {optionTexts.map((text, index) => <input key={index} aria-label={`Lựa chọn ${String.fromCharCode(65 + index)}`} value={text} onChange={(e) => setOptionTexts((old) => old.map((value, i) => i === index ? e.target.value : value))} placeholder={`Lựa chọn ${String.fromCharCode(65 + index)}`} className="rounded-lg border p-2 text-sm" />)}
                    <select aria-label="Đáp án đúng" value={answerKey} onChange={(e) => setAnswerKey(e.target.value)} className="rounded-lg border p-2 text-sm">{['A','B','C','D'].map((key) => <option key={key} value={key}>{key} đúng</option>)}</select>
                  </div>}
                  <button onClick={() => handleAddQuestion(exam.id)} className="rounded-lg bg-indigo-600 px-3 py-2 text-xs font-bold text-white">Thêm câu hỏi</button>
                </> : <button onClick={() => { setAuthoringExamId(exam.id); void loadExamDetails(exam.id); }} className="text-xs font-bold text-indigo-700">Soạn câu hỏi và công bố</button>}
              </div>
            )}
            {/* Non-DRAFT: questions are frozen (ExamService.updateQuestion/deleteQuestion/reorderQuestions
                all reject once the exam is published), so show a read-only explanation instead of
                offering controls that would just 400. */}
            {exam.status !== 'DRAFT' && hasCoursePermission(classroom, 'EXAM', 'EDIT', exam.targetCourseId ?? '') && (
              <p className="mt-3 border-t border-slate-100 pt-3 text-[11px] text-slate-500">
                Câu hỏi và cấu hình đã bị khóa vì kỳ thi không còn ở trạng thái DRAFT. Dùng "Chạy thử" để kiểm tra đề, hoặc sửa điểm bài đã chấm ở mục Chấm bài.
              </p>
            )}
            {/* R8-03: publishing is a distinct, more consequential action than editing draft
                questions — gate it purely on EXAM:PUBLISH (mirroring ExamService.publishExam's own
                enforceManage check), independent of the EDIT-gated authoring block above. A staff
                member with PUBLISH but no EDIT grant must still be able to publish a drafted exam. */}
            {exam.status === 'DRAFT' && hasCoursePermission(classroom, 'EXAM', 'PUBLISH', exam.targetCourseId ?? '') && (
              <div className="mt-3 border-t border-slate-100 pt-3">
                <button onClick={() => handlePublish(exam.id)} disabled={!exam.questionCount} className="rounded-lg bg-emerald-600 px-3 py-2 text-xs font-bold text-white disabled:opacity-50">Công bố</button>
              </div>
            )}
            {(exam.status === 'PUBLISHED' || exam.status === 'OPEN') && hasCoursePermission(classroom, 'EXAM', 'PUBLISH', exam.targetCourseId ?? '') && (
              <div className="mt-3 border-t border-slate-100 pt-3">
                <button onClick={() => handleCloseExam(exam.id)} className="rounded-lg bg-amber-700 px-3 py-2 text-xs font-bold text-white">Đóng kỳ thi</button>
              </div>
            )}
            {exam.status === 'CLOSED' && hasCoursePermission(classroom, 'EXAM', 'PUBLISH', exam.targetCourseId ?? '') && (
              <div className="mt-3 border-t border-slate-100 pt-3">
                <button onClick={() => handleArchiveExam(exam.id)} className="rounded-lg bg-slate-600 px-3 py-2 text-xs font-bold text-white">Lưu trữ</button>
              </div>
            )}
          </div>
        ))}
      </div>

      {/* Create Exam Modal */}
      {showExamModal && (
        <Modal size="lg" title="Tạo kỳ thi mới" onClose={() => setShowExamModal(false)}>
            <form onSubmit={handleCreateExam} className="space-y-4">
              <div>
                <label htmlFor={examTitleId} className="block text-xs font-semibold text-slate-700 uppercase">Tên kỳ thi</label>
                <input
                  id={examTitleId}
                  type="text"
                  required
                  value={examTitle}
                  onChange={(e) => setExamTitle(e.target.value)}
                  placeholder="VD: Kiểm tra chuyên đề Số học"
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label htmlFor={durationId} className="block text-xs font-semibold text-slate-700 uppercase">Thời gian (phút)</label>
                  <input
                    id={durationId}
                    type="number"
                    min={5}
                    max={180}
                    value={durationMinutes}
                    onChange={(e) => setDurationMinutes(parseInt(e.target.value) || 45)}
                    className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                  />
                </div>

                <div>
                  <label htmlFor={attemptLimitId} className="block text-xs font-semibold text-slate-700 uppercase">Số lượt làm bài</label>
                  <input
                    id={attemptLimitId}
                    type="number"
                    min={1}
                    max={10}
                    value={attemptLimit}
                    onChange={(e) => setAttemptLimit(parseInt(e.target.value) || 1)}
                    className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                  />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label htmlFor={scheduleStartId} className="block text-xs font-semibold text-slate-700 uppercase">Bắt đầu mở đề (Tùy chọn)</label>
                  <input
                    id={scheduleStartId}
                    type="datetime-local"
                    value={scheduleStart}
                    onChange={(e) => setScheduleStart(e.target.value)}
                    className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                  />
                </div>

                <div>
                  <label htmlFor={scheduleEndId} className="block text-xs font-semibold text-slate-700 uppercase">Đóng đề thi (Tùy chọn)</label>
                  <input
                    id={scheduleEndId}
                    type="datetime-local"
                    value={scheduleEnd}
                    onChange={(e) => setScheduleEnd(e.target.value)}
                    className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                  />
                </div>
              </div>

              <div>
                <label htmlFor={audienceScopeId} className="block text-xs font-semibold text-slate-700 uppercase">Đối tượng được tham gia</label>
                <select
                  id={audienceScopeId}
                  value={audienceScope}
                  onChange={(e) => setAudienceScope(e.target.value as any)}
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                >
                  {/* R7-03: ExamService.createExam only ever resolves a course-scoped EXAM:CREATE
                      check (rbacScope) for COURSE/COURSE_SEGMENT audiences; ALL/PRO/SEGMENT always
                      require the class-wide grant. A staff member without that class-wide grant
                      would only ever get a 400 from these, so they are hidden rather than offered. */}
                  {hasClassWideCreateExam && <option value="ALL">Toàn bộ thành viên lớp (ALL)</option>}
                  {hasClassWideCreateExam && <option value="PRO">Chỉ dành cho học viên PRO (PRO)</option>}
                  <option value="COURSE">Chỉ dành cho học viên đã mua khóa học (COURSE)</option>
                  {hasClassWideCreateExam && <option value="SEGMENT">Theo nhóm học viên phân khúc (SEGMENT)</option>}
                  <option value="COURSE_SEGMENT">Kết hợp khóa học và nhóm (COURSE_SEGMENT)</option>
                </select>
                {['COURSE', 'COURSE_SEGMENT'].includes(audienceScope) && <select required aria-label="Khóa học mục tiêu" value={targetCourseId} onChange={(e) => setTargetCourseId(e.target.value)} className="mt-2 block w-full rounded-xl border border-slate-300 bg-slate-50 px-3 py-2 text-xs">
                  <option value="">Chọn khóa học</option>{allowedCourses.map((course) => <option key={course.id} value={course.id}>{course.title}</option>)}
                </select>}
                {['SEGMENT', 'COURSE_SEGMENT'].includes(audienceScope) && <select required aria-label="Nhóm học viên mục tiêu" value={targetSegmentId} onChange={(e) => setTargetSegmentId(e.target.value)} className="mt-2 block w-full rounded-xl border border-slate-300 bg-slate-50 px-3 py-2 text-xs">
                  <option value="">Chọn nhóm học viên</option>{segments.map((segment) => <option key={segment.id} value={segment.id}>{segment.name || segment.title || segment.id}</option>)}
                </select>}
                {audienceScope === 'COURSE_SEGMENT' && <select aria-label="Cách kết hợp điều kiện" value={audienceOperator} onChange={(e) => setAudienceOperator(e.target.value as 'AND' | 'OR')} className="mt-2 block w-full rounded-xl border border-slate-300 bg-slate-50 px-3 py-2 text-xs"><option value="AND">Phải thỏa cả hai điều kiện (AND)</option><option value="OR">Thỏa một trong hai điều kiện (OR)</option></select>}
              </div>

              <div>
                <label htmlFor={examDescId} className="block text-xs font-semibold text-slate-700 uppercase">Mô tả kỳ thi</label>
                <textarea
                  id={examDescId}
                  rows={3}
                  value={examDesc}
                  onChange={(e) => setExamDesc(e.target.value)}
                  placeholder="Quy định và hướng dẫn làm bài..."
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              <div className="flex justify-end space-x-2 pt-2">
                <button
                  type="button"
                  onClick={() => setShowExamModal(false)}
                  className="px-4 py-2 border border-slate-300 text-slate-700 rounded-xl text-xs font-semibold"
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  disabled={savingExam}
                  className="px-4 py-2 bg-indigo-600 text-white rounded-xl text-xs font-bold hover:bg-indigo-700 disabled:opacity-50"
                >
                  {savingExam ? 'Đang tạo...' : 'Tạo kỳ thi'}
                </button>
              </div>
            </form>
        </Modal>
      )}

      {/* Edit Exam Config Modal (DRAFT only) — audienceScope/targetCourseId/targetSegmentId are
          frozen after creation (ExamService.updateExam never touches them), so this form only
          covers the fields the backend actually accepts. */}
      {editingExam && (
        <Modal size="lg" title="Sửa cấu hình kỳ thi" onClose={() => setEditingExam(null)}>
            {editExamError && <p className="mb-3 text-xs font-semibold text-red-600">{editExamError}</p>}
            <form onSubmit={handleUpdateExam} className="space-y-4">
              <div>
                <label htmlFor={editExamTitleId} className="block text-xs font-semibold text-slate-700 uppercase">Tên kỳ thi</label>
                <input
                  id={editExamTitleId}
                  type="text"
                  required
                  value={editExamTitle}
                  onChange={(e) => setEditExamTitle(e.target.value)}
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label htmlFor={editDurationId} className="block text-xs font-semibold text-slate-700 uppercase">Thời gian (phút)</label>
                  <input
                    id={editDurationId}
                    type="number"
                    min={5}
                    max={180}
                    value={editDuration}
                    onChange={(e) => setEditDuration(parseInt(e.target.value) || 45)}
                    className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                  />
                </div>
                <div>
                  <label htmlFor={editAttemptLimitId} className="block text-xs font-semibold text-slate-700 uppercase">Số lượt làm bài</label>
                  <input
                    id={editAttemptLimitId}
                    type="number"
                    min={1}
                    max={10}
                    value={editAttemptLimit}
                    onChange={(e) => setEditAttemptLimit(parseInt(e.target.value) || 1)}
                    className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                  />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label htmlFor={editScheduleStartId} className="block text-xs font-semibold text-slate-700 uppercase">Bắt đầu mở đề (Tùy chọn)</label>
                  <input
                    id={editScheduleStartId}
                    type="datetime-local"
                    value={editScheduleStart}
                    onChange={(e) => setEditScheduleStart(e.target.value)}
                    className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                  />
                </div>
                <div>
                  <label htmlFor={editScheduleEndId} className="block text-xs font-semibold text-slate-700 uppercase">Đóng đề thi (Tùy chọn)</label>
                  <input
                    id={editScheduleEndId}
                    type="datetime-local"
                    value={editScheduleEnd}
                    onChange={(e) => setEditScheduleEnd(e.target.value)}
                    className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                  />
                </div>
              </div>

              <div>
                <label htmlFor={editExamDescId} className="block text-xs font-semibold text-slate-700 uppercase">Mô tả kỳ thi</label>
                <textarea
                  id={editExamDescId}
                  rows={3}
                  value={editExamDesc}
                  onChange={(e) => setEditExamDesc(e.target.value)}
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              <p className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600">
                Đối tượng tham gia (audience) và khóa học/nhóm liên kết được cố định từ lúc tạo và không thể sửa sau đó.
              </p>

              <div className="flex justify-end space-x-2 pt-2">
                <button
                  type="button"
                  onClick={() => setEditingExam(null)}
                  className="px-4 py-2 border border-slate-300 text-slate-700 rounded-xl text-xs font-semibold"
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  disabled={savingEditExam}
                  className="px-4 py-2 bg-indigo-600 text-white rounded-xl text-xs font-bold hover:bg-indigo-700 disabled:opacity-50"
                >
                  {savingEditExam ? 'Đang lưu...' : 'Lưu thay đổi'}
                </button>
              </div>
            </form>
        </Modal>
      )}
    </div>
  );
};
