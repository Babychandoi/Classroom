import React, { useState } from 'react';
import { useOutletContext, Link } from 'react-router-dom';
import { Classroom } from '../../types';
import { api } from '../../api/client';
import { ClassBadges } from '../../components/ClassBadges';
import { Users, BookOpen, Award, ShoppingBag, Sparkles, RefreshCw } from 'lucide-react';

export const StudioOverview: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const [rebuilding, setRebuilding] = useState(false);

  const handleRebuildLeaderboard = async () => {
    setRebuilding(true);
    try {
      await api.post(`/classes/${classroom.id}/leaderboard/rebuild`);
      alert('Đã tính toán và tái tạo lại Bảng Xếp Hạng thành công!');
    } catch (err: any) {
      alert(err.message || 'Tái tạo thất bại');
    } finally {
      setRebuilding(false);
    }
  };

  return (
    <div className="max-w-6xl mx-auto space-y-8">
      <div>
        <h1 className="text-2xl font-black text-slate-900 tracking-tight">Tổng quan Studio Quản Trị</h1>
        <p className="text-xs text-slate-600">Trung tâm điều hành và thiết lập nội dung của lớp học</p>
        {/* D-19 */}
        <ClassBadges classroom={classroom} showPublic className="mt-2" />
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5">
        <div className="bg-white p-5 rounded-2xl border border-slate-200 shadow-sm flex items-center space-x-4">
          <div className="w-12 h-12 rounded-xl bg-indigo-50 text-indigo-600 flex items-center justify-center flex-shrink-0">
            <Users className="w-6 h-6" />
          </div>
          <div>
            <span className="text-xs font-semibold text-slate-500 uppercase">Thành viên</span>
            <div className="text-2xl font-black text-slate-900">{classroom.memberCount}</div>
          </div>
        </div>

        <div className="bg-white p-5 rounded-2xl border border-slate-200 shadow-sm flex items-center space-x-4">
          <div className="w-12 h-12 rounded-xl bg-blue-50 text-blue-600 flex items-center justify-center flex-shrink-0">
            <BookOpen className="w-6 h-6" />
          </div>
          <div>
            <span className="text-xs font-semibold text-slate-500 uppercase">Khóa học</span>
            <div className="text-2xl font-black text-slate-900">Hoạt động</div>
          </div>
        </div>

        <div className="bg-white p-5 rounded-2xl border border-slate-200 shadow-sm flex items-center space-x-4">
          <div className="w-12 h-12 rounded-xl bg-amber-50 text-amber-600 flex items-center justify-center flex-shrink-0">
            <Award className="w-6 h-6" />
          </div>
          <div>
            <span className="text-xs font-semibold text-slate-500 uppercase">Kỳ thi</span>
            <div className="text-2xl font-black text-slate-900">Sẵn sàng</div>
          </div>
        </div>

        <div className="bg-white p-5 rounded-2xl border border-slate-200 shadow-sm flex items-center space-x-4">
          <div className="w-12 h-12 rounded-xl bg-emerald-50 text-emerald-600 flex items-center justify-center flex-shrink-0">
            <ShoppingBag className="w-6 h-6" />
          </div>
          <div>
            <span className="text-xs font-semibold text-slate-500 uppercase">Cửa hàng</span>
            <div className="text-2xl font-black text-slate-900">Mở bán</div>
          </div>
        </div>
      </div>

      {/* Quick Actions */}
      <div className="bg-white p-6 rounded-2xl border border-slate-200 shadow-sm">
        <h3 className="text-sm font-bold text-slate-900 uppercase tracking-wider mb-4">Thao tác nhanh</h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <Link
            to={`/studio/classes/${classroom.id}/courses`}
            className="p-4 bg-slate-50 hover:bg-indigo-50 border border-slate-200 hover:border-indigo-200 rounded-xl transition text-left group"
          >
            <BookOpen className="w-5 h-5 text-indigo-600 mb-2" />
            <h4 className="text-sm font-bold text-slate-900 group-hover:text-indigo-600">Thêm khóa học mới</h4>
            <p className="text-xs text-slate-500 mt-0.5">Tạo các chương và bài giảng video hoặc tài liệu</p>
          </Link>

          <Link
            to={`/studio/classes/${classroom.id}/exams`}
            className="p-4 bg-slate-50 hover:bg-indigo-50 border border-slate-200 hover:border-indigo-200 rounded-xl transition text-left group"
          >
            <Award className="w-5 h-5 text-amber-600 mb-2" />
            <h4 className="text-sm font-bold text-slate-900 group-hover:text-indigo-600">Tạo đề thi & câu hỏi</h4>
            <p className="text-xs text-slate-500 mt-0.5">Thiết lập bài thi trắc nghiệm hoặc tự luận</p>
          </Link>

          <button
            onClick={handleRebuildLeaderboard}
            disabled={rebuilding}
            className="p-4 bg-slate-50 hover:bg-amber-50 border border-slate-200 hover:border-amber-200 rounded-xl transition text-left group disabled:opacity-50"
          >
            <RefreshCw className={`w-5 h-5 text-amber-600 mb-2 ${rebuilding ? 'animate-spin' : ''}`} />
            <h4 className="text-sm font-bold text-slate-900 group-hover:text-amber-700">Tái tạo Bảng Xếp Hạng</h4>
            <p className="text-xs text-slate-500 mt-0.5">Tính lại điểm tích lũy của toàn bộ học viên</p>
          </button>
        </div>
      </div>
    </div>
  );
};
