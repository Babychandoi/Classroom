import React, { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, LeaderboardEntry, Exam } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner, EmptyState } from '../../components/UIStates';
import { Sparkles, Trophy, Medal, Award } from 'lucide-react';

export const LeaderboardTab: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();

  const [leaderboard, setLeaderboard] = useState<LeaderboardEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // R13-08 (UI spec §2 "bộ lọc kỳ"): filter the board by one exam's published results instead of
  // the class-wide total. "" means the class-wide (all exams) board.
  const [exams, setExams] = useState<Exam[]>([]);
  const [selectedExamId, setSelectedExamId] = useState<string>('');

  const fetchLeaderboard = async () => {
    try {
      setLoading(true);
      setError(null);
      const query = selectedExamId ? `?examId=${selectedExamId}` : '';
      const data = await api.get<LeaderboardEntry[]>(`/classes/${classroom.id}/leaderboard${query}`);
      setLeaderboard(data || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải Bảng Xếp Hạng');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchLeaderboard();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classroom.id, selectedExamId]);

  useEffect(() => {
    api.get<Exam[]>(`/classes/${classroom.id}/exams`).then((data) => setExams(data || [])).catch(() => setExams([]));
  }, [classroom.id]);

  // R8-04: with standard competition ranking (1,1,3,...) ties share a rank, so the podium must be
  // selected by rank rather than by array index — e.g. two students tied for rank 1 both belong in
  // the "Hạng 1" slot, and there may then be no rank-2 entry at all.
  const top1s = leaderboard.filter((e) => e.rank === 1);
  const top2s = leaderboard.filter((e) => e.rank === 2);
  const top3s = leaderboard.filter((e) => e.rank === 3);

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
          {selectedExamId
            ? 'Xếp hạng theo điểm cao nhất đã công bố của kỳ thi đã chọn.'
            : 'Điểm số được quy đổi từ kết quả bài thi cao nhất đã công bố của mỗi kỳ thi.'}
        </p>
      </div>

      {/* R13-08: "bộ lọc kỳ" — filter the board to one exam's published results */}
      <div className="flex justify-center">
        <label className="flex items-center space-x-2 text-xs font-semibold text-slate-600">
          <span>Bộ lọc kỳ thi:</span>
          <select
            value={selectedExamId}
            onChange={(e) => setSelectedExamId(e.target.value)}
            className="rounded-lg border border-slate-300 px-2.5 py-1.5 text-xs font-semibold text-slate-800"
          >
            <option value="">Toàn bộ lớp (tổng điểm)</option>
            {exams.map((exam) => (
              <option key={exam.id} value={exam.id}>{exam.title}</option>
            ))}
          </select>
        </label>
      </div>

      {loading && <LoadingSpinner message="Đang cập nhật bảng xếp hạng..." />}
      {error && <ErrorBanner message={error} onRetry={fetchLeaderboard} />}

      {!loading && !error && leaderboard.length === 0 && (
        <EmptyState
          title="Chưa có học viên nào trên bảng xếp hạng"
          description="Hãy là người đầu tiên tham gia thi và ghi tên mình lên bảng vàng!"
        />
      )}

      {/* Top 3 Podium Cards — grouped by RANK, not array position, so tied students share a slot */}
      {!loading && leaderboard.length > 0 && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 pt-4">
          {/* Hạng 2 */}
          {top2s.length > 0 && (
            <div className="order-2 md:order-1 mt-4 space-y-3">
              {top2s.map((entry, i) => (
                <div key={entry.userId ?? `rank2-${i}`} className="bg-white rounded-2xl border border-slate-200 p-5 text-center shadow-sm flex flex-col justify-between">
                  <div>
                    <div className="w-12 h-12 rounded-full bg-slate-100 border-2 border-slate-300 text-slate-600 font-black text-base mx-auto flex items-center justify-center mb-2">
                      2
                    </div>
                    <h4 className="text-sm font-bold text-slate-900 line-clamp-1">{entry.userFullName}</h4>
                    {entry.currentTier && <span className="text-xs text-indigo-600 font-semibold">{entry.currentTier}</span>}
                  </div>
                  <div className="mt-4 pt-3 border-t border-slate-100 text-lg font-black text-slate-800">
                    {entry.totalPoints} <span className="text-xs text-slate-500 font-normal">{selectedExamId ? '%' : 'điểm'}</span>
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* Hạng 1 */}
          {top1s.length > 0 && (
            <div className="order-1 md:order-2 space-y-3">
              {top1s.map((entry, i) => (
                <div key={entry.userId ?? `rank1-${i}`} className="bg-gradient-to-b from-amber-50 to-white rounded-2xl border-2 border-amber-300 p-6 text-center shadow-md flex flex-col justify-between scale-105">
                  <div>
                    <div className="w-14 h-14 rounded-full bg-gradient-to-tr from-amber-400 to-amber-500 text-white font-black text-lg mx-auto flex items-center justify-center shadow-md shadow-amber-200 mb-2">
                      <Trophy className="w-7 h-7" />
                    </div>
                    <div className="text-[10px] uppercase font-bold text-amber-700 tracking-wider">Hạng 1</div>
                    <h4 className="text-base font-extrabold text-slate-900 line-clamp-1">{entry.userFullName}</h4>
                    {entry.currentTier && <span className="text-xs text-amber-700 font-bold px-2.5 py-0.5 rounded-full bg-amber-100 mt-1 inline-block">
                      {entry.currentTier}
                    </span>}
                  </div>
                  <div className="mt-4 pt-3 border-t border-amber-100 text-2xl font-black text-amber-900">
                    {entry.totalPoints} <span className="text-xs text-amber-700 font-normal">{selectedExamId ? '%' : 'điểm'}</span>
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* Hạng 3 */}
          {top3s.length > 0 && (
            <div className="order-3 mt-4 space-y-3">
              {top3s.map((entry, i) => (
                <div key={entry.userId ?? `rank3-${i}`} className="bg-white rounded-2xl border border-slate-200 p-5 text-center shadow-sm flex flex-col justify-between">
                  <div>
                    <div className="w-12 h-12 rounded-full bg-amber-50 border-2 border-amber-200 text-amber-800 font-black text-base mx-auto flex items-center justify-center mb-2">
                      3
                    </div>
                    <h4 className="text-sm font-bold text-slate-900 line-clamp-1">{entry.userFullName}</h4>
                    {entry.currentTier && <span className="text-xs text-indigo-600 font-semibold">{entry.currentTier}</span>}
                  </div>
                  <div className="mt-4 pt-3 border-t border-slate-100 text-lg font-black text-slate-800">
                    {entry.totalPoints} <span className="text-xs text-slate-500 font-normal">{selectedExamId ? '%' : 'điểm'}</span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Full Leaderboard Table */}
      {!loading && leaderboard.length > 0 && (
        <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
          <div className="px-6 py-4 border-b border-slate-100 flex items-center justify-between">
            <span className="text-xs font-bold text-slate-700 uppercase tracking-wider">Danh sách xếp hạng</span>
            <span className="text-xs text-slate-500 font-medium">{leaderboard.length} học viên</span>
          </div>

          <div className="divide-y divide-slate-100">
            {leaderboard.map((entry, index) => (
              <div
                // R8-07: entry.userId is null for an anonymised (private) learner — falling back to
                // rank+index keeps the key stable and unique even when several entries share a rank.
                key={entry.userId ?? `rank-${entry.rank}-${index}`}
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
                    {entry.currentTier && <span className="text-[11px] text-indigo-600 font-semibold">{entry.currentTier}</span>}
                  </div>
                </div>

                <div className="text-right">
                  <span className="text-base font-extrabold text-slate-900">{entry.totalPoints}</span>
                  <span className="text-xs text-slate-500 ml-1">{selectedExamId ? '%' : 'điểm'}</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};
