import React, { useEffect, useId, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { Modal } from '../../components/Modal';
import { Layers, Plus, Users, Eye, X } from 'lucide-react';

interface RuleDraft {
  criterion: string;
  operator: string;
  value: string;
}

const emptyRule = (): RuleDraft => ({ criterion: 'IS_PRO', operator: 'EQUALS', value: 'true' });

interface SegmentItem {
  id: string;
  classId: string;
  name: string;
  description: string;
  logicOperator: string;
  rules: { criterion: string; operator: string; value: string }[];
}

export const StudioSegments: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const canCreateSegment = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('SEGMENT:CREATE');

  const [segments, setSegments] = useState<SegmentItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // New Segment Modal
  const [showModal, setShowModal] = useState(false);
  const [name, setName] = useState('');
  const [desc, setDesc] = useState('');
  const [operator, setOperator] = useState('AND');
  // R8-11: a segment can combine several rules with the chosen AND/OR combinator; the form used to
  // always send exactly one rule, making the combinator selector meaningless.
  const [rules, setRules] = useState<RuleDraft[]>([emptyRule()]);
  const [saving, setSaving] = useState(false);
  const nameId = useId();
  const descId = useId();

  const updateRule = (index: number, patch: Partial<RuleDraft>) => {
    setRules((old) => old.map((rule, i) => (i === index ? { ...rule, ...patch } : rule)));
  };

  const addRule = () => setRules((old) => [...old, emptyRule()]);
  const removeRule = (index: number) => setRules((old) => (old.length <= 1 ? old : old.filter((_, i) => i !== index)));

  // Preview Result
  const [previewData, setPreviewData] = useState<Record<string, any>>({});

  const fetchSegments = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.get<SegmentItem[]>(`/classes/${classroom.id}/segments`);
      setSegments(data || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải danh sách phân khúc');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchSegments();
  }, [classroom.id]);

  const handlePreview = async (segId: string) => {
    try {
      const data = await api.post(`/segments/${segId}/preview?classId=${classroom.id}`);
      setPreviewData((prev) => ({ ...prev, [segId]: data }));
    } catch (err: any) {
      alert(err.message || 'Xem trước thất bại');
    }
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    try {
      await api.post(`/classes/${classroom.id}/segments`, {
        name,
        description: desc,
        logicOperator: operator,
        // R8-11: send every rule the staff member configured, in the combinator (AND/OR) they chose
        // — SegmentService.createSegment/isUserInSegment already evaluate an arbitrary list of rules
        // against that single operator; only the UI used to collapse it down to one rule.
        rules: rules.map((r) => ({ criterion: r.criterion, operator: r.operator, value: r.value })),
      });
      setShowModal(false);
      setName('');
      setDesc('');
      setRules([emptyRule()]);
      await fetchSegments();
    } catch (err: any) {
      alert(err.message || 'Tạo phân khúc thất bại');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="max-w-5xl mx-auto space-y-6">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-black text-slate-900 tracking-tight">Phân khúc học viên (Segment Engine)</h1>
          <p className="text-xs text-slate-600">
            Tạo nhóm học viên theo tiêu chí an toàn (Whitelist AST) để mở bài thi hoặc bài đăng riêng
          </p>
        </div>

        {canCreateSegment && (
          <button
            onClick={() => setShowModal(true)}
            className="inline-flex items-center space-x-1.5 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold shadow-sm transition"
          >
            <Plus className="w-4 h-4" />
            <span>Tạo nhóm phân khúc</span>
          </button>
        )}
      </div>

      {loading && <LoadingSpinner message="Đang tải danh sách phân khúc..." />}
      {error && <ErrorBanner message={error} onRetry={fetchSegments} />}

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {segments.map((seg) => (
          <div key={seg.id} className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm">
            <div className="flex items-center justify-between mb-2">
              <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-indigo-50 text-indigo-700 uppercase">
                Toán tử logic: {seg.logicOperator}
              </span>
              <button
                onClick={() => handlePreview(seg.id)}
                className="inline-flex items-center space-x-1 text-xs font-bold text-indigo-600 hover:text-indigo-800"
              >
                <Eye className="w-3.5 h-3.5" />
                <span>Kiểm tra số lượng</span>
              </button>
            </div>

            <h3 className="text-base font-bold text-slate-900 mb-1">{seg.name}</h3>
            <p className="text-xs text-slate-500 mb-4">{seg.description || 'Chưa có mô tả'}</p>

            {previewData[seg.id] && (
              <div className="p-3 bg-slate-50 rounded-xl text-xs text-slate-700 flex justify-between items-center border border-slate-100">
                <span>Số học viên khớp điều kiện:</span>
                <span className="font-bold text-indigo-600">
                  {previewData[seg.id].matchingMembers} / {previewData[seg.id].totalMembers} học viên
                </span>
              </div>
            )}
          </div>
        ))}
      </div>

      {/* Modal Create Segment */}
      {showModal && (
        <Modal size="lg" title="Tạo nhóm phân khúc học viên" onClose={() => setShowModal(false)}>
            <form onSubmit={handleCreate} className="space-y-4">
              <div>
                <label htmlFor={nameId} className="block text-xs font-semibold text-slate-700 uppercase">Tên phân khúc</label>
                <input
                  id={nameId}
                  type="text"
                  required
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="VD: Học viên hoàn thành trên 5 bài học"
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              <div>
                <label htmlFor={descId} className="block text-xs font-semibold text-slate-700 uppercase">Mô tả</label>
                <textarea
                  id={descId}
                  rows={2}
                  value={desc}
                  onChange={(e) => setDesc(e.target.value)}
                  placeholder="Mô tả nhóm đối tượng..."
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <label className="text-xs font-semibold text-slate-700 uppercase">Điều kiện</label>
                  {/* R8-11: the combinator only matters — and is only shown — once there is more
                      than one rule to combine. */}
                  {rules.length > 1 && (
                    <label className="text-xs font-semibold text-slate-700 inline-flex items-center gap-2">
                      Kết hợp bằng
                      <select
                        aria-label="Toán tử kết hợp điều kiện"
                        value={operator}
                        onChange={(e) => setOperator(e.target.value)}
                        className="rounded-lg border p-1.5"
                      >
                        <option value="AND">AND (thỏa tất cả)</option>
                        <option value="OR">OR (thỏa một trong số)</option>
                      </select>
                    </label>
                  )}
                </div>

                {rules.map((rule, index) => (
                  <div key={index} className="grid grid-cols-[1fr_1fr_1fr_auto] gap-2 items-end">
                    <label className="text-xs font-semibold text-slate-700">Tiêu chí
                      <select
                        aria-label={`Tiêu chí điều kiện ${index + 1}`}
                        value={rule.criterion}
                        onChange={(e) => updateRule(index, { criterion: e.target.value })}
                        className="mt-1 block w-full rounded-lg border p-2"
                      >
                        <option value="IS_PRO">PRO</option><option value="COURSE_OWNED">Sở hữu khóa</option>
                        <option value="COMPLETED_LESSONS_COUNT">Số bài hoàn thành</option><option value="AVG_EXAM_SCORE">Điểm thi TB</option><option value="DAYS_SINCE_JOINED">Ngày tham gia</option>
                      </select>
                    </label>
                    <label className="text-xs font-semibold text-slate-700">Toán tử
                      <select
                        aria-label={`Toán tử điều kiện ${index + 1}`}
                        value={rule.operator}
                        onChange={(e) => updateRule(index, { operator: e.target.value })}
                        className="mt-1 block w-full rounded-lg border p-2"
                      >
                        <option value="EQUALS">Bằng</option><option value="GREATER_THAN_OR_EQUAL">Từ</option><option value="LESS_THAN_OR_EQUAL">Đến</option>
                      </select>
                    </label>
                    <label className="text-xs font-semibold text-slate-700">Giá trị
                      <input
                        aria-label={`Giá trị điều kiện ${index + 1}`}
                        required
                        value={rule.value}
                        onChange={(e) => updateRule(index, { value: e.target.value })}
                        className="mt-1 block w-full rounded-lg border p-2"
                      />
                    </label>
                    <button
                      type="button"
                      onClick={() => removeRule(index)}
                      disabled={rules.length <= 1}
                      aria-label={`Xóa điều kiện ${index + 1}`}
                      className="p-2 rounded-lg border border-slate-300 text-slate-500 hover:text-rose-600 hover:border-rose-300 disabled:opacity-30 disabled:cursor-not-allowed"
                    >
                      <X className="w-4 h-4" />
                    </button>
                  </div>
                ))}

                <button
                  type="button"
                  onClick={addRule}
                  className="inline-flex items-center space-x-1 text-xs font-bold text-indigo-600 hover:text-indigo-800"
                >
                  <Plus className="w-3.5 h-3.5" />
                  <span>Thêm điều kiện</span>
                </button>
              </div>

              <div className="flex justify-end space-x-2 pt-2">
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
                  {saving ? 'Đang tạo...' : 'Tạo phân khúc'}
                </button>
              </div>
            </form>
        </Modal>
      )}
    </div>
  );
};
