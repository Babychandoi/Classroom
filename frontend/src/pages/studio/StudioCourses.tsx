import React, { useEffect, useId, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, Course, Section, Lesson } from '../../types';
import { api } from '../../api/client';
import { putToObjectStore } from '../../api/upload';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { Modal } from '../../components/Modal';
import { hasStudioPermission, hasCoursePermission } from '../../api/permissions';
import { nextPosition } from '../../api/ordering';
import { BookOpen, Plus, FolderPlus, FilePlus, Check, Archive, ArchiveRestore, Trash2, Pencil, ArrowUp, ArrowDown } from 'lucide-react';

type CourseConfirmAction =
  | { kind: 'archive-course'; courseId: string; label: string }
  | { kind: 'restore-course'; courseId: string; label: string }
  | { kind: 'delete-course'; courseId: string; label: string }
  | { kind: 'archive-section'; sectionId: string; courseId: string; archived: boolean; label: string }
  | { kind: 'delete-section'; sectionId: string; courseId: string; label: string }
  | { kind: 'archive-lesson'; lessonId: string; courseId: string; archived: boolean; label: string }
  | { kind: 'delete-lesson'; lessonId: string; courseId: string; label: string };

// R18-06: action errors belong to the course they happened in, so they are kept per course id and only rendered
// inside that course's card (they used to be one string rendered in every card).
type ErrorsByCourse = Record<string, string>;
const withCourseError = (errors: ErrorsByCourse, courseId: string, message: string | null): ErrorsByCourse => {
  const next = { ...errors };
  if (message) next[courseId] = message; else delete next[courseId];
  return next;
};

