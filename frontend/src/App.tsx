import React from 'react';
import { createBrowserRouter, RouterProvider, Routes, Route, Navigate, useLocation, Link } from 'react-router-dom';
import { Compass } from 'lucide-react';
import { AuthProvider, useAuth } from './context/AuthContext';
import { Navbar } from './components/Navbar';
import { RequireSignIn } from './components/RequireSignIn';
import { LoginPage } from './pages/LoginPage';
import { ClassesPage } from './pages/ClassesPage';
import { CreateClassPage } from './pages/CreateClassPage';
import { MyProfilePage } from './pages/MyProfilePage';
import { MyClassesPage } from './pages/MyClassesPage';
import { MyCoursesPage } from './pages/MyCoursesPage';
import { MyEventsPage } from './pages/MyEventsPage';
import { ClassroomLayout } from './pages/ClassroomLayout';
import { JoinByInvitePage } from './pages/JoinByInvitePage';

import { FeedTab } from './pages/classroom/FeedTab';
import { LearnTab } from './pages/classroom/LearnTab';
import { LessonViewPage } from './pages/classroom/LessonViewPage';
import { ExamsTab } from './pages/classroom/ExamsTab';
import { ExamAttemptPage } from './pages/classroom/ExamAttemptPage';
import { ExamResultPage } from './pages/classroom/ExamResultPage';
import { LeaderboardTab } from './pages/classroom/LeaderboardTab';
import { DocumentsTab } from './pages/classroom/DocumentsTab';
import { MembersTab } from './pages/classroom/MembersTab';
import { MemberProfilePage } from './pages/classroom/MemberProfilePage';
import { AboutTab } from './pages/classroom/AboutTab';
import { PrivacyPage } from './pages/PrivacyPage';
import { StoreTab } from './pages/classroom/StoreTab';
import { BlogTab } from './pages/classroom/BlogTab';
import { BlogPostPage } from './pages/classroom/BlogPostPage';
import { EventsTab } from './pages/classroom/EventsTab';
import { EventDetailPage } from './pages/classroom/EventDetailPage';

import { StudioLayout } from './pages/studio/StudioLayout';
import { StudioOverview } from './pages/studio/StudioOverview';
import { StudioCourses } from './pages/studio/StudioCourses';
import { StudioExams } from './pages/studio/StudioExams';
import { StudioGrading } from './pages/studio/StudioGrading';
import { StudioLeaderboard } from './pages/studio/StudioLeaderboard';
import { StudioStaff } from './pages/studio/StudioStaff';
import { StudioSettings } from './pages/studio/StudioSettings';
import { StudioMembers } from './pages/studio/StudioMembers';
import { StudioSegments } from './pages/studio/StudioSegments';
import { StudioStore } from './pages/studio/StudioStore';
import { StudioAudit } from './pages/studio/StudioAudit';
import { StudioAbout, StudioDocuments, StudioFeed } from './pages/studio/StudioCommunity';
import { StudioBlog } from './pages/studio/StudioBlog';
import { StudioEvents } from './pages/studio/StudioEvents';
import { StudioCourseWizard } from './pages/studio/StudioCourseWizard';

import { AdminLayout } from './pages/admin/AdminLayout';
import { AdminOverview } from './pages/admin/AdminOverview';
import { AdminUsers } from './pages/admin/AdminUsers';
import { AdminUserDetail } from './pages/admin/AdminUserDetail';
import { AdminClasses } from './pages/admin/AdminClasses';
import { AdminClassDetail } from './pages/admin/AdminClassDetail';
import { AdminPrivacy } from './pages/admin/AdminPrivacy';
import { AdminAudit } from './pages/admin/AdminAudit';

