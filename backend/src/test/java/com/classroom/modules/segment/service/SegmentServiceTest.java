package com.classroom.modules.segment.service;

import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.exam.model.ExamAttempt;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.learning.repository.LessonProgressRepository;
import com.classroom.modules.segment.model.Segment;
import com.classroom.modules.segment.policy.SegmentParser;
import com.classroom.modules.segment.repository.SegmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * R3-08: previewSegment used to call isUserInSegment per member, which independently re-fetched
 * and re-parsed the same segment row for every member in the class (N+1). These tests pin down
 * the correct matched/total counts and verify the segment row is now resolved only once per
 * preview request regardless of how many members are evaluated.
 */
@ExtendWith(MockitoExtension.class)
class SegmentServiceTest {

    @Mock
    private SegmentRepository segmentRepository;
    @Mock
    private ClassMemberRepository memberRepository;
    @Mock
    private LessonProgressRepository progressRepository;
    @Mock
    private ProPolicy proPolicy;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private EntitlementRepository entitlementRepository;
    @Mock
    private ExamAttemptRepository examAttemptRepository;

    private SegmentService segmentService;
    private SegmentParser segmentParser;

    @BeforeEach
    void setUp() {
        segmentParser = new SegmentParser();
        segmentService = new SegmentService(segmentRepository, memberRepository, progressRepository,
                proPolicy, accessPolicy, segmentParser, new ObjectMapper(), entitlementRepository,
                examAttemptRepository);
    }

    @Test
    @DisplayName("R3-08: previewSegment resolves the segment row exactly once regardless of member count")
    void previewSegmentFetchesSegmentOnlyOnce() {
        Segment segment = new Segment("class-1", "PRO members", "desc", "AND",
                "[{\"criterion\":\"IS_PRO\",\"operator\":\"EQUALS\",\"value\":\"true\"}]");
        segment.setId("seg-1");
        when(segmentRepository.findById("seg-1")).thenReturn(Optional.of(segment));

        List<ClassMember> members = List.of(
                activeMember("u1"), activeMember("u2"), activeMember("u3")
        );
        when(memberRepository.findByClassId("class-1")).thenReturn(members);
        when(proPolicy.isPro(eq("u1"), eq("class-1"))).thenReturn(true);
        when(proPolicy.isPro(eq("u2"), eq("class-1"))).thenReturn(false);
        when(proPolicy.isPro(eq("u3"), eq("class-1"))).thenReturn(true);

        Map<String, Object> result = segmentService.previewSegment("seg-1", "class-1", "owner-1");

        assertEquals(3, result.get("totalMembers"));
        assertEquals(2, result.get("matchingMembers"));
        // The core N+1 fix: the segment row itself must be fetched once per request, not once
        // per member evaluated.
        verify(segmentRepository, times(1)).findById("seg-1");
    }

    @Test
    @DisplayName("previewSegment returns zero matches when the segment has no rules")
    void previewSegmentEmptyRulesMatchesNone() {
        Segment segment = new Segment("class-1", "Empty", "desc", "AND", "[]");
        segment.setId("seg-empty");
        when(segmentRepository.findById("seg-empty")).thenReturn(Optional.of(segment));
        when(memberRepository.findByClassId("class-1")).thenReturn(List.of(activeMember("u1")));

        Map<String, Object> result = segmentService.previewSegment("seg-empty", "class-1", "owner-1");

        assertEquals(1, result.get("totalMembers"));
        assertEquals(0, result.get("matchingMembers"));
    }

    // --- R8-08: AVG_EXAM_SCORE must be a missing value (no match) when there are no published
    // attempts, and must average the BEST published attempt per exam, not every attempt ---

    @Test
    @DisplayName("R8-08: a learner with no published attempts has no AVG_EXAM_SCORE value (missing -> no match)")
    void avgExamScoreMissingWhenNoPublishedAttempts() {
        Segment segment = new Segment("class-1", "Low scorers", "desc", "AND",
                "[{\"criterion\":\"AVG_EXAM_SCORE\",\"operator\":\"LESS_THAN_OR_EQUAL\",\"value\":\"50\"}]");
        segment.setId("seg-avg");
        when(segmentRepository.findById("seg-avg")).thenReturn(Optional.of(segment));
        when(memberRepository.findByClassIdAndUserId("class-1", "student-1"))
                .thenReturn(Optional.of(activeMember("student-1")));
        when(examAttemptRepository.findByClassIdAndUserIdAndStatusAndIsPreviewFalse("class-1", "student-1", "PUBLISHED"))
                .thenReturn(List.of());

        // A learner with zero exam history must NOT match "score <= 50" — 0.0 would wrongly match,
        // but a missing value never matches any operator (SegmentParser.evaluate's null-context rule).
        boolean matches = segmentService.isUserInSegment("seg-avg", "student-1", "class-1");

        assertEquals(false, matches);
    }

