import { Classroom } from '../types';

/**
 * R7-01: mirrors backend StaffService.COURSE_SCOPABLE_MODULES — the only modules ever evaluated
 * with a non-null course scope server-side (AccessPolicy.canManage callers). A course-scoped
 * grant on any other module (SEGMENT, STORE, STUDIO, FEED, DOCUMENT, ABOUT, MEDIA, MEMBER, AUDIT,
 * ...) can never actually authorize anything there, so it must not be treated as "reachable" here
 * either — otherwise nav/routes would open for a page every server check then 403s on.
 */
export const COURSE_SCOPABLE_MODULES = new Set(['COURSE', 'EXAM']);

/**
 * R4-09: shared permission helper so UI gating matches the server's AccessPolicy.canManage,
 * including the "*" wildcard grants staff permissions can carry (e.g. "FEED:*", "*:DELETE",
 * "*:*") — see AccessPolicy.java canManage(). classroom.studioPermissions holds literal
 * "MODULE:ACTION" strings (see ClassroomService), so a plain .includes('FEED:DELETE') check
 * misses a staff member holding a wildcard grant.
 */
export function hasStudioPermission(classroom: Pick<Classroom, 'userRole' | 'studioPermissions'>, module: string, action: string): boolean {
  if (classroom.userRole === 'OWNER') return true;
  const grants = classroom.studioPermissions;
  if (!grants || grants.length === 0) return false;
  return grants.some((grant) => {
    const [grantModule, grantAction] = grant.split(':');
    const moduleMatch = grantModule === module || grantModule === '*';
    const actionMatch = grantAction === action || grantAction === '*';
    return moduleMatch && actionMatch;
  });
}

/**
 * R6-01: whether classroom.studioScopedPermissions has a wildcard-aware grant for
 * (module, action) scoped to courseId — mirrors AccessPolicy.canManage's scope matching
 * (perm.scopeCourseId == null is a class-wide grant, handled separately; a scoped grant only
 * matches the exact course it names).
 */
function hasScopedStudioPermission(
  classroom: Pick<Classroom, 'studioScopedPermissions'>,
  module: string,
  action: string,
  courseId: string,
): boolean {
  const scoped = classroom.studioScopedPermissions;
  if (!scoped || scoped.length === 0) return false;
  return scoped.some((grant) => {
    const moduleMatch = grant.module === module || grant.module === '*';
    const actionMatch = grant.action === action || grant.action === '*';
    return moduleMatch && actionMatch && grant.courseId === courseId;
  });
}

/**
 * R6-01: whether the viewer has SOME access to (module, action) in Studio — either a class-wide
 * grant (studioPermissions) or a course-scoped grant for at least one course
 * (studioScopedPermissions). Used to decide whether Studio nav/routes for that module should be
 * reachable at all; the actual per-course action still needs hasCoursePermission.
 */
export function hasAnyStudioPermission(
  classroom: Pick<Classroom, 'userRole' | 'studioPermissions' | 'studioScopedPermissions'>,
  module: string,
  action: string,
): boolean {
  if (hasStudioPermission(classroom, module, action)) return true;
  // R7-01: a scoped grant only ever authorizes something server-side for a course-scopable
  // module; falling back to it for e.g. STAFF/SEGMENT/STORE would open nav/routes that then
  // 403 on every request because the server never checks those modules with a course scope.
  if (!COURSE_SCOPABLE_MODULES.has(module)) return false;
  const scoped = classroom.studioScopedPermissions;
  if (!scoped || scoped.length === 0) return false;
  return scoped.some((grant) => {
    const moduleMatch = grant.module === module || grant.module === '*';
    const actionMatch = grant.action === action || grant.action === '*';
    return moduleMatch && actionMatch;
  });
}

/**
 * R6-01: whether the viewer may perform (module, action) on the specific course courseId —
 * a class-wide grant authorizes every course, and a scoped grant authorizes only its own course.
 * Mirrors AccessPolicy.canManage(userId, classId, module, action, resourceScopeCourseId) server-side.
 */
export function hasCoursePermission(
  classroom: Pick<Classroom, 'userRole' | 'studioPermissions' | 'studioScopedPermissions'>,
  module: string,
  action: string,
  courseId: string,
): boolean {
  if (hasStudioPermission(classroom, module, action)) return true;
  return hasScopedStudioPermission(classroom, module, action, courseId);
}