export const RequireLogin: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, isLoading, isReconnecting, retryReconnect } = useAuth();
  const location = useLocation();
  if (isLoading) {
    return (
      <div role="status" aria-live="polite" className="flex flex-1 flex-col items-center justify-center gap-3 px-4 py-16">
        <span aria-hidden="true" className="h-8 w-8 animate-spin rounded-full border-[3px] border-slate-200 border-t-blue-600" />
        <p className="text-meta font-medium text-slate-500">Đang xác thực...</p>
      </div>
    );
  }
  // R9-05/R10-01: bootstrap could not reach the backend (429/5xx/network) even after retrying -
  // this is not the same as "no session" (401), so keep showing a reconnecting state here instead
  // of bouncing an otherwise still-logged-in user to /login. AuthContext keeps retrying with
  // capped backoff and on 'online'/'visibilitychange' in the background; this screen also offers a
  // manual retry and a way out to the login page in case the user's session did in fact end.
  if (isReconnecting && !user) {
    return (
      <div className="flex flex-1 items-center justify-center px-4 py-16">
        <div role="status" className="w-full max-w-md rounded-card border border-slate-200 bg-white p-8 text-center shadow-hairline">
          <span aria-hidden="true" className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-community bg-slate-100">
            <span className="h-6 w-6 animate-spin rounded-full border-[3px] border-slate-200 border-t-blue-600" />
          </span>
          <p className="text-h3 font-semibold text-slate-900">Đang kết nối lại...</p>
          <p className="mt-1 text-ui text-slate-600">Máy chủ chưa phản hồi. Bạn có thể thử lại ngay hoặc đăng nhập lại.</p>
          <div className="mt-5 flex items-center justify-center gap-4">
            <button
              type="button"
              onClick={retryReconnect}
              className="press inline-flex h-10 items-center rounded-btn border border-slate-200 bg-white px-4 text-ui font-semibold text-slate-900 transition-colors duration-micro hover:bg-slate-100"
            >
              Thử lại
            </button>
            <Link to="/login" className="text-ui font-medium text-blue-600 hover:text-blue-700">
              Đăng nhập
            </Link>
          </div>
        </div>
      </div>
    );
  }
  return user ? <>{children}</> : <Navigate to="/login" state={{ from: location }} replace />;
};

// Fallback for an unknown URL: a calm empty state that says what to do next.
const NotFound: React.FC = () => (
  <div className="flex flex-1 items-center justify-center px-4 py-16">
    <div role="alert" className="w-full max-w-md rounded-card border border-slate-200 bg-white p-8 text-center shadow-hairline sm:p-10">
      <span aria-hidden="true" className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-community bg-slate-100 text-slate-500">
        <Compass className="h-6 w-6" strokeWidth={1.7} />
      </span>
      <h1 className="text-h3-lg font-semibold text-slate-900">Không tìm thấy trang yêu cầu.</h1>
      <p className="mt-1 text-ui text-slate-600">Liên kết có thể đã cũ hoặc gõ nhầm. Hãy quay về danh sách lớp để tìm tiếp.</p>
      <Link
        to="/classes"
        className="press mt-5 inline-flex h-10 items-center rounded-btn border border-slate-200 bg-white px-4 text-ui font-semibold text-slate-900 transition-colors duration-micro hover:bg-slate-100"
      >
        Về danh sách lớp
      </Link>
    </div>
  </div>
);

/** Full-screen pages that bring their own header (close-X + logo) render without the global top bar. */
const FULL_SCREEN_PATHS = ['/classes/new'];
export const AppNavbar: React.FC = () => {
  const { pathname } = useLocation();
  return FULL_SCREEN_PATHS.includes(pathname.replace(/\/+$/, '')) ? null : <Navbar />;
};

