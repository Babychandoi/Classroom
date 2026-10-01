// D-19: the three error codes the class-access feature added, and the Vietnamese sentence a person should read for each.
// Kept free of imports so api/client.ts (which builds every ApiException) and the UI helpers can both use it.
//
//  - INVITE_REQUIRED   (403) a PRIVATE class cannot be joined by id.
//  - PAYMENT_REQUIRED  (402) a PAID class needs its class-access product bought first (error.details.accessProduct).
//  - MEMBERSHIP_EXPIRED(403) the paid access of a member has lapsed: everything except About / Store is closed.
export const INVITE_REQUIRED = 'INVITE_REQUIRED';
export const PAYMENT_REQUIRED = 'PAYMENT_REQUIRED';
export const MEMBERSHIP_EXPIRED = 'MEMBERSHIP_EXPIRED';

export const FRIENDLY_CODE_MESSAGES: Record<string, string> = {
  [INVITE_REQUIRED]: 'Đây là lớp riêng tư. Bạn cần liên kết mời của chủ lớp để tham gia.',
  [PAYMENT_REQUIRED]: 'Lớp học này có thu phí. Hãy mua gói truy cập để tham gia.',
  [MEMBERSHIP_EXPIRED]: 'Gói thành viên của bạn trong lớp đã hết hạn. Hãy gia hạn để tiếp tục học và thảo luận.',
};

/** The friendly sentence for a class-access error code, or `fallback` (the server's own message) for every other code. */
export function friendlyMessageFor(code: string | undefined, fallback: string): string {
  return (code && FRIENDLY_CODE_MESSAGES[code]) || fallback;
}

/** Fired on `window` whenever the server answers MEMBERSHIP_EXPIRED, so the open classroom can re-read its membership state. */
export const MEMBERSHIP_EXPIRED_EVENT = 'classroom:membership-expired';
