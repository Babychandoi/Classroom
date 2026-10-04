import React, { useEffect, useId, useState } from 'react';
import { Link, useOutletContext } from 'react-router-dom';
import { Classroom, Product, Order, Course } from '../../types';
import { api } from '../../api/client';
import { fetchCheckoutAvailability } from '../../api/payments';
import { durationLabel, formatDateTime, formatDong } from '../../api/format';
import { hasStudioPermission } from '../../api/permissions';
import { LoadingSpinner, ErrorBanner, StatusBadge } from '../../components/UIStates';
import { Modal } from '../../components/Modal';
import { Badge, Button, Card, Field, Input, Select, Textarea, buttonClass } from '../../components/ui';
import { EmptyRow, ModalActions, Notice, PageHeader, StudioPage, rowActionClass } from './studioUi';
import { Plus, Receipt, Pencil, KeyRound } from 'lucide-react';

export const StudioStore: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  // hasStudioPermission is wildcard-aware ("STORE:*", "*:EDIT") like the server's AccessPolicy.canManage.
  const canCreateProduct = hasStudioPermission(classroom, 'STORE', 'CREATE');
  const canEditStore = hasStudioPermission(classroom, 'STORE', 'EDIT');
  const canPublishProduct = hasStudioPermission(classroom, 'STORE', 'PUBLISH');
  const canViewOrders = hasStudioPermission(classroom, 'STORE', 'VIEW');
  const [sandboxEnabled, setSandboxEnabled] = useState(false);

  const [products, setProducts] = useState<Product[]>([]);
  // D-19: the class-access product (what a PAID class sells) is managed only from Cài đặt lớp, so it never enters the normal
  // product list / counts / edit and archive flows - it gets its own read-only card instead.
  const [accessProduct, setAccessProduct] = useState<Product | null>(null);
  const [courses, setCourses] = useState<Course[]>([]);
  const [targetCourseId, setTargetCourseId] = useState('');
  const [orders, setOrders] = useState<Order[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // New Product Modal
  const [showModal, setShowModal] = useState(false);
  const [title, setTitle] = useState('');
  const [desc, setDesc] = useState('');
  const [price, setPrice] = useState(199000);
  const [durationDays, setDurationDays] = useState(30);
  const [saving, setSaving] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  // Refunding a sandbox order and taking a product off sale both ask first (they used to use window.confirm).
  const [confirmAction, setConfirmAction] = useState<
    | { kind: 'refund'; orderNumber: string }
    | { kind: 'archive'; id: string; title: string }
    | null
  >(null);
  const [confirmPending, setConfirmPending] = useState(false);
  const titleId = useId();
  const priceId = useId();
  const durationId = useId();
  const targetCourseId2 = useId();
  const descId = useId();

  // Edit Product Modal
  const [editingProduct, setEditingProduct] = useState<Product | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [editDesc, setEditDesc] = useState('');
  const [editPrice, setEditPrice] = useState(0);
  const [editDurationDays, setEditDurationDays] = useState(30);
  const [savingEdit, setSavingEdit] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);
  const editTitleId = useId();
  const editPriceId = useId();
  const editDurationId = useId();
  const editDescId = useId();

  const fetchData = async () => {
    try {
      setLoading(true);
      setError(null);
      const [pData, cData] = await Promise.all([
        api.get<Product[]>(`/classes/${classroom.id}/studio/products`),
        api.get<Course[]>(`/classes/${classroom.id}/courses`),
      ]);
      const all = pData || [];
      setProducts(all.filter((p) => p.kind !== 'CLASS_ACCESS'));
      setAccessProduct(all.find((p) => p.kind === 'CLASS_ACCESS') ?? null);
      setCourses(cData || []);
      setSandboxEnabled(await fetchCheckoutAvailability());
      if (canViewOrders) {
        try {
          setOrders(await api.get<Order[]>(`/classes/${classroom.id}/orders`) || []);
        } catch (err: any) {
          setError(err.message || 'Không thể tải đơn hàng');
        }
      } else {
        setOrders([]);
      }
    } catch (err: any) {
      setError(err.message || 'Không thể tải thông tin bán hàng');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, [classroom.id]);

  const handleCreateProduct = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setCreateError(null);
    try {
      const product = await api.post<Product>(`/classes/${classroom.id}/products`, {
        title,
        description: desc,
        price,
        durationDays,
        targetCourseId: targetCourseId || null,
      });
      if (targetCourseId) await api.put(`/courses/${targetCourseId}/product/${product.id}`, {});
      setShowModal(false);
      setTitle('');
      setDesc('');
      setTargetCourseId('');
      await fetchData();
    } catch (err: any) {
      setCreateError(err.message || 'Tạo sản phẩm thất bại');
    } finally {
      setSaving(false);
    }
  };

  const refundSandboxOrder = async (orderNumber: string) => {
    setError(null);
    try {
      await api.post('/payments/mock/simulate', { orderNumber, eventType: 'PAYMENT_REFUNDED' });
      await fetchData();
    } catch (err: any) {
      setError(err.message || 'Không thể hoàn tiền sandbox');
    }
  };

  const settleSandboxOrder = async (orderNumber: string) => {
    setError(null);
    try {
      await api.post('/payments/mock/simulate', { orderNumber, eventType: 'PAYMENT_SUCCESS' });
      await fetchData();
    } catch (err: any) {
      setError(err.message || 'Không thể xác nhận thanh toán sandbox');
    }
  };

  const publishProduct = async (id: string) => {
    setError(null);
    try {
      await api.post(`/products/${id}/publish`);
      await fetchData();
    } catch (err: any) { setError(err.message || 'Không thể xuất bản sản phẩm'); }
  };

  const archiveProduct = async (id: string) => {
    setError(null);
    try {
      await api.post(`/products/${id}/archive`);
      await fetchData();
    } catch (err: any) { setError(err.message || 'Không thể gỡ bán sản phẩm'); }
  };

  const runConfirmed = async () => {
    if (!confirmAction) return;
    setConfirmPending(true);
    try {
      if (confirmAction.kind === 'refund') await refundSandboxOrder(confirmAction.orderNumber);
      else await archiveProduct(confirmAction.id);
    } finally {
      setConfirmPending(false);
      setConfirmAction(null);
    }
  };

  const restoreProduct = async (id: string) => {
    setError(null);
    try {
      await api.post(`/products/${id}/restore`);
      await fetchData();
    } catch (err: any) { setError(err.message || 'Không thể khôi phục sản phẩm'); }
  };

  const openEditProduct = (p: Product) => {
    setEditingProduct(p);
    setEditTitle(p.title);
    setEditDesc(p.description || '');
    setEditPrice(p.price);
    setEditDurationDays(p.durationDays);
    setEditError(null);
  };

  const handleUpdateProduct = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingProduct) return;
    setSavingEdit(true);
    setEditError(null);
    try {
      await api.put(`/products/${editingProduct.id}`, {
        title: editTitle,
        description: editDesc,
        price: editPrice,
        durationDays: editDurationDays,
      });
      setEditingProduct(null);
      await fetchData();
    } catch (err: any) {
      setEditError(err.message || 'Cập nhật sản phẩm thất bại');
    } finally {
      setSavingEdit(false);
    }
  };

  const openCreate = () => {
    setCreateError(null);
    setShowModal(true);
  };

  return (
    <StudioPage>
      <PageHeader
        title="Shop & đơn hàng"
        description="Tạo gói sản phẩm, định giá theo thời hạn và theo dõi các đơn thanh toán của lớp."
        action={canCreateProduct && (
          <Button variant="primary" size="md" onClick={openCreate}>
            <Plus className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
            <span>Tạo gói sản phẩm mới</span>
          </Button>
        )}
      />

      {loading && <LoadingSpinner message="Đang tải dữ liệu..." />}
      {error && <ErrorBanner message={error} onRetry={fetchData} />}

      {/* D-19: read-only card of the class-access product */}
      {accessProduct && (
        <Card data-testid="class-access-card" className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex min-w-0 items-start gap-3">
            <span className="inline-flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-[12px] bg-amber-100 text-amber-800" aria-hidden="true">
              <KeyRound className="h-5 w-5" strokeWidth={1.75} />
            </span>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-ui font-semibold text-slate-900">Gói vào lớp</span>
                <StatusBadge status={accessProduct.status} />
              </div>
              <p className="mt-1 text-body-sm font-semibold text-slate-900 tabular">
                {classroom.accessType === 'PAID'
                  ? `${formatDong(accessProduct.price)} / ${durationLabel(accessProduct.durationDays)}`
                  : 'Lớp đang miễn phí - gói vào lớp đã được lưu trữ'}
              </p>
              <p className="mt-0.5 text-meta text-slate-600">
                Gói này bán quyền tham gia lớp và chỉ chỉnh ở Cài đặt lớp, nên không nằm trong danh sách sản phẩm bên dưới.
              </p>
            </div>
          </div>
          <Link to={`/studio/classes/${classroom.id}/settings`} className={buttonClass('secondary', 'md', 'flex-shrink-0')}>
            Quản lý ở Cài đặt lớp
          </Link>
        </Card>
      )}

      {/* Products list */}
      <Card as="section" padded={false} aria-labelledby="products-title" className="overflow-hidden">
        <div className="flex items-center justify-between gap-3 px-5 py-4 sm:px-6">
          <h2 id="products-title" className="text-[16px] font-semibold leading-6 text-slate-900">
            Sản phẩm <span className="text-slate-500 tabular">({products.length})</span>
          </h2>
        </div>
        {!loading && products.length === 0 && (
          <EmptyRow className="border-t border-slate-100">
            Chưa có gói sản phẩm nào. {canCreateProduct ? 'Tạo gói đầu tiên để học viên có thể nâng cấp quyền lợi.' : ''}
          </EmptyRow>
        )}
        <ul>
          {products.map((p) => (
            <li key={p.id} className="flex flex-col gap-3 border-t border-slate-100 px-5 py-4 sm:flex-row sm:items-center sm:justify-between sm:px-6">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <h3 className="text-ui font-semibold text-slate-900">{p.title}</h3>
                  <StatusBadge status={p.status} />
                  <Badge tone="neutral" size="sm" className="tabular">{p.durationDays} ngày hiệu lực</Badge>
                </div>
                {p.description && <p className="mt-1 line-clamp-2 text-meta text-slate-600">{p.description}</p>}
              </div>
              <div className="flex flex-shrink-0 flex-wrap items-center gap-1 sm:justify-end">
                <span className="mr-2 text-body-sm font-semibold text-slate-900 tabular">{formatDong(p.price)}</span>
                {canEditStore && p.status !== 'ARCHIVED' && (
                  <button type="button" onClick={() => openEditProduct(p)} className={rowActionClass()}>
                    <Pencil className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />Sửa
                  </button>
                )}
                {p.status === 'DRAFT' && canPublishProduct && (
                  <button type="button" onClick={() => publishProduct(p.id)} className={rowActionClass('success')}>Xuất bản</button>
                )}
                {p.status !== 'ARCHIVED' && canEditStore && (
                  <button type="button" onClick={() => setConfirmAction({ kind: 'archive', id: p.id, title: p.title })} className={rowActionClass('warn')}>Gỡ bán</button>
                )}
                {p.status === 'ARCHIVED' && canEditStore && (
                  <button type="button" onClick={() => restoreProduct(p.id)} className={rowActionClass('success')}>Khôi phục</button>
                )}
              </div>
            </li>
          ))}
        </ul>
      </Card>

      {/* Orders list */}
      {canViewOrders && (
        <Card as="section" padded={false} aria-labelledby="orders-title" className="overflow-hidden">
          <div className="flex items-center gap-2 px-5 py-4 sm:px-6">
            <Receipt className="h-[18px] w-[18px] text-slate-500" strokeWidth={1.75} aria-hidden="true" />
            <h2 id="orders-title" className="text-[16px] font-semibold leading-6 text-slate-900">
              Lịch sử đơn hàng <span className="text-slate-500 tabular">({orders.length})</span>
            </h2>
          </div>
          {!loading && orders.length === 0 && (
            <EmptyRow className="border-t border-slate-100">Chưa có đơn hàng nào. Đơn mới sẽ hiện ở đây ngay khi thành viên thanh toán.</EmptyRow>
          )}
          <ul>
            {orders.map((ord) => (
              <li key={ord.id} className="flex flex-col gap-2 border-t border-slate-100 px-5 py-3.5 sm:flex-row sm:items-center sm:justify-between sm:px-6">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-mono text-meta font-semibold text-slate-900">{ord.orderNumber}</span>
                    <StatusBadge status={ord.status} />
                  </div>
                  <p className="mt-0.5 text-caption text-slate-500 tabular">
                    {formatDateTime(ord.createdAt)} · Cổng: {ord.provider}
                  </p>
                </div>
                <div className="flex flex-wrap items-center gap-1 sm:justify-end">
                  {/* Both sandbox actions only exist while the mock checkout is enabled on this server. */}
                  {sandboxEnabled && ord.status === 'PAID' && ord.provider === 'MOCK' && canEditStore && (
                    <button type="button" onClick={() => setConfirmAction({ kind: 'refund', orderNumber: ord.orderNumber })} className={rowActionClass('danger')}>
                      Hoàn tiền sandbox
                    </button>
                  )}
                  {sandboxEnabled && ord.status === 'PENDING' && ord.provider === 'MOCK' && canEditStore && (
                    <button type="button" onClick={() => settleSandboxOrder(ord.orderNumber)} className={rowActionClass('success')}>
                      Xác nhận thanh toán sandbox
                    </button>
                  )}
                  <span className="ml-2 text-ui font-semibold text-slate-900 tabular">{formatDong(ord.totalAmount)}</span>
                </div>
              </li>
            ))}
          </ul>
        </Card>
      )}

      {/* Create Product Modal */}
      {showModal && (
        <Modal size="lg" title="Tạo sản phẩm mở bán" onClose={() => setShowModal(false)}>
          <form onSubmit={handleCreateProduct} className="space-y-4">
            {createError && <ErrorBanner message={createError} />}
            <Field label="Tên sản phẩm" htmlFor={titleId}>
              <Input id={titleId} type="text" required value={title} onChange={(e) => setTitle(e.target.value)} placeholder="VD: Gói Đấu Trường PRO 60 Ngày" />
            </Field>

            <div className="grid grid-cols-2 gap-3">
              <Field label="Giá bán (VND)" htmlFor={priceId}>
                <Input id={priceId} type="number" min={0} step={1000} value={price} onChange={(e) => setPrice(parseInt(e.target.value) || 0)} className="tabular" />
              </Field>
              <Field label="Thời hạn (ngày)" htmlFor={durationId}>
                <Input id={durationId} type="number" min={1} value={durationDays} onChange={(e) => setDurationDays(parseInt(e.target.value) || 30)} className="tabular" />
              </Field>
            </div>

            <Field label="Khóa học được mở (không bắt buộc)" htmlFor={targetCourseId2}>
              <Select id={targetCourseId2} value={targetCourseId} onChange={(e) => setTargetCourseId(e.target.value)}>
                <option value="">Quyền PRO dùng chung trong lớp</option>
                {courses.map((course) => <option key={course.id} value={course.id}>{course.title}</option>)}
              </Select>
            </Field>

            <Field label="Mô tả sản phẩm" htmlFor={descId}>
              <Textarea id={descId} rows={3} value={desc} onChange={(e) => setDesc(e.target.value)} placeholder="Học viên nhận được gì khi tham gia gói này..." />
            </Field>

            <ModalActions>
              <Button variant="secondary" onClick={() => setShowModal(false)}>Hủy</Button>
              <Button type="submit" variant="primary" disabled={saving}>{saving ? 'Đang tạo...' : 'Tạo sản phẩm'}</Button>
            </ModalActions>
          </form>
        </Modal>
      )}

      {/* Edit Product Modal */}
      {editingProduct && (
        <Modal size="lg" title="Sửa sản phẩm" onClose={() => setEditingProduct(null)}>
          <div className="space-y-4">
            {editError && <ErrorBanner message={editError} />}
            <Notice tone="warn">Giá mới chỉ áp dụng cho đơn hàng mới; các đơn đã thanh toán trước đó không bị ảnh hưởng.</Notice>
            <form onSubmit={handleUpdateProduct} className="space-y-4">
              <Field label="Tên sản phẩm" htmlFor={editTitleId}>
                <Input id={editTitleId} type="text" required value={editTitle} onChange={(e) => setEditTitle(e.target.value)} />
              </Field>
              <div className="grid grid-cols-2 gap-3">
                <Field label="Giá bán (VND)" htmlFor={editPriceId}>
                  <Input id={editPriceId} type="number" min={0} step={1000} value={editPrice} onChange={(e) => setEditPrice(parseInt(e.target.value) || 0)} className="tabular" />
                </Field>
                <Field label="Thời hạn (ngày)" htmlFor={editDurationId}>
                  <Input id={editDurationId} type="number" min={1} value={editDurationDays} onChange={(e) => setEditDurationDays(parseInt(e.target.value) || 30)} className="tabular" />
                </Field>
              </div>
              <Field label="Mô tả sản phẩm" htmlFor={editDescId}>
                <Textarea id={editDescId} rows={3} value={editDesc} onChange={(e) => setEditDesc(e.target.value)} />
              </Field>
              <ModalActions>
                <Button variant="secondary" onClick={() => setEditingProduct(null)}>Hủy</Button>
                <Button type="submit" variant="primary" disabled={savingEdit}>{savingEdit ? 'Đang lưu...' : 'Lưu thay đổi'}</Button>
              </ModalActions>
            </form>
          </div>
        </Modal>
      )}

      {confirmAction && (
        <Modal
          size="sm"
          role="alertdialog"
          title={confirmAction.kind === 'refund' ? 'Hoàn tiền đơn sandbox?' : 'Gỡ bán sản phẩm?'}
          onClose={() => setConfirmAction(null)}
        >
          <p className="text-ui text-slate-600">
            {confirmAction.kind === 'refund'
              ? `Hoàn tiền đơn ${confirmAction.orderNumber} trong sandbox? Quyền truy cập của đơn sẽ bị thu hồi.`
              : `Gỡ bán sản phẩm "${confirmAction.title}"? Người mua còn hạn vẫn giữ quyền truy cập; sản phẩm chỉ ngừng hiển thị cho người mua mới.`}
          </p>
          <ModalActions className="mt-5">
            <Button variant="secondary" onClick={() => setConfirmAction(null)}>Hủy</Button>
            <Button variant="danger" disabled={confirmPending} onClick={runConfirmed}>
              {confirmPending ? 'Đang xử lý...' : confirmAction.kind === 'refund' ? 'Hoàn tiền' : 'Gỡ bán'}
            </Button>
          </ModalActions>
        </Modal>
      )}
    </StudioPage>
  );
};
