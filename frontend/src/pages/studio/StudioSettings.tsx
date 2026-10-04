import React, { useEffect, useId, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, ClassAccessType, ClassVisibility } from '../../types';
import { api } from '../../api/client';
import { accessPriceLabel, durationLabel, formatDong } from '../../api/format';
import { putToObjectStore } from '../../api/upload';
import { ErrorBanner } from '../../components/UIStates';
import { ClassBadges } from '../../components/ClassBadges';
import { hasStudioPermission } from '../../api/permissions';
import { Button, Card, ClassAvatar, CoverImage, Field, FilterChip, Input, Textarea, Toggle, buttonClass } from '../../components/ui';
import { ImagePositioner } from './ImagePositioner';
import { CardHeader, Notice, PageHeader, StudioPage, radioCardClass } from './studioUi';
import { Archive, ArchiveRestore, Coins, Crop, Eye, Gift, Globe, ImageIcon, Lock, Save, Trash2, Upload, UserCheck } from 'lucide-react';

// D-19: PUT /classes/{id}/access accepts 1..3650 days; no duration = lifetime.
const MAX_ACCESS_DAYS = 3650;
const DEFAULT_ACCESS_DAYS = 30;
// Class cover uploads: images only, at most 5 MB (media purpose CLASS_COVER).
const MAX_COVER_BYTES = 5 * 1024 * 1024;

/** API-CREATE-CLASS §1: the fixed category list, used when GET /classes/categories is unavailable. */
export const FALLBACK_CATEGORIES = [
  'Nấu ăn', 'Ăn chay', 'Sức khoẻ', 'Chạy bộ', 'Thể hình', 'YouTube', 'Kinh doanh', 'Tiếng Anh', 'Ôn thi', 'AI', 'Âm nhạc', 'Phát triển bản thân',
];

/** Create-class contract fields read here (typed locally until the shared Classroom type has them). */
type SettingsClassroom = Classroom & {
  category?: string | null;
  avatarMediaId?: string | null;
  avatarUrl?: string | null;
  coverPosition?: string | null;
  avatarPosition?: string | null;
  requireApproval?: boolean;
  pendingRequestCount?: number;
};

type ImageKind = 'cover' | 'avatar';
const IMAGE_KINDS: Record<ImageKind, { purpose: string; field: 'coverMediaId' | 'avatarMediaId'; noun: string; lower: string }> = {
  cover: { purpose: 'CLASS_COVER', field: 'coverMediaId', noun: 'Ảnh bìa', lower: 'ảnh bìa' },
  avatar: { purpose: 'CLASS_AVATAR', field: 'avatarMediaId', noun: 'Ảnh đại diện', lower: 'ảnh đại diện' },
};

/**
 * R13-02: Studio "Cài đặt lớp" (FR-14 / sitemap /studio/classes/:id/settings).
 * Edit form is gated on CLASS:EDIT (OWNER always satisfies it); archive/unarchive is OWNER-only,
 * matching ClassroomService#updateClassroomStatus's authorization (a much wider blast radius than
 * a plain field edit - it flips visibility/joinability for the whole class).
 */
