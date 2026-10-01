import React, { createContext, useContext, useState, useEffect } from 'react';
import { User, AuthResponse } from '../types';
import { api, ApiException, bootstrapSessionDetailed, setAccessToken, setSessionExpiredHandler } from '../api/client';

interface AuthContextType {
  user: User | null;
  token: string | null;
  isLoading: boolean;
  // R9-05: true only while bootstrap could not reach the backend at all (429/5xx/network) after
  // retrying - as opposed to a genuine 401 (no session), which resolves isLoading with user=null.
  // Callers show a "reconnecting" state instead of redirecting to /login while this is true.
  isReconnecting: boolean;
  // R10-01: manual "Thử lại" trigger for the reconnecting screen, in addition to the automatic
  // backoff retry and the online/visibilitychange listeners.
  retryReconnect: () => void;
  login: (email: string, pass: string) => Promise<void>;
  register: (email: string, pass: string, name: string) => Promise<void>;
  quickLogin: (email: string) => Promise<void>;
  logout: () => Promise<void>;
  refreshUser: () => Promise<void>;
}

// R10-01: while isReconnecting is true (bootstrap could not reach the backend), keep retrying with
// capped exponential backoff instead of giving up after the initial bootstrapSessionDetailed()
// retries. Starts at 2s, doubles up to a 30s cap.
const RECONNECT_BACKOFF_START_MS = 2000;
const RECONNECT_BACKOFF_CAP_MS = 30000;

const AuthContext = createContext<AuthContextType | undefined>(undefined);

