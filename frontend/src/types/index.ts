export interface User {
  id: string;
  email: string;
  fullName: string;
  avatarUrl?: string;
  bio?: string;
  role: string;
  status: string;
}

export interface AuthResponse {
  token: string;
  userId: string;
  email: string;
  fullName: string;
  role: string;
  avatarUrl?: string;
}

export interface Classroom {
  id: string;
  ownerId: string;
  ownerName?: string;
  slug: string;
  title: string;
  description?: string;
  coverImageUrl?: string;
  status: string;
  memberCount: number;
  isOwner?: boolean;
  isMember?: boolean;
  isPro?: boolean;
  userRole?: string;
  studioPermissions?: string[];
  createdAt: string;
}

export interface Course {
  id: string;
  classId: string;
  productId?: string;
  title: string;
  description?: string;
  coverImageUrl?: string;
  accessMode: 'FREE' | 'PURCHASE_REQUIRED';
  status: string;
  position: number;
  canLearn: boolean;
  totalLessons: number;
  completedLessons: number;
  progressPercentage: number;
  sections?: Section[];
}

export interface Section {
  id: string;
  courseId: string;
  title: string;
  position: number;
  lessons: Lesson[];
}

export interface Lesson {
  id: string;
  sectionId: string;
  courseId: string;
  title: string;
  type: 'VIDEO' | 'TEXT' | 'DOCUMENT' | 'ASSIGNMENT';
  contentText?: string;
  mediaAssetId?: string;
  mediaDownloadUrl?: string;
  durationMinutes: number;
  position: number;
  completed: boolean;
}

export interface QuestionAnswer {
  id: string;
  lessonId: string;
  userId: string;
  authorName: string;
  questionText: string;
  createdAt: string;
  answers: {
    id: string;
    questionId: string;
    userId: string;
    authorName: string;
    answerText: string;
    createdAt: string;
  }[];
}

export interface Post {
  id: string;
  classId: string;
  authorId: string | null;
  authorName?: string;
  authorAvatarUrl?: string;
  title: string;
  contentMarkdown: string;
  visibility: 'PUBLIC' | 'FREE' | 'PRO' | 'PRODUCT_OWNER' | 'SEGMENT';
  pinned: boolean;
  status: string;
  commentCount: number;
  createdAt: string;
  comments?: Comment[];
}

export interface Comment {
  id: string;
  postId: string;
  authorId: string | null;
  authorName: string;
  authorAvatarUrl?: string;
  content: string;
  createdAt: string;
}

export interface DocumentAsset {
  id: string;
  classId: string;
  title: string;
  description?: string;
  mediaAssetId: string;
  filename?: string;
  mimeType?: string;
  sizeBytes?: number;
  visibility: string;
  targetProductId?: string;
  targetCourseId?: string;
  downloadUrl?: string;
  createdAt: string;
}

export interface Exam {
  id: string;
  classId: string;
  title: string;
  description?: string;
  scheduleStart?: string;
  scheduleEnd?: string;
  durationMinutes: number;
  attemptLimit: number;
  audienceScope: 'ALL' | 'PRO' | 'COURSE' | 'SEGMENT' | 'COURSE_SEGMENT';
  status: string;
  passScore: number;
  canEnter: boolean;
  userAttemptsCount: number;
  questionCount?: number;
  createdAt: string;
  questions?: Question[];
}

export interface Question {
  id: string;
  examId: string;
  questionText: string;
  type: 'MULTIPLE_CHOICE' | 'ESSAY' | 'TRUE_FALSE';
  points: number;
  position: number;
  options?: AnswerOption[];
}

export interface AnswerOption {
  id: string;
  questionId: string;
  optionKey: string;
  optionText: string;
  position: number;
}

export interface ExamAttempt {
  id: string;
  examId: string;
  examTitle: string;
  userId: string;
  classId: string;
  startedAt: string;
  submittedAt?: string;
  endsAt: string;
  score?: number;
  totalPoints: number;
  status: 'IN_PROGRESS' | 'SUBMITTED' | 'GRADING' | 'GRADED' | 'PUBLISHED' | 'CANCELLED';
  isPreview: boolean;
  questions?: Question[];
  answers?: {
    questionId: string;
    studentAnswer: string;
    pointsAwarded?: number;
    teacherFeedback?: string;
  }[];
}

export interface LeaderboardEntry {
  rank: number;
  userId: string;
  userFullName: string;
  userAvatarUrl?: string;
  classId: string;
  totalPoints: number;
  currentTier: string;
  lastCalculatedAt: string;
}

export interface Product {
  id: string;
  classId: string;
  targetCourseId?: string;
  targetCourseTitle?: string;
  title: string;
  description?: string;
  status: string;
  price: number;
  currency: string;
  durationDays: number;
  userHasActiveEntitlement?: boolean;
  entitlementExpiresAt?: string;
}

export interface Order {
  id: string;
  orderNumber: string;
  buyerId: string;
  classId: string;
  status: 'PENDING' | 'PAID' | 'FAILED' | 'CANCELLED' | 'REFUNDED';
  totalAmount: number;
  currency: string;
  provider: string;
  checkoutUrl?: string;
  paidAt?: string;
  createdAt: string;
  items?: {
    productId: string;
    productName: string;
    price: number;
    durationDays: number;
  }[];
}

export interface StaffAssignment {
  id: string;
  classId: string;
  userId: string;
  userEmail?: string;
  userFullName?: string;
  status: string;
  assignedAt: string;
  permissions: {
    id?: string;
    module: string;
    action: string;
    scopeCourseId?: string;
  }[];
}

export interface MemberProfile {
  id: string;
  email?: string | null;
  fullName: string;
  avatarUrl?: string | null;
  bio?: string | null;
  role?: string | null;
  status?: string | null;
  createdAt?: string | null;
  membershipRole?: string;
  isPro?: boolean;
  totalPoints?: number;
  rankTier?: string;
}
