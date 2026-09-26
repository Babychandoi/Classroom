package com.classroom.modules.segment.policy;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.segment.dto.SegmentRule;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SegmentParser {

    private static final Set<String> ALLOWED_CRITERIA = Set.of(
            "IS_PRO",
            "COURSE_OWNED",
            "COMPLETED_LESSONS_COUNT",
            "AVG_EXAM_SCORE",
            "DAYS_SINCE_JOINED"
    );

    private static final Set<String> BOOLEAN_OPERATORS = Set.of(
            "EQUALS",
            "NOT_EQUALS"
    );

    private static final Set<String> STRING_OPERATORS = Set.of(
            "EQUALS",
            "NOT_EQUALS",
            "IN",
            "CONTAINS"
    );

    private static final Set<String> NUMERIC_OPERATORS = Set.of(
            "EQUALS",
            "NOT_EQUALS",
            "GREATER_THAN",
            "GREATER_THAN_OR_EQUAL",
            "LESS_THAN",
            "LESS_THAN_OR_EQUAL"
    );

    public void validateRule(SegmentRule rule) {
        if (rule == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Quy tắc segment không được để trống");
        }

        String criterion = (rule.getCriterion() != null) ? rule.getCriterion().toUpperCase().trim() : "";
        if (!ALLOWED_CRITERIA.contains(criterion)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tiêu chí segment không hợp lệ: " + rule.getCriterion());
        }

        String operator = (rule.getOperator() != null) ? rule.getOperator().toUpperCase().trim() : "";
        String value = (rule.getValue() != null) ? rule.getValue().trim() : "";

        if (value.isEmpty()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Giá trị quy tắc segment không được để trống");
        }

        // Strict typed validation & operator compatibility (Finding 10)
        switch (criterion) {
            case "IS_PRO" -> {
                if (!BOOLEAN_OPERATORS.contains(operator)) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Toán tử không tương thích với tiêu chí IS_PRO: " + operator);
                }
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Giá trị cho IS_PRO phải là 'true' hoặc 'false'");
                }
            }
            case "COURSE_OWNED" -> {
                if (!STRING_OPERATORS.contains(operator)) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Toán tử không tương thích với tiêu chí COURSE_OWNED: " + operator);
                }
                // Validate alphanumeric/UUID identifier format without SQL or shell metacharacters
                String[] parts = "IN".equals(operator) ? value.split(",") : new String[]{value};
                for (String part : parts) {
                    String trimmed = part.trim();
                    if (!trimmed.matches("^[a-zA-Z0-9_-]+$")) {
                        throw new AppException(ErrorCode.BAD_REQUEST, "Mã khóa học không hợp lệ: " + trimmed);
                    }
                }
            }
            case "COMPLETED_LESSONS_COUNT", "DAYS_SINCE_JOINED" -> {
                if (!NUMERIC_OPERATORS.contains(operator)) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Toán tử không tương thích với tiêu chí số: " + operator);
                }
                try {
                    long intVal = Long.parseLong(value);
                    if (intVal < 0) {
                        throw new AppException(ErrorCode.BAD_REQUEST, "Giá trị phải lớn hơn hoặc bằng 0");
                    }
                } catch (NumberFormatException e) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Giá trị phải là số nguyên hợp lệ: " + value);
                }
            }
            case "AVG_EXAM_SCORE" -> {
                if (!NUMERIC_OPERATORS.contains(operator)) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Toán tử không tương thích với tiêu chí điểm: " + operator);
                }
                try {
                    double dVal = Double.parseDouble(value);
                    if (!Double.isFinite(dVal) || dVal < 0.0 || dVal > 100.0) {
                        throw new AppException(ErrorCode.BAD_REQUEST, "Điểm trung bình phải nằm trong khoảng [0, 100]");
                    }
                } catch (NumberFormatException e) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Giá trị điểm phải là số thực hợp lệ: " + value);
                }
            }
            default -> throw new AppException(ErrorCode.BAD_REQUEST, "Tiêu chí chưa được hỗ trợ: " + criterion);
        }
    }

    public boolean evaluate(SegmentRule rule, Map<String, Object> userContext) {
        validateRule(rule);

        String criterion = rule.getCriterion().toUpperCase().trim();
        String operator = rule.getOperator().toUpperCase().trim();
        String expectedVal = rule.getValue() != null ? rule.getValue().trim() : "";

        Object actualObj = userContext.get(criterion);
        if (actualObj == null) {
            // Treat missing values as non-match/deny, including NOT_EQUALS (Finding 10)
            return false;
        }

        if (actualObj instanceof Boolean boolVal) {
            boolean expBool = Boolean.parseBoolean(expectedVal);
            return switch (operator) {
                case "EQUALS" -> boolVal == expBool;
                case "NOT_EQUALS" -> boolVal != expBool;
                default -> false;
            };
        }

        if (actualObj instanceof Collection<?> coll) {
            Set<String> stringSet = coll.stream().map(Object::toString).collect(Collectors.toSet());
            return switch (operator) {
                case "EQUALS", "CONTAINS" -> stringSet.contains(expectedVal);
                case "NOT_EQUALS" -> !stringSet.contains(expectedVal);
                case "IN" -> Arrays.stream(expectedVal.split(","))
                        .map(String::trim)
                        .anyMatch(stringSet::contains);
                default -> false;
            };
        }

        if (actualObj instanceof Number numVal) {
            double actualDouble = numVal.doubleValue();
            double expectedDouble;
            try {
                expectedDouble = Double.parseDouble(expectedVal);
            } catch (NumberFormatException e) {
                return false;
            }

            return switch (operator) {
                case "EQUALS" -> Double.compare(actualDouble, expectedDouble) == 0;
                case "NOT_EQUALS" -> Double.compare(actualDouble, expectedDouble) != 0;
                case "GREATER_THAN" -> actualDouble > expectedDouble;
                case "GREATER_THAN_OR_EQUAL" -> actualDouble >= expectedDouble;
                case "LESS_THAN" -> actualDouble < expectedDouble;
                case "LESS_THAN_OR_EQUAL" -> actualDouble <= expectedDouble;
                default -> false;
            };
        }

        String actualStr = actualObj.toString();
        return switch (operator) {
            case "EQUALS" -> actualStr.equalsIgnoreCase(expectedVal);
            case "NOT_EQUALS" -> !actualStr.equalsIgnoreCase(expectedVal);
            case "CONTAINS" -> actualStr.toLowerCase().contains(expectedVal.toLowerCase());
            case "IN" -> Arrays.stream(expectedVal.split(","))
                    .map(String::trim)
                    .anyMatch(v -> v.equalsIgnoreCase(actualStr));
            default -> false;
        };
    }
}
