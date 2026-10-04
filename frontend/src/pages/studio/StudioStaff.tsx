import React, { useEffect, useId, useMemo, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, Course, StaffAssignment } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { Modal } from '../../components/Modal';
import { COURSE_SCOPABLE_MODULES } from '../../api/permissions';
import { Avatar, Button, Card, Field, Input, Select, inputClass } from '../../components/ui';
import { EmptyRow, ModalActions, PageHeader, StudioPage, iconActionClass, rowActionClass } from './studioUi';
import { UserPlus, Trash2, Pencil } from 'lucide-react';

interface MemberItem {
  id: string;
  userId: string;
  role: string;
  state: string;
  joinedAt: string;
  userFullName?: string | null;
  userAvatarUrl?: string | null;
}

type Permission = StaffAssignment['permissions'][number];
const PERMISSION_OPTIONS: { module: string; actions: string[] }[] = [
  { module: 'STUDIO', actions: ['VIEW'] },
  { module: 'CLASS', actions: ['VIEW', 'EDIT'] },
  { module: 'FEED', actions: ['VIEW', 'CREATE', 'EDIT', 'DELETE', 'PUBLISH'] },
  { module: 'COURSE', actions: ['VIEW', 'CREATE', 'EDIT', 'DELETE', 'PUBLISH', 'PREVIEW', 'GRADE'] },
  { module: 'EXAM', actions: ['VIEW', 'CREATE', 'EDIT', 'DELETE', 'PUBLISH', 'GRADE', 'PREVIEW'] },
  { module: 'SEGMENT', actions: ['VIEW', 'CREATE', 'EDIT', 'DELETE'] },
  { module: 'MEMBER', actions: ['VIEW', 'EDIT'] },
  { module: 'STORE', actions: ['VIEW', 'CREATE', 'EDIT', 'DELETE', 'PUBLISH'] },
  { module: 'DOCUMENT', actions: ['VIEW', 'CREATE', 'EDIT', 'DELETE'] },
  { module: 'MEDIA', actions: ['CREATE'] },
  { module: 'ABOUT', actions: ['VIEW', 'EDIT', 'PUBLISH'] },
  { module: 'BLOG', actions: ['VIEW', 'CREATE', 'EDIT', 'PUBLISH', 'DELETE'] },
  { module: 'EVENT', actions: ['VIEW', 'CREATE', 'EDIT', 'DELETE'] },
];

// Vietnamese names of the permission modules/actions in the editor (the stored grant stays "MODULE:ACTION").
const MODULE_LABELS: Record<string, string> = {
  STUDIO: 'Tổng quan Xưởng',
  CLASS: 'Lớp học',
  FEED: 'Bảng tin',
  COURSE: 'Khóa học',
  EXAM: 'Thi',
  SEGMENT: 'Nhóm học viên',
  MEMBER: 'Thành viên',
  STORE: 'Shop & đơn hàng',
  DOCUMENT: 'Tài liệu',
  MEDIA: 'Tệp tải lên',
  ABOUT: 'Giới thiệu',
  BLOG: 'Blog',
  EVENT: 'Sự kiện',
};
const ACTION_LABELS: Record<string, string> = {
  VIEW: 'Xem',
  CREATE: 'Tạo',
  EDIT: 'Sửa',
  DELETE: 'Xóa',
  PUBLISH: 'Xuất bản',
  PREVIEW: 'Chạy thử',
  GRADE: 'Chấm bài',
};

