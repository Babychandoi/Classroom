import React, { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, Exam } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { Award, Plus, FileQuestion, Clock, CheckCircle } from 'lucide-react';

export const StudioExams: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const canCreateExam = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('EXAM:CREATE');
  const canEditExam = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('EXAM:EDIT');

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
  const [questionText, setQuestionText] = useState('');
  const [questionType, setQuestionType] = useState<'MULTIPLE_CHOICE' | 'ESSAY'>('MULTIPLE_CHOICE');
  const [answerKey, setAnswerKey] = useState('A');
  const [optionTexts, setOptionTexts] = useState(['', '', '', '']);
  const [savingExam, setSavingExam] = useState(false);

  const fetchExams = async () => {
    try {
      setLoading(true);
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

  const handleCreateExam = async (e: React.FormEvent) => {
    e.preventDefault();
    setSavingExam(true);
    try {
      await api.post(`/classes/${classroom.id}/exams`, {
        title: examTitle,
        description: examDesc,
        durationMinutes,
        attemptLimit,
        scheduleStart: scheduleStart ? new Date(scheduleStart).toISOString() : null,
        scheduleEnd: scheduleEnd ? new Date(scheduleEnd).toISOString() : null,
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
        question: { questionText: questionText.trim(), type: questionType, points: 10, position: 0, answerKey: questionType === 'MULTIPLE_CHOICE' ? answerKey : null },
        options,
      });
      setQuestionText('');
      setOptionTexts(['', '', '', '']);
      setError(null);
      await fetchExams();
    } catch (err: any) { setError(err.message || 'Không thể thêm câu hỏi'); }
  };

  const handlePublish = async (examId: string) => {
    try {
      await api.post(`/exams/${examId}/publish`, {});
      setAuthoringExamId(null);
      await fetchExams();
    } catch (err: any) { setError(err.message || 'Không thể công bố kỳ thi'); }
  };

  return (
    <div className="max-w-5xl mx-auto space-y-6">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-black text-slate-900 tracking-tight">Quản lý Kỳ thi & Khảo sát</h1>
          <p className="text-xs text-slate-500">Tạo đề thi, cấu hình đối tượng tham gia và số lượt làm bài</p>
        </div>

        {canCreateExam && (
          <button
            onClick={() => setShowExamModal(true)}
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
              <span className="text-xs text-slate-400 flex items-center space-x-1">
                <Clock className="w-3 h-3" />
                <span>{exam.durationMinutes} phút</span>
              </span>
            </div>

            <h3 className="text-base font-bold text-slate-900 mb-1">{exam.title}</h3>
            <p className="text-xs text-slate-500 mb-4 line-clamp-2">{exam.description || 'Chưa có mô tả'}</p>

            <div className="text-[11px] text-slate-500 flex justify-between pt-3 border-t border-slate-100">
              <span>Lượt làm bài: {exam.attemptLimit}</span>
              <span>Số câu hỏi: {exam.questions?.length || 0}</span>
            </div>
            {exam.status === 'DRAFT' && canEditExam && (
              <div className="mt-4 space-y-3 border-t border-slate-100 pt-3">
                {authoringExamId === exam.id ? <>
                  <textarea aria-label="Nội dung câu hỏi" value={questionText} onChange={(e) => setQuestionText(e.target.value)} placeholder="Nội dung câu hỏi" className="w-full rounded-lg border p-2 text-sm" />
                  <select aria-label="Loại câu hỏi" value={questionType} onChange={(e) => setQuestionType(e.target.value as typeof questionType)} className="rounded-lg border p-2 text-sm">
                    <option value="MULTIPLE_CHOICE">Trắc nghiệm</option><option value="ESSAY">Tự luận</option>
                  </select>
                  {questionType === 'MULTIPLE_CHOICE' && <div className="grid grid-cols-2 gap-2">
                    {optionTexts.map((text, index) => <input key={index} aria-label={`Lựa chọn ${String.fromCharCode(65 + index)}`} value={text} onChange={(e) => setOptionTexts((old) => old.map((value, i) => i === index ? e.target.value : value))} placeholder={`Lựa chọn ${String.fromCharCode(65 + index)}`} className="rounded-lg border p-2 text-sm" />)}
                    <select aria-label="Đáp án đúng" value={answerKey} onChange={(e) => setAnswerKey(e.target.value)} className="rounded-lg border p-2 text-sm">{['A','B','C','D'].map((key) => <option key={key} value={key}>{key} đúng</option>)}</select>
                  </div>}
                  <div className="flex gap-2"><button onClick={() => handleAddQuestion(exam.id)} className="rounded-lg bg-indigo-600 px-3 py-2 text-xs font-bold text-white">Thêm câu hỏi</button><button onClick={() => handlePublish(exam.id)} disabled={!exam.questionCount} className="rounded-lg bg-emerald-600 px-3 py-2 text-xs font-bold text-white disabled:opacity-50">Công bố</button></div>
                </> : <button onClick={() => setAuthoringExamId(exam.id)} className="text-xs font-bold text-indigo-700">Soạn câu hỏi và công bố</button>}
              </div>
            )}
          </div>
        ))}
      </div>

      {/* Create Exam Modal */}
      {showExamModal && (
        <div className="fixed inset-0 bg-slate-900/50 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-white rounded-2xl max-w-lg w-full p-6 shadow-xl border border-slate-200">
            <h3 className="text-lg font-bold text-slate-900 mb-4">Tạo kỳ thi mới</h3>
            <form onSubmit={handleCreateExam} className="space-y-4">
              <div>
                <label className="block text-xs font-semibold text-slate-700 uppercase">Tên kỳ thi</label>
                <input
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
                  <label className="block text-xs font-semibold text-slate-700 uppercase">Thời gian (phút)</label>
                  <input
                    type="number"
                    min={5}
                    max={180}
                    value={durationMinutes}
                    onChange={(e) => setDurationMinutes(parseInt(e.target.value) || 45)}
                    className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                  />
                </div>

                <div>
                  <label className="block text-xs font-semibold text-slate-700 uppercase">Số lượt làm bài</label>
                  <input
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
                  <label className="block text-xs font-semibold text-slate-700 uppercase">Bắt đầu mở đề (Tùy chọn)</label>
                  <input
                    type="datetime-local"
                    value={scheduleStart}
                    onChange={(e) => setScheduleStart(e.target.value)}
                    className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                  />
                </div>

                <div>
                  <label className="block text-xs font-semibold text-slate-700 uppercase">Đóng đề thi (Tùy chọn)</label>
                  <input
                    type="datetime-local"
                    value={scheduleEnd}
                    onChange={(e) => setScheduleEnd(e.target.value)}
                    className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                  />
                </div>
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-700 uppercase">Đối tượng được tham gia</label>
                <select
                  value={audienceScope}
                  onChange={(e) => setAudienceScope(e.target.value as any)}
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                >
                  <option value="ALL">Toàn bộ thành viên lớp (ALL)</option>
                  <option value="PRO">Chỉ dành cho học viên PRO (PRO)</option>
                  <option value="COURSE">Chỉ dành cho học viên đã mua khóa học (COURSE)</option>
                  <option value="SEGMENT">Theo nhóm học viên phân khúc (SEGMENT)</option>
                  <option value="COURSE_SEGMENT">Kết hợp khóa học và nhóm (COURSE_SEGMENT)</option>
                </select>
                {['COURSE', 'COURSE_SEGMENT'].includes(audienceScope) && <select required aria-label="Khóa học mục tiêu" value={targetCourseId} onChange={(e) => setTargetCourseId(e.target.value)} className="mt-2 block w-full rounded-xl border border-slate-300 bg-slate-50 px-3 py-2 text-xs">
                  <option value="">Chọn khóa học</option>{courses.map((course) => <option key={course.id} value={course.id}>{course.title}</option>)}
                </select>}
                {['SEGMENT', 'COURSE_SEGMENT'].includes(audienceScope) && <select required aria-label="Nhóm học viên mục tiêu" value={targetSegmentId} onChange={(e) => setTargetSegmentId(e.target.value)} className="mt-2 block w-full rounded-xl border border-slate-300 bg-slate-50 px-3 py-2 text-xs">
                  <option value="">Chọn nhóm học viên</option>{segments.map((segment) => <option key={segment.id} value={segment.id}>{segment.name || segment.title || segment.id}</option>)}
                </select>}
                {audienceScope === 'COURSE_SEGMENT' && <select aria-label="Cách kết hợp điều kiện" value={audienceOperator} onChange={(e) => setAudienceOperator(e.target.value as 'AND' | 'OR')} className="mt-2 block w-full rounded-xl border border-slate-300 bg-slate-50 px-3 py-2 text-xs"><option value="AND">Phải thỏa cả hai điều kiện (AND)</option><option value="OR">Thỏa một trong hai điều kiện (OR)</option></select>}
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-700 uppercase">Mô tả kỳ thi</label>
                <textarea
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
          </div>
        </div>
      )}
    </div>
  );
};