export const StudioSettings: React.FC = () => {
  const { classroom, refreshClassroom } = useOutletContext<{ classroom: SettingsClassroom; refreshClassroom: () => Promise<void> }>();
  const canEdit = classroom.userRole === 'OWNER' || hasStudioPermission(classroom, 'CLASS', 'EDIT');
  const isOwner = classroom.userRole === 'OWNER';
  // D-19: money is involved, so changing FREE/PAID is OWNER, or staff holding BOTH CLASS:EDIT and STORE:EDIT (ClassAccessService).
  const canEditAccess = isOwner || (hasStudioPermission(classroom, 'CLASS', 'EDIT') && hasStudioPermission(classroom, 'STORE', 'EDIT'));

  const [title, setTitle] = useState(classroom.title);
  const [description, setDescription] = useState(classroom.description ?? '');
  const [coverImageUrl, setCoverImageUrl] = useState(classroom.coverImageUrl ?? '');
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showArchiveConfirm, setShowArchiveConfirm] = useState(false);
  const [archiving, setArchiving] = useState(false);

  // D-19 "Hiển thị & tham gia": who can find the class, and how it is joined.
  const currentVisibility: ClassVisibility = classroom.visibility === 'PRIVATE' ? 'PRIVATE' : 'PUBLIC';
  const currentAccess: ClassAccessType = classroom.accessType === 'PAID' ? 'PAID' : 'FREE';
  const [visibilityChoice, setVisibilityChoice] = useState<ClassVisibility>(currentVisibility);
  const [visibilityError, setVisibilityError] = useState<string | null>(null);
  const [visibilityBusy, setVisibilityBusy] = useState(false);
  const [accessChoice, setAccessChoice] = useState<ClassAccessType>(currentAccess);
  const [priceInput, setPriceInput] = useState(classroom.accessProduct ? String(Math.round(classroom.accessProduct.price)) : '');
  const [lifetime, setLifetime] = useState(classroom.accessProduct ? !classroom.accessProduct.durationDays : false);
  const [daysInput, setDaysInput] = useState(String(classroom.accessProduct?.durationDays || DEFAULT_ACCESS_DAYS));
  const [accessError, setAccessError] = useState<string | null>(null);
  const [accessBusy, setAccessBusy] = useState(false);
  const [confirmingAccess, setConfirmingAccess] = useState(false);

  const titleFieldId = useId();
  const descriptionFieldId = useId();
  const coverImageFieldId = useId();
  const visibilityGroupName = useId();
  const accessGroupName = useId();
  const priceFieldId = useId();
  const daysFieldId = useId();
  const lifetimeFieldId = useId();

  useEffect(() => {
    setTitle(classroom.title);
    setDescription(classroom.description ?? '');
    setCoverImageUrl(classroom.coverImageUrl ?? '');
  }, [classroom.id, classroom.title, classroom.description, classroom.coverImageUrl]);

  // Follow the server's value after a save / refresh (and reset the half-edited choice with it).
  useEffect(() => {
    setVisibilityChoice(currentVisibility);
  }, [classroom.id, currentVisibility]);

  const productPrice = classroom.accessProduct?.price;
  const productDays = classroom.accessProduct?.durationDays ?? null;
  useEffect(() => {
    setAccessChoice(currentAccess);
    setPriceInput(productPrice != null ? String(Math.round(productPrice)) : '');
    setLifetime(classroom.accessProduct ? !productDays : false);
    setDaysInput(String(productDays || DEFAULT_ACCESS_DAYS));
    setConfirmingAccess(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classroom.id, currentAccess, productPrice, productDays]);

  const isArchived = classroom.status?.toUpperCase() === 'ARCHIVED';

  // PUT /classes/{id} replaces title / description / coverImageUrl, so every partial save sends their SAVED values
  // (never the half-edited form) plus only the fields being changed.
  const savePatch = async (patch: Record<string, unknown>) => {
    await api.put(`/classes/${classroom.id}`, {
      title: classroom.title,
      description: classroom.description ?? '',
      coverImageUrl: classroom.coverImageUrl ?? '',
      ...patch,
    });
    await refreshClassroom();
  };

  // Cover and avatar: the same media flow as the other Studio uploads (upload intent -> presigned PUT -> complete),
  // then PUT with the new coverMediaId / avatarMediaId ("" clears it).
  const [imageBusy, setImageBusy] = useState<ImageKind | null>(null);
  const [imageError, setImageError] = useState<{ kind: ImageKind; text: string } | null>(null);
  const [positioning, setPositioning] = useState<ImageKind | null>(null);
  const [positionSaving, setPositionSaving] = useState(false);
  const coverBusy = imageBusy === 'cover';
  const avatarBusy = imageBusy === 'avatar';

  const handleUploadImage = async (kind: ImageKind, file?: File) => {
    if (!file) return;
    const k = IMAGE_KINDS[kind];
    setImageError(null);
    setMessage(null);
    if (!file.type.startsWith('image/')) {
      setImageError({ kind, text: `${k.noun} phải là tệp ảnh (JPG, PNG, WebP hoặc GIF).` });
      return;
    }
    if (file.size > MAX_COVER_BYTES) {
      setImageError({ kind, text: `${k.noun} tối đa 5 MB. Bạn hãy chọn ảnh nhỏ hơn hoặc nén ảnh trước khi tải lên.` });
      return;
    }
    setImageBusy(kind);
    try {
      const intent = await api.post<{ assetId: string; uploadUrl: string }>(`/classes/${classroom.id}/media/upload-intents`, {
        filename: file.name, mimeType: file.type, sizeBytes: file.size, purpose: k.purpose,
      });
      await putToObjectStore(intent.uploadUrl, file, `Tải ${k.lower} thất bại`);
      await api.post(`/media/${intent.assetId}/complete`);
      await savePatch({ [k.field]: intent.assetId });
      setPositioning(null);
      setMessage(`Đã cập nhật ${k.lower} của lớp.`);
    } catch (err: any) {
      setImageError({ kind, text: err.message || `Không thể tải ${k.lower} lên. Bạn thử lại sau ít phút nhé.` });
    } finally {
      setImageBusy(null);
    }
  };

  const handleRemoveImage = async (kind: ImageKind) => {
    const k = IMAGE_KINDS[kind];
    setImageError(null);
    setMessage(null);
    setImageBusy(kind);
    try {
      await savePatch({ [k.field]: '' });
      setPositioning(null);
      setMessage(`Đã gỡ ${k.lower} của lớp.`);
    } catch (err: any) {
      setImageError({ kind, text: err.message || `Không thể gỡ ${k.lower}` });
    } finally {
      setImageBusy(null);
    }
  };

  const handleSavePosition = async (kind: ImageKind, position: string) => {
    setImageError(null);
    setMessage(null);
    setPositionSaving(true);
    try {
      await savePatch(kind === 'cover' ? { coverPosition: position } : { avatarPosition: position });
      setPositioning(null);
      setMessage(`Đã lưu vị trí ${IMAGE_KINDS[kind].lower}.`);
    } catch (err: any) {
      setImageError({ kind, text: err.message || 'Không thể lưu vị trí ảnh' });
    } finally {
      setPositionSaving(false);
    }
  };

  // Category chips (GET /classes/categories, public; falls back to the contract list).
  const [categories, setCategories] = useState<string[]>(FALLBACK_CATEGORIES);
  const [category, setCategory] = useState<string | null>(classroom.category ?? null);
  useEffect(() => {
    let cancelled = false;
    api.get<string[]>('/classes/categories')
      .then((list) => { if (!cancelled && Array.isArray(list) && list.length) setCategories(list); })
      .catch(() => { /* keep the fallback list */ });
    return () => { cancelled = true; };
  }, []);
  useEffect(() => setCategory(classroom.category ?? null), [classroom.id, classroom.category]);
  const categoryOptions = category && !categories.includes(category) ? [...categories, category] : categories;

  // "Duyệt từng người trước khi vào": saved as soon as it is switched.
  const requireApproval = classroom.requireApproval === true;
  const [approvalBusy, setApprovalBusy] = useState(false);
  const [approvalError, setApprovalError] = useState<string | null>(null);
  const handleToggleApproval = async (next: boolean) => {
    setApprovalBusy(true);
    setApprovalError(null);
    setMessage(null);
    try {
      await savePatch({ requireApproval: next });
      setMessage(next ? 'Đã bật duyệt thành viên: người mới cần được bạn duyệt trước khi vào lớp.' : 'Đã tắt duyệt thành viên: người mới vào lớp ngay.');
    } catch (err: any) {
      setApprovalError(err.message || 'Không thể đổi cách duyệt thành viên');
    } finally {
      setApprovalBusy(false);
    }
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setMessage(null);
    setError(null);
    try {
      await api.put(`/classes/${classroom.id}`, {
        title: title.trim(),
        description,
        coverImageUrl,
        // only what changed among the newer optional fields (absent = keep)
        ...(category && category !== (classroom.category ?? null) ? { category } : {}),
      });
      await refreshClassroom();
      setMessage('Đã lưu cài đặt lớp học.');
    } catch (err: any) {
      setError(err.message || 'Không thể lưu cài đặt lớp học');
    } finally {
      setSaving(false);
    }
  };

  const handleToggleArchive = async () => {
    setArchiving(true);
    setError(null);
    try {
      await api.put(`/classes/${classroom.id}/status`, {
        status: isArchived ? 'ACTIVE' : 'ARCHIVED',
      });
      await refreshClassroom();
      setShowArchiveConfirm(false);
      setMessage(isArchived ? 'Đã mở lại lớp học.' : 'Đã lưu trữ (đóng) lớp học.');
    } catch (err: any) {
      setError(err.message || 'Không thể đổi trạng thái lớp học');
    } finally {
      setArchiving(false);
    }
  };

  // The saved title / description / cover go with the visibility change: PUT /classes/{id} replaces them, so sending the SAVED values
  // (not the half-edited form above) changes the visibility and nothing else.
  const handleChangeVisibility = async () => {
    setVisibilityBusy(true);
    setVisibilityError(null);
    setMessage(null);
    try {
      await api.put(`/classes/${classroom.id}`, {
        title: classroom.title,
        description: classroom.description ?? '',
        coverImageUrl: classroom.coverImageUrl ?? '',
        visibility: visibilityChoice,
      });
      await refreshClassroom();
      setMessage(visibilityChoice === 'PRIVATE' ? 'Đã chuyển lớp sang chế độ Riêng tư.' : 'Đã chuyển lớp sang chế độ Công khai.');
    } catch (err: any) {
      setVisibilityError(err.message || 'Không thể đổi chế độ hiển thị');
    } finally {
      setVisibilityBusy(false);
    }
  };

  const parsedPrice = /^\d+$/.test(priceInput.trim()) ? Number(priceInput.trim()) : NaN;
  const parsedDays = /^\d+$/.test(daysInput.trim()) ? Number(daysInput.trim()) : NaN;
  const priceValid = Number.isSafeInteger(parsedPrice) && parsedPrice > 0;
  const daysValid = lifetime || (Number.isInteger(parsedDays) && parsedDays >= 1 && parsedDays <= MAX_ACCESS_DAYS);
  const accessUnchanged =
    accessChoice === currentAccess &&
    (accessChoice === 'FREE' ||
      (priceValid && parsedPrice === Math.round(productPrice ?? -1) && (lifetime ? !productDays : parsedDays === productDays)));

  const requestAccessChange = () => {
    setAccessError(null);
    setMessage(null);
    if (accessChoice === 'PAID') {
      if (!priceValid) {
        setAccessError('Giá vào lớp phải là số nguyên đồng lớn hơn 0.');
        return;
      }
      if (!daysValid) {
        setAccessError(`Thời hạn phải từ 1 đến ${MAX_ACCESS_DAYS} ngày, hoặc chọn "Trọn đời".`);
        return;
      }
    }
    setConfirmingAccess(true);
  };

  const handleChangeAccess = async () => {
    setAccessBusy(true);
    setAccessError(null);
    try {
      await api.put(`/classes/${classroom.id}/access`, accessChoice === 'PAID'
        ? { accessType: 'PAID', price: parsedPrice, currency: 'VND', durationDays: lifetime ? null : parsedDays }
        : { accessType: 'FREE' });
      await refreshClassroom();
      setConfirmingAccess(false);
      setMessage(accessChoice === 'PAID' ? 'Đã lưu hình thức thu phí của lớp.' : 'Đã chuyển lớp sang miễn phí.');
    } catch (err: any) {
      setConfirmingAccess(false);
      setAccessError(err.message || 'Không thể đổi hình thức thu phí');
    } finally {
      setAccessBusy(false);
    }
  };

  const accessConsequence = (): string => {
    if (currentAccess === 'FREE' && accessChoice === 'PAID') {
      return `Từ nay người mới phải trả ${formatDong(parsedPrice)} / ${durationLabel(lifetime ? null : parsedDays)} để vào lớp. Thành viên hiện tại được giữ quyền truy cập miễn phí trọn đời.`;
    }
    if (currentAccess === 'PAID' && accessChoice === 'FREE') {
      return 'Mọi người có thể vào lớp tự do, không cần trả phí. Gói vào lớp hiện tại được lưu trữ (lịch sử đơn hàng vẫn giữ); thành viên đang hoạt động không mất quyền và thành viên đã hết hạn chỉ cần bấm tham gia lại.';
    }
    return `Giá và thời hạn mới (${formatDong(parsedPrice)} / ${durationLabel(lifetime ? null : parsedDays)}) chỉ áp dụng cho đơn mua mới. Thành viên hiện tại giữ nguyên thời hạn đã mua.`;
  };

  const coverPreview = classroom.coverUrl || coverImageUrl.trim() || null;

  return (
    <StudioPage width="narrow">
      <PageHeader title="Cài đặt lớp học" description="Ảnh của lớp, thông tin hiển thị, cách vào lớp và trạng thái hoạt động của lớp." />

      {error && <ErrorBanner message={error} />}
      {message && <Notice tone="success" role="status">{message}</Notice>}

      <Card as="section" aria-labelledby="cover-section-title" className="space-y-4">
        <CardHeader
          id="cover-section-title"
          title="Ảnh bìa"
          description="Hiện ở đầu trang lớp và trên thẻ lớp trong danh sách khám phá. Ảnh ngang 16:9, tối đa 5 MB."
        />
        {positioning === 'cover' && coverPreview ? (
          <ImagePositioner
            src={coverPreview}
            value={classroom.coverPosition}
            frameClassName="aspect-[16/9] w-full sm:aspect-[3/1]"
            label="Căn ảnh bìa"
            saving={positionSaving}
            onSave={(pos) => handleSavePosition('cover', pos)}
            onCancel={() => setPositioning(null)}
          />
        ) : (
          <div className="relative aspect-[16/9] w-full overflow-hidden rounded-2xl border border-slate-200 bg-slate-50 sm:aspect-[3/1]">
            {coverPreview ? (
              <img
                src={coverPreview}
                alt={`Ảnh bìa của lớp ${classroom.title}`}
                className="h-full w-full object-cover"
                style={{ objectPosition: classroom.coverPosition || '50% 50%' }}
              />
            ) : (
              <CoverImage src={null} seed={classroom.id} icon={<ImageIcon className="h-10 w-10" strokeWidth={1.5} />} />
            )}
          </div>
        )}
        {imageError?.kind === 'cover' && <p role="alert" className="text-meta font-medium text-red-600">{imageError.text}</p>}
        {canEdit ? (
          <div className="flex flex-wrap items-center gap-2">
            <label
              className={buttonClass('secondary', 'md', `cursor-pointer focus-within:ring-2 focus-within:ring-blue-600 focus-within:ring-offset-2 ${coverBusy ? 'pointer-events-none opacity-60' : ''}`)}
            >
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp,image/gif"
                className="sr-only"
                disabled={coverBusy}
                onChange={(e) => { void handleUploadImage('cover', e.target.files?.[0]); e.target.value = ''; }}
              />
              <Upload className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
              <span>{coverBusy ? 'Đang tải ảnh...' : classroom.coverUrl ? 'Đổi ảnh bìa' : 'Tải ảnh bìa lên'}</span>
            </label>
            {coverPreview && positioning !== 'cover' && (
              <Button variant="ghost" size="md" disabled={coverBusy} onClick={() => setPositioning('cover')}>
                <Crop className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                Căn ảnh bìa
              </Button>
            )}
            {classroom.coverUrl && (
              <Button variant="ghost" size="md" disabled={coverBusy} onClick={() => handleRemoveImage('cover')}>
                <Trash2 className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                Gỡ ảnh bìa
              </Button>
            )}
            <span className="text-caption text-slate-500">JPG, PNG, WebP hoặc GIF.</span>
          </div>
        ) : (
          <p className="text-meta text-slate-600">Bạn cần quyền CLASS:EDIT để đổi ảnh bìa.</p>
        )}
      </Card>

      <Card as="section" aria-labelledby="avatar-section-title" className="space-y-4">
        <CardHeader
          id="avatar-section-title"
          title="Ảnh đại diện lớp"
          description="Ảnh vuông nhỏ cạnh tên lớp, trong Xưởng và trên thẻ lớp. Nên dùng logo hoặc ảnh rõ nét, tối đa 5 MB."
        />
        {positioning === 'avatar' && classroom.avatarUrl ? (
          <ImagePositioner
            src={classroom.avatarUrl}
            value={classroom.avatarPosition}
            frameClassName="h-32 w-32"
            label="Căn ảnh đại diện"
            saving={positionSaving}
            onSave={(pos) => handleSavePosition('avatar', pos)}
            onCancel={() => setPositioning(null)}
          />
        ) : (
          <div className="flex items-center gap-4">
            {classroom.avatarUrl ? (
              <img
                src={classroom.avatarUrl}
                alt={`Ảnh đại diện của lớp ${classroom.title}`}
                className="h-24 w-24 flex-shrink-0 rounded-[20px] border border-slate-200 object-cover"
                style={{ objectPosition: classroom.avatarPosition || '50% 50%' }}
              />
            ) : (
              <ClassAvatar title={classroom.title} seed={classroom.id} size={96} className="!rounded-[20px]" />
            )}
            <p className="text-meta text-slate-600">
              {classroom.avatarUrl ? 'Ảnh vuông hiển thị ở nhiều cỡ nhỏ; căn để phần quan trọng nằm giữa.' : 'Chưa có ảnh đại diện: lớp đang dùng ô chữ cái đầu của tên lớp.'}
            </p>
          </div>
        )}
        {imageError?.kind === 'avatar' && <p role="alert" className="text-meta font-medium text-red-600">{imageError.text}</p>}
        {canEdit ? (
          <div className="flex flex-wrap items-center gap-2">
            <label
              className={buttonClass('secondary', 'md', `cursor-pointer focus-within:ring-2 focus-within:ring-blue-600 focus-within:ring-offset-2 ${avatarBusy ? 'pointer-events-none opacity-60' : ''}`)}
            >
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp,image/gif"
                className="sr-only"
                disabled={avatarBusy}
                onChange={(e) => { void handleUploadImage('avatar', e.target.files?.[0]); e.target.value = ''; }}
              />
              <Upload className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
              <span>{avatarBusy ? 'Đang tải ảnh...' : classroom.avatarUrl ? 'Đổi ảnh đại diện' : 'Tải ảnh đại diện lên'}</span>
            </label>
            {classroom.avatarUrl && positioning !== 'avatar' && (
              <Button variant="ghost" size="md" disabled={avatarBusy} onClick={() => setPositioning('avatar')}>
                <Crop className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                Căn ảnh đại diện
              </Button>
            )}
            {classroom.avatarUrl && (
              <Button variant="ghost" size="md" disabled={avatarBusy} onClick={() => handleRemoveImage('avatar')}>
                <Trash2 className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                Gỡ ảnh đại diện
              </Button>
            )}
          </div>
        ) : (
          <p className="text-meta text-slate-600">Bạn cần quyền CLASS:EDIT để đổi ảnh đại diện.</p>
        )}
      </Card>

      <form onSubmit={handleSave} aria-labelledby="info-section-title" className="space-y-5 rounded-card border border-slate-200 bg-white p-5 shadow-hairline sm:p-6">
        <CardHeader id="info-section-title" title="Thông tin lớp" description="Tên và mô tả là điều đầu tiên người mới đọc về lớp của bạn." />
        <Field label="Tên lớp học" htmlFor={titleFieldId}>
          <Input
            id={titleFieldId}
            type="text"
            required
            disabled={!canEdit}
            value={title}
            onChange={(e) => setTitle(e.target.value)}
          />
        </Field>

        <Field label="Mô tả" htmlFor={descriptionFieldId} hint="Lớp dành cho ai, học xong làm được gì, bạn đồng hành thế nào mỗi tuần.">
          <Textarea
            id={descriptionFieldId}
            rows={5}
            disabled={!canEdit}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
        </Field>

        <fieldset disabled={!canEdit} className="space-y-2">
          <legend className="text-meta font-semibold text-slate-900">Chủ đề</legend>
          <p className="text-caption text-slate-500">Giúp người mới tìm thấy lớp khi lọc theo chủ đề ở trang khám phá.</p>
          <div className="flex flex-wrap gap-2" role="group" aria-label="Chủ đề lớp">
            {categoryOptions.map((c) => (
              <FilterChip key={c} selected={category === c} onClick={() => setCategory(c)} disabled={!canEdit}>
                {c}
              </FilterChip>
            ))}
          </div>
        </fieldset>

        <Field label="Ảnh bìa (URL)" htmlFor={coverImageFieldId} hint="Không bắt buộc. Ảnh đã tải lên ở trên được ưu tiên hơn địa chỉ này.">
          <Input
            id={coverImageFieldId}
            type="text"
            disabled={!canEdit}
            value={coverImageUrl}
            onChange={(e) => setCoverImageUrl(e.target.value)}
            placeholder="https://..."
          />
        </Field>

        {canEdit && (
          <div className="flex justify-end">
            <Button type="submit" variant="primary" size="md" disabled={saving}>
              <Save className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
              <span>{saving ? 'Đang lưu...' : 'Lưu thay đổi'}</span>
            </Button>
          </div>
        )}
      </form>

      {/* D-19: Hiển thị & tham gia */}
      <Card as="section" aria-labelledby="visibility-section-title" className="space-y-4">
        <CardHeader
          id="visibility-section-title"
          icon={<Eye className="h-[18px] w-[18px] text-slate-500" strokeWidth={1.75} aria-hidden="true" />}
          title="Hiển thị & tham gia"
          description="Ai tìm thấy được lớp và lớp được vào bằng cách nào."
          action={<ClassBadges classroom={classroom} showPublic />}
        />

        {visibilityError && <ErrorBanner message={visibilityError} />}

        <fieldset disabled={!canEdit || visibilityBusy} className="space-y-2">
          <legend className="mb-2 text-meta font-semibold text-slate-900">Chế độ hiển thị</legend>
          {([
            { value: 'PUBLIC' as const, label: 'Công khai', hint: 'Hiện trong danh sách khám phá; ai cũng có thể tìm thấy và tham gia.', Icon: Globe },
            { value: 'PRIVATE' as const, label: 'Riêng tư', hint: 'Ẩn khỏi khám phá; chỉ người có liên kết mời (tạo ở trang Thành viên) mới vào được.', Icon: Lock },
          ]).map(({ value, label, hint, Icon }) => (
            <label key={value} className={radioCardClass(visibilityChoice === value, canEdit)}>
              <input
                type="radio"
                name={visibilityGroupName}
                value={value}
                checked={visibilityChoice === value}
                onChange={() => setVisibilityChoice(value)}
                className="mt-0.5 h-[18px] w-[18px] flex-shrink-0 accent-blue-600"
              />
              <span className="min-w-0">
                <span className="flex items-center gap-1.5 text-ui font-semibold text-slate-900">
                  <Icon className="h-4 w-4 text-slate-500" strokeWidth={1.75} aria-hidden="true" />
                  {label}
                </span>
                <span className="mt-0.5 block text-meta text-slate-600">{hint}</span>
              </span>
            </label>
          ))}
        </fieldset>

        {canEdit && visibilityChoice !== currentVisibility && (
          <Notice tone="warn" role="group" aria-label="Xác nhận đổi chế độ hiển thị">
            <p className="font-medium">
              {visibilityChoice === 'PRIVATE'
                ? 'Chuyển sang Riêng tư: lớp bị ẩn khỏi danh sách khám phá và người ngoài không còn truy cập được bằng địa chỉ lớp. Thành viên hiện tại giữ nguyên quyền; người mới chỉ vào được bằng liên kết mời.'
                : 'Chuyển sang Công khai: lớp hiện trong danh sách khám phá và ai cũng có thể tham gia (các liên kết mời còn hiệu lực vẫn dùng được nhưng không còn cần thiết).'}
            </p>
            <div className="mt-3 flex flex-wrap gap-2">
              <Button size="sm" variant="secondary" onClick={() => setVisibilityChoice(currentVisibility)}>
                Hủy
              </Button>
              <Button size="sm" variant="primary" disabled={visibilityBusy} onClick={handleChangeVisibility}>
                {visibilityBusy ? 'Đang lưu...' : 'Xác nhận đổi chế độ'}
              </Button>
            </div>
          </Notice>
        )}
        <div className="flex items-start justify-between gap-4 rounded-[14px] border border-slate-200 p-3.5" data-testid="approval-row">
          <div className="flex min-w-0 items-start gap-3">
            <UserCheck className="mt-0.5 h-[18px] w-[18px] flex-shrink-0 text-slate-500" strokeWidth={1.75} aria-hidden="true" />
            <div className="min-w-0">
              <p className="text-ui font-semibold text-slate-900">Duyệt từng người trước khi vào</p>
              <p className="mt-0.5 text-meta text-slate-600">
                Người xin vào lớp công khai miễn phí phải chờ bạn duyệt ở trang Thành viên. Người vào bằng liên kết mời hoặc mua gói vào lớp không cần duyệt.
              </p>
              {requireApproval && (classroom.pendingRequestCount ?? 0) > 0 && (
                <p className="mt-1 text-meta font-medium text-amber-800">
                  {`Đang có ${classroom.pendingRequestCount} yêu cầu chờ duyệt. Tắt duyệt không tự nhận họ vào lớp: yêu cầu vẫn chờ đến khi bạn xử lý.`}
                </p>
              )}
            </div>
          </div>
          <Toggle
            checked={requireApproval}
            onChange={(next) => void handleToggleApproval(next)}
            label="Duyệt từng người trước khi vào"
            disabled={!canEdit || approvalBusy}
          />
        </div>
        {approvalError && <ErrorBanner message={approvalError} />}

        {!canEdit && <p className="text-meta text-slate-600">Bạn cần quyền CLASS:EDIT để đổi chế độ hiển thị.</p>}
      </Card>

      <Card as="section" aria-labelledby="access-section-title" className="space-y-4">
        <CardHeader
          id="access-section-title"
          icon={<Coins className="h-[18px] w-[18px] text-slate-500" strokeWidth={1.75} aria-hidden="true" />}
          title="Hình thức vào lớp (thu phí)"
          description="Miễn phí hoặc bán gói vào lớp theo thời hạn."
        />

        <div data-testid="access-summary" className="rounded-btn border border-slate-200 bg-slate-50 px-3.5 py-3 text-meta text-slate-600">
          {currentAccess === 'PAID' && classroom.accessProduct ? (
            <>
              Gói vào lớp hiện tại: <strong className="font-semibold text-slate-900 tabular">{accessPriceLabel(classroom.accessProduct.price, classroom.accessProduct.durationDays, classroom.accessProduct.lifetime)}</strong>.
              Đổi giá hoặc thời hạn chỉ ảnh hưởng đơn mua mới.
            </>
          ) : (
            <>Lớp đang <strong className="font-semibold text-slate-900">miễn phí</strong>: ai đủ điều kiện (lớp công khai hoặc có liên kết mời) đều vào được.</>
          )}
        </div>

        {accessError && <ErrorBanner message={accessError} />}

        <fieldset disabled={!canEditAccess || accessBusy} className="space-y-2">
          <legend className="mb-2 text-meta font-semibold text-slate-900">Hình thức</legend>
          {([
            { value: 'FREE' as const, label: 'Miễn phí', hint: 'Mọi người vào lớp không phải trả tiền.', Icon: Gift },
            { value: 'PAID' as const, label: 'Trả phí', hint: 'Người mới mua gói vào lớp (VND) để trở thành thành viên.', Icon: Coins },
          ]).map(({ value, label, hint, Icon }) => (
            <label key={value} className={radioCardClass(accessChoice === value, canEditAccess)}>
              <input
                type="radio"
                name={accessGroupName}
                value={value}
                checked={accessChoice === value}
                onChange={() => { setAccessChoice(value); setConfirmingAccess(false); }}
                className="mt-0.5 h-[18px] w-[18px] flex-shrink-0 accent-blue-600"
              />
              <span className="min-w-0">
                <span className="flex items-center gap-1.5 text-ui font-semibold text-slate-900">
                  <Icon className="h-4 w-4 text-slate-500" strokeWidth={1.75} aria-hidden="true" />
                  {label}
                </span>
                <span className="mt-0.5 block text-meta text-slate-600">{hint}</span>
              </span>
            </label>
          ))}

          {accessChoice === 'PAID' && (
            <div className="grid gap-4 pt-2 sm:grid-cols-2">
              <Field label="Giá vào lớp (VND)" htmlFor={priceFieldId}>
                <Input
                  id={priceFieldId}
                  type="number"
                  inputMode="numeric"
                  min={1}
                  step={1000}
                  value={priceInput}
                  onChange={(e) => { setPriceInput(e.target.value); setConfirmingAccess(false); }}
                  placeholder="199000"
                  className="tabular"
                />
                {priceValid && <p data-testid="price-hint" className="text-caption text-slate-500 tabular">= {formatDong(parsedPrice)}</p>}
              </Field>
              <Field label="Thời hạn (ngày)" htmlFor={daysFieldId}>
                <Input
                  id={daysFieldId}
                  type="number"
                  inputMode="numeric"
                  min={1}
                  max={MAX_ACCESS_DAYS}
                  disabled={lifetime}
                  value={lifetime ? '' : daysInput}
                  onChange={(e) => { setDaysInput(e.target.value); setConfirmingAccess(false); }}
                  className="tabular"
                />
                <div className="flex items-center gap-2 pt-1">
                  <input
                    id={lifetimeFieldId}
                    type="checkbox"
                    checked={lifetime}
                    onChange={(e) => { setLifetime(e.target.checked); setConfirmingAccess(false); }}
                    className="h-4 w-4 accent-blue-600"
                  />
                  <label htmlFor={lifetimeFieldId} className="text-meta font-medium text-slate-900">Trọn đời (không hết hạn)</label>
                </div>
              </Field>
            </div>
          )}
        </fieldset>

        {canEditAccess ? (
          !confirmingAccess ? (
            <div className="flex justify-end">
              <Button variant="secondary" size="md" disabled={accessBusy || accessUnchanged} onClick={requestAccessChange}>
                <Save className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                <span>Lưu hình thức thu phí</span>
              </Button>
            </div>
          ) : (
            <Notice tone="warn" role="group" aria-label="Xác nhận đổi hình thức thu phí">
              <p className="font-medium">{accessConsequence()}</p>
              <div className="mt-3 flex flex-wrap gap-2">
                <Button size="sm" variant="secondary" onClick={() => setConfirmingAccess(false)}>
                  Hủy
                </Button>
                <Button size="sm" variant="primary" disabled={accessBusy} onClick={handleChangeAccess}>
                  {accessBusy ? 'Đang lưu...' : 'Xác nhận thay đổi'}
                </Button>
              </div>
            </Notice>
          )
        ) : (
          <p className="text-meta text-slate-600">
            Chỉ chủ lớp, hoặc nhân sự có đồng thời quyền CLASS:EDIT và STORE:EDIT, mới đổi được hình thức thu phí.
          </p>
        )}
      </Card>

      {isOwner && (
        <Card as="section" aria-labelledby="danger-section-title" className="space-y-3 border-red-200">
          <CardHeader
            id="danger-section-title"
            title="Vùng nguy hiểm"
            description={isArchived
              ? 'Lớp học đang được lưu trữ (đóng): học viên mới không thể tham gia và lớp bị ẩn khỏi danh sách công khai. Thành viên hiện tại vẫn giữ quyền truy cập đã có.'
              : 'Lưu trữ (đóng) lớp học sẽ ẩn lớp khỏi danh sách công khai và ngăn học viên mới tham gia. Thành viên hiện tại không bị ảnh hưởng.'}
          />

          {!showArchiveConfirm ? (
            <Button variant={isArchived ? 'secondary' : 'danger'} size="md" onClick={() => setShowArchiveConfirm(true)}>
              {isArchived ? <ArchiveRestore className="h-4 w-4" strokeWidth={1.75} /> : <Archive className="h-4 w-4" strokeWidth={1.75} />}
              <span>{isArchived ? 'Mở lại lớp học' : 'Lưu trữ (đóng) lớp học'}</span>
            </Button>
          ) : (
            <div className="space-y-3 rounded-btn border border-red-200 bg-red-50 p-4">
              <p className="text-meta font-medium text-red-700">
                {isArchived
                  ? 'Xác nhận mở lại lớp học? Lớp sẽ hiển thị công khai trở lại.'
                  : 'Xác nhận lưu trữ (đóng) lớp học? Học viên mới sẽ không thể tham gia cho đến khi bạn mở lại.'}
              </p>
              <div className="flex flex-wrap gap-2">
                <Button size="sm" variant="secondary" onClick={() => setShowArchiveConfirm(false)}>
                  Hủy
                </Button>
                <Button
                  size="sm"
                  variant={isArchived ? 'primary' : 'danger'}
                  disabled={archiving}
                  onClick={handleToggleArchive}
                  className={isArchived ? '' : '!border-red-600 !bg-red-600 !text-white hover:!bg-red-700'}
                >
                  {archiving ? 'Đang xử lý...' : 'Xác nhận'}
                </Button>
              </div>
            </div>
          )}
        </Card>
      )}
    </StudioPage>
  );
};
