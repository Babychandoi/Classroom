import React, { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, LeaderboardEntry, Exam } from '../../types';
import { api } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { hasStudioPermission } from '../../api/permissions';
import { LoadingSpinner, ErrorBanner, EmptyState } from '../../components/UIStates';
import { Avatar, FilterChip } from '../../components/ui';
import { Crown, Lock, Trophy } from 'lucide-react';

/** A rank tier of the class, normalised from either tier endpoint. */
interface RankTier {
  tierName: string;
  minPoints: number;
  description?: string | null;
}

/** The raw tier shape: GET /leaderboard/tiers may name the field `name`, the Studio configuration names it `tierName`. */
type RawTier = { tierName?: string | null; name?: string | null; minPoints?: number | null; description?: string | null };

/** Accepts `[tier]` or `{ tiers: [tier] }`, drops malformed rows, orders by threshold. */
export const normaliseTiers = (payload: unknown): RankTier[] => {
  const list: RawTier[] = Array.isArray(payload)
    ? payload
    : Array.isArray((payload as { tiers?: unknown } | null)?.tiers)
      ? (payload as { tiers: RawTier[] }).tiers
      : [];
  return list
    .map((t) => ({ tierName: (t?.tierName ?? t?.name ?? '').trim(), minPoints: Number(t?.minPoints), description: t?.description ?? null }))
    .filter((t) => t.tierName && Number.isFinite(t.minPoints))
    .sort((a, b) => a.minPoints - b.minPoints);
};

const formatPoints = (n: number) => n.toLocaleString('vi-VN');

/** Podium medal colours (design: gold #F59E0B, silver #94A3B8, bronze #B45309) - always paired with the rank number. */
const MEDAL: Record<number, string> = { 1: 'bg-amber-500', 2: 'bg-slate-400', 3: 'bg-amber-700' };

