import React, { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useOutletContext, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft, ArrowRight, Check, CircleAlert, Coins, Eye, FolderPlus, Gift, Pencil, Rocket, Save, TriangleAlert, Lock } from 'lucide-react';
import { Classroom, Course, Product } from '../../types';
import { api } from '../../api/client';
import { formatDong } from '../../api/format';
import { hasCoursePermission, hasStudioPermission } from '../../api/permissions';
import { LoadingSpinner, ErrorBanner, StatusBadge } from '../../components/UIStates';
import { Modal } from '../../components/Modal';
import { Badge, Button, CoverImage, FilterChip, Input, Textarea, buttonClass } from '../../components/ui';
import { ModalActions, Notice, radioCardClass } from './studioUi';
import { ContentStudioHandle, CourseContentStudio } from './CourseContentStudio';
import { CourseCardFace, CourseCardPreview, CourseDetailPreview } from './CoursePreviews';
import { FormCard, LeaveConfirm, WizField, WizardFooter, WizardStepper, useLeaveGuard } from './wizardUi';
import {
  CourseForm, DESCRIPTION_MAX, LAST_STEP, MAX_DURATION_DAYS, TITLE_MAX, WIZARD_STEPS,
  activeLessonCount, activeSectionCount, advisoryMissing, blockingMissing, buildChecklist, clampStep, coverError, durationError,
  emptyForm, formFromCourse, formatPriceInput, highestOpenStep, isStepLocked, parseDuration, parsePrice, priceError, priceSummary,
  sameBasic, sameSelling, step1Error, step2Error, stepError, titleError,
} from './courseWizardModel';

const cx = (...parts: Array<string | false | null | undefined>) => parts.filter(Boolean).join(' ');

const DURATION_PRESETS = [
  { days: 30, label: '30 ngày' },
  { days: 90, label: '90 ngày' },
  { days: 180, label: '180 ngày' },
  { days: 365, label: '1 năm' },
  { days: MAX_DURATION_DAYS, label: '10 năm (tối đa)' },
];

const stepStorageKey = (classId: string, courseId: string) => `studio.courseWizard.step.${classId}.${courseId}`;
const readStoredStep = (classId: string, courseId: string): number => {
  try { return clampStep(Number(localStorage.getItem(stepStorageKey(classId, courseId)))); } catch { return 1; }
};
const storeStep = (classId: string, courseId: string, step: number) => {
  try {
    localStorage.setItem(stepStorageKey(classId, courseId), String(Math.max(step, readStoredStep(classId, courseId))));
  } catch { /* storage unavailable: the furthest step is simply not remembered */ }
};

type StatusNote = { tone: 'success' | 'warn'; text: string };
type ProductRef = { id: string; status: string; price: number; durationDays: number };

const productStatusLabel = (status: string) =>
  status === 'PUBLISHED' ? 'Đang bán' : status === 'ARCHIVED' ? 'Đã gỡ bán' : 'Bản nháp';

