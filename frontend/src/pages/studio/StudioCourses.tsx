import React, { useEffect, useId, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, Course, Section, Lesson } from '../../types';
import { api } from '../../api/client';
import { putToObjectStore } from '../../api/upload';
import { LoadingSpinner, ErrorBanner, StatusBadge } from '../../components/UIStates';
import { Modal } from '../../components/Modal';
import { hasStudioPermission, hasCoursePermission } from '../../api/permissions';
import { nextPosition } from '../../api/ordering';
import { Badge, Button, Card, CoverImage, Field, Input, Textarea, buttonClass, inputClass } from '../../components/ui';
import { ModalActions, Notice, PageHeader, StudioPage, iconActionClass, rowActionClass } from './studioUi';
import { BookOpen, Plus, FolderPlus, FilePlus, Check, Archive, ArchiveRestore, Trash2, Pencil, ArrowUp, ArrowDown } from 'lucide-react';

const LESSON_TYPE_LABELS: Record<string, string> = { VIDEO: 'Video', TEXT: 'Văn bản', DOCUMENT: 'Tài liệu', ASSIGNMENT: 'Bài tập' };

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
  const [createCourseError, setCreateCourseError] = useState<string | null>(null);

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
    setCreateCourseError(null);
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
      setCreateCourseError(err.message || 'Tạo khóa học thất bại');
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
      setSectionActionErrors((errors) => withCourseError(errors, courseId, err.message || 'Tạo chương thất bại'));
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
      setLessonActionErrors((errors) => withCourseError(errors, courseId, err.message || 'Tạo bài học thất bại'));
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
    } catch (err: any) { setLessonActionErrors((errors) => withCourseError(errors, courseId, err.message || 'Không thể tải media')); }
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
    } catch (err: any) { setActionErrors((errors) => withCourseError(errors, courseId, err.message || 'Không thể tải nội dung khóa học')); }
  };

  const lessonTypeOptions = (
    <>
      <option value="VIDEO">Video</option><option value="TEXT">Văn bản</option><option value="DOCUMENT">Tài liệu</option><option value="ASSIGNMENT">Bài tập</option>
    </>
  );
  const fileInputClass = 'mt-1 block w-full text-meta text-slate-600 file:mr-3 file:h-9 file:cursor-pointer file:rounded-[10px] file:border file:border-solid file:border-slate-200 file:bg-white file:px-3 file:text-meta file:font-semibold file:text-slate-900 hover:file:bg-slate-100';

  return (
    <StudioPage>
      <PageHeader
        title="Khóa học"
        description="Tổ chức chương trình học theo khóa, chương và từng bài học."
        action={canCreateCourse && (
          <Button variant="primary" size="md" onClick={() => { setCreateCourseError(null); setShowCourseModal(true); }}>
            <Plus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
            <span>Tạo khóa học mới</span>
          </Button>
        )}
      />

      {loading && <LoadingSpinner message="Đang tải dữ liệu khóa học..." />}
      {error && <ErrorBanner message={error} onRetry={fetchCourses} />}

      {!loading && !error && courses.length === 0 && (
        <Card className="text-center">
          <p className="text-ui text-slate-600">
            Lớp chưa có khóa học nào. {canCreateCourse ? 'Bắt đầu với một khóa ngắn vài bài để học viên có việc để làm ngay.' : ''}
          </p>
        </Card>
      )}

      <div className="space-y-4">
        {courses.map((course, courseIndex) => {
          const canEditCourse = hasCoursePermission(classroom, 'COURSE', 'EDIT', course.id);
          return (
          <article key={course.id} className="rounded-card border border-slate-200 bg-white p-5 shadow-hairline sm:p-6">
            <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
              <div className="flex min-w-0 items-start gap-3">
                <div className="h-12 w-[72px] flex-shrink-0 overflow-hidden rounded-thumb">
                  <CoverImage src={course.coverImageUrl} seed={course.id} icon={<BookOpen className="h-5 w-5" strokeWidth={1.75} />} />
                </div>
                <div className="min-w-0">
                  <h3 className="text-h3 font-semibold text-slate-900">{course.title}</h3>
                  <div className="mt-1 flex flex-wrap items-center gap-1.5">
                    <Badge tone={course.accessMode === 'FREE' ? 'free' : 'paid'} size="sm">
                      {course.accessMode === 'FREE' ? 'Miễn phí' : 'Yêu cầu mua'}
                    </Badge>
                    <StatusBadge status={course.status} />
                  </div>
                </div>
              </div>

              <div className="flex flex-wrap items-center gap-0.5 lg:justify-end">
                {canReorderCourses && courses.length > 1 && (
                  <span className="mr-1 inline-flex items-center">
                    <button
                      type="button"
                      aria-label={`Chuyển khóa học "${course.title}" lên trên`}
                      disabled={courseIndex === 0 || reorderingCourses}
                      onClick={() => moveCourse(courseIndex, -1)}
                      className={iconActionClass()}
                    >
                      <ArrowUp className="h-4 w-4" strokeWidth={1.75} />
                    </button>
                    <button
                      type="button"
                      aria-label={`Chuyển khóa học "${course.title}" xuống dưới`}
                      disabled={courseIndex === courses.length - 1 || reorderingCourses}
                      onClick={() => moveCourse(courseIndex, 1)}
                      className={iconActionClass()}
                    >
                      <ArrowDown className="h-4 w-4" strokeWidth={1.75} />
                    </button>
                  </span>
                )}
                {course.status === 'DRAFT' && hasCoursePermission(classroom, 'COURSE', 'PUBLISH', course.id) && (
                  <button type="button" onClick={() => publishCourse(course.id)} className={rowActionClass('success')}>
                    <Check className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />Xuất bản
                  </button>
                )}
                <button type="button" onClick={() => toggleCourse(course.id)} aria-expanded={!!expandedCourses[course.id]} className={rowActionClass()}>
                  {expandedCourses[course.id] ? 'Ẩn nội dung' : 'Xem bài học'}
                </button>
                {canEditCourse && course.status !== 'ARCHIVED' && (
                  <button type="button" onClick={() => openEditCourse(course)} className={rowActionClass()}>
                    <Pencil className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />
                    <span>Sửa</span>
                  </button>
                )}
                {canEditCourse && (
                  <button type="button" onClick={() => setActiveCourseId(activeCourseId === course.id ? null : course.id)} className={rowActionClass()}>
                    <FolderPlus className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />
                    <span>Thêm chương</span>
                  </button>
                )}
                {canEditCourse && course.status !== 'ARCHIVED' && (
                  <button
                    type="button"
                    onClick={() => setConfirmAction({ kind: 'archive-course', courseId: course.id, label: `lưu trữ khóa học "${course.title}"` })}
                    className={rowActionClass('warn')}
                  >
                    <Archive className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />
                    <span>Lưu trữ</span>
                  </button>
                )}
                {canEditCourse && course.status === 'ARCHIVED' && (
                  <button
                    type="button"
                    onClick={() => setConfirmAction({ kind: 'restore-course', courseId: course.id, label: `khôi phục khóa học "${course.title}"` })}
                    className={rowActionClass('success')}
                  >
                    <ArchiveRestore className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />
                    <span>Khôi phục</span>
                  </button>
                )}
                {canEditCourse && course.status === 'DRAFT' && (
                  <button
                    type="button"
                    onClick={() => setConfirmAction({ kind: 'delete-course', courseId: course.id, label: `xóa vĩnh viễn khóa học "${course.title}"` })}
                    className={rowActionClass('danger')}
                  >
                    <Trash2 className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />
                    <span>Xóa</span>
                  </button>
                )}
              </div>
            </div>
            {actionErrors[course.id] && <p role="alert" className="mt-3 text-meta font-medium text-red-600">{actionErrors[course.id]}</p>}

            <p className="mt-3 text-meta text-slate-600">{course.description || 'Chưa có mô tả'}</p>

            {/* Add Section inline form */}
            {activeCourseId === course.id && (
              <div className="mt-4 flex flex-col gap-2 rounded-2xl border border-blue-100 bg-tint p-3 sm:flex-row sm:items-center">
                <input
                  type="text"
                  aria-label="Tên chương mới"
                  value={sectionTitle}
                  onChange={(e) => setSectionTitle(e.target.value)}
                  placeholder="Tên chương học mới (VD: Chương 1: Giới thiệu)..."
                  className={inputClass('h-10 flex-1')}
                />
                <Button size="md" variant="secondary" onClick={() => handleCreateSection(course.id)}>
                  Lưu chương
                </Button>
              </div>
            )}
            {sectionActionErrors[course.id] && <p role="alert" className="mt-3 text-meta font-medium text-red-600">{sectionActionErrors[course.id]}</p>}
            {lessonActionErrors[course.id] && <p role="alert" className="mt-3 text-meta font-medium text-red-600">{lessonActionErrors[course.id]}</p>}
            {expandedCourses[course.id] && (expandedCourses[course.id].sections ?? []).length === 0 && (
              <p className="mt-4 rounded-btn bg-slate-50 px-3.5 py-3 text-meta text-slate-500">Khóa học chưa có chương nào. {canEditCourse ? 'Bấm “Thêm chương” để bắt đầu.' : ''}</p>
            )}
            {expandedCourses[course.id]?.sections?.map((section, sectionIndex, allSections) => (
              <section key={section.id} className="mt-4 overflow-hidden rounded-2xl border border-slate-200">
                {editingSectionId === section.id ? (
                  <div className="flex flex-col gap-2 bg-slate-50 p-3 sm:flex-row sm:items-center">
                    <input
                      aria-label="Tên chương"
                      value={editSectionTitle}
                      onChange={(e) => setEditSectionTitle(e.target.value)}
                      className={inputClass('h-10 flex-1')}
                    />
                    <div className="flex gap-2">
                      <Button size="sm" variant="primary" onClick={() => handleUpdateSection(course.id)}>Lưu</Button>
                      <Button size="sm" variant="secondary" onClick={() => setEditingSectionId(null)}>Hủy</Button>
                    </div>
                  </div>
                ) : (
                  <div className="flex flex-wrap items-center justify-between gap-2 bg-slate-50 px-4 py-2.5">
                    <h4 className="text-ui font-semibold text-slate-900">
                      {section.title}
                      {section.archived && <Badge tone="warn" size="xs" className="ml-2 align-middle">Đã lưu trữ</Badge>}
                    </h4>
                    {canEditCourse && (
                      <div className="flex items-center gap-0.5">
                        <button
                          type="button"
                          aria-label={`Chuyển chương "${section.title}" lên trên`}
                          disabled={sectionIndex === 0}
                          onClick={() => moveSection(course.id, allSections, sectionIndex, -1)}
                          className={iconActionClass()}
                        >
                          <ArrowUp className="h-3.5 w-3.5" strokeWidth={1.75} />
                        </button>
                        <button
                          type="button"
                          aria-label={`Chuyển chương "${section.title}" xuống dưới`}
                          disabled={sectionIndex === allSections.length - 1}
                          onClick={() => moveSection(course.id, allSections, sectionIndex, 1)}
                          className={iconActionClass()}
                        >
                          <ArrowDown className="h-3.5 w-3.5" strokeWidth={1.75} />
                        </button>
                        <button type="button" onClick={() => openEditSection(section)} className={rowActionClass()}>
                          Sửa
                        </button>
                        <button
                          type="button"
                          onClick={() => setConfirmAction({ kind: 'archive-section', sectionId: section.id, courseId: course.id, archived: !!section.archived, label: `${section.archived ? 'khôi phục' : 'lưu trữ'} chương "${section.title}"` })}
                          className={rowActionClass('warn')}
                        >
                          {section.archived ? 'Khôi phục' : 'Lưu trữ'}
                        </button>
                        <button
                          type="button"
                          onClick={() => setConfirmAction({ kind: 'delete-section', sectionId: section.id, courseId: course.id, label: `xóa chương "${section.title}"` })}
                          className={rowActionClass('danger')}
                        >
                          Xóa
                        </button>
                      </div>
                    )}
                  </div>
                )}
                <ul className="divide-y divide-slate-100">{section.lessons?.map((lesson, lessonIndex, allLessons) => (
                  <li key={lesson.id} className="px-4 py-2">
                    {editingLessonId === lesson.id ? (
                      <div className="grid gap-3 py-2 md:grid-cols-3">
                        <input aria-label="Tên bài học (sửa)" value={editLessonTitle} onChange={(e) => setEditLessonTitle(e.target.value)} placeholder="Tên bài học" className={inputClass('h-10')} />
                        <select aria-label="Loại bài học (sửa)" value={editLessonType} onChange={(e) => setEditLessonType(e.target.value as typeof editLessonType)} className={inputClass('h-10 pr-8')}>{lessonTypeOptions}</select>
                        <label className="text-caption font-semibold text-slate-600">Thời lượng (phút) <input aria-label="Thời lượng bài học (sửa)" type="number" min={0} value={editLessonDuration} onChange={(e) => setEditLessonDuration(parseInt(e.target.value) || 0)} className={inputClass('mt-1 h-10 tabular')} /></label>
                        <textarea aria-label="Nội dung bài học (sửa)" value={editLessonContent} onChange={(e) => setEditLessonContent(e.target.value)} placeholder="Nội dung văn bản" className={inputClass('py-2.5 md:col-span-3')} rows={3} />
                        {editLessonType === 'VIDEO' && <label className="text-caption font-semibold text-slate-600 md:col-span-3">Phụ đề WebVTT<textarea aria-label="Phụ đề WebVTT (sửa)" maxLength={1000000} value={editLessonCaptions} onChange={e => setEditLessonCaptions(e.target.value)} className={inputClass('mt-1 py-2.5 font-mono text-meta')} rows={3} /></label>}
                        <label className="text-caption font-semibold text-slate-600 md:col-span-2">Video/tài liệu <input type="file" onChange={(e) => uploadEditLessonMedia(course.id, e.target.files?.[0])} disabled={uploadingEditMedia} className={fileInputClass} /></label>
                        {editLessonMediaAssetId && <span className="self-end text-meta font-medium text-green-800">Đã gắn tệp</span>}
                        <div className="flex gap-2 md:col-span-3">
                          <Button size="sm" variant="primary" onClick={() => handleUpdateLesson(course.id)}>Lưu bài học</Button>
                          <Button size="sm" variant="secondary" onClick={() => setEditingLessonId(null)}>Hủy</Button>
                        </div>
                      </div>
                    ) : (
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <span className="min-w-0 text-ui text-slate-900">
                          {lesson.title} · {LESSON_TYPE_LABELS[lesson.type] ?? lesson.type}
                          {lesson.archived && <Badge tone="warn" size="xs" className="ml-2 align-middle">Đã lưu trữ</Badge>}
                        </span>
                        {canEditCourse && (
                          <span className="flex items-center gap-0.5">
                            <button
                              type="button"
                              aria-label={`Chuyển bài học "${lesson.title}" lên trên`}
                              disabled={lessonIndex === 0}
                              onClick={() => moveLesson(course.id, section.id, allLessons, lessonIndex, -1)}
                              className={iconActionClass()}
                            >
                              <ArrowUp className="h-3.5 w-3.5" strokeWidth={1.75} />
                            </button>
                            <button
                              type="button"
                              aria-label={`Chuyển bài học "${lesson.title}" xuống dưới`}
                              disabled={lessonIndex === allLessons.length - 1}
                              onClick={() => moveLesson(course.id, section.id, allLessons, lessonIndex, 1)}
                              className={iconActionClass()}
                            >
                              <ArrowDown className="h-3.5 w-3.5" strokeWidth={1.75} />
                            </button>
                            <button type="button" onClick={() => openEditLesson(lesson)} className={rowActionClass()}>
                              Sửa
                            </button>
                            <button
                              type="button"
                              onClick={() => setConfirmAction({ kind: 'archive-lesson', lessonId: lesson.id, courseId: course.id, archived: !!lesson.archived, label: `${lesson.archived ? 'khôi phục' : 'lưu trữ'} bài học "${lesson.title}"` })}
                              className={rowActionClass('warn')}
                            >
                              {lesson.archived ? 'Khôi phục' : 'Lưu trữ'}
                            </button>
                            <button
                              type="button"
                              onClick={() => setConfirmAction({ kind: 'delete-lesson', lessonId: lesson.id, courseId: course.id, label: `xóa bài học "${lesson.title}"` })}
                              className={rowActionClass('danger')}
                            >
                              Xóa
                            </button>
                          </span>
                        )}
                      </div>
                    )}
                  </li>
                ))}</ul>
                {canEditCourse && (
                  <div className="border-t border-slate-100 px-4 py-3">
                    {activeSectionId === section.id ? (
                      <div className="grid gap-3 md:grid-cols-3">
                        <input aria-label="Tên bài học" value={lessonTitle} onChange={(e) => setLessonTitle(e.target.value)} placeholder="Tên bài học" className={inputClass('h-10')} />
                        <select aria-label="Loại bài học" value={lessonType} onChange={(e) => setLessonType(e.target.value as typeof lessonType)} className={inputClass('h-10 pr-8')}>{lessonTypeOptions}</select>
                        <Button size="md" variant="secondary" onClick={() => handleCreateLesson(course.id, section)}>Tạo bài</Button>
                        <textarea aria-label="Nội dung bài học" value={lessonContent} onChange={(e) => setLessonContent(e.target.value)} placeholder={lessonType === 'VIDEO' ? 'Bản chép lời và mô tả hình ảnh của video' : 'Nội dung văn bản'} className={inputClass('py-2.5 md:col-span-3')} rows={3} />
                        {lessonType === 'VIDEO' && <label className="text-caption font-semibold text-slate-600 md:col-span-3">Phụ đề WebVTT<textarea aria-label="Phụ đề WebVTT" maxLength={1000000} value={lessonCaptions} onChange={e => setLessonCaptions(e.target.value)} placeholder={'WEBVTT\n\n00:00.000 --> 00:05.000\nLời giảng và âm thanh cần thiết'} className={inputClass('mt-1 py-2.5 font-mono text-meta')} rows={3} /><span className="mt-1 block font-normal text-slate-500">Cung cấp lời thoại, người nói và âm thanh cần thiết; mô tả hình ảnh trong nội dung bài học.</span></label>}
                        <label className="text-caption font-semibold text-slate-600 md:col-span-2">Video/tài liệu <input type="file" onChange={(e) => uploadLessonMedia(course.id, e.target.files?.[0])} disabled={uploadingMedia} className={fileInputClass} /></label>
                        {lessonMediaAssetId && <span className="self-end text-meta font-medium text-green-800">Đã tải tệp</span>}
                        <span className="self-end text-meta text-slate-500">{uploadingMedia ? 'Đang tải...' : ''}</span>
                      </div>
                    ) : (
                      <button type="button" onClick={() => setActiveSectionId(section.id)} className={buttonClass('tertiary', 'sm')}>
                        <FilePlus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                        <span>Thêm bài học</span>
                      </button>
                    )}
                  </div>
                )}
              </section>
            ))}
          </article>
          );
        })}
      </div>

      {/* Create Course Modal */}
      {showCourseModal && (
        <Modal size="lg" title="Tạo khóa học mới" onClose={() => setShowCourseModal(false)}>
          <form onSubmit={handleCreateCourse} className="space-y-5">
            {createCourseError && <ErrorBanner message={createCourseError} />}
            <Field label="Tên khóa học" htmlFor={courseTitleId}>
              <Input id={courseTitleId} type="text" required value={courseTitle} onChange={(e) => setCourseTitle(e.target.value)} placeholder="VD: Toán Ôn Thi Học Sinh Giỏi" />
            </Field>

            <div className="space-y-1.5">
              <p className="text-meta font-semibold text-slate-900">Chế độ truy cập</p>
              <Notice tone="info">
                Tạo khóa miễn phí trước. Để bán khóa, tạo sản phẩm nhắm tới khóa này ở mục Shop; hệ thống sẽ liên kết quyền truy cập.
              </Notice>
            </div>

            <Field label="Mô tả khóa học" htmlFor={courseDescId}>
              <Textarea id={courseDescId} rows={3} value={courseDesc} onChange={(e) => setCourseDesc(e.target.value)} placeholder="Học xong khóa này, học viên làm được gì..." />
            </Field>

            <ModalActions>
              <Button variant="secondary" onClick={() => setShowCourseModal(false)}>Hủy</Button>
              <Button type="submit" variant="primary" disabled={savingCourse}>{savingCourse ? 'Đang tạo...' : 'Tạo khóa học'}</Button>
            </ModalActions>
          </form>
        </Modal>
      )}

      {/* Edit Course Modal (R13-04) */}
      {editingCourse && (
        <Modal size="lg" title="Sửa khóa học" onClose={() => setEditingCourse(null)}>
          <form onSubmit={handleUpdateCourse} className="space-y-5">
            {editCourseError && <ErrorBanner message={editCourseError} />}
            <Field label="Tên khóa học" htmlFor={editCourseTitleId}>
              <Input id={editCourseTitleId} type="text" required value={editCourseTitle} onChange={(e) => setEditCourseTitle(e.target.value)} />
            </Field>
            <Field label="Mô tả khóa học" htmlFor={editCourseDescId}>
              <Textarea id={editCourseDescId} rows={3} value={editCourseDesc} onChange={(e) => setEditCourseDesc(e.target.value)} />
            </Field>
            <Field label="Ảnh bìa (URL)" htmlFor={editCourseCoverId}>
              <Input id={editCourseCoverId} type="text" value={editCourseCover} onChange={(e) => setEditCourseCover(e.target.value)} placeholder="https://..." />
            </Field>
            <ModalActions>
              <Button variant="secondary" onClick={() => setEditingCourse(null)}>Hủy</Button>
              <Button type="submit" variant="primary" disabled={savingEditCourse}>{savingEditCourse ? 'Đang lưu...' : 'Lưu thay đổi'}</Button>
            </ModalActions>
          </form>
        </Modal>
      )}

      {/* In-page confirmation, mirrors StudioMembers' confirm pattern */}
      {confirmAction && (
        <Modal size="md" title="Xác nhận thao tác" role="alertdialog" onClose={() => setConfirmAction(null)}>
          <p className="text-ui text-slate-600">Bạn có chắc chắn muốn {confirmAction.label}?</p>
          <ModalActions className="mt-5">
            <Button variant="secondary" onClick={() => setConfirmAction(null)}>Hủy</Button>
            <Button variant="danger" disabled={actionPending} onClick={() => runConfirmedAction(confirmAction)}>
              {actionPending ? 'Đang xử lý...' : 'Xác nhận'}
            </Button>
          </ModalActions>
        </Modal>
      )}
    </StudioPage>
  );
};
