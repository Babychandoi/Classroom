import type { Course, Section } from '../../types';
import { formatDong } from '../../api/format';

// Pure rules of the course wizard (no React): the step list, the form shape, number formatting, per-step validity and the
// review checklist. Kept apart so the locking / gating rules can be unit-tested without rendering anything.

export const TITLE_MAX = 100;
export const DESCRIPTION_MAX = 500;
/** Backend limit of a product's access period (CommerceService.MAX_PRODUCT_DURATION_DAYS). */
export const MAX_DURATION_DAYS = 3650;
/** Backend limit: price columns are DECIMAL(12,2) with a 9.999.999.999 ceiling. */
export const MAX_PRICE = 9_999_999_999;

export type AccessMode = 'FREE' | 'PURCHASE_REQUIRED';

export interface WizardStep {
  num: 1 | 2 | 3 | 4;
  label: string;
  desc: string;
}

export const WIZARD_STEPS: readonly WizardStep[] = [
  { num: 1, label: 'Thông tin cơ bản', desc: 'Đặt tên và mô tả khóa học' },
  { num: 2, label: 'Loại bán', desc: 'Chọn cách bán' },
  { num: 3, label: 'Nội dung bài học', desc: 'Thêm bài học & tài liệu' },
  { num: 4, label: 'Kiểm tra & xuất bản', desc: 'Xem trước & xuất bản' },
];
export const LAST_STEP = WIZARD_STEPS.length;

export interface CourseForm {
  title: string;
  description: string;
  coverImageUrl: string;
  accessMode: AccessMode;
  /** Digits with vi-VN thousands separators as typed ("299.000"). */
  priceText: string;
  /** Digits only, kept as text so the field can be emptied while typing. */
  durationText: string;
}

export const DEFAULT_DURATION_DAYS = 365;

export const emptyForm = (): CourseForm => ({
  title: '', description: '', coverImageUrl: '', accessMode: 'FREE', priceText: '', durationText: String(DEFAULT_DURATION_DAYS),
});

/** "299000" / "299.000đ" / "2 99 000" -> 299000 (0 when there are no digits). */
export function parsePrice(text: string): number {
  const digits = text.replace(/\D/g, '');
  if (!digits) return 0;
  return Math.min(Number(digits), MAX_PRICE * 10); // clamp absurd paste, validation reports the ceiling
}

/** Re-formats what was typed as whole dong with "." thousands separators ("2990" -> "2.990"). */
export function formatPriceInput(text: string): string {
  const value = parsePrice(text);
  return value > 0 ? new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 0 }).format(value) : '';
}

export function parseDuration(text: string): number {
  const digits = text.replace(/\D/g, '');
  return digits ? Number(digits) : 0;
}

export const priceFromProduct = (price: number): string => formatPriceInput(String(Math.round(price)));

export interface WizardData {
  form: CourseForm;
  /** The course as stored (null before the first save). */
  course: Course | null;
}

export const trimmed = (v: string) => v.trim();

// ---------------------------------------------------------------------------------------------------------------
// Per-step validation. Each returns the first thing that blocks moving on, or null.

export function titleError(form: CourseForm): string | null {
  if (!trimmed(form.title)) return 'Nhập tên khóa học để tiếp tục.';
  if (form.title.length > TITLE_MAX) return `Tên khóa học tối đa ${TITLE_MAX} ký tự.`;
  return null;
}

export function priceError(form: CourseForm): string | null {
  if (form.accessMode !== 'PURCHASE_REQUIRED') return null;
  const price = parsePrice(form.priceText);
  if (price <= 0) return 'Nhập giá bán lớn hơn 0đ cho khóa học trả phí.';
  if (price > MAX_PRICE) return `Giá bán tối đa ${formatDong(MAX_PRICE)}.`;
  return null;
}

export function durationError(form: CourseForm): string | null {
  if (form.accessMode !== 'PURCHASE_REQUIRED') return null;
  const days = parseDuration(form.durationText);
  if (days < 1 || days > MAX_DURATION_DAYS) return `Thời hạn truy cập phải từ 1 đến ${MAX_DURATION_DAYS} ngày.`;
  return null;
}

export function coverError(form: CourseForm): string | null {
  const url = trimmed(form.coverImageUrl);
  if (url && !/^(https?:\/\/|\/)\S+$/i.test(url)) return 'Ảnh bìa là đường dẫn bắt đầu bằng https:// (hoặc /).';
  return null;
}

export const step1Error = (form: CourseForm) => titleError(form) ?? coverError(form);
export const step2Error = (form: CourseForm) => priceError(form) ?? durationError(form);

/** Lessons a learner would actually see: not archived, inside a section that is not archived. */
export function activeLessonCount(sections: Section[] | undefined): number {
  return (sections ?? []).filter((s) => !s.archived).reduce((n, s) => n + (s.lessons ?? []).filter((l) => !l.archived).length, 0);
}
export function activeSectionCount(sections: Section[] | undefined): number {
  return (sections ?? []).filter((s) => !s.archived).length;
}

export function step3Error(course: Course | null): string | null {
  return activeLessonCount(course?.sections) > 0 ? null : 'Thêm ít nhất một bài học để tiếp tục.';
}

