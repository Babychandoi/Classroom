package com.classroom.modules.assessment.doc;

import com.classroom.modules.assessment.grading.ScoreMath;

import java.math.BigDecimal;
import java.util.*;

/**
 * D-34: derived facts about a document in its canonical (authoring) order: every item with its section / group, labels ("Câu 1", a group
 * "Câu 1–4" or "Câu 5" for one child), points per node / section / total (BigDecimal, 2 decimals) and the skills tags.
 * Q04 is one item, whatever its number of statements.
 */
public final class DocumentIndex {

    /** One item with where it lives. {@code group} is null for a standalone item. */
    public record ItemRef(ItemNode item, GroupNode group, SectionNode section) {}

    private final AssessmentDocument doc;
    private final List<ItemRef> items = new ArrayList<>();
    private final Map<String, ItemRef> itemById = new LinkedHashMap<>();
    private final Map<String, GroupNode> groupById = new LinkedHashMap<>();
    private final Map<String, BigDecimal> points = new LinkedHashMap<>();
    private final Map<String, String> labels;
    private final BigDecimal total;

    private DocumentIndex(AssessmentDocument doc) {
        this.doc = doc;
        BigDecimal sum = BigDecimal.ZERO;
        for (SectionNode section : doc.sections()) {
            BigDecimal sectionSum = BigDecimal.ZERO;
            for (Node node : section.nodes()) {
                if (node instanceof GroupNode group) {
                    groupById.put(group.id(), group);
                    BigDecimal groupSum = BigDecimal.ZERO;
                    for (ItemNode child : group.children()) {
                        add(new ItemRef(child, group, section));
                        groupSum = groupSum.add(ScoreMath.points(child.points()));
                    }
                    points.put(group.id(), ScoreMath.round(groupSum));
                    sectionSum = sectionSum.add(groupSum);
                } else if (node instanceof ItemNode item) {
                    add(new ItemRef(item, null, section));
                    sectionSum = sectionSum.add(ScoreMath.points(item.points()));
                }
            }
            points.put(section.id(), ScoreMath.round(sectionSum));
            sum = sum.add(sectionSum);
        }
        this.total = ScoreMath.round(sum);
        this.labels = computeLabels(doc, s -> s.nodes().stream().map(Node::id).toList(), g -> g.children().stream().map(ItemNode::id).toList());
    }

    private void add(ItemRef ref) {
        items.add(ref);
        itemById.put(ref.item().id(), ref);
        points.put(ref.item().id(), ScoreMath.round(ScoreMath.points(ref.item().points())));
    }

    public static DocumentIndex of(AssessmentDocument doc) {
        return new DocumentIndex(doc);
    }

    public AssessmentDocument document() { return doc; }
    public List<ItemRef> items() { return items; }
    public Optional<ItemRef> item(String itemId) { return Optional.ofNullable(itemById.get(itemId)); }
    public Optional<GroupNode> group(String groupId) { return Optional.ofNullable(groupById.get(groupId)); }
    public Collection<GroupNode> groups() { return groupById.values(); }
    public int itemCount() { return items.size(); }
    public BigDecimal total() { return total; }
    public BigDecimal points(String nodeId) { return points.getOrDefault(nodeId, BigDecimal.ZERO.setScale(2)); }
    public Map<String, String> labels() { return labels; }
    public String label(String nodeId) { return labels.getOrDefault(nodeId, ""); }

    /** Section ids in order. */
    public List<String> sectionIds() {
        return doc.sections().stream().map(SectionNode::id).toList();
    }

    /** listening (Q21), reading (Q13), writing (Q12), speaking (Q22); "gv" when none of them. */
    public List<String> skills() {
        boolean listening = groupById.values().stream().anyMatch(g -> "Q21".equals(g.groupType()));
        boolean reading = groupById.values().stream().anyMatch(g -> "Q13".equals(g.groupType()));
        boolean writing = items.stream().anyMatch(r -> "Q12".equals(r.item().type()));
        boolean speaking = items.stream().anyMatch(r -> "Q22".equals(r.item().type()));
        List<String> out = new ArrayList<>();
        if (listening) out.add("listening");
        if (reading) out.add("reading");
        if (writing) out.add("writing");
        if (speaking) out.add("speaking");
        if (out.isEmpty()) out.add("gv");
        return out;
    }

    /**
     * Labels for a display order: items are numbered 1..N in the order given by {@code sectionOrder} / {@code childOrder}; a group gets
     * "Câu a–b" (en dash), or "Câu a" when it has one child; an empty group gets "Nhóm".
     */
    public static Map<String, String> computeLabels(AssessmentDocument doc,
                                                    java.util.function.Function<SectionNode, List<String>> sectionOrder,
                                                    java.util.function.Function<GroupNode, List<String>> childOrder) {
        Map<String, String> out = new LinkedHashMap<>();
        int n = 0;
        for (SectionNode section : doc.sections()) {
            Map<String, Node> nodes = new HashMap<>();
            section.nodes().forEach(nd -> nodes.put(nd.id(), nd));
            for (String nodeId : sectionOrder.apply(section)) {
                Node node = nodes.get(nodeId);
                if (node instanceof GroupNode group) {
                    int first = n + 1;
                    for (String childId : childOrder.apply(group)) {
                        n++;
                        out.put(childId, "Câu " + n);
                    }
                    if (n >= first) {
                        out.put(group.id(), n == first ? "Câu " + first : "Câu " + first + "–" + n);
                    } else {
                        out.put(group.id(), "Nhóm");
                    }
                } else if (node instanceof ItemNode) {
                    n++;
                    out.put(node.id(), "Câu " + n);
                }
            }
        }
        return out;
    }
}
