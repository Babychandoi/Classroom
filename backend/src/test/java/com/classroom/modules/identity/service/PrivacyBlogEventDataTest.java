package com.classroom.modules.identity.service;

import com.classroom.modules.blog.model.BlogPost;
import com.classroom.modules.blog.repository.BlogPostRepository;
import com.classroom.modules.classroom.dto.CreateClassroomRequest;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.service.ClassroomService;
import com.classroom.modules.event.model.ClassEvent;
import com.classroom.modules.event.repository.ClassEventRepository;
import com.classroom.modules.event.repository.EventRegistrationRepository;
import com.classroom.modules.event.service.EventService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-27 review: the data export covers blog posts, events created / hosted and event registrations; a COMPLETED deletion frees the user's
 * seats in events that have not ended (registered_count follows), keeps past registrations as history, and leaves authored posts and hosted
 * events in place as class content under the anonymised user.
 */
@SpringBootTest
@ActiveProfiles("test")
class PrivacyBlogEventDataTest {

    private static final String PASSWORD = "Mat-khau-xac-nhan-1";

    @Autowired private PrivacyService privacyService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ClassroomService classroomService;
    @Autowired private ClassMemberRepository memberRepository;
    @Autowired private BlogPostRepository blogPostRepository;
    @Autowired private ClassEventRepository eventRepository;
    @Autowired private EventRegistrationRepository registrationRepository;
    @Autowired private EventService eventService;

    private User user(String tag) {
        User u = new User(UUID.randomUUID().toString(), "priv-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@d27.test",
                passwordEncoder.encode(PASSWORD), "Priv " + tag, "USER");
        return userRepository.save(u);
    }

    private ClassEvent event(String classId, String creator, String host, Instant startsAt, String title) {
        ClassEvent e = new ClassEvent();
        e.setClassId(classId);
        e.setCreatedBy(creator);
        e.setHostUserId(host);
        e.setTitle(title);
        e.setFormat(ClassEvent.FORMAT_ONLINE);
        e.setStartsAt(startsAt.truncatedTo(ChronoUnit.MICROS));
        e.setEndsAt(startsAt.plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS));
        e.setAudience(ClassEvent.AUDIENCE_PUBLIC);
        return eventRepository.save(e);
    }

    @Test
    @DisplayName("export lists blogPosts / events / eventRegistrations; COMPLETED frees upcoming seats only and keeps authored content")
    void exportAndDeletion() {
        User owner = user("owner");
        User subject = user("subject");
        User other = user("other");
        User admin = user("admin");
        CreateClassroomRequest req = new CreateClassroomRequest();
        req.setTitle("Lop quyen du lieu");
        req.setSlug("priv-" + UUID.randomUUID().toString().substring(0, 10));
        String classId = classroomService.createClassroom(owner.getId(), req).getId();
        memberRepository.save(new ClassMember(classId, subject.getId(), "STUDENT"));
        memberRepository.save(new ClassMember(classId, other.getId(), "STUDENT"));
        // D-28: a pending join request in another class disappears on deletion (no REMOVED row is left behind)
        CreateClassroomRequest approvalReq = new CreateClassroomRequest();
        approvalReq.setTitle("Lop duyet quyen du lieu");
        approvalReq.setRequireApproval(true);
        String approvalClassId = classroomService.createClassroom(owner.getId(), approvalReq).getId();
        classroomService.joinClassroom(approvalClassId, subject.getId());
        assertEquals("PENDING", memberRepository.findByClassIdAndUserId(approvalClassId, subject.getId()).orElseThrow().getState());

        BlogPost post = new BlogPost();
        post.setClassId(classId);
        post.setAuthorId(subject.getId());
        post.setTitle("Bai cua chu the");
        post.setContentMarkdown("noi dung");
        post = blogPostRepository.save(post);
        ClassEvent hosted = event(classId, owner.getId(), subject.getId(), Instant.now().plus(5, ChronoUnit.DAYS), "Chu the dan");
        ClassEvent upcoming = event(classId, owner.getId(), owner.getId(), Instant.now().plus(2, ChronoUnit.DAYS), "Sap toi");
        eventService.register(upcoming.getId(), subject.getId());
        eventService.register(upcoming.getId(), other.getId());
        ClassEvent past = event(classId, owner.getId(), owner.getId(), Instant.now().minus(3, ChronoUnit.DAYS), "Da qua");
        registrationRepository.save(new com.classroom.modules.event.model.EventRegistration(past.getId(), subject.getId()));
        past.setRegisteredCount(1);
        eventRepository.save(past);

        Map<String, Object> export = privacyService.export(subject.getId(), PASSWORD, 0);
        List<?> posts = (List<?>) export.get("blogPosts");
        List<?> events = (List<?>) export.get("events");
        List<?> regs = (List<?>) export.get("eventRegistrations");
        assertEquals(1, posts.size());
        assertTrue(posts.toString().contains(post.getId()));
        assertEquals(1, events.size(), "the hosted event (created by someone else) is the subject's data too");
        assertTrue(events.toString().contains(hosted.getId()));
        assertEquals(2, regs.size());
        assertTrue(regs.toString().contains(upcoming.getId()) && regs.toString().contains(past.getId()));
        assertFalse(((List<?>) privacyService.export(other.getId(), PASSWORD, 0).get("blogPosts")).toString().contains(post.getId()));

        privacyService.requestDeletion(subject.getId(), PASSWORD, "Xin xoa tai khoan");
        privacyService.resolve(subject.getId(), admin.getId(), "COMPLETED", "Da ra soat, khong co nghia vu luu giu");

        assertFalse(registrationRepository.existsByEventIdAndUserId(upcoming.getId(), subject.getId()), "upcoming seat freed");
        assertEquals(1, eventRepository.findById(upcoming.getId()).orElseThrow().getRegisteredCount());
        assertEquals(1, registrationRepository.countByEventId(upcoming.getId()));
        assertTrue(registrationRepository.existsByEventIdAndUserId(upcoming.getId(), other.getId()));
        assertTrue(registrationRepository.existsByEventIdAndUserId(past.getId(), subject.getId()), "past attendance kept as history");
        assertEquals(1, eventRepository.findById(past.getId()).orElseThrow().getRegisteredCount());

        assertEquals(subject.getId(), blogPostRepository.findById(post.getId()).orElseThrow().getAuthorId(), "the post stays as class content");
        assertEquals(subject.getId(), eventRepository.findById(hosted.getId()).orElseThrow().getHostUserId(), "the hosted event stays");
        assertTrue(memberRepository.findByClassIdAndUserId(approvalClassId, subject.getId()).isEmpty(), "pending request deleted");
        assertEquals("REMOVED", memberRepository.findByClassIdAndUserId(classId, subject.getId()).orElseThrow().getState());
        assertEquals("Tài khoản đã xóa", userRepository.findById(subject.getId()).orElseThrow().getFullName(),
                "byline / host now show the anonymised record");
    }
}
