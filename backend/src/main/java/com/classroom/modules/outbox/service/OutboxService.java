package com.classroom.modules.outbox.service;

import com.classroom.modules.outbox.model.OutboxEvent;
import com.classroom.modules.outbox.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Enqueue an event inside the caller's active database transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEvent(String aggregateType, String aggregateId, String eventType, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize outbox event payload", e);
        }

        OutboxEvent event = new OutboxEvent(aggregateType, aggregateId, eventType, json);
        outboxEventRepository.save(event);
    }

    /**
     * Idempotently enqueue an event: only saves if no event with the same aggregate and event type exists.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEventIfNotExists(String aggregateType, String aggregateId, String eventType, Object payload) {
        if (outboxEventRepository.existsByAggregateTypeAndAggregateIdAndEventType(aggregateType, aggregateId, eventType)) {
            return;
        }
        recordEvent(aggregateType, aggregateId, eventType, payload);
    }
}
