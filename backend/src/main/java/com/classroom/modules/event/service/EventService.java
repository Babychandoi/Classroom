package com.classroom.modules.event.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.dto.PersonSummaryDto;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.event.dto.ClassEventDto;
import com.classroom.modules.event.dto.CreateEventRequest;
import com.classroom.modules.event.dto.EventRegistrantDto;
import com.classroom.modules.event.dto.UpdateEventRequest;
import com.classroom.modules.event.model.ClassEvent;
import com.classroom.modules.event.model.EventRegistration;
import com.classroom.modules.event.repository.ClassEventRepository;
import com.classroom.modules.event.repository.EventRegistrationRepository;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.media.service.MediaService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * D-27: class events and registrations.
 *
 * <ul>
 *   <li>Reads follow {@link AccessPolicy#requireVisibleClass} (guests included for a PUBLIC ACTIVE class; a hidden PRIVATE class is 404).</li>
 *   <li>{@code meetingUrl} is only returned to a registered caller and to managers (owner, EVENT:VIEW or EVENT:EDIT); never on the
 *   cross-class rail.</li>
 *   <li>Capacity: every registration, unregistration and capacity change locks the event row first ({@code SELECT ... FOR UPDATE}) and
 *   keeps {@code registered_count} equal to the number of registration rows inside that lock, so the "full" check is exact under
 *   concurrency. Register / unregister are idempotent.</li>
 *   <li>D-11: in an ARCHIVED class nothing new can be created, and nobody can register.</li>
 * </ul>
 */
@Service
public class EventService {

    public static final String MEDIA_PURPOSE = "EVENT";
    public static final int CLASS_LIST_MAX = 100;
    public static final int UPCOMING_DEFAULT_SIZE = 6;
    public static final int UPCOMING_MAX_SIZE = 20;
    public static final Duration MAX_DURATION = Duration.ofDays(7);
    static final String CLASS_NOT_FOUND = "Không tìm thấy lớp học";
    static final String EVENT_NOT_FOUND = "Không tìm thấy sự kiện";
    public static final String FULL_MESSAGE = "Sự kiện đã đủ chỗ";

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final ClassEventRepository eventRepository;
    private final EventRegistrationRepository registrationRepository;
    private final ClassroomRepository classroomRepository;
    private final ClassMemberRepository memberRepository;
    private final AccessPolicy accessPolicy;
    private final UserRepository userRepository;
    private final MediaService mediaService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public EventService(ClassEventRepository eventRepository,
                        EventRegistrationRepository registrationRepository,
                        ClassroomRepository classroomRepository,
                        ClassMemberRepository memberRepository,
                        AccessPolicy accessPolicy,
                        UserRepository userRepository,
                        @Lazy MediaService mediaService,
                        AuditService auditService,
                        ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.registrationRepository = registrationRepository;
        this.classroomRepository = classroomRepository;
        this.memberRepository = memberRepository;
        this.accessPolicy = accessPolicy;
        this.userRepository = userRepository;
        this.mediaService = mediaService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private boolean isManager(String userId, String classId) {
        return userId != null && accessPolicy.canManageAny(userId, classId, "EVENT", "VIEW", "EDIT");
    }

    /**
     * Who the caller is in ONE class, resolved once per request / listing. {@code restrictedClass}: PAID or PRIVATE, where every event is
     * members-only. Eligibility is re-evaluated on every read, so a registrant who has since lost it (EXPIRED / REMOVED / BLOCKED member,
     * or no longer a member for a MEMBERS event) stops seeing {@code meetingUrl}.
     */
    private record Viewer(String userId, boolean manager, boolean member, boolean blocked, boolean restrictedClass) {
        static final Viewer NOBODY = new Viewer(null, false, false, false, true);

        /** Whether the caller may (still) take part in the event. */
        boolean eligible(ClassEvent e) {
            if (userId == null) return false;
            boolean membersOnly = restrictedClass || ClassEvent.AUDIENCE_MEMBERS.equals(e.getAudience());
            return membersOnly ? member : !blocked;
        }

        boolean seesMeetingUrl(ClassEvent e, boolean registered) {
            return manager || (registered && eligible(e));
        }
    }

    private Viewer viewer(Classroom classroom, String userId) {
        if (userId == null || classroom == null) return Viewer.NOBODY;
        String classId = classroom.getId();
        return new Viewer(userId, isManager(userId, classId), accessPolicy.isMember(userId, classId), isBlocked(classId, userId),
                classroom.isPaid() || classroom.isPrivate());
    }

    private Viewer viewer(String classId, String userId) {
        return userId == null ? Viewer.NOBODY : viewer(classroomRepository.findById(classId).orElse(null), userId);
    }

    // ------------------------------------------------------------------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<ClassEventDto> listForClass(String classId, String userId, String scope) {
        Classroom classroom = accessPolicy.requireVisibleClass(classId, userId, CLASS_NOT_FOUND);
        String normalised = scope == null || scope.isBlank() ? "UPCOMING" : scope.trim().toUpperCase(Locale.ROOT);
        Instant now = now();
        Pageable limit = Pageable.ofSize(CLASS_LIST_MAX);
        List<ClassEvent> rows = switch (normalised) {
            case "UPCOMING" -> eventRepository.findUpcomingByClass(classId, now, limit);
            case "PAST" -> eventRepository.findPastByClass(classId, now, limit);
            case "ALL" -> eventRepository.findAllByClass(classId, limit);
            default -> throw new AppException(ErrorCode.BAD_REQUEST, "Phạm vi sự kiện chỉ có thể là upcoming, past hoặc all");
        };
        return toDtos(rows, viewer(classroom, userId), null);
    }

    @Transactional(readOnly = true)
    public ClassEventDto get(String eventId, String userId) {
        ClassEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, EVENT_NOT_FOUND));
        Classroom classroom = accessPolicy.requireVisibleClass(event.getClassId(), userId, EVENT_NOT_FOUND);
        return toDtos(List.of(event), viewer(classroom, userId), null).get(0);
    }

    /** D-27: the public cross-class rail: upcoming SCHEDULED events of PUBLIC + ACTIVE classes; {@code meetingUrl} is always null here. */
    @Transactional(readOnly = true)
    public List<ClassEventDto> upcoming(String userId, Integer size) {
        int pageSize = Math.min(Math.max(size != null ? size : UPCOMING_DEFAULT_SIZE, 1), UPCOMING_MAX_SIZE);
        List<ClassEvent> rows = eventRepository.findUpcomingInPublicClasses(now(), Pageable.ofSize(pageSize));
        Map<String, Classroom> classes = new HashMap<>();
        if (!rows.isEmpty()) {
            classroomRepository.findAllById(rows.stream().map(ClassEvent::getClassId).distinct().toList())
                    .forEach(c -> classes.put(c.getId(), c));
        }
        // The rail never reveals meetingUrl (NOBODY is not a manager); isRegistered is still the caller's own flag.
        return toDtos(rows, new Viewer(userId, false, false, false, true), classes);
    }

    @Transactional(readOnly = true)
    public List<EventRegistrantDto> registrants(String eventId, String userId) {
        ClassEvent event = loadForManage(eventId, userId, false);
        if (!isManager(userId, event.getClassId())) {
            accessPolicy.enforceManage(userId, event.getClassId(), "EVENT", "VIEW", null);
        }
        List<EventRegistration> rows = registrationRepository.findByEventIdOrderByRegisteredAtAsc(eventId);
        Map<String, User> users = new HashMap<>();
        if (!rows.isEmpty()) {
            userRepository.findAllById(rows.stream().map(EventRegistration::getUserId).distinct().toList())
                    .forEach(u -> users.put(u.getId(), u));
        }
        List<EventRegistrantDto> result = new ArrayList<>(rows.size());
        for (EventRegistration r : rows) {
            result.add(new EventRegistrantDto(PersonSummaryDto.of(users.get(r.getUserId()), r.getUserId()), r.getRegisteredAt()));
        }
        return result;
    }

    // ----------------------------------------------------------------------------------------------------------------------------- writes

    @Transactional
    public ClassEventDto create(String classId, String userId, CreateEventRequest request) {
        accessPolicy.enforceManage(userId, classId, "EVENT", "CREATE", null);
        if (accessPolicy.isClassFrozen(classId)) { // D-11 / D-29
            throw new AppException(ErrorCode.CONFLICT, (accessPolicy.isClassSuspended(classId) ? AccessPolicy.SUSPENDED_MESSAGE : "Lớp học đã được lưu trữ; không thể tạo sự kiện mới"));
        }
        ClassEvent event = new ClassEvent();
        event.setClassId(classId);
        event.setCreatedBy(userId);
        event.setTitle(request.title().trim());
        event.setDescription(blankToNull(request.description()));
        event.setForWhom(blankToNull(request.forWhom()));
        event.setTakeawaysJson(takeawaysJson(request.takeaways()));
        event.setFormat(request.format().trim().toUpperCase(Locale.ROOT));
        event.setLocation(blankToNull(request.location()));
        event.setMeetingUrl(validMeetingUrl(request.meetingUrl()));
        event.setStartsAt(request.startsAt().truncatedTo(ChronoUnit.MICROS));
        event.setEndsAt(request.endsAt().truncatedTo(ChronoUnit.MICROS));
        validateSchedule(event.getStartsAt(), event.getEndsAt());
        event.setCapacity(request.capacity());
        String host = blankToNull(request.hostUserId());
        event.setHostUserId(host == null ? userId : validHost(host, classId));
        String cover = blankToNull(request.coverMediaId());
        event.setCoverMediaId(cover == null ? null : mediaService.requireAttachableImage(cover, classId, MEDIA_PURPOSE));
        event.setAudience(request.audience().trim().toUpperCase(Locale.ROOT));
        event.setStatus(ClassEvent.STATUS_SCHEDULED);
        event.setRegisteredCount(0);
        ClassEvent saved = eventRepository.save(event);

        audit(saved, userId, "EVENT_CREATE", details(saved, null));
        return toDto(saved, userId);
    }

    @Transactional
    public ClassEventDto update(String eventId, String userId, UpdateEventRequest request) {
        // Locked first: a capacity change must see the registered count that concurrent registrations leave behind (loading the row
        // unlocked first would leave a stale copy in the persistence context that the locking query does not refresh).
        ClassEvent event = loadForManage(eventId, userId, true);
        accessPolicy.enforceManage(userId, event.getClassId(), "EVENT", "EDIT", null);
        List<String> changed = new ArrayList<>();

        if (request.getTitle() != null) {
            if (request.getTitle().isBlank()) throw new AppException(ErrorCode.BAD_REQUEST, "Tên sự kiện không được để trống");
            event.setTitle(request.getTitle().trim());
            changed.add("title");
        }
        if (request.hasDescription()) {
            event.setDescription(blankToNull(request.getDescription()));
            changed.add("description");
        }
        if (request.hasForWhom()) {
            event.setForWhom(blankToNull(request.getForWhom()));
            changed.add("forWhom");
        }
        if (request.hasTakeaways()) {
            event.setTakeawaysJson(takeawaysJson(request.getTakeaways()));
            changed.add("takeaways");
        }
        if (request.getFormat() != null) {
            event.setFormat(request.getFormat().trim().toUpperCase(Locale.ROOT));
            changed.add("format");
        }
        if (request.hasLocation()) {
            event.setLocation(blankToNull(request.getLocation()));
            changed.add("location");
        }
        if (request.hasMeetingUrl()) {
            event.setMeetingUrl(validMeetingUrl(request.getMeetingUrl()));
            changed.add("meetingUrl");
        }
        if (request.getStartsAt() != null) {
            event.setStartsAt(request.getStartsAt().truncatedTo(ChronoUnit.MICROS));
            changed.add("startsAt");
        }
        if (request.getEndsAt() != null) {
            event.setEndsAt(request.getEndsAt().truncatedTo(ChronoUnit.MICROS));
            changed.add("endsAt");
        }
        validateSchedule(event.getStartsAt(), event.getEndsAt());
        if (request.hasCapacity()) {
            Integer capacity = request.getCapacity();
            if (capacity != null && capacity < event.getRegisteredCount()) {
                throw new AppException(ErrorCode.CONFLICT,
                        "Số chỗ không thể nhỏ hơn số người đã đăng ký (" + event.getRegisteredCount() + ")");
            }
            event.setCapacity(capacity);
            changed.add("capacity");
        }
        if (request.getHostUserId() != null && !request.getHostUserId().isBlank()) {
            event.setHostUserId(validHost(request.getHostUserId().trim(), event.getClassId()));
            changed.add("hostUserId");
        }
        if (request.hasCoverMediaId()) {
            String cover = blankToNull(request.getCoverMediaId());
            event.setCoverMediaId(cover == null ? null : mediaService.requireAttachableImage(cover, event.getClassId(), MEDIA_PURPOSE));
            changed.add("coverMediaId");
        }
        if (request.getAudience() != null) {
            event.setAudience(request.getAudience().trim().toUpperCase(Locale.ROOT));
            changed.add("audience");
        }
        event.setUpdatedAt(now());
        ClassEvent saved = eventRepository.save(event);

        audit(saved, userId, "EVENT_UPDATE", details(saved, changed));
        return toDto(saved, userId);
    }

    @Transactional
    public ClassEventDto cancel(String eventId, String userId) {
        // Locked like update(): saving the whole row must not overwrite a concurrent registration's registered_count.
        ClassEvent event = loadForManage(eventId, userId, true);
        accessPolicy.enforceManage(userId, event.getClassId(), "EVENT", "EDIT", null);
        if (!ClassEvent.STATUS_CANCELLED.equals(event.getStatus())) {
            event.setStatus(ClassEvent.STATUS_CANCELLED);
            event.setUpdatedAt(now());
            event = eventRepository.save(event);
            audit(event, userId, "EVENT_CANCEL", details(event, null));
        }
        return toDto(event, userId);
    }

    @Transactional
    public void delete(String eventId, String userId) {
        ClassEvent event = loadForManage(eventId, userId, true);
        accessPolicy.enforceManage(userId, event.getClassId(), "EVENT", "DELETE", null);
        Map<String, Object> details = details(event, null);
        details.put("registeredCount", event.getRegisteredCount());
        registrationRepository.deleteByEventId(eventId);
        eventRepository.delete(event);
        audit(event, userId, "EVENT_DELETE", details);
    }

    /**
     * POST /events/{id}/registrations. Who may register: an ACTIVE member (owner / staff included) for a MEMBERS event and for any event of
     * a PAID or PRIVATE class; for a PUBLIC event of a PUBLIC FREE class, any signed-in user who can see the class (except someone BLOCKED
     * in it). Already registered -&gt; the same payload (idempotent). Full -&gt; 409; cancelled / ended / archived class -&gt; 409.
     */
    @Transactional
    public ClassEventDto register(String eventId, String userId) {
        // The row lock is the FIRST read of the event in this transaction, so registered_count is the committed, current value.
        ClassEvent event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, EVENT_NOT_FOUND));
        Classroom classroom = accessPolicy.requireVisibleClass(event.getClassId(), userId, EVENT_NOT_FOUND);

        // Eligibility BEFORE the idempotent "already registered" answer: a registrant who has lost it must not get meetingUrl back.
        boolean membersOnly = ClassEvent.AUDIENCE_MEMBERS.equals(event.getAudience()) || classroom.isPaid() || classroom.isPrivate();
        if (membersOnly) {
            if (!accessPolicy.isMember(userId, classroom.getId())) {
                AppException denied = accessPolicy.membershipDenied(userId, classroom.getId());
                if (denied.getErrorCode() == ErrorCode.FORBIDDEN) {
                    throw new AppException(ErrorCode.FORBIDDEN, "Chỉ thành viên lớp học mới đăng ký được sự kiện này");
                }
                throw denied;
            }
        } else if (isBlocked(classroom.getId(), userId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn không thể đăng ký sự kiện của lớp học này");
        }
        Viewer viewer = viewer(classroom, userId);
        if (registrationRepository.existsByEventIdAndUserId(eventId, userId)) {
            return toDtos(List.of(event), viewer, null).get(0);
        }
        if (accessPolicy.isClassFrozen(classroom.getId())) { // D-11 / D-29
            throw new AppException(ErrorCode.CONFLICT,
                    (accessPolicy.isClassSuspended(classroom.getId()) ? AccessPolicy.SUSPENDED_MESSAGE : "Lớp học đã được lưu trữ; không thể đăng ký sự kiện"));
        }
        if (ClassEvent.STATUS_CANCELLED.equals(event.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "Sự kiện đã bị hủy");
        }
        if (event.getEndsAt().isBefore(Instant.now())) {
            throw new AppException(ErrorCode.CONFLICT, "Sự kiện đã kết thúc");
        }
        if (event.isFull()) {
            throw new AppException(ErrorCode.CONFLICT, FULL_MESSAGE);
        }

        registrationRepository.save(new EventRegistration(eventId, userId));
        event.setRegisteredCount(event.getRegisteredCount() + 1);
        ClassEvent saved = eventRepository.save(event);
        return toDtos(List.of(saved), viewer, null).get(0);
    }

    /**
     * DELETE /events/{id}/registrations/me - idempotent. With a registration, the seat is ALWAYS released (even when the class has since
     * become hidden from the caller); the answer is the full event if the caller can still see the class, otherwise only
     * {@link EventUnregisteredDto}. Without a registration the usual visibility rule applies (404 for a hidden PRIVATE class or an unknown
     * id, 401 / 403 for a draft / archived one) and the answer is the event.
     */
    @Transactional
    public Object unregister(String eventId, String userId) {
        ClassEvent event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, EVENT_NOT_FOUND));
        var registration = registrationRepository.findByEventIdAndUserId(eventId, userId);
        if (registration.isEmpty()) {
            Classroom classroom = accessPolicy.requireVisibleClass(event.getClassId(), userId, EVENT_NOT_FOUND);
            return toDtos(List.of(event), viewer(classroom, userId), null).get(0);
        }
        registrationRepository.delete(registration.get());
        event.setRegisteredCount(Math.max(0, event.getRegisteredCount() - 1));
        event = eventRepository.save(event);
        Classroom classroom;
        try {
            classroom = accessPolicy.requireVisibleClass(event.getClassId(), userId, EVENT_NOT_FOUND);
        } catch (AppException hidden) {
            return new EventUnregisteredDto(eventId, false);
        }
        return toDtos(List.of(event), viewer(classroom, userId), null).get(0);
    }

    /** Minimal answer of an unregistration from an event whose class the caller can no longer see. */
    public record EventUnregisteredDto(String id, @com.fasterxml.jackson.annotation.JsonProperty("isRegistered") boolean isRegistered) {}

    // --------------------------------------------------------------------------------------------------------------------------- helpers

    /**
     * 404 for an unknown event and for one of a PRIVATE class hidden from the caller (indistinguishable); the grant is checked by the caller.
     * {@code forUpdate} takes the event row lock as the first read of the row in the transaction.
     */
    private ClassEvent loadForManage(String eventId, String userId, boolean forUpdate) {
        ClassEvent event = (forUpdate ? eventRepository.findByIdForUpdate(eventId) : eventRepository.findById(eventId))
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, EVENT_NOT_FOUND));
        if (accessPolicy.isMissingOrHiddenPrivateClass(event.getClassId(), userId)) {
            throw new AppException(ErrorCode.NOT_FOUND, EVENT_NOT_FOUND);
        }
        return event;
    }

    private boolean isBlocked(String classId, String userId) {
        return memberRepository.findByClassIdAndUserId(classId, userId)
                .map(ClassMember::getState)
                .map(state -> "BLOCKED".equalsIgnoreCase(state) || "BANNED".equalsIgnoreCase(state))
                .orElse(false);
    }

    /** The host is the owner or an ACTIVE staff member of the class. */
    private String validHost(String hostUserId, String classId) {
        if (accessPolicy.isOwner(hostUserId, classId) || accessPolicy.isActiveStaff(hostUserId, classId)) {
            return hostUserId;
        }
        throw new AppException(ErrorCode.BAD_REQUEST, "Người dẫn phải là chủ lớp hoặc nhân sự đang hoạt động của lớp");
    }

    private static void validateSchedule(Instant startsAt, Instant endsAt) {
        if (!endsAt.isAfter(startsAt)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thời gian kết thúc phải sau thời gian bắt đầu");
        }
        if (Duration.between(startsAt, endsAt).compareTo(MAX_DURATION) > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Sự kiện kéo dài tối đa 7 ngày");
        }
    }

    /** http(s) with a host and no user-info; blank = none. */
    static String validMeetingUrl(String raw) {
        String value = blankToNull(raw);
        if (value == null) return null;
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null || uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("not http(s)");
            }
            return value;
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Đường dẫn tham gia phải là địa chỉ http(s) hợp lệ");
        }
    }

    private String takeawaysJson(List<String> takeaways) {
        if (takeaways == null) return null;
        List<String> clean = takeaways.stream().filter(t -> t != null && !t.isBlank()).map(String::trim).toList();
        if (clean.size() > 8) throw new AppException(ErrorCode.BAD_REQUEST, "Tối đa 8 ý \"Mang về gì\"");
        if (clean.stream().anyMatch(t -> t.length() > 200)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Mỗi ý \"Mang về gì\" tối đa 200 ký tự");
        }
        if (clean.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(clean);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> takeaways(ClassEvent event) {
        if (event.getTakeawaysJson() == null || event.getTakeawaysJson().isBlank()) return List.of();
        try {
            return objectMapper.readValue(event.getTakeawaysJson(), STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid persisted event takeaways", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ClassEventDto toDto(ClassEvent event, String userId) {
        return toDtos(List.of(event), viewer(event.getClassId(), userId), null).get(0);
    }

    /**
     * A listing in a fixed number of statements: hosts (1), the viewer's registrations (1), cover images (1; signing is local).
     *
     * @param viewer      the caller in the (single) class listed; decides {@code meetingUrl}
     * @param railClasses non-null only for the cross-class rail: fills classTitle / classSlug and never reveals {@code meetingUrl}
     */
    private List<ClassEventDto> toDtos(List<ClassEvent> events, Viewer viewer, Map<String, Classroom> railClasses) {
        return assemble(events, viewer.userId(), e -> viewer, railClasses == null ? null : railClasses, railClasses != null);
    }

    /**
     * D-30: the cross-class listing of the caller's own registrations. Same assembly (hosts, registrations, covers: a fixed number of
     * statements); the caller's standing is resolved once per class from two batched reads instead of per event. A manager is always an
     * ACTIVE member (canManage needs isMember), so {@code manager} adds nothing to eligibility here and is not looked up.
     */
    @Transactional(readOnly = true)
    public List<ClassEventDto> listMine(String userId, String scope, int page, int size) {
        String normalised = scope == null || scope.isBlank() ? "UPCOMING" : scope.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("UPCOMING", "PAST", "ALL").contains(normalised)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Phạm vi sự kiện chỉ có thể là upcoming, past hoặc all");
        }
        Pageable pageable = org.springframework.data.domain.PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50));
        List<ClassEvent> rows = eventRepository.findRegisteredBy(userId, normalised, now(), pageable);
        if (rows.isEmpty()) return List.of();
        List<String> classIds = rows.stream().map(ClassEvent::getClassId).distinct().toList();
        Map<String, Classroom> classes = new HashMap<>();
        classroomRepository.findAllById(classIds).forEach(c -> classes.put(c.getId(), c));
        Map<String, ClassMember> memberships = new HashMap<>();
        memberRepository.findByUserIdAndClassIdIn(userId, classIds).forEach(m -> memberships.put(m.getClassId(), m));
        Instant at = Instant.now();
        Map<String, Viewer> viewers = new HashMap<>();
        for (String classId : classIds) {
            Classroom classroom = classes.get(classId);
            ClassMember m = memberships.get(classId);
            boolean owner = classroom != null && userId.equals(classroom.getOwnerId());
            boolean member = owner || (classroom != null && !AccessPolicy.isSuspended(classroom) && m != null && m.isActiveAt(at));
            boolean blocked = m != null && ("BLOCKED".equalsIgnoreCase(m.getState()) || "BANNED".equalsIgnoreCase(m.getState()));
            viewers.put(classId, new Viewer(userId, owner, member, blocked,
                    classroom != null && (classroom.isPaid() || classroom.isPrivate())));
        }
        return assemble(rows, userId, e -> viewers.get(e.getClassId()), classes, false);
    }

    private List<ClassEventDto> assemble(List<ClassEvent> events, String userId, java.util.function.Function<ClassEvent, Viewer> viewerFor,
                                         Map<String, Classroom> railClasses, boolean rail) {
        if (events.isEmpty()) return List.of();
        Set<String> hostIds = new LinkedHashSet<>();
        Set<String> coverIds = new LinkedHashSet<>();
        List<String> eventIds = new ArrayList<>(events.size());
        for (ClassEvent e : events) {
            hostIds.add(e.getHostUserId());
            if (e.getCoverMediaId() != null) coverIds.add(e.getCoverMediaId());
            eventIds.add(e.getId());
        }
        Map<String, User> hosts = new HashMap<>();
        userRepository.findAllById(hostIds).forEach(u -> hosts.put(u.getId(), u));
        Set<String> registered = userId == null ? Set.of()
                : new HashSet<>(registrationRepository.findEventIdsRegisteredBy(userId, eventIds));
        Map<String, String> coverUrls = coverIds.isEmpty() ? Map.of() : mediaService.presignedImageUrls(coverIds, MEDIA_PURPOSE);

        List<ClassEventDto> result = new ArrayList<>(events.size());
        for (ClassEvent e : events) {
            boolean isRegistered = registered.contains(e.getId());
            boolean revealUrl = !rail && viewerFor.apply(e).seesMeetingUrl(e, isRegistered);
            Classroom railClass = railClasses == null ? null : railClasses.get(e.getClassId());
            result.add(new ClassEventDto(
                    e.getId(), e.getClassId(),
                    railClass == null ? null : railClass.getTitle(),
                    railClass == null ? null : railClass.getSlug(),
                    e.getTitle(), e.getDescription(), e.getForWhom(), takeaways(e), e.getFormat(), e.getLocation(),
                    revealUrl ? e.getMeetingUrl() : null,
                    e.getStartsAt(), e.getEndsAt(), e.getCapacity(), e.getRegisteredCount(), isRegistered, e.isFull(),
                    PersonSummaryDto.of(hosts.get(e.getHostUserId()), e.getHostUserId()),
                    e.getCoverMediaId(), e.getCoverMediaId() == null ? null : coverUrls.get(e.getCoverMediaId()),
                    e.getAudience(), e.getStatus(), e.getCreatedAt()));
        }
        return result;
    }

    private Map<String, Object> details(ClassEvent event, List<String> changed) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("title", event.getTitle());
        details.put("status", event.getStatus());
        details.put("startsAt", event.getStartsAt() == null ? null : event.getStartsAt().toString());
        details.put("capacity", event.getCapacity());
        if (changed != null) details.put("changed", changed);
        return details;
    }

    private void audit(ClassEvent event, String actorId, String action, Map<String, Object> details) {
        String json;
        try {
            json = objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise audit details", e);
        }
        auditService.record(event.getClassId(), actorId, action, "CLASS_EVENT", event.getId(), json);
    }
}
