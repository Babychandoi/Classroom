import { api } from './client';
import type { Classroom, ClassEvent, MyCourse } from '../types';
import type { EventScope } from './events';

// "Của tôi" pages (docs/API-ME.md). Paged like GET /classes: page is 0-based, a page shorter than `size` is the last one.
export const ME_PAGE_SIZE = 20;

export const listMyClasses = (page: number, size = ME_PAGE_SIZE) =>
  api.get<Classroom[]>(`/me/classes?page=${page}&size=${size}`);

export const listMyCourses = (page: number, size = ME_PAGE_SIZE) =>
  api.get<MyCourse[]>(`/me/courses?page=${page}&size=${size}`);

export const listMyEvents = (scope: EventScope, page: number, size = ME_PAGE_SIZE) =>
  api.get<ClassEvent[]>(`/me/events?scope=${scope}&page=${page}&size=${size}`);
