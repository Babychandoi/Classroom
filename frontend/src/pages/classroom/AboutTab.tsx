import React, { useEffect, useId, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom } from '../../types';
import { api } from '../../api/client';
import { AboutSections, AboutSectionsEditor, AboutSection } from '../../components/AboutSections';
import { SafeMarkdown } from '../../components/SafeMarkdown';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { Avatar, buttonClass, inputClass } from '../../components/ui';
import { accessPriceLabel } from '../../api/format';
import { Check, Coins, Edit3, Gift, Globe, Info, Lock, ShieldCheck, Tag, Users } from 'lucide-react';

interface ClassAbout {
  id: string;
  classId: string;
  contentMarkdown: string;
  rulesMarkdown: string;
  publishedVersion: number;
  sections?: AboutSection[];
}

export const AboutTab: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();

  const [about, setAbout] = useState<ClassAbout | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Edit mode for owner/staff
  const [editing, setEditing] = useState(false);
  const [contentDraft, setContentDraft] = useState('');
  const [rulesDraft, setRulesDraft] = useState('');
  const [sections, setSections] = useState<AboutSection[]>([]);
  const [saveError, setSaveError] = useState('');
  const [saving, setSaving] = useState(false);
  const contentId = useId();
  const rulesId = useId();

  const canEdit = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('ABOUT:EDIT');

  const fetchAbout = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.get<ClassAbout>(`/classes/${classroom.id}/about`);
      setAbout(data);
      setContentDraft(data.contentMarkdown || '');
      setRulesDraft(data.rulesMarkdown || '');
      setSections(data.sections || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải thông tin giới thiệu');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchAbout();
  }, [classroom.id]);

  const handleSave = async () => {
    setSaving(true);
    setSaveError('');
    try {
      const updated = await api.put<ClassAbout>(`/classes/${classroom.id}/about`, {
        contentMarkdown: contentDraft,
        rulesMarkdown: rulesDraft,
        sections: sections.map(s => ({ ...s, imageUrl: s.mediaAssetId ? '' : s.imageUrl })),
        publishedVersion: about?.publishedVersion,
      });
      setAbout(updated);
      setEditing(false);
    } catch (err: any) {
      setSaveError(err.message || 'Cập nhật thất bại. Nội dung bạn nhập vẫn được giữ lại.');
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <LoadingSpinner message="Đang tải trang giới thiệu..." />;
  if (error) return <ErrorBanner message={error} onRetry={fetchAbout} />;

  const created = monthYear(classroom.createdAt);
  const isPrivate = classroom.visibility === 'PRIVATE';
  const product = classroom.accessProduct;
  const textareaClass = inputClass('py-3 font-mono leading-[22px]');

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h2 className="text-h2-sm font-semibold text-slate-900">Giới thiệu & Nội quy</h2>
          <p className="mt-0.5 text-meta text-slate-600">Lớp học này dành cho ai, học được gì và những quy tắc chung của lớp</p>
        </div>

        {canEdit && !editing && (
          <button type="button" onClick={() => setEditing(true)} className={buttonClass('secondary', 'md')}>
            <Edit3 className="h-4 w-4 text-slate-600" strokeWidth={1.75} aria-hidden="true" />
            <span>Chỉnh sửa nội dung</span>
          </button>
        )}

        {editing && (
          <div className="flex gap-2">
            <button type="button" onClick={() => setEditing(false)} className={buttonClass('secondary', 'md')}>
              Hủy
            </button>
            <button type="button" onClick={handleSave} disabled={saving} className={buttonClass('primary', 'md')}>
              <Check className="h-4 w-4" strokeWidth={2} aria-hidden="true" />
              <span>{saving ? 'Đang lưu...' : 'Lưu thay đổi'}</span>
            </button>
          </div>
        )}
      </div>

      {saveError && <p role="alert" className="rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-ui text-red-700">{saveError}</p>}
      {editing ? (
        <div className="space-y-6 rounded-card border border-slate-200 bg-white p-5 shadow-hairline sm:p-7">
          <AboutSectionsEditor classId={classroom.id} sections={sections} onChange={setSections} />
          <div>
            <label htmlFor={contentId} className="mb-1.5 block text-meta font-semibold text-slate-900">
              Nội dung giới thiệu lớp học (Markdown)
            </label>
            <textarea id={contentId} rows={8} value={contentDraft} onChange={(e) => setContentDraft(e.target.value)} className={textareaClass} />
          </div>

          <div>
            <label htmlFor={rulesId} className="mb-1.5 block text-meta font-semibold text-slate-900">
              Nội quy lớp học (Markdown)
            </label>
            <textarea id={rulesId} rows={6} value={rulesDraft} onChange={(e) => setRulesDraft(e.target.value)} className={textareaClass} />
          </div>
        </div>
      ) : (
        <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_360px]">
          <div className="min-w-0 space-y-4">
            {/* 1. Name + one-line pitch + facts the system knows */}
            <section className="rounded-card border border-slate-200 bg-white p-5 shadow-hairline sm:px-7 sm:py-6">
              <h3 className="text-h2-sm font-semibold text-slate-900 sm:text-h2">{classroom.title}</h3>
              {classroom.description && <p className="mt-2 text-body-sm text-slate-600">{classroom.description}</p>}
              <div className="mt-4 flex flex-wrap items-center gap-x-6 gap-y-2 border-t border-slate-100 pt-3.5 text-meta font-medium text-slate-600">
                {classroom.category && (
                  <span data-testid="about-category" className="inline-flex h-[26px] items-center gap-1.5 rounded-full bg-slate-100 px-2.5 text-caption font-semibold text-slate-600">
                    <Tag className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />
                    {classroom.category}
                  </span>
                )}
                <span className="inline-flex items-center gap-1.5">
                  {isPrivate ? <Lock className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" /> : <Globe className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />}
                  {isPrivate ? 'Riêng tư' : 'Công khai'}
                </span>
                <span className="inline-flex items-center gap-1.5">
                  <Users className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                  <strong className="font-semibold text-slate-900 tabular">{(classroom.memberCount ?? 0).toLocaleString('vi-VN')}</strong> thành viên
                </span>
                {classroom.accessType === 'PAID' ? (
                  <span className="inline-flex items-center gap-1.5 font-semibold text-violet-800 tabular">
                    <Coins className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                    {product ? accessPriceLabel(product.price, product.durationDays, product.lifetime) : 'Trả phí'}
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1.5 font-semibold text-green-700">
                    <Gift className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                    Miễn phí
                  </span>
                )}
                {classroom.ownerName && (
                  <span className="inline-flex items-center gap-1.5">
                    <Avatar name={classroom.ownerName} src={classroom.ownerAvatarUrl} size={20} />
                    bởi <strong className="font-semibold text-slate-900">{classroom.ownerName}</strong>
                  </span>
                )}
              </div>
            </section>

            <AboutSections sections={about?.sections || []} />

            {/* 2. The owner's own description */}
            <section className="rounded-card border border-slate-200 bg-white p-5 shadow-hairline sm:px-7 sm:py-6">
              <div className="mb-3 flex items-center gap-2 text-slate-600">
                <Info className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                <span className="text-caption font-semibold uppercase tracking-[0.5px]">Thông tin chi tiết</span>
              </div>
              {about?.contentMarkdown
                ? <SafeMarkdown source={about.contentMarkdown} size="body" className="max-w-reading" />
                : <p className="text-body-sm text-slate-600">Lớp học chưa có mô tả giới thiệu.</p>}
            </section>

            {/* 3. House rules */}
            <section className="rounded-card border border-slate-200 bg-white p-5 shadow-hairline sm:px-7 sm:py-6">
              <div className="mb-3 flex items-center gap-2 text-slate-600">
                <ShieldCheck className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                <span className="text-caption font-semibold uppercase tracking-[0.5px]">Nội quy lớp học</span>
              </div>
              {about?.rulesMarkdown
                ? <SafeMarkdown source={about.rulesMarkdown} size="body" className="max-w-reading [&_li]:text-ui [&_p]:text-ui" />
                : <p className="text-ui text-slate-600">Nội quy đang được cập nhật.</p>}
            </section>

            {/* 4. Who leads the class (dark band) */}
            {classroom.ownerName && (
              <section className="rounded-card bg-slate-900 p-5 sm:px-7 sm:py-6">
                <p className="text-caption font-semibold uppercase tracking-[0.7px] text-blue-300">Người dẫn dắt</p>
                <div className="mt-3.5 flex items-center gap-4">
                  <Avatar name={classroom.ownerName} src={classroom.ownerAvatarUrl} size={56} />
                  <div className="min-w-0">
                    <p className="text-body font-semibold text-white">{classroom.ownerName}</p>
                    <p className="mt-0.5 text-meta text-slate-300">Chủ lớp{created ? ` · dẫn dắt lớp từ ${created}` : ''}</p>
                  </div>
                </div>
              </section>
            )}
          </div>

          {/* Right rail: the class at a glance (real numbers only). */}
          <aside className="min-w-0 lg:sticky lg:top-[128px]">
            <div className="rounded-card border border-slate-200 bg-white p-5 shadow-hairline">
              <p className="text-h3 font-semibold text-slate-900">{classroom.title}</p>
              <p className="mt-0.5 truncate text-caption text-slate-500">/classes/{classroom.slug}</p>
              <div className={`mt-4 grid border-y border-slate-100 py-3 text-center ${classroom.upcomingEventCount != null ? 'grid-cols-3' : 'grid-cols-2'}`}>
                <span>
                  <span className="block text-body font-semibold text-slate-900 tabular">{(classroom.memberCount ?? 0).toLocaleString('vi-VN')}</span>
                  <span className="mt-0.5 block text-micro text-slate-500">Thành viên</span>
                </span>
                <span className="border-l border-slate-100">
                  <span className="block text-body font-semibold text-slate-900 tabular">{created || '—'}</span>
                  <span className="mt-0.5 block text-micro text-slate-500">Thành lập</span>
                </span>
                {classroom.upcomingEventCount != null && (
                  <span className="border-l border-slate-100">
                    <span className="block text-body font-semibold text-slate-900 tabular">{classroom.upcomingEventCount}</span>
                    <span className="mt-0.5 block text-micro text-slate-500">Sự kiện sắp tới</span>
                  </span>
                )}
              </div>
              <ul className="mt-4 space-y-2">
                {[
                  isPrivate ? 'Lớp riêng tư — vào lớp bằng liên kết mời của chủ lớp' : 'Lớp công khai — ai cũng xem được trang giới thiệu',
                  classroom.accessType === 'PAID'
                    ? `Gói vào lớp ${product ? accessPriceLabel(product.price, product.durationDays, product.lifetime) : 'trả phí'}`
                    : 'Tham gia miễn phí',
                  ...(classroom.requireApproval && classroom.accessType !== 'PAID' ? ['Người dẫn dắt duyệt từng người trước khi vào lớp'] : []),
                ].map((text) => (
                  <li key={text} className="flex items-start gap-2 text-meta text-slate-600">
                    <Check className="mt-0.5 h-[13px] w-[13px] flex-shrink-0 text-green-600" strokeWidth={2.4} aria-hidden="true" />
                    <span className="tabular">{text}</span>
                  </li>
                ))}
              </ul>
            </div>
          </aside>
        </div>
      )}
    </div>
  );
};

/** ISO instant -> "mm/yyyy" ('' when absent). */
function monthYear(instant?: string | null): string {
  if (!instant) return '';
  const d = new Date(instant);
  if (Number.isNaN(d.getTime())) return '';
  return `${String(d.getMonth() + 1).padStart(2, '0')}/${d.getFullYear()}`;
}
