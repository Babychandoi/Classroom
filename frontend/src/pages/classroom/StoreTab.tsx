import React, { useEffect, useState } from 'react';
import { useOutletContext, useNavigate } from 'react-router-dom';
import { Classroom, Product, Order } from '../../types';
import { api } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { LoadingSpinner, ErrorBanner, EmptyState, StatusBadge } from '../../components/UIStates';
import { CheckCircle, Clock, Sparkles, AlertCircle } from 'lucide-react';

// crypto.randomUUID() is only defined in secure contexts (HTTPS/localhost); fall back to a
// manually assembled RFC 4122 v4-ish UUID elsewhere so checkout still works over plain HTTP.
const generateIdempotencyKey = (): string => {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    try {
      return crypto.randomUUID();
    } catch {
      // fall through to the manual generator below
    }
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16);
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
};

export const StoreTab: React.FC = () => {
  const { classroom, refreshClassroom } = useOutletContext<{
    classroom: Classroom;
    refreshClassroom: () => Promise<void>;
  }>();
  const { user } = useAuth();
  const navigate = useNavigate();

  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Checkout modal
  const [selectedProduct, setSelectedProduct] = useState<Product | null>(null);
  const [currentOrder, setCurrentOrder] = useState<Order | null>(null);
  const [processing, setProcessing] = useState(false);
  const [paymentError, setPaymentError] = useState<string | null>(null);
  const [checkoutKey, setCheckoutKey] = useState<string | null>(null);
  const [checkoutAvailable, setCheckoutAvailable] = useState(false);

  const fetchProducts = async () => {
    try {
      setLoading(true);
      const data = await api.get<Product[]>(`/classes/${classroom.id}/products`);
      setProducts(data || []);
    } catch (err: any) {
      setError(err.message || 'Không thể tải danh sách sản phẩm');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchProducts();
    api.get<{ checkoutAvailable: boolean }>('/payments/sandbox-status')
      .then((status) => setCheckoutAvailable(status.checkoutAvailable === true))
      .catch(() => setCheckoutAvailable(false));
  }, [classroom.id, user]);

  const handleBuy = async (prod: Product) => {
    if (!user) {
      navigate('/login');
      return;
    }

    setSelectedProduct(prod);
    if (checkoutKey && selectedProduct?.id !== prod.id) setCheckoutKey(null);
    setPaymentError(null);
    setProcessing(true);
    try {
      const idempotencyKey = (checkoutKey && selectedProduct?.id === prod.id) ? checkoutKey : generateIdempotencyKey();
      setCheckoutKey(idempotencyKey);
      const order = await api.post<Order>('/orders', {
        classId: classroom.id,
        productId: prod.id,
        idempotencyKey,
      }, {
        headers: { 'Idempotency-Key': idempotencyKey },
      });
      // Success: the key has been consumed by a completed order, so a later retry must mint a new one.
      setCheckoutKey(null);
      setCurrentOrder(order);
      // An order may already be settled out-of-band by an operator; refresh entitlement state.
      await refreshClassroom();
    } catch (err: any) {
      // Keep the idempotency key on network errors and 5xx so a retry reuses it (the request may
      // have been accepted server-side even though the response was lost). Only clear it on a
      // definitive 4xx, where the server is telling us this exact request will never succeed.
      const status = err?.status;
      if (typeof status === 'number' && status >= 400 && status < 500) {
        setCheckoutKey(null);
      }
      alert(err.message || 'Khởi tạo đơn hàng thất bại');
      setSelectedProduct(null);
    } finally {
      setProcessing(false);
    }
  };

  const refreshOrderStatus = async () => {
    if (!currentOrder) return;
    try {
      const order = await api.get<Order>(`/orders/${currentOrder.id}`);
      setCurrentOrder(order);
      await refreshClassroom();
      await fetchProducts();
    } catch (err: any) {
      setPaymentError(err.message || 'Không thể làm mới trạng thái đơn hàng');
    }
  };

  const formatPrice = (price: number) => {
    return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' }).format(price);
  };

  return (
    <div className="space-y-8">
      <div>
        <div className="inline-flex items-center space-x-1.5 px-3 py-1 rounded-full bg-indigo-50 text-indigo-700 text-xs font-bold mb-2">
          <Sparkles className="w-3.5 h-3.5" />
          <span>Cửa hàng & Gói Hội Viên</span>
        </div>
        <h2 className="text-2xl font-black text-slate-900 tracking-tight">Đăng ký dịch vụ & Mở khóa đặc quyền</h2>
        <p className="text-xs text-slate-500">
          Nâng cấp tài khoản PRO hoặc sở hữu các khóa học chuyên sâu với quyền truy cập có thời hạn
        </p>
      </div>

      {loading && <LoadingSpinner message="Đang tải cửa hàng..." />}
      {error && <ErrorBanner message={error} onRetry={fetchProducts} />}

      {!loading && !error && products.length === 0 && (
        <EmptyState
          title="Chưa có sản phẩm nào đang mở bán"
          description="Lớp học chưa mở bán gói hội viên hoặc khóa học nào."
        />
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {products.map((prod) => (
          <div
            key={prod.id}
            className={`rounded-3xl border p-6 flex flex-col justify-between transition ${
              prod.userHasActiveEntitlement
                ? 'bg-emerald-50/40 border-emerald-300 shadow-sm'
                : 'bg-white border-slate-200 hover:shadow-md hover:border-indigo-300'
            }`}
          >
            <div>
              <div className="flex items-center justify-between mb-4">
                <span className="text-[11px] font-bold px-3 py-1 rounded-full bg-slate-100 text-slate-700 uppercase">
                  {prod.durationDays} Ngày hiệu lực
                </span>

                {prod.userHasActiveEntitlement && (
                  <span className="inline-flex items-center space-x-1 text-xs font-bold text-emerald-700 bg-emerald-100 px-2.5 py-0.5 rounded-full">
                    <CheckCircle className="w-3.5 h-3.5" />
                    <span>Đang sở hữu</span>
                  </span>
                )}
              </div>

              <h3 className="text-xl font-bold text-slate-900 mb-2">{prod.title}</h3>
              <p className="text-xs text-slate-500 line-clamp-3 mb-6">
                {prod.description || 'Gói dịch vụ học tập nâng cao trọn gói.'}
              </p>

              {prod.targetCourseTitle && (
                <div className="text-xs bg-indigo-50/70 p-3 rounded-xl border border-indigo-100 text-indigo-900 mb-4">
                  <span className="font-bold block mb-0.5">Mở khóa khóa học:</span>
                  <span>{prod.targetCourseTitle}</span>
                </div>
              )}

              {prod.userHasActiveEntitlement && prod.entitlementExpiresAt && (
                <div className="text-xs text-emerald-800 bg-emerald-100/70 p-3 rounded-xl mb-4 flex items-center space-x-2">
                  <Clock className="w-4 h-4 flex-shrink-0" />
                  <span>Hết hạn: {new Date(prod.entitlementExpiresAt).toLocaleDateString('vi-VN')}</span>
                </div>
              )}
            </div>

            <div className="pt-4 border-t border-slate-100 flex items-center justify-between">
              <div>
                <span className="text-xs text-slate-400 block leading-tight">Giá trọn gói</span>
                <span className="text-xl font-black text-indigo-600">{formatPrice(prod.price)}</span>
              </div>

              <button
                onClick={() => handleBuy(prod)}
                disabled={!checkoutAvailable}
                title={!checkoutAvailable ? 'Thanh toán chưa được cấu hình cho lớp này' : undefined}
                className={`px-4 py-2.5 rounded-xl text-xs font-bold transition shadow-sm ${
                  !checkoutAvailable
                    ? 'bg-slate-200 text-slate-500 cursor-not-allowed'
                    : prod.userHasActiveEntitlement
                    ? 'bg-emerald-600 hover:bg-emerald-700 text-white'
                    : 'bg-indigo-600 hover:bg-indigo-700 text-white'
                }`}
              >
                {!checkoutAvailable ? 'Tạm chưa hỗ trợ thanh toán' : prod.userHasActiveEntitlement ? 'Gia hạn thêm' : 'Mua ngay'}
              </button>
            </div>
          </div>
        ))}
      </div>

      {/* Checkout & Mock Sandbox Payment Modal */}
      {currentOrder && (
        <div className="fixed inset-0 bg-slate-950/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-white rounded-3xl max-w-md w-full p-6 shadow-2xl border border-slate-200">
            <div className="flex justify-between items-start mb-4">
              <div>
                <span className="text-[11px] font-mono font-bold text-indigo-600 uppercase">
                  Mã đơn: {currentOrder.orderNumber}
                </span>
                <h3 className="text-xl font-bold text-slate-900 mt-0.5">Trạng thái thanh toán</h3>
              </div>
              <StatusBadge status={currentOrder.status} />
            </div>

            <div className="bg-slate-50 p-4 rounded-2xl border border-slate-100 mb-6 space-y-2 text-xs">
              <div className="flex justify-between">
                <span className="text-slate-500">Sản phẩm:</span>
                <span className="font-bold text-slate-800">{selectedProduct?.title}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">Thời hạn sử dụng:</span>
                <span className="font-bold text-slate-800">{selectedProduct?.durationDays} ngày</span>
              </div>
              <div className="flex justify-between pt-2 border-t border-slate-200 text-sm">
                <span className="font-bold text-slate-700">Tổng thanh toán:</span>
                <span className="font-black text-indigo-600">{formatPrice(currentOrder.totalAmount)}</span>
              </div>
            </div>

            {paymentError && (
              <div className="p-3 bg-rose-50 border border-rose-200 rounded-xl text-xs text-rose-700 flex items-center space-x-2">
                <AlertCircle className="w-4 h-4 text-rose-600 flex-shrink-0" />
                <span>{paymentError}</span>
              </div>
            )}

            {
              <div className="space-y-3">
                <div className="p-3.5 bg-amber-50 border border-amber-200 rounded-xl text-xs text-amber-800 space-y-1">
                  <div className="font-bold flex items-center space-x-1.5">
                    <AlertCircle className="w-4 h-4 text-amber-600 flex-shrink-0" />
                    <span>{checkoutAvailable ? 'Thanh toán đang chờ xác nhận' : 'Chưa cấu hình cổng thanh toán'}</span>
                  </div>
                  <p className="text-[11px] text-amber-700 leading-relaxed">
                    {checkoutAvailable
                      ? 'Đơn hàng sẽ ở trạng thái chờ xác nhận. Quản trị lớp xử lý xác nhận, người mua không thể tự đánh dấu đã thanh toán.'
                      : 'Chức năng mua sẽ khả dụng khi lớp cấu hình một phương thức thanh toán.'}
                  </p>
                </div>

                <button
                  disabled={processing}
                  onClick={refreshOrderStatus}
                  className="w-full py-2.5 text-center text-xs font-semibold text-indigo-700 hover:text-indigo-900 disabled:opacity-50"
                >
                  Làm mới trạng thái đơn hàng
                </button>
                <button
                  disabled={processing}
                  onClick={() => {
                    setCurrentOrder(null);
                    setSelectedProduct(null);
                    setCheckoutKey(null);
                  }}
                  className="w-full py-2.5 text-center text-xs font-semibold text-slate-500 hover:text-slate-800 disabled:opacity-50"
                >
                  {processing ? 'Đang xử lý…' : 'Đóng cửa sổ'}
                </button>
              </div>
            }
          </div>
        </div>
      )}
    </div>
  );
};
