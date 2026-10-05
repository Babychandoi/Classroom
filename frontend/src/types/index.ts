export interface User {
  id: string;
  email: string;
  fullName: string;
  avatarUrl?: string;
  bio?: string;
  profileVisibility?: 'PRIVATE' | 'CLASS' | 'PUBLIC';
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

/** D-19: who can see a class. PRIVATE classes are invisible (404) to everybody who is not on the roster. */
export type ClassVisibility = 'PUBLIC' | 'PRIVATE';
/** D-19: how a class is joined. PAID classes sell a CLASS_ACCESS product (see ClassAccessProduct). */
export type ClassAccessType = 'FREE' | 'PAID';
/** D-19: the caller's membership lifecycle. EXPIRED = paid access has lapsed (renew to come back). */
// PENDING = asked to join a class that requires approval (requireApproval); not a member until the owner approves.
export type MemberState = 'ACTIVE' | 'EXPIRED' | 'REMOVED' | 'BLOCKED' | 'PENDING' | 'NONE';

/**
 * D-19: what a PAID class sells. `id` is the productId of POST /orders; `durationDays == null` (lifetime = true) means the
 * purchase never expires. Also the `error.details.accessProduct` of a PAYMENT_REQUIRED (402).
 */
export interface ClassAccessProduct {
  id: string;
  price: number;
  currency: string;
  durationDays: number | null;
  lifetime?: boolean;
}

/**
 * Lifecycle of a class. SUSPENDED = frozen by a platform admin (docs/API-PLATFORM-ADMIN.md §3): hidden from everyone but
 * the owner, who sees it read-only with `suspendedReason`. Older payloads may carry other strings - keep them displayable.
 */
export type ClassStatus = 'ACTIVE' | 'ARCHIVED' | 'SUSPENDED';

export interface Classroom {
  id: string;
  ownerId: string;
  ownerName?: string;
  slug: string;
  title: string;
  description?: string;
  coverImageUrl?: string;
  /** Short-lived presigned URL of an uploaded cover (preferred over coverImageUrl). */
  coverUrl?: string | null;
  ownerAvatarUrl?: string | null;
  upcomingEventCount?: number;
  status: ClassStatus | (string & {});
  /** Why / since when a platform admin suspended the class (only when status is SUSPENDED). */
  suspendedReason?: string | null;
  suspendedAt?: string | null;
  memberCount: number;
  isOwner?: boolean;
  isMember?: boolean;
  isPro?: boolean;
  userRole?: string;
  // R16-01: the caller's membership lifecycle in this class. Only ACTIVE (or the owner) is a member;
  // REMOVED may rejoin on their own ("Tham gia lại"); BLOCKED may not (only a Studio unblock helps).
  // D-19: EXPIRED = a paid member whose access lapsed (isMember=false, userRole=GUEST); accessExpiresAt is then when it lapsed.
  memberState?: MemberState;
  // D-19: PUBLIC (default) or PRIVATE; FREE (default) or PAID. Absent on older payloads - treat as PUBLIC / FREE.
  visibility?: ClassVisibility;
  accessType?: ClassAccessType;
  // D-19: when the CALLER's paid access ends (null = no expiry / not a member).
  accessExpiresAt?: string | null;
  accessProduct?: ClassAccessProduct | null;
  studioPermissions?: string[];
  studioScopedPermissions?: { module: string; action: string; courseId: string }[];
  /** One of GET /classes/categories (null for older classes). */
  category?: string | null;
  avatarMediaId?: string | null;
  /** Short-lived presigned URL of the square class avatar. */
  avatarUrl?: string | null;
  /** CSS object-position ("50% 30%") of the cover / the avatar; null = centred. */
  coverPosition?: string | null;
  avatarPosition?: string | null;
  /** Join requests wait for the owner's approval (memberState PENDING until then). */
  requireApproval?: boolean;
  /** PENDING join requests (only filled for callers with MEMBER:VIEW). */
  pendingRequestCount?: number;
  createdAt: string;
}

/** GET /me/courses item (docs/API-ME.md): a course the caller can learn, with their own progress and resume point. */
export interface MyCourse {
  id: string;
  classId: string;
  classTitle: string;
  classSlug: string;
  classAvatarUrl?: string | null;
  title: string;
  description?: string | null;
  coverImageUrl?: string | null;
  accessMode: 'FREE' | 'PURCHASE_REQUIRED';
  totalLessons: number;
  completedLessons: number;
  progressPercent: number;
  lastActivityAt?: string | null;
  nextLessonId?: string | null;
  started: boolean;
}

export interface Course {
  id: string;
  classId: string;
  productId?: string;
  title: string;
  description?: string;
  coverImageUrl?: string;
  createdAt?: string;
  accessMode: 'FREE' | 'PURCHASE_REQUIRED';
  status: string;
  position: number;
  canLearn: boolean;
  totalLessons: number;
  completedLessons: number;
  progressPercentage: number;
  canEdit?: boolean;
  sections?: Section[];
  // R13-09 (Learn "hết hạn"): why canLearn is true/false, and expiry for a time-boxed entitlement.
  // R14-12: OWNED_UPCOMING = paid, but the entitlement has not started yet (see accessStartsAt).
  accessReason?: 'FREE' | 'OWNED' | 'OWNED_UPCOMING' | 'PRO' | 'EXPIRED' | 'NOT_PURCHASED' | 'STAFF' | 'OWNER';
  expiresAt?: string;
  accessStartsAt?: string;
  // R19-12: whether the course can be bought in the store right now (its product is PUBLISHED); productStatus is
  // the linked product's status. Absent on older payloads - treat only an explicit false as "not for sale".
  canPurchase?: boolean;
  productStatus?: string;
}

export interface Section {
  id: string;
  courseId: string;
  title: string;
  position: number;
  archived?: boolean;
  lessons: Lesson[];
}

export interface Lesson {
  captionsVtt?: string;
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
  archived?: boolean;
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

/** R20-03: one page of OLDER comments of a post (GET /posts/{id}/comments?before=...); comments are oldest-first. */
export interface CommentPage {
  comments: Comment[];
  hasMore: boolean;
  nextBefore: string | null;
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
  targetCourseId?: string | null;
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
  /**
   * R15-02: the stored correct option key (e.g. 'C'). GET /exams/{id} only includes it for callers
   * allowed to see answer keys (owner / explicit EXAM:EDIT); it is always absent for learners and
   * for essay questions.
   */
  answerKey?: string | null;
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
  // R19-04: a PREVIEW attempt's score/points are withheld from staff who may not read the answer key; `notice` says so.
  resultHidden?: boolean;
  notice?: string;
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
  currentTier?: string | null;
  lastCalculatedAt: string;
}

export interface Product {
  id: string;
  classId: string;
  // D-19: CLASS_ACCESS is the product that sells membership of a PAID class (managed only through PUT /classes/{id}/access).
  kind?: 'STANDARD' | 'CLASS_ACCESS';
  targetCourseId?: string;
  targetCourseTitle?: string;
  title: string;
  description?: string;
  status: string;
  price: number;
  currency: string;
  durationDays: number;
  userHasActiveEntitlement?: boolean;
  // R19-06: paid for, but every entitlement starts in the future (pre-sale). entitlementStartsAt is when the earliest begins.
  userOwnsUpcoming?: boolean;
  entitlementStartsAt?: string;
  entitlementExpiresAt?: string;
}

/** POST /orders. `inviteCode` (D-19) is only for the class-access product of a PRIVATE paid class when the buyer is not on the roster. */
export interface CreateOrderRequest {
  classId: string;
  productId: string;
  idempotencyKey: string;
  inviteCode?: string;
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
  refundedAt?: string;
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

export interface UserJourney {
  userId: string;
  classId: string;
  courses: {
    courseId: string;
    courseTitle: string;
    completedLessons: number;
    totalLessons: number;
  }[];
  examResults: {
    examId: string;
    examTitle: string;
    score?: number;
    submittedAt?: string;
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

/** D-19: a row of the Studio roster (GET /classes/{id}/studio/members). `state` is the effective state (an overdue ACTIVE row already reads EXPIRED). */
export interface ClassMember {
  id: string;
  userId: string;
  role: string;
  state: 'ACTIVE' | 'EXPIRED' | 'REMOVED' | 'BLOCKED' | 'BANNED' | string;
  joinedAt: string;
  accessExpiresAt?: string | null;
  userFullName?: string | null;
  userAvatarUrl?: string | null;
  userEmail?: string | null;
  isPro?: boolean;
}

export type InviteStatus = 'ACTIVE' | 'REVOKED' | 'EXPIRED' | 'EXHAUSTED';

/** D-19: one invite as the owner / MEMBER:EDIT staff sees it. `code` is present ONLY in the response of the create call. */
export interface ClassInvite {
  id: string;
  code?: string;
  codeHint: string;
  createdAt: string;
  expiresAt?: string | null;
  maxUses?: number | null;
  usedCount: number;
  status: InviteStatus;
  createdBy?: string;
}

/** D-19: GET /classes/invites/{code} (public): the class card a person holding a valid invite decides on. */
export interface InvitePreview {
  classId: string;
  slug: string;
  title: string;
  description?: string | null;
  coverImageUrl?: string | null;
  accessType: ClassAccessType;
  price?: number | null;
  currency?: string | null;
  durationDays?: number | null;
  lifetime?: boolean | null;
  ownerName?: string | null;
}

// ---------------------------------------------------------------------------------------------------------------
// Blog & events (see docs/API.md "Blog" / "Sự kiện").

export interface PersonSummary {
  id: string;
  fullName: string;
  avatarUrl?: string | null;
}

export type BlogAudience = 'PUBLIC' | 'MEMBERS';
export type BlogStatus = 'DRAFT' | 'PUBLISHED';

export interface BlogPost {
  id: string;
  classId: string;
  title: string;
  excerpt?: string | null;
  category?: string | null;
  /** null in list responses and when `locked`. */
  contentMarkdown?: string | null;
  coverMediaId?: string | null;
  coverUrl?: string | null;
  audience: BlogAudience;
  status: BlogStatus;
  /** The caller may see the card but not the content (MEMBERS post, caller is not a member). */
  locked: boolean;
  readingMinutes: number;
  author: PersonSummary;
  publishedAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface BlogPostPage {
  items: BlogPost[];
  nextCursor?: string | null;
}

export type EventFormat = 'ONLINE' | 'OFFLINE';
export type EventStatus = 'SCHEDULED' | 'CANCELLED';

export interface ClassEvent {
  id: string;
  classId: string;
  classTitle?: string;
  classSlug?: string;
  title: string;
  description?: string | null;
  forWhom?: string | null;
  takeaways: string[];
  format: EventFormat;
  location?: string | null;
  /** Only returned to registered people and to managers. */
  meetingUrl?: string | null;
  startsAt: string;
  endsAt: string;
  capacity?: number | null;
  registeredCount: number;
  isRegistered: boolean;
  isFull: boolean;
  host: PersonSummary;
  coverMediaId?: string | null;
  coverUrl?: string | null;
  audience: 'PUBLIC' | 'MEMBERS';
  status: EventStatus;
  createdAt: string;
}

export interface EventRegistrant {
  user: PersonSummary;
  registeredAt: string;
}
