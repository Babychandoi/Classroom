package com.classroom.modules.ranking.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.exam.model.Exam;
import com.classroom.modules.exam.model.ExamAttempt;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.exam.repository.ExamRepository;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.ranking.dto.LeaderboardEntryDto;
import com.classroom.modules.ranking.dto.LeaderboardConfigRequest;
import com.classroom.modules.ranking.model.ExamRewardRule;
import com.classroom.modules.ranking.model.LeaderboardEntry;
import com.classroom.modules.ranking.model.LeaderboardRecalcJob;
import com.classroom.modules.ranking.model.RankTier;
import com.classroom.modules.ranking.repository.ExamRewardRuleRepository;
import com.classroom.modules.ranking.repository.LeaderboardEntryRepository;
import com.classroom.modules.ranking.repository.LeaderboardRecalcJobRepository;
import com.classroom.modules.ranking.repository.RankTierRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class LeaderboardService {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardService.class);

    /** Transaction-scoped set of (classId,userId) keys already queued for post-commit recalculation. */
    private static final String PENDING_RECALCULATIONS = LeaderboardService.class.getName() + ".pendingRecalculations";

    /** Upper bound on the retry backoff for a queued recalculation job. */
    private static final long MAX_RECALC_BACKOFF_SECONDS = 300;

    public static final int MAX_REWARD_POINTS = 1_000_000;
    public static final int MAX_TIER_POINTS = 1_000_000_000;

    private final LeaderboardEntryRepository leaderboardRepository;
    private final LeaderboardRecalcJobRepository recalcJobRepository;
    private final RankTierRepository rankTierRepository;
    private final ExamRewardRuleRepository rewardRuleRepository;
    private final ExamAttemptRepository attemptRepository;
    private final ExamRepository examRepository;
    private final UserRepository userRepository;
    private final AccessPolicy accessPolicy;
    private final ProfileVisibilityPolicy profileVisibilityPolicy;
    private final ClassMemberRepository memberRepository;
    private final AuditService auditService;
    private final LeaderboardService self;
    /** R20-01: runs post-commit recalculations off the request thread; {@code null} in plain unit tests (sweeper only). */
    private final LeaderboardRecalcExecutor recalcExecutor;

    /**
     * How long a freshly armed job is hidden from {@link #sweepPendingRecalculations()}: the worker pool normally
     * services it within milliseconds, so the sweeper only has to act as the safety net for a dropped task or a
     * crashed process and must not race the worker for the same learner.
     */
    @Value("${classroom.leaderboard.recalc.sweep-grace-seconds:10}")
    private long sweepGraceSeconds = 10;

    public LeaderboardService(LeaderboardEntryRepository leaderboardRepository,
                              LeaderboardRecalcJobRepository recalcJobRepository,
                              RankTierRepository rankTierRepository,
                              ExamRewardRuleRepository rewardRuleRepository,
                              ExamAttemptRepository attemptRepository,
                              ExamRepository examRepository,
                              UserRepository userRepository,
                              AccessPolicy accessPolicy,
                              ProfileVisibilityPolicy profileVisibilityPolicy,
                              ClassMemberRepository memberRepository,
                              AuditService auditService,
                              @Lazy LeaderboardService self) {
        this(leaderboardRepository, recalcJobRepository, rankTierRepository, rewardRuleRepository, attemptRepository,
                examRepository, userRepository, accessPolicy, profileVisibilityPolicy, memberRepository, auditService,
                self, null);
    }

    @Autowired
    public LeaderboardService(LeaderboardEntryRepository leaderboardRepository,
                              LeaderboardRecalcJobRepository recalcJobRepository,
                              RankTierRepository rankTierRepository,
                              ExamRewardRuleRepository rewardRuleRepository,
                              ExamAttemptRepository attemptRepository,
                              ExamRepository examRepository,
                              UserRepository userRepository,
                              AccessPolicy accessPolicy,
                              ProfileVisibilityPolicy profileVisibilityPolicy,
                              ClassMemberRepository memberRepository,
                              AuditService auditService,
                              @Lazy LeaderboardService self,
                              LeaderboardRecalcExecutor recalcExecutor) {
        this.recalcExecutor = recalcExecutor;
        this.self = self;
        this.leaderboardRepository = leaderboardRepository;
        this.recalcJobRepository = recalcJobRepository;
        this.rankTierRepository = rankTierRepository;
        this.rewardRuleRepository = rewardRuleRepository;
        this.attemptRepository = attemptRepository;
        this.examRepository = examRepository;
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.profileVisibilityPolicy = profileVisibilityPolicy;
        this.memberRepository = memberRepository;
        this.auditService = auditService;
    }

    /**
     * R14-11: a leaderboard lists only people who are currently members of the class. A member who
     * was removed or blocked keeps their historical points/attempts (nothing is deleted), but must
     * not keep appearing - or holding a rank - on the class boards.
     */
    private Set<String> activeMemberIds(String classId) {
        return new HashSet<>(memberRepository.findActiveUserIdsByClassId(classId));
    }

    /**
     * Self-reference through the Spring proxy, so calls that must cross a transaction boundary
     * honour their {@code @Transactional} settings. Absent outside a Spring context (plain unit
     * tests), where the calls are simply direct.
     */
    private LeaderboardService self() {
        return self != null ? self : this;
    }

    @Transactional(readOnly = true)
    public List<LeaderboardEntryDto> getLeaderboard(String classId, String currentUserId) {
        return getLeaderboard(classId, currentUserId, null);
    }

    /**
     * R13-08 (UI spec §2 "bộ lọc kỳ" — SRS §3: "Điểm xếp hạng tính từ một attempt đã công bố cho
     * mỗi kỳ thi theo chính sách", i.e. "kỳ" = kỳ thi/exam, not a calendar period): when
     * {@code examId} is given, ranks members by their best PUBLISHED, non-preview score for that
     * one exam instead of the class-wide total. Members with no published attempt for the exam are
     * omitted (there is nothing to rank them on), mirroring how the class-wide board only ever
     * contains members with a leaderboard entry.
     */
    /**
     * The class's rank tiers, lowest threshold first, for members: the same access rule as {@link #getLeaderboard} (active members, owner,
     * staff; a PRIVATE class hidden from the caller is a 404).
     */
    @Transactional(readOnly = true)
    public List<com.classroom.modules.ranking.dto.RankTierDto> getTiers(String classId, String currentUserId) {
        accessPolicy.enforceMember(currentUserId, classId);
        return rankTierRepository.findByClassIdOrderByMinPointsAsc(classId).stream()
                .map(t -> new com.classroom.modules.ranking.dto.RankTierDto(
                        t.getTierName(), t.getTierName(), t.getMinPoints(), t.getBadgeUrl(), t.getDescription()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LeaderboardEntryDto> getLeaderboard(String classId, String currentUserId, String examId) {
        accessPolicy.enforceMember(currentUserId, classId);
        List<RankTier> tiers = rankTierRepository.findByClassIdOrderByMinPointsAsc(classId);

        if (examId != null && !examId.isBlank()) {
            Exam exam = examRepository.findById(examId)
                    .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi"));
            if (!classId.equals(exam.getClassId())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Kỳ thi không thuộc lớp học này");
            }
            return getExamLeaderboard(classId, examId, currentUserId, activeMemberIds(classId));
        }

        Set<String> activeMembers = activeMemberIds(classId);
        List<LeaderboardEntry> entries = leaderboardRepository.findByClassIdOrderByTotalPointsDesc(classId).stream()
                .filter(e -> activeMembers.contains(e.getUserId()))
                .toList();
        List<LeaderboardEntryDto> dtos = new ArrayList<>();

        // R8-04: findByClassIdOrderByTotalPointsDesc alone has no deterministic order among ties
        // (equal totalPoints), so re-sort with a stable secondary key before ranking: earliest
        // lastCalculatedAt first (whoever reached that score first), then userId as a final,
        // fully deterministic tiebreaker.
        List<LeaderboardEntry> ordered = entries.stream()
                .sorted(Comparator
                        .comparingInt(LeaderboardEntry::getTotalPoints).reversed()
                        .thenComparing(LeaderboardService::calculatedAtOrEpoch)
                        .thenComparing(LeaderboardEntry::getUserId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // R16-08: batch-load the board's users once and resolve the viewer's privacy context once.
        Map<String, User> usersById = loadUsers(ordered.stream().map(LeaderboardEntry::getUserId).toList());
        ProfileVisibilityPolicy.ViewerContext viewer = profileVisibilityPolicy.viewerContext(currentUserId, classId);

        // Standard competition ranking (1, 1, 3, ...): entries tied on totalPoints share the same
        // rank, and the next distinct score resumes at the 1-based position, not the next integer.
        int rank = 0;
        Integer previousPoints = null;
        int position = 0;
        for (LeaderboardEntry e : ordered) {
            position++;
            if (previousPoints == null || e.getTotalPoints() != previousPoints) {
                rank = position;
                previousPoints = e.getTotalPoints();
            }

            LeaderboardEntryDto dto = new LeaderboardEntryDto();
            dto.setRank(rank);
            dto.setClassId(e.getClassId());
            dto.setTotalPoints(e.getTotalPoints());

            // Derive current tier from active class thresholds at read time if tiers are configured,
            // otherwise keep the stored tier (Finding 12)
            String derivedTier = e.getCurrentTier() != null ? e.getCurrentTier() : "Tân thủ";
            if (!tiers.isEmpty()) {
                derivedTier = "Tân thủ";
                for (RankTier t : tiers) {
                    if (e.getTotalPoints() >= t.getMinPoints()) {
                        derivedTier = t.getTierName();
                    }
                }
            }
            dto.setCurrentTier(derivedTier);
            dto.setLastCalculatedAt(e.getLastCalculatedAt());

            // Finding 1: a private learner keeps their rank, points and tier, but their
            // identity is anonymised for peers exactly as on the profile endpoint.
            applyIdentity(dto, usersById.get(e.getUserId()), viewer);

            dtos.add(dto);
        }

        return dtos;
    }

    /**
     * R16-08: identity fields for one leaderboard row from the batch-loaded user and the per-listing
     * viewer context. Every row on a board is an ACTIVE member (see {@link #activeMemberIds}), so the
     * target-has-a-membership-row question the class-admin override asks is already answered.
     */
    private void applyIdentity(LeaderboardEntryDto dto, User user,
                               ProfileVisibilityPolicy.ViewerContext viewer) {
        if (user == null) return;
        boolean identityVisible = viewer.isIdentityVisible(user, () -> true);
        if (identityVisible) {
            dto.setUserId(user.getId());
        }
        dto.setUserFullName(viewer.displayName(user, identityVisible));
        dto.setUserAvatarUrl(viewer.avatarUrl(user, identityVisible));
    }

    /** R16-08: one findAllById for a whole board instead of a findById per row. */
    private Map<String, User> loadUsers(Collection<String> userIds) {
        Map<String, User> usersById = new HashMap<>();
        if (!userIds.isEmpty()) {
            userRepository.findAllById(userIds.stream().distinct().toList())
                    .forEach(u -> usersById.put(u.getId(), u));
        }
        return usersById;
    }

    /**
     * Per-exam leaderboard: one row per learner with a PUBLISHED, non-preview attempt of
     * {@code examId}, ranked by their best such score (ties broken by earliest submission, then by
     * userId) using the same competition-ranking (1,1,3,...) as the class-wide board.
     */
    private List<LeaderboardEntryDto> getExamLeaderboard(String classId, String examId, String currentUserId,
                                                         Set<String> activeMembers) {
        List<ExamAttempt> published = attemptRepository.findByExamIdOrderByScoreDesc(examId).stream()
                .filter(a -> "PUBLISHED".equalsIgnoreCase(a.getStatus()) && !a.isPreview())
                .filter(a -> activeMembers.contains(a.getUserId())) // R14-11: drop REMOVED/BLOCKED members
                .toList();

        Map<String, ExamAttempt> bestByUser = new LinkedHashMap<>();
        for (ExamAttempt attempt : published) {
            bestByUser.merge(attempt.getUserId(), attempt,
                    (current, candidate) -> isBetterAttempt(candidate, current) ? candidate : current);
        }

        List<ExamAttempt> ordered = bestByUser.values().stream()
                .sorted(Comparator
                        .comparing((ExamAttempt a) -> a.getScore() != null ? a.getScore() : BigDecimal.ZERO)
                        .reversed()
                        .thenComparing(a -> a.getSubmittedAt() != null ? a.getSubmittedAt() : Instant.MAX)
                        .thenComparing(ExamAttempt::getUserId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // R16-08: one findAllById + one viewer context for the whole exam board.
        Map<String, User> usersById = loadUsers(ordered.stream().map(ExamAttempt::getUserId).toList());
        ProfileVisibilityPolicy.ViewerContext viewer = profileVisibilityPolicy.viewerContext(currentUserId, classId);

        List<LeaderboardEntryDto> dtos = new ArrayList<>();
        int rank = 0;
        BigDecimal previousScore = null;
        int position = 0;
        for (ExamAttempt attempt : ordered) {
            position++;
            BigDecimal score = attempt.getScore() != null ? attempt.getScore() : BigDecimal.ZERO;
            if (previousScore == null || score.compareTo(previousScore) != 0) {
                rank = position;
                previousScore = score;
            }

            LeaderboardEntryDto dto = new LeaderboardEntryDto();
            dto.setRank(rank);
            dto.setClassId(classId);
            // R13-08: the exam-scoped board ranks by the exam's own score, reusing totalPoints as
            // the displayed metric (percentage score) so the frontend's existing rendering — which
            // already shows totalPoints/currentTier per row — needs no shape change for this filter.
            dto.setTotalPoints(score.setScale(0, java.math.RoundingMode.HALF_UP).intValueExact());
            dto.setCurrentTier(null);
            dto.setLastCalculatedAt(attempt.getSubmittedAt());

            applyIdentity(dto, usersById.get(attempt.getUserId()), viewer);

            dtos.add(dto);
        }
        return dtos;
    }

    /**
     * Queues a leaderboard recalculation for a learner that runs <em>after</em> the current
     * transaction commits, in its own transaction.
     *
     * <p>Recalculating inline is unsafe when two exams publish for the same learner concurrently:
     * each transaction still holds an uncommitted attempt row, so neither can observe the other's
     * result and the last writer persists an incomplete total. Deferring to after commit means the
     * recalculation always reads committed attempts, and the leaderboard row lock taken by
     * {@link #recalculateUserPoints} serialises the two recalculations.</p>
     *
     * <p>The intent is also recorded durably in {@code leaderboard_recalc_jobs}, written
     * <em>inside</em> the caller's transaction so that the job becomes visible exactly when the
     * score does. Writing it in a separate transaction would let the sweeper pick the job up while
     * the score was still uncommitted, recalculate the old total and delete the job, leaving
     * nothing behind to repair the score if this process died before its post-commit
     * recalculation ran.</p>
     *
     * <p>The post-commit run clears only the job rows the recalculation observed before it read
     * the attempts, so a transient failure - or the process dying before the post-commit hook ran
     * at all - leaves a job behind for {@link #sweepPendingRecalculations()} to retry. A published
     * or corrected score can therefore no longer stay silently missing from the total until
     * someone rebuilds the board by hand.</p>
     *
     * <p>Outside a transaction the recalculation runs immediately.</p>
     */
    public void scheduleRecalculation(String classId, String userId) {
        if (classId == null || userId == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            self().recalculateUserPoints(classId, userId);
            return;
        }

        @SuppressWarnings("unchecked")
        Set<String> pending = (Set<String>) TransactionSynchronizationManager.getResource(PENDING_RECALCULATIONS);
        if (pending == null) {
            pending = new LinkedHashSet<>();
            TransactionSynchronizationManager.bindResource(PENDING_RECALCULATIONS, pending);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_RECALCULATIONS);
                }
            });
        }
        // Recalculation is idempotent and reads every published attempt, so one run per learner
        // per transaction is enough no matter how many attempts were published.
        if (!pending.add(classId + "::" + userId)) return;

        enqueueRecalcJob(classId, userId);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // R20-01: NO database work here. Spring keeps this request's JDBC connection until after the
                // afterCommit callbacks, so a recalculation transaction (REQUIRES_NEW) - and the entry creation
                // it used to nest - made every submitting request hold one pooled connection while waiting for
                // a second one: N concurrent submits exhausted the pool and froze the whole backend. Hand the
                // work to the bounded worker pool instead; the durable job row already committed with the score
                // is the safety net (sweepPendingRecalculations) if the hand-off is dropped or the process dies.
                dispatchRecalculation(classId, userId);
            }
        });
    }

    /** Pure in-memory hand-off to the recalculation worker pool; never touches the database, never throws. */
    private void dispatchRecalculation(String classId, String userId) {
        LeaderboardRecalcExecutor executor = recalcExecutor;
        if (executor == null) {
            return; // no worker pool (plain unit tests): the durable job + sweeper will run it
        }
        executor.submit(classId + "::" + userId, () -> runQueuedRecalculation(classId, userId));
    }

    /** Runs on a worker thread that holds no connection: the recalculation opens its own single transaction. */
    void runQueuedRecalculation(String classId, String userId) {
        try {
            self().recalculateUserPointsInNewTransaction(classId, userId);
        } catch (RuntimeException ex) {
            // The publishing transaction is long committed; never fail it because of the leaderboard.
            // The durable job row survives, so the sweeper retries this.
            log.error("Leaderboard recalculation failed for class {} user {}; queued for retry", classId, userId, ex);
            try {
                self().recordRecalcFailure(classId, userId, ex);
            } catch (RuntimeException failureRecordingError) {
                log.warn("Could not record leaderboard recalculation failure for class {} user {}: {}",
                        classId, userId, failureRecordingError.getMessage());
            }
        }
    }

    /**
     * Arms a durable retry job for a learner in the caller's transaction.
     *
     * <p>A plain insert of a fresh row: the table carries no unique key on (class, user), so this
     * neither blocks nor collides with a concurrent publication for the same learner, and it can
     * never raise a constraint violation that would poison the publishing transaction.</p>
     */
    private void enqueueRecalcJob(String classId, String userId) {
        LeaderboardRecalcJob job = new LeaderboardRecalcJob(classId, userId);
        if (recalcExecutor != null && sweepGraceSeconds > 0) {
            // R20-01: the worker pool services the job within milliseconds of the commit; hide it from the sweeper for
            // a short grace so both do not recalculate the same learner. If the hand-off is dropped or the process
            // dies, the sweeper picks the job up once the grace has elapsed.
            job.setNextAttemptAt(Instant.now().plusSeconds(sweepGraceSeconds));
        }
        recalcJobRepository.save(job);
    }

    /** Pushes a learner's failed armings out by an exponential backoff so the sweeper does not spin on them. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRecalcFailure(String classId, String userId, Throwable cause) {
        try {
            List<LeaderboardRecalcJob> jobs = recalcJobRepository.findByClassIdAndUserId(classId, userId);
            if (jobs.isEmpty()) {
                // The arming was lost (or never committed); re-arm so the score is still repaired.
                jobs = List.of(new LeaderboardRecalcJob(classId, userId));
            }
            String message = cause == null ? null : cause.getClass().getSimpleName() + ": " + cause.getMessage();
            String lastError = message == null || message.length() <= 1000 ? message : message.substring(0, 1000);
            for (LeaderboardRecalcJob job : jobs) {
                int attempts = job.getAttempts() + 1;
                job.setAttempts(attempts);
                job.setLastError(lastError);
                long backoff = Math.min(MAX_RECALC_BACKOFF_SECONDS, (long) Math.pow(2, Math.min(attempts, 8)));
                job.setNextAttemptAt(Instant.now().plusSeconds(backoff));
                recalcJobRepository.save(job);
            }
        } catch (RuntimeException ex) {
            log.warn("Could not record leaderboard recalculation failure for class {} user {}: {}",
                    classId, userId, ex.getMessage());
        }
    }

    /**
     * Retries leaderboard recalculations whose durable job is still outstanding.
     *
     * <p>This is what makes publication eventually consistent with the leaderboard: a job only
     * disappears once {@link #recalculateUserPoints} has committed, so transient database or
     * process failures are repaired automatically rather than needing a manual rebuild.</p>
     */
    @Scheduled(fixedDelayString = "${classroom.leaderboard.recalc.sweep-ms:5000}")
    public void sweepPendingRecalculations() {
        List<LeaderboardRecalcJob> due;
        try {
            due = recalcJobRepository.findDueJobs(Instant.now(), PageRequest.of(0, 50));
        } catch (RuntimeException ex) {
            log.warn("Could not read pending leaderboard recalculation jobs: {}", ex.getMessage());
            return;
        }
        // One row per publication means a learner can have several due armings; one recalculation
        // services all of them, so do not run it once per row.
        Set<String> swept = new LinkedHashSet<>();
        for (LeaderboardRecalcJob job : due) {
            if (!swept.add(job.getClassId() + "::" + job.getUserId())) continue;
            try {
                self().recalculateUserPointsInNewTransaction(job.getClassId(), job.getUserId());
            } catch (RuntimeException ex) {
                log.error("Retry of leaderboard recalculation failed for class {} user {}",
                        job.getClassId(), job.getUserId(), ex);
                self().recordRecalcFailure(job.getClassId(), job.getUserId(), ex);
            }
        }
    }

    /** Runs {@link #recalculateUserPoints} in a fresh transaction (used for post-commit recalculation). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recalculateUserPointsInNewTransaction(String classId, String userId) {
        recalculateUserPoints(classId, userId);
    }

    /**
     * Recalculates a learner's total leaderboard points for one class.
     *
     * <p>The learner's leaderboard row is locked first, which serialises concurrent
     * recalculations for the same (classId, userId). The published attempts are then read under a
     * lock as well, so the read always returns the latest committed rows rather than a snapshot
     * taken before another recalculation committed. The leaderboard row is always the first lock
     * acquired, giving a consistent lock ordering.</p>
     */
    @Transactional
    public void recalculateUserPoints(String classId, String userId) {
        LeaderboardEntry lockedEntry = lockOrCreateEntry(classId, userId);

        // Snapshot the outstanding armings *before* reading the attempts. A publication that
        // commits from here on is not in this set, so the delete at the end of this method cannot
        // clear a job whose score this run had no chance to observe.
        List<String> observedJobIds = new ArrayList<>();
        for (LeaderboardRecalcJob job : recalcJobRepository.findByClassIdAndUserId(classId, userId)) {
            observedJobIds.add(job.getId());
        }

        // Locking read - see ExamAttemptRepository#lockPublishedAttemptsForRecalculation.
        List<ExamAttempt> publishedAttempts =
                attemptRepository.lockPublishedAttemptsForRecalculation(classId, userId);

        // Keep only the best published attempt per exam (highest score; earliest submission wins ties).
        Map<String, ExamAttempt> bestByExam = new LinkedHashMap<>();
        for (ExamAttempt attempt : publishedAttempts) {
            bestByExam.merge(attempt.getExamId(), attempt,
                    (current, candidate) -> isBetterAttempt(candidate, current) ? candidate : current);
        }

        long totalRewardPointsLong = 0;

        for (Map.Entry<String, ExamAttempt> examEntry : bestByExam.entrySet()) {
            String examId = examEntry.getKey();
            ExamAttempt bestAttempt = examEntry.getValue();
            BigDecimal bestScore = (bestAttempt.getScore() != null) ? bestAttempt.getScore() : BigDecimal.ZERO;

            // Corrections to a published score must award the corrected score's rule
            // while retaining the captured award when the score itself is unchanged.
            boolean needsInitialSnapshot = bestAttempt.getRewardPointsSnapshot() == null;
            boolean scoreWasCorrected = bestAttempt.getRewardScoreSnapshot() != null
                    && bestScore.compareTo(bestAttempt.getRewardScoreSnapshot()) != 0;
            if (needsInitialSnapshot || scoreWasCorrected) {
                if (needsInitialSnapshot || bestAttempt.getRewardRuleSnapshot() == null) {
                    bestAttempt.setRewardRuleSnapshot(snapshotRewardRules(classId, examId));
                }
                bestAttempt.setRewardPointsSnapshot(calculateReward(bestAttempt.getRewardRuleSnapshot(), bestScore));
                bestAttempt.setRewardScoreSnapshot(bestScore);
                attemptRepository.save(bestAttempt);
            } else if (bestAttempt.getRewardScoreSnapshot() == null) {
                // Legacy reward snapshot has no score baseline; preserve its award and
                // establish the baseline without applying potentially edited rules.
                bestAttempt.setRewardScoreSnapshot(bestScore);
                attemptRepository.save(bestAttempt);
            }
            int reward = bestAttempt.getRewardPointsSnapshot() != null ? bestAttempt.getRewardPointsSnapshot() : 0;
            totalRewardPointsLong = Math.addExact(totalRewardPointsLong, (long) reward);
        }

        int totalRewardPoints = totalRewardPointsLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalRewardPointsLong;

        // Determine rank tier
        String tierName = "Tân thủ";
        List<RankTier> tiers = rankTierRepository.findByClassIdOrderByMinPointsAsc(classId);
        for (RankTier t : tiers) {
            if (totalRewardPoints >= t.getMinPoints()) {
                tierName = t.getTierName();
            }
        }

        // Save entry
        lockedEntry.setTotalPoints(totalRewardPoints);
        lockedEntry.setCurrentTier(tierName);
        lockedEntry.setLastCalculatedAt(Instant.now());

        leaderboardRepository.save(lockedEntry);

// The recalculation has now observed every published attempt committed before it snapshotted
        // the armings, so exactly those armings are satisfied. Deleting it in the same
        // transaction means the job only disappears if the new total actually commits, and the
        // id snapshot means a publication that armed a job after that read keeps its job.
        if (!observedJobIds.isEmpty()) {
            recalcJobRepository.deleteByIdIn(observedJobIds);
        }
    }

    /**
     * Returns the learner's leaderboard row holding a pessimistic write lock on it, creating the
     * row first when it does not exist yet.
     *
     * <p>R20-01: the creation used to run in a nested {@code REQUIRES_NEW} transaction, i.e. a second pooled
     * connection requested while the recalculation already held one - a second source of pool exhaustion under a
     * burst. It is now a single {@code INSERT ... ON DUPLICATE KEY UPDATE id = id} on the recalculation's own
     * connection: a lost race against a concurrent creator (unique key {@code uk_lb_class_user}) is a no-op instead of
     * a constraint violation that would poison the transaction, and the row lock that follows serialises the two.</p>
     */
    private LeaderboardEntry lockOrCreateEntry(String classId, String userId) {
        // Probe without a lock first (the common case: the row exists). A locking read that matches no row takes a
        // gap lock, and two concurrent recalculations would then deadlock trying to insert into each other's gap.
        if (leaderboardRepository.findByClassIdAndUserId(classId, userId).isEmpty()) {
            leaderboardRepository.insertIfAbsent(UUID.randomUUID().toString(), classId, userId, "Tân thủ");
        }

        return leaderboardRepository.lockByClassIdAndUserId(classId, userId)
                .orElseGet(() -> new LeaderboardEntry(classId, userId, 0, "Tân thủ"));
    }

    /**
     * Manual rebuild of every learner's total. R20-01: deliberately NOT one outer transaction - each learner is
     * recalculated in its own {@code REQUIRES_NEW} transaction, and an enclosing transaction would hold a pooled
     * connection for the whole loop while the inner ones asked for more (nested connection acquisition). Each step
     * (permission check, attempt read, per-learner recalculation, audit row) is its own short transaction instead.
     */
    public void rebuildLeaderboard(String classId, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "LEADERBOARD", "EDIT", null);

        // Find all distinct users with published attempts in this class
        List<ExamAttempt> allAttempts = attemptRepository.findAllPublishedAttemptsByClass(classId);
        Set<String> userIds = new HashSet<>();
        for (ExamAttempt a : allAttempts) {
            userIds.add(a.getUserId());
        }

        for (String uid : userIds) {
            self().recalculateUserPointsInNewTransaction(classId, uid);
        }

        // R16-09: a manual rebuild rewrites every learner's total, so it leaves an audit trail.
        auditService.record(classId, currentUserId, "LEADERBOARD_REBUILD", "LEADERBOARD", classId,
                String.format("{\"recalculatedUsers\":%d}", userIds.size()));
    }

    /** R8-05: current tiers + per-exam reward rules for the Studio leaderboard configuration editor. */
    @Transactional(readOnly = true)
    public com.classroom.modules.ranking.dto.LeaderboardConfigResponse getConfiguration(String classId, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "LEADERBOARD", "EDIT", null);
        List<com.classroom.modules.ranking.dto.LeaderboardConfigResponse.Tier> tiers =
                rankTierRepository.findByClassIdOrderByMinPointsAsc(classId).stream()
                        .map(t -> new com.classroom.modules.ranking.dto.LeaderboardConfigResponse.Tier(
                                t.getTierName(), t.getMinPoints(), t.getBadgeUrl(), t.getDescription()))
                        .toList();
        List<com.classroom.modules.ranking.dto.LeaderboardConfigResponse.Reward> rewards =
                rewardRuleRepository.findByClassId(classId).stream()
                        .map(r -> new com.classroom.modules.ranking.dto.LeaderboardConfigResponse.Reward(
                                r.getExamId(), r.getMinExamScore(), r.getRewardPoints()))
                        .toList();
        return new com.classroom.modules.ranking.dto.LeaderboardConfigResponse(tiers, rewards);
    }

    @Transactional
    public void configure(String classId, String currentUserId, LeaderboardConfigRequest request) {
        accessPolicy.enforceManage(currentUserId, classId, "LEADERBOARD", "EDIT", null);
        if (request == null || request.tiers() == null || request.tiers().isEmpty()
                || request.rewards() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "At least one tier and a reward list are required");
        }
        Set<Integer> thresholds = new HashSet<>();
        for (var tier : request.tiers()) {
            // R4-05: a null entry in the tiers list (e.g. a stray element in the JSON array) must
            // be a 400 contract error, not an NPE at tier.tierName().
            if (tier == null || tier.tierName() == null || tier.tierName().isBlank() || tier.minPoints() < 0
                    || tier.minPoints() > MAX_TIER_POINTS
                    || !thresholds.add(tier.minPoints())) throw new AppException(ErrorCode.BAD_REQUEST, "Invalid or duplicate rank tier");
        }
        Set<String> rewardThresholds = new HashSet<>();
        for (var reward : request.rewards()) {
            if (reward == null || reward.examId() == null || reward.examId().isBlank()) throw new AppException(ErrorCode.BAD_REQUEST, "Exam ID is required for each reward rule");
            Exam exam = examRepository.findById(reward.examId()).orElseThrow(() -> new AppException(ErrorCode.BAD_REQUEST, "Exam not found"));
            if (!classId.equals(exam.getClassId()) || reward.minExamScore() == null
                    || reward.minExamScore().signum() < 0 || reward.minExamScore().compareTo(BigDecimal.valueOf(100)) > 0
                    || reward.rewardPoints() < 0 || reward.rewardPoints() > MAX_REWARD_POINTS) throw new AppException(ErrorCode.BAD_REQUEST, "Invalid reward rule for class");
            String thresholdKey = reward.examId() + "::" + reward.minExamScore().stripTrailingZeros().toPlainString();
            if (!rewardThresholds.add(thresholdKey)) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Duplicate reward threshold for exam " + reward.examId() + ": " + reward.minExamScore());
            }
        }
        // R16-09: capture the configuration being replaced so the audit event can show before/after.
        String before = summarizeConfiguration(
                rankTierRepository.findByClassIdOrderByMinPointsAsc(classId),
                rewardRuleRepository.findByClassId(classId));
        rankTierRepository.deleteByClassId(classId);
        rewardRuleRepository.deleteByClassId(classId);
        List<RankTier> savedTiers =rankTierRepository.saveAll(request.tiers().stream().map(t -> new RankTier(classId, t.tierName().trim(), t.minPoints(), t.badgeUrl(), t.description())).toList());
        List<ExamRewardRule> savedRewards = rewardRuleRepository.saveAll(request.rewards().stream().map(r -> new ExamRewardRule(classId, r.examId(), r.minExamScore(), r.rewardPoints())).toList());
        auditService.record(classId, currentUserId, "LEADERBOARD_CONFIGURE", "LEADERBOARD", classId,
                "{\"before\":" + before + ",\"after\":" + summarizeConfiguration(savedTiers, savedRewards) + "}");

        // Update tiers on existing entries so persisted records stay in sync with new tier thresholds (Finding 12)
        List<LeaderboardEntry> existingEntries = leaderboardRepository.findByClassIdOrderByTotalPointsDesc(classId);
        List<RankTier> sortedTiers = rankTierRepository.findByClassIdOrderByMinPointsAsc(classId);
        for (LeaderboardEntry entry : existingEntries) {
            String updatedTier = "Tân thủ";
            for (RankTier t : sortedTiers) {
                if (entry.getTotalPoints() >= t.getMinPoints()) {
                    updatedTier = t.getTierName();
                }
            }
            entry.setCurrentTier(updatedTier);
        }
        if (!existingEntries.isEmpty()) {
            leaderboardRepository.saveAll(existingEntries);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper AUDIT_JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * R16-09: compact, human-readable snapshot of a class's leaderboard configuration for the audit
     * log: tiers as {@code name:minPoints} and reward rules as {@code examId:minScore=points}.
     */
    private static String summarizeConfiguration(List<RankTier> tiers, List<ExamRewardRule> rewards) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tiers", tiers == null ? List.of() : tiers.stream()
                .map(t -> t.getTierName() + ":" + t.getMinPoints()).toList());
        summary.put("rewards", rewards == null ? List.of() : rewards.stream()
                .map(r -> r.getExamId() + ":"
                        + (r.getMinExamScore() == null ? "?" : r.getMinExamScore().stripTrailingZeros().toPlainString())
                        + "=" + r.getRewardPoints()).toList());
        try {
            return AUDIT_JSON.writeValueAsString(summary);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    /** Sort key for tie-breaking equal totalPoints: entries never recalculated sort last. */
    private static Instant calculatedAtOrEpoch(LeaderboardEntry entry) {
        return entry.getLastCalculatedAt() != null ? entry.getLastCalculatedAt() : Instant.MAX;
    }

    /** Highest score wins; ties fall back to the earliest submission, then to a stable id order. */
    private static boolean isBetterAttempt(ExamAttempt candidate, ExamAttempt current) {
        BigDecimal candidateScore = candidate.getScore() != null ? candidate.getScore() : BigDecimal.ZERO;
        BigDecimal currentScore = current.getScore() != null ? current.getScore() : BigDecimal.ZERO;
        int byScore = candidateScore.compareTo(currentScore);
        if (byScore != 0) return byScore > 0;
        Instant candidateAt = candidate.getSubmittedAt();
        Instant currentAt = current.getSubmittedAt();
        if (candidateAt != null && currentAt != null && !candidateAt.equals(currentAt)) {
            return candidateAt.isBefore(currentAt);
        }
        if (candidate.getId() == null || current.getId() == null) return false;
        return candidate.getId().compareTo(current.getId()) < 0;
    }

    public String snapshotRewardRules(String classId, String examId) {
        return rewardRuleRepository.findByClassIdAndExamIdOrderByMinExamScoreDesc(classId, examId).stream()
                .map(rule -> rule.getMinExamScore().toPlainString() + ":" + rule.getRewardPoints())
                .collect(java.util.stream.Collectors.joining(","));
    }

    public int calculateReward(String snapshot, BigDecimal score) {
        if (snapshot == null || snapshot.isBlank()) return 0;
        for (String item : snapshot.split(",")) {
            String[] parts = item.split(":", -1);
            if (parts.length == 2 && score.compareTo(new BigDecimal(parts[0])) >= 0) {
                return Integer.parseInt(parts[1]);
            }
        }
        return 0;
    }
}
