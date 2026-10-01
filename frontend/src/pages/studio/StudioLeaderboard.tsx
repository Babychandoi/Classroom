import React, { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, Exam } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { Trophy, Plus, X, RefreshCw } from 'lucide-react';

interface TierDraft {
  tierName: string;
  minPoints: number;
  badgeUrl: string;
  description: string;
}

interface RewardDraft {
  examId: string;
  minExamScore: number;
  rewardPoints: number;
}

interface LeaderboardConfigResponse {
  tiers: { tierName: string; minPoints: number; badgeUrl?: string | null; description?: string | null }[];
  rewards: { examId: string; minExamScore: number; rewardPoints: number }[];
}

const emptyTier = (): TierDraft => ({ tierName: '', minPoints: 0, badgeUrl: '', description: '' });
const emptyReward = (defaultExamId: string): RewardDraft => ({ examId: defaultExamId, minExamScore: 50, rewardPoints: 10 });

/**
 * R8-05: Studio page to view/edit the leaderboard's rank tiers and per-exam reward-point
 * thresholds (PUT /classes/:id/leaderboard/configuration), and trigger a full rebuild.
 * Gated on LEADERBOARD:EDIT, matching LeaderboardService.configure/getConfiguration/rebuildLeaderboard.
 */
export const StudioLeaderboard: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();

  const [exams, setExams] = useState<Exam[]>([]);
  const [tiers, setTiers] = useState<TierDraft[]>([]);
  const [rewards, setRewards] = useState<RewardDraft[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [rebuilding, setRebuilding] = useState(false);
  const [saveMessage, setSaveMessage] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);

  const fetchData = async () => {
    try {
      setLoading(true);
      setError(null);
      const [config, examList] = await Promise.all([
        api.get<LeaderboardConfigResponse>(`/classes/${classroom.id}/leaderboard/configuration`),
        api.get<Exam[]>(`/classes/${classroom.id}/exams`),
      ]);
      setTiers(
        (config.tiers || []).map((t) => ({
          tierName: t.tierName,
          minPoints: t.minPoints,
          badgeUrl: t.badgeUrl || '',
          description: t.description || '',
        }))
      );
      setRewards((config.rewards || []).map((r) => ({ ...r })));
      setExams(examList || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải cấu hình xếp hạng');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, [classroom.id]);

  const updateTier = (index: number, patch: Partial<TierDraft>) => {
    setTiers((old) => old.map((t, i) => (i === index ? { ...t, ...patch } : t)));
  };
  const addTier = () => setTiers((old) => [...old, emptyTier()]);
  const removeTier = (index: number) => setTiers((old) => old.filter((_, i) => i !== index));

  const updateReward = (index: number, patch: Partial<RewardDraft>) => {
    setRewards((old) => old.map((r, i) => (i === index ? { ...r, ...patch } : r)));
  };
  const addReward = () => setRewards((old) => [...old, emptyReward(exams[0]?.id || '')]);
  const removeReward = (index: number) => setRewards((old) => old.filter((_, i) => i !== index));

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setSaveMessage(null);
    setSaveError(null);
    try {
      // Mirrors LeaderboardService.configure's own validation, so a bad form gets a client-side
      // message before the round trip:
      // - every tier needs a non-blank name and a minPoints >= 0, with no duplicate threshold
      // - every reward needs an exam, a score in [0, 100], and rewardPoints >= 0, with no duplicate
      //   (examId, minExamScore) threshold
      if (tiers.length === 0) {
        throw new Error('Cần ít nhất một bậc thành tích');
      }
      const seenTierPoints = new Set<number>();
      for (const t of tiers) {
        if (!t.tierName.trim() || t.minPoints < 0) {
          throw new Error('Mỗi bậc thành tích cần tên và điểm tối thiểu hợp lệ (>= 0)');
        }
        if (seenTierPoints.has(t.minPoints)) {
          throw new Error(`Ngưỡng điểm bậc thành tích bị trùng: ${t.minPoints}`);
        }
        seenTierPoints.add(t.minPoints);
      }
      const seenRewardKeys = new Set<string>();
      for (const r of rewards) {
        if (!r.examId || r.minExamScore < 0 || r.minExamScore > 100 || r.rewardPoints < 0) {
          throw new Error('Mỗi quy tắc thưởng điểm cần kỳ thi và ngưỡng điểm hợp lệ (0-100)');
        }
        const key = `${r.examId}::${r.minExamScore}`;
        if (seenRewardKeys.has(key)) {
          throw new Error('Ngưỡng thưởng điểm bị trùng cho cùng một kỳ thi');
        }
        seenRewardKeys.add(key);
      }

      await api.put(`/classes/${classroom.id}/leaderboard/configuration`, {
        tiers: tiers.map((t) => ({
          tierName: t.tierName.trim(),
          minPoints: t.minPoints,
          badgeUrl: t.badgeUrl || null,
          description: t.description || null,
        })),
        rewards: rewards.map((r) => ({
          examId: r.examId,
          minExamScore: r.minExamScore,
          rewardPoints: r.rewardPoints,
        })),
      });
      setSaveMessage('Đã lưu cấu hình xếp hạng thành công');
      await fetchData();
    } catch (err: any) {
      setSaveError(err.message || 'Lưu cấu hình thất bại');
    } finally {
      setSaving(false);
    }
  };

  const handleRebuild = async () => {
    setRebuilding(true);
    setSaveMessage(null);
    setSaveError(null);
    try {
      await api.post(`/classes/${classroom.id}/leaderboard/rebuild`, {});
      setSaveMessage('Đã tái tạo bảng xếp hạng thành công');
    } catch (err: any) {
      setSaveError(err.message || 'Tái tạo bảng xếp hạng thất bại');
    } finally {
      setRebuilding(false);
    }
  };

  if (loading) return <LoadingSpinner message="Đang tải cấu hình xếp hạng..." />;
  if (error) {
    return (
      <div className="max-w-3xl mx-auto py-12">
        <ErrorBanner message={error} onRetry={fetchData} />
      </div>
    );
  }

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      <div className="flex justify-between items-start">
        <div>
          <h1 className="text-2xl font-black text-slate-900 tracking-tight">Cấu hình Bảng Xếp Hạng</h1>
          <p className="text-xs text-slate-600">
            Định nghĩa các bậc thành tích và quy tắc thưởng điểm theo kết quả từng kỳ thi
          </p>
        </div>
        <button
          type="button"
          onClick={handleRebuild}
          disabled={rebuilding}
          className="inline-flex items-center space-x-1.5 px-4 py-2 bg-slate-100 hover:bg-slate-200 text-slate-700 rounded-xl text-xs font-bold transition disabled:opacity-50"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${rebuilding ? 'animate-spin' : ''}`} />
          <span>{rebuilding ? 'Đang tái tạo...' : 'Tái tạo bảng xếp hạng'}</span>
        </button>
      </div>

      {saveMessage && (
        <div className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-2 text-xs font-semibold text-emerald-800">
          {saveMessage}
        </div>
      )}
      {saveError && (
        <div role="alert" className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-2 text-xs font-semibold text-rose-800">
          {saveError}
        </div>
      )}

      <form onSubmit={handleSave} className="space-y-6">
        <section className="bg-white rounded-2xl border border-slate-200 p-6 shadow-sm space-y-3">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-bold text-slate-900 uppercase tracking-wider flex items-center space-x-2">
              <Trophy className="w-4 h-4 text-amber-500" />
              <span>Bậc thành tích</span>
            </h2>
          </div>

          {tiers.map((tier, index) => (
            <div key={index} className="grid grid-cols-[1fr_120px_1fr_auto] gap-2 items-end">
              <label className="text-xs font-semibold text-slate-700">Tên bậc
                <input
                  aria-label={`Tên bậc ${index + 1}`}
                  required
                  value={tier.tierName}
                  onChange={(e) => updateTier(index, { tierName: e.target.value })}
                  className="mt-1 block w-full rounded-lg border p-2 text-xs"
                />
              </label>
              <label className="text-xs font-semibold text-slate-700">Điểm tối thiểu
                <input
                  aria-label={`Điểm tối thiểu bậc ${index + 1}`}
                  type="number"
                  min={0}
                  required
                  value={tier.minPoints}
                  onChange={(e) => updateTier(index, { minPoints: parseInt(e.target.value) || 0 })}
                  className="mt-1 block w-full rounded-lg border p-2 text-xs"
                />
              </label>
              <label className="text-xs font-semibold text-slate-700">Mô tả (tùy chọn)
                <input
                  aria-label={`Mô tả bậc ${index + 1}`}
                  value={tier.description}
                  onChange={(e) => updateTier(index, { description: e.target.value })}
                  className="mt-1 block w-full rounded-lg border p-2 text-xs"
                />
              </label>
              <button
                type="button"
                onClick={() => removeTier(index)}
                aria-label={`Xóa bậc ${index + 1}`}
                className="p-2 rounded-lg border border-slate-300 text-slate-500 hover:text-rose-600 hover:border-rose-300"
              >
                <X className="w-4 h-4" />
              </button>
            </div>
          ))}

          <button
            type="button"
            onClick={addTier}
            className="inline-flex items-center space-x-1 text-xs font-bold text-indigo-600 hover:text-indigo-800"
          >
            <Plus className="w-3.5 h-3.5" />
            <span>Thêm bậc thành tích</span>
          </button>
        </section>

        <section className="bg-white rounded-2xl border border-slate-200 p-6 shadow-sm space-y-3">
          <h2 className="text-sm font-bold text-slate-900 uppercase tracking-wider">
            Quy tắc thưởng điểm theo kỳ thi
          </h2>

          {exams.length === 0 && (
            <p className="text-xs text-slate-600">Lớp học chưa có kỳ thi nào để cấu hình thưởng điểm.</p>
          )}

          {rewards.map((reward, index) => (
            <div key={index} className="grid grid-cols-[2fr_1fr_1fr_auto] gap-2 items-end">
              <label className="text-xs font-semibold text-slate-700">Kỳ thi
                <select
                  aria-label={`Kỳ thi quy tắc ${index + 1}`}
                  required
                  value={reward.examId}
                  onChange={(e) => updateReward(index, { examId: e.target.value })}
                  className="mt-1 block w-full rounded-lg border p-2 text-xs"
                >
                  <option value="">Chọn kỳ thi</option>
                  {exams.map((exam) => (
                    <option key={exam.id} value={exam.id}>{exam.title}</option>
                  ))}
                </select>
              </label>
              <label className="text-xs font-semibold text-slate-700">Điểm thi tối thiểu (%)
                <input
                  aria-label={`Điểm thi tối thiểu quy tắc ${index + 1}`}
                  type="number"
                  min={0}
                  max={100}
                  required
                  value={reward.minExamScore}
                  onChange={(e) => updateReward(index, { minExamScore: parseFloat(e.target.value) || 0 })}
                  className="mt-1 block w-full rounded-lg border p-2 text-xs"
                />
              </label>
              <label className="text-xs font-semibold text-slate-700">Điểm thưởng
                <input
                  aria-label={`Điểm thưởng quy tắc ${index + 1}`}
                  type="number"
                  min={0}
                  required
                  value={reward.rewardPoints}
                  onChange={(e) => updateReward(index, { rewardPoints: parseInt(e.target.value) || 0 })}
                  className="mt-1 block w-full rounded-lg border p-2 text-xs"
                />
              </label>
              <button
                type="button"
                onClick={() => removeReward(index)}
                aria-label={`Xóa quy tắc ${index + 1}`}
                className="p-2 rounded-lg border border-slate-300 text-slate-500 hover:text-rose-600 hover:border-rose-300"
              >
                <X className="w-4 h-4" />
              </button>
            </div>
          ))}

          <button
            type="button"
            onClick={addReward}
            disabled={exams.length === 0}
            className="inline-flex items-center space-x-1 text-xs font-bold text-indigo-600 hover:text-indigo-800 disabled:opacity-50"
          >
            <Plus className="w-3.5 h-3.5" />
            <span>Thêm quy tắc thưởng điểm</span>
          </button>
        </section>

        <div className="flex justify-end">
          <button
            type="submit"
            disabled={saving}
            className="px-5 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold shadow-sm transition disabled:opacity-50"
          >
            {saving ? 'Đang lưu...' : 'Lưu cấu hình'}
          </button>
        </div>
      </form>
    </div>
  );
};
