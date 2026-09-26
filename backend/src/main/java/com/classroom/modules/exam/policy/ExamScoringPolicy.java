package com.classroom.modules.exam.policy;

import com.classroom.modules.exam.model.AttemptAnswer;
import com.classroom.modules.exam.model.ExamAttempt;
import com.classroom.modules.exam.model.Question;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ExamScoringPolicy {

    public void autoGradeAttempt(ExamAttempt attempt, List<Question> questions, List<AttemptAnswer> answers) {
        // Build answer lookup for O(1) access by question ID
        Map<String, AttemptAnswer> answerByQuestionId = answers.stream()
                .collect(Collectors.toMap(AttemptAnswer::getQuestionId, a -> a, (a1, a2) -> a1));

        BigDecimal totalAwarded = BigDecimal.ZERO;
        long maxPointsLong = 0;
        boolean hasPendingManualGrading = false;

        for (Question q : questions) {
            maxPointsLong = Math.addExact(maxPointsLong, q.getPoints());
        }
        int maxPoints = Math.toIntExact(maxPointsLong);
        attempt.setTotalPoints(maxPoints);

        for (Question q : questions) {
            AttemptAnswer ans = answerByQuestionId.get(q.getId());

            if ("MULTIPLE_CHOICE".equalsIgnoreCase(q.getType()) || "TRUE_FALSE".equalsIgnoreCase(q.getType())) {
                if (ans != null && q.getAnswerKey() != null && ans.getStudentAnswer() != null
                        && q.getAnswerKey().trim().equalsIgnoreCase(ans.getStudentAnswer().trim())) {
                    ans.setPointsAwarded(BigDecimal.valueOf(q.getPoints()));
                    totalAwarded = totalAwarded.add(BigDecimal.valueOf(q.getPoints()));
                } else {
                    if (ans != null) ans.setPointsAwarded(BigDecimal.ZERO);
                }
            } else if ("ESSAY".equalsIgnoreCase(q.getType())) {
                if (ans != null && ans.getPointsAwarded() != null) {
                    BigDecimal points = ans.getPointsAwarded();
                    if (points.compareTo(BigDecimal.ZERO) < 0 || points.compareTo(BigDecimal.valueOf(q.getPoints())) > 0) {
                        throw new com.classroom.common.AppException(
                                com.classroom.common.ErrorCode.BAD_REQUEST,
                                String.format("Điểm câu hỏi tự luận %s nằm ngoài giới hạn [0, %d]: %s", q.getId(), q.getPoints(), points)
                        );
                    }
                    totalAwarded = totalAwarded.add(points);
                } else {
                    // Essay question has no answer record OR answer has no pointsAwarded yet — needs manual grading
                    hasPendingManualGrading = true;
                }
            }
        }

        if (maxPoints > 0) {
            BigDecimal scorePercent = totalAwarded.multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(maxPoints), 2, RoundingMode.HALF_UP);
            attempt.setScore(scorePercent);
        } else {
            attempt.setScore(BigDecimal.ZERO);
        }

        if (hasPendingManualGrading) {
            attempt.setStatus("GRADING");
        } else {
            attempt.setStatus("PUBLISHED");
        }
    }
}