export const StudioStaff: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();

  const [staffList, setStaffList] = useState<StaffAssignment[]>([]);
  const [courses, setCourses] = useState<Course[]>([]);
  const [members, setMembers] = useState<MemberItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // New Staff Modal
  const [showModal, setShowModal] = useState(false);
  const [targetUserId, setTargetUserId] = useState('');
  const [permissions, setPermissions] = useState<Permission[]>([]);
  const [editingUserId, setEditingUserId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [removeTarget, setRemoveTarget] = useState<StaffAssignment | null>(null);
  const [removing, setRemoving] = useState(false);
  const targetUserFieldId = useId();
  // R18-08: a course-scoped grant must say which course it is scoped to; class-wide grants stay bare.
  const courseTitleById = useMemo(() => new Map(courses.map((course) => [course.id, course.title])), [courses]);

  const fetchStaff = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.get<StaffAssignment[]>(`/classes/${classroom.id}/staff`);
      setStaffList(data || []);
      const courseData = await api.get<Course[]>(`/classes/${classroom.id}/courses`);
      setCourses(courseData || []);
      const memberData = await api.get<MemberItem[]>(`/classes/${classroom.id}/members`);
      setMembers(memberData || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải danh sách nhân sự');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchStaff();
  }, [classroom.id]);

  const handleAssignStaff = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!targetUserId.trim()) return;

    setSaving(true);
    setSaveError(null);
    try {
      await api.put(`/classes/${classroom.id}/staff/${targetUserId.trim()}/permissions`, permissions);
      setShowModal(false);
      setTargetUserId('');
      setEditingUserId(null);
      await fetchStaff();
    } catch (err: any) {
      setSaveError(err.message || 'Phân quyền thất bại');
    } finally {
      setSaving(false);
    }
  };

  const openEditor = (assignment?: StaffAssignment) => {
    setEditingUserId(assignment?.userId ?? null);
    setTargetUserId(assignment?.userId ?? '');
    setPermissions(assignment?.permissions.map(p => ({ module: p.module, action: p.action, scopeCourseId: p.scopeCourseId })) ?? []);
    setSaveError(null);
    setShowModal(true);
  };

  const togglePermission = (module: string, action: string, checked: boolean) => {
    setPermissions(current => checked
      ? [...current.filter(p => !(p.module === module && p.action === action && !p.scopeCourseId)), { module, action }]
      : current.filter(p => !(p.module === module && p.action === action && !p.scopeCourseId)));
  };

  const setCoursePermission = (module: string, action: string, courseId: string) => {
    setPermissions(current => [
      ...current.filter(p => !(p.module === module && p.action === action && p.scopeCourseId)),
      ...(courseId ? [{ module, action, scopeCourseId: courseId }] : []),
    ]);
  };

  const handleRemoveStaff = async (userId: string) => {
    setRemoving(true);
    setActionError(null);
    try {
      await api.delete(`/classes/${classroom.id}/staff/${userId}`);
      await fetchStaff();
    } catch (err: any) {
      setActionError(err.message || 'Xóa nhân sự thất bại');
    } finally {
      setRemoving(false);
      setRemoveTarget(null);
    }
  };

  const isOwner = classroom.userRole === 'OWNER';

  return (
    <StudioPage>
      <PageHeader
        title="Trợ giảng & phân quyền"
        description="Chỉ định trợ giảng từ thành viên của lớp và cấp quyền theo từng khu vực của Xưởng."
        action={isOwner && (
          <Button variant="primary" size="md" onClick={() => openEditor()}>
            <UserPlus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
            <span>Thêm trợ giảng mới</span>
          </Button>
        )}
      />

      {loading && <LoadingSpinner message="Đang tải danh sách nhân sự..." />}
      {error && <ErrorBanner message={error} onRetry={fetchStaff} />}
      {actionError && <ErrorBanner message={actionError} />}

      {!loading && !error && (
        <Card padded={false} className="overflow-hidden">
          {staffList.length === 0 && (
            <EmptyRow>
              Lớp chưa có trợ giảng nào. {isOwner ? 'Chọn một thành viên tích cực và cấp quyền chấm bài hoặc soạn nội dung để chia bớt việc.' : ''}
            </EmptyRow>
          )}
          <ul className="divide-y divide-slate-100">
            {staffList.map((st) => (
              <li key={st.id} className="flex flex-col gap-3 px-5 py-4 sm:flex-row sm:items-start sm:justify-between sm:px-6">
                <div className="flex min-w-0 items-start gap-3">
                  <Avatar name={st.userFullName || 'Trợ giảng'} size={36} />
                  <div className="min-w-0 space-y-1.5">
                    <div>
                      <span className="block text-ui font-semibold text-slate-900">{st.userFullName || 'Trợ giảng'}</span>
                      <span className="block truncate text-meta text-slate-500">{st.userEmail || st.userId}</span>
                    </div>
                    <div className="flex flex-wrap gap-1.5">
                      {st.permissions.length === 0 && <span className="text-meta text-slate-500">Chưa có quyền nào</span>}
                      {st.permissions.map((p, idx) => (
                        <span
                          key={idx}
                          className="inline-flex h-[22px] items-center whitespace-nowrap rounded-full bg-slate-100 px-2 font-mono text-micro font-semibold text-slate-600"
                        >
                          {`${p.module}:${p.action}${p.scopeCourseId ? ` · ${courseTitleById.get(p.scopeCourseId) ?? 'Khóa không còn hiển thị'}` : ''}`}
                        </span>
                      ))}
                    </div>
                  </div>
                </div>

                {isOwner && (
                  <div className="flex flex-shrink-0 gap-1">
                    <button type="button" onClick={() => openEditor(st)} className={rowActionClass()} title="Chỉnh sửa quyền" aria-label="Chỉnh sửa quyền">
                      <Pencil className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                      <span className="hidden sm:inline">Sửa quyền</span>
                    </button>
                    <button
                      type="button"
                      onClick={() => setRemoveTarget(st)}
                      className={iconActionClass('danger')}
                      title="Xóa quyền trợ giảng"
                      aria-label="Xóa quyền trợ giảng"
                    >
                      <Trash2 className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                    </button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        </Card>
      )}

      {/* Add Staff Modal */}
      {showModal && (
        <Modal size="md" title={editingUserId ? 'Chỉnh sửa quyền trợ giảng' : 'Phân quyền Trợ giảng'} onClose={() => setShowModal(false)}>
          <form onSubmit={handleAssignStaff} className="space-y-5">
            {saveError && <ErrorBanner message={saveError} />}
            <Field label="Thành viên" htmlFor={targetUserFieldId} hint="Chỉ có thể bổ nhiệm trợ giảng từ các thành viên đang hoạt động trong lớp.">
              {editingUserId ? (
                <Input id={targetUserFieldId} type="text" disabled value={targetUserId} className="font-mono" />
              ) : (
                <Select id={targetUserFieldId} required value={targetUserId} onChange={(e) => setTargetUserId(e.target.value)}>
                  <option value="">Chọn thành viên đang hoạt động trong lớp...</option>
                  {members
                    .filter(m => m.state === 'ACTIVE' && m.role !== 'STAFF' && m.role !== 'OWNER')
                    .map(m => (
                      <option key={m.userId} value={m.userId}>
                        {m.userFullName || m.userId}
                      </option>
                    ))}
                </Select>
              )}
            </Field>

            <div>
              <p className="mb-2 text-meta font-semibold text-slate-900">Quyền hạn cấp phát</p>
              <div className="divide-y divide-slate-100 rounded-2xl border border-slate-200">
                {PERMISSION_OPTIONS.map(group => (
                  <fieldset key={group.module} className="px-4 py-3">
                    <legend className="sr-only">{MODULE_LABELS[group.module] ?? group.module}</legend>
                    <div aria-hidden="true" className="mb-2 flex items-baseline justify-between gap-2">
                      <span className="text-ui font-semibold text-slate-900">{MODULE_LABELS[group.module] ?? group.module}</span>
                      <span className="font-mono text-micro text-slate-500">{group.module}</span>
                    </div>
                    <div className="grid grid-cols-2 gap-x-3 gap-y-1">
                      {group.actions.map(action => (
                        <label key={action} className="flex min-h-[32px] items-center gap-2 text-meta text-slate-600">
                          <input
                            type="checkbox"
                            checked={permissions.some(p => p.module === group.module && p.action === action && !p.scopeCourseId)}
                            onChange={e => togglePermission(group.module, action, e.target.checked)}
                            className="h-[18px] w-[18px] shrink-0 rounded accent-blue-600"
                          />
                          <span>{ACTION_LABELS[action] ?? action}</span>
                        </label>
                      ))}
                    </div>
                    {/* R7-01: server only ever evaluates a course scope for COURSE/EXAM
                        (AccessPolicy.canManage callers) — showing this picker for any other
                        module would let the owner grant a scoped permission that assignStaff now
                        rejects (400) and that would never authorize anything anyway. */}
                    {courses.length > 0 && COURSE_SCOPABLE_MODULES.has(group.module) && (
                      <div className="mt-3 space-y-1.5 rounded-btn bg-slate-50 p-3">
                        <p className="text-caption font-semibold uppercase tracking-[0.5px] text-slate-500">Cấp riêng cho một khóa</p>
                        {group.actions.map(action => (
                          <label key={`${action}-scope`} className="grid grid-cols-[88px_minmax(0,1fr)] items-center gap-2 text-meta text-slate-600">
                            <span>{ACTION_LABELS[action] ?? action} · khóa:</span>
                            <select
                              value={permissions.find(p => p.module === group.module && p.action === action && p.scopeCourseId)?.scopeCourseId ?? ''}
                              onChange={e => setCoursePermission(group.module, action, e.target.value)}
                              className={inputClass('h-9 pr-8 text-meta')}
                            >
                              <option value="">Không cấp riêng</option>
                              {courses.map(course => <option key={course.id} value={course.id}>{course.title}</option>)}
                            </select>
                          </label>
                        ))}
                      </div>
                    )}
                  </fieldset>
                ))}
              </div>
            </div>

            {/* R18-01: the panel scrolls, so the actions stick to its bottom edge and stay reachable. */}
            <div className="sticky bottom-0 -mx-6 -mb-6 flex justify-end gap-2 border-t border-slate-100 bg-white px-6 py-4 sm:-mx-7 sm:-mb-7 sm:px-7">
              <Button variant="secondary" onClick={() => setShowModal(false)}>
                Hủy
              </Button>
              <Button type="submit" variant="primary" disabled={saving}>
                {saving ? 'Đang lưu...' : 'Lưu quyền'}
              </Button>
            </div>
          </form>
        </Modal>
      )}

      {removeTarget && (
        <Modal size="sm" role="alertdialog" title="Gỡ vai trò trợ giảng?" onClose={() => setRemoveTarget(null)}>
          <p className="text-ui text-slate-600">
            {`${removeTarget.userFullName || 'Thành viên này'} sẽ không còn vào được Xưởng. Họ vẫn là thành viên của lớp.`}
          </p>
          <ModalActions className="mt-5">
            <Button variant="secondary" onClick={() => setRemoveTarget(null)}>Hủy</Button>
            <Button variant="danger" disabled={removing} onClick={() => handleRemoveStaff(removeTarget.userId)}>
              {removing ? 'Đang gỡ...' : 'Gỡ vai trò'}
            </Button>
          </ModalActions>
        </Modal>
      )}
    </StudioPage>
  );
};
