import { MEMBERSHIP_EXPIRED, MEMBERSHIP_EXPIRED_EVENT, friendlyMessageFor } from './errorMessages';

export interface ApiError {
  code: string;
  message: string;
  requestId?: string;
  // D-19: structured payload of a few errors (PAYMENT_REQUIRED carries { classId, accessType, accessProduct }).
  details?: Record<string, unknown>;
}

export class ApiException extends Error {
  code: string;
  requestId?: string;
  status?: number;
  details?: Record<string, unknown>;

  constructor(code: string, message: string, requestId?: string, status?: number, details?: Record<string, unknown>) {
    super(message);
    this.name = 'ApiException';
    this.code = code;
    this.requestId = requestId;
    this.status = status;
    this.details = details;
  }
}

/**
 * Builds the exception for a server error body. D-19: INVITE_REQUIRED / PAYMENT_REQUIRED / MEMBERSHIP_EXPIRED get a friendly
 * Vietnamese message here, once, so that every screen that shows `err.message` (Feed, Learn, Exams, ...) says something a person can
 * act on instead of the raw server text. MEMBERSHIP_EXPIRED also tells the open classroom to re-read its membership state.
 */
function toApiException(error: ApiError, status: number): ApiException {
  const details = error.details && typeof error.details === 'object' ? error.details : undefined;
  if (error.code === MEMBERSHIP_EXPIRED && typeof window !== 'undefined') {
    try { window.dispatchEvent(new CustomEvent(MEMBERSHIP_EXPIRED_EVENT)); } catch { /* no DOM events here */ }
  }
  return new ApiException(error.code, friendlyMessageFor(error.code, error.message), error.requestId, status, details);
}

const BASE_URL = '/api/v1';
// R18-09: what the user sees when no HTTP response could be obtained at all (offline, DNS/TLS failure, connection
// refused, CORS/CSP block) - instead of the browser's raw "Failed to fetch" / "Load failed" / "NetworkError…".
export const NETWORK_ERROR_MESSAGE = 'Không thể kết nối tới máy chủ. Vui lòng kiểm tra mạng và thử lại.';
const networkError = () => new ApiException('NETWORK_ERROR', NETWORK_ERROR_MESSAGE, undefined, 503);
// Access tokens are deliberately ephemeral: never persist bearer credentials in Web Storage.
let accessToken: string | null = null;
let onSessionExpired: (() => void) | null = null;
export const setAccessToken = (token: string | null) => { accessToken = token; };
export const setSessionExpiredHandler = (handler: (() => void) | null) => { onSessionExpired = handler; };

// R8-06: the refresh token lives only in an HttpOnly cookie (never readable/settable from JS); the
// browser attaches it automatically to same-origin requests with credentials: 'include'. A single
// in-flight refresh call is shared across every concurrent 401 so a burst of parallel requests
// (e.g. a page that fires several API calls at once right as the access token expires) triggers
// exactly one rotation instead of a stampede that would race each other's rotation and trip the
// reuse-detection on the backend.
//
// R12-04: refreshAccessToken() used to collapse every non-2xx outcome (a genuine 401 from the
// backend AND a network failure/429/5xx) to the same `null`, so request()'s 401 handler could not
// tell "the session truly ended" apart from "the refresh call itself could not be completed right
// now". Both were treated as session-expired, forcing a logout on a transient blip. This now
// returns a tri-state RefreshOutcome so the caller can react differently.
type RefreshOutcome =
  | { status: 'refreshed'; token: string }
  | { status: 'logged-out' }
  | { status: 'unreachable' };

let refreshInFlight: Promise<RefreshOutcome> | null = null;

// R9-01(c): the refresh cookie jar is shared across every same-origin tab, but each tab runs its
// own JS heap - the single-flight promise above only dedupes concurrent refreshes *within one tab*.
// navigator.locks.request (when available) additionally serializes refreshes *across* tabs, so two
// tabs whose access tokens expire at nearly the same moment don't both race the backend's rotation
// (which the server-side grace window in RefreshTokenService now also tolerates, but avoiding the
// race in the first place means a tab is never even briefly unauthenticated waiting on it).
async function withCrossTabRefreshLock<T>(fn: () => Promise<T>): Promise<T> {
  const locks = typeof navigator !== 'undefined' ? (navigator as any).locks : undefined;
  if (!locks || typeof locks.request !== 'function') {
    return fn();
  }
  try {
    return await locks.request('classroom-auth-refresh', () => fn());
  } catch {
    // Locks API present but unusable in this context (e.g. some sandboxed iframes) - fall back to
    // running unlocked rather than failing the refresh outright.
    return fn();
  }
}

/**
 * R12-04: only a genuine 401 from /auth/refresh (no valid refresh cookie / session truly revoked)
 * is reported as 'logged-out'. A network failure, a 429 (rate limit), or a 5xx is reported as
 * 'unreachable' instead, so request()'s caller can show a retryable error rather than forcing a
 * logout for what may be a transient blip (matching the retry semantics bootstrapSessionDetailed()
 * already applies on page load).
 */
async function refreshAccessToken(): Promise<RefreshOutcome> {
  if (!refreshInFlight) {
    refreshInFlight = withCrossTabRefreshLock(async () => {
      try {
        const response = await fetch(`${BASE_URL}/auth/refresh`, {
          method: 'POST',
          credentials: 'include',
          headers: { 'X-Requested-With': 'XMLHttpRequest' },
        });
        if (response.status === 401) return { status: 'logged-out' };
        if (!response.ok) return { status: 'unreachable' };
        const json = await response.json().catch(() => null);
        const token: string | undefined = json?.data?.token;
        if (!token) return { status: 'logged-out' };
        accessToken = token;
        return { status: 'refreshed', token };
      } catch {
        return { status: 'unreachable' };
      } finally {
        refreshInFlight = null;
      }
    });
  }
  return refreshInFlight;
}

