import { describe, expect, it } from 'vitest';
import { isClassArea, isFocusRoute, isHomeArea, showsAppSidebar } from '../components/shell/focusRoutes';

describe('focus routes (D-35)', () => {
  it.each([
    '/classes/new',
    '/classes/new/',
    '/classes/demo/exams/e-1/attempt',
    '/classes/demo/homework/a-1/attempt',
    '/classes/demo/homework/a-1/attempt/',
    '/studio/classes/c-1/assessments/asm-1/edit',
  ])('%s is a focus route', (path) => {
    expect(isFocusRoute(path)).toBe(true);
    expect(showsAppSidebar(path)).toBe(false);
  });

  it.each([
    '/classes',
    '/classes/demo/feed',
    '/classes/demo/exams/e-1/result',
    '/classes/demo/homework',
    '/classes/demo/homework/a-1/result',
    '/classes/demo/learn/lessons/l-1',
    '/studio/classes/c-1/assessments',
    '/studio/classes/c-1/assessments/results/as-1',
    '/me/classes',
  ])('%s is not a focus route', (path) => {
    expect(isFocusRoute(path)).toBe(false);
  });

  it('shows the app sidebar in the home and class areas only', () => {
    expect(showsAppSidebar('/classes')).toBe(true);
    expect(showsAppSidebar('/me/profile')).toBe(true);
    expect(showsAppSidebar('/privacy')).toBe(true);
    expect(showsAppSidebar('/classes/demo/feed')).toBe(true);
    expect(showsAppSidebar('/studio/classes/c-1/overview')).toBe(false);
    expect(showsAppSidebar('/admin/overview')).toBe(false);
    expect(showsAppSidebar('/login')).toBe(false);
  });

  it('tells the home area from a class page', () => {
    expect(isHomeArea('/me/events')).toBe(true);
    expect(isHomeArea('/classes/demo')).toBe(false);
    expect(isClassArea('/classes/demo')).toBe(true);
    expect(isClassArea('/classes/new')).toBe(false);
    expect(isClassArea('/classes')).toBe(false);
  });
});
