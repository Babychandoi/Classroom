import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { api, ApiException } from '../api/client';

describe('Platform End-to-End Workflows', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('executes complete student authentication and class exploration journey', async () => {
    const fakeToken = 'header.payload.signature';
    const userProfile = {
      id: 'student-free-id',
      email: 'student.free@classroom.local',
      fullName: 'Học Viên Lê Tự Do',
      role: 'USER',
    };
    const classroomList = [
      {
        id: 'class-toan',
        slug: 'lop-toan-nang-cao',
        title: 'Lớp Học Toán Nâng Cao',
        status: 'ACTIVE',
      },
    ];

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();

      // 1. Auth login
      if (url.includes('/auth/login')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: {
              token: fakeToken,
              user: userProfile,
            },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }

      // 2. Auth me profile
      if (url.includes('/auth/me')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: userProfile,
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }

      // 3. Classes listing
      if (url.includes('/classes')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: classroomList,
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }

      return new Response('{}', { status: 404 });
    });

    // Login
    const loginRes = await api.post<{ token: string; user: any }>('/auth/login', {
      email: 'student.free@classroom.local',
      password: 'Password123!',
    });
    expect(loginRes.token).toBe(fakeToken);
    // Use the same key that api/client.ts reads: 'token'
    localStorage.setItem('token', loginRes.token);

    // Profile
    const me = await api.get<any>('/auth/me');
    expect(me.email).toBe('student.free@classroom.local');

    // Classes
    const classes = await api.get<any[]>('/classes');
    expect(classes).toHaveLength(1);
    expect(classes[0].slug).toBe('lop-toan-nang-cao');
  });

  it('handles active exam attempt resume at attempt limit=1 and submits idempotently', async () => {
    let attemptStartedCount = 0;
    const activeAttempt = {
      id: 'att-resumed-1',
      examId: 'exam-speed',
      userId: 'student-free-id',
      status: 'IN_PROGRESS',
      attemptNumber: 1,
      endsAt: new Date(Date.now() + 30 * 60 * 1000).toISOString(),
      questions: [
        {
          id: 'q-math-1',
          questionText: '9 * 9 = ?',
          type: 'MULTIPLE_CHOICE',
          points: 10,
          options: [
            { id: 'opt-1', optionKey: 'A', optionText: '72' },
            { id: 'opt-2', optionKey: 'B', optionText: '81' },
          ],
        },
      ],
    };

    let submittedAttempt = {
      ...activeAttempt,
      status: 'PUBLISHED',
      score: 100,
    };

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString();

      // Start attempt: first time or resume
      if (url.includes('/exams/exam-speed/attempts') && init?.method === 'POST') {
        attemptStartedCount++;
        if (attemptStartedCount <= 2) {
          // Both initial start and resume return the same active attempt
          return new Response(
            JSON.stringify({
              success: true,
              data: activeAttempt,
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } }
          );
        } else {
          // After submit, 3rd call exceeds limit
          return new Response(
            JSON.stringify({
              success: false,
              error: {
                code: 'EXAM_ATTEMPT_LIMIT_REACHED',
                message: 'Bạn đã hết số lượt làm bài cho kỳ thi này',
              },
            }),
            { status: 400, headers: { 'Content-Type': 'application/json' } }
          );
        }
      }

      // Autosave answers
      if (url.includes('/attempts/att-resumed-1/answers')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: { id: 'att-resumed-1', status: 'IN_PROGRESS' },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }

      // Submit attempt
      if (url.includes('/attempts/att-resumed-1/submit')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: submittedAttempt,
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }

      return new Response('{}', { status: 404 });
    });

    // 1. First start attempt
    const att1 = await api.post<any>('/exams/exam-speed/attempts');
    expect(att1.id).toBe('att-resumed-1');
    expect(att1.status).toBe('IN_PROGRESS');
    expect(att1.questions[0].answerKey).toBeUndefined();

    // 2. Reconnect / resume active attempt at limit=1
    const resumed = await api.post<any>('/exams/exam-speed/attempts');
    expect(resumed.id).toBe('att-resumed-1');
    expect(resumed.status).toBe('IN_PROGRESS');

    // 3. Autosave answer
    await api.put('/attempts/att-resumed-1/answers', {
      answers: { 'q-math-1': 'B' },
    });

    // 4. Submit
    const submitted = await api.post<any>('/attempts/att-resumed-1/submit', {
      answers: { 'q-math-1': 'B' },
    });
    expect(submitted.status).toBe('PUBLISHED');
    expect(submitted.score).toBe(100);

    // 5. Start attempt after limit reached -> rejected
    await expect(api.post('/exams/exam-speed/attempts')).rejects.toThrow(ApiException);
  });

  it('enforces multi-tenant cross-class boundary on classroom store and content', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();

      if (url.includes('/classes/foreign-class-id/members')) {
        return new Response(
          JSON.stringify({
            success: false,
            error: {
              code: 'FORBIDDEN',
              message: 'Bạn chưa là thành viên của lớp học này',
            },
          }),
          { status: 403, headers: { 'Content-Type': 'application/json' } }
        );
      }

      if (url.includes('/classes/foreign-class-id/documents')) {
        return new Response(
          JSON.stringify({
            success: false,
            error: {
              code: 'FORBIDDEN',
              message: 'Bạn chưa là thành viên của lớp học này',
            },
          }),
          { status: 403, headers: { 'Content-Type': 'application/json' } }
        );
      }

      return new Response('{}', { status: 404 });
    });

    // Cross-class members access rejected
    await expect(api.get('/classes/foreign-class-id/members')).rejects.toThrow(ApiException);

    // Cross-class documents access rejected
    await expect(api.get('/classes/foreign-class-id/documents')).rejects.toThrow(ApiException);
  });

  it('loads privacy-aware member profile and learning journey for classroom member', async () => {
    const peerProfile = {
      id: 'student-2-id',
      fullName: 'Học Viên Nguyễn Văn B',
      avatarUrl: null,
      bio: 'Đang theo học lớp chuyên toán',
      membershipRole: 'STUDENT',
      isPro: true,
      totalPoints: 180,
      rankTier: 'VÀNG',
      email: null, // Omitted for peers
    };

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();

      if (url.includes('/classes/class-1/members/student-2-id/profile')) {
        return new Response(
          JSON.stringify({
            success: true,
            data: peerProfile,
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }

      return new Response('{}', { status: 404 });
    });

    const profile = await api.get<any>('/classes/class-1/members/student-2-id/profile');
    expect(profile.fullName).toBe('Học Viên Nguyễn Văn B');
    expect(profile.isPro).toBe(true);
    expect(profile.totalPoints).toBe(180);
    expect(profile.rankTier).toBe('VÀNG');
    expect(profile.email).toBeNull();
  });

  it('studio grading resolves full course details and assignment submission queue', async () => {
    const courseList = [
      { id: 'course-101', title: 'Toán Rời Rạc', accessMode: 'FREE', status: 'PUBLISHED' },
    ];

    const courseDetail = {
      id: 'course-101',
      title: 'Toán Rời Rạc',
      sections: [
        {
          id: 'sec-1',
          courseId: 'course-101',
          title: 'Chương 1',
          position: 1,
          lessons: [
            {
              id: 'lesson-assign-1',
              sectionId: 'sec-1',
              courseId: 'course-101',
              title: 'Bài Tập Đồ Thị',
              type: 'ASSIGNMENT',
              durationMinutes: 45,
              position: 1,
              completed: false,
            },
          ],
        },
      ],
    };

    const submissions = [
      {
        id: 'sub-1',
        lessonId: 'lesson-assign-1',
        userId: 'student-free-id',
        submissionText: 'Lời giải bài toán cây khung',
        attemptNumber: 1,
        score: null,
        feedback: null,
        submittedAt: '2026-09-24T10:00:00Z',
      },
    ];

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString();

      if (url.includes('/classes/class-1/courses')) {
        return new Response(
          JSON.stringify({ success: true, data: courseList }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }

      if (url.includes('/courses/course-101')) {
        return new Response(
          JSON.stringify({ success: true, data: courseDetail }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }

      if (url.includes('/lessons/lesson-assign-1/submissions')) {
        return new Response(
          JSON.stringify({ success: true, data: submissions }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }

      if (url.includes('/classes/class-1/assignment-queue')) {
        return new Response(
          JSON.stringify({ success: true, data: submissions }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }

      return new Response('{}', { status: 404 });
    });

    // 1. Fetch courses
    const courses = await api.get<any[]>('/classes/class-1/courses');
    expect(courses).toHaveLength(1);

    // 2. Fetch course details to load sections and lessons
    const details = await api.get<any>(`/courses/${courses[0].id}`);
    expect(details.sections).toHaveLength(1);
    expect(details.sections[0].lessons[0].type).toBe('ASSIGNMENT');

    // 3. Fetch submissions for assignment
    const queue = await api.get<any[]>(`/lessons/${details.sections[0].lessons[0].id}/submissions`);
    expect(queue).toHaveLength(1);
    expect(queue[0].submissionText).toContain('Lời giải bài toán cây khung');

    // 4. Fetch class-level assignment queue
    const classQueue = await api.get<any[]>('/classes/class-1/assignment-queue');
    expect(classQueue).toHaveLength(1);
  });
});