export const LeaderboardTab: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const { user } = useAuth();

  const [leaderboard, setLeaderboard] = useState<LeaderboardEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // R13-08 (UI spec §2 "bộ lọc kỳ"): filter the board by one exam's published results instead of
  // the class-wide total. "" means the class-wide (all exams) board.
  const [exams, setExams] = useState<Exam[]>([]);
  const [selectedExamId, setSelectedExamId] = useState<string>('');

  // The real rank tiers. Every member reads them from GET /leaderboard/tiers; until that endpoint is deployed (404/403)
  // a LEADERBOARD:EDIT viewer falls back to the Studio configuration, and everybody else just sees their tier name -
  // never a ladder with invented thresholds.
  const canReadTiers = hasStudioPermission(classroom, 'LEADERBOARD', 'EDIT');
  const [tiers, setTiers] = useState<RankTier[] | null>(null);

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

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const list = normaliseTiers(await api.get<unknown>(`/classes/${classroom.id}/leaderboard/tiers`));
        if (!cancelled) setTiers(list);
        return;
      } catch {
        // Not deployed yet / not allowed: try the Studio configuration below when the viewer may read it.
      }
      if (!canReadTiers) { if (!cancelled) setTiers(null); return; }
      try {
        const list = normaliseTiers(await api.get<unknown>(`/classes/${classroom.id}/leaderboard/configuration`));
        if (!cancelled) setTiers(list);
      } catch {
        if (!cancelled) setTiers(null);
      }
    };
    void load();
    return () => { cancelled = true; };
  }, [classroom.id, canReadTiers]);

  const isExamBoard = !!selectedExamId;
  const unit = isExamBoard ? '%' : 'điểm';

  // R8-04: with standard competition ranking (1,1,3,...) ties share a rank, so the podium must be
  // selected by rank rather than by array index — e.g. two students tied for rank 1 both belong in
  // the "Hạng 1" slot, and there may then be no rank-2 entry at all.
  const top1s = leaderboard.filter((e) => e.rank === 1);
  const top2s = leaderboard.filter((e) => e.rank === 2);
  const top3s = leaderboard.filter((e) => e.rank === 3);

  // The viewer's own row always carries their real userId (anonymised rows have userId null).
  const me = user ? leaderboard.find((e) => e.userId === user.id) : undefined;

  // Progress to the next tier, from the real tier list (class-wide board only).
  const myPoints = me?.totalPoints ?? 0;
  const currentTierIndex = tiers ? tiers.reduce((found, t, i) => (myPoints >= t.minPoints ? i : found), -1) : -1;
  const nextTier = tiers && currentTierIndex + 1 < tiers.length ? tiers[currentTierIndex + 1] : null;
  // Progress inside the current tier band (from its threshold up to the next tier's threshold).
  const tierFloor = tiers && currentTierIndex >= 0 ? tiers[currentTierIndex].minPoints : 0;
  const currentTierName = me?.currentTier ?? (tiers && currentTierIndex >= 0 ? tiers[currentTierIndex].tierName : null);

  const podiumCard = (entry: LeaderboardEntry, rank: 1 | 2 | 3, key: string) => {
    const first = rank === 1;
    return (
      <div key={key} className={`min-w-0 text-center ${first ? 'pb-4' : 'pb-1'}`}>
        <span className="relative inline-block">
          {first && <Crown className="absolute -top-[22px] left-1/2 h-[22px] w-[22px] -translate-x-1/2 fill-amber-500 text-amber-500" strokeWidth={1.5} aria-hidden="true" />}
          <span className={`inline-block rounded-full border-[3px] ${first ? 'border-amber-500' : 'border-slate-200'}`}>
            <Avatar name={entry.userFullName} src={entry.userAvatarUrl} size={first ? 78 : 58} />
          </span>
          <span
            aria-hidden="true"
            className={`absolute -bottom-1.5 left-1/2 inline-flex -translate-x-1/2 items-center justify-center rounded-full border-2 border-white font-bold text-white tabular ${MEDAL[rank]} ${first ? 'h-6 w-6 text-caption' : 'h-[22px] w-[22px] text-micro'}`}
          >
            {rank}
          </span>
        </span>
        <p className="mt-3 text-micro font-semibold uppercase tracking-[0.5px] text-slate-500">Hạng {rank}</p>
        <p className={`mt-0.5 line-clamp-2 break-words font-semibold text-slate-900 ${first ? 'text-body-sm' : 'text-ui'}`}>
          {entry.userFullName}
          {me && entry.userId === me.userId && <span className="ml-1 text-caption font-semibold text-blue-600">(bạn)</span>}
        </p>
        {entry.currentTier && <p className="mt-0.5 truncate text-micro text-slate-500">{entry.currentTier}</p>}
        <p className={`mt-1.5 font-semibold text-slate-900 tabular ${first ? 'text-h3' : 'text-body-sm'}`}>
          {formatPoints(entry.totalPoints)} <span className="text-caption font-normal text-slate-500">{unit}</span>
        </p>
      </div>
    );
  };

  return (
    <div className="space-y-5">
      <div>
        <h2 className="text-h2-sm font-semibold text-slate-900 sm:text-h2">Bảng xếp hạng</h2>
        <p className="mt-1 text-ui text-slate-600">
          {isExamBoard
            ? 'Xếp hạng theo điểm cao nhất đã công bố của kỳ thi đã chọn.'
            : 'Điểm số được quy đổi từ kết quả bài thi cao nhất đã công bố của mỗi kỳ thi.'}
        </p>
      </div>

      {/* R13-08: "bộ lọc kỳ" — filter the board to one exam's published results */}
      <div role="group" aria-label="Bộ lọc kỳ thi" className="-mx-4 flex gap-2 overflow-x-auto px-4 scrollbar-none sm:mx-0 sm:flex-wrap sm:px-0">
        <FilterChip selected={!isExamBoard} onClick={() => setSelectedExamId('')}>Toàn bộ lớp (tổng điểm)</FilterChip>
        {exams.map((exam) => (
          <FilterChip key={exam.id} selected={selectedExamId === exam.id} onClick={() => setSelectedExamId(exam.id)}>
            {exam.title}
          </FilterChip>
        ))}
      </div>

      <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,1fr)_340px] lg:gap-6">
        {/* Left column: podium, ranking, how points work */}
        <div className="min-w-0 space-y-4">
          {loading && <LoadingSpinner message="Đang cập nhật bảng xếp hạng..." />}
          {error && <ErrorBanner message={error} onRetry={fetchLeaderboard} />}

          {!loading && !error && leaderboard.length === 0 && (
            <EmptyState
              title="Chưa có học viên nào trên bảng xếp hạng"
              description={isExamBoard
                ? 'Kỳ thi này chưa có kết quả nào được công bố. Bảng sẽ cập nhật ngay khi có.'
                : 'Bảng xếp hạng cập nhật khi kết quả thi được công bố. Vào tab Thi để làm bài đầu tiên.'}
              icon={<Trophy className="h-6 w-6" strokeWidth={1.75} />}
            />
          )}

          {/* Top 3 podium — grouped by RANK, not array position, so tied students share a slot */}
          {!loading && leaderboard.length > 0 && (
            <section aria-label="Top 3" className="grid grid-cols-3 items-end gap-2 rounded-2xl border border-slate-200 bg-white px-3 pb-5 pt-8 shadow-hairline sm:gap-3 sm:rounded-card sm:px-6 sm:pb-6">
              {/* Hạng 2 */}
              <div className="space-y-4">{top2s.map((entry, i) => podiumCard(entry, 2, entry.userId ?? `rank2-${i}`))}</div>
              {/* Hạng 1 */}
              <div className="space-y-6">{top1s.map((entry, i) => podiumCard(entry, 1, entry.userId ?? `rank1-${i}`))}</div>
              {/* Hạng 3 */}
              <div className="space-y-4">{top3s.map((entry, i) => podiumCard(entry, 3, entry.userId ?? `rank3-${i}`))}</div>
            </section>
          )}

          {/* Full ranking */}
          {!loading && leaderboard.length > 0 && (
            <section aria-labelledby="ranking-list" className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-hairline sm:rounded-card">
              <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3 sm:px-5">
                <h3 id="ranking-list" className="text-ui font-semibold text-slate-900">Danh sách xếp hạng</h3>
                <span className="text-caption text-slate-500 tabular">{leaderboard.length} học viên</span>
              </div>
              <ol>
                {leaderboard.map((entry, index) => {
                  const isMe = !!me && entry.userId === me.userId;
                  return (
                    <li
                      // R8-07: entry.userId is null for an anonymised (private) learner — falling back to
                      // rank+index keeps the key stable and unique even when several entries share a rank.
                      key={entry.userId ?? `rank-${entry.rank}-${index}`}
                      aria-current={isMe ? 'true' : undefined}
                      className={`flex items-center gap-3 border-b border-slate-100 py-3 last:border-b-0 sm:gap-3.5 ${
                        isMe ? 'border-l-[3px] border-l-blue-600 bg-tint pl-[13px] pr-4 sm:pl-[17px] sm:pr-5' : 'px-4 sm:px-5'
                      }`}
                    >
                      <span className={`w-[26px] flex-shrink-0 text-center text-meta font-bold tabular ${isMe ? 'text-blue-800' : 'text-slate-600'}`}>
                        {entry.rank}
                      </span>
                      <Avatar name={entry.userFullName} src={entry.userAvatarUrl} size={38} />
                      <span className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2 gap-y-1">
                        <span className={`truncate text-ui text-slate-900 ${isMe ? 'font-bold' : 'font-semibold'}`}>{entry.userFullName}</span>
                        {isMe && (
                          <span className="inline-flex h-[18px] flex-shrink-0 items-center rounded-full bg-blue-600 px-[7px] text-micro-xs font-bold text-white">BẠN</span>
                        )}
                        {entry.currentTier && (
                          <span className="inline-flex h-[18px] flex-shrink-0 items-center rounded-full bg-slate-100 px-[7px] text-micro-xs font-bold uppercase text-slate-600">
                            {entry.currentTier}
                          </span>
                        )}
                      </span>
                      <span className="flex-shrink-0 text-right">
                        <span className="text-ui font-bold text-slate-900 tabular">{entry.totalPoints}</span>
                        <span className="ml-1 text-caption text-slate-600">{unit}</span>
                      </span>
                    </li>
                  );
                })}
              </ol>
            </section>
          )}

          {/* How points work — the real rule, public to every member */}
          <section className="rounded-2xl border border-slate-200 bg-white px-4 py-4 shadow-hairline sm:rounded-card sm:px-5">
            <h3 className="text-ui font-semibold text-slate-900">Cách tính điểm — công khai với mọi thành viên</h3>
            <ul className="mt-3 space-y-2 text-meta text-slate-600">
              <li className="flex gap-2.5">
                <span aria-hidden="true" className="mt-[7px] h-1.5 w-1.5 flex-shrink-0 rounded-full bg-slate-300" />
                <span>Mỗi kỳ thi chỉ tính <strong className="font-semibold text-slate-900">lượt làm có điểm cao nhất đã được công bố</strong> — làm lại không bị trừ điểm.</span>
              </li>
              <li className="flex gap-2.5">
                <span aria-hidden="true" className="mt-[7px] h-1.5 w-1.5 flex-shrink-0 rounded-full bg-slate-300" />
                <span>Điểm của lượt đó được đổi thành <strong className="font-semibold text-slate-900">điểm thưởng</strong> theo các mốc lớp học đặt cho từng kỳ thi (đạt mốc càng cao, thưởng càng nhiều).</span>
              </li>
              <li className="flex gap-2.5">
                <span aria-hidden="true" className="mt-[7px] h-1.5 w-1.5 flex-shrink-0 rounded-full bg-slate-300" />
                <span>Tổng điểm thưởng của mọi kỳ thi quyết định thứ hạng và cấp độ. Bằng điểm thì xếp cùng hạng.</span>
              </li>
              <li className="flex gap-2.5">
                <span aria-hidden="true" className="mt-[7px] h-1.5 w-1.5 flex-shrink-0 rounded-full bg-slate-300" />
                <span>Khi xem theo một kỳ thi, bảng xếp theo điểm phần trăm cao nhất đã công bố của kỳ đó.</span>
              </li>
            </ul>
            <p className="mt-3 border-t border-slate-100 pt-2.5 text-caption text-slate-500">
              Lượt xem thử của người dẫn dắt không được tính. Thành viên đặt hồ sơ riêng tư hiển thị là “Người dùng ẩn danh”.
            </p>
          </section>
        </div>

        {/* Right column: your tier + the class's tiers */}
        <aside className="min-w-0 space-y-4">
          {me && !isExamBoard && (
            <section aria-label="Cấp độ của bạn" className="rounded-2xl border border-slate-200 bg-white px-5 py-4 shadow-hairline sm:rounded-card">
              <div className="flex items-center gap-3">
                <span className="relative flex-shrink-0">
                  <Avatar name={me.userFullName} src={me.userAvatarUrl} size={52} />
                  <span className="absolute -bottom-1 -right-1 inline-flex h-[22px] min-w-[22px] items-center justify-center rounded-full border-2 border-white bg-blue-600 px-1 text-micro font-bold text-white tabular">
                    {me.rank}
                  </span>
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-ui font-semibold text-slate-900">{currentTierName ?? 'Chưa có cấp độ'}</span>
                  <span className="mt-px block text-caption text-slate-500 tabular">
                    Hạng {me.rank} · {formatPoints(myPoints)}{nextTier ? ` / ${formatPoints(nextTier.minPoints)}` : ''} điểm tích lũy
                  </span>
                </span>
              </div>
              {nextTier && (
                <>
                  <div
                    role="progressbar"
                    aria-label={`Tiến độ lên ${nextTier.tierName}`}
                    aria-valuemin={tierFloor}
                    aria-valuemax={nextTier.minPoints}
                    aria-valuenow={myPoints}
                    className="mt-3 h-1.5 overflow-hidden rounded-full bg-slate-100"
                  >
                    <div className="h-full rounded-full bg-blue-600" style={{ width: `${Math.min(100, Math.max(0, ((myPoints - tierFloor) / Math.max(1, nextTier.minPoints - tierFloor)) * 100))}%` }} />
                  </div>
                  <p className="mt-2 text-caption text-slate-600">
                    Còn <strong className="font-semibold text-slate-900 tabular">{formatPoints(nextTier.minPoints - myPoints)} điểm</strong> để lên {nextTier.tierName}
                  </p>
                </>
              )}
            </section>
          )}

          <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-hairline sm:rounded-card">
            <div className="flex items-baseline justify-between gap-2.5 px-5 pb-2.5 pt-4">
              <h3 className="text-ui font-semibold text-slate-900">Cấp độ trong lớp</h3>
              {tiers && <span className="text-micro text-slate-500 tabular">{tiers.length} cấp</span>}
            </div>
            {tiers && tiers.length > 0 ? (
              <ol>
                {tiers.map((tier, i) => {
                  const mine = !!me && i === currentTierIndex;
                  const reached = !!me && i <= currentTierIndex;
                  return (
                    <li
                      key={`${tier.tierName}-${tier.minPoints}`}
                      aria-current={mine ? 'true' : undefined}
                      className={`flex items-center gap-2.5 border-t border-slate-50 py-2 ${mine ? 'border-l-[3px] border-l-blue-600 bg-tint pl-[11px] pr-3.5' : 'px-3.5'}`}
                    >
                      <span
                        aria-hidden="true"
                        className={`inline-flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full text-micro font-bold tabular ${
                          mine ? 'bg-blue-600 text-white' : reached || !me ? 'bg-tint text-blue-800' : 'bg-slate-100 text-slate-400'
                        }`}
                      >
                        {i + 1}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="flex items-center gap-1.5">
                          <span className={`truncate text-caption ${mine ? 'font-bold text-slate-900' : 'font-semibold text-slate-700'}`}>{tier.tierName}</span>
                          {mine && <span className="inline-flex h-[15px] flex-shrink-0 items-center rounded-full bg-blue-600 px-[5px] text-[8px] font-bold text-white">BẠN</span>}
                        </span>
                        {tier.description && <span className="block truncate text-micro text-slate-500">{tier.description}</span>}
                      </span>
                      <span className="flex flex-shrink-0 items-center gap-1 text-micro text-slate-500 tabular">
                        {!reached && me && <Lock className="h-[9px] w-[9px]" strokeWidth={2.4} aria-hidden="true" />}
                        từ {formatPoints(tier.minPoints)}
                      </span>
                    </li>
                  );
                })}
              </ol>
            ) : (
              <p className="border-t border-slate-50 px-5 py-4 text-meta text-slate-600">
                {me?.currentTier
                  ? <>Cấp độ hiện tại của bạn: <strong className="font-semibold text-slate-900">{me.currentTier}</strong>. Tổng điểm thưởng càng cao, cấp độ càng lên.</>
                  : 'Cấp độ được xét theo tổng điểm thưởng. Làm bài thi để có điểm và nhận cấp độ đầu tiên.'}
              </p>
            )}
          </section>
        </aside>
      </div>
    </div>
  );
};
