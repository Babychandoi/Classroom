import React, { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Classroom, Product, Order, Course } from '../../types';
import { api } from '../../api/client';
import { LoadingSpinner, ErrorBanner, StatusBadge } from '../../components/UIStates';
import { ShoppingBag, Plus, Receipt } from 'lucide-react';

export const StudioStore: React.FC = () => {
  const { classroom } = useOutletContext<{ classroom: Classroom }>();
  const canCreateProduct = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('STORE:CREATE');
  const canEditStore = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('STORE:EDIT');
  const canPublishProduct = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('STORE:PUBLISH');
  const canViewOrders = classroom.userRole === 'OWNER' || classroom.studioPermissions?.includes('STORE:VIEW');
  const [sandboxEnabled, setSandboxEnabled] = useState(false);

  const [products, setProducts] = useState<Product[]>([]);
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

  const fetchData = async () => {
    try {
      setLoading(true);
      const [pData, cData] = await Promise.all([
        api.get<Product[]>(`/classes/${classroom.id}/studio/products`),
        api.get<Course[]>(`/classes/${classroom.id}/courses`),
      ]);
      setProducts(pData || []);
      setCourses(cData || []);
      try {
        const status = await api.get<{ checkoutAvailable: boolean }>('/payments/sandbox-status');
        setSandboxEnabled(status.checkoutAvailable === true);
      } catch {
        setSandboxEnabled(false);
      }
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
    try {
      await api.post('/payments/mock/simulate', { orderNumber, eventType: 'PAYMENT_REFUNDED' });
      await fetchData();
    } catch (err: any) {
      setError(err.message || 'Không thể hoàn tiền sandbox');
    }
  };

  const settleSandboxOrder = async (orderNumber: string) => {
    try {
      await api.post('/payments/mock/simulate', { orderNumber, eventType: 'PAYMENT_SUCCESS' });
      await fetchData();
    } catch (err: any) {
      setError(err.message || 'Không thể xác nhận thanh toán sandbox');
    }
  };

  const publishProduct = async (id: string) => {
    try {
      await api.post(`/products/${id}/publish`);
      await fetchData();
    } catch (err: any) { setError(err.message || 'Không thể xuất bản sản phẩm'); }
  };

  const formatPrice = (p: number) => {
    return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' }).format(p);
  };

  return (
    <div className="max-w-5xl mx-auto space-y-8">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-black text-slate-900 tracking-tight">Cửa hàng & Quản lý đơn hàng</h1>
          <p className="text-xs text-slate-500">Tạo các gói dịch vụ, định giá theo thời hạn và theo dõi lịch sử thanh toán</p>
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
                {p.status === 'DRAFT' && canPublishProduct && <button onClick={() => publishProduct(p.id)} className="ml-3 text-xs font-bold text-indigo-700 underline">Xuất bản</button>}
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
                <div className="text-[11px] text-slate-400 mt-0.5">
                  Ngày tạo: {new Date(ord.createdAt).toLocaleString('vi-VN')}
                </div>
              </div>

              <div className="text-right">
                <span className="font-bold text-slate-800 text-sm block">{formatPrice(ord.totalAmount)}</span>
                <span className="text-[10px] text-slate-400">Cổng: {ord.provider}</span>
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
        <div className="fixed inset-0 bg-slate-900/50 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-white rounded-2xl max-w-lg w-full p-6 shadow-xl border border-slate-200">
            <h3 className="text-lg font-bold text-slate-900 mb-4">Tạo sản phẩm mở bán</h3>
            <form onSubmit={handleCreateProduct} className="space-y-4">
              <div>
                <label className="block text-xs font-semibold text-slate-700 uppercase">Tên sản phẩm</label>
                <input
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
                  <label className="block text-xs font-semibold text-slate-700 uppercase">Giá bán (VND)</label>
                  <input
                    type="number"
                    min={0}
                    step={1000}
                    value={price}
                    onChange={(e) => setPrice(parseInt(e.target.value) || 0)}
                    className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                  />
                </div>

                <div>
                  <label className="block text-xs font-semibold text-slate-700 uppercase">Thời hạn (ngày)</label>
                  <input
                    type="number"
                    min={1}
                    value={durationDays}
                    onChange={(e) => setDurationDays(parseInt(e.target.value) || 30)}
                    className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500"
                  />
                </div>
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-700 uppercase">Khóa học được mở (không bắt buộc)</label>
                <select value={targetCourseId} onChange={(e) => setTargetCourseId(e.target.value)} className="mt-1 block w-full px-3 py-2 bg-slate-50 border border-slate-300 rounded-xl text-xs">
                  <option value="">Quyền PRO dùng chung trong lớp</option>
                  {courses.map((course) => <option key={course.id} value={course.id}>{course.title}</option>)}
                </select>
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-700 uppercase">Mô tả sản phẩm</label>
                <textarea
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
          </div>
        </div>
      )}
    </div>
  );
};
