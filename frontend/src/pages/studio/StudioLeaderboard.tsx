import React, { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, Exam } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { Button, Card, buttonClass, inputClass } from '../../components/ui';
import { CardHeader, Notice, PageHeader, StudioPage, iconActionClass } from './studioUi';
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
      <StudioPage width="narrow" className="py-12">
        <ErrorBanner message={error} onRetry={fetchData} />
      </StudioPage>
    );
  }

  const fieldLabel = 'block text-caption font-semibold text-slate-600';

  return (
    <StudioPage width="narrow">
      <PageHeader
        title="Bảng xếp hạng"
        description="Đặt các bậc thành tích và quy tắc thưởng điểm theo kết quả từng kỳ thi."
        action={
          <Button variant="secondary" size="md" onClick={handleRebuild} disabled={rebuilding}>
            <RefreshCw className={`h-4 w-4 ${rebuilding ? 'animate-spin' : ''}`} strokeWidth={1.75} aria-hidden="true" />
            <span>{rebuilding ? 'Đang tái tạo...' : 'Tái tạo bảng xếp hạng'}</span>
          </Button>
        }
      />

      {saveMessage && <Notice tone="success" role="status">{saveMessage}</Notice>}
      {saveError && (
        <div role="alert" className="rounded-btn border border-red-200 bg-red-50 px-3.5 py-3 text-meta font-medium text-red-700">
          {saveError}
        </div>
      )}

      <form onSubmit={handleSave} className="space-y-5">
        <Card as="section" aria-labelledby="tiers-title" className="space-y-4">
          <CardHeader
            id="tiers-title"
            icon={<Trophy className="h-[18px] w-[18px] text-amber-500" strokeWidth={1.75} aria-hidden="true" />}
            title="Bậc thành tích"
            description="Học viên lên bậc khi điểm tích lũy đạt ngưỡng tối thiểu."
          />

          {tiers.length === 0 && <p className="text-meta text-slate-500">Chưa có bậc nào. Cần ít nhất một bậc để lưu cấu hình.</p>}

          <div className="space-y-3">
            {tiers.map((tier, index) => (
              <div key={index} className="grid grid-cols-[minmax(0,1fr)_112px_auto] items-end gap-2 sm:grid-cols-[minmax(0,1fr)_120px_minmax(0,1fr)_auto]">
                <label className={fieldLabel}><span className={index > 0 ? 'sm:sr-only' : undefined}>Tên bậc</span>
                  <input
                    aria-label={`Tên bậc ${index + 1}`}
                    required
                    value={tier.tierName}
                    onChange={(e) => updateTier(index, { tierName: e.target.value })}
                    className={inputClass('mt-1 h-10')}
                  />
                </label>
                <label className={fieldLabel}><span className={index > 0 ? 'sm:sr-only' : undefined}>Điểm tối thiểu</span>
                  <input
                    aria-label={`Điểm tối thiểu bậc ${index + 1}`}
                    type="number"
                    min={0}
                    required
                    value={tier.minPoints}
                    onChange={(e) => updateTier(index, { minPoints: parseInt(e.target.value) || 0 })}
                    className={inputClass('mt-1 h-10 tabular')}
                  />
                </label>
                <label className={`${fieldLabel} col-span-2 row-start-2 sm:col-span-1 sm:row-start-auto`}><span className={index > 0 ? 'sm:sr-only' : undefined}>Mô tả (tùy chọn)</span>
                  <input
                    aria-label={`Mô tả bậc ${index + 1}`}
                    value={tier.description}
                    onChange={(e) => updateTier(index, { description: e.target.value })}
                    className={inputClass('mt-1 h-10')}
                  />
                </label>
                <button
                  type="button"
                  onClick={() => removeTier(index)}
                  aria-label={`Xóa bậc ${index + 1}`}
                  className={`${iconActionClass('danger')} h-10 w-10 border border-slate-200`}
                >
                  <X className="h-4 w-4" strokeWidth={1.75} />
                </button>
              </div>
            ))}
          </div>

          <button type="button" onClick={addTier} className={buttonClass('tertiary', 'sm')}>
            <Plus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
            <span>Thêm bậc thành tích</span>
          </button>
        </Card>

        <Card as="section" aria-labelledby="rewards-title" className="space-y-4">
          <CardHeader
            id="rewards-title"
            title="Quy tắc thưởng điểm theo kỳ thi"
            description="Học viên đạt từ điểm thi tối thiểu trở lên được cộng điểm thưởng vào bảng xếp hạng."
          />

          {exams.length === 0 && (
            <p className="text-meta text-slate-600">Lớp học chưa có kỳ thi nào để cấu hình thưởng điểm.</p>
          )}

          <div className="space-y-3">
            {rewards.map((reward, index) => (
              <div key={index} className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] items-end gap-2 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_minmax(0,1fr)_auto]">
                <label className={`${fieldLabel} col-span-3 sm:col-span-1`}><span className={index > 0 ? 'sm:sr-only' : undefined}>Kỳ thi</span>
                  <select
                    aria-label={`Kỳ thi quy tắc ${index + 1}`}
                    required
                    value={reward.examId}
                    onChange={(e) => updateReward(index, { examId: e.target.value })}
                    className={inputClass('mt-1 h-10 pr-8')}
                  >
                    <option value="">Chọn kỳ thi</option>
                    {exams.map((exam) => (
                      <option key={exam.id} value={exam.id}>{exam.title}</option>
                    ))}
                  </select>
                </label>
                <label className={fieldLabel}><span className={index > 0 ? 'sm:sr-only' : undefined}>Điểm thi tối thiểu (%)</span>
                  <input
                    aria-label={`Điểm thi tối thiểu quy tắc ${index + 1}`}
                    type="number"
                    min={0}
                    max={100}
                    required
                    value={reward.minExamScore}
                    onChange={(e) => updateReward(index, { minExamScore: parseFloat(e.target.value) || 0 })}
                    className={inputClass('mt-1 h-10 tabular')}
                  />
                </label>
                <label className={fieldLabel}><span className={index > 0 ? 'sm:sr-only' : undefined}>Điểm thưởng</span>
                  <input
                    aria-label={`Điểm thưởng quy tắc ${index + 1}`}
                    type="number"
                    min={0}
                    required
                    value={reward.rewardPoints}
                    onChange={(e) => updateReward(index, { rewardPoints: parseInt(e.target.value) || 0 })}
                    className={inputClass('mt-1 h-10 tabular')}
                  />
                </label>
                <button
                  type="button"
                  onClick={() => removeReward(index)}
                  aria-label={`Xóa quy tắc ${index + 1}`}
                  className={`${iconActionClass('danger')} h-10 w-10 border border-slate-200`}
                >
                  <X className="h-4 w-4" strokeWidth={1.75} />
                </button>
              </div>
            ))}
          </div>

          <button type="button" onClick={addReward} disabled={exams.length === 0} className={buttonClass('tertiary', 'sm')}>
            <Plus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
            <span>Thêm quy tắc thưởng điểm</span>
          </button>
        </Card>

        <div className="flex justify-end">
          <Button type="submit" variant="primary" size="md" disabled={saving}>
            {saving ? 'Đang lưu...' : 'Lưu cấu hình'}
          </Button>
        </div>
      </form>
    </StudioPage>
  );
};
