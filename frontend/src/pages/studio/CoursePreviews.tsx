import React from 'react';
import { BookOpen, ChevronRight, Lock, ShoppingBag } from 'lucide-react';
import type { Classroom, Section } from '../../types';
import { Modal } from '../../components/Modal';
import { Avatar, Badge, Button, CoverImage } from '../../components/ui';
import { formatDong } from '../../api/format';
import { ModalActions } from './studioUi';
import { LESSON_TYPE_LABELS } from './CourseContentStudio';
import { CourseForm, activeLessonCount, parseDuration, parsePrice } from './courseWizardModel';

// "Xem trước thẻ khóa học" / "Xem trước trang khóa học": static copies of what a learner sees in the Learn tab (CourseCard and
// FeaturedCourse of pages/classroom/LearnTab.tsx), fed by the wizard's current form + curriculum. Nothing here is interactive
// except closing / switching between the two previews.

interface PreviewProps {
  form: CourseForm;
  sections: Section[];
  courseId: string;
}

const paid = (form: CourseForm) => form.accessMode === 'PURCHASE_REQUIRED';
const visibleSections = (sections: Section[]) =>
  sections.filter((s) => !s.archived).map((s) => ({ ...s, lessons: (s.lessons ?? []).filter((l) => !l.archived) }));

const AccessBadge: React.FC<{ form: CourseForm; size?: 'md' | 'sm' }> = ({ form, size = 'sm' }) =>
  paid(form) ? <Badge tone="paid" size={size}>Trả phí</Badge> : <Badge tone="free" size={size}>Miễn phí</Badge>;

const PriceLine: React.FC<{ form: CourseForm }> = ({ form }) => {
  if (!paid(form)) return null;
  const price = parsePrice(form.priceText);
  const days = parseDuration(form.durationText);
  return (
    <span className="tabular">
      {price > 0 ? formatDong(price) : 'Chưa nhập giá'}
      {days > 0 && <span className="font-normal text-slate-600"> / {days} ngày</span>}
    </span>
  );
};

/** The course card as the Learn tab draws it (static). */
export const CourseCardFace: React.FC<{ form: CourseForm; lessons: number; courseId: string; className?: string; testId?: string }> = ({ form, lessons, courseId, className = '', testId = 'course-card-face' }) => (
  <article data-testid={testId} className={`overflow-hidden rounded-2xl border border-slate-200 bg-white text-left shadow-hairline sm:rounded-card ${className}`}>
    <div className="aspect-video w-full bg-slate-100">
      <CoverImage src={form.coverImageUrl.trim() || undefined} seed={courseId} icon={<BookOpen className="h-10 w-10" strokeWidth={1.4} />} />
    </div>
    <div className="px-4 pb-4 pt-3 sm:px-[18px] sm:pb-[18px] sm:pt-4">
      <div className="flex flex-wrap items-center gap-2">
        <AccessBadge form={form} />
        <span className="text-meta text-slate-500 tabular">{lessons} bài</span>
      </div>
      <p className="mt-2.5 truncate text-body-sm font-semibold text-slate-900 sm:text-h3">{form.title.trim() || 'Chưa đặt tên khóa học'}</p>
      <p className="mt-1 line-clamp-2 text-meta text-slate-600 sm:text-ui">{form.description.trim() || 'Chưa có mô tả chi tiết cho khóa học.'}</p>
      <div className="pt-3.5 text-meta font-semibold text-slate-900">
        {paid(form) ? (
          <span className="flex items-center gap-1.5">
            <Lock className="h-3.5 w-3.5 flex-shrink-0 text-slate-500" strokeWidth={2} aria-hidden="true" />
            <PriceLine form={form} />
          </span>
        ) : (
          <span className="text-blue-600">Vào học</span>
        )}
      </div>
    </div>
  </article>
);

export const CourseCardPreview: React.FC<PreviewProps & { onClose: () => void; onViewDetail: () => void }> = ({
  form, sections, courseId, onClose, onViewDetail,
}) => {
  const lessons = activeLessonCount(sections);
  return (
    <Modal size="md" title="Xem trước thẻ khóa học" onClose={onClose}>
      <p className="-mt-2 mb-4 text-meta text-slate-600">Đây là thẻ học viên thấy ở tab Khóa học của lớp. Bản xem trước không thực hiện thao tác nào.</p>
      <CourseCardFace form={form} lessons={lessons} courseId={courseId} testId="course-card-preview" />
      <ModalActions className="mt-5">
        <Button variant="secondary" onClick={onClose}>Đóng</Button>
        <Button variant="primary" onClick={onViewDetail}>
          Xem trang khóa học
          <ChevronRight className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
        </Button>
      </ModalActions>
    </Modal>
  );
};

