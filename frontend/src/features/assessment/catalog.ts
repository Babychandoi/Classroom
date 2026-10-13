import type { AssessmentKind, GradingMode, ItemTypeCode, QuestionTypeCode, TypeMeta } from './types';

/**
 * Static mirror of the backend `QuestionType` enum (28 interaction templates, spec §III / prototype 13-qb-data-types.js).
 * The server also serves this list in `GET /assessment-catalog`; the frontend keeps a copy for icons and offline rendering.
 * All 28 templates are available; `phase` records their original P0–P2 delivery group.
 */
type Row = [QuestionTypeCode, string, string, string, TypeMeta['family'], TypeMeta['phase'], GradingMode | null, boolean?];

const ROWS: Row[] = [
  ['Q01', 'SINGLE_CHOICE', 'Chọn 1 đáp án', 'A/B/C/D hoặc số lựa chọn khác', 'A', 'P0', 'AUTO'],
  ['Q02', 'MULTIPLE_CHOICE', 'Chọn nhiều đáp án', 'Chấm một phần tùy cấu hình', 'A', 'P0', 'AUTO'],
  ['Q03', 'TRUE_FALSE', 'Đúng / Sai', 'Một nhận định', 'A', 'P0', 'AUTO'],
  ['Q04', 'TRUE_FALSE_MATRIX', 'Đúng/Sai nhiều ý', 'Các ý a/b/c/d chấm độc lập', 'A', 'P0', 'AUTO'],
  ['Q05', 'MATCHING', 'Ghép cặp', 'Hai cột: từ–nghĩa, sự kiện–mốc', 'A', 'P0', 'AUTO'],
  ['Q06', 'ORDERING', 'Sắp xếp thứ tự', 'Bước giải, sự kiện, đoạn văn', 'A', 'P0', 'AUTO'],
  ['Q07', 'FILL_BLANK', 'Điền chỗ trống', 'Gõ vào một hoặc nhiều ô trống', 'A', 'P0', 'AUTO'],
  ['Q08', 'DRAG_WORDS_TO_BLANKS', 'Kéo từ vào chỗ trống', 'Ngân hàng từ có từ gây nhiễu', 'A', 'P0', 'AUTO'],
  ['Q09', 'INLINE_DROPDOWN', 'Chọn từ trong đoạn', 'Danh sách chọn ngay trong câu', 'A', 'P0', 'AUTO'],
  ['Q10', 'SHORT_TEXT', 'Trả lời ngắn', 'Từ, cụm từ, câu ngắn', 'A', 'P0', 'AUTO'],
  ['Q11', 'NUMERIC', 'Nhập số', 'Đơn vị, sai số, khoảng giá trị', 'A', 'P0', 'AUTO'],
  ['Q12', 'ESSAY', 'Tự luận', 'Đoạn văn, bài giải, nghị luận', 'A', 'P0', 'MANUAL'],
  ['Q13', 'COMPREHENSION_GROUP', 'Nhóm câu hỏi · ngữ liệu chung', 'Đoạn đọc / tư liệu / bảng + N câu con', 'B', 'P0', null, true],
  ['Q14', 'IMAGE_HOTSPOT', 'Chọn vùng trên ảnh', 'Ảnh, sơ đồ, bản đồ', 'B', 'P1', 'AUTO'],
  ['Q15', 'IMAGE_LABEL_DRAG', 'Kéo nhãn vào hình', 'Bộ phận, vị trí trên hình', 'B', 'P1', 'AUTO'],
  ['Q16', 'CATEGORIZATION', 'Phân loại', 'Kéo thẻ vào nhóm', 'B', 'P1', 'AUTO'],
  ['Q17', 'TABLE_COMPLETION', 'Hoàn thành bảng', 'Điền các ô còn trống', 'B', 'P1', 'AUTO'],
  ['Q18', 'GRAPH_CHART', 'Đồ thị / biểu đồ', 'Đọc, hoàn thiện hoặc vẽ', 'B', 'P1', 'HYBRID'],
  ['Q19', 'MATH_EXPRESSION', 'Biểu thức / công thức', 'Kiểm tra tương đương an toàn', 'B', 'P1', 'AUTO'],
  ['Q20', 'MULTI_STEP_SOLUTION', 'Bài nhiều bước', 'Mỗi bước có điểm riêng', 'B', 'P1', 'HYBRID'],
  ['Q21', 'LISTENING_TASK', 'Nhóm bài nghe', 'Audio chung cho 1 hoặc N câu con', 'C', 'P0', null, true],
  ['Q22', 'SPEAKING_RECORDING', 'Ghi âm nói', 'Học sinh ghi âm, chấm bằng rubric', 'C', 'P0', 'MANUAL'],
  ['Q23', 'DRAW_HANDWRITING', 'Vẽ / viết tay', 'Canvas vẽ và chú thích', 'C', 'P2', 'MANUAL'],
  ['Q24', 'FILE_SUBMISSION', 'Nộp tệp', 'Ảnh, PDF, bảng tính, sản phẩm số', 'C', 'P2', 'MANUAL'],
  ['Q25', 'VIDEO_SUBMISSION', 'Nộp video', 'Quay hoặc tải video thực hành', 'C', 'P2', 'MANUAL'],
  ['Q26', 'CODE_EDITOR_TASK', 'Viết code', 'Chạy sandbox, chấm test case', 'C', 'P2', 'AUTO'],
  ['Q27', 'SIMULATION_TASK', 'Mô phỏng / thí nghiệm', 'Tương tác rồi nộp kết quả', 'C', 'P2', 'HYBRID'],
  ['Q28', 'PROJECT_RUBRIC', 'Dự án có rubric', 'Minh chứng, rubric, nhận xét', 'C', 'P2', 'PASS_FAIL'],
];

