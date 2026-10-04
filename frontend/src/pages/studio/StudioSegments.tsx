import React, { useEffect, useId, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner } from '../../components/UIStates';
import { Modal } from '../../components/Modal';
import { hasStudioPermission } from '../../api/permissions';
import { Badge, Button, Card, Field, Input, Textarea, buttonClass, inputClass } from '../../components/ui';
import { ModalActions, PageHeader, StudioPage, iconActionClass, rowActionClass } from './studioUi';
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
  // Wildcard-aware ("SEGMENT:*", "*:CREATE"), like the server's AccessPolicy.canManage.
  const canCreateSegment = hasStudioPermission(classroom, 'SEGMENT', 'CREATE');

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
  const [createError, setCreateError] = useState<string | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
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
    setPreviewError(null);
    try {
      const data = await api.post(`/segments/${segId}/preview?classId=${classroom.id}`);
      setPreviewData((prev) => ({ ...prev, [segId]: data }));
    } catch (err: any) {
      setPreviewError(err.message || 'Xem trước thất bại');
    }
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setCreateError(null);
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
      setCreateError(err.message || 'Tạo phân khúc thất bại');
    } finally {
      setSaving(false);
    }
  };

  const openCreate = () => {
    setCreateError(null);
    setShowModal(true);
  };

  return (
    <StudioPage>
      <PageHeader
        title="Nhóm học viên"
        description="Gom học viên theo tiêu chí (PRO, khóa đã mua, số bài hoàn thành, điểm thi, ngày tham gia) để mở bài thi hoặc bài đăng riêng cho từng nhóm."
        action={canCreateSegment && (
          <Button variant="primary" size="md" onClick={openCreate}>
            <Plus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
            <span>Tạo nhóm phân khúc</span>
          </Button>
        )}
      />

      {loading && <LoadingSpinner message="Đang tải danh sách phân khúc..." />}
      {error && <ErrorBanner message={error} onRetry={fetchSegments} />}
      {previewError && <ErrorBanner message={previewError} />}

      {!loading && !error && segments.length === 0 && (
        <Card className="text-center">
          <p className="text-ui text-slate-600">Chưa có nhóm học viên nào. Ví dụ dễ bắt đầu: nhóm “Học viên PRO” để mở một bài thi riêng.</p>
        </Card>
      )}

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        {segments.map((seg) => (
          <Card key={seg.id} as="article" className="flex flex-col">
            <div className="flex items-start justify-between gap-3">
              <div className="flex min-w-0 items-start gap-3">
                <span className="inline-flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-[12px] bg-slate-100 text-slate-600" aria-hidden="true">
                  <Layers className="h-5 w-5" strokeWidth={1.75} />
                </span>
                <div className="min-w-0">
                  <h3 className="text-h3 font-semibold text-slate-900">{seg.name}</h3>
                  <p className="mt-0.5 text-meta text-slate-600">{seg.description || 'Chưa có mô tả'}</p>
                </div>
              </div>
              <Badge tone="neutral" size="sm" title="Cách kết hợp các điều kiện">
                {seg.logicOperator === 'OR' ? 'Một trong các điều kiện' : 'Tất cả điều kiện'}
              </Badge>
            </div>

            <div className="mt-auto pt-4">
              {previewData[seg.id] ? (
                <div className="flex items-center justify-between gap-3 rounded-btn border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-meta text-slate-600">
                  <span className="inline-flex items-center gap-1.5">
                    <Users className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                    Số học viên khớp điều kiện:
                  </span>
                  <span className="font-semibold text-slate-900 tabular">
                    {previewData[seg.id].matchingMembers} / {previewData[seg.id].totalMembers} học viên
                  </span>
                </div>
              ) : (
                <button type="button" onClick={() => handlePreview(seg.id)} className={rowActionClass()}>
                  <Eye className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                  <span>Kiểm tra số lượng</span>
                </button>
              )}
            </div>
          </Card>
        ))}
      </div>

      {/* Modal Create Segment */}
      {showModal && (
        <Modal size="lg" title="Tạo nhóm phân khúc học viên" onClose={() => setShowModal(false)}>
          <form onSubmit={handleCreate} className="space-y-5">
            {createError && <ErrorBanner message={createError} />}
            <Field label="Tên phân khúc" htmlFor={nameId}>
              <Input id={nameId} type="text" required value={name} onChange={(e) => setName(e.target.value)} placeholder="VD: Học viên hoàn thành trên 5 bài học" />
            </Field>

            <Field label="Mô tả" htmlFor={descId}>
              <Textarea id={descId} rows={2} value={desc} onChange={(e) => setDesc(e.target.value)} placeholder="Nhóm này gồm những ai, dùng để làm gì..." />
            </Field>

            <div className="space-y-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="text-meta font-semibold text-slate-900">Điều kiện</p>
                {/* R8-11: the combinator only matters — and is only shown — once there is more
                    than one rule to combine. */}
                {rules.length > 1 && (
                  <label className="inline-flex items-center gap-2 text-meta text-slate-600">
                    Kết hợp bằng
                    <select
                      aria-label="Toán tử kết hợp điều kiện"
                      value={operator}
                      onChange={(e) => setOperator(e.target.value)}
                      className={inputClass('h-9 w-auto pr-8 text-meta')}
                    >
                      <option value="AND">AND (thỏa tất cả)</option>
                      <option value="OR">OR (thỏa một trong số)</option>
                    </select>
                  </label>
                )}
              </div>

              {rules.map((rule, index) => (
                <div key={index} className="grid grid-cols-[1fr_auto] items-end gap-2 rounded-2xl border border-slate-200 p-3 sm:grid-cols-[1fr_1fr_1fr_auto] sm:border-0 sm:p-0">
                  <div className="col-span-2 grid gap-2 sm:col-span-3 sm:grid-cols-3">
                    <label className="text-caption font-semibold text-slate-600">Tiêu chí
                      <select
                        aria-label={`Tiêu chí điều kiện ${index + 1}`}
                        value={rule.criterion}
                        onChange={(e) => updateRule(index, { criterion: e.target.value })}
                        className={inputClass('mt-1 h-10 pr-8')}
                      >
                        <option value="IS_PRO">PRO</option><option value="COURSE_OWNED">Sở hữu khóa</option>
                        <option value="COMPLETED_LESSONS_COUNT">Số bài hoàn thành</option><option value="AVG_EXAM_SCORE">Điểm thi TB</option><option value="DAYS_SINCE_JOINED">Ngày tham gia</option>
                      </select>
                    </label>
                    <label className="text-caption font-semibold text-slate-600">Toán tử
                      <select
                        aria-label={`Toán tử điều kiện ${index + 1}`}
                        value={rule.operator}
                        onChange={(e) => updateRule(index, { operator: e.target.value })}
                        className={inputClass('mt-1 h-10 pr-8')}
                      >
                        <option value="EQUALS">Bằng</option><option value="GREATER_THAN_OR_EQUAL">Từ</option><option value="LESS_THAN_OR_EQUAL">Đến</option>
                      </select>
                    </label>
                    <label className="text-caption font-semibold text-slate-600">Giá trị
                      <input
                        aria-label={`Giá trị điều kiện ${index + 1}`}
                        required
                        value={rule.value}
                        onChange={(e) => updateRule(index, { value: e.target.value })}
                        className={inputClass('mt-1 h-10')}
                      />
                    </label>
                  </div>
                  <button
                    type="button"
                    onClick={() => removeRule(index)}
                    disabled={rules.length <= 1}
                    aria-label={`Xóa điều kiện ${index + 1}`}
                    className={`${iconActionClass('danger')} col-start-2 h-10 w-10 border border-slate-200 sm:col-start-auto`}
                  >
                    <X className="h-4 w-4" strokeWidth={1.75} />
                  </button>
                </div>
              ))}

              <button type="button" onClick={addRule} className={buttonClass('tertiary', 'sm')}>
                <Plus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                <span>Thêm điều kiện</span>
              </button>
            </div>

            <ModalActions>
              <Button variant="secondary" onClick={() => setShowModal(false)}>Hủy</Button>
              <Button type="submit" variant="primary" disabled={saving}>{saving ? 'Đang tạo...' : 'Tạo phân khúc'}</Button>
            </ModalActions>
          </form>
        </Modal>
      )}
    </StudioPage>
  );
};
