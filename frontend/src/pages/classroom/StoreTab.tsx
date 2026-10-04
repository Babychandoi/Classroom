import React, { useEffect, useState } from 'react';
import { useOutletContext, useNavigate } from 'react-router-dom';
import { Classroom, Product, Order } from '../../types';
import { api } from '../../api/client';
import { durationLabel, formatDate, formatDateTime, formatDong } from '../../api/format';
import { useAuth } from '../../context/AuthContext';
import { useCheckout } from '../../hooks/useCheckout';
import { CheckoutDialog, OrderStatusBadge } from '../../components/CheckoutDialog';
import { LoadingSpinner, ErrorBanner, EmptyState } from '../../components/UIStates';
import { Badge, ClassAvatar, CoverImage, buttonClass } from '../../components/ui';
import { BookOpen, Check, CheckCircle, Clock, KeyRound, Receipt, ShieldCheck, Sparkles, XCircle } from 'lucide-react';

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

  // D-19: the product that sells membership of a PAID class. It has no fixed length when durationDays is 0 (lifetime), and what the
  // button offers depends on who is looking: staff never pay, a member without an expiry already has unlimited access.
  const isStaffOrOwner = classroom.isOwner === true || classroom.userRole === 'OWNER' || classroom.userRole === 'STAFF';
  const isOpenEndedMember = classroom.isMember === true && !classroom.accessExpiresAt && classroom.memberState !== 'EXPIRED';
  const isPaidClass = classroom.accessType === 'PAID';
  const isMemberNow = classroom.isMember === true || classroom.isOwner === true;

  // "Mua ngay" is not in the design's vocabulary: an ordinary product offers "Nhận quyền lợi".
  const productAction = (prod: Product): { label: string; disabled: boolean; note?: string } => {
    const accessProduct = prod.kind === 'CLASS_ACCESS';
    if (!checkoutAvailable) return { label: 'Tạm chưa hỗ trợ thanh toán', disabled: true };
    if (accessProduct) {
      if (isStaffOrOwner) {
        return { label: 'Không cần mua', disabled: true, note: 'Quản trị lớp không phải trả phí vào lớp.' };
      }
      if (isOpenEndedMember) {
        return { label: 'Đang có quyền không hạn', disabled: true, note: 'Bạn đã có quyền truy cập lớp không giới hạn thời gian.' };
      }
      if (classroom.memberState === 'EXPIRED') return { label: 'Gia hạn', disabled: false };
      if (!isMemberNow) return { label: 'Mua để tham gia', disabled: false };
      return { label: 'Gia hạn thêm', disabled: false };
    }
    // Every other product needs a membership: in a paid class a person outside it cannot buy one.
    if (isPaidClass && user && !isMemberNow) {
      return { label: 'Cần là thành viên', disabled: true, note: 'Hãy mua gói vào lớp trước.' };
    }
    if (prod.userHasActiveEntitlement || prod.userOwnsUpcoming) {
      return { label: 'Gia hạn thêm', disabled: false };
    }
    return { label: 'Nhận quyền lợi', disabled: false };
  };

  const accessProducts = products.filter((p) => p.kind === 'CLASS_ACCESS');
  const otherProducts = products.filter((p) => p.kind !== 'CLASS_ACCESS');
  const card = 'rounded-card border border-slate-200 bg-white shadow-hairline';

  /** "Đang sở hữu" / "Đã mua — bắt đầu từ …" + the expiry of what the viewer owns. */
  const ownership = (prod: Product) => (
    <>
      {prod.userHasActiveEntitlement && (
        <Badge tone="success" size="sm">
          <CheckCircle className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />
          <span>Đang sở hữu</span>
        </Badge>
      )}
      {/* R19-06: paid for but the access has not started yet (pre-sale) - owned, so not "Nhận quyền lợi". */}
      {!prod.userHasActiveEntitlement && prod.userOwnsUpcoming && (
        <Badge tone="member" size="sm">
          <CheckCircle className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />
          <span>
            {prod.entitlementStartsAt
              ? `Đã mua — bắt đầu từ ${formatDate(prod.entitlementStartsAt)}`
              : 'Đã mua — sắp bắt đầu'}
          </span>
        </Badge>
      )}
    </>
  );

  const expiryNote = (prod: Product) =>
    (prod.userHasActiveEntitlement || prod.userOwnsUpcoming) && prod.entitlementExpiresAt ? (
      <p className="flex items-center gap-2 rounded-btn bg-slate-50 px-3 py-2 text-meta text-slate-600">
        <Clock className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" />
        <span className="tabular">Hết hạn: {formatDate(prod.entitlementExpiresAt)}</span>
      </p>
    ) : null;

  return (
    <div className="space-y-8">
      {/* Storefront identity */}
      <section className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <div className="flex min-w-0 items-center gap-4">
          <ClassAvatar title={classroom.title} seed={classroom.id} size={48} />
          <div className="min-w-0">
            <h2 className="text-h2-sm font-semibold text-slate-900">Shop của lớp</h2>
            <p className="mt-0.5 text-ui text-slate-600">Gói vào lớp, gói hội viên và khóa học do giáo viên của lớp mở bán.</p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <span className="inline-flex h-8 items-center gap-1.5 rounded-full border border-slate-200 bg-white px-3 text-meta font-medium text-slate-600">
            <ShieldCheck className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
            Quyền truy cập mở khi đơn được xác nhận
          </span>
          <span className="inline-flex h-8 items-center gap-1.5 rounded-full border border-slate-200 bg-white px-3 text-meta font-medium text-slate-600">
            <Receipt className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
            Hủy được đơn đang chờ
          </span>
        </div>
      </section>

      {loading && <LoadingSpinner message="Đang tải cửa hàng..." />}
      {error && <ErrorBanner message={error} onRetry={fetchProducts} />}

      {!loading && !error && products.length === 0 && (
        <EmptyState
          title="Chưa có sản phẩm nào đang mở bán"
          description="Lớp học chưa mở bán gói hội viên hoặc khóa học nào. Bạn có thể quay lại sau, hoặc hỏi giáo viên trong Thảo luận."
        />
      )}

      {/* D-19: the class-access product, featured with the design's buy-box (radio-card + one primary action). */}
      {accessProducts.map((prod) => {
        const action = productAction(prod);
        const per = durationLabel(prod.durationDays);
        return (
          <section key={prod.id} data-testid="class-access-product" className={`grid overflow-hidden md:grid-cols-[minmax(0,1fr)_360px] ${card}`}>
            <div className="flex min-w-0 flex-col p-5 sm:p-8">
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone="paid" size="sm">
                  <KeyRound className="h-3.5 w-3.5" strokeWidth={1.75} aria-hidden="true" />
                  <span>Gói vào lớp</span>
                </Badge>
                {ownership(prod)}
              </div>
              <h3 className="mt-3 text-h2 font-semibold text-slate-900">{prod.title}</h3>
              <p className="mt-2 max-w-[520px] text-body-sm text-slate-600">
                {prod.description || 'Mua gói này để tham gia lớp học: học tập, luyện thi, thảo luận và xem tài liệu của lớp.'}
              </p>
              <ul className="mt-4 space-y-2">
                {['Toàn bộ khóa học và bài thi của lớp', 'Thảo luận, tài liệu và bảng xếp hạng', 'Gia hạn bất cứ lúc nào — không tự trừ tiền'].map((text) => (
                  <li key={text} className="flex items-start gap-2 text-ui text-slate-900">
                    <Check className="mt-0.5 h-4 w-4 flex-shrink-0 text-green-600" strokeWidth={2.4} aria-hidden="true" />
                    {text}
                  </li>
                ))}
              </ul>
            </div>

            <div className="flex flex-col gap-3 border-t border-slate-100 bg-slate-50 p-5 sm:p-6 md:border-l md:border-t-0">
              <p className="text-meta font-semibold text-slate-900">Thời hạn</p>
              <div className="grid grid-cols-[auto_1fr_auto] items-center gap-3 rounded-[14px] border-2 border-blue-600 bg-tint px-3.5 py-3">
                <span aria-hidden="true" className="h-[18px] w-[18px] flex-shrink-0 rounded-full border-[5px] border-blue-600 bg-white" />
                <span className="min-w-0">
                  <span className="block text-ui font-semibold text-slate-900">Truy cập lớp</span>
                  <span className="block text-caption text-slate-600">{per}</span>
                </span>
                <span className="text-body-sm font-semibold text-slate-900 tabular">{formatDong(prod.price)}</span>
              </div>
              {expiryNote(prod)}
              {classroom.memberState === 'EXPIRED' && classroom.accessExpiresAt && (
                <p className="flex items-center gap-2 rounded-btn bg-warn-soft px-3 py-2 text-meta text-amber-800">
                  <Clock className="h-4 w-4 flex-shrink-0" strokeWidth={1.75} aria-hidden="true" />
                  <span className="tabular">Gói thành viên đã hết hạn ngày {formatDate(classroom.accessExpiresAt)}</span>
                </p>
              )}
              <button
                type="button"
                onClick={() => handleBuy(prod)}
                disabled={action.disabled}
                title={!checkoutAvailable ? 'Thanh toán chưa được cấu hình cho lớp này' : undefined}
                className={buttonClass('primary', 'lg', 'mt-1 w-full !h-12')}
              >
                {action.label}
              </button>
              {action.note && <p className="text-center text-caption text-slate-600">{action.note}</p>}
              <p className="flex items-start gap-2 border-t border-slate-200 pt-3 text-meta text-slate-600">
                <ShieldCheck className="mt-0.5 h-4 w-4 flex-shrink-0 text-slate-400" strokeWidth={1.75} aria-hidden="true" />
                Quyền vào lớp mở ngay khi đơn được xác nhận thanh toán.
              </p>
            </div>
          </section>
        );
      })}

      {otherProducts.length > 0 && (
        <section>
          <div className="mb-5 flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1">
            <h2 className="text-h2-sm font-semibold text-slate-900">
              Sản phẩm · <span className="font-medium text-slate-500 tabular">{otherProducts.length}</span>
            </h2>
            <p className="text-meta text-slate-500">Mỗi gói mở quyền truy cập trong thời hạn ghi trên thẻ.</p>
          </div>
          <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
            {otherProducts.map((prod) => {
              const action = productAction(prod);
              const Icon = prod.targetCourseTitle ? BookOpen : Sparkles;
              return (
                <article key={prod.id} data-testid="store-product" className={`flex flex-col overflow-hidden ${card}`}>
                  <div className="relative aspect-[16/9]">
                    <CoverImage seed={prod.targetCourseId || prod.id} icon={<Icon className="h-10 w-10" strokeWidth={1.5} aria-hidden="true" />} />
                    <div className="absolute right-3 top-3 flex flex-col items-end gap-1.5">{ownership(prod)}</div>
                  </div>
                  <div className="flex flex-1 flex-col px-[18px] pb-[18px] pt-4">
                    <p className="text-caption font-medium text-slate-600 tabular">
                      {prod.targetCourseTitle ? 'Khóa học' : 'Gói thành viên'} · {durationLabel(prod.durationDays)}
                    </p>
                    <h3 className="mt-1 text-body font-semibold text-slate-900">{prod.title}</h3>
                    <p className="mt-1 line-clamp-2 text-meta text-slate-600">{prod.description || 'Gói dịch vụ học tập nâng cao trọn gói.'}</p>
                    {prod.targetCourseTitle && (
                      <p className="mt-3 rounded-btn bg-slate-50 px-3 py-2 text-meta text-slate-600">
                        Mở khóa khóa học: <span className="font-semibold text-slate-900">{prod.targetCourseTitle}</span>
                      </p>
                    )}
                    {expiryNote(prod) && <div className="mt-3">{expiryNote(prod)}</div>}
                    <div className="mt-auto pt-4">
                      <div className="flex items-center justify-between gap-3">
                        <span className="text-h3 font-semibold text-slate-900 tabular">{formatDong(prod.price)}</span>
                        <button
                          type="button"
                          onClick={() => handleBuy(prod)}
                          disabled={action.disabled}
                          title={!checkoutAvailable ? 'Thanh toán chưa được cấu hình cho lớp này' : undefined}
                          className={buttonClass('secondary', 'md')}
                        >
                          {action.label}
                        </button>
                      </div>
                      {action.note && <p className="mt-2 text-caption text-slate-600">{action.note}</p>}
                    </div>
                  </div>
                </article>
              );
            })}
          </div>
        </section>
      )}

      {/* R13-06 (FR-07/TC-17): "Đơn hàng của tôi" */}
      <section className={`overflow-hidden ${card}`}>
        <div className="flex items-center gap-2 border-b border-slate-100 px-5 py-4 sm:px-6">
          <Receipt className="h-4 w-4 text-slate-600" strokeWidth={1.75} aria-hidden="true" />
          <h2 className="text-ui font-semibold text-slate-900">Đơn hàng của tôi</h2>
        </div>

        {!user && (
          <div className="flex flex-col gap-3 p-5 sm:flex-row sm:items-center sm:justify-between sm:px-6">
            <p className="text-ui text-slate-600">Đăng nhập để xem và quản lý các đơn hàng của bạn trong lớp học này.</p>
            <button
              type="button"
              onClick={() => navigate('/login', { state: { from: { pathname: `/classes/${classroom.slug}/store` } } })}
              className={buttonClass('secondary', 'md', 'flex-shrink-0')}
            >
              Đăng nhập
            </button>
          </div>
        )}

        {user && myOrdersLoading && <div className="p-6"><LoadingSpinner message="Đang tải đơn hàng của bạn..." /></div>}
        {user && myOrdersError && <div className="p-6"><ErrorBanner message={myOrdersError} onRetry={fetchMyOrders} /></div>}

        {user && !myOrdersLoading && !myOrdersError && myOrders.length === 0 && (
          <div className="p-5 text-ui text-slate-600 sm:px-6">Bạn chưa có đơn hàng nào trong lớp học này.</div>
        )}

        {user && !myOrdersLoading && !myOrdersError && myOrders.length > 0 && (
          <ul className="divide-y divide-slate-100">
            {myOrders.map((order) => (
              <li key={order.id} className="flex flex-col gap-2 px-5 py-4 sm:flex-row sm:items-center sm:justify-between sm:px-6">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-caption font-semibold text-slate-500 tabular">{order.orderNumber}</span>
                    <OrderStatusBadge status={order.status} />
                  </div>
                  <p className="mt-1 text-ui font-semibold text-slate-900">
                    {order.items?.map((i) => i.productName).join(', ') || 'Sản phẩm'}
                  </p>
                  <p className="mt-0.5 text-caption text-slate-600 tabular">
                    Tạo lúc {formatDateTime(order.createdAt)}
                    {order.paidAt && ` · Thanh toán ${formatDateTime(order.paidAt)}`}
                    {order.refundedAt && ` · Hoàn tiền ${formatDateTime(order.refundedAt)}`}
                  </p>
                </div>
                <div className="flex items-center gap-3">
                  <span className="text-body-sm font-semibold text-slate-900 tabular">{formatDong(order.totalAmount)}</span>
                  {order.status === 'PENDING' && (
                    <button
                      type="button"
                      onClick={() => cancelMyOrder(order.id)}
                      disabled={cancellingOrderId === order.id}
                      className={buttonClass('ghost', 'sm', '!text-red-700 hover:!bg-red-50')}
                    >
                      <XCircle className="h-4 w-4" strokeWidth={1.75} aria-hidden="true" />
                      <span>{cancellingOrderId === order.id ? 'Đang hủy...' : 'Hủy đơn'}</span>
                    </button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      <p className="text-center text-meta text-slate-500">
        Câu hỏi trước khi mua?{' '}
        <button type="button" onClick={() => navigate(`/classes/${classroom.slug}/feed`)} className="font-medium text-blue-600 hover:text-blue-700">
          Hỏi công khai trong Thảo luận
        </button>
      </p>

      {/* Checkout & Mock Sandbox Payment Modal (shared with the class paywall - hooks/useCheckout.ts) */}
      <CheckoutDialog checkout={checkout} />
    </div>
  );
};