export const TYPE_CATALOG: TypeMeta[] = ROWS.map(([code, key, name, description, family, phase, defaultMode, container]) => ({
  code, key, name, description, family, phase, defaultMode, container: !!container, available: true,
}));

const BY_CODE = new Map(TYPE_CATALOG.map((t) => [t.code, t]));

export function typeMeta(code: string): TypeMeta {
  const meta = BY_CODE.get(code as QuestionTypeCode);
  if (meta) return meta;
  return { code: code as QuestionTypeCode, key: code, name: code, description: '', family: 'A', phase: 'P2', defaultMode: null, container: false, available: false };
}

export const TYPE_GROUPS: { family: TypeMeta['family']; label: string }[] = [
  { family: 'A', label: 'Cơ bản' },
  { family: 'B', label: 'Tương tác nâng cao' },
  { family: 'C', label: 'Audio · thực hành · đa phương tiện' },
];

/** Item (non-container) types the R1 builder and grader support. */
export const ITEM_TYPES: ItemTypeCode[] = ['Q01', 'Q02', 'Q03', 'Q04', 'Q05', 'Q06', 'Q07', 'Q08', 'Q09', 'Q10', 'Q11', 'Q12', 'Q14', 'Q15', 'Q16', 'Q17', 'Q18', 'Q19', 'Q20', 'Q22', 'Q23', 'Q24', 'Q25', 'Q26', 'Q27', 'Q28'];
export const isItemType = (code: string): code is ItemTypeCode => (ITEM_TYPES as string[]).includes(code);
export const isGroupType = (code: string): code is 'Q13' | 'Q21' => code === 'Q13' || code === 'Q21';

/** Types that can never be auto-graded (no answer key). */
export const MANUAL_ONLY_TYPES: ItemTypeCode[] = ['Q12', 'Q22', 'Q23', 'Q24', 'Q25', 'Q28'];
/** Types for which "chấm một phần" makes sense. */
export const PARTIAL_TYPES: ItemTypeCode[] = ['Q02', 'Q04', 'Q05', 'Q06', 'Q07', 'Q08', 'Q09', 'Q15', 'Q16', 'Q17', 'Q18', 'Q20', 'Q26', 'Q27'];
/** Types matched as text (normalization settings apply). */
export const NORMALIZED_TYPES: ItemTypeCode[] = ['Q07', 'Q08', 'Q10'];
/** Types whose prompt carries `___` placeholders. */
export const BLANK_TYPES: ItemTypeCode[] = ['Q07', 'Q08', 'Q09'];

export const KIND_LABELS: Record<AssessmentKind, string> = {
  HOMEWORK: 'Bài tập',
  QUIZ: 'Kiểm tra',
  REVIEW: 'Ôn tập',
  WORKSHEET: 'Phiếu học tập',
  PRACTICE: 'Thực hành',
};

export const KIND_HINTS: Record<AssessmentKind, string> = {
  HOMEWORK: 'Luyện tập về nhà',
  QUIZ: 'Có giờ, chấm điểm',
  REVIEW: 'Không tính điểm',
  WORKSHEET: 'Dùng trên lớp',
  PRACTICE: 'Nộp sản phẩm, rubric',
};

export const KINDS: AssessmentKind[] = ['HOMEWORK', 'QUIZ', 'REVIEW', 'WORKSHEET', 'PRACTICE'];

export const MODE_LABELS: Record<GradingMode, string> = {
  AUTO: 'Tự chấm',
  MANUAL: 'Chấm tay',
  HYBRID: 'Kết hợp',
  PASS_FAIL: 'Đạt/Chưa đạt',
};

export const DIFFICULTY_LABELS = { NB: 'Nhận biết', TH: 'Thông hiểu', VD: 'Vận dụng', VDC: 'VD cao' } as const;

export const LEVELS: { code: 'TH' | 'THCS' | 'THPT'; name: string; grades: number[] }[] = [
  { code: 'TH', name: 'Tiểu học', grades: [1, 2, 3, 4, 5] },
  { code: 'THCS', name: 'Trung học cơ sở', grades: [6, 7, 8, 9] },
  { code: 'THPT', name: 'Trung học phổ thông', grades: [10, 11, 12] },
];

export type Skill = 'listening' | 'reading' | 'speaking' | 'writing' | 'gv' | 'focus';
export const SKILL_LABELS: Record<Skill, string> = {
  listening: 'Nghe',
  reading: 'Đọc',
  speaking: 'Nói',
  writing: 'Viết',
  gv: 'Ngữ pháp · từ vựng',
  focus: 'Tổng hợp',
};
