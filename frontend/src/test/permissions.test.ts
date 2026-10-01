import { describe, it, expect } from 'vitest';
import { hasStudioPermission, hasAnyStudioPermission, hasCoursePermission } from '../api/permissions';
import type { Classroom } from '../types';

/**
 * R6-01: a course-scoped grant (studioScopedPermissions) must never be treated as class-wide by
 * hasStudioPermission (used by class-wide-only gates like "create a new course"), but must still
 * unlock Studio nav/routes (hasAnyStudioPermission) and the specific course it names
 * (hasCoursePermission) — mirroring AccessPolicy.canManage's server-side scope matching.
 */
describe('Studio permission helpers', () => {
  const owner: Pick<Classroom, 'userRole' | 'studioPermissions' | 'studioScopedPermissions'> = {
    userRole: 'OWNER',
    studioPermissions: [],
    studioScopedPermissions: [],
  };

  const classWideStaff: Pick<Classroom, 'userRole' | 'studioPermissions' | 'studioScopedPermissions'> = {
    userRole: 'STAFF',
    studioPermissions: ['COURSE:CREATE', 'COURSE:EDIT'],
    studioScopedPermissions: [],
  };

  const courseScopedOnlyStaff: Pick<Classroom, 'userRole' | 'studioPermissions' | 'studioScopedPermissions'> = {
    userRole: 'STAFF',
    studioPermissions: [],
    studioScopedPermissions: [
      { module: 'COURSE', action: 'EDIT', courseId: 'course-X' },
      { module: 'EXAM', action: 'EDIT', courseId: 'course-X' },
    ],
  };

  const wildcardScopedStaff: Pick<Classroom, 'userRole' | 'studioPermissions' | 'studioScopedPermissions'> = {
    userRole: 'STAFF',
    studioPermissions: [],
    studioScopedPermissions: [{ module: '*', action: '*', courseId: 'course-X' }],
  };

  describe('hasStudioPermission (class-wide only, unchanged)', () => {
    it('OWNER always passes', () => {
      expect(hasStudioPermission(owner, 'COURSE', 'CREATE')).toBe(true);
    });

    it('passes for a matching class-wide grant', () => {
      expect(hasStudioPermission(classWideStaff, 'COURSE', 'CREATE')).toBe(true);
    });

    it('a course-scoped-only grant does NOT satisfy the class-wide check', () => {
      expect(hasStudioPermission(courseScopedOnlyStaff, 'COURSE', 'EDIT')).toBe(false);
    });
  });

  describe('hasAnyStudioPermission (nav/route gating)', () => {
    it('OWNER always passes', () => {
      expect(hasAnyStudioPermission(owner, 'COURSE', 'EDIT')).toBe(true);
    });

    it('passes via a class-wide grant', () => {
      expect(hasAnyStudioPermission(classWideStaff, 'COURSE', 'EDIT')).toBe(true);
    });

    it('passes via a course-scoped grant for at least one course (R6-01 fix)', () => {
      expect(hasAnyStudioPermission(courseScopedOnlyStaff, 'COURSE', 'EDIT')).toBe(true);
      expect(hasAnyStudioPermission(courseScopedOnlyStaff, 'EXAM', 'EDIT')).toBe(true);
    });

    it('fails when neither class-wide nor scoped grants match', () => {
      expect(hasAnyStudioPermission(courseScopedOnlyStaff, 'STORE', 'CREATE')).toBe(false);
    });

    it('honors module/action wildcards on scoped grants', () => {
      expect(hasAnyStudioPermission(wildcardScopedStaff, 'COURSE', 'PUBLISH')).toBe(true);
    });

    // R7-01: a scoped grant only ever authorizes a course-scopable module (COURSE, EXAM)
    // server-side; a scoped grant on any other module (STAFF, SEGMENT, STORE, ...) is inert and
    // must not open nav/routes that would then 403 on every request.
    it('ignores a scoped grant for a non-course-scopable module (R7-01 fix)', () => {
      const inertScopedStaff: Pick<Classroom, 'userRole' | 'studioPermissions' | 'studioScopedPermissions'> = {
        userRole: 'STAFF',
        studioPermissions: [],
        studioScopedPermissions: [{ module: 'STORE', action: 'EDIT', courseId: 'course-X' }],
      };
      expect(hasAnyStudioPermission(inertScopedStaff, 'STORE', 'EDIT')).toBe(false);
    });
  });

  describe('hasCoursePermission (per-course action gating)', () => {
    it('OWNER always passes for any course', () => {
      expect(hasCoursePermission(owner, 'COURSE', 'EDIT', 'course-anything')).toBe(true);
    });

    it('a class-wide grant authorizes every course', () => {
      expect(hasCoursePermission(classWideStaff, 'COURSE', 'EDIT', 'course-X')).toBe(true);
      expect(hasCoursePermission(classWideStaff, 'COURSE', 'EDIT', 'course-Y')).toBe(true);
    });

    it('a scoped grant authorizes only its own course', () => {
      expect(hasCoursePermission(courseScopedOnlyStaff, 'COURSE', 'EDIT', 'course-X')).toBe(true);
      expect(hasCoursePermission(courseScopedOnlyStaff, 'COURSE', 'EDIT', 'course-Y')).toBe(false);
    });

    it('a scoped grant does not leak to a different module/action on the same course', () => {
      expect(hasCoursePermission(courseScopedOnlyStaff, 'COURSE', 'PUBLISH', 'course-X')).toBe(false);
      expect(hasCoursePermission(courseScopedOnlyStaff, 'STORE', 'EDIT', 'course-X')).toBe(false);
    });

    it('honors module/action wildcards on scoped grants, still scoped to the named course', () => {
      expect(hasCoursePermission(wildcardScopedStaff, 'COURSE', 'PUBLISH', 'course-X')).toBe(true);
      expect(hasCoursePermission(wildcardScopedStaff, 'COURSE', 'PUBLISH', 'course-Y')).toBe(false);
    });

    it('an empty courseId (e.g. an exam with no targetCourseId) never matches a scoped grant', () => {
      expect(hasCoursePermission(courseScopedOnlyStaff, 'COURSE', 'EDIT', '')).toBe(false);
    });
  });
});
