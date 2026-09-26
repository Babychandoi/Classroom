package com.classroom.modules.segment.policy;

import com.classroom.common.AppException;
import com.classroom.modules.segment.dto.SegmentRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SegmentParserTest {

    private final SegmentParser parser = new SegmentParser();

    @Test
    @DisplayName("UT-07: Evaluates valid whitelisted rules correctly")
    void testValidEvaluation() {
        SegmentRule rule1 = new SegmentRule("IS_PRO", "EQUALS", "true");
        assertTrue(parser.evaluate(rule1, Map.of("IS_PRO", true)));
        assertFalse(parser.evaluate(rule1, Map.of("IS_PRO", false)));

        SegmentRule rule2 = new SegmentRule("COMPLETED_LESSONS_COUNT", "GREATER_THAN_OR_EQUAL", "5");
        assertTrue(parser.evaluate(rule2, Map.of("COMPLETED_LESSONS_COUNT", 5L)));
        assertTrue(parser.evaluate(rule2, Map.of("COMPLETED_LESSONS_COUNT", 10L)));
        assertFalse(parser.evaluate(rule2, Map.of("COMPLETED_LESSONS_COUNT", 3L)));
    }

    @Test
    @DisplayName("UT-07: Rejects SQL injection attempts and dangerous keywords")
    void testRejectsSqlInjection() {
        SegmentRule sqlInjectionRule = new SegmentRule(
                "IS_PRO",
                "EQUALS",
                "true' OR '1'='1'; DROP TABLE users; --"
        );

        assertThrows(AppException.class, () -> parser.validateRule(sqlInjectionRule));
    }

    @Test
    @DisplayName("UT-07: Rejects invalid or unwhitelisted criteria and operators")
    void testRejectsUnwhitelistedInputs() {
        SegmentRule badCriterion = new SegmentRule("CREDIT_CARD_NUMBER", "EQUALS", "1234");
        assertThrows(AppException.class, () -> parser.validateRule(badCriterion));

        SegmentRule badOperator = new SegmentRule("IS_PRO", "EXECUTE_SHELL", "rm -rf");
        assertThrows(AppException.class, () -> parser.validateRule(badOperator));
    }

    @Test
    @DisplayName("Finding 10: Rejects incompatible operators and malformed typed values")
    void testOperatorTypeCompatibility() {
        // GREATER_THAN is not allowed for boolean criterion IS_PRO
        SegmentRule invalidOp = new SegmentRule("IS_PRO", "GREATER_THAN", "true");
        assertThrows(AppException.class, () -> parser.validateRule(invalidOp));

        // Non-numeric value for numeric criterion
        SegmentRule nonNumeric = new SegmentRule("COMPLETED_LESSONS_COUNT", "EQUALS", "not-a-number");
        assertThrows(AppException.class, () -> parser.validateRule(nonNumeric));

        // Score above 100
        SegmentRule scoreOutOfRange = new SegmentRule("AVG_EXAM_SCORE", "EQUALS", "150");
        assertThrows(AppException.class, () -> parser.validateRule(scoreOutOfRange));
        assertThrows(AppException.class, () -> parser.validateRule(new SegmentRule("AVG_EXAM_SCORE", "NOT_EQUALS", "NaN")));
        assertThrows(AppException.class, () -> parser.validateRule(new SegmentRule("AVG_EXAM_SCORE", "EQUALS", "Infinity")));

        // Course identifier with illegal punctuation
        SegmentRule badCourseId = new SegmentRule("COURSE_OWNED", "EQUALS", "course;--drop");
        assertThrows(AppException.class, () -> parser.validateRule(badCourseId));
    }

    @Test
    @DisplayName("Finding 10: Missing context values evaluate to false (deny by default, including NOT_EQUALS)")
    void testMissingContextEvaluatesToFalse() {
        // AVG_EXAM_SCORE is missing from context
        SegmentRule notEqualsRule = new SegmentRule("AVG_EXAM_SCORE", "NOT_EQUALS", "50");
        assertFalse(parser.evaluate(notEqualsRule, Map.of()));

        // COURSE_OWNED is missing from context
        SegmentRule courseRule = new SegmentRule("COURSE_OWNED", "NOT_EQUALS", "course-1");
        assertFalse(parser.evaluate(courseRule, Map.of()));
    }

    @Test
    @DisplayName("Finding 10: Evaluates COURSE_OWNED and AVG_EXAM_SCORE with authoritative context values")
    void testCourseOwnedAndAvgScoreEvaluation() {
        SegmentRule ownsCourseA = new SegmentRule("COURSE_OWNED", "EQUALS", "course-A");
        assertTrue(parser.evaluate(ownsCourseA, Map.of("COURSE_OWNED", java.util.Set.of("course-A", "course-B"))));
        assertFalse(parser.evaluate(ownsCourseA, Map.of("COURSE_OWNED", java.util.Set.of("course-C"))));

        SegmentRule inCourses = new SegmentRule("COURSE_OWNED", "IN", "course-X,course-Y");
        assertTrue(parser.evaluate(inCourses, Map.of("COURSE_OWNED", java.util.Set.of("course-Y"))));
        assertFalse(parser.evaluate(inCourses, Map.of("COURSE_OWNED", java.util.Set.of("course-Z"))));

        SegmentRule avgScoreAbove80 = new SegmentRule("AVG_EXAM_SCORE", "GREATER_THAN_OR_EQUAL", "80");
        assertTrue(parser.evaluate(avgScoreAbove80, Map.of("AVG_EXAM_SCORE", 85.5)));
        assertFalse(parser.evaluate(avgScoreAbove80, Map.of("AVG_EXAM_SCORE", 75.0)));
    }
}
