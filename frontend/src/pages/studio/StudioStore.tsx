import React, { useEffect, useId, useState } from 'react';
import { Link, useOutletContext } from 'react-router-dom';
import { Classroom, Product, Order, Course } from '../../types';
import { api } from '../../api/client';
import { fetchCheckoutAvailability } from '../../api/payments';
import { durationLabel, formatDong } from '../../api/format';
import { LoadingSpinner, ErrorBanner, StatusBadge } from '../../components/UIStates';
import { Modal } from '../../components/Modal';
import { ShoppingBag, Plus, Receipt, Pencil, KeyRound } from 'lucide-react';

export const StudioStore: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const canCreateProduct = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('STORE:CREATE');
  const canEditStore = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('STORE:EDIT');
  const canPublishProduct = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('STORE:PUBLISH');
  const canViewOrders = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('STORE:VIEW');
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
      alert(err.message || 'Tạo sản phẩm thất bại');
    } finally {
      setSaving(false);
    }
  };

  const refundSandboxOrder = async (orderNumber: string) => {
    if (!window.confirm(`Hoàn tiền đơn ${orderNumber} trong sandbox? Quyền truy cập của đơn sẽ bị thu hồi.`)) return;
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

  const archiveProduct = async (id: string, title: string) => {
    if (!window.confirm(`Gỡ bán sản phẩm "${title}"? Người mua còn hạn vẫn giữ quyền truy cập; sản phẩm chỉ ngừng hiển thị cho người mua mới.`)) return;
    setError(null);
    try {
      await api.post(`/products/${id}/archive`);
      await fetchData();
    } catch (err: any) { setError(err.message || 'Không thể gỡ bán sản phẩm'); }
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

  const formatPrice = (p: number) => {
    return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' }).format(p);
  };

  return (
    <div className="max-w-5xl mx-auto space-y-8">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-black text-slate-900 tracking-tight">Cửa hàng & Quản lý đơn hàng</h1>
          <p className="text-xs text-slate-600">Tạo các gói dịch vụ, định giá theo thời hạn và theo dõi lịch sử thanh toán</p>
        </div>

        {canCreateProduct && (
          <button
            onClick={() => setShowModal(true)}
            className="inline-flex items-center space-x-1.5 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold shadow-sm transition"
          >
            <Plus className="w-4 h-4" />
            <span>Tạo gói sản phẩm mới</span>
          </button>
        )}
      </div>

      {loading && <LoadingSpinner message="Đang tải dữ liệu..." />}
      {error && <ErrorBanner message={error} onRetry={fetchData} />}

      {/* D-19: read-only card of the class-access product */}
      {accessProduct && (
        <div data-testid="class-access-card" className="bg-amber-50 border border-amber-200 rounded-2xl p-5 shadow-sm flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-bold bg-amber-100 text-amber-900">
                <KeyRound className="w-3.5 h-3.5" aria-hidden="true" />
                Gói vào lớp
              </span>
              <StatusBadge status={accessProduct.status} />
            </div>
            <p className="text-sm font-bold text-slate-900 mt-2">
              {classroom.accessType === 'PAID'
                ? `${formatDong(accessProduct.price)} / ${durationLabel(accessProduct.durationDays)}`
                : 'Lớp đang miễn phí - gói vào lớp đã được lưu trữ'}
            </p>
            <p className="text-xs text-slate-600 mt-0.5">
              Gói này bán quyền tham gia lớp và chỉ chỉnh ở Cài đặt lớp, nên không nằm trong danh sách sản phẩm bên dưới.
            </p>
          </div>
          <Link
            to={`/studio/classes/${classroom.id}/settings`}
            className="inline-flex items-center justify-center px-4 py-2 bg-white border border-amber-300 text-amber-900 hover:bg-amber-100 rounded-xl text-xs font-bold transition flex-shrink-0"
          >
            Quản lý ở Cài đặt lớp
          </Link>
        </div>
      )}

      {/* Products list */}
      <div>
        <h3 className="text-sm font-bold text-slate-900 uppercase tracking-wider mb-3">Sản phẩm ({products.length})</h3>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {products.map((p) => (
            <div key={p.id} className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm flex flex-col justify-between">
              <div>
                <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-slate-100 text-slate-700 uppercase">
                  {p.durationDays} Ngày hiệu lực
                </span>
                <StatusBadge status={p.status} />
                <h4 className="text-base font-bold text-slate-900 mt-2">{p.title}</h4>
                <p className="text-xs text-slate-500 line-clamp-2 mt-1">{p.description}</p>
              </div>
              <div className="mt-4 pt-3 border-t border-slate-100 text-right">
                <span className="text-base font-black text-indigo-600">{formatPrice(p.price)}</span>
                {canEditStore && p.status !== 'ARCHIVED' && <button onClick={() => openEditProduct(p)} className="ml-3 text-xs font-bold text-indigo-700 underline inline-flex items-center gap-1"><Pencil className="w-3 h-3" />Sửa</button>}
                {p.status === 'DRAFT' && canPublishProduct && <button onClick={() => publishProduct(p.id)} className="ml-3 text-xs font-bold text-indigo-700 underline">Xuất bản</button>}
                {p.status !== 'ARCHIVED' && canEditStore && <button onClick={() => archiveProduct(p.id, p.title)} className="ml-3 text-xs font-bold text-amber-700 underline">Gỡ bán</button>}
                {p.status === 'ARCHIVED' && canEditStore && <button onClick={() => restoreProduct(p.id)} className="ml-3 text-xs font-bold text-emerald-700 underline">Khôi phục</button>}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Orders list */}
      {canViewOrders && <div>
        <h3 className="text-sm font-bold text-slate-900 uppercase tracking-wider mb-3">Lịch sử đơn hàng ({orders.length})</h3>
        <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm divide-y divide-slate-100">
          {orders.map((ord) => (
            <div key={ord.id} className="p-4 flex items-center justify-between text-xs">
              <div>
                <div className="flex items-center space-x-2">
                  <span className="font-mono font-bold text-slate-900">{ord.orderNumber}</span>
                  <StatusBadge status={ord.status} />
                </div>
                <div className="text-[11px] text-slate-500 mt-0.5">
                  Ngày tạo: {new Date(ord.createdAt).toLocaleString('vi-VN')}
                </div>
              </div>

              <div className="text-right">
                <span className="font-bold text-slate-800 text-sm block">{formatPrice(ord.totalAmount)}</span>
                <span className="text-[10px] text-slate-500">Cổng: {ord.provider}</span>
                {ord.status === 'PAID' && ord.provider === 'MOCK' && canEditStore && (
                  <button onClick={() => refundSandboxOrder(ord.orderNumber)} className="block ml-auto mt-1 text-[10px] font-bold text-rose-700 underline">Hoàn tiền sandbox</button>
                )}
                {sandboxEnabled && ord.status === 'PENDING' && ord.provider === 'MOCK' && canEditStore && (
                  <button onClick={() => settleSandboxOrder(ord.orderNumber)} className="block ml-auto mt-1 text-[10px] font-bold text-emerald-700 underline">Xác nhận thanh toán sandbox</button>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>}

      {/* Create Product Modal */}
      {showModal && (
        <Modal size="lg" title="Tạo sản phẩm mở bán" onClose={() => setShowModal(false)}>
            <form onSubmit={handleCreateProduct} className="space-y-4">
              <div>
                <label htmlFor={titleId} className="block text-xs font-semibold text-slate-700 uppercase">Tên sản phẩm</label>
                <input
                  id={titleId}
                  type="text"
                  required
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                  placeholder="VD: Gói Đấu Trường PRO 60 Ngày"
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label htmlFor={priceId} className="block text-xs font-semibold text-slate-700 uppercase">Giá bán (VND)</label>
                  <input
                    id={priceId}
                    type="number"
                    min={0}
                    step={1000}
                    value={price}
                    onChange={(e) => setPrice(parseInt(e.target.value) || 0)}
                    className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                  />
                </div>

                <div>
                  <label htmlFor={durationId} className="block text-xs font-semibold text-slate-700 uppercase">Thời hạn (ngày)</label>
                  <input
                    id={durationId}
                    type="number"
                    min={1}
                    value={durationDays}
                    onChange={(e) => setDurationDays(parseInt(e.target.value) || 30)}
                    className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                  />
                </div>
              </div>

              <div>
                <label htmlFor={targetCourseId2} className="block text-xs font-semibold text-slate-700 uppercase">Khóa học được mở (không bắt buộc)</label>
                <select id={targetCourseId2} value={targetCourseId} onChange={(e) => setTargetCourseId(e.target.value)} className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs">
                  <option value="">Quyền PRO dùng chung trong lớp</option>
                  {courses.map((course) => <option key={course.id} value={course.id}>{course.title}</option>)}
                </select>
              </div>

              <div>
                <label htmlFor={descId} className="block text-xs font-semibold text-slate-700 uppercase">Mô tả sản phẩm</label>
                <textarea
                  id={descId}
                  rows={3}
                  value={desc}
                  onChange={(e) => setDesc(e.target.value)}
                  placeholder="Mô tả quyền lợi khi mua..."
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                />
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
                  {saving ? 'Đang tạo...' : 'Tạo sản phẩm'}
                </button>
              </div>
            </form>
        </Modal>
      )}

      {/* Edit Product Modal */}
      {editingProduct && (
        <Modal size="lg" title="Sửa sản phẩm" onClose={() => setEditingProduct(null)}>
            {editError && <p className="mb-3 text-xs font-semibold text-red-600">{editError}</p>}
            <p className="mb-4 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
              Giá mới chỉ áp dụng cho đơn hàng mới; các đơn đã thanh toán trước đó không bị ảnh hưởng.
            </p>
            <form onSubmit={handleUpdateProduct} className="space-y-4">
              <div>
                <label htmlFor={editTitleId} className="block text-xs font-semibold text-slate-700 uppercase">Tên sản phẩm</label>
                <input
                  id={editTitleId}
                  type="text"
                  required
                  value={editTitle}
                  onChange={(e) => setEditTitle(e.target.value)}
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label htmlFor={editPriceId} className="block text-xs font-semibold text-slate-700 uppercase">Giá bán (VND)</label>
                  <input
                    id={editPriceId}
                    type="number"
                    min={0}
                    step={1000}
                    value={editPrice}
                    onChange={(e) => setEditPrice(parseInt(e.target.value) || 0)}
                    className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                  />
                </div>
                <div>
                  <label htmlFor={editDurationId} className="block text-xs font-semibold text-slate-700 uppercase">Thời hạn (ngày)</label>
                  <input
                    id={editDurationId}
                    type="number"
                    min={1}
                    value={editDurationDays}
                    onChange={(e) => setEditDurationDays(parseInt(e.target.value) || 30)}
                    className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                  />
                </div>
              </div>

              <div>
                <label htmlFor={editDescId} className="block text-xs font-semibold text-slate-700 uppercase">Mô tả sản phẩm</label>
                <textarea
                  id={editDescId}
                  rows={3}
                  value={editDesc}
                  onChange={(e) => setEditDesc(e.target.value)}
                  className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                />
              </div>

              <div className="flex justify-end space-x-2 pt-2">
                <button
                  type="button"
                  onClick={() => setEditingProduct(null)}
                  className="px-4 py-2 border border-slate-300 text-slate-700 rounded-xl text-xs font-semibold"
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  disabled={savingEdit}
                  className="px-4 py-2 bg-indigo-600 text-white rounded-xl text-xs font-bold hover:bg-indigo-700 disabled:opacity-50"
                >
                  {savingEdit ? 'Đang lưu...' : 'Lưu thay đổi'}
                </button>
              </div>
            </form>
        </Modal>
      )}
    </div>
  );
};
