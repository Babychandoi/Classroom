export interface ApiError {
  code: string;
  message: string;
  requestId?: string;
}

export class ApiException extends Error {
  code: string;
  requestId?: string;
  status?: number;

  constructor(code: string, message: string, requestId?: string, status?: number) {
    super(message);
    this.name = 'ApiException';
    this.code = code;
    this.requestId = requestId;
    this.status = status;
  }
}

const BASE_URL = '/api/v1';
// Access tokens are deliberately ephemeral: never persist bearer credentials in Web Storage.
let accessToken: string | null = null;
let onSessionExpired: (() => void) | null = null;
export const setAccessToken = (token: string | null) => { accessToken = token; };
export const setSessionExpiredHandler = (handler: (() => void) | null) => { onSessionExpired = handler; };

async function request<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
  const token = accessToken;
  const headers = new Headers(options.headers || {});

  headers.set('Content-Type', 'application/json');
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  const url = `${BASE_URL}${endpoint.startsWith('/') ? endpoint : `/${endpoint}`}`;

  const response = await fetch(url, {
    ...options,
    headers,
  });

  const json = await response.json().catch(() => null);

  if (response.status === 401 && token && accessToken === token) {
    accessToken = null;
    onSessionExpired?.();
  }

  if (!response.ok) {
    if (json && json.error) {
      throw new ApiException(json.error.code, json.error.message, json.error.requestId, response.status);
    }
    throw new ApiException('HTTP_ERROR', `Request failed with status ${response.status}`, undefined, response.status);
  }

  if (json && json.success === false && json.error) {
    throw new ApiException(json.error.code, json.error.message, json.error.requestId, response.status);
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
  download: async (url: string): Promise<Blob> => {
    const headers = new Headers();
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
    const apiUrl = url.startsWith(`${BASE_URL}/`) ? url : `${BASE_URL}${url.startsWith('/') ? url : `/${url}`}`;
    const response = await fetch(apiUrl, { headers });
    if (!response.ok) throw new ApiException('DOWNLOAD_FAILED', 'Không thể tải tệp');
    return response.blob();
  },
};
