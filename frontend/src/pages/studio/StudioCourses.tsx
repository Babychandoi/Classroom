import React, { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, Course } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { BookOpen, Plus, FolderPlus, FilePlus, Check } from 'lucide-react';

export const StudioCourses: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const canCreateCourse = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('COURSE:CREATE');
  const canEditCourse = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('COURSE:EDIT');
  const canPublishCourse = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('COURSE:PUBLISH');

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
  const [lessonMediaAssetId, setLessonMediaAssetId] = useState('');
  const [uploadingMedia, setUploadingMedia] = useState(false);

  const fetchCourses = async () => {
    try {
      setLoading(true);
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
    try { await api.post(`/courses/${id}/publish`); await fetchCourses(); }
    catch (err: any) { setError(err.message || 'Không thể xuất bản khóa học'); }
  };

  const handleCreateSection = async (courseId: string) => {
    if (!sectionTitle.trim()) return;
    try {
      await api.post(`/courses/${courseId}/sections`, {
        title: sectionTitle,
        position: 1,
      });
      setSectionTitle('');
      setActiveCourseId(null);
      await fetchCourses();
    } catch (err: any) {
      alert(err.message || 'Tạo chương thất bại');
    }
  };

  const handleCreateLesson = async (sectionId: string) => {
    if (!lessonTitle.trim()) return;
    try {
      await api.post(`/sections/${sectionId}/lessons`, {
        title: lessonTitle,
        type: lessonType,
        contentText: lessonContent,
        mediaAssetId: lessonMediaAssetId || null,
      });
      setLessonTitle('');
      setLessonContent('');
      setLessonMediaAssetId('');
      setActiveSectionId(null);
      await fetchCourses();
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
      const uploaded = await fetch(intent.uploadUrl, { method: 'PUT', headers: { 'Content-Type': file.type }, body: file });
      if (!uploaded.ok) throw new Error(`Tải file thất bại (${uploaded.status})`);
      await api.post(`/media/${intent.assetId}/complete`);
      setLessonMediaAssetId(intent.assetId);
    } catch (err: any) { alert(err.message || 'Không thể tải media'); }
    finally { setUploadingMedia(false); }
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
          <p className="text-xs text-slate-500">Tổ chức chương trình học theo khóa, chương và từng bài học</p>
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
        {courses.map((course) => (
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

              <div className="flex gap-3">
              {course.status === 'DRAFT' && canPublishCourse && <button onClick={() => publishCourse(course.id)} className="text-xs font-bold text-indigo-700">Xuất bản</button>}
              <button onClick={() => toggleCourse(course.id)} className="text-xs font-bold text-slate-600">
                {expandedCourses[course.id] ? 'Ẩn nội dung' : 'Xem bài học'}
              </button>
              {(canCreateCourse || canEditCourse) && <button
                onClick={() => setActiveCourseId(activeCourseId === course.id ? null : course.id)}
                className="inline-flex items-center space-x-1 text-xs font-bold text-indigo-600 hover:text-indigo-800"
              >
                <FolderPlus className="w-3.5 h-3.5" />
                <span>Thêm chương</span>
              </button>}
              </div>
            </div>

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
            {expandedCourses[course.id]?.sections?.map((section) => (
              <section key={section.id} className="mt-4 border-t border-slate-100 pt-3">
                <h4 className="font-bold text-sm">{section.title}</h4>
                <ul className="my-2 space-y-1">{section.lessons?.map((lesson) => <li key={lesson.id} className="text-xs text-slate-600">{lesson.title} · {lesson.type}</li>)}</ul>
                {(canCreateCourse || canEditCourse) && (
                  activeSectionId === section.id ? (
                    <div className="grid gap-2 md:grid-cols-3">
                      <input aria-label="Tên bài học" value={lessonTitle} onChange={(e) => setLessonTitle(e.target.value)} placeholder="Tên bài học" className="rounded border p-2 text-xs" />
                      <select aria-label="Loại bài học" value={lessonType} onChange={(e) => setLessonType(e.target.value as typeof lessonType)} className="rounded border p-2 text-xs"><option value="VIDEO">Video</option><option value="TEXT">Văn bản</option><option value="DOCUMENT">Tài liệu</option><option value="ASSIGNMENT">Bài tập</option></select>
                      <button onClick={() => handleCreateLesson(section.id)} className="rounded bg-indigo-600 px-3 py-2 text-xs font-bold text-white">Tạo bài</button>
                      <textarea aria-label="Nội dung bài học" value={lessonContent} onChange={(e) => setLessonContent(e.target.value)} placeholder="Nội dung văn bản" className="md:col-span-3 rounded border p-2 text-xs" />
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
        <div className="fixed inset-0 bg-slate-900/50 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-white rounded-2xl max-w-lg w-full p-6 shadow-xl border border-slate-200">
            <h3 className="text-lg font-bold text-slate-900 mb-4">Tạo khóa học mới</h3>
            <form onSubmit={handleCreateCourse} className="space-y-4">
              <div>
                <label className="block text-xs font-semibold text-slate-700 uppercase">Tên khóa học</label>
                <input
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
                <label className="block text-xs font-semibold text-slate-700 uppercase">Mô tả khóa học</label>
                <textarea
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
          </div>
        </div>
      )}
    </div>
  );
};
