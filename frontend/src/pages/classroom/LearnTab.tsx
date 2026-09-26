import React, { useEffect, useState } from 'react';
import { useOutletContext, Link, useNavigate } from 'react-router-dom';
import { Classroom, Course } from '../../types';
import { api } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { LoadingSpinner, ErrorBanner, EmptyState, StatusBadge } from '../../components/UIStates';
import { BookOpen, CheckCircle, Lock, PlayCircle, ChevronRight, ShoppingBag } from 'lucide-react';

export const LearnTab: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const { user } = useAuth();
  const navigate = useNavigate();

  const [courses, setCourses] = useState<Course[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Selected course details
  const [selectedCourse, setSelectedCourse] = useState<Course | null>(null);
  const [loadingDetails, setLoadingDetails] = useState(false);

  const fetchCourses = async () => {
    try {
      setLoading(true);
      const data = await api.get<Course[]>(`/classes/${classroom.id}/courses`);
      setCourses(data || []);
      if (data && data.length > 0 && !selectedCourse) {
        loadCourseDetails(data[0].id);
      }
    } catch (err: any) {
      setError(err.message || 'Không thể tải danh sách khóa học');
    } finally {
      setLoading(false);
    }
  };

  const loadCourseDetails = async (courseId: string) => {
    try {
      setLoadingDetails(true);
      const data = await api.get<Course>(`/courses/${courseId}`);
      setSelectedCourse(data);
    } catch (err: any) {
      alert(err.message || 'Không thể tải chi tiết khóa học');
    } finally {
      setLoadingDetails(false);
    }
  };

  useEffect(() => {
    fetchCourses();
  }, [classroom.id, user]);

  return (
    <div className="space-y-8">
      {/* Course List Carousel / Grid */}
      <div>
        <div className="flex justify-between items-center mb-4">
          <div>
            <h2 className="text-xl font-extrabold text-slate-900 tracking-tight">Các khóa học trong lớp</h2>
            <p className="text-xs text-slate-500">Chọn khóa học để xem lộ trình và các bài học</p>
          </div>
        </div>

        {loading && <LoadingSpinner message="Đang tải danh mục khóa học..." />}
        {error && <ErrorBanner message={error} onRetry={fetchCourses} />}

        {!loading && !error && courses.length === 0 && (
          <EmptyState
            title="Chưa có khóa học nào"
            description="Giáo viên đang chuẩn bị bài giảng, vui lòng quay lại sau!"
          />
        )}

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
          {courses.map((course) => {
            const isSelected = selectedCourse?.id === course.id;
            return (
              <div
                key={course.id}
                onClick={() => loadCourseDetails(course.id)}
                className={`cursor-pointer rounded-2xl border p-5 transition flex flex-col justify-between ${
                  isSelected
                    ? 'bg-indigo-50/50 border-indigo-500 shadow-md ring-2 ring-indigo-500/20'
                    : 'bg-white border-slate-200 hover:border-indigo-300 hover:shadow-sm'
                }`}
              >
                <div>
                  <div className="flex items-center justify-between mb-3">
                    <span
                      className={`text-[10px] font-bold px-2 py-0.5 rounded-full uppercase tracking-wider ${
                        course.accessMode === 'FREE'
                          ? 'bg-emerald-100 text-emerald-800'
                          : 'bg-amber-100 text-amber-800'
                      }`}
                    >
                      {course.accessMode === 'FREE' ? 'Miễn phí' : 'Khóa PRO/Trả phí'}
                    </span>

                    {course.canLearn ? (
                      <span className="flex items-center space-x-1 text-xs font-semibold text-emerald-600">
                        <CheckCircle className="w-3.5 h-3.5" />
                        <span>Được phép học</span>
                      </span>
                    ) : (
                      <span className="flex items-center space-x-1 text-xs font-semibold text-slate-400">
                        <Lock className="w-3.5 h-3.5" />
                        <span>Khóa bảo vệ</span>
                      </span>
                    )}
                  </div>

                  <h3 className="text-base font-bold text-slate-900 line-clamp-1 mb-1">{course.title}</h3>
                  <p className="text-xs text-slate-500 line-clamp-2 mb-4">
                    {course.description || 'Chưa có mô tả chi tiết cho khóa học.'}
                  </p>
                </div>

                {/* Progress bar */}
                <div>
                  <div className="flex justify-between items-center text-[11px] text-slate-500 font-semibold mb-1">
                    <span>Tiến độ: {course.completedLessons}/{course.totalLessons} bài</span>
                    <span>{Math.round(course.progressPercentage)}%</span>
                  </div>
                  <div className="w-full h-1.5 bg-slate-100 rounded-full overflow-hidden">
                    <div
                      className="h-full bg-indigo-600 rounded-full transition-all duration-500"
                      style={{ width: `${course.progressPercentage}%` }}
                    />
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Selected Course Curriculum / Sections & Lessons */}
      {selectedCourse && (
        <div className="bg-white rounded-2xl border border-slate-200 p-6 shadow-sm">
          <div className="flex flex-col md:flex-row md:items-center justify-between pb-6 border-b border-slate-100 gap-4">
            <div>
              <div className="flex items-center space-x-2 mb-1">
                <span className="text-xs font-bold text-indigo-600 uppercase tracking-wider">
                  Chương trình chi tiết
                </span>
                {selectedCourse.accessMode === 'PURCHASE_REQUIRED' && (
                  <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-amber-100 text-amber-800">
                    Yêu cầu mua
                  </span>
                )}
              </div>
              <h2 className="text-2xl font-black text-slate-900">{selectedCourse.title}</h2>
              <p className="text-sm text-slate-500 mt-1 max-w-2xl">{selectedCourse.description}</p>
            </div>

            {!selectedCourse.canLearn && (
              <div className="flex-shrink-0">
                <Link
                  to={`/classes/${classroom.slug}/store`}
                  className="inline-flex items-center space-x-2 px-5 py-2.5 bg-amber-500 hover:bg-amber-600 text-white font-bold text-sm rounded-xl shadow-md transition"
                >
                  <ShoppingBag className="w-4 h-4" />
                  <span>Mua khóa học tại Cửa hàng</span>
                </Link>
              </div>
            )}
          </div>

          {loadingDetails && <LoadingSpinner message="Đang tải giáo trình..." />}

          {!loadingDetails && selectedCourse.sections && selectedCourse.sections.length === 0 && (
            <div className="text-center py-8 text-sm text-slate-500">Khóa học chưa có bài học nào.</div>
          )}

          {!loadingDetails && selectedCourse.sections && (
            <div className="mt-6 space-y-6">
              {selectedCourse.sections.map((section, idx) => (
                <div key={section.id} className="border border-slate-100 rounded-xl overflow-hidden">
                  <div className="bg-slate-50 px-4 py-3 border-b border-slate-100 flex items-center justify-between">
                    <span className="text-sm font-bold text-slate-800">
                      {section.title}
                    </span>
                    <span className="text-xs text-slate-400 font-medium">
                      {section.lessons.length} bài học
                    </span>
                  </div>

                  <div className="divide-y divide-slate-100">
                    {section.lessons.map((lesson) => (
                      <div
                        key={lesson.id}
                        className="px-4 py-3 flex items-center justify-between hover:bg-slate-50/80 transition"
                      >
                        <div className="flex items-center space-x-3">
                          {lesson.completed ? (
                            <CheckCircle className="w-4 h-4 text-emerald-500 flex-shrink-0" />
                          ) : (
                            <PlayCircle className="w-4 h-4 text-slate-400 flex-shrink-0" />
                          )}
                          <div>
                            <span className="text-sm font-semibold text-slate-800 block">
                              {lesson.title}
                            </span>
                            <span className="text-[11px] text-slate-400">
                              {lesson.type} • {lesson.durationMinutes > 0 ? `${lesson.durationMinutes} phút` : 'Tài liệu'}
                            </span>
                          </div>
                        </div>

                        {selectedCourse.canLearn ? (
                          <Link
                            to={`/classes/${classroom.slug}/learn/lessons/${lesson.id}`}
                            className="inline-flex items-center space-x-1 px-3 py-1.5 bg-indigo-50 hover:bg-indigo-100 text-indigo-700 text-xs font-bold rounded-lg transition"
                          >
                            <span>Vào học</span>
                            <ChevronRight className="w-3.5 h-3.5" />
                          </Link>
                        ) : (
                          <span className="text-xs text-slate-400 flex items-center space-x-1">
                            <Lock className="w-3.5 h-3.5" />
                            <span>Khóa</span>
                          </span>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
};
