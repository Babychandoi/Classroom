package com.classroom.modules.assessment.catalog;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * D-34: the 28 interaction templates of the Question Builder (spec §IV, prototype {@code 13-qb-data-types.js}). All P0–P2 interactions are available; P3 adds interchange and reports. Q13 / Q21 are containers (a group holding
 * child items), never gradable themselves.
 */
public enum QuestionType {
    Q01("SINGLE_CHOICE", "Chọn 1 đáp án", "A/B/C/D hoặc số lựa chọn khác", "A", "P0", "AUTO", false, "checkbox"),
    Q02("MULTIPLE_CHOICE", "Chọn nhiều đáp án", "Chấm một phần tùy cấu hình", "A", "P0", "AUTO", false, "checkbox"),
    Q03("TRUE_FALSE", "Đúng / Sai", "Một nhận định", "A", "P0", "AUTO", false, "check"),
    Q04("TRUE_FALSE_MATRIX", "Đúng/Sai nhiều ý", "Các ý a/b/c/d chấm độc lập", "A", "P0", "AUTO", false, "grid"),
    Q05("MATCHING", "Ghép cặp", "Hai cột: từ–nghĩa, sự kiện–mốc", "A", "P0", "AUTO", false, "link"),
    Q06("ORDERING", "Sắp xếp thứ tự", "Bước giải, sự kiện, đoạn văn", "A", "P0", "AUTO", false, "stats"),
    Q07("FILL_BLANK", "Điền chỗ trống", "Gõ vào một hoặc nhiều ô trống", "A", "P0", "AUTO", false, "edit"),
    Q08("DRAG_WORDS_TO_BLANKS", "Kéo từ vào chỗ trống", "Ngân hàng từ có từ gây nhiễu", "A", "P0", "AUTO", false, "tag"),
    Q09("INLINE_DROPDOWN", "Chọn từ trong đoạn", "Danh sách chọn ngay trong câu", "A", "P0", "AUTO", false, "down"),
    Q10("SHORT_TEXT", "Trả lời ngắn", "Từ, cụm từ, câu ngắn", "A", "P0", "AUTO", false, "note"),
    Q11("NUMERIC", "Nhập số", "Đơn vị, sai số, khoảng giá trị", "A", "P0", "AUTO", false, "stats"),
    Q12("ESSAY", "Tự luận", "Đoạn văn, bài giải, nghị luận", "A", "P0", "MANUAL", false, "page"),
    Q13("COMPREHENSION_GROUP", "Nhóm câu hỏi · ngữ liệu chung", "Đoạn đọc / tư liệu / bảng + N câu con", "B", "P0", null, true, "docs"),
    Q14("IMAGE_HOTSPOT", "Chọn vùng trên ảnh", "Ảnh, sơ đồ, bản đồ", "B", "P1", "AUTO", false, "location"),
    Q15("IMAGE_LABEL_DRAG", "Kéo nhãn vào hình", "Bộ phận, vị trí trên hình", "B", "P1", "AUTO", false, "image"),
    Q16("CATEGORIZATION", "Phân loại", "Kéo thẻ vào nhóm", "B", "P1", "AUTO", false, "box"),
    Q17("TABLE_COMPLETION", "Hoàn thành bảng", "Điền các ô còn trống", "B", "P1", "AUTO", false, "grid"),
    Q18("GRAPH_CHART", "Đồ thị / biểu đồ", "Đọc, hoàn thiện hoặc vẽ", "B", "P1", "HYBRID", false, "trend"),
    Q19("MATH_EXPRESSION", "Biểu thức / công thức", "Kiểm tra tương đương an toàn", "B", "P1", "AUTO", false, "fmt"),
    Q20("MULTI_STEP_SOLUTION", "Bài nhiều bước", "Mỗi bước có điểm riêng", "B", "P1", "HYBRID", false, "report"),
    Q21("LISTENING_TASK", "Nhóm bài nghe", "Audio chung cho 1 hoặc N câu con", "C", "P0", null, true, "play"),
    Q22("SPEAKING_RECORDING", "Ghi âm nói", "Học sinh ghi âm, chấm bằng rubric", "C", "P0", "MANUAL", false, "mic"),
    Q23("DRAW_HANDWRITING", "Vẽ / viết tay", "Canvas vẽ và chú thích", "C", "P2", "MANUAL", false, "edit"),
    Q24("FILE_SUBMISSION", "Nộp tệp", "Ảnh, PDF, bảng tính, sản phẩm số", "C", "P2", "MANUAL", false, "attach"),
    Q25("VIDEO_SUBMISSION", "Nộp video", "Quay hoặc tải video thực hành", "C", "P2", "MANUAL", false, "camera"),
    Q26("CODE_EDITOR_TASK", "Viết code", "Chạy sandbox, chấm test case", "C", "P2", "AUTO", false, "fmt"),
    Q27("SIMULATION_TASK", "Mô phỏng / thí nghiệm", "Tương tác rồi nộp kết quả", "C", "P2", "HYBRID", false, "bolt"),
    Q28("PROJECT_RUBRIC", "Dự án có rubric", "Minh chứng, rubric, nhận xét", "C", "P2", "PASS_FAIL", false, "trophy");

    /** The item (non-container) types the R1 builder, validator and grading engine support. */
    public static final Set<String> ITEM_CODES_R1 = Set.of("Q01", "Q02", "Q03", "Q04", "Q05", "Q06", "Q07", "Q08", "Q09", "Q10", "Q11", "Q12", "Q14", "Q15", "Q16", "Q17", "Q18", "Q19", "Q20", "Q22", "Q23", "Q24", "Q25", "Q26", "Q27", "Q28");
    /** Container (group) types. */
    public static final Set<String> GROUP_CODES = Set.of("Q13", "Q21");

    private final String key;
    private final String nameVi;
    private final String descVi;
    private final String family;
    private final String phase;
    private final String defaultMode;
    private final boolean container;
    private final String icon;

    QuestionType(String key, String nameVi, String descVi, String family, String phase, String defaultMode, boolean container, String icon) {
        this.key = key;
        this.nameVi = nameVi;
        this.descVi = descVi;
        this.family = family;
        this.phase = phase;
        this.defaultMode = defaultMode;
        this.container = container;
        this.icon = icon;
    }

    public String code() { return name(); }
    public String key() { return key; }
    public String nameVi() { return nameVi; }
    public String descVi() { return descVi; }
    public String family() { return family; }
    public String phase() { return phase; }
    public String defaultMode() { return defaultMode; }
    public boolean container() { return container; }
    public String icon() { return icon; }
    public boolean available() { return true; }

    public static Optional<QuestionType> of(String code) {
        if (code == null) return Optional.empty();
        return Arrays.stream(values()).filter(t -> t.name().equals(code)).findFirst();
    }
}
