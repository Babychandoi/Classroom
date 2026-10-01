import React, { useEffect, useId, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, ClassAccessType, ClassVisibility } from '../../types';
import { api } from '../../api/client';
import { accessPriceLabel, durationLabel, formatDong } from '../../api/format';
import { ErrorBanner } from '../../components/UIStates';
import { ClassBadges } from '../../components/ClassBadges';
import { hasStudioPermission } from '../../api/permissions';
import { Archive, ArchiveRestore, Coins, Eye, Gift, Globe, Lock, Save } from 'lucide-react';

// D-19: PUT /classes/{id}/access accepts 1..3650 days; no duration = lifetime.
const MAX_ACCESS_DAYS = 3650;
const DEFAULT_ACCESS_DAYS = 30;

/**
 * R13-02: Studio "Cài đặt lớp" (FR-14 / sitemap /studio/classes/:id/settings).
 * Edit form is gated on CLASS:EDIT (OWNER always satisfies it); archive/unarchive is OWNER-only,
 * matching ClassroomService#updateClassroomStatus's authorization (a much wider blast radius than
 * a plain field edit - it flips visibility/joinability for the whole class).
 */
export const StudioSettings: React.FC = () => {
  const { classroom, refreshClassroom } = useOutletContext<{ classroom: Classroom; refreshClassroom: () => Promise<void> }>();
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

  return (
    <div className="max-w-3xl mx-auto space-y-6">
      <div>
        <h1 className="text-2xl font-black text-slate-900 tracking-tight">Cài đặt lớp học</h1>
        <p className="text-xs text-slate-600">Chỉnh sửa thông tin hiển thị và trạng thái hoạt động của lớp học</p>
      </div>

      {error && <ErrorBanner message={error} />}
      {message && <p role="status" className="text-xs font-semibold text-emerald-700">{message}</p>}

      <form onSubmit={handleSave} className="bg-white rounded-2xl border border-slate-200 shadow-sm p-6 space-y-4">
        <div>
          <label htmlFor={titleFieldId} className="block text-xs font-semibold text-slate-700 uppercase">
            Tên lớp học
          </label>
          <input
            id={titleFieldId}
            type="text"
            required
            disabled={!canEdit}
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-sm focus:ring-2 focus:ring-indigo-500 disabled:opacity-60"
          />
        </div>

        <div>
          <label htmlFor={descriptionFieldId} className="block text-xs font-semibold text-slate-700 uppercase">
            Mô tả
          </label>
          <textarea
            id={descriptionFieldId}
            rows={5}
            disabled={!canEdit}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-sm focus:ring-2 focus:ring-indigo-500 disabled:opacity-60"
          />
        </div>

        <div>
          <label htmlFor={coverImageFieldId} className="block text-xs font-semibold text-slate-700 uppercase">
            Ảnh bìa (URL)
          </label>
          <input
            id={coverImageFieldId}
            type="text"
            disabled={!canEdit}
            value={coverImageUrl}
            onChange={(e) => setCoverImageUrl(e.target.value)}
            placeholder="https://..."
            className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-sm focus:ring-2 focus:ring-indigo-500 disabled:opacity-60"
          />
        </div>

        {canEdit && (
          <div className="flex justify-end">
            <button
              type="submit"
              disabled={saving}
              className="inline-flex items-center space-x-1.5 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold shadow-sm transition disabled:opacity-50"
            >
              <Save className="w-4 h-4" />
              <span>{saving ? 'Đang lưu...' : 'Lưu thay đổi'}</span>
            </button>
          </div>
        )}
      </form>

      {/* D-19: Hiển thị & tham gia */}
      <section aria-labelledby="visibility-section-title" className="bg-white rounded-2xl border border-slate-200 shadow-sm p-6 space-y-4">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div>
            <h2 id="visibility-section-title" className="text-sm font-bold text-slate-900 flex items-center gap-2">
              <Eye className="w-4 h-4 text-indigo-600" aria-hidden="true" />
              Hiển thị & tham gia
            </h2>
            <p className="text-xs text-slate-600 mt-0.5">Ai tìm thấy được lớp và lớp được vào bằng cách nào.</p>
          </div>
          <ClassBadges classroom={classroom} showPublic />
        </div>

        {visibilityError && <ErrorBanner message={visibilityError} />}

        <fieldset disabled={!canEdit || visibilityBusy} className="space-y-2">
          <legend className="text-xs font-semibold text-slate-700 uppercase mb-2">Chế độ hiển thị</legend>
          {([
            { value: 'PUBLIC' as const, label: 'Công khai', hint: 'Hiện trong danh sách khám phá; ai cũng có thể tìm thấy và tham gia.', Icon: Globe },
            { value: 'PRIVATE' as const, label: 'Riêng tư', hint: 'Ẩn khỏi khám phá; chỉ người có liên kết mời (tạo ở trang Thành viên) mới vào được.', Icon: Lock },
          ]).map(({ value, label, hint, Icon }) => (
            <label
              key={value}
              className={`flex items-start gap-3 p-3 border rounded-xl transition ${canEdit ? 'cursor-pointer' : 'cursor-not-allowed opacity-70'} ${
                visibilityChoice === value ? 'border-indigo-500 bg-indigo-50' : 'border-slate-300 bg-white hover:bg-slate-50'
              }`}
            >
              <input
                type="radio"
                name={visibilityGroupName}
                value={value}
                checked={visibilityChoice === value}
                onChange={() => setVisibilityChoice(value)}
                className="mt-1 h-4 w-4 accent-indigo-600"
              />
              <span className="min-w-0">
                <span className="flex items-center gap-1.5 text-sm font-bold text-slate-900">
                  <Icon className="w-3.5 h-3.5" aria-hidden="true" />
                  {label}
                </span>
                <span className="block text-xs text-slate-600 mt-0.5">{hint}</span>
              </span>
            </label>
          ))}
        </fieldset>

        {canEdit && visibilityChoice !== currentVisibility && (
          <div role="group" aria-label="Xác nhận đổi chế độ hiển thị" className="p-4 bg-amber-50 border border-amber-200 rounded-xl space-y-3">
            <p className="text-xs font-semibold text-amber-900">
              {visibilityChoice === 'PRIVATE'
                ? 'Chuyển sang Riêng tư: lớp bị ẩn khỏi danh sách khám phá và người ngoài không còn truy cập được bằng địa chỉ lớp. Thành viên hiện tại giữ nguyên quyền; người mới chỉ vào được bằng liên kết mời.'
                : 'Chuyển sang Công khai: lớp hiện trong danh sách khám phá và ai cũng có thể tham gia (các liên kết mời còn hiệu lực vẫn dùng được nhưng không còn cần thiết).'}
            </p>
            <div className="flex space-x-2">
              <button
                type="button"
                onClick={() => setVisibilityChoice(currentVisibility)}
                className="px-3 py-1.5 border border-slate-300 bg-white text-slate-700 rounded-xl text-xs font-semibold"
              >
                Hủy
              </button>
              <button
                type="button"
                disabled={visibilityBusy}
                onClick={handleChangeVisibility}
                className="px-3 py-1.5 bg-amber-700 hover:bg-amber-800 text-white rounded-xl text-xs font-bold disabled:opacity-50"
              >
                {visibilityBusy ? 'Đang lưu...' : 'Xác nhận đổi chế độ'}
              </button>
            </div>
          </div>
        )}
        {!canEdit && <p className="text-xs text-slate-600">Bạn cần quyền CLASS:EDIT để đổi chế độ hiển thị.</p>}
      </section>

      <section aria-labelledby="access-section-title" className="bg-white rounded-2xl border border-slate-200 shadow-sm p-6 space-y-4">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div>
            <h2 id="access-section-title" className="text-sm font-bold text-slate-900 flex items-center gap-2">
              <Coins className="w-4 h-4 text-indigo-600" aria-hidden="true" />
              Hình thức vào lớp (thu phí)
            </h2>
            <p className="text-xs text-slate-600 mt-0.5">Miễn phí hoặc bán gói vào lớp theo thời hạn.</p>
          </div>
        </div>

        <div data-testid="access-summary" className="text-xs text-slate-700 bg-slate-50 border border-slate-200 rounded-xl px-3 py-2">
          {currentAccess === 'PAID' && classroom.accessProduct ? (
            <>
              Gói vào lớp hiện tại: <strong>{accessPriceLabel(classroom.accessProduct.price, classroom.accessProduct.durationDays, classroom.accessProduct.lifetime)}</strong>.
              Đổi giá hoặc thời hạn chỉ ảnh hưởng đơn mua mới.
            </>
          ) : (
            <>Lớp đang <strong>miễn phí</strong>: ai đủ điều kiện (lớp công khai hoặc có liên kết mời) đều vào được.</>
          )}
        </div>

        {accessError && <ErrorBanner message={accessError} />}

        <fieldset disabled={!canEditAccess || accessBusy} className="space-y-2">
          <legend className="text-xs font-semibold text-slate-700 uppercase mb-2">Hình thức</legend>
          {([
            { value: 'FREE' as const, label: 'Miễn phí', hint: 'Mọi người vào lớp không phải trả tiền.', Icon: Gift },
            { value: 'PAID' as const, label: 'Trả phí', hint: 'Người mới mua gói vào lớp (VND) để trở thành thành viên.', Icon: Coins },
          ]).map(({ value, label, hint, Icon }) => (
            <label
              key={value}
              className={`flex items-start gap-3 p-3 border rounded-xl transition ${canEditAccess ? 'cursor-pointer' : 'cursor-not-allowed opacity-70'} ${
                accessChoice === value ? 'border-indigo-500 bg-indigo-50' : 'border-slate-300 bg-white hover:bg-slate-50'
              }`}
            >
              <input
                type="radio"
                name={accessGroupName}
                value={value}
                checked={accessChoice === value}
                onChange={() => { setAccessChoice(value); setConfirmingAccess(false); }}
                className="mt-1 h-4 w-4 accent-indigo-600"
              />
              <span className="min-w-0">
                <span className="flex items-center gap-1.5 text-sm font-bold text-slate-900">
                  <Icon className="w-3.5 h-3.5" aria-hidden="true" />
                  {label}
                </span>
                <span className="block text-xs text-slate-600 mt-0.5">{hint}</span>
              </span>
            </label>
          ))}

          {accessChoice === 'PAID' && (
            <div className="grid gap-3 sm:grid-cols-2 pt-2">
              <div>
                <label htmlFor={priceFieldId} className="block text-xs font-semibold text-slate-700 uppercase">Giá vào lớp (VND)</label>
                <input
                  id={priceFieldId}
                  type="number"
                  inputMode="numeric"
                  min={1}
                  step={1000}
                  value={priceInput}
                  onChange={(e) => { setPriceInput(e.target.value); setConfirmingAccess(false); }}
                  placeholder="199000"
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-sm focus:ring-2 focus:ring-indigo-500 disabled:opacity-60"
                />
                {priceValid && <p data-testid="price-hint" className="mt-1 text-[11px] text-slate-600">= {formatDong(parsedPrice)}</p>}
              </div>
              <div>
                <label htmlFor={daysFieldId} className="block text-xs font-semibold text-slate-700 uppercase">Thời hạn (ngày)</label>
                <input
                  id={daysFieldId}
                  type="number"
                  inputMode="numeric"
                  min={1}
                  max={MAX_ACCESS_DAYS}
                  disabled={lifetime}
                  value={lifetime ? '' : daysInput}
                  onChange={(e) => { setDaysInput(e.target.value); setConfirmingAccess(false); }}
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-sm focus:ring-2 focus:ring-indigo-500 disabled:opacity-60"
                />
                <div className="mt-2 flex items-center gap-2">
                  <input
                    id={lifetimeFieldId}
                    type="checkbox"
                    checked={lifetime}
                    onChange={(e) => { setLifetime(e.target.checked); setConfirmingAccess(false); }}
                    className="h-4 w-4 accent-indigo-600"
                  />
                  <label htmlFor={lifetimeFieldId} className="text-xs font-semibold text-slate-700">Trọn đời (không hết hạn)</label>
                </div>
              </div>
            </div>
          )}
        </fieldset>

        {canEditAccess ? (
          !confirmingAccess ? (
            <div className="flex justify-end">
              <button
                type="button"
                disabled={accessBusy || accessUnchanged}
                onClick={requestAccessChange}
                className="inline-flex items-center space-x-1.5 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold shadow-sm transition disabled:opacity-50"
              >
                <Save className="w-4 h-4" />
                <span>Lưu hình thức thu phí</span>
              </button>
            </div>
          ) : (
            <div role="group" aria-label="Xác nhận đổi hình thức thu phí" className="p-4 bg-amber-50 border border-amber-200 rounded-xl space-y-3">
              <p className="text-xs font-semibold text-amber-900">{accessConsequence()}</p>
              <div className="flex space-x-2">
                <button
                  type="button"
                  onClick={() => setConfirmingAccess(false)}
                  className="px-3 py-1.5 border border-slate-300 bg-white text-slate-700 rounded-xl text-xs font-semibold"
                >
                  Hủy
                </button>
                <button
                  type="button"
                  disabled={accessBusy}
                  onClick={handleChangeAccess}
                  className="px-3 py-1.5 bg-amber-700 hover:bg-amber-800 text-white rounded-xl text-xs font-bold disabled:opacity-50"
                >
                  {accessBusy ? 'Đang lưu...' : 'Xác nhận thay đổi'}
                </button>
              </div>
            </div>
          )
        ) : (
          <p className="text-xs text-slate-600">
            Chỉ chủ lớp, hoặc nhân sự có đồng thời quyền CLASS:EDIT và STORE:EDIT, mới đổi được hình thức thu phí.
          </p>
        )}
      </section>

      {isOwner && (
        <div className="bg-white rounded-2xl border border-rose-200 shadow-sm p-6 space-y-3">
          <h2 className="text-sm font-bold text-slate-900">Vùng nguy hiểm</h2>
          <p className="text-xs text-slate-600">
            {isArchived
              ? 'Lớp học đang được lưu trữ (đóng): học viên mới không thể tham gia và lớp bị ẩn khỏi danh sách công khai. Thành viên hiện tại vẫn giữ quyền truy cập đã có.'
              : 'Lưu trữ (đóng) lớp học sẽ ẩn lớp khỏi danh sách công khai và ngăn học viên mới tham gia. Thành viên hiện tại không bị ảnh hưởng.'}
          </p>

          {!showArchiveConfirm ? (
            <button
              type="button"
              onClick={() => setShowArchiveConfirm(true)}
              className={`inline-flex items-center space-x-1.5 px-4 py-2 rounded-xl text-xs font-bold shadow-sm transition ${
                isArchived
                  ? 'bg-emerald-600 hover:bg-emerald-700 text-white'
                  : 'bg-rose-600 hover:bg-rose-700 text-white'
              }`}
            >
              {isArchived ? <ArchiveRestore className="w-4 h-4" /> : <Archive className="w-4 h-4" />}
              <span>{isArchived ? 'Mở lại lớp học' : 'Lưu trữ (đóng) lớp học'}</span>
            </button>
          ) : (
            <div className="p-4 bg-rose-50 border border-rose-200 rounded-xl space-y-3">
              <p className="text-xs font-semibold text-rose-800">
                {isArchived
                  ? 'Xác nhận mở lại lớp học? Lớp sẽ hiển thị công khai trở lại.'
                  : 'Xác nhận lưu trữ (đóng) lớp học? Học viên mới sẽ không thể tham gia cho đến khi bạn mở lại.'}
              </p>
              <div className="flex space-x-2">
                <button
                  type="button"
                  onClick={() => setShowArchiveConfirm(false)}
                  className="px-3 py-1.5 border border-slate-300 text-slate-700 rounded-xl text-xs font-semibold"
                >
                  Hủy
                </button>
                <button
                  type="button"
                  disabled={archiving}
                  onClick={handleToggleArchive}
                  className="px-3 py-1.5 bg-rose-600 hover:bg-rose-700 text-white rounded-xl text-xs font-bold disabled:opacity-50"
                >
                  {archiving ? 'Đang xử lý...' : 'Xác nhận'}
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};