/** First blocking message of a step (null = the step is valid). Step 4 has no inputs of its own. */
export function stepError(step: number, data: WizardData): string | null {
  if (step === 1) return step1Error(data.form);
  if (step === 2) return step2Error(data.form);
  if (step === 3) return data.course ? step3Error(data.course) : 'Lưu thông tin cơ bản trước khi thêm bài học.';
  return null;
}

/**
 * A step is open once every step before it is valid; steps 3 and 4 also need the course to exist on the server (lessons are
 * stored under it). Step 1 is always open.
 */
export function isStepLocked(step: number, data: WizardData): boolean {
  if (step <= 1) return false;
  for (let k = 1; k < step; k++) if (stepError(k, data)) return true;
  if (step >= 3 && !data.course) return true;
  return false;
}

/** Highest step that is not locked. */
export function highestOpenStep(data: WizardData): number {
  let best = 1;
  for (let n = 2; n <= LAST_STEP; n++) if (!isStepLocked(n, data)) best = n;
  return best;
}

export const clampStep = (n: number) => Math.max(1, Math.min(LAST_STEP, Math.trunc(n) || 1));

// ---------------------------------------------------------------------------------------------------------------
// Review checklist (step 4).

export interface CheckItem {
  key: string;
  ok: boolean;
  /** A failing blocking item stops "Xuất bản"; a failing advisory item is only a suggestion. */
  blocking: boolean;
  label: string;
  /** What to do when it is not ok. */
  fix?: string;
  step: number;
}

export interface ChecklistContext extends WizardData {
  /** The linked product has been created on the server (needed to sell). */
  productSaved: boolean;
  /** Any unsaved edit in steps 1-2. */
  dirty: boolean;
}

export function buildChecklist(ctx: ChecklistContext): CheckItem[] {
  const { form, course } = ctx;
  const lessons = activeLessonCount(course?.sections);
  const paid = form.accessMode === 'PURCHASE_REQUIRED';
  const items: CheckItem[] = [
    {
      key: 'title', ok: !titleError(form), blocking: true, step: 1,
      label: 'Đã đặt tên khóa học', fix: 'Nhập tên khóa học ở bước Thông tin cơ bản.',
    },
    {
      key: 'description', ok: !!trimmed(form.description), blocking: false, step: 1,
      label: 'Đã có mô tả khóa học', fix: 'Nên viết vài dòng để học viên biết họ học được gì.',
    },
    {
      key: 'cover', ok: !!trimmed(form.coverImageUrl), blocking: false, step: 1,
      label: 'Đã có ảnh bìa', fix: 'Nên thêm ảnh bìa để thẻ khóa học nổi bật hơn.',
    },
    {
      key: 'lessons', ok: lessons > 0, blocking: true, step: 3,
      label: lessons > 0 ? `Đã có ${lessons} bài học` : 'Đã có ít nhất 1 bài học', fix: 'Thêm một danh mục và ít nhất một bài học ở bước Nội dung bài học.',
    },
  ];
  if (paid) {
    items.push({
      key: 'price', ok: !step2Error(form), blocking: true, step: 2,
      label: 'Đã đặt giá và thời hạn truy cập', fix: step2Error(form) ?? undefined,
    });
    items.push({
      key: 'product', ok: ctx.productSaved && !ctx.dirty, blocking: true, step: 2,
      label: 'Đã lưu sản phẩm bán khóa học', fix: 'Bấm "Lưu nháp" để lưu giá và tạo sản phẩm bán khóa học.',
    });
  }
  return items;
}

export const blockingMissing = (items: CheckItem[]) => items.filter((i) => i.blocking && !i.ok);
export const advisoryMissing = (items: CheckItem[]) => items.filter((i) => !i.blocking && !i.ok);

export const formFromCourse = (course: Course, product?: { price: number; durationDays: number } | null): CourseForm => ({
  title: course.title ?? '',
  description: course.description ?? '',
  coverImageUrl: course.coverImageUrl ?? '',
  accessMode: course.accessMode === 'PURCHASE_REQUIRED' ? 'PURCHASE_REQUIRED' : 'FREE',
  priceText: product ? priceFromProduct(product.price) : '',
  durationText: product ? String(product.durationDays) : String(DEFAULT_DURATION_DAYS),
});

export const sameBasic = (a: CourseForm, b: CourseForm) =>
  a.title === b.title && a.description === b.description && a.coverImageUrl === b.coverImageUrl;

export const sameSelling = (a: CourseForm, b: CourseForm) =>
  a.accessMode === b.accessMode
  && (a.accessMode === 'FREE' || (parsePrice(a.priceText) === parsePrice(b.priceText) && parseDuration(a.durationText) === parseDuration(b.durationText)));

/** "299.000đ / 365 ngày" for the paid summary lines. */
export function priceSummary(form: CourseForm): string {
  const price = parsePrice(form.priceText);
  const days = parseDuration(form.durationText);
  if (price <= 0) return 'Chưa nhập giá';
  return `${formatDong(price)} / ${days > 0 ? `${days} ngày` : 'chưa có thời hạn'}`;
}
