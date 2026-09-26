package com.classroom.modules.exam.repository;

import com.classroom.modules.exam.model.AttemptAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AttemptAnswerRepository extends JpaRepository<AttemptAnswer, String> {
    List<AttemptAnswer> findByAttemptId(String attemptId);
    Optional<AttemptAnswer> findByAttemptIdAndQuestionId(String attemptId, String questionId);
}