export const StudioCourseWizard: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const { courseId: routeCourseId } = useParams<{ courseId?: string }>();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const classId = classroom.id;
  const coursesPath = `/studio/classes/${classId}/courses`;

  // ---- permissions -------------------------------------------------------------------------------------------------
  const canCreateCourse = hasStudioPermission(classroom, 'COURSE', 'CREATE');
  const canCreateProduct = hasStudioPermission(classroom, 'STORE', 'CREATE');
  const canEditProduct = hasStudioPermission(classroom, 'STORE', 'EDIT');
  const canPublishProduct = hasStudioPermission(classroom, 'STORE', 'PUBLISH');
  const canSeeProducts = canCreateProduct || canEditProduct || canPublishProduct || hasStudioPermission(classroom, 'STORE', 'VIEW');
  // A SUSPENDED or ARCHIVED class is read-only on the server; the page mirrors that instead of failing on every save.
  const classLocked = classroom.status === 'SUSPENDED' || classroom.status === 'ARCHIVED';

  // ---- state -------------------------------------------------------------------------------------------------------
  const [loading, setLoading] = useState(!!routeCourseId);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [course, setCourse] = useState<Course | null>(null);
  const [product, setProduct] = useState<ProductRef | null>(null);
  /** False when the linked product's price could not be read (no Shop permission): the price fields stay read-only. */
  const [productKnown, setProductKnown] = useState(true);
  const [form, setForm] = useState<CourseForm>(emptyForm);
  /** What the server has for steps 1-2: the dirty check compares the form against it. */
  const [saved, setSaved] = useState<CourseForm>(emptyForm);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [note, setNote] = useState<StatusNote | null>(null);
  const [touched, setTouched] = useState(false);
  const [cardPreview, setCardPreview] = useState(false);
  const [detailPreview, setDetailPreview] = useState(false);
  /** Which confirmation is open: leaving the published state, archiving, or deleting the course. */
  const [confirmKind, setConfirmKind] = useState<null | 'unpublish' | 'archive' | 'delete'>(null);
  const [zoneError, setZoneError] = useState<string | null>(null);
  const [lessonDirty, setLessonDirty] = useState(false);
  const studioRef = useRef<ContentStudioHandle>(null);

  /** The course id the page currently holds, so the /new -> /:id/edit move after the first save does not reload it. */
  const loadedIdRef = useRef<string | null>(null);
  /** Latest saved course, readable from async continuations that closed over an older render. */
  const courseRef = useRef<Course | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const firstRenderRef = useRef(true);

  const titleId = useId();
  const descId = useId();
  const coverId = useId();
  const priceId = useId();
  const durationId = useId();
  const sellGroup = useId();

  courseRef.current = course;
  const readOnly = classLocked || (!!course && course.status === 'ARCHIVED');
  const data = useMemo(() => ({ form, course }), [form, course]);
  const sections = course?.sections ?? [];
  const hasProduct = !!product || !!course?.productId;
  const isPublished = course?.status === 'PUBLISHED';
  const canEditThisCourse = course ? hasCoursePermission(classroom, 'COURSE', 'EDIT', course.id) : canCreateCourse;
  const canPublishThisCourse = !!course && hasCoursePermission(classroom, 'COURSE', 'PUBLISH', course.id);

  const basicDirty = !sameBasic(form, saved);
  const sellingDirty = !sameSelling(form, saved);
  const dirty = !readOnly && (basicDirty || sellingDirty);
  const guard = useLeaveGuard(dirty || lessonDirty);

  // ---- step handling -----------------------------------------------------------------------------------------------
  const requestedStep = clampStep(Number(searchParams.get('step')));
  const current = Math.min(requestedStep, highestOpenStep(data));
  const wide = current === 3;
  // Absolute paths on purpose: right after the first save the page moves from /new to /:id/edit, and a relative
  // "?step=n" from a stale render would send it back to /new.
  const setStep = useCallback((n: number, id?: string | null) => {
    const base = id ? `${coursesPath}/${id}/edit` : `${coursesPath}/new`;
    navigate(`${base}?step=${n}`, { replace: true });
  }, [coursesPath, navigate]);

  useEffect(() => {
    if (firstRenderRef.current) { firstRenderRef.current = false; return; }
    headingRef.current?.focus({ preventScroll: true });
    headingRef.current?.scrollIntoView?.({ block: 'start' });
  }, [current]);

  useEffect(() => {
    if (course) storeStep(classId, course.id, current);
  }, [classId, course, current]);

  // ---- loading -----------------------------------------------------------------------------------------------------
  const loadProduct = useCallback(async (c: Course): Promise<ProductRef | null> => {
    if (!c.productId) { setProductKnown(true); return null; }
    if (!canSeeProducts) { setProductKnown(false); return null; }
    try {
      const all = await api.get<Product[]>(`/classes/${classId}/studio/products`);
      const found = (all || []).find((p) => p.id === c.productId);
      if (!found) { setProductKnown(false); return null; }
      setProductKnown(true);
      return { id: found.id, status: found.status, price: found.price, durationDays: found.durationDays };
    } catch {
      setProductKnown(false);
      return null;
    }
  }, [canSeeProducts, classId]);

  useEffect(() => {
    if (!routeCourseId || loadedIdRef.current === routeCourseId) return;
    let cancelled = false;
    (async () => {
      setLoading(true);
      setLoadError(null);
      try {
        const detail = await api.get<Course>(`/courses/${routeCourseId}`);
        if (cancelled) return;
        if (detail.classId && detail.classId !== classId) throw new Error('Khóa học này không thuộc lớp hiện tại.');
        const linked = await loadProduct(detail);
        if (cancelled) return;
        loadedIdRef.current = detail.id;
        const next = formFromCourse(detail, linked);
        setCourse({ ...detail, sections: detail.sections ?? [] });
        setProduct(linked);
        setForm(next);
        setSaved(next);
        if (!searchParams.get('step')) setStep(readStoredStep(classId, detail.id), detail.id);
      } catch (err: any) {
        if (!cancelled) setLoadError(err.message || 'Không thể tải khóa học');
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [routeCourseId, classId]);

  /** Re-reads the course (curriculum, status, product link) without touching the form. */
  const reloadCourse = useCallback(async () => {
    if (!course) return;
    try {
      const detail = await api.get<Course>(`/courses/${course.id}`);
      setCourse({ ...detail, sections: detail.sections ?? [] });
    } catch (err: any) {
      setError(err.message || 'Không thể tải lại nội dung khóa học');
    }
  }, [course]);

  // ---- saving ------------------------------------------------------------------------------------------------------
  const patchForm = (patch: Partial<CourseForm>) => {
    setForm((f) => ({ ...f, ...patch }));
    setStatus(null);
    setError(null);
  };

  /**
   * Writes whatever is dirty in steps 1-2: first save creates the DRAFT (POST), later saves update it (PUT); a paid choice
   * creates / updates the linked product. Returns the ids on success, null when something blocked or failed (error shown).
   */
  const persist = async (): Promise<{ course: Course; product: ProductRef | null } | null> => {
    if (readOnly) return course ? { course, product } : null;
    // An open lesson with unsaved edits (step 3) is saved first so nothing typed there is lost.
    if (studioRef.current && !(await studioRef.current.flush())) return null;
    setSaving(true);
    setError(null);
    setTouched(true);
    try {
      let c = course;
      let p = product;
      const createdNow = !c;
      if (!c || basicDirty) {
        const invalid = step1Error(form);
        if (invalid) { setError(invalid); return null; }
        const payload = { title: form.title.trim(), description: form.description, coverImageUrl: form.coverImageUrl.trim() };
        if (!c) {
          const created = await api.post<Course>(`/classes/${classId}/courses`, { ...payload, accessMode: 'FREE' });
          c = { ...created, sections: [] };
        } else {
          await api.put(`/courses/${c.id}`, payload);
          c = { ...c, ...payload };
        }
        setCourse(c);
        setSaved((s) => ({ ...s, title: payload.title, description: payload.description, coverImageUrl: payload.coverImageUrl }));
        setForm((f) => (f.title === form.title && f.coverImageUrl === form.coverImageUrl ? { ...f, title: payload.title, coverImageUrl: payload.coverImageUrl } : f));
        if (p && canEditProduct) {
          // Keep the product's name / description in step with the course (best effort: the course itself is already saved).
          try { await api.put(`/products/${p.id}`, { title: payload.title, description: payload.description }); } catch { /* shown in Shop */ }
        }
      }
      if (sellingDirty && form.accessMode === 'PURCHASE_REQUIRED') {
        const invalid = step2Error(form);
        if (invalid) {
          // Only the selling step reports its own problems; from another step the selling choice just stays unsaved.
          if (current === 2) { setError(invalid); setSaving(false); return null; }
        } else {
          const price = parsePrice(form.priceText);
          const durationDays = parseDuration(form.durationText);
          const body = { title: form.title.trim(), description: form.description, price, durationDays };
          if (!p) {
            // The backend links the new product to the course (accessMode becomes PURCHASE_REQUIRED) in the same transaction.
            const made = await api.post<Product>(`/classes/${classId}/products`, { ...body, targetCourseId: c.id });
            p = { id: made.id, status: made.status || 'DRAFT', price, durationDays };
            c = { ...c, accessMode: 'PURCHASE_REQUIRED', productId: made.id };
            setCourse(c);
            setProductKnown(true);
          } else if (canEditProduct) {
            await api.put(`/products/${p.id}`, body);
            p = { ...p, price, durationDays };
          }
          setProduct(p);
          setSaved((s) => ({ ...s, accessMode: 'PURCHASE_REQUIRED', priceText: form.priceText, durationText: form.durationText }));
        }
      }
      courseRef.current = c;
      if (createdNow) {
        // Moves /courses/new to /courses/:id/edit on the same step; the page keeps its state (loadedIdRef).
        loadedIdRef.current = c.id;
        guard.withoutPrompt(() => navigate(`${coursesPath}/${c!.id}/edit?step=${current}`, { replace: true }));
      }
      setStatus(`Đã lưu lúc ${new Date().toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })}`);
      return { course: c, product: p };
    } catch (err: any) {
      setError(err.message || 'Lưu thất bại. Vui lòng thử lại.');
      return null;
    } finally {
      setSaving(false);
    }
  };

  const saveDraft = async () => { await persist(); };

  const goToStep = async (target: number) => {
    if (target === current || saving) return;
    setNote(null);
    if (!readOnly && studioRef.current && !(await studioRef.current.flush())) return;
    if (target > current) {
      const invalid = stepError(current, data);
      if (invalid) { setTouched(true); setError(invalid); return; }
      if (!readOnly) {
        const result = await persist();
        if (!result) return;
      }
    } else {
      setError(null);
    }
    setStep(target, courseRef.current?.id ?? course?.id ?? null);
  };

  const nextStep = () => goToStep(Math.min(LAST_STEP, current + 1));

  // ---- publishing --------------------------------------------------------------------------------------------------
  const checklist = buildChecklist({ form, course, productSaved: hasProduct, dirty });
  const blocking = blockingMissing(checklist);
  const advisory = advisoryMissing(checklist);

  const publish = async () => {
    if (!course || blocking.length > 0) return;
    setNote(null);
    const result = await persist();
    if (!result) return;
    setSaving(true);
    try {
      await api.post(`/courses/${result.course.id}/publish`);
    } catch (err: any) {
      setError(err.message || 'Không thể xuất bản khóa học');
      setSaving(false);
      return;
    }
    let productLeft: string | null = null;
    const paid = form.accessMode === 'PURCHASE_REQUIRED';
    let nextProduct = result.product;
    if (paid && result.product && result.product.status !== 'PUBLISHED') {
      if (canPublishProduct) {
        try {
          await api.post(`/products/${result.product.id}/publish`);
          nextProduct = { ...result.product, status: 'PUBLISHED' };
        } catch (err: any) {
          productLeft = err.message || 'Không thể xuất bản sản phẩm bán khóa học';
        }
      } else {
        productLeft = 'Bạn chưa có quyền xuất bản sản phẩm trong Shop. Hãy nhờ chủ lớp hoặc người có quyền đó bấm "Xuất bản" ở mục Shop & đơn hàng để học viên mua được khóa học.';
      }
    }
    setProduct(nextProduct);
    setCourse((c) => (c ? { ...c, status: 'PUBLISHED' } : c));
    await reloadCourse();
    setNote(productLeft
      ? { tone: 'warn', text: `Khóa học đã được xuất bản, nhưng sản phẩm bán khóa học chưa mở bán. ${productLeft}` }
      : { tone: 'success', text: paid ? 'Khóa học và sản phẩm bán khóa học đã được xuất bản.' : 'Khóa học đã được xuất bản. Thành viên của lớp có thể vào học ngay.' });
    setStatus(null);
    setSaving(false);
  };

  /** Archive (also what "Ngừng xuất bản" does), restore and delete; errors of the danger zone stay in the zone. */
  const runCourseAction = async (kind: 'unpublish' | 'archive' | 'delete' | 'restore') => {
    if (!course) return;
    setSaving(true);
    setError(null);
    setZoneError(null);
    try {
      if (kind === 'restore') {
        await api.post(`/courses/${course.id}/restore`);
        setConfirmKind(null);
        await reloadCourse();
        setNote({ tone: 'success', text: 'Đã khôi phục khóa học về bản nháp. Bạn có thể chỉnh sửa và xuất bản lại.' });
      } else {
        if (kind === 'delete') await api.delete(`/courses/${course.id}`);
        else await api.post(`/courses/${course.id}/archive`);
        setConfirmKind(null);
        guard.withoutPrompt(() => navigate(coursesPath));
      }
    } catch (err: any) {
      setConfirmKind(null);
      const message = err.message || 'Thao tác thất bại';
      if (kind === 'unpublish') setError(message); else setZoneError(message);
    } finally {
      setSaving(false);
    }
  };

  // ---- render guards -----------------------------------------------------------------------------------------------
  const blockedCard = (title: string, body: string) => (
    <div className="mx-auto w-full max-w-[560px] py-12">
      <div className="rounded-card border border-slate-200 bg-white p-8 text-center shadow-hairline">
        <h1 className="mb-1 text-h3-lg font-semibold text-slate-900">{title}</h1>
        <p className="mb-6 text-ui text-slate-600">{body}</p>
        <Link to={coursesPath} className={buttonClass('secondary', 'md')}>Về danh sách khóa học</Link>
      </div>
    </div>
  );

  if (routeCourseId && loading) return <LoadingSpinner message="Đang mở khóa học..." />;
  if (routeCourseId && loadError) {
    return (
      <div className="mx-auto w-full max-w-[760px]">
        <ErrorBanner message={loadError} onRetry={() => { loadedIdRef.current = null; navigate(0); }} />
        <Link to={coursesPath} className={buttonClass('secondary', 'md', 'mt-4')}>Về danh sách khóa học</Link>
      </div>
    );
  }
  if (!routeCourseId && !course && !canCreateCourse) {
    return blockedCard('Bạn chưa có quyền tạo khóa học', 'Hãy nhờ chủ lớp cấp quyền tạo khóa học (COURSE:CREATE) cho bạn.');
  }
  if (!routeCourseId && !course && classLocked) {
    return blockedCard('Không thể tạo khóa học lúc này', classroom.status === 'SUSPENDED'
      ? 'Lớp đang bị tạm khóa bởi quản trị nền tảng nên chỉ xem được, không tạo hoặc sửa nội dung.'
      : 'Lớp đã lưu trữ nên chỉ xem được. Hãy khôi phục lớp ở Cài đặt trước khi tạo khóa học.');
  }
  if (course && !canEditThisCourse) {
    return blockedCard('Bạn chưa có quyền sửa khóa học này', 'Hãy nhờ chủ lớp cấp quyền sửa khóa học (COURSE:EDIT) cho bạn.');
  }

  // ---- derived view data -------------------------------------------------------------------------------------------
  const paid = form.accessMode === 'PURCHASE_REQUIRED';
  const lessonCount = activeLessonCount(sections);
  const courseKey = course?.id ?? 'new';
  const titleShown = touched ? titleError(form) : null;
  const coverErr = coverError(form);
  const priceErr = touched ? priceError(form) : null;
  const durationErr = touched ? durationError(form) : null;
  const nextLabel = WIZARD_STEPS.find((s) => s.num === current + 1)?.label;
  const stepOf = (num: number) => ({ active: num === current, done: num < current, locked: isStepLocked(num, data) });
  const saveLabel = isPublished ? 'Lưu' : 'Lưu nháp';
  const sellLockedToPaid = hasProduct; // a course that already has a product cannot go back to free (no unlink in the API)
  const paidDisabledReason = !hasProduct && !canCreateProduct
    ? 'Bạn chưa có quyền tạo sản phẩm trong Shop (STORE:CREATE) nên chưa thể bán khóa học. Hãy nhờ chủ lớp cấp quyền, hoặc tạo khóa học miễn phí.'
    : null;
  const priceFieldsReadOnly = readOnly || (hasProduct && (!canEditProduct || !productKnown));

  // ---- step bodies -------------------------------------------------------------------------------------------------
  const previewColumn = (
    <aside aria-label="Xem trước" className="space-y-3 lg:sticky lg:top-36 lg:self-start">
      <p className="text-center text-caption font-semibold text-slate-600">Xem trước thẻ khóa học</p>
      <CourseCardFace form={form} lessons={lessonCount} courseId={courseKey} />
      <p className="text-center text-caption text-slate-500">Thẻ cập nhật ngay khi bạn nhập.</p>
    </aside>
  );

  const step1 = (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-6 lg:grid-cols-[minmax(0,1fr)_320px]">
      <FormCard title="Thông tin cơ bản" subtitle="Những thông tin này hiển thị trên thẻ khóa học và đầu trang khóa học." headingRef={headingRef}>
        <WizField
          label="Tên khóa học" htmlFor={titleId} required counter={`${form.title.length}/${TITLE_MAX}`}
          hint="Tên ngắn gọn, nói rõ học viên sẽ học gì." error={titleShown}
        >
          <Input
            id={titleId} value={form.title} maxLength={TITLE_MAX} placeholder="VD: Toán ôn thi học sinh giỏi"
            aria-invalid={!!titleShown} onChange={(e) => patchForm({ title: e.target.value })}
          />
        </WizField>
        <WizField
          label="Mô tả ngắn" htmlFor={descId} counter={`${form.description.length}/${DESCRIPTION_MAX}`}
          hint="Học xong khóa này, học viên làm được gì? Hiển thị dưới tên khóa học."
        >
          <Textarea
            id={descId} rows={4} value={form.description} maxLength={DESCRIPTION_MAX} placeholder="Học xong khóa này, học viên làm được gì..."
            onChange={(e) => patchForm({ description: e.target.value })}
          />
        </WizField>
        <WizField
          label="Ảnh bìa (đường dẫn ảnh)" htmlFor={coverId} error={coverErr}
          hint="Dán đường dẫn ảnh (https://...). Tỷ lệ 16:9, đề xuất 1280x720px. Để trống sẽ dùng khung màu mặc định."
        >
          <Input id={coverId} value={form.coverImageUrl} placeholder="https://..." inputMode="url" aria-invalid={!!coverErr} onChange={(e) => patchForm({ coverImageUrl: e.target.value })} />
          <div className="mt-2 aspect-video w-full max-w-[360px] overflow-hidden rounded-2xl border border-slate-200 bg-slate-100">
            <CoverImage key={form.coverImageUrl.trim()} src={coverErr ? undefined : form.coverImageUrl.trim() || undefined} seed={courseKey} alt="Ảnh bìa khóa học" />
          </div>
        </WizField>
      </FormCard>
      {previewColumn}
    </div>
  );

  const step2 = (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-6 lg:grid-cols-[minmax(0,1fr)_320px]">
      <FormCard title="Chọn cách bán" subtitle="Khóa học miễn phí mở cho mọi thành viên; khóa trả phí cần mua trong Cửa hàng của lớp." headingRef={headingRef}>
        <fieldset className="space-y-2">
          <legend className="mb-2 text-meta font-semibold text-slate-900">Loại bán</legend>
          <label className={radioCardClass(!paid, !sellLockedToPaid && !readOnly)}>
            <input
              type="radio" name={sellGroup} value="FREE" checked={!paid} disabled={sellLockedToPaid || readOnly}
              onChange={() => patchForm({ accessMode: 'FREE' })} className="mt-0.5 h-[18px] w-[18px] flex-shrink-0 accent-blue-600"
            />
            <span className="min-w-0">
              <span className="flex items-center gap-1.5 text-ui font-semibold text-slate-900">
                <Gift className="h-4 w-4 text-slate-500" strokeWidth={1.75} aria-hidden="true" />Miễn phí
              </span>
              <span className="mt-0.5 block text-meta text-slate-600">Mọi thành viên của lớp vào học ngay, không cần mua.</span>
            </span>
          </label>
          <label className={radioCardClass(paid, !paidDisabledReason && !readOnly)}>
            <input
              type="radio" name={sellGroup} value="PURCHASE_REQUIRED" checked={paid} disabled={!!paidDisabledReason || readOnly}
              onChange={() => patchForm({ accessMode: 'PURCHASE_REQUIRED' })} className="mt-0.5 h-[18px] w-[18px] flex-shrink-0 accent-blue-600"
            />
            <span className="min-w-0">
              <span className="flex items-center gap-1.5 text-ui font-semibold text-slate-900">
                <Coins className="h-4 w-4 text-slate-500" strokeWidth={1.75} aria-hidden="true" />Trả phí
              </span>
              <span className="mt-0.5 block text-meta text-slate-600">Học viên mua khóa học (VND) và học trong thời hạn bạn đặt.</span>
            </span>
          </label>
        </fieldset>
        {paidDisabledReason && <Notice tone="warn" role="note">{paidDisabledReason}</Notice>}
        {sellLockedToPaid && (
          <Notice tone="info" role="note">
            Khóa học đã có sản phẩm bán nên không chuyển lại thành miễn phí được. Bạn vẫn đổi được giá và thời hạn bên dưới; muốn ngừng bán, hãy gỡ bán sản phẩm trong mục Shop & đơn hàng.
          </Notice>
        )}

        {paid && (
          <div className="space-y-5 rounded-2xl border border-slate-200 bg-slate-50 p-4 sm:p-5">
            {hasProduct && !productKnown && (
              <Notice tone="warn" role="note">Không đọc được giá hiện tại của sản phẩm (bạn chưa có quyền xem Shop), nên phần này chỉ để xem.</Notice>
            )}
            {hasProduct && productKnown && !canEditProduct && (
              <Notice tone="info" role="note">Bạn chưa có quyền sửa sản phẩm trong Shop (STORE:EDIT) nên không đổi được giá và thời hạn.</Notice>
            )}
            <WizField label="Giá khóa học (VND)" htmlFor={priceId} required hint="Số tiền học viên trả, tính bằng đồng (không có số lẻ)." error={priceErr}>
              <div className="relative">
                <Input
                  id={priceId} inputMode="numeric" autoComplete="off" value={form.priceText} placeholder="VD: 299.000"
                  disabled={priceFieldsReadOnly} aria-invalid={!!priceErr} className="pr-9 tabular"
                  onChange={(e) => patchForm({ priceText: formatPriceInput(e.target.value) })}
                />
                <span aria-hidden="true" className="pointer-events-none absolute inset-y-0 right-3.5 flex items-center text-ui font-semibold text-slate-500">đ</span>
              </div>
            </WizField>
            <WizField
              label="Thời hạn truy cập (ngày)" htmlFor={durationId} required error={durationErr}
              hint={`Học viên học được trong số ngày này kể từ khi mua (1 đến ${MAX_DURATION_DAYS.toLocaleString('vi-VN')} ngày). Hệ thống chưa hỗ trợ truy cập trọn đời.`}
            >
              <div className="mb-2 flex flex-wrap gap-2" role="group" aria-label="Chọn nhanh thời hạn">
                {DURATION_PRESETS.map((preset) => (
                  <FilterChip
                    key={preset.days} selected={parseDuration(form.durationText) === preset.days} disabled={priceFieldsReadOnly}
                    onClick={() => patchForm({ durationText: String(preset.days) })}
                  >
                    {preset.label}
                  </FilterChip>
                ))}
              </div>
              <Input
                id={durationId} inputMode="numeric" autoComplete="off" value={form.durationText} disabled={priceFieldsReadOnly}
                aria-invalid={!!durationErr} className="tabular sm:max-w-[200px]"
                onChange={(e) => patchForm({ durationText: e.target.value.replace(/\D/g, '').slice(0, 5) })}
              />
            </WizField>
            <p className="text-meta text-slate-600">
              Sản phẩm bán khóa học sẽ được tạo ở mục Shop & đơn hàng và liên kết với khóa này. {priceSummary(form) !== 'Chưa nhập giá' && <strong className="font-semibold text-slate-900">{priceSummary(form)}</strong>}
            </p>
          </div>
        )}
      </FormCard>
      {previewColumn}
    </div>
  );

  const step3 = (
    <div className="space-y-4">
      <div>
        <h2 ref={headingRef} tabIndex={-1} className="scroll-mt-52 text-h3-lg font-semibold text-slate-900 focus:outline-none">Thêm bài học & tài liệu</h2>
        <p className="mt-0.5 text-meta text-slate-600">Chia khóa học thành các danh mục, mỗi danh mục có bài video, văn bản, tài liệu hoặc bài tập. Mọi thay đổi ở danh mục được lưu ngay; nội dung bài học lưu bằng nút “Lưu bài học”.</p>
      </div>
      {course && (
        <CourseContentStudio
          ref={studioRef} classId={classId} courseId={course.id} sections={sections}
          canEdit={!readOnly && canEditThisCourse} onChanged={reloadCourse} onDirtyChange={setLessonDirty}
        />
      )}
    </div>
  );

  // Also shown on step 1 (outside the read-only fieldset): a draft without lessons cannot reach step 4, yet must be deletable.
  const dangerZone = course && canEditThisCourse ? (
    <section aria-label="Quản lý khóa học" className="rounded-card border border-red-200 bg-white p-5 shadow-hairline sm:p-7">
      <h2 className="text-h3-lg font-semibold text-slate-900">Quản lý khóa học</h2>
      <p className="mt-0.5 text-meta text-slate-600">Các thao tác ảnh hưởng tới cả khóa học. Người đã mua vẫn giữ quyền học đến hết hạn.</p>
      {zoneError && <p role="alert" className="mt-3 text-meta font-medium text-red-600">{zoneError}</p>}
      <div className="mt-4 flex flex-wrap gap-2">
        {course.status === 'ARCHIVED' ? (
          <Button variant="secondary" size="md" disabled={saving || classLocked} onClick={() => runCourseAction('restore')}>Khôi phục khóa học</Button>
        ) : (
          <Button variant="secondary" size="md" disabled={saving || classLocked} onClick={() => setConfirmKind('archive')}>Lưu trữ khóa học</Button>
        )}
        {course.status === 'DRAFT' && (
          <Button variant="danger" size="md" disabled={saving || classLocked} onClick={() => setConfirmKind('delete')}>Xóa khóa học</Button>
        )}
      </div>
      <p className="mt-3 text-caption text-slate-500">
        {course.status === 'ARCHIVED' ? 'Khóa học đang lưu trữ: khôi phục để sửa và xuất bản lại.' : 'Lưu trữ ẩn khóa học khỏi học viên mới và có thể khôi phục. Chỉ xóa vĩnh viễn được bản nháp chưa có đơn hàng.'}
      </p>
    </section>
  ) : null;

  const summaryHead = (title: string, stepNum: number, editLabel: string) => (
    <div className="mb-3 flex items-center justify-between gap-3">
      <h3 className="text-ui font-semibold text-slate-900">{title}</h3>
      <button
        type="button" onClick={() => goToStep(stepNum)} aria-label={editLabel}
        className="inline-flex h-8 items-center gap-1.5 rounded-[10px] px-2.5 text-meta font-semibold text-blue-600 transition-colors duration-micro hover:bg-tint"
      >
        <Pencil className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />Sửa
      </button>
    </div>
  );

  const step4 = (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-6 lg:grid-cols-[minmax(0,1fr)_320px]">
      <div className="space-y-5">
        <FormCard title="Xem trước & xuất bản" subtitle="Kiểm tra lại từng phần trước khi mở khóa học cho học viên." headingRef={headingRef}>
          {note && <Notice tone={note.tone} role="status">{note.text}</Notice>}
          {isPublished && !note && <Notice tone="success" role="note">Khóa học đang được xuất bản. Thay đổi bạn lưu sẽ hiển thị với học viên ngay.</Notice>}

          <section aria-label="Tóm tắt thông tin cơ bản" className="rounded-2xl border border-slate-200 p-4">
            {summaryHead('Thông tin cơ bản', 1, 'Sửa thông tin cơ bản')}
            <div className="flex items-start gap-3">
              <div className="h-14 w-24 flex-shrink-0 overflow-hidden rounded-thumb bg-slate-100">
                <CoverImage src={form.coverImageUrl.trim() || undefined} seed={courseKey} />
              </div>
              <div className="min-w-0">
                <p className="truncate text-ui font-semibold text-slate-900">{form.title.trim() || 'Chưa đặt tên'}</p>
                <p className="mt-0.5 line-clamp-2 text-meta text-slate-600">{form.description.trim() || 'Chưa có mô tả'}</p>
              </div>
            </div>
          </section>

          <section aria-label="Tóm tắt loại bán" className="rounded-2xl border border-slate-200 p-4">
            {summaryHead('Loại bán', 2, 'Sửa loại bán')}
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={paid ? 'paid' : 'free'}>{paid ? 'Trả phí' : 'Miễn phí'}</Badge>
              {paid && <span className="text-ui font-semibold text-slate-900 tabular">{priceSummary(form)}</span>}
              {paid && product && <Badge tone="neutral" size="sm">Sản phẩm: {productStatusLabel(product.status)}</Badge>}
            </div>
          </section>

          <section aria-label="Tóm tắt nội dung" className="rounded-2xl border border-slate-200 p-4">
            {summaryHead('Nội dung bài học', 3, 'Sửa nội dung bài học')}
            <p className="text-ui text-slate-900 tabular"><strong className="font-semibold">{activeSectionCount(sections)}</strong> danh mục · <strong className="font-semibold">{lessonCount}</strong> bài học</p>
          </section>
        </FormCard>

        <FormCard title="Kiểm tra trước khi xuất bản" subtitle={blocking.length > 0 ? `Còn ${blocking.length} mục bắt buộc chưa xong.` : 'Các mục bắt buộc đã đủ. Bạn có thể xuất bản.'}>
          <ul className="space-y-2" aria-label="Danh sách kiểm tra">
            {checklist.map((item) => (
              <li key={item.key} className="flex items-start gap-2.5 text-ui">
                <span
                  aria-hidden="true"
                  className={cx(
                    'mt-0.5 inline-flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-full',
                    item.ok ? 'bg-green-100 text-green-800' : item.blocking ? 'bg-red-50 text-red-700' : 'bg-warn-soft text-amber-800',
                  )}
                >
                  {item.ok ? <Check className="h-3 w-3" strokeWidth={3} /> : item.blocking ? <CircleAlert className="h-3 w-3" strokeWidth={2.5} /> : <TriangleAlert className="h-3 w-3" strokeWidth={2.5} />}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="font-medium text-slate-900">
                    {item.label}
                    <span className="sr-only">{item.ok ? ': đạt' : item.blocking ? ': còn thiếu, bắt buộc' : ': nên bổ sung'}</span>
                  </span>
                  {!item.ok && (
                    <span className="mt-0.5 block text-meta text-slate-600">
                      {item.fix}{' '}
                      <button type="button" onClick={() => goToStep(item.step)} className="font-semibold text-blue-600 hover:text-blue-700">
                        Đi tới bước này
                      </button>
                    </span>
                  )}
                </span>
              </li>
            ))}
          </ul>
          {!canPublishThisCourse && (
            <Notice tone="warn" role="note">Bạn chưa có quyền xuất bản khóa học này (COURSE:PUBLISH). Bạn vẫn lưu nháp được; hãy nhờ chủ lớp xuất bản.</Notice>
          )}
          {paid && !isPublished && !canPublishProduct && (
            <Notice tone="info" role="note">
              Khóa trả phí cần xuất bản cả sản phẩm bán khóa học. Bạn chưa có quyền xuất bản sản phẩm trong Shop, nên sau khi xuất bản khóa học vẫn cần người có quyền đó bấm “Xuất bản” ở mục Shop & đơn hàng.
            </Notice>
          )}
        </FormCard>

        <FormCard title="Xem trước" subtitle="Xem học viên sẽ thấy khóa học như thế nào.">
          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" size="md" onClick={() => setCardPreview(true)}>
              <Eye className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />Xem trước thẻ khóa học
            </Button>
            <Button variant="secondary" size="md" onClick={() => setDetailPreview(true)}>
              <Eye className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />Xem trước trang khóa học
            </Button>
          </div>
        </FormCard>

        {dangerZone}
      </div>
      {previewColumn}
    </div>
  );

  const stepBody = current === 1 ? step1 : current === 2 ? step2 : current === 3 ? step3 : step4;

  return (
    // The footer sits at the bottom of the viewport even on a short step: this wrapper fills the height below the top bar and
    // cancels <main>'s bottom padding (pb-24), so the footer's sticky position is the page bottom.
    <div className="-mb-24 flex min-h-[calc(100vh-6rem)] flex-col">
      <div className={cx('mx-auto w-full flex-1', wide ? 'max-w-[1280px]' : 'max-w-[1080px]')}>
        <nav aria-label="Đường dẫn" className="mb-3 flex items-center gap-1.5 text-meta text-slate-600">
          <Link to={coursesPath} className="font-medium hover:text-slate-900">Khóa học</Link>
          <span aria-hidden="true">/</span>
          <span className="truncate font-semibold text-slate-900">{course ? course.title : 'Tạo khóa học'}</span>
        </nav>
        <header className="mb-5 flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <h1 className="text-h2-sm font-semibold tracking-[-0.3px] text-slate-900 sm:text-h2">{course ? 'Chỉnh sửa khóa học' : 'Tạo khóa học'}</h1>
            <p className="mt-1 max-w-[640px] text-ui text-slate-600">Làm theo từng bước: bạn có thể lưu nháp bất cứ lúc nào và quay lại sau.</p>
          </div>
          {course && <StatusBadge status={course.status} />}
        </header>

        {readOnly && (
          <Notice tone="warn" role="note" className="mb-4">
            {classroom.status === 'SUSPENDED' ? 'Lớp đang bị tạm khóa' : 'Lớp đã lưu trữ'}: bạn chỉ xem được, không lưu hoặc xuất bản được.
          </Notice>
        )}

        <div className="sticky top-16 z-20 -mx-1 bg-slate-50 px-1 pb-5 pt-1">
          <WizardStepper
            steps={WIZARD_STEPS} ariaLabel="Các bước tạo khóa học" stateOf={stepOf}
            onSelect={(n) => { void goToStep(n); }}
          />
        </div>

        {error && <div className="mb-4"><ErrorBanner message={error} /></div>}

        <fieldset disabled={readOnly && current !== 4} className="min-w-0 border-0 p-0">
          {stepBody}
        </fieldset>
        {current === 1 && dangerZone && <div className="mt-6">{dangerZone}</div>}
      </div>

      <WizardFooter
        wide={wide}
        status={status}
        left={(
          <Button variant="ghost" size="md" disabled={current <= 1 || saving} onClick={() => goToStep(current - 1)}>
            <ArrowLeft className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" /><span className="sr-only sm:not-sr-only">Quay lại</span>
          </Button>
        )}
        right={(
          <>
            <Button variant="secondary" size="md" disabled={saving || readOnly} onClick={saveDraft}>
              <Save className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
              {saving ? 'Đang lưu...' : saveLabel}
            </Button>
            {current < LAST_STEP ? (
              <Button variant="primary" size="md" disabled={saving} onClick={nextStep}>
                <span>Tiếp tục<span className="hidden sm:inline">{nextLabel ? `: ${nextLabel}` : ''}</span></span>
                <ArrowRight className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
              </Button>
            ) : isPublished ? (
              <Button variant="secondary" size="md" disabled={saving || readOnly || !canEditThisCourse} onClick={() => setConfirmKind('unpublish')}>
                <Lock className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />Ngừng xuất bản
              </Button>
            ) : (
              <Button
                variant="primary" size="md" onClick={publish}
                disabled={saving || readOnly || !canPublishThisCourse || blocking.length > 0}
                title={!canPublishThisCourse ? 'Bạn chưa có quyền xuất bản khóa học này.' : blocking.length > 0 ? 'Còn mục bắt buộc chưa xong.' : undefined}
              >
                <Rocket className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />{saving ? 'Đang xử lý...' : 'Xuất bản'}
              </Button>
            )}
          </>
        )}
      />

      {cardPreview && (
        <CourseCardPreview
          form={form} sections={sections} courseId={courseKey} onClose={() => setCardPreview(false)}
          onViewDetail={() => { setCardPreview(false); setDetailPreview(true); }}
        />
      )}
      {detailPreview && (
        <CourseDetailPreview form={form} sections={sections} courseId={courseKey} classroom={classroom} onClose={() => setDetailPreview(false)} />
      )}
      {confirmKind && (
        <Modal
          size="md" role="alertdialog" onClose={() => setConfirmKind(null)}
          title={confirmKind === 'delete' ? 'Xóa khóa học?' : confirmKind === 'archive' ? 'Lưu trữ khóa học?' : 'Ngừng xuất bản khóa học?'}
        >
          <p className="text-ui text-slate-600">
            {confirmKind === 'delete'
              ? `Xóa vĩnh viễn khóa học "${form.title}" cùng các danh mục và bài học của nó. Không thể hoàn tác.`
              : 'Khóa học sẽ chuyển sang lưu trữ và ẩn với học viên mới. Người đã mua vẫn học đến hết hạn. Bạn có thể khôi phục khóa học ngay trong trình chỉnh sửa này.'}
          </p>
          <ModalActions className="mt-5">
            <Button variant="secondary" onClick={() => setConfirmKind(null)}>Hủy</Button>
            <Button variant="danger" disabled={saving} onClick={() => runCourseAction(confirmKind)}>
              {saving ? 'Đang xử lý...' : confirmKind === 'delete' ? 'Xóa khóa học' : confirmKind === 'archive' ? 'Lưu trữ' : 'Ngừng xuất bản'}
            </Button>
          </ModalActions>
        </Modal>
      )}
      <LeaveConfirm open={guard.open} onStay={guard.stay} onLeave={guard.leave} />
    </div>
  );
};
