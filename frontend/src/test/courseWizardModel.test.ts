import { describe, expect, it } from 'vitest';
import type { Course } from '../types';
import {
  advisoryMissing, blockingMissing, buildChecklist, emptyForm, formatPriceInput, highestOpenStep, isStepLocked, parsePrice, sameSelling,
  step2Error,
} from '../pages/studio/courseWizardModel';

const section = (archived = false, lessonArchived = false) => ({
  id: 's', courseId: 'c', title: 'S', position: 1, archived,
  lessons: [{ id: 'l', sectionId: 's', courseId: 'c', title: 'L', type: 'TEXT' as const, position: 1, durationMinutes: 0, completed: false, archived: lessonArchived }],
});
const course = (sections: any[] = [section()]) => ({ id: 'c', classId: 'k', title: 'T', accessMode: 'FREE', status: 'DRAFT', sections }) as unknown as Course;
const titled = { ...emptyForm(), title: 'Toán' };

describe('course wizard model', () => {
  it('formats and parses whole dong', () => {
    expect(formatPriceInput('299000')).toBe('299.000');
    expect(formatPriceInput('2.990')).toBe('2.990');
    expect(formatPriceInput('abc')).toBe('');
    expect(parsePrice('1.250.000đ')).toBe(1250000);
  });

  it('locks a step until every step before it is valid', () => {
    expect(isStepLocked(1, { form: emptyForm(), course: null })).toBe(false);
    expect(isStepLocked(2, { form: emptyForm(), course: null })).toBe(true);
    expect(isStepLocked(2, { form: titled, course: null })).toBe(false);
    // Step 3 and 4 need the course to exist.
    expect(isStepLocked(3, { form: titled, course: null })).toBe(true);
    expect(isStepLocked(3, { form: titled, course: course([]) })).toBe(false);
    // Step 4 needs at least one visible lesson.
    expect(isStepLocked(4, { form: titled, course: course([]) })).toBe(true);
    expect(isStepLocked(4, { form: titled, course: course([section(true)]) })).toBe(true);
    expect(isStepLocked(4, { form: titled, course: course([section(false, true)]) })).toBe(true);
    expect(isStepLocked(4, { form: titled, course: course() })).toBe(false);
    expect(highestOpenStep({ form: titled, course: course() })).toBe(4);
  });

  it('a paid choice without price or with a bad duration closes the steps after it', () => {
    const paid = { ...titled, accessMode: 'PURCHASE_REQUIRED' as const };
    expect(step2Error(paid)).toMatch(/giá bán/);
    expect(isStepLocked(3, { form: paid, course: course() })).toBe(true);
    expect(step2Error({ ...paid, priceText: '100.000', durationText: '0' })).toMatch(/1 đến 3\.?650/);
    expect(step2Error({ ...paid, priceText: '100.000', durationText: '3651' })).not.toBeNull();
    expect(step2Error({ ...paid, priceText: '100.000', durationText: '3650' })).toBeNull();
    expect(isStepLocked(3, { form: { ...paid, priceText: '100.000', durationText: '30' }, course: course() })).toBe(false);
  });

  it('compares selling choices ignoring price formatting and free-mode leftovers', () => {
    expect(sameSelling({ ...emptyForm(), priceText: '5' }, emptyForm())).toBe(true);
    const a = { ...emptyForm(), accessMode: 'PURCHASE_REQUIRED' as const, priceText: '299.000', durationText: '30' };
    expect(sameSelling(a, { ...a, priceText: '299000' })).toBe(true);
    expect(sameSelling(a, { ...a, durationText: '31' })).toBe(false);
  });

  it('the checklist blocks on name, lessons and a saved product, and only advises on description and cover', () => {
    const items = buildChecklist({ form: emptyForm(), course: course([]), productSaved: false, dirty: false });
    expect(blockingMissing(items).map((i) => i.key)).toEqual(['title', 'lessons']);
    expect(advisoryMissing(items).map((i) => i.key)).toEqual(['description', 'cover']);

    const ready = buildChecklist({ form: titled, course: course(), productSaved: false, dirty: false });
    expect(blockingMissing(ready)).toHaveLength(0);

    const paid = { ...titled, accessMode: 'PURCHASE_REQUIRED' as const, priceText: '10.000', durationText: '30' };
    expect(blockingMissing(buildChecklist({ form: paid, course: course(), productSaved: false, dirty: false })).map((i) => i.key)).toEqual(['product']);
    expect(blockingMissing(buildChecklist({ form: paid, course: course(), productSaved: true, dirty: false }))).toHaveLength(0);
    expect(blockingMissing(buildChecklist({ form: { ...paid, priceText: '' }, course: course(), productSaved: true, dirty: false })).map((i) => i.key)).toEqual(['price']);
  });
});