export const CourseDetailPreview: React.FC<PreviewProps & { classroom: Pick<Classroom, 'ownerName' | 'ownerAvatarUrl'>; onClose: () => void }> = ({
  form, sections, courseId, classroom, onClose,
}) => {
  const shown = visibleSections(sections);
  const lessons = activeLessonCount(sections);
  return (
    <Modal size="xl" title="Xem trước trang khóa học" onClose={onClose}>
      <p className="-mt-2 mb-4 text-meta text-slate-600">Đây là trang học viên thấy khi mở khóa học. Bản xem trước không thực hiện thao tác nào.</p>
      <article data-testid="course-detail-preview" className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-hairline">
        <div className="grid grid-cols-1 md:grid-cols-[minmax(0,280px)_minmax(0,1fr)]">
          <div className="aspect-video bg-slate-100 md:aspect-auto md:min-h-[200px]">
            <CoverImage src={form.coverImageUrl.trim() || undefined} seed={courseId} icon={<BookOpen className="h-10 w-10" strokeWidth={1.4} />} />
          </div>
          <div className="flex min-w-0 flex-col px-4 py-4 sm:px-6 sm:py-5">
            <div className="flex flex-wrap items-center gap-2">
              <AccessBadge form={form} />
              <span className="text-meta text-slate-500 tabular">{lessons} bài</span>
            </div>
            <h4 className="mt-3 text-h3 font-semibold text-slate-900 sm:text-[22px] sm:leading-[30px]">{form.title.trim() || 'Chưa đặt tên khóa học'}</h4>
            <p className="mt-1.5 whitespace-pre-line text-meta text-slate-600 sm:text-ui sm:leading-[22px]">
              {form.description.trim() || 'Chưa có mô tả chi tiết cho khóa học.'}
            </p>
            {classroom.ownerName && (
              <div className="mt-3 flex items-center gap-2">
                <Avatar name={classroom.ownerName} src={classroom.ownerAvatarUrl} size={22} />
                <span className="text-meta text-slate-600">
                  <strong className="font-semibold text-slate-900">{classroom.ownerName}</strong> · Người dẫn dắt
                </span>
              </div>
            )}
            <div className="mt-auto pt-4">
              {paid(form) ? (
                <div className="space-y-2 rounded-2xl bg-slate-50 px-4 py-3.5">
                  <p className="text-body-sm font-semibold text-slate-900"><PriceLine form={form} /></p>
                  <p className="inline-flex items-center gap-1.5 text-meta text-slate-600">
                    <ShoppingBag className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                    Học viên mua khóa học tại Cửa hàng của lớp để mở toàn bộ bài học.
                  </p>
                </div>
              ) : (
                <p className="text-meta text-slate-600">Miễn phí: thành viên của lớp vào học ngay.</p>
              )}
            </div>
          </div>
        </div>

        <div className="border-t border-slate-100">
          <p className="px-4 py-3 text-ui font-semibold text-slate-900 sm:px-6">
            Chương trình học
            <span className="ml-2 font-normal text-slate-500 tabular">{shown.length} danh mục · {lessons} bài</span>
          </p>
          {shown.length === 0 ? (
            <p className="px-4 pb-5 text-ui text-slate-500 sm:px-6">Khóa học chưa có bài học nào.</p>
          ) : (
            <div className="pb-3">
              {shown.map((section) => (
                <div key={section.id} className="border-t border-slate-100">
                  <div className="flex items-baseline justify-between gap-3 px-4 pb-1.5 pt-3.5 sm:px-6">
                    <p className="text-meta font-semibold text-slate-900">{section.title}</p>
                    <p className="flex-shrink-0 text-caption text-slate-500 tabular">{section.lessons.length} bài học</p>
                  </div>
                  <ul>
                    {section.lessons.map((lesson) => (
                      <li key={lesson.id} className="grid min-h-[40px] grid-cols-[minmax(0,1fr)_auto] items-center gap-2.5 px-4 py-1.5 sm:px-6">
                        <span className="min-w-0 truncate text-meta font-medium text-slate-900">{lesson.title}</span>
                        <span className="flex-shrink-0 text-caption text-slate-500 tabular">
                          {LESSON_TYPE_LABELS[lesson.type] ?? lesson.type}
                          {lesson.durationMinutes > 0 ? ` · ${lesson.durationMinutes} phút` : ''}
                        </span>
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </div>
          )}
        </div>
      </article>
      <ModalActions className="mt-5">
        <Button variant="secondary" onClick={onClose}>Đóng</Button>
      </ModalActions>
    </Modal>
  );
};
