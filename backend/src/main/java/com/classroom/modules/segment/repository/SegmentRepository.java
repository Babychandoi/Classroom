package com.classroom.modules.segment.repository;

import com.classroom.modules.segment.model.Segment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SegmentRepository extends JpaRepository<Segment, String> {
    List<Segment> findByClassId(String classId);
}
