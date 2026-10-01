import React from 'react';
import { BrowserRouter, Routes, Route, Navigate, useLocation, Link } from 'react-router-dom';
import { AuthProvider, useAuth } from './context/AuthContext';
import { Navbar } from './components/Navbar';
import { RequireSignIn } from './components/RequireSignIn';
import { LoginPage } from './pages/LoginPage';
import { ClassesPage } from './pages/ClassesPage';
import { MyProfilePage } from './pages/MyProfilePage';
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

const RequireLogin: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, isLoading, isReconnecting, retryReconnect } = useAuth();
  const location = useLocation();
  if (isLoading) return <div role="status" className="p-8">Đang xác thực...</div>;
  // R9-05/R10-01: bootstrap could not reach the backend (429/5xx/network) even after retrying -
  // this is not the same as "no session" (401), so keep showing a reconnecting state here instead
  // of bouncing an otherwise still-logged-in user to /login. AuthContext keeps retrying with
  // capped backoff and on 'online'/'visibilitychange' in the background; this screen also offers a
  // manual retry and a way out to the login page in case the user's session did in fact end.
  if (isReconnecting && !user) {
    return (
      <div role="status" className="p-8 space-y-3">
        <p>Đang kết nối lại...</p>
        <div className="flex items-center space-x-3">
          <button
            type="button"
            onClick={retryReconnect}
            className="px-3 py-1.5 text-xs font-bold rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white transition"
          >
            Thử lại
          </button>
          <Link to="/login" className="text-xs font-bold text-indigo-600 hover:underline">
            Đăng nhập
          </Link>
        </div>
      </div>
    );
  }
  return user ? <>{children}</> : <Navigate to="/login" state={{ from: location }} replace />;
};

export const App: React.FC = () => {
  return (
    <AuthProvider>
      <BrowserRouter>
        <div className="min-h-screen flex flex-col bg-slate-50 text-slate-900">
          <Navbar />
          <div className="flex-1 flex flex-col">
            <Routes>
              {/* Home & Auth */}
              <Route path="/" element={<Navigate to="/classes" replace />} />
              <Route path="/login" element={<LoginPage />} />
              <Route path="/privacy" element={<PrivacyPage />} />
              <Route path="/classes" element={<ClassesPage />} />
              {/* D-19: where an invite link lands (public: a guest sees the class card and is asked to sign in). */}
              <Route path="/join/:code" element={<JoinByInvitePage />} />
              <Route path="/me/profile" element={<RequireLogin><MyProfilePage /></RequireLogin>} />

              {/* Classroom Tabs Route */}
              <Route path="/classes/:slug" element={<ClassroomLayout />}>
                <Route index element={<Navigate to="feed" replace />} />
                <Route path="feed" element={<FeedTab />} />
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
              </Route>

              {/* Fallback */}
              <Route path="*" element={<div role="alert" className="p-8">Không tìm thấy trang yêu cầu. <a href="/classes">Về danh sách lớp</a></div>} />
            </Routes>
          </div>
        </div>
      </BrowserRouter>
    </AuthProvider>
  );
};
