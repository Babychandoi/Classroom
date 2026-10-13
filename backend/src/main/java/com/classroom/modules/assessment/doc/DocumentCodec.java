package com.classroom.modules.assessment.doc;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * D-34: (de)serialization of {@link AssessmentDocument}. Parsing enforces the size limit and maps every malformed input to a 400 with a
 * Vietnamese message; {@link #canonicalHash} is the SHA-256 of the document with all object keys sorted (decides whether assigning again
 * needs a new version).
 */
public final class DocumentCodec {

    public static final int MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    private DocumentCodec() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static AssessmentDocument parse(String json) {
        if (json == null || json.isBlank()) {
            throw invalid("Đề trống");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw invalid("Đề vượt quá dung lượng tối đa 2 MB");
        }
        try {
            return parse(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            throw invalid("Không đọc được nội dung đề");
        }
    }

    public static AssessmentDocument parse(JsonNode tree) {
        if (tree == null || !tree.isObject()) {
            throw invalid("Nội dung đề phải là một đối tượng JSON");
        }
        try {
            if (MAPPER.writeValueAsBytes(tree).length > MAX_DOCUMENT_BYTES) {
                throw invalid("Đề vượt quá dung lượng tối đa 2 MB");
            }
            return MAPPER.treeToValue(tree, AssessmentDocument.class);
        } catch (InvalidTypeIdException e) {
            throw invalid("Cấu trúc đề không hợp lệ: không lồng nhóm trong nhóm và mỗi nút phải có kind GROUP hoặc ITEM");
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException e) {
            throw invalid("Cấu trúc đề không hợp lệ");
        }
    }

    public static String write(AssessmentDocument doc) {
        try {
            return MAPPER.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Không ghi được nội dung đề");
        }
    }

    public static JsonNode toTree(Object value) {
        return MAPPER.valueToTree(value);
    }

    public static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Không ghi được dữ liệu");
        }
    }

    public static JsonNode readTree(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Dữ liệu đã lưu không đọc được");
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Dữ liệu đã lưu không đọc được");
        }
    }

    /** SHA-256 (hex) of the canonical form: all object keys sorted recursively, compact JSON. */
    public static String canonicalHash(AssessmentDocument doc) {
        return canonicalHash((Object) doc);
    }

    public static String canonicalHash(Object value) {
        try {
            String canonical = MAPPER.writeValueAsString(sortKeys(MAPPER.valueToTree(value)));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Không tính được mã băm của đề");
        }
    }

    static JsonNode sortKeys(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (String name : names) out.set(name, sortKeys(node.get(name)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) out.add(sortKeys(it.next()));
            return out;
        }
        return node;
    }

    private static AppException invalid(String message) {
        return new AppException(ErrorCode.BAD_REQUEST, message);
    }

    /** Small helper for graders / mappers: text of a field or null. */
    public static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    public static Map<String, Object> asMap(JsonNode node) {
        return MAPPER.convertValue(node, MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
    }
}
