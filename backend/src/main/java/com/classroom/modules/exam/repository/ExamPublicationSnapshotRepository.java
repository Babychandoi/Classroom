package com.classroom.modules.exam.repository;
import com.classroom.modules.exam.model.ExamPublicationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExamPublicationSnapshotRepository extends JpaRepository<ExamPublicationSnapshot, String> {}
