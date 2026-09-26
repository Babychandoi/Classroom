package com.classroom.modules.projection.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface LearningEventRepository extends MongoRepository<LearningEventDocument, String> {
    Optional<LearningEventDocument> findByEventId(String eventId);
}
