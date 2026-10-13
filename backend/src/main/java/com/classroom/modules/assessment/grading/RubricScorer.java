package com.classroom.modules.assessment.grading;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.assessment.dto.CatalogDtos.RubricCriterion;
import com.classroom.modules.assessment.dto.CatalogDtos.RubricDto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * D-34: rubric score = Σ_c (weight_c / 100) × (level_c / (L - 1)) × maxPoints, rounded to 2 decimals at the end. Levels are 0-based
 * (0 = the lowest label). Every criterion must be given a level in 0..L-1.
 */
public final class RubricScorer {

    private RubricScorer() {}

    public static BigDecimal score(RubricDto rubric, Map<String, Integer> levels, BigDecimal maxPoints) {
        int levelCount = rubric.levels().size();
        if (levelCount == 0 || rubric.criteria().isEmpty()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Rubric chưa có mức hoặc tiêu chí");
        }
        double weightSum=rubric.criteria().stream().mapToDouble(RubricCriterion::weight).sum();
        if(!Double.isFinite(weightSum) || Math.abs(weightSum-100)>0.0001 || rubric.criteria().stream().anyMatch(c->!Double.isFinite(c.weight()) || c.weight()<=0)
            || rubric.criteria().stream().map(RubricCriterion::id).distinct().count()!=rubric.criteria().size())
            throw new AppException(ErrorCode.BAD_REQUEST,"Tổng trọng số rubric phải bằng 100%, mỗi tiêu chí có trọng số dương");
        if(levels==null || levels.size()!=rubric.criteria().size())
            throw new AppException(ErrorCode.BAD_REQUEST,"Cần chọn mức cho đúng các tiêu chí của rubric");
        BigDecimal sum = BigDecimal.ZERO;
        for (RubricCriterion criterion : rubric.criteria()) {
            Integer level = levels == null ? null : levels.get(criterion.id());
            if (level == null || level < 0 || level >= levelCount) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Chưa chọn mức hợp lệ cho tiêu chí \"" + criterion.title() + "\"");
            }
            BigDecimal levelFraction = levelCount == 1 ? BigDecimal.ONE : ScoreMath.ratio(level, levelCount - 1);
            BigDecimal weight = BigDecimal.valueOf(criterion.weight()).divide(BigDecimal.valueOf(100), ScoreMath.MC);
            sum = sum.add(weight.multiply(levelFraction, ScoreMath.MC), ScoreMath.MC);
        }
        return ScoreMath.round(sum.multiply(ScoreMath.points(maxPoints), ScoreMath.MC).min(ScoreMath.points(maxPoints)));
    }
}
