import { Route } from 'react-router-dom';
import { RequireSignIn } from '../../components/RequireSignIn';
import { HomeworkTab } from './homework/HomeworkTab';
import { HomeworkAttemptPage } from './homework/HomeworkAttemptPage';
import { HomeworkResultPage } from './homework/HomeworkResultPage';
import { StudioAssessmentsPage } from './studio/StudioAssessmentsPage';
import { AssessmentResultsPage } from './studio/AssessmentResultsPage';
import { AssessmentBuilderPage } from './builder/AssessmentBuilderPage';

/**
 * Route fragments for the "Bài tập & đề" feature (plan R1 §4, G2 ↔ G3 interface). App.tsx places:
 * - classHomeworkRoutes() inside the `/classes/:slug` route (ClassroomLayout outlet);
 * - studioAssessmentRoutes() inside the `/studio/classes/:id` route (StudioLayout outlet);
 * - assessmentTopLevelRoutes() at top level, before the `*` fallback.
 * React Router flattens Fragments in createRoutesFromChildren, so returning a Fragment is safe.
 */
export function classHomeworkRoutes(): JSX.Element {
  return (
    <>
      <Route path="homework" element={<RequireSignIn><HomeworkTab /></RequireSignIn>} />
      <Route path="homework/:assignmentId/attempt" element={<RequireSignIn><HomeworkAttemptPage /></RequireSignIn>} />
      <Route path="homework/:assignmentId/result" element={<RequireSignIn><HomeworkResultPage /></RequireSignIn>} />
    </>
  );
}

export function studioAssessmentRoutes(): JSX.Element {
  return (
    <>
      <Route path="assessments" element={<StudioAssessmentsPage />} />
      <Route path="assessments/results/:assignmentId" element={<AssessmentResultsPage />} />
    </>
  );
}

export function assessmentTopLevelRoutes(): JSX.Element {
  return (
    <Route
      path="/studio/classes/:id/assessments/:assessmentId/edit"
      element={<RequireSignIn><AssessmentBuilderPage /></RequireSignIn>}
    />
  );
}

/** Fixed path strings (contract with G3 shell / focus routes). */
export const ASSESSMENT_PATHS = {
  classHomework: (slug: string) => `/classes/${slug}/homework`,
  homeworkAttempt: (slug: string, assignmentId: string) => `/classes/${slug}/homework/${assignmentId}/attempt`,
  homeworkResult: (slug: string, assignmentId: string, attemptId?: string | null) =>
    `/classes/${slug}/homework/${assignmentId}/result${attemptId ? `?attempt=${encodeURIComponent(attemptId)}` : ''}`,
  studioList: (classId: string) => `/studio/classes/${classId}/assessments`,
  studioResults: (classId: string, assignmentId: string) => `/studio/classes/${classId}/assessments/results/${assignmentId}`,
  builder: (classId: string, assessmentId: string) => `/studio/classes/${classId}/assessments/${assessmentId}/edit`,
} as const;
