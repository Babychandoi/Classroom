package com.classroom.modules.assessment.grading;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Objects;

/** D-34: all score arithmetic - BigDecimal, 2 decimals, HALF_UP. Group = Σ children, section = Σ nodes, total = Σ sections. */
public final class ScoreMath {
    public static final MathContext MC = MathContext.DECIMAL64;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private ScoreMath() {}

    public static BigDecimal round(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** Null-safe points (null counts as 0). */
    public static BigDecimal points(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** round(fraction × points). */
    public static BigDecimal ofFraction(BigDecimal fraction, BigDecimal points) {
        return round(points(fraction).multiply(points(points), MC));
    }

    public static BigDecimal sum(Collection<BigDecimal> values) {
        return round(values.stream().filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** a / b as a fraction with enough precision for later rounding; 0 when b is 0. */
    public static BigDecimal ratio(long a, long b) {
        if (b == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(a).divide(BigDecimal.valueOf(b), MC);
    }

    public static boolean isOne(BigDecimal fraction) {
        return fraction != null && fraction.compareTo(BigDecimal.ONE) >= 0;
    }

    public static boolean isZero(BigDecimal fraction) {
        return fraction == null || fraction.signum() <= 0;
    }
}
