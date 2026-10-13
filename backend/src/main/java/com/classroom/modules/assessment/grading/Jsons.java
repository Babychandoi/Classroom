package com.classroom.modules.assessment.grading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/** Small null-safe JSON readers shared by the graders, the validator and the learner mapper. */
public final class Jsons {
    public static final JsonNodeFactory F = JsonNodeFactory.instance;

    private Jsons() {}

    public static boolean present(JsonNode n) {
        return n != null && !n.isNull() && !n.isMissingNode();
    }

    public static String text(JsonNode node, String field) {
        if (!present(node)) return null;
        JsonNode v = node.get(field);
        return present(v) && v.isValueNode() ? v.asText() : null;
    }

    /** Elements of an array field as strings (non-text elements skipped). */
    public static List<String> strings(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        if (!present(node)) return out;
        JsonNode arr = node.get(field);
        if (arr != null && arr.isArray()) arr.forEach(e -> { if (e.isValueNode() && !e.isNull()) out.add(e.asText()); });
        return out;
    }

    /** An array field of objects. */
    public static List<JsonNode> objects(JsonNode node, String field) {
        List<JsonNode> out = new ArrayList<>();
        if (!present(node)) return out;
        JsonNode arr = node.get(field);
        if (arr != null && arr.isArray()) arr.forEach(e -> { if (e.isObject()) out.add(e); });
        return out;
    }

    /** The {@code id}s of an array field of objects. */
    public static List<String> ids(JsonNode node, String field) {
        return objects(node, field).stream().map(o -> text(o, "id")).filter(Objects::nonNull).toList();
    }

    /** An object field read as a map of text values (non-text values skipped). */
    public static Map<String, String> textMap(JsonNode node, String field) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!present(node)) return out;
        JsonNode obj = node.get(field);
        if (obj != null && obj.isObject()) obj.fields().forEachRemaining(e -> {
            if (e.getValue().isValueNode() && !e.getValue().isNull()) out.put(e.getKey(), e.getValue().asText());
        });
        return out;
    }

    /** An object field read as a map of booleans (non-boolean values skipped). */
    public static Map<String, Boolean> boolMap(JsonNode node, String field) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (!present(node)) return out;
        JsonNode obj = node.get(field);
        if (obj != null && obj.isObject()) obj.fields().forEachRemaining(e -> {
            if (e.getValue().isBoolean()) out.put(e.getKey(), e.getValue().booleanValue());
        });
        return out;
    }

    /** An object field read as a map of string lists (Q07 accepted variants); a single string counts as a one-element list. */
    public static Map<String, List<String>> listMap(JsonNode node, String field) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!present(node)) return out;
        JsonNode obj = node.get(field);
        if (obj != null && obj.isObject()) obj.fields().forEachRemaining(e -> {
            List<String> values = new ArrayList<>();
            if (e.getValue().isArray()) e.getValue().forEach(v -> { if (v.isValueNode() && !v.isNull()) values.add(v.asText()); });
            else if (e.getValue().isValueNode() && !e.getValue().isNull()) values.add(e.getValue().asText());
            out.put(e.getKey(), values);
        });
        return out;
    }

    public static ObjectNode obj() {
        return F.objectNode();
    }

    public static ArrayNode arr(Collection<String> values) {
        ArrayNode a = F.arrayNode();
        values.forEach(a::add);
        return a;
    }

    public static ObjectNode textObject(Map<String, String> values) {
        ObjectNode o = F.objectNode();
        values.forEach(o::put);
        return o;
    }
}
