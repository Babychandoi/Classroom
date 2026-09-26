import React from 'react';
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { AuthProvider, useAuth } from './context/AuthContext';
import { Navbar } from './components/Navbar';
import { LoginPage } from './pages/LoginPage';
import { ClassesPage } from './pages/ClassesPage';
import { ClassroomLayout } from './pages/ClassroomLayout';

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
import { StoreTab } from './pages/classroom/StoreTab';

import { StudioLayout } from './pages/studio/StudioLayout';
import { StudioOverview } from './pages/studio/StudioOverview';
import { StudioCourses } from './pages/studio/StudioCourses';
import { StudioExams } from './pages/studio/StudioExams';
import { StudioGrading } from './pages/studio/StudioGrading';
import { StudioStaff } from './pages/studio/StudioStaff';
import { StudioSegments } from './pages/studio/StudioSegments';
import { StudioStore } from './pages/studio/StudioStore';
import { StudioAudit } from './pages/studio/StudioAudit';
import { StudioAbout, StudioDocuments, StudioFeed } from './pages/studio/StudioCommunity';

const RequireLogin: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, isLoading } = useAuth();
  const location = useLocation();
  if (isLoading) return <div role="status" className="p-8">Đang xác thực...</div>;
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
              <Route path="/classes" element={<ClassesPage />} />

              {/* Classroom Tabs Route */}
              <Route path="/classes/:slug" element={<ClassroomLayout />}>
                <Route index element={<Navigate to="feed" replace />} />
                <Route path="feed" element={<FeedTab />} />
                <Route path="learn" element={<LearnTab />} />
                <Route path="learn/lessons/:lessonId" element={<LessonViewPage />} />
                <Route path="exams" element={<ExamsTab />} />
                <Route path="exams/:examId/attempt" element={<ExamAttemptPage />} />
                <Route path="exams/:examId/result" element={<ExamResultPage />} />
                <Route path="leaderboard" element={<LeaderboardTab />} />
                <Route path="documents" element={<DocumentsTab />} />
                <Route path="members" element={<MembersTab />} />
                <Route path="members/:userId" element={<MemberProfilePage />} />
                <Route path="about" element={<AboutTab />} />
                <Route path="store" element={<StoreTab />} />
              </Route>

              {/* Studio Routes */}
              <Route path="/studio/classes/:id" element={<RequireLogin><StudioLayout /></RequireLogin>}>
                <Route index element={<Navigate to="overview" replace />} />
                <Route path="overview" element={<StudioOverview />} />
                <Route path="courses" element={<StudioCourses />} />
                <Route path="exams" element={<StudioExams />} />
                <Route path="grading" element={<StudioGrading />} />
                <Route path="staff" element={<StudioStaff />} />
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
