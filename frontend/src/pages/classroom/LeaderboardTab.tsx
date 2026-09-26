import React, { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, LeaderboardEntry } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner, EmptyState } from '../../components/UIStates';
import { Sparkles, Trophy, Medal, Award } from 'lucide-react';

export const LeaderboardTab: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();

  const [leaderboard, setLeaderboard] = useState<LeaderboardEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchLeaderboard = async () => {
    try {
      setLoading(true);
      const data = await api.get<LeaderboardEntry[]>(`/classes/${classroom.id}/leaderboard`);
      setLeaderboard(data || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải Bảng Xếp Hạng');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchLeaderboard();
  }, [classroom.id]);

  const top1 = leaderboard[0];
  const top2 = leaderboard[1];
  const top3 = leaderboard[2];

  return (
    <div className="max-w-4xl mx-auto space-y-8">
      {/* Header */}
      <div className="text-center max-w-xl mx-auto">
        <div className="inline-flex items-center space-x-2 px-3 py-1 rounded-full bg-amber-100 text-amber-800 text-xs font-bold mb-2">
          <Sparkles className="w-3.5 h-3.5" />
          <span>Vinh danh thành tích học tập</span>
        </div>
        <h2 className="text-2xl font-black text-slate-900 tracking-tight">Bảng Xếp Hạng Lớp Học</h2>
        <p className="text-xs text-slate-500 mt-1">
          Điểm số được quy đổi từ kết quả bài thi cao nhất đã công bố của mỗi kỳ thi.
        </p>
      </div>

      {loading && <LoadingSpinner message="Đang cập nhật bảng xếp hạng..." />}
      {error && <ErrorBanner message={error} onRetry={fetchLeaderboard} />}

      {!loading && !error && leaderboard.length === 0 && (
        <EmptyState
          title="Chưa có học viên nào trên bảng xếp hạng"
          description="Hãy là người đầu tiên tham gia thi và ghi tên mình lên bảng vàng!"
        />
      )}

      {/* Top 3 Podium Cards */}
      {!loading && leaderboard.length > 0 && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 pt-4">
          {/* Top 2 */}
          {top2 && (
            <div className="bg-white rounded-2xl border border-slate-200 p-5 text-center shadow-sm flex flex-col justify-between order-2 md:order-1 mt-4">
              <div>
                <div className="w-12 h-12 rounded-full bg-slate-100 border-2 border-slate-300 text-slate-600 font-black text-base mx-auto flex items-center justify-center mb-2">
                  2
                </div>
                <h4 className="text-sm font-bold text-slate-900 line-clamp-1">{top2.userFullName}</h4>
                <span className="text-xs text-indigo-600 font-semibold">{top2.currentTier}</span>
              </div>
              <div className="mt-4 pt-3 border-t border-slate-100 text-lg font-black text-slate-800">
                {top2.totalPoints} <span className="text-xs text-slate-400 font-normal">điểm</span>
              </div>
            </div>
          )}

          {/* Top 1 */}
          {top1 && (
            <div className="bg-gradient-to-b from-amber-50 to-white rounded-2xl border-2 border-amber-300 p-6 text-center shadow-md flex flex-col justify-between order-1 md:order-2 scale-105">
              <div>
                <div className="w-14 h-14 rounded-full bg-gradient-to-tr from-amber-400 to-amber-500 text-white font-black text-lg mx-auto flex items-center justify-center shadow-md shadow-amber-200 mb-2">
                  <Trophy className="w-7 h-7" />
                </div>
                <div className="text-[10px] uppercase font-bold text-amber-600 tracking-wider">Hạng 1</div>
                <h4 className="text-base font-extrabold text-slate-900 line-clamp-1">{top1.userFullName}</h4>
                <span className="text-xs text-amber-700 font-bold px-2.5 py-0.5 rounded-full bg-amber-100 mt-1 inline-block">
                  {top1.currentTier}
                </span>
              </div>
              <div className="mt-4 pt-3 border-t border-amber-100 text-2xl font-black text-amber-900">
                {top1.totalPoints} <span className="text-xs text-amber-700 font-normal">điểm</span>
              </div>
            </div>
          )}

          {/* Top 3 */}
          {top3 && (
            <div className="bg-white rounded-2xl border border-slate-200 p-5 text-center shadow-sm flex flex-col justify-between order-3 mt-4">
              <div>
                <div className="w-12 h-12 rounded-full bg-amber-50 border-2 border-amber-200 text-amber-800 font-black text-base mx-auto flex items-center justify-center mb-2">
                  3
                </div>
                <h4 className="text-sm font-bold text-slate-900 line-clamp-1">{top3.userFullName}</h4>
                <span className="text-xs text-indigo-600 font-semibold">{top3.currentTier}</span>
              </div>
              <div className="mt-4 pt-3 border-t border-slate-100 text-lg font-black text-slate-800">
                {top3.totalPoints} <span className="text-xs text-slate-400 font-normal">điểm</span>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Full Leaderboard Table */}
      {!loading && leaderboard.length > 0 && (
        <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
          <div className="px-6 py-4 border-b border-slate-100 flex items-center justify-between">
            <span className="text-xs font-bold text-slate-700 uppercase tracking-wider">Danh sách xếp hạng</span>
            <span className="text-xs text-slate-400 font-medium">{leaderboard.length} học viên</span>
          </div>

          <div className="divide-y divide-slate-100">
            {leaderboard.map((entry) => (
              <div
                key={entry.userId}
                className="px-6 py-4 flex items-center justify-between hover:bg-slate-50/70 transition"
              >
                <div className="flex items-center space-x-4">
                  <div
                    className={`w-7 h-7 rounded-full flex items-center justify-center font-bold text-xs ${
                      entry.rank === 1
                        ? 'bg-amber-400 text-white'
                        : entry.rank === 2
                        ? 'bg-slate-300 text-slate-800'
                        : entry.rank === 3
                        ? 'bg-amber-200 text-amber-900'
                        : 'bg-slate-100 text-slate-600'
                    }`}
                  >
                    {entry.rank}
                  </div>

                  <div>
                    <span className="text-sm font-bold text-slate-900 block">{entry.userFullName}</span>
                    <span className="text-[11px] text-indigo-600 font-semibold">{entry.currentTier}</span>
                  </div>
                </div>

                <div className="text-right">
                  <span className="text-base font-extrabold text-slate-900">{entry.totalPoints}</span>
                  <span className="text-xs text-slate-400 ml-1">điểm</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};
