package com.classroom.integration;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.dto.CreateClassroomRequest;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.service.ClassroomService;
import com.classroom.modules.event.dto.ClassEventDto;
import com.classroom.modules.event.dto.CreateEventRequest;
import com.classroom.modules.event.repository.ClassEventRepository;
import com.classroom.modules.event.repository.EventRegistrationRepository;
import com.classroom.modules.event.service.EventService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-27 against a real MySQL 8.4 (Flyway V1..V44, Hibernate validating the mapping): the capacity of an event holds under contention. Many
 * members register for the last seats at the same instant on separate connections; the row lock on the event lets exactly {@code capacity}
 * of them in, every other one gets 409 "Sự kiện đã đủ chỗ", and {@code registered_count} equals the number of registration rows.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
class EventCapacityConcurrencyIntegrationTest {

    @Autowired private EventService eventService;
    @Autowired private ClassroomService classroomService;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassMemberRepository memberRepository;
    @Autowired private ClassEventRepository eventRepository;
    @Autowired private EventRegistrationRepository registrationRepository;
    @Autowired private JdbcTemplate jdbc;

    private User newUser(String tag) {
        return userRepository.save(new User(UUID.randomUUID().toString(),
                "cap-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@d27.test", "hash", "Cap " + tag, "USER"));
    }

    private List<Object> runTogether(List<? extends Callable<?>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        try {
            CountDownLatch ready = new CountDownLatch(jobs.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> job : jobs) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return job.call();
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) results.add(f.get(60, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("12 members race for 5 seats: exactly 5 registrations, 7 x 409, registered_count = rows; a repeat of a winner stays idempotent")
    void capacityHoldsUnderContention() throws Exception {
        User owner = newUser("owner");
        CreateClassroomRequest req = new CreateClassroomRequest();
        req.setTitle("Lop suc chua");
        req.setSlug("cap-" + UUID.randomUUID().toString().substring(0, 12));
        String classId = classroomService.createClassroom(owner.getId(), req).getId();
        List<User> members = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            User u = newUser("m" + i);
            memberRepository.save(new ClassMember(classId, u.getId(), "STUDENT"));
            members.add(u);
        }
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        ClassEventDto event = eventService.create(classId, owner.getId(), new CreateEventRequest("Workshop 5 cho", null, null, null,
                "ONLINE", "Zoom", "https://zoom.us/j/1", start, start.plus(1, ChronoUnit.HOURS), 5, null, null, "PUBLIC"));

        List<Callable<ClassEventDto>> jobs = new ArrayList<>();
        for (User u : members) jobs.add(() -> eventService.register(event.id(), u.getId()));
        List<Object> results = runTogether(jobs);

        int ok = 0;
        int full = 0;
        for (Object r : results) {
            if (r instanceof ClassEventDto) {
                ok++;
            } else if (r instanceof AppException e && e.getErrorCode() == ErrorCode.CONFLICT) {
                assertEquals(EventService.FULL_MESSAGE, e.getMessage());
                full++;
            } else {
                fail("unexpected outcome: " + r);
            }
        }
        assertEquals(5, ok);
        assertEquals(7, full);
        assertEquals(5, registrationRepository.countByEventId(event.id()));
        assertEquals(5, eventRepository.findById(event.id()).orElseThrow().getRegisteredCount());
        assertEquals(5, jdbc.queryForObject("SELECT registered_count FROM class_events WHERE id = ?", Integer.class, event.id()));

        // the same winner registering again (concurrently with itself) changes nothing
        String winner = jdbc.queryForObject("SELECT user_id FROM class_event_registrations WHERE event_id = ? LIMIT 1", String.class, event.id());
        List<Callable<ClassEventDto>> repeats = new ArrayList<>();
        for (int i = 0; i < 4; i++) repeats.add(() -> eventService.register(event.id(), winner));
        for (Object r : runTogether(repeats)) assertInstanceOf(ClassEventDto.class, r, String.valueOf(r));
        assertEquals(5, registrationRepository.countByEventId(event.id()));
    }

    @Test
    @DisplayName("V44 on MySQL: DATETIME(6) instants, the unique (event_id, user_id) key and the cascading foreign keys")
    void migrationShape() {
        assertEquals(6, jdbc.queryForObject("SELECT datetime_precision FROM information_schema.columns WHERE table_schema = DATABASE()"
                + " AND table_name = 'class_events' AND column_name = 'starts_at'", Integer.class));
        assertEquals(6, jdbc.queryForObject("SELECT datetime_precision FROM information_schema.columns WHERE table_schema = DATABASE()"
                + " AND table_name = 'blog_posts' AND column_name = 'published_at'", Integer.class));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics"
                + " WHERE table_schema = DATABASE() AND table_name = 'class_event_registrations' AND index_name = 'uk_class_event_registration'"
                + " AND non_unique = 0", Integer.class));
        assertEquals("CASCADE", jdbc.queryForObject("SELECT delete_rule FROM information_schema.referential_constraints"
                + " WHERE constraint_schema = DATABASE() AND constraint_name = 'fk_event_registration_event'", String.class));
        assertEquals("SET NULL", jdbc.queryForObject("SELECT delete_rule FROM information_schema.referential_constraints"
                + " WHERE constraint_schema = DATABASE() AND constraint_name = 'fk_class_cover_media'", String.class));
    }
}
