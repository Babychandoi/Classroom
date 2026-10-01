package com.classroom.integration;

import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.service.ClassAccessTestBase;
import com.classroom.modules.identity.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-19: the real wiring. The integration profile switches the sweeper timer off so other tests are not raced; this test turns it on with the
 * configuration properties an operator would set ({@code classroom.membership.expiry.*}) and proves that, in the running application, a member whose
 * paid access ran out is moved to EXPIRED - with its MEMBER_EXPIRED event - by the sweeper's OWN thread, never the shared scheduler's.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "classroom.membership.expiry.enabled=true",
        "classroom.membership.expiry.interval-seconds=1",
        "classroom.membership.expiry.batch-size=50"})
@ActiveProfiles("integration")
class MembershipExpiryTimerIntegrationTest extends ClassAccessTestBase {

    @Test
    @DisplayName("with the timer enabled, a lapsed member becomes EXPIRED within seconds, exactly once, on the membership-expiry thread")
    void timerExpiresLapsedMembers() throws Exception {
        User owner = newUser("owner");
        User lapsed = newUser("lapsed");
        User running = newUser("running");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        addRow(c, lapsed, "STUDENT", "ACTIVE", Instant.now().minusSeconds(2));
        addRow(c, running, "STUDENT", "ACTIVE", Instant.now().plusSeconds(3600));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!"EXPIRED".equals(row(c, lapsed).getState()) && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }

        assertEquals("EXPIRED", row(c, lapsed).getState());
        assertEquals("ACTIVE", row(c, running).getState());
        Thread.sleep(2500); // further runs change nothing and emit nothing more
        assertEquals(1, events(c, "MEMBER_EXPIRED").stream().filter(e -> e.getPayloadJson().contains(lapsed.getId())).count());
        Set<String> threads = Thread.getAllStackTraces().keySet().stream().map(Thread::getName).collect(Collectors.toSet());
        assertTrue(threads.stream().anyMatch(n -> n.startsWith("membership-expiry-")), "its own thread");
    }
}