// R8-06: a BroadcastChannel notifies other same-origin tabs when one tab logs out, so they clear
// their in-memory session instead of silently continuing to act as if still authenticated until
// their next API call happens to 401. Not all browsers/contexts support BroadcastChannel (older
// Safari, some embedded webviews), so every use below is guarded - the fallback is simply that the
// next 401 in another tab handles it via the existing session-expired path.
const LOGOUT_CHANNEL_NAME = 'classroom-auth';
const logoutChannel: BroadcastChannel | null = (() => {
  try {
    return typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel(LOGOUT_CHANNEL_NAME) : null;
  } catch {
    return null;
  }
})();

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(null);
  const [token, setToken] = useState<string | null>(null);
  // R8-06: starts true and stays true until the initial bootstrap (silent refresh from the
  // HttpOnly cookie) settles, so protected routes can show a loading state instead of flashing a
  // redirect to /login before the cookie has had a chance to restore the session.
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isReconnecting, setIsReconnecting] = useState<boolean>(false);

  // R10-01: guards against overlapping bootstrap attempts (an automatic retry firing at the same
  // moment as a manual "Thử lại" click, or 'online'/'visibilitychange' firing back-to-back).
  const bootstrapInFlightRef = React.useRef(false);
  // R11-01: mirrors isReconnecting but readable synchronously from the online/visibilitychange
  // listeners below, which are registered once on mount and would otherwise close over the initial
  // (stale) isReconnecting value forever. Kept in sync everywhere isReconnecting is set.
  const isReconnectingRef = React.useRef(false);
  // R10-01: current backoff delay for the next automatic retry while reconnecting.
  const backoffMsRef = React.useRef(RECONNECT_BACKOFF_START_MS);
  const retryTimerRef = React.useRef<ReturnType<typeof setTimeout> | null>(null);
  const mountedRef = React.useRef(true);

  // R11-01: a transient network/5xx failure here must NOT clear the session - only a genuine 401
  // (the server explicitly rejecting the access token even after the api client's own retry-once-
  // after-refresh path, see api/client.ts) means the session is actually gone. Otherwise a blip
  // (offline, a 502 from a restarting backend, a rate limit) would silently log a still-valid user
  // out. GET /me itself never triggers a refresh, but the api client's fetch wrapper already
  // retries a 401 once after attempting a silent refresh - so a 401 that reaches here has already
  // survived that retry and is authoritative.
  const refreshUser = async () => {
    try {
      const u = await api.get<User>('/me');
      setUser(u);
      setIsLoading(false);
    } catch (err) {
      const status = err instanceof ApiException ? err.status : undefined;
      if (status === 401) {
        setUser(null);
        setAccessToken(null);
        setToken(null);
      }
      // Transient failure (network error, 429, 5xx, etc.): keep the existing user/token as-is and
      // just stop showing the loading state - the caller (attemptBootstrap/login/register) already
      // decided the token itself is valid; refreshUser's job here was only to fetch the profile.
      setIsLoading(false);
    }
  };

  const clearRetryTimer = () => {
    if (retryTimerRef.current) {
      clearTimeout(retryTimerRef.current);
      retryTimerRef.current = null;
    }
  };

  const scheduleReconnectRetry = () => {
    clearRetryTimer();
    const delay = backoffMsRef.current;
    backoffMsRef.current = Math.min(backoffMsRef.current * 2, RECONNECT_BACKOFF_CAP_MS);
    retryTimerRef.current = setTimeout(() => {
      retryTimerRef.current = null;
      void attemptBootstrap();
    }, delay);
  };

  // R10-01: runs bootstrapSessionDetailed() once, updating state from the outcome; while
  // 'unreachable', schedules the next capped-backoff retry. Used by the initial mount, the manual
  // "Thử lại" button, and the online/visibilitychange listeners - all guarded against overlap.
  const attemptBootstrap = async () => {
    if (bootstrapInFlightRef.current) return;
    bootstrapInFlightRef.current = true;
    try {
      // R9-05: bootstrapSessionDetailed() already retried transient failures internally; 'unreachable'
      // here means it still could not reach the backend after those retries. Show a reconnecting
      // state rather than treating that the same as a genuine 401 (which would bounce an otherwise
      // still-logged-in user to the login screen).
      const outcome = await bootstrapSessionDetailed();
      if (!mountedRef.current) return;
      if (outcome.status === 'authenticated') {
        clearRetryTimer();
        backoffMsRef.current = RECONNECT_BACKOFF_START_MS;
        isReconnectingRef.current = false;
        setIsReconnecting(false);
        setToken(outcome.token);
        await refreshUser();
      } else if (outcome.status === 'unreachable') {
        isReconnectingRef.current = true;
        setIsReconnecting(true);
        setIsLoading(false);
        scheduleReconnectRetry();
      } else {
        clearRetryTimer();
        backoffMsRef.current = RECONNECT_BACKOFF_START_MS;
        isReconnectingRef.current = false;
        setIsReconnecting(false);
        setIsLoading(false);
      }
    } finally {
      bootstrapInFlightRef.current = false;
    }
  };

  const retryReconnect = () => {
    clearRetryTimer();
    backoffMsRef.current = RECONNECT_BACKOFF_START_MS;
    // R11-01: the manual "Thử lại" button is only ever rendered while isReconnecting is already
    // true, but keep the ref consistent regardless so a subsequent online/visibilitychange event
    // during this attempt is still allowed to retry too.
    isReconnectingRef.current = true;
    void attemptBootstrap();
  };

  useEffect(() => {
    setSessionExpiredHandler(() => {
      setToken(null);
      setUser(null);
      // R10-01: a session-expired signal (a 401 the retry-once path could not recover from) is a
      // genuine logout, not a connectivity problem - never leave isReconnecting stuck true from a
      // stale earlier bootstrap attempt.
      clearRetryTimer();
      backoffMsRef.current = RECONNECT_BACKOFF_START_MS;
      isReconnectingRef.current = false;
      setIsReconnecting(false);
    });
    return () => setSessionExpiredHandler(null);
  }, []);

  // R8-06: on mount (page load, reload, new tab, or a deep link), silently exchange the HttpOnly
  // refresh cookie for a fresh access token before rendering protected routes. This is what makes
  // a reload no longer log the user out even though the access token itself only ever lives in
  // memory.
  //
  // R10-01: while the outcome is 'unreachable', this no longer leaves isReconnecting stuck true
  // forever - it keeps retrying with capped exponential backoff (2s -> 30s), and also retries
  // immediately on the browser regaining connectivity ('online') or the tab becoming visible again
  // ('visibilitychange'), on top of the manual "Thử lại" button rendered by callers.
  useEffect(() => {
    mountedRef.current = true;
    void attemptBootstrap();

    // R11-01: a signed-in user is not "reconnecting" - these listeners exist solely to retry the
    // bootstrap sooner than the backoff timer would when the earlier attempt failed to reach the
    // backend at all (isReconnecting/isReconnectingRef true). Firing unconditionally on every tab
    // focus or online event meant a signed-in user rotated their refresh token and re-fetched /me
    // every time they merely switched tabs or their OS reported connectivity - burning through
    // rate limits (especially behind a shared NAT) and occasionally tripping a spurious logout.
    const onOnline = () => {
      if (!isReconnectingRef.current) return;
      clearRetryTimer();
      backoffMsRef.current = RECONNECT_BACKOFF_START_MS;
      void attemptBootstrap();
    };
    const onVisibility = () => {
      if (document.visibilityState !== 'visible') return;
      if (!isReconnectingRef.current) return;
      clearRetryTimer();
      backoffMsRef.current = RECONNECT_BACKOFF_START_MS;
      void attemptBootstrap();
    };
    window.addEventListener('online', onOnline);
    document.addEventListener('visibilitychange', onVisibility);

    return () => {
      mountedRef.current = false;
      clearRetryTimer();
      window.removeEventListener('online', onOnline);
      document.removeEventListener('visibilitychange', onVisibility);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // R8-06: cross-tab logout. A logout in one tab broadcasts here; other tabs clear their in-memory
  // session immediately instead of waiting for their next API call to 401.
  useEffect(() => {
    if (!logoutChannel) return;
    const handler = (event: MessageEvent) => {
      if (event.data === 'logout') {
        setAccessToken(null);
        setToken(null);
        setUser(null);
      }
    };
    try {
      logoutChannel.addEventListener('message', handler);
    } catch {
      return;
    }
    return () => {
      try { logoutChannel.removeEventListener('message', handler); } catch { /* ignore */ }
    };
  }, []);

  // R10-01: a successful explicit login/register/logout means we are unambiguously past any
  // connectivity problem the earlier bootstrap may have hit - never leave isReconnecting (and its
  // pending retry timer) stuck around after one of these.
  const clearReconnectState = () => {
    clearRetryTimer();
    backoffMsRef.current = RECONNECT_BACKOFF_START_MS;
    isReconnectingRef.current = false;
    setIsReconnecting(false);
  };

  const login = async (email: string, pass: string) => {
    const res = await api.post<AuthResponse>('/auth/login', { email, password: pass });
    setAccessToken(res.token);
    setToken(res.token);
    clearReconnectState();
    await refreshUser();
  };

  const register = async (email: string, pass: string, name: string) => {
    const res = await api.post<AuthResponse>('/auth/register', { email, password: pass, fullName: name });
    setAccessToken(res.token);
    setToken(res.token);
    clearReconnectState();
    await refreshUser();
  };

  const quickLogin = async (email: string) => {
    if (import.meta.env.VITE_ENABLE_DEMO_LOGIN !== 'true') {
      throw new Error('Đăng nhập demo không được bật trong môi trường này');
    }
    await login(email, 'Password123!');
  };

  const logout = async () => {
    const previousToken = token;
    setAccessToken(null);
    setToken(null);
    setUser(null);
    clearReconnectState();
    try {
      // R8-06: the HttpOnly refresh cookie is sent automatically (credentials: 'include'); the
      // server revokes the refresh token family in addition to the access token below.
      await api.post('/auth/logout', undefined, {
        credentials: 'include',
        headers: previousToken ? { Authorization: `Bearer ${previousToken}` } : undefined,
      });
    } catch { /* Session already invalid or network unavailable. */ }
    try {
      logoutChannel?.postMessage('logout');
    } catch { /* BroadcastChannel unsupported/unavailable; other tabs fall back to their next 401. */ }
  };

  return (
    <AuthContext.Provider value={{ user, token, isLoading, isReconnecting, retryReconnect, login, register, quickLogin, logout, refreshUser }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
