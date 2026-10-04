import { api } from './client';

// Platform admin ("Quản trị nền tảng") API - docs/API-PLATFORM-ADMIN.md. Every route requires PLATFORM_ADMIN on the
// server (403 otherwise); the UI only adds a client-side guard so a non-admin never sees the shell.

export const PLATFORM_ADMIN = 'PLATFORM_ADMIN';
export const isPlatformAdmin = (user?: { role?: string } | null) => user?.role === PLATFORM_ADMIN;

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface AdminOverview {
  users: { total: number; active: number; banned: number; deleted: number; admins: number; newLast7Days: number; newLast30Days: number };
  classes: { total: number; active: number; archived: number; suspended: number; public: number; private: number; paid: number; newLast7Days: number };
  members: { activeMemberships: number; pendingRequests: number };
  content: { courses: number; publishedExams: number; blogPostsPublished: number; upcomingEvents: number };
  commerce: { paidOrdersLast30Days: number; revenueLast30Days: number; currency: 'VND'; pendingOrders: number };
  privacy: { openRequests: number };
  outbox: { pending: number; deadLetter: number };
  signupsByDay: { date: string; count: number }[];
}

export type AdminUserRole = 'USER' | 'PLATFORM_ADMIN';
export type AdminUserStatus = 'ACTIVE' | 'BANNED' | 'DELETED';

export interface AdminUserRow {
  id: string;
  email: string;
  fullName: string;
  avatarUrl?: string | null;
  role: AdminUserRole;
  status: AdminUserStatus;
  createdAt: string;
  ownedClassCount: number;
  membershipCount: number;
  lastLoginAt?: string | null;
}

export interface AuditRow {
  id: string;
  createdAt: string;
  action: string;
  targetType: string;
  targetId: string;
  classId?: string | null;
  classTitle?: string | null;
  actor?: { id: string; fullName: string; email: string } | null;
  details: Record<string, unknown> | null;
}

export interface AdminUserDetail extends AdminUserRow {
  ownedClasses: { id: string; slug: string; title: string; status: string }[];
  recentAudit: AuditRow[];
}

export type AdminClassStatus = 'ACTIVE' | 'ARCHIVED' | 'SUSPENDED';

export interface AdminClassRow {
  id: string;
  slug: string;
  title: string;
  owner: { id: string; fullName: string; email: string };
  status: AdminClassStatus;
  visibility: 'PUBLIC' | 'PRIVATE';
  accessType: 'FREE' | 'PAID';
  category?: string | null;
  memberCount: number;
  pendingRequestCount: number;
  createdAt: string;
  coverUrl?: string | null;
  avatarUrl?: string | null;
  suspendedReason?: string | null;
  suspendedAt?: string | null;
}

export interface AdminClassDetail extends AdminClassRow {
  counts: { courses: number; exams: number; blogPosts: number; events: number; products: number; paidOrders: number };
  recentAudit: AuditRow[];
}

/** `?a=1&b=x`, skipping empty / undefined values ('' when nothing is set). */
export function queryString(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return;
    search.set(key, String(value));
  });
  const text = search.toString();
  return text ? `?${text}` : '';
}

export const adminApi = {
  overview: () => api.get<AdminOverview>('/admin/overview'),
  users: (params: Record<string, string | number | undefined>) => api.get<Page<AdminUserRow>>(`/admin/users${queryString(params)}`),
  user: (id: string) => api.get<AdminUserDetail>(`/admin/users/${encodeURIComponent(id)}`),
  ban: (id: string, reason: string) => api.post<AdminUserRow>(`/admin/users/${encodeURIComponent(id)}/ban`, { reason }),
  unban: (id: string, reason: string) => api.post<AdminUserRow>(`/admin/users/${encodeURIComponent(id)}/unban`, { reason }),
  setRole: (id: string, role: AdminUserRole, reason: string) =>
    api.put<AdminUserRow>(`/admin/users/${encodeURIComponent(id)}/role`, { role, reason }),
  classes: (params: Record<string, string | number | undefined>) => api.get<Page<AdminClassRow>>(`/admin/classes${queryString(params)}`),
  classDetail: (id: string) => api.get<AdminClassDetail>(`/admin/classes/${encodeURIComponent(id)}`),
  suspend: (id: string, reason: string) => api.post<AdminClassRow>(`/admin/classes/${encodeURIComponent(id)}/suspend`, { reason }),
  restore: (id: string, reason: string) => api.post<AdminClassRow>(`/admin/classes/${encodeURIComponent(id)}/restore`, { reason }),
  audit: (params: Record<string, string | number | undefined>) => api.get<Page<AuditRow>>(`/admin/audit${queryString(params)}`),
};

// ---------------------------------------------------------------------------------------------------------------
// Vietnamese labels.

export const USER_ROLE_LABEL: Record<string, string> = { USER: 'Thành viên', PLATFORM_ADMIN: 'Quản trị nền tảng' };
export const USER_STATUS_LABEL: Record<string, string> = { ACTIVE: 'Đang hoạt động', BANNED: 'Bị khóa', DELETED: 'Đã xóa' };
export const CLASS_STATUS_LABEL: Record<string, string> = { ACTIVE: 'Đang hoạt động', ARCHIVED: 'Đã lưu trữ', SUSPENDED: 'Tạm khóa' };

export const AUDIT_ACTION_LABEL: Record<string, string> = {
  ADMIN_USER_BAN: 'Khóa tài khoản',
  ADMIN_USER_UNBAN: 'Mở khóa tài khoản',
  ADMIN_USER_ROLE: 'Đổi vai trò',
  ADMIN_CLASS_SUSPEND: 'Tạm khóa lớp',
  ADMIN_CLASS_RESTORE: 'Mở khóa lớp',
  PRIVACY_REQUEST_RESOLVE: 'Xử lý yêu cầu dữ liệu',
};

/** Max length of the mandatory `reason` of every admin write. */
export const REASON_MAX = 500;
