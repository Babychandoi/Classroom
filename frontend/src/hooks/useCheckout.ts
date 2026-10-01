import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import { generateIdempotencyKey } from '../api/checkout';
import { fetchCheckoutAvailability } from '../api/payments';
import type { CreateOrderRequest, Order } from '../types';

/** What is being bought, as much as the checkout dialog needs to describe it. */
export interface CheckoutItem {
  id: string;
  title: string;
  /** null / 0 = lifetime (a class-access product) */
  durationDays?: number | null;
  price?: number;
}

export type CheckoutChange = 'created' | 'refreshed' | 'cancelled';

export type BuyResult =
  | { ok: true; order: Order }
  /** `definitive` = a 4xx: the server will never accept this exact request, so the idempotency key was dropped. */
  | { ok: false; error: unknown; definitive: boolean };

export interface UseCheckoutOptions {
  /** The class the order belongs to. */
  classId: string | undefined;
  /** Runs after every change of the order so the caller can refresh what depends on it (classroom, products, order list). */
  onChanged?: (change: CheckoutChange, order: Order) => Promise<void> | void;
  /**
   * Ask the deployment whether checkout exists at all (GET /payments/sandbox-status, answered once per page load - see
   * api/payments.ts). Callers that may never open a checkout pass false so a plain page view makes no such request.
   */
  checkAvailability?: boolean;
}

export type Checkout = ReturnType<typeof useCheckout>;

/**
 * The one purchase flow shared by the Store tab, the class paywall / renewal prompt and the invite page (it used to live in
 * StoreTab). Idempotency semantics (R3) are unchanged: the key minted for an attempt is KEPT after a network error or 5xx (the request may
 * have been accepted even though the answer was lost, so a retry must reuse it) and DROPPED after a success (the order exists) or a
 * definitive 4xx (this exact request will never succeed). The key is per (product, invite code).
 */
export function useCheckout({ classId, onChanged, checkAvailability = true }: UseCheckoutOptions) {
  const [item, setItem] = useState<CheckoutItem | null>(null);
  const [order, setOrder] = useState<Order | null>(null);
  const [processing, setProcessing] = useState(false);
  const [paymentError, setPaymentError] = useState<string | null>(null);
  const [checkoutAvailable, setCheckoutAvailable] = useState<boolean | null>(null);
  const keyRef = useRef<{ scope: string; key: string } | null>(null);
  const onChangedRef = useRef(onChanged);
  onChangedRef.current = onChanged;

  // R17-06: a deployment-wide fact, so asked once per mount (and once per page load overall - see fetchCheckoutAvailability).
  useEffect(() => {
    if (!checkAvailability) return undefined;
    let active = true;
    void fetchCheckoutAvailability().then((available) => { if (active) setCheckoutAvailable(available); });
    return () => { active = false; };
  }, [checkAvailability]);

  const notify = useCallback(async (change: CheckoutChange, next: Order) => {
    try {
      await onChangedRef.current?.(change, next);
    } catch {
      // A failing follow-up refresh must not turn a successful purchase into a "failed" one.
    }
  }, []);

  const buy = useCallback(async (product: CheckoutItem, extra?: { inviteCode?: string }): Promise<BuyResult> => {
    if (!classId) return { ok: false, error: new Error('Thiếu lớp học'), definitive: true };
    const inviteCode = extra?.inviteCode || undefined;
    const scope = `${product.id}|${inviteCode ?? ''}`;
    setItem(product);
    setPaymentError(null);
    setProcessing(true);
    const key = keyRef.current?.scope === scope ? keyRef.current.key : generateIdempotencyKey();
    keyRef.current = { scope, key };
    try {
      const body: CreateOrderRequest = { classId, productId: product.id, idempotencyKey: key };
      if (inviteCode) body.inviteCode = inviteCode;
      const created = await api.post<Order>('/orders', body, { headers: { 'Idempotency-Key': key } });
      // Success: the key has been consumed by a completed order, so a later retry must mint a new one.
      keyRef.current = null;
      setOrder(created);
      // An order may already be settled out-of-band by an operator; refresh entitlement state.
      await notify('created', created);
      return { ok: true, order: created };
    } catch (err: any) {
      // Keep the idempotency key on network errors and 5xx so a retry reuses it (the request may
      // have been accepted server-side even though the response was lost). Only clear it on a
      // definitive 4xx, where the server is telling us this exact request will never succeed.
      const status = err?.status;
      const definitive = typeof status === 'number' && status >= 400 && status < 500;
      if (definitive) {
        keyRef.current = null;
        // Only clear the in-progress selection on a definitive rejection: a retry after a network/5xx error must
        // still see the same item so it recognizes the pending idempotency key belongs to it and reuses it.
        setItem(null);
      }
      return { ok: false, error: err, definitive };
    } finally {
      setProcessing(false);
    }
  }, [classId, notify]);

  const refreshOrderStatus = useCallback(async () => {
    if (!order) return;
    try {
      const next = await api.get<Order>(`/orders/${order.id}`);
      setOrder(next);
      await notify('refreshed', next);
    } catch (err: any) {
      setPaymentError(err.message || 'Không thể làm mới trạng thái đơn hàng');
    }
  }, [order, notify]);

  // R13-03: buyer can cancel their own still-PENDING order (SRS §5 order state machine).
  const cancelOrder = useCallback(async () => {
    if (!order) return;
    setProcessing(true);
    setPaymentError(null);
    try {
      const next = await api.post<Order>(`/orders/${order.id}/cancel`);
      setOrder(next);
      await notify('cancelled', next);
    } catch (err: any) {
      setPaymentError(err.message || 'Không thể hủy đơn hàng');
    } finally {
      setProcessing(false);
    }
  }, [order, notify]);

  const close = useCallback(() => {
    if (processing) return;
    setOrder(null);
    setItem(null);
    keyRef.current = null;
  }, [processing]);

  return { item, order, processing, paymentError, checkoutAvailable, buy, refreshOrderStatus, cancelOrder, close };
}