// The app lives in a *data* router (RouterProvider with one catch-all route whose element holds the <Routes> tree below)
// instead of <BrowserRouter>: only a data router offers useBlocker, which the course wizard needs to ask "Thoát mà chưa
// lưu?" before leaving by link, Back button or any other navigation.
const AppShell: React.FC = () => {
  return (
    <div className="flex min-h-screen flex-col bg-slate-50 text-slate-900">
      <AppNavbar />
      <div className="flex-1 flex flex-col">
        <Routes>
          {/* Home & Auth */}
          <Route path="/" element={<Navigate to="/classes" replace />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/privacy" element={<PrivacyPage />} />
          <Route path="/classes" element={<ClassesPage />} />
          <Route path="/classes/new" element={<RequireLogin><CreateClassPage /></RequireLogin>} />
          {/* D-19: where an invite link lands (public: a guest sees the class card and is asked to sign in). */}
          <Route path="/join/:code" element={<JoinByInvitePage />} />
          <Route path="/me/profile" element={<RequireLogin><MyProfilePage /></RequireLogin>} />
          <Route path="/me/classes" element={<RequireLogin><MyClassesPage /></RequireLogin>} />
          <Route path="/me/courses" element={<RequireLogin><MyCoursesPage /></RequireLogin>} />
          <Route path="/me/events" element={<RequireLogin><MyEventsPage /></RequireLogin>} />

          {/* Classroom Tabs Route */}
          <Route path="/classes/:slug" element={<ClassroomLayout />}>
            <Route index element={<Navigate to="feed" replace />} />
            <Route path="feed" element={<FeedTab />} />
            {/* Blog and events are readable by every class viewer (guests included on a public class). */}
            <Route path="blog" element={<BlogTab />} />
            <Route path="blog/:postId" element={<BlogPostPage />} />
            <Route path="events" element={<EventsTab />} />
            <Route path="events/:eventId" element={<EventDetailPage />} />
            {/* R18-10: everything below except feed/about/store is member-only on the server (401 for a
                guest). RequireSignIn shows a sign-in prompt to a visitor instead of firing requests that
                can only fail (each one also cost an extra /auth/refresh). */}
            <Route path="learn" element={<RequireSignIn><LearnTab /></RequireSignIn>} />
            <Route path="learn/lessons/:lessonId" element={<RequireSignIn><LessonViewPage /></RequireSignIn>} />
            <Route path="exams" element={<RequireSignIn><ExamsTab /></RequireSignIn>} />
            <Route path="exams/:examId/attempt" element={<RequireSignIn><ExamAttemptPage /></RequireSignIn>} />
            <Route path="exams/:examId/result" element={<RequireSignIn><ExamResultPage /></RequireSignIn>} />
            <Route path="leaderboard" element={<RequireSignIn><LeaderboardTab /></RequireSignIn>} />
            <Route path="documents" element={<RequireSignIn><DocumentsTab /></RequireSignIn>} />
            <Route path="members" element={<RequireSignIn><MembersTab /></RequireSignIn>} />
            <Route path="members/:userId" element={<RequireSignIn><MemberProfilePage /></RequireSignIn>} />
            <Route path="about" element={<AboutTab />} />
            <Route path="store" element={<StoreTab />} />
          </Route>

          {/* Studio Routes */}
          <Route path="/studio/classes/:id" element={<RequireLogin><StudioLayout /></RequireLogin>}>
            {/* R7-01: StudioLayout itself redirects the bare index route to the first Studio
                page the signed-in user is actually authorized for (see StudioLayout.tsx),
                instead of hardcoding "overview" which requires class-wide STUDIO:VIEW. */}
            <Route path="overview" element={<StudioOverview />} />
            <Route path="courses" element={<StudioCourses />} />
            {/* Course wizard: create (new) and edit / continue a draft (:courseId/edit?step=n). */}
            <Route path="courses/new" element={<StudioCourseWizard />} />
            <Route path="courses/:courseId/edit" element={<StudioCourseWizard />} />
            <Route path="exams" element={<StudioExams />} />
            <Route path="grading" element={<StudioGrading />} />
            <Route path="leaderboard" element={<StudioLeaderboard />} />
            <Route path="staff" element={<StudioStaff />} />
            <Route path="members" element={<StudioMembers />} />
            <Route path="settings" element={<StudioSettings />} />
            <Route path="segments" element={<StudioSegments />} />
            <Route path="store" element={<StudioStore />} />
            <Route path="audit" element={<StudioAudit />} />
            <Route path="feed" element={<StudioFeed />} />
            <Route path="documents" element={<StudioDocuments />} />
            <Route path="about" element={<StudioAbout />} />
            <Route path="blog" element={<StudioBlog />} />
            <Route path="events" element={<StudioEvents />} />
          </Route>

          {/* Platform admin ("Quản trị nền tảng"): guests go to /login, other accounts see a ForbiddenState. */}
          <Route path="/admin" element={<RequireLogin><AdminLayout /></RequireLogin>}>
            <Route index element={<Navigate to="overview" replace />} />
            <Route path="overview" element={<AdminOverview />} />
            <Route path="users" element={<AdminUsers />} />
            <Route path="users/:id" element={<AdminUserDetail />} />
            <Route path="classes" element={<AdminClasses />} />
            <Route path="classes/:id" element={<AdminClassDetail />} />
            <Route path="privacy" element={<AdminPrivacy />} />
            <Route path="audit" element={<AdminAudit />} />
            <Route path="*" element={<Navigate to="overview" replace />} />
          </Route>

          {/* Fallback */}
          <Route path="*" element={<NotFound />} />
        </Routes>
      </div>
    </div>
  );
};

export const App: React.FC = () => {
  const [router] = React.useState(() => createBrowserRouter([{ path: '*', element: <AppShell /> }]));
  return (
    <AuthProvider>
      <RouterProvider router={router} />
    </AuthProvider>
  );
};
