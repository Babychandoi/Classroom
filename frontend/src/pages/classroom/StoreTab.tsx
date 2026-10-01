import React, { useEffect, useState } from 'react';
import { useOutletContext, useNavigate } from 'react-router-dom';
import { Classroom, Product, Order } from '../../types';
import { api } from '../../api/client';
import { durationLabel, formatDate } from '../../api/format';
import { useAuth } from '../../context/AuthContext';
import { useCheckout } from '../../hooks/useCheckout';
import { CheckoutDialog } from '../../components/CheckoutDialog';
import { LoadingSpinner, ErrorBanner, EmptyState, StatusBadge } from '../../components/UIStates';
import { CheckCircle, Clock, Sparkles, Receipt, XCircle, KeyRound } from 'lucide-react';

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

  // R13-06 (FR-07/TC-17): "Đơn hàng của tôi" — the buyer's own orders in this class.
  const [myOrders, setMyOrders] = useState<Order[]>([]);
  const [myOrdersLoading, setMyOrdersLoading] = useState(true);
  const [myOrdersError, setMyOrdersError] = useState<string | null>(null);
  const [cancellingOrderId, setCancellingOrderId] = useState<string | null>(null);

  const fetchMyOrders = async () => {
    // A guest has no orders and GET /me/orders would answer 401, which used to surface as a red
    // "Chưa đăng nhập hoặc phiên đã hết hạn" banner on a perfectly normal public page. Don't ask.
    if (!user) {
      setMyOrders([]);
      setMyOrdersError(null);
      setMyOrdersLoading(false);
      return;
    }
    try {
      setMyOrdersLoading(true);
      setMyOrdersError(null);
      const data = await api.get<Order[]>(`/me/orders?classId=${classroom.id}`);
      setMyOrders(data || []);
    } catch (err: any) {
      setMyOrdersError(err.message || 'Không thể tải danh sách đơn hàng của bạn');
    } finally {
      setMyOrdersLoading(false);
    }
  };

  const cancelMyOrder = async (orderId: string) => {
    setCancellingOrderId(orderId);
    try {
      await api.post<Order>(`/orders/${orderId}/cancel`);
      await fetchMyOrders();
      await fetchProducts();
    } catch (err: any) {
      alert(err.message || 'Không thể hủy đơn hàng');
    } finally {
      setCancellingOrderId(null);
    }
  };

  const fetchProducts = async () => {
    try {
      setLoading(true);
      setError(null);
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
    fetchMyOrders();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classroom.id, user]);

  // The shared purchase flow (hooks/useCheckout.ts): idempotency key kept on network/5xx, dropped on success / definitive 4xx.
  const checkout = useCheckout({
    classId: classroom.id,
    onChanged: async (change) => {
      // An order may already be settled out-of-band by an operator; refresh entitlement state.
      if (change !== 'cancelled') await refreshClassroom();
      if (change !== 'created') await fetchProducts();
      await fetchMyOrders();
    },
  });
  const checkoutAvailable = checkout.checkoutAvailable === true;

  const handleBuy = async (prod: Product) => {
    if (!user) {
      // R17-01: come back to the store after signing in.
      navigate('/login', { state: { from: { pathname: `/classes/${classroom.slug}/store` } } });
      return;
    }
    const result = await checkout.buy(prod);
    if (!result.ok) {
      const err = result.error as { message?: string };
      alert(err?.message || 'Khởi tạo đơn hàng thất bại');
    }
  };

  const formatPrice = (price: number) => {
    return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' }).format(price);
  };

  // D-19: the product that sells membership of a PAID class. It has no fixed length when durationDays is 0 (lifetime), and what the
  // button offers depends on who is looking: staff never pay, a member without an expiry already has unlimited access.
  const isStaffOrOwner = classroom.isOwner === true || classroom.userRole === 'OWNER' || classroom.userRole === 'STAFF';
  const isOpenEndedMember = classroom.isMember === true && !classroom.accessExpiresAt && classroom.memberState !== 'EXPIRED';
  const isPaidClass = classroom.accessType === 'PAID';
  const isMemberNow = classroom.isMember === true || classroom.isOwner === true;

  const productAction = (prod: Product): { label: string; disabled: boolean; note?: string; tone: string } => {
    const accessProduct = prod.kind === 'CLASS_ACCESS';
    const disabledTone = 'bg-slate-200 text-slate-500 cursor-not-allowed';
    if (!checkoutAvailable) return { label: 'Tạm chưa hỗ trợ thanh toán', disabled: true, tone: disabledTone };
    if (accessProduct) {
      if (isStaffOrOwner) {
        return { label: 'Không cần mua', disabled: true, note: 'Quản trị lớp không phải trả phí vào lớp.', tone: disabledTone };
      }
      if (isOpenEndedMember) {
        return { label: 'Đang có quyền không hạn', disabled: true, note: 'Bạn đã có quyền truy cập lớp không giới hạn thời gian.', tone: disabledTone };
      }
      if (classroom.memberState === 'EXPIRED') return { label: 'Gia hạn', disabled: false, tone: 'bg-amber-700 hover:bg-amber-800 text-white' };
      if (!isMemberNow) return { label: 'Mua để tham gia', disabled: false, tone: 'bg-indigo-600 hover:bg-indigo-700 text-white' };
      return { label: 'Gia hạn thêm', disabled: false, tone: 'bg-emerald-600 hover:bg-emerald-700 text-white' };
    }
    // Every other product needs a membership: in a paid class a person outside it cannot buy one.
    if (isPaidClass && user && !isMemberNow) {
      return { label: 'Cần là thành viên', disabled: true, note: 'Hãy mua gói vào lớp trước.', tone: disabledTone };
    }
    if (prod.userHasActiveEntitlement || prod.userOwnsUpcoming) {
      return {
        label: 'Gia hạn thêm',
        disabled: false,
        tone: prod.userHasActiveEntitlement ? 'bg-emerald-600 hover:bg-emerald-700 text-white' : 'bg-indigo-500 hover:bg-indigo-600 text-white',
      };
    }
    return { label: 'Mua ngay', disabled: false, tone: 'bg-indigo-600 hover:bg-indigo-700 text-white' };
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
        {products.map((prod) => {
          const accessProduct = prod.kind === 'CLASS_ACCESS';
          const action = productAction(prod);
          return (
            <div
              key={prod.id}
              data-testid={accessProduct ? 'class-access-product' : undefined}
              className={`rounded-3xl border p-6 flex flex-col justify-between transition ${
                prod.userHasActiveEntitlement
                  ? 'bg-emerald-50/40 border-emerald-300 shadow-sm'
                  : prod.userOwnsUpcoming
                  ? 'bg-indigo-50/40 border-indigo-300 shadow-sm'
                  : accessProduct
                  ? 'bg-amber-50/40 border-amber-300 hover:shadow-md'
                  : 'bg-white border-slate-200 hover:shadow-md hover:border-indigo-300'
              }`}
            >
              <div>
                <div className="flex items-center justify-between mb-4 gap-2 flex-wrap">
                  <span className="text-[11px] font-bold px-3 py-1 rounded-full bg-slate-100 text-slate-700 uppercase">
                    {accessProduct ? durationLabel(prod.durationDays) : `${prod.durationDays} Ngày hiệu lực`}
                  </span>

                  {accessProduct && (
                    <span className="inline-flex items-center space-x-1 text-xs font-bold text-amber-900 bg-amber-100 px-2.5 py-0.5 rounded-full">
                      <KeyRound className="w-3.5 h-3.5" aria-hidden="true" />
                      <span>Gói vào lớp</span>
                    </span>
                  )}

                  {prod.userHasActiveEntitlement && (
                    <span className="inline-flex items-center space-x-1 text-xs font-bold text-emerald-700 bg-emerald-100 px-2.5 py-0.5 rounded-full">
                      <CheckCircle className="w-3.5 h-3.5" />
                      <span>Đang sở hữu</span>
                    </span>
                  )}

                  {/* R19-06: paid for but the access has not started yet (pre-sale) - owned, so not "Mua ngay". */}
                  {!prod.userHasActiveEntitlement && prod.userOwnsUpcoming && (
                    <span className="inline-flex items-center space-x-1 text-xs font-bold text-indigo-700 bg-indigo-100 px-2.5 py-0.5 rounded-full">
                      <CheckCircle className="w-3.5 h-3.5" />
                      <span>
                        {prod.entitlementStartsAt
                          ? `Đã mua — bắt đầu từ ${new Date(prod.entitlementStartsAt).toLocaleDateString('vi-VN')}`
                          : 'Đã mua — sắp bắt đầu'}
                      </span>
                    </span>
                  )}
                </div>

                <h3 className="text-xl font-bold text-slate-900 mb-2">{prod.title}</h3>
                <p className="text-xs text-slate-500 line-clamp-3 mb-6">
                  {prod.description ||
                    (accessProduct
                      ? 'Mua gói này để tham gia lớp học: học tập, luyện thi, thảo luận và xem tài liệu của lớp.'
                      : 'Gói dịch vụ học tập nâng cao trọn gói.')}
                </p>

                {prod.targetCourseTitle && (
                  <div className="text-xs bg-indigo-50/70 p-3 rounded-xl border border-indigo-100 text-indigo-900 mb-4">
                    <span className="font-bold block mb-0.5">Mở khóa khóa học:</span>
                    <span>{prod.targetCourseTitle}</span>
                  </div>
                )}

                {(prod.userHasActiveEntitlement || prod.userOwnsUpcoming) && prod.entitlementExpiresAt && (
                  <div className={`text-xs p-3 rounded-xl mb-4 flex items-center space-x-2 ${
                    prod.userHasActiveEntitlement ? 'text-emerald-800 bg-emerald-100/70' : 'text-indigo-800 bg-indigo-100/70'
                  }`}>
                    <Clock className="w-4 h-4 flex-shrink-0" />
                    <span>Hết hạn: {new Date(prod.entitlementExpiresAt).toLocaleDateString('vi-VN')}</span>
                  </div>
                )}

                {accessProduct && classroom.memberState === 'EXPIRED' && classroom.accessExpiresAt && (
                  <div className="text-xs p-3 rounded-xl mb-4 flex items-center space-x-2 text-amber-900 bg-amber-100/70">
                    <Clock className="w-4 h-4 flex-shrink-0" />
                    <span>Gói thành viên đã hết hạn ngày {formatDate(classroom.accessExpiresAt)}</span>
                  </div>
                )}
              </div>

              <div className="pt-4 border-t border-slate-100">
                <div className="flex items-center justify-between gap-3">
                  <div>
                    <span className="text-xs text-slate-500 block leading-tight">Giá trọn gói</span>
                    <span className="text-xl font-black text-indigo-600">{formatPrice(prod.price)}</span>
                  </div>

                  <button
                    onClick={() => handleBuy(prod)}
                    disabled={action.disabled}
                    title={!checkoutAvailable ? 'Thanh toán chưa được cấu hình cho lớp này' : undefined}
                    className={`px-4 py-2.5 rounded-xl text-xs font-bold transition shadow-sm ${action.tone}`}
                  >
                    {action.label}
                  </button>
                </div>
                {action.note && <p className="mt-2 text-[11px] text-slate-500">{action.note}</p>}
              </div>
            </div>
          );
        })}
      </div>

      {/* R13-06 (FR-07/TC-17): "Đơn hàng của tôi" */}
      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
        <div className="px-6 py-4 border-b border-slate-100 flex items-center space-x-2">
          <Receipt className="w-4 h-4 text-indigo-600" />
          <span className="text-sm font-bold text-slate-800">Đơn hàng của tôi</span>
        </div>

        {!user && (
          <div className="p-6 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
            <p className="text-sm text-slate-600">Đăng nhập để xem và quản lý các đơn hàng của bạn trong lớp học này.</p>
            <button
              type="button"
              onClick={() => navigate('/login', { state: { from: { pathname: `/classes/${classroom.slug}/store` } } })}
              className="inline-flex items-center justify-center px-4 py-2 rounded-xl text-sm font-bold text-white bg-indigo-600 hover:bg-indigo-700 transition"
            >
              Đăng nhập
            </button>
          </div>
        )}

        {user && myOrdersLoading && <div className="p-6"><LoadingSpinner message="Đang tải đơn hàng của bạn..." /></div>}
        {user && myOrdersError && <div className="p-6"><ErrorBanner message={myOrdersError} onRetry={fetchMyOrders} /></div>}

        {user && !myOrdersLoading && !myOrdersError && myOrders.length === 0 && (
          <div className="p-6 text-sm text-slate-500">Bạn chưa có đơn hàng nào trong lớp học này.</div>
        )}

        {user && !myOrdersLoading && !myOrdersError && myOrders.length > 0 && (
          <div className="divide-y divide-slate-100">
            {myOrders.map((order) => (
              <div key={order.id} className="px-6 py-4 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2">
                <div>
                  <div className="flex items-center space-x-2">
                    <span className="text-xs font-mono font-bold text-indigo-600">{order.orderNumber}</span>
                    <StatusBadge status={order.status} />
                  </div>
                  <p className="text-sm font-semibold text-slate-800 mt-0.5">
                    {order.items?.map((i) => i.productName).join(', ') || 'Sản phẩm'}
                  </p>
                  <p className="text-xs text-slate-500 mt-0.5">
                    Tạo lúc {new Date(order.createdAt).toLocaleString('vi-VN')}
                    {order.paidAt && ` · Thanh toán ${new Date(order.paidAt).toLocaleString('vi-VN')}`}
                    {order.refundedAt && ` · Hoàn tiền ${new Date(order.refundedAt).toLocaleString('vi-VN')}`}
                  </p>
                </div>
                <div className="flex items-center space-x-3">
                  <span className="text-sm font-black text-slate-900">{formatPrice(order.totalAmount)}</span>
                  {order.status === 'PENDING' && (
                    <button
                      type="button"
                      onClick={() => cancelMyOrder(order.id)}
                      disabled={cancellingOrderId === order.id}
                      className="inline-flex items-center space-x-1 px-3 py-1.5 rounded-lg text-xs font-bold text-rose-600 hover:bg-rose-50 disabled:opacity-50 transition"
                    >
                      <XCircle className="w-3.5 h-3.5" />
                      <span>{cancellingOrderId === order.id ? 'Đang hủy...' : 'Hủy đơn'}</span>
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Checkout & Mock Sandbox Payment Modal (shared with the class paywall - hooks/useCheckout.ts) */}
      <CheckoutDialog checkout={checkout} />
    </div>
  );
};
