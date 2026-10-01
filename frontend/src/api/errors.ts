import { ApiException } from './client';
import { INVITE_REQUIRED, MEMBERSHIP_EXPIRED, PAYMENT_REQUIRED } from './errorMessages';
import type { ClassAccessProduct } from '../types';

export { INVITE_REQUIRED, MEMBERSHIP_EXPIRED, PAYMENT_REQUIRED, friendlyMessageFor, FRIENDLY_CODE_MESSAGES } from './errorMessages';

/** True when `err` is an ApiException with this `error.code`. */
export const hasErrorCode = (err: unknown, code: string): err is ApiException =>
  err instanceof ApiException && err.code === code;

export const isInviteRequired = (err: unknown) => hasErrorCode(err, INVITE_REQUIRED);
export const isPaymentRequired = (err: unknown) => hasErrorCode(err, PAYMENT_REQUIRED);
export const isMembershipExpired = (err: unknown) => hasErrorCode(err, MEMBERSHIP_EXPIRED);

/**
 * The class-access product a PAYMENT_REQUIRED (402) hands the UI in `error.details.accessProduct`, so checkout can open right away.
 * Null when `err` is not a PAYMENT_REQUIRED or the payload is malformed.
 */
export function accessProductFromError(err: unknown): ClassAccessProduct | null {
  if (!isPaymentRequired(err)) return null;
  const raw = (err.details as { accessProduct?: Partial<ClassAccessProduct> } | undefined)?.accessProduct;
  if (!raw || typeof raw.id !== 'string' || raw.id === '') return null;
  const price = Number(raw.price);
  if (!Number.isFinite(price)) return null;
  const durationDays = raw.durationDays == null || Number(raw.durationDays) <= 0 ? null : Number(raw.durationDays);
  return {
    id: raw.id,
    price,
    currency: raw.currency || 'VND',
    durationDays,
    lifetime: durationDays === null,
  };
}
