import React, { useEffect, useId, useMemo, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, Course, StaffAssignment } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { Modal } from '../../components/Modal';
import { COURSE_SCOPABLE_MODULES } from '../../api/permissions';
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
];

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
    try {
      await api.put(`/classes/${classroom.id}/staff/${targetUserId.trim()}/permissions`, permissions);
      setShowModal(false);
      setTargetUserId('');
      setEditingUserId(null);
      await fetchStaff();
    } catch (err: any) {
      alert(err.message || 'Phân quyền thất bại');
    } finally {
      setSaving(false);
    }
  };

  const openEditor = (assignment?: StaffAssignment) => {
    setEditingUserId(assignment?.userId ?? null);
    setTargetUserId(assignment?.userId ?? '');
    setPermissions(assignment?.permissions.map(p => ({ module: p.module, action: p.action, scopeCourseId: p.scopeCourseId })) ?? []);
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
    if (!confirm('Bạn có chắc muốn xóa vai trò trợ giảng của thành viên này?')) return;
    try {
      await api.delete(`/classes/${classroom.id}/staff/${userId}`);
      await fetchStaff();
    } catch (err: any) {
      alert(err.message || 'Xóa nhân sự thất bại');
    }
  };

  return (
    <div className="max-w-5xl mx-auto space-y-6">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-black text-slate-900 tracking-tight">Nhân sự & Phân quyền Studio</h1>
          <p className="text-xs text-slate-600">Chỉ định trợ giảng và cấp quyền theo từng phân hệ quản trị</p>
        </div>

        {classroom.userRole === 'OWNER' && (
          <button
            onClick={() => openEditor()}
            className="inline-flex items-center space-x-1.5 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold shadow-sm transition"
          >
            <UserPlus className="w-4 h-4" />
            <span>Thêm trợ giảng mới</span>
          </button>
        )}
      </div>

      {loading && <LoadingSpinner message="Đang tải danh sách nhân sự..." />}
      {error && <ErrorBanner message={error} onRetry={fetchStaff} />}

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm divide-y divide-slate-100">
        {staffList.map((st) => (
          <div key={st.id} className="p-5 flex items-center justify-between">
            <div className="space-y-1">
              <div className="flex items-center space-x-2">
                <span className="text-sm font-bold text-slate-900">{st.userFullName || 'Trợ giảng'}</span>
                <span className="text-xs text-slate-500 font-mono">({st.userEmail || st.userId})</span>
              </div>
              <div className="flex flex-wrap gap-1.5 pt-1">
                {st.permissions.map((p, idx) => (
                  <span
                    key={idx}
                    className="inline-flex items-center px-2 py-0.5 rounded text-[10px] font-bold bg-indigo-50 text-indigo-700 border border-indigo-100 font-mono"
                  >
                    {`${p.module}:${p.action}${p.scopeCourseId ? ` · ${courseTitleById.get(p.scopeCourseId) ?? 'Khóa không còn hiển thị'}` : ''}`}
                  </span>
                ))}
              </div>
            </div>

            {classroom.userRole === 'OWNER' && (
              <div className="flex gap-2">
              <button onClick={() => openEditor(st)} className="p-2 text-indigo-500 hover:bg-indigo-50 rounded-lg" title="Chỉnh sửa quyền" aria-label="Chỉnh sửa quyền">
                <Pencil className="w-4 h-4" />
              </button>
              <button
                onClick={() => handleRemoveStaff(st.userId)}
                className="p-2 text-slate-500 hover:text-rose-600 rounded-lg hover:bg-rose-50 transition"
                title="Xóa quyền trợ giảng"
              >
                <Trash2 className="w-4 h-4" />
              </button>
              </div>
            )}
          </div>
        ))}
      </div>

      {/* Add Staff Modal */}
      {showModal && (
        <Modal size="md" title={editingUserId ? 'Chỉnh sửa quyền trợ giảng' : 'Phân quyền Trợ giảng'} onClose={() => setShowModal(false)}>
            <form onSubmit={handleAssignStaff} className="space-y-4">
              <div>
                <label htmlFor={targetUserFieldId} className="block text-xs font-semibold text-slate-700 uppercase">Thành viên</label>
                {editingUserId ? (
                  <input
                    id={targetUserFieldId}
                    type="text"
                    disabled
                    value={targetUserId}
                    className="mt-1 block w-full px-3 py-2 bg-slate-100 border border-slate-300 rounded-xl text-xs font-mono text-slate-500"
                  />
                ) : (
                  <select
                    id={targetUserFieldId}
                    required
                    value={targetUserId}
                    onChange={(e) => setTargetUserId(e.target.value)}
                    className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                  >
                    <option value="">Chọn thành viên đang hoạt động trong lớp...</option>
                    {members
                      .filter(m => m.state === 'ACTIVE' && m.role !== 'STAFF' && m.role !== 'OWNER')
                      .map(m => (
                        <option key={m.userId} value={m.userId}>
                          {m.userFullName || m.userId}
                        </option>
                      ))}
                  </select>
                )}
                <p className="mt-1 text-[11px] text-slate-500">Chỉ có thể bổ nhiệm trợ giảng từ các thành viên đang hoạt động trong lớp.</p>
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-700 uppercase mb-2">Quyền hạn cấp phát</label>
                <div className="space-y-2 bg-slate-50 p-3 rounded-xl border border-slate-100 text-xs">
                  {PERMISSION_OPTIONS.map(group => <fieldset key={group.module} className="border-b border-slate-200 pb-2">
                    <legend className="font-bold text-slate-800">{group.module}</legend>
                    <div className="grid grid-cols-2 gap-1">
                    {group.actions.map(action => <label key={action} className="flex items-center gap-2">
                      <input type="checkbox" checked={permissions.some(p => p.module === group.module && p.action === action && !p.scopeCourseId)} onChange={e => togglePermission(group.module, action, e.target.checked)} className="h-6 w-6 shrink-0 rounded text-indigo-600" />
                      <span>{action}</span>
                    </label>)}
                    </div>
                    {/* R7-01: server only ever evaluates a course scope for COURSE/EXAM
                        (AccessPolicy.canManage callers) — showing this picker for any other
                        module would let the owner grant a scoped permission that assignStaff now
                        rejects (400) and that would never authorize anything anyway. */}
                    {courses.length > 0 && COURSE_SCOPABLE_MODULES.has(group.module) && group.actions.map(action => <label key={`${action}-scope`} className="flex items-center gap-2 mt-1">
                      <span className="min-w-24">{action} · khóa:</span>
                      <select value={permissions.find(p => p.module === group.module && p.action === action && p.scopeCourseId)?.scopeCourseId ?? ''} onChange={e => setCoursePermission(group.module, action, e.target.value)} className="border rounded px-2 py-1">
                        <option value="">Không cấp riêng</option>{courses.map(course => <option key={course.id} value={course.id}>{course.title}</option>)}
                      </select>
                    </label>)}
                  </fieldset>)}
                </div>
              </div>

              {/* R18-01: the panel scrolls, so the actions stick to its bottom edge and stay reachable. */}
              <div className="sticky bottom-0 -mx-6 -mb-6 flex justify-end space-x-2 border-t border-slate-100 bg-white px-6 py-4">
                <button
                  type="button"
                  onClick={() => setShowModal(false)}
                  className="px-4 py-2 border border-slate-300 text-slate-700 rounded-xl text-xs font-semibold"
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  disabled={saving}
                  className="px-4 py-2 bg-indigo-600 text-white rounded-xl text-xs font-bold hover:bg-indigo-700 disabled:opacity-50"
                >
                  {saving ? 'Đang lưu...' : 'Lưu quyền'}
                </button>
              </div>
            </form>
        </Modal>
      )}
    </div>
  );
};