    @Test
    @DisplayName("R8-08: AVG_EXAM_SCORE averages the best published attempt per exam, not every attempt")
    void avgExamScoreUsesBestAttemptPerExam() {
        Segment segment = new Segment("class-1", "High scorers", "desc", "AND",
                "[{\"criterion\":\"AVG_EXAM_SCORE\",\"operator\":\"GREATER_THAN_OR_EQUAL\",\"value\":\"80\"}]");
        segment.setId("seg-avg-high");
        when(segmentRepository.findById("seg-avg-high")).thenReturn(Optional.of(segment));
        when(memberRepository.findByClassIdAndUserId("class-1", "student-1"))
                .thenReturn(Optional.of(activeMember("student-1")));

        // Two attempts on exam-A (60 then a resit at 100 -> best is 100) and one on exam-B (60).
        // Best-per-exam average = (100 + 60) / 2 = 80, which meets the >= 80 threshold.
        // A naive average-over-all-attempts would instead give (60 + 100 + 60) / 3 = 73.3, which
        // would NOT meet the threshold - so this test also pins down which behaviour is correct.
        ExamAttempt examAWorse = attemptWithScore("exam-A", "60");
        ExamAttempt examABest = attemptWithScore("exam-A", "100");
        ExamAttempt examB = attemptWithScore("exam-B", "60");
        when(examAttemptRepository.findByClassIdAndUserIdAndStatusAndIsPreviewFalse("class-1", "student-1", "PUBLISHED"))
                .thenReturn(List.of(examAWorse, examABest, examB));

        boolean matches = segmentService.isUserInSegment("seg-avg-high", "student-1", "class-1");

        assertEquals(true, matches);
    }

    // --- R14-03: COMPLETED_LESSONS_COUNT counts completions on visible (non-archived) lessons only ---

    @Test
    @DisplayName("R14-03: COMPLETED_LESSONS_COUNT is evaluated from the visible-lesson completion count")
    void completedLessonsCountUsesVisibleLessonsOnly() {
        Segment segment = new Segment("class-1", "Chăm chỉ", "desc", "AND",
                "[{\"criterion\":\"COMPLETED_LESSONS_COUNT\",\"operator\":\"GREATER_THAN_OR_EQUAL\",\"value\":\"3\"}]");
        segment.setId("seg-completed");
        when(segmentRepository.findById("seg-completed")).thenReturn(Optional.of(segment));
        when(memberRepository.findByClassIdAndUserId("class-1", "student-1"))
                .thenReturn(Optional.of(activeMember("student-1")));
        // 2 visible completions: the (say) 2 extra completions on archived lessons are not counted
        // by this query, so the learner does not reach the >= 3 threshold.
        when(progressRepository.countCompletedVisibleByUserIdAndClassId("student-1", "class-1")).thenReturn(2L);

        assertEquals(false, segmentService.isUserInSegment("seg-completed", "student-1", "class-1"));

        when(progressRepository.countCompletedVisibleByUserIdAndClassId("student-1", "class-1")).thenReturn(3L);
        assertEquals(true, segmentService.isUserInSegment("seg-completed", "student-1", "class-1"));
        verify(progressRepository, never()).countByUserIdAndClassIdAndCompletedTrue(any(), any());
    }

    private ExamAttempt attemptWithScore(String examId, String score) {
        ExamAttempt attempt = new ExamAttempt(examId, "student-1", "class-1", Instant.now(), false);
        attempt.setScore(new BigDecimal(score));
        return attempt;
    }

    private ClassMember activeMember(String userId) {
        ClassMember m = new ClassMember("class-1", userId, "STUDENT");
        m.setState("ACTIVE");
        return m;
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