// R9-05: AuthRateLimitFilter now gives /auth/refresh a much higher per-IP budget than login/register,
// but a shared IP (office NAT, campus network) or a transient backend hiccup can still surface a
// 429/5xx/network failure here. Treating any non-2xx bootstrap response as "logged out" (the
// previous behavior) forced a legitimate, still-authenticated user back to the login screen just
// because of a rate limit or a blip - bootstrapSession() now retries transient failures with a short
// backoff before giving up, and callers can tell "genuinely logged out" (401) apart from "could not
// reach the server" (network/5xx/429) to show a reconnecting state instead of the login page.
export type BootstrapOutcome =
  | { status: 'authenticated'; token: string }
  | { status: 'logged-out' }
  | { status: 'unreachable' };

const BOOTSTRAP_RETRY_DELAYS_MS = [500, 1500, 3000];

async function attemptBootstrap(): Promise<BootstrapOutcome> {
  try {
    const response = await fetch(`${BASE_URL}/auth/refresh`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'X-Requested-With': 'XMLHttpRequest' },
    });
    if (response.status === 401) return { status: 'logged-out' };
    if (!response.ok) return { status: 'unreachable' };
    const json = await response.json().catch(() => null);
    const token: string | undefined = json?.data?.token;
    if (!token) return { status: 'logged-out' };
    accessToken = token;
    return { status: 'authenticated', token };
  } catch {
    return { status: 'unreachable' };
  }
}

/**
 * Bootstraps a session from the HttpOnly refresh cookie (page load / reload / new tab / deep
 * link). Retries a transient failure (429/5xx/network) a few times with backoff before reporting
 * 'unreachable'; only a genuine 401 (no valid refresh cookie / session truly ended) is reported as
 * 'logged-out'. Serialized across tabs the same way the regular 401-triggered refresh is.
 */
export const bootstrapSessionDetailed = async (): Promise<BootstrapOutcome> =>
  withCrossTabRefreshLock(async () => {
    for (let attempt = 0; ; attempt++) {
      const outcome = await attemptBootstrap();
      if (outcome.status !== 'unreachable' || attempt >= BOOTSTRAP_RETRY_DELAYS_MS.length) {
        return outcome;
      }
      await new Promise((resolve) => setTimeout(resolve, BOOTSTRAP_RETRY_DELAYS_MS[attempt]));
    }
  });

/** Back-compat wrapper: same retry behavior as bootstrapSessionDetailed, collapsed to a raw token/null. */
export const bootstrapSession = async (): Promise<string | null> => {
  const outcome = await bootstrapSessionDetailed();
  return outcome.status === 'authenticated' ? outcome.token : null;
};

async function doFetch(url: string, options: RequestInit): Promise<Response> {
  const headers = new Headers(options.headers || {});
  headers.set('Content-Type', 'application/json');
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
  return fetch(url, { ...options, headers });
}

async function request<T>(endpoint: string, options: RequestInit = {}, isRetry = false): Promise<T> {
  const url = `${BASE_URL}${endpoint.startsWith('/') ? endpoint : `/${endpoint}`}`;
  let response: Response;
  try {
    response = await doFetch(url, options);
  } catch (err) {
    // A caller that aborts its own request (AbortController) is not a connectivity problem.
    if (err instanceof DOMException && err.name === 'AbortError') throw err;
    // fetch() rejects (with a TypeError in every engine) only when there is no HTTP response to read. The status is
    // 503, like the refresh path below, so callers keep treating it as retryable rather than a definitive rejection.
    throw networkError();
  }

  if (response.status === 401 && !isRetry) {
    // R12-04: refreshAccessToken() is now tri-state. Only a genuine 401 from /auth/refresh means
    // the session truly ended - that (and only that) triggers onSessionExpired(). A transient
    // refresh failure (network/429/5xx) must not log the user out from under them; it surfaces as
    // a distinct, retryable ApiException instead, so the caller can show "could not reach the
    // server, try again" rather than bouncing to the login screen.
    const outcome = await refreshAccessToken();
    if (outcome.status === 'refreshed') {
      return request<T>(endpoint, options, true);
    }
    accessToken = null;
    if (outcome.status === 'logged-out') {
      onSessionExpired?.();
      throw new ApiException('UNAUTHORIZED', 'Chưa đăng nhập hoặc phiên đã hết hạn', undefined, 401);
    }
    throw networkError();
  }

  const json = await response.json().catch(() => null);

  if (response.status === 401 && isRetry) {
    accessToken = null;
    onSessionExpired?.();
  }

  if (!response.ok) {
    if (json && json.error) {
      throw toApiException(json.error, response.status);
    }
    throw new ApiException('HTTP_ERROR', `Request failed with status ${response.status}`, undefined, response.status);
  }

  if (json && json.success === false && json.error) {
    throw toApiException(json.error, response.status);
  }

  return json ? (json.data as T) : (null as unknown as T);
}

export const api = {
  get: <T>(url: string, init?: RequestInit) => request<T>(url, { ...init, method: 'GET' }),
  post: <T>(url: string, body?: unknown, init?: RequestInit) =>
    request<T>(url, { ...init, method: 'POST', body: body ? JSON.stringify(body) : undefined }),
  put: <T>(url: string, body?: unknown, init?: RequestInit) =>
    request<T>(url, { ...init, method: 'PUT', body: body ? JSON.stringify(body) : undefined }),
  delete: <T>(url: string, init?: RequestInit) => request<T>(url, { ...init, method: 'DELETE' }),
};