export const StudioCourses: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  // R6-01: creating a course is a class-wide action (there is no course yet to scope it to), so
  // it stays gated by the class-wide grant — mirrors LearningService.createCourse's
  // enforceManage(..., "COURSE", "CREATE", null). Editing/publishing an existing course is
  // scoped to that course server-side (resourceScopeCourseId = courseId), so course-scoped-only
  // staff must be evaluated per course, not just once for the whole page.
  const canCreateCourse = hasStudioPermission(classroom, 'COURSE', 'CREATE');

  const [courses, setCourses] = useState<Course[]>([]);
  const [expandedCourses, setExpandedCourses] = useState<Record<string, Course>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // New Course Modal
  const [showCourseModal, setShowCourseModal] = useState(false);
  const [courseTitle, setCourseTitle] = useState('');
  const [courseDesc, setCourseDesc] = useState('');
  const [savingCourse, setSavingCourse] = useState(false);

  // New Section Form
  const [activeCourseId, setActiveCourseId] = useState<string | null>(null);
  const [sectionTitle, setSectionTitle] = useState('');

  // New Lesson Form
  const [activeSectionId, setActiveSectionId] = useState<string | null>(null);
  const [lessonTitle, setLessonTitle] = useState('');
  const [lessonType, setLessonType] = useState<'VIDEO' | 'TEXT' | 'DOCUMENT' | 'ASSIGNMENT'>('VIDEO');
  const [lessonContent, setLessonContent] = useState('');
  const [lessonCaptions, setLessonCaptions] = useState('');
  const [lessonMediaAssetId, setLessonMediaAssetId] = useState('');
  const [uploadingMedia, setUploadingMedia] = useState(false);
  const courseTitleId = useId();
  const courseDescId = useId();
  const [confirmAction, setConfirmAction] = useState<CourseConfirmAction | null>(null);
  const [actionErrors, setActionErrors] = useState<ErrorsByCourse>({});
  const [actionPending, setActionPending] = useState(false);
  // R18-04: reordering courses is class-wide (the whole ordered id list), so it needs the class-wide EDIT grant.
  const canReorderCourses = hasStudioPermission(classroom, 'COURSE', 'EDIT');
  const [reorderingCourses, setReorderingCourses] = useState(false);

  // Edit Course Modal (R13-04)
  const [editingCourse, setEditingCourse] = useState<Course | null>(null);
  const [editCourseTitle, setEditCourseTitle] = useState('');
  const [editCourseDesc, setEditCourseDesc] = useState('');
  const [editCourseCover, setEditCourseCover] = useState('');
  const [savingEditCourse, setSavingEditCourse] = useState(false);
  const [editCourseError, setEditCourseError] = useState<string | null>(null);
  const editCourseTitleId = useId();
  const editCourseDescId = useId();
  const editCourseCoverId = useId();

  // Edit Section inline state
  const [editingSectionId, setEditingSectionId] = useState<string | null>(null);
  const [editSectionTitle, setEditSectionTitle] = useState('');
  const [sectionActionErrors, setSectionActionErrors] = useState<ErrorsByCourse>({});

  // Edit Lesson inline state
  const [editingLessonId, setEditingLessonId] = useState<string | null>(null);
  const [editLessonTitle, setEditLessonTitle] = useState('');
  const [editLessonType, setEditLessonType] = useState<'VIDEO' | 'TEXT' | 'DOCUMENT' | 'ASSIGNMENT'>('VIDEO');
  const [editLessonCaptions, setEditLessonCaptions] = useState('');
  const [editLessonContent, setEditLessonContent] = useState('');
  const [editLessonMediaAssetId, setEditLessonMediaAssetId] = useState('');
  const [editLessonDuration, setEditLessonDuration] = useState(0);
  const [uploadingEditMedia, setUploadingEditMedia] = useState(false);
  const [lessonActionErrors, setLessonActionErrors] = useState<ErrorsByCourse>({});

  const fetchCourses = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.get<Course[]>(`/classes/${classroom.id}/courses`);
      setCourses(data || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải danh sách khóa học');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchCourses();
  }, [classroom.id]);

  const handleCreateCourse = async (e: React.FormEvent) => {
    e.preventDefault();
    setSavingCourse(true);
    try {
      await api.post(`/classes/${classroom.id}/courses`, {
        title: courseTitle,
        description: courseDesc,
        accessMode: 'FREE',
      });
      setShowCourseModal(false);
      setCourseTitle('');
      setCourseDesc('');
      await fetchCourses();
    } catch (err: any) {
      alert(err.message || 'Tạo khóa học thất bại');
    } finally {
      setSavingCourse(false);
    }
  };

  const publishCourse = async (id: string) => {
    setError(null);
    try { await api.post(`/courses/${id}/publish`); await fetchCourses(); await refreshExpandedCourse(id); }
    catch (err: any) { setError(err.message || 'Không thể xuất bản khóa học'); }
  };

  const handleCreateSection = async (courseId: string) => {
    if (!sectionTitle.trim()) return;
    try {
      // R17-02: append after the existing sections (a collapsed course has not loaded them yet, so
      // read them first) instead of sending the same position for every new section.
      const existing = expandedCourses[courseId]?.sections
        ?? (await api.get<Course>(`/courses/${courseId}`)).sections
        ?? [];
      await api.post(`/courses/${courseId}/sections`, {
        title: sectionTitle,
        position: nextPosition(existing),
      });
      setSectionTitle('');
      setActiveCourseId(null);
      await fetchCourses();
      // The list refresh above does not touch an expanded course's own detail, so without this the
      // new section stayed invisible until the course was collapsed and re-expanded.
      await refreshExpandedCourse(courseId);
    } catch (err: any) {
      alert(err.message || 'Tạo chương thất bại');
    }
  };

  const handleCreateLesson = async (courseId: string, section: Section) => {
    if (!lessonTitle.trim()) return;
    try {
      await api.post(`/sections/${section.id}/lessons`, {
        title: lessonTitle,
        type: lessonType,
        contentText: lessonContent,
        captionsVtt: lessonCaptions,
        mediaAssetId: lessonMediaAssetId || null,
        position: nextPosition(section.lessons),
      });
      setLessonTitle('');
      setLessonContent('');
      setLessonCaptions('');
      setLessonMediaAssetId('');
      setActiveSectionId(null);
      await fetchCourses();
      // R17-02: lessons are only ever created inside an expanded course, whose detail is not part of
      // the list fetched above - refresh it or the new lesson is invisible until re-expanding.
      await refreshExpandedCourse(courseId);
    } catch (err: any) {
      alert(err.message || 'Tạo bài học thất bại');
    }
  };

  const uploadLessonMedia = async (courseId: string, file?: File) => {
    if (!file) return;
    setUploadingMedia(true);
    try {
      const intent = await api.post<{ assetId: string; uploadUrl: string }>(`/classes/${classroom.id}/media/upload-intents`, {
        filename: file.name, mimeType: file.type, sizeBytes: file.size, purpose: 'LESSON', scopeCourseId: courseId,
      });
      await putToObjectStore(intent.uploadUrl, file, 'Tải file thất bại');
      await api.post(`/media/${intent.assetId}/complete`);
      setLessonMediaAssetId(intent.assetId);
    } catch (err: any) { alert(err.message || 'Không thể tải media'); }
    finally { setUploadingMedia(false); }
  };

  const refreshExpandedCourse = async (courseId: string) => {
    if (!expandedCourses[courseId]) return;
    try {
      const detail = await api.get<Course>(`/courses/${courseId}`);
      setExpandedCourses((current) => ({ ...current, [courseId]: detail }));
    } catch { /* keep stale detail rather than surfacing a second error */ }
  };

  const openEditCourse = (course: Course) => {
    setEditingCourse(course);
    setEditCourseTitle(course.title);
    setEditCourseDesc(course.description || '');
    setEditCourseCover(course.coverImageUrl || '');
    setEditCourseError(null);
  };

  const handleUpdateCourse = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingCourse) return;
    setSavingEditCourse(true);
    setEditCourseError(null);
    try {
      await api.put(`/courses/${editingCourse.id}`, {
        title: editCourseTitle,
        description: editCourseDesc,
        coverImageUrl: editCourseCover,
      });
      setEditingCourse(null);
      await fetchCourses();
      await refreshExpandedCourse(editingCourse.id);
    } catch (err: any) {
      setEditCourseError(err.message || 'Cập nhật khóa học thất bại');
    } finally {
      setSavingEditCourse(false);
    }
  };

  const openEditSection = (section: Section) => {
    setEditingSectionId(section.id);
    setEditSectionTitle(section.title);
    setSectionActionErrors({});
  };

  const handleUpdateSection = async (courseId: string) => {
    if (!editingSectionId || !editSectionTitle.trim()) return;
    setSectionActionErrors((errors) => withCourseError(errors, courseId, null));
    try {
      await api.put(`/sections/${editingSectionId}`, { title: editSectionTitle });
      setEditingSectionId(null);
      await refreshExpandedCourse(courseId);
    } catch (err: any) {
      setSectionActionErrors((errors) => withCourseError(errors, courseId, err.message || 'Cập nhật chương thất bại'));
    }
  };

  const openEditLesson = (lesson: Lesson) => {
    setEditingLessonId(lesson.id);
    setEditLessonTitle(lesson.title);
    setEditLessonType(lesson.type);
    setEditLessonContent(lesson.contentText || '');
    setEditLessonCaptions(lesson.captionsVtt || '');
    setEditLessonMediaAssetId(lesson.mediaAssetId || '');
    setEditLessonDuration(lesson.durationMinutes || 0);
    setLessonActionErrors({});
  };

  const uploadEditLessonMedia = async (courseId: string, file?: File) => {
    if (!file) return;
    setUploadingEditMedia(true);
    try {
      const intent = await api.post<{ assetId: string; uploadUrl: string }>(`/classes/${classroom.id}/media/upload-intents`, {
        filename: file.name, mimeType: file.type, sizeBytes: file.size, purpose: 'LESSON', scopeCourseId: courseId,
      });
      await putToObjectStore(intent.uploadUrl, file, 'Tải file thất bại');
      await api.post(`/media/${intent.assetId}/complete`);
      setEditLessonMediaAssetId(intent.assetId);
    } catch (err: any) { setLessonActionErrors((errors) => withCourseError(errors, courseId, err.message || 'Không thể tải media')); }
    finally { setUploadingEditMedia(false); }
  };

  const handleUpdateLesson = async (courseId: string) => {
    if (!editingLessonId || !editLessonTitle.trim()) return;
    setLessonActionErrors((errors) => withCourseError(errors, courseId, null));
    try {
      await api.put(`/lessons/${editingLessonId}`, {
        title: editLessonTitle,
        type: editLessonType,
        contentText: editLessonContent,
        captionsVtt: editLessonCaptions,
        mediaAssetId: editLessonMediaAssetId || null,
        durationMinutes: editLessonDuration,
      });
      setEditingLessonId(null);
      await refreshExpandedCourse(courseId);
    } catch (err: any) {
      setLessonActionErrors((errors) => withCourseError(errors, courseId, err.message || 'Cập nhật bài học thất bại'));
    }
  };

  const moveSection = async (courseId: string, sections: Section[], index: number, direction: -1 | 1) => {
    const targetIndex = index + direction;
    if (targetIndex < 0 || targetIndex >= sections.length) return;
    const reordered = [...sections];
    const [moved] = reordered.splice(index, 1);
    reordered.splice(targetIndex, 0, moved);
    setSectionActionErrors((errors) => withCourseError(errors, courseId, null));
    try {
      await api.put(`/courses/${courseId}/sections/reorder`, reordered.map((s) => s.id));
      await refreshExpandedCourse(courseId);
    } catch (err: any) {
      setSectionActionErrors((errors) => withCourseError(errors, courseId, err.message || 'Không thể sắp xếp lại chương'));
    }
  };

  const moveLesson = async (courseId: string, sectionId: string, lessons: Lesson[], index: number, direction: -1 | 1) => {
    const targetIndex = index + direction;
    if (targetIndex < 0 || targetIndex >= lessons.length) return;
    const reordered = [...lessons];
    const [moved] = reordered.splice(index, 1);
    reordered.splice(targetIndex, 0, moved);
    setLessonActionErrors((errors) => withCourseError(errors, courseId, null));
    try {
      await api.put(`/sections/${sectionId}/lessons/reorder`, reordered.map((l) => l.id));
      await refreshExpandedCourse(courseId);
    } catch (err: any) {
      setLessonActionErrors((errors) => withCourseError(errors, courseId, err.message || 'Không thể sắp xếp lại bài học'));
    }
  };

  // R18-04: move a course one place up/down. The endpoint takes the whole class's ordered id list.
  const moveCourse = async (index: number, direction: -1 | 1) => {
    const targetIndex = index + direction;
    if (targetIndex < 0 || targetIndex >= courses.length || reorderingCourses) return;
    const reordered = [...courses];
    const [moved] = reordered.splice(index, 1);
    reordered.splice(targetIndex, 0, moved);
    setReorderingCourses(true);
    setError(null);
    try {
      await api.put(`/classes/${classroom.id}/courses/reorder`, reordered.map((c) => c.id));
      setCourses(reordered);
    } catch (err: any) {
      // Not tied to one card (it is the order of the whole list), so it is a page-level error.
      setError(err.message || 'Không thể sắp xếp lại khóa học');
    } finally {
      setReorderingCourses(false);
    }
  };

  const runConfirmedAction = async (action: CourseConfirmAction) => {
    setActionPending(true);
    setActionErrors((errors) => withCourseError(errors, action.courseId, null));
    try {
      switch (action.kind) {
        case 'archive-course':
          await api.post(`/courses/${action.courseId}/archive`);
          break;
        case 'restore-course':
          await api.post(`/courses/${action.courseId}/restore`);
          break;
        case 'delete-course':
          await api.delete(`/courses/${action.courseId}`);
          break;
        case 'archive-section':
          await api.post(`/sections/${action.sectionId}/archive?archived=${!action.archived}`);
          break;
        case 'delete-section':
          await api.delete(`/sections/${action.sectionId}`);
          break;
        case 'archive-lesson':
          await api.post(`/lessons/${action.lessonId}/archive?archived=${!action.archived}`);
          break;
        case 'delete-lesson':
          await api.delete(`/lessons/${action.lessonId}`);
          break;
      }
      await fetchCourses();
      if (action.kind === 'delete-course') {
        // The course is gone: refreshing its detail would only 404, so drop the stale expanded copy.
        setExpandedCourses((current) => { const next = { ...current }; delete next[action.courseId]; return next; });
      } else {
        await refreshExpandedCourse(action.courseId);
      }
    } catch (err: any) {
      setActionErrors((errors) => withCourseError(errors, action.courseId, err.message || 'Thao tác thất bại'));
    } finally {
      setActionPending(false);
      setConfirmAction(null);
    }
  };

  const toggleCourse = async (courseId: string) => {
    if (expandedCourses[courseId]) {
      setExpandedCourses((current) => { const next = { ...current }; delete next[courseId]; return next; });
      return;
    }
    try {
      const detail = await api.get<Course>(`/courses/${courseId}`);
      setExpandedCourses((current) => ({ ...current, [courseId]: detail }));
    } catch (err: any) { alert(err.message || 'Không thể tải nội dung khóa học'); }
  };

  return (
    <div className="max-w-5xl mx-auto space-y-6">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-black text-slate-900 tracking-tight">Quản lý Khóa học & Bài giảng</h1>
          <p className="text-xs text-slate-600">Tổ chức chương trình học theo khóa, chương và từng bài học</p>
        </div>

        {canCreateCourse && (
          <button
            onClick={() => setShowCourseModal(true)}
            className="inline-flex items-center space-x-1.5 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold shadow-sm transition"
          >
            <Plus className="w-4 h-4" />
            <span>Tạo khóa học mới</span>
          </button>
        )}
      </div>

      {loading && <LoadingSpinner message="Đang tải dữ liệu khóa học..." />}
      {error && <ErrorBanner message={error} onRetry={fetchCourses} />}

      <div className="space-y-4">
        {courses.map((course, courseIndex) => (
          <div key={course.id} className="bg-white rounded-2xl border border-slate-200 p-6 shadow-sm">
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center space-x-2">
                <span
                  className={`text-[10px] font-bold px-2 py-0.5 rounded-full uppercase ${
                    course.accessMode === 'FREE' ? 'bg-emerald-100 text-emerald-800' : 'bg-amber-100 text-amber-800'
                  }`}
                >
                  {course.accessMode === 'FREE' ? 'Miễn phí' : 'Yêu cầu mua'}
                </span>
                <h3 className="text-lg font-bold text-slate-900">{course.title}</h3>
                <span className="text-xs text-slate-500">{course.status}</span>
              </div>

              <div className="flex flex-wrap items-center gap-3">
              {canReorderCourses && courses.length > 1 && (
                <span className="inline-flex items-center gap-1">
                  <button
                    type="button"
                    aria-label={`Chuyển khóa học "${course.title}" lên trên`}
                    disabled={courseIndex === 0 || reorderingCourses}
                    onClick={() => moveCourse(courseIndex, -1)}
                    className="text-slate-600 hover:text-indigo-700 disabled:opacity-30 min-h-6 min-w-6 inline-flex items-center justify-center"
                  >
                    <ArrowUp className="w-4 h-4" />
                  </button>
                  <button
                    type="button"
                    aria-label={`Chuyển khóa học "${course.title}" xuống dưới`}
                    disabled={courseIndex === courses.length - 1 || reorderingCourses}
                    onClick={() => moveCourse(courseIndex, 1)}
                    className="text-slate-600 hover:text-indigo-700 disabled:opacity-30 min-h-6 min-w-6 inline-flex items-center justify-center"
                  >
                    <ArrowDown className="w-4 h-4" />
                  </button>
                </span>
              )}
              {course.status === 'DRAFT' && hasCoursePermission(classroom, 'COURSE', 'PUBLISH', course.id) && <button onClick={() => publishCourse(course.id)} className="text-xs font-bold text-indigo-700">Xuất bản</button>}
              <button onClick={() => toggleCourse(course.id)} className="text-xs font-bold text-slate-600">
                {expandedCourses[course.id] ? 'Ẩn nội dung' : 'Xem bài học'}
              </button>
              {hasCoursePermission(classroom, 'COURSE', 'EDIT', course.id) && course.status !== 'ARCHIVED' && (
                <button
                  onClick={() => openEditCourse(course)}
                  className="inline-flex items-center space-x-1 text-xs font-bold text-indigo-600 hover:text-indigo-800"
                >
                  <Pencil className="w-3.5 h-3.5" />
                  <span>Sửa</span>
                </button>
              )}
              {hasCoursePermission(classroom, 'COURSE', 'EDIT', course.id) && <button
                onClick={() => setActiveCourseId(activeCourseId === course.id ? null : course.id)}
                className="inline-flex items-center space-x-1 text-xs font-bold text-indigo-600 hover:text-indigo-800"
              >
                <FolderPlus className="w-3.5 h-3.5" />
                <span>Thêm chương</span>
              </button>}
              {hasCoursePermission(classroom, 'COURSE', 'EDIT', course.id) && course.status !== 'ARCHIVED' && (
                <button
                  onClick={() => setConfirmAction({ kind: 'archive-course', courseId: course.id, label: `lưu trữ khóa học "${course.title}"` })}
                  className="inline-flex items-center space-x-1 text-xs font-bold text-amber-700 hover:text-amber-900"
                >
                  <Archive className="w-3.5 h-3.5" />
                  <span>Lưu trữ</span>
                </button>
              )}
              {hasCoursePermission(classroom, 'COURSE', 'EDIT', course.id) && course.status === 'ARCHIVED' && (
                <button
                  onClick={() => setConfirmAction({ kind: 'restore-course', courseId: course.id, label: `khôi phục khóa học "${course.title}"` })}
                  className="inline-flex items-center space-x-1 text-xs font-bold text-emerald-700 hover:text-emerald-900"
                >
                  <ArchiveRestore className="w-3.5 h-3.5" />
                  <span>Khôi phục</span>
                </button>
              )}
              {hasCoursePermission(classroom, 'COURSE', 'EDIT', course.id) && course.status === 'DRAFT' && (
                <button
                  onClick={() => setConfirmAction({ kind: 'delete-course', courseId: course.id, label: `xóa vĩnh viễn khóa học "${course.title}"` })}
                  className="inline-flex items-center space-x-1 text-xs font-bold text-red-600 hover:text-red-800"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                  <span>Xóa</span>
                </button>
              )}
              </div>
            </div>
            {actionErrors[course.id] && <p role="alert" className="mb-2 text-xs font-semibold text-red-600">{actionErrors[course.id]}</p>}

            <p className="text-xs text-slate-500 mb-4">{course.description || 'Chưa có mô tả'}</p>

            {/* Add Section inline form */}
            {activeCourseId === course.id && (
              <div className="mb-4 p-4 bg-indigo-50/60 rounded-xl border border-indigo-100 flex items-center space-x-3">
                <input
                  type="text"
                  value={sectionTitle}
                  onChange={(e) => setSectionTitle(e.target.value)}
                  placeholder="Tên chương học mới (VD: Chương 1: Giới thiệu)..."
                  className="flex-1 px-3 py-1.5 bg-white border border-slate-200 rounded-lg text-xs focus:outline-none focus:ring-1 focus:ring-indigo-500"
                />
                <button
                  onClick={() => handleCreateSection(course.id)}
                  className="px-3 py-1.5 bg-indigo-600 text-white rounded-lg text-xs font-bold hover:bg-indigo-700"
                >
                  Lưu chương
                </button>
              </div>
            )}
            {sectionActionErrors[course.id] && <p role="alert" className="mb-2 text-xs font-semibold text-red-600">{sectionActionErrors[course.id]}</p>}
            {lessonActionErrors[course.id] && <p role="alert" className="mb-2 text-xs font-semibold text-red-600">{lessonActionErrors[course.id]}</p>}
            {expandedCourses[course.id]?.sections?.map((section, sectionIndex, allSections) => (
              <section key={section.id} className="mt-4 border-t border-slate-100 pt-3">
                {editingSectionId === section.id ? (
                  <div className="flex items-center space-x-2">
                    <input
                      aria-label="Tên chương"
                      value={editSectionTitle}
                      onChange={(e) => setEditSectionTitle(e.target.value)}
                      className="flex-1 px-3 py-1.5 bg-white border border-slate-200 rounded-lg text-xs focus:outline-none focus:ring-1 focus:ring-indigo-500"
                    />
                    <button onClick={() => handleUpdateSection(course.id)} className="px-3 py-1.5 bg-indigo-600 text-white rounded-lg text-xs font-bold hover:bg-indigo-700">Lưu</button>
                    <button onClick={() => setEditingSectionId(null)} className="px-3 py-1.5 border border-slate-300 text-slate-700 rounded-lg text-xs font-semibold">Hủy</button>
                  </div>
                ) : (
                  <div className="flex items-center justify-between">
                    <h4 className="font-bold text-sm">{section.title}{section.archived && <span className="ml-2 text-[10px] font-bold uppercase text-amber-700">Đã lưu trữ</span>}</h4>
                    {hasCoursePermission(classroom, 'COURSE', 'EDIT', course.id) && (
                      <div className="flex items-center gap-2">
                        <button
                          type="button"
                          aria-label={`Chuyển chương "${section.title}" lên trên`}
                          disabled={sectionIndex === 0}
                          onClick={() => moveSection(course.id, allSections, sectionIndex, -1)}
                          className="text-slate-500 hover:text-indigo-700 disabled:opacity-30 min-h-6 min-w-6 inline-flex items-center justify-center"
                        >
                          <ArrowUp className="w-3.5 h-3.5" />
                        </button>
                        <button
                          type="button"
                          aria-label={`Chuyển chương "${section.title}" xuống dưới`}
                          disabled={sectionIndex === allSections.length - 1}
                          onClick={() => moveSection(course.id, allSections, sectionIndex, 1)}
                          className="text-slate-500 hover:text-indigo-700 disabled:opacity-30 min-h-6 min-w-6 inline-flex items-center justify-center"
                        >
                          <ArrowDown className="w-3.5 h-3.5" />
                        </button>
                        <button
                          onClick={() => openEditSection(section)}
                          className="text-[11px] font-bold text-indigo-600 hover:text-indigo-800"
                        >
                          Sửa
                        </button>
                        <button
                          onClick={() => setConfirmAction({ kind: 'archive-section', sectionId: section.id, courseId: course.id, archived: !!section.archived, label: `${section.archived ? 'khôi phục' : 'lưu trữ'} chương "${section.title}"` })}
                          className="text-[11px] font-bold text-amber-700 hover:text-amber-900"
                        >
                          {section.archived ? 'Khôi phục' : 'Lưu trữ'}
                        </button>
                        <button
                          onClick={() => setConfirmAction({ kind: 'delete-section', sectionId: section.id, courseId: course.id, label: `xóa chương "${section.title}"` })}
                          className="text-[11px] font-bold text-red-600 hover:text-red-800"
                        >
                          Xóa
                        </button>
                      </div>
                    )}
                  </div>
                )}
                <ul className="my-2 space-y-1">{section.lessons?.map((lesson, lessonIndex, allLessons) => (
                  <li key={lesson.id} className="text-xs text-slate-600">
                    {editingLessonId === lesson.id ? (
                      <div className="grid gap-2 md:grid-cols-3 p-2 bg-indigo-50/60 rounded-lg border border-indigo-100">
                        <input aria-label="Tên bài học (sửa)" value={editLessonTitle} onChange={(e) => setEditLessonTitle(e.target.value)} placeholder="Tên bài học" className="rounded border p-2 text-xs" />
                        <select aria-label="Loại bài học (sửa)" value={editLessonType} onChange={(e) => setEditLessonType(e.target.value as typeof editLessonType)} className="rounded border p-2 text-xs"><option value="VIDEO">Video</option><option value="TEXT">Văn bản</option><option value="DOCUMENT">Tài liệu</option><option value="ASSIGNMENT">Bài tập</option></select>
                        <label className="text-xs">Thời lượng (phút) <input aria-label="Thời lượng bài học (sửa)" type="number" min={0} value={editLessonDuration} onChange={(e) => setEditLessonDuration(parseInt(e.target.value) || 0)} className="w-full rounded border p-2 text-xs" /></label>
                        <textarea aria-label="Nội dung bài học (sửa)" value={editLessonContent} onChange={(e) => setEditLessonContent(e.target.value)} placeholder="Nội dung văn bản" className="md:col-span-3 rounded border p-2 text-xs" />
                        {editLessonType === 'VIDEO' && <label className="md:col-span-3">Phụ đề WebVTT<textarea aria-label="Phụ đề WebVTT (sửa)" maxLength={1000000} value={editLessonCaptions} onChange={e => setEditLessonCaptions(e.target.value)} className="mt-1 block w-full rounded border p-2 text-xs" /></label>}
                        <label className="md:col-span-2 text-xs">Video/tài liệu <input type="file" onChange={(e) => uploadEditLessonMedia(course.id, e.target.files?.[0])} disabled={uploadingEditMedia} /></label>
                        {editLessonMediaAssetId && <span className="text-xs text-emerald-700">Đã gắn tệp</span>}
                        <div className="md:col-span-3 flex gap-2">
                          <button onClick={() => handleUpdateLesson(course.id)} className="rounded bg-indigo-600 px-3 py-2 text-xs font-bold text-white">Lưu bài học</button>
                          <button onClick={() => setEditingLessonId(null)} className="rounded border border-slate-300 px-3 py-2 text-xs font-semibold text-slate-700">Hủy</button>
                        </div>
                      </div>
                    ) : (
                      <div className="flex items-center justify-between">
                        <span>{lesson.title} · {lesson.type}{lesson.archived && <span className="ml-2 text-[10px] font-bold uppercase text-amber-700">Đã lưu trữ</span>}</span>
                        {hasCoursePermission(classroom, 'COURSE', 'EDIT', course.id) && (
                          <span className="flex items-center gap-2">
                            <button
                              type="button"
                              aria-label={`Chuyển bài học "${lesson.title}" lên trên`}
                              disabled={lessonIndex === 0}
                              onClick={() => moveLesson(course.id, section.id, allLessons, lessonIndex, -1)}
                              className="text-slate-500 hover:text-indigo-700 disabled:opacity-30 min-h-6 min-w-6 inline-flex items-center justify-center"
                            >
                              <ArrowUp className="w-3.5 h-3.5" />
                            </button>
                            <button
                              type="button"
                              aria-label={`Chuyển bài học "${lesson.title}" xuống dưới`}
                              disabled={lessonIndex === allLessons.length - 1}
                              onClick={() => moveLesson(course.id, section.id, allLessons, lessonIndex, 1)}
                              className="text-slate-500 hover:text-indigo-700 disabled:opacity-30 min-h-6 min-w-6 inline-flex items-center justify-center"
                            >
                              <ArrowDown className="w-3.5 h-3.5" />
                            </button>
                            <button
                              onClick={() => openEditLesson(lesson)}
                              className="text-[11px] font-bold text-indigo-600 hover:text-indigo-800"
                            >
                              Sửa
                            </button>
                            <button
                              onClick={() => setConfirmAction({ kind: 'archive-lesson', lessonId: lesson.id, courseId: course.id, archived: !!lesson.archived, label: `${lesson.archived ? 'khôi phục' : 'lưu trữ'} bài học "${lesson.title}"` })}
                              className="text-[11px] font-bold text-amber-700 hover:text-amber-900"
                            >
                              {lesson.archived ? 'Khôi phục' : 'Lưu trữ'}
                            </button>
                            <button
                              onClick={() => setConfirmAction({ kind: 'delete-lesson', lessonId: lesson.id, courseId: course.id, label: `xóa bài học "${lesson.title}"` })}
                              className="text-[11px] font-bold text-red-600 hover:text-red-800"
                            >
                              Xóa
                            </button>
                          </span>
                        )}
                      </div>
                    )}
                  </li>
                ))}</ul>
                {hasCoursePermission(classroom, 'COURSE', 'EDIT', course.id) && (
                  activeSectionId === section.id ? (
                    <div className="grid gap-2 md:grid-cols-3">
                      <input aria-label="Tên bài học" value={lessonTitle} onChange={(e) => setLessonTitle(e.target.value)} placeholder="Tên bài học" className="rounded border p-2 text-xs" />
                      <select aria-label="Loại bài học" value={lessonType} onChange={(e) => setLessonType(e.target.value as typeof lessonType)} className="rounded border p-2 text-xs"><option value="VIDEO">Video</option><option value="TEXT">Văn bản</option><option value="DOCUMENT">Tài liệu</option><option value="ASSIGNMENT">Bài tập</option></select>
                      <button onClick={() => handleCreateLesson(course.id, section)} className="rounded bg-indigo-600 px-3 py-2 text-xs font-bold text-white">Tạo bài</button>
                      <textarea aria-label="Nội dung bài học" value={lessonContent} onChange={(e) => setLessonContent(e.target.value)} placeholder={lessonType === 'VIDEO' ? 'Bản chép lời và mô tả hình ảnh của video' : 'Nội dung văn bản'} className="md:col-span-3 rounded border p-2 text-xs" />
                      {lessonType === 'VIDEO' && <label className="md:col-span-3">Phụ đề WebVTT<textarea aria-label="Phụ đề WebVTT" maxLength={1000000} value={lessonCaptions} onChange={e => setLessonCaptions(e.target.value)} placeholder={'WEBVTT\n\n00:00.000 --> 00:05.000\nLời giảng và âm thanh cần thiết'} className="mt-1 block w-full rounded border p-2 text-xs" /><span className="block mt-1 text-slate-600">Cung cấp lời thoại, người nói và âm thanh cần thiết; mô tả hình ảnh trong nội dung bài học.</span></label>}
                      <label className="md:col-span-2 text-xs">Video/tài liệu <input type="file" onChange={(e) => uploadLessonMedia(course.id, e.target.files?.[0])} disabled={uploadingMedia} /></label>
                      {lessonMediaAssetId && <span className="text-xs text-emerald-700">Đã tải tệp</span>}
                      <span className="text-xs">{uploadingMedia ? 'Đang tải...' : ''}</span>
                    </div>
                  ) : <button onClick={() => setActiveSectionId(section.id)} className="text-xs font-bold text-indigo-600">Thêm bài học</button>
                )}
              </section>
            ))}
          </div>
        ))}
      </div>

      {/* Create Course Modal */}
      {showCourseModal && (
        <Modal size="lg" title="Tạo khóa học mới" onClose={() => setShowCourseModal(false)}>
            <form onSubmit={handleCreateCourse} className="space-y-4">
              <div>
                <label htmlFor={courseTitleId} className="block text-xs font-semibold text-slate-700 uppercase">Tên khóa học</label>
                <input
                  id={courseTitleId}
                  type="text"
                  required
                  value={courseTitle}
                  onChange={(e) => setCourseTitle(e.target.value)}
                  placeholder="VD: Toán Ôn Thi Học Sinh Giỏi"
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-700 uppercase">Chế độ truy cập</label>
                <p className="mt-1 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600">
                  Tạo khóa miễn phí trước. Để bán khóa, tạo sản phẩm nhắm tới khóa này ở mục Cửa hàng; hệ thống sẽ liên kết quyền truy cập.
                </p>
              </div>

              <div>
                <label htmlFor={courseDescId} className="block text-xs font-semibold text-slate-700 uppercase">Mô tả khóa học</label>
                <textarea
                  id={courseDescId}
                  rows={3}
                  value={courseDesc}
                  onChange={(e) => setCourseDesc(e.target.value)}
                  placeholder="Mô tả tóm tắt nội dung..."
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              <div className="flex justify-end space-x-2 pt-2">
                <button
                  type="button"
                  onClick={() => setShowCourseModal(false)}
                  className="px-4 py-2 border border-slate-300 text-slate-700 rounded-xl text-xs font-semibold"
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  disabled={savingCourse}
                  className="px-4 py-2 bg-indigo-600 text-white rounded-xl text-xs font-bold hover:bg-indigo-700 disabled:opacity-50"
                >
                  {savingCourse ? 'Đang tạo...' : 'Tạo khóa học'}
                </button>
              </div>
            </form>
        </Modal>
      )}

      {/* Edit Course Modal (R13-04) */}
      {editingCourse && (
        <Modal size="lg" title="Sửa khóa học" onClose={() => setEditingCourse(null)}>
            {editCourseError && <p className="mb-3 text-xs font-semibold text-red-600">{editCourseError}</p>}
            <form onSubmit={handleUpdateCourse} className="space-y-4">
              <div>
                <label htmlFor={editCourseTitleId} className="block text-xs font-semibold text-slate-700 uppercase">Tên khóa học</label>
                <input
                  id={editCourseTitleId}
                  type="text"
                  required
                  value={editCourseTitle}
                  onChange={(e) => setEditCourseTitle(e.target.value)}
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                />
              </div>
              <div>
                <label htmlFor={editCourseDescId} className="block text-xs font-semibold text-slate-700 uppercase">Mô tả khóa học</label>
                <textarea
                  id={editCourseDescId}
                  rows={3}
                  value={editCourseDesc}
                  onChange={(e) => setEditCourseDesc(e.target.value)}
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                />
              </div>
              <div>
                <label htmlFor={editCourseCoverId} className="block text-xs font-semibold text-slate-700 uppercase">Ảnh bìa (URL)</label>
                <input
                  id={editCourseCoverId}
                  type="text"
                  value={editCourseCover}
                  onChange={(e) => setEditCourseCover(e.target.value)}
                  placeholder="https://..."
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                />
              </div>
              <div className="flex justify-end space-x-2 pt-2">
                <button
                  type="button"
                  onClick={() => setEditingCourse(null)}
                  className="px-4 py-2 border border-slate-300 text-slate-700 rounded-xl text-xs font-semibold"
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  disabled={savingEditCourse}
                  className="px-4 py-2 bg-indigo-600 text-white rounded-xl text-xs font-bold hover:bg-indigo-700 disabled:opacity-50"
                >
                  {savingEditCourse ? 'Đang lưu...' : 'Lưu thay đổi'}
                </button>
              </div>
            </form>
        </Modal>
      )}

      {/* In-page confirmation, mirrors StudioMembers' confirm pattern */}
      {confirmAction && (
        <Modal size="md" title="Xác nhận thao tác" role="alertdialog" onClose={() => setConfirmAction(null)}>
            <p className="text-sm text-slate-600">Bạn có chắc chắn muốn {confirmAction.label}?</p>
            <div className="flex justify-end space-x-2 pt-4">
              <button
                type="button"
                onClick={() => setConfirmAction(null)}
                className="px-4 py-2 border border-slate-300 text-slate-700 rounded-xl text-xs font-semibold"
              >
                Hủy
              </button>
              <button
                type="button"
                disabled={actionPending}
                onClick={() => runConfirmedAction(confirmAction)}
                className="px-4 py-2 bg-red-600 text-white rounded-xl text-xs font-bold hover:bg-red-700 disabled:opacity-50"
              >
                {actionPending ? 'Đang xử lý...' : 'Xác nhận'}
              </button>
            </div>
        </Modal>
      )}
    </div>
  );
};
