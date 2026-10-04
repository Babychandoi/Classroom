package com.classroom.modules.event.repository;

import com.classroom.modules.event.model.EventRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface EventRegistrationRepository extends JpaRepository<EventRegistration, String> {

    Optional<EventRegistration> findByEventIdAndUserId(String eventId, String userId);

    boolean existsByEventIdAndUserId(String eventId, String userId);

    List<EventRegistration> findByEventIdOrderByRegisteredAtAsc(String eventId);

    long countByEventId(String eventId);

    /** D-27: which of a page of events the viewer is registered for - one query for a whole listing. */
    @Query("SELECT r.eventId FROM EventRegistration r WHERE r.userId = :userId AND r.eventId IN :eventIds")
    List<String> findEventIdsRegisteredBy(@Param("userId") String userId, @Param("eventIds") Collection<String> eventIds);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("DELETE FROM EventRegistration r WHERE r.eventId = :eventId")
    int deleteByEventId(@Param("eventId") String eventId);
}
