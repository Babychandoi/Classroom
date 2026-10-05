package com.classroom.modules.learning.service;

import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.service.ClassroomService;
import com.classroom.modules.learning.dto.MyCourseDto;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.policy.LearningPolicy;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.repository.LessonProgressRepository;
import com.classroom.modules.learning.repository.LessonRepository;
import com.classroom.modules.media.service.MediaService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D-30: GET /me/courses. The candidates are the PUBLISHED courses of classes the caller currently belongs to (one query, D-19 membership
 * and D-29 suspension rules in SQL); {@link LearningPolicy#canLearn} then decides each one that needs it. A FREE course of a class the
 * caller belongs to is always learnable by canLearn's own rules (owner / member), so only purchase-required courses of classes the caller
 * does not own call it (entitlement lookup). Progress comes from grouped queries - activity for every candidate (it drives the order),
 * counts and the resume lesson only for the requested page - so the cost does not grow with the page.
 */
@Service
public class MyCoursesService {

    private final CourseRepository courseRepository;
    private final ClassroomRepository classroomRepository;
    private final LessonRepository lessonRepository;
    private final LessonProgressRepository progressRepository;
    private final LearningPolicy learningPolicy;
    private final MediaService mediaService;

    public MyCoursesService(CourseRepository courseRepository, ClassroomRepository classroomRepository,
                            LessonRepository lessonRepository, LessonProgressRepository progressRepository,
                            LearningPolicy learningPolicy, @Lazy MediaService mediaService) {
        this.courseRepository = courseRepository;
        this.classroomRepository = classroomRepository;
        this.lessonRepository = lessonRepository;
        this.progressRepository = progressRepository;
        this.learningPolicy = learningPolicy;
        this.mediaService = mediaService;
    }

    @Transactional(readOnly = true)
    public List<MyCourseDto> listMine(String userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        List<Course> candidates = courseRepository.findLearnerCandidates(userId, Instant.now());
        if (candidates.isEmpty()) return List.of();

        Map<String, Classroom> classes = new HashMap<>();
        classroomRepository.findAllById(candidates.stream().map(Course::getClassId).distinct().toList())
                .forEach(c -> classes.put(c.getId(), c));

        List<Course> learnable = new ArrayList<>(candidates.size());
        for (Course course : candidates) {
            Classroom classroom = classes.get(course.getClassId());
            if (classroom == null) continue;
            boolean owner = userId.equals(classroom.getOwnerId());
            boolean free = "FREE".equalsIgnoreCase(course.getAccessMode());
            if (owner || free || learningPolicy.canLearn(userId, course)) {
                learnable.add(course);
            }
        }
        if (learnable.isEmpty()) return List.of();

        List<String> allIds = learnable.stream().map(Course::getId).toList();
        Map<String, Instant> lastActivity = new HashMap<>();
        Set<String> hasRow = new HashSet<>();
        progressRepository.activityByUserAndCourseIdIn(userId, allIds).forEach(a -> {
            hasRow.add(a.getCourseId());
            lastActivity.put(a.getCourseId(), a.getLastAt());
        });

        // Started first (latest activity first); the rest keep the repository order (class title, then course position).
        List<Course> ordered = new ArrayList<>(learnable);
        List<Course> started = new ArrayList<>(ordered.stream().filter(c -> hasRow.contains(c.getId())).toList());
        started.sort(Comparator.comparing((Course c) -> lastActivity.get(c.getId()),
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<Course> rest = ordered.stream().filter(c -> !hasRow.contains(c.getId())).toList();
        List<Course> all = new ArrayList<>(started);
        all.addAll(rest);

        long from = (long) safePage * safeSize;
        if (from >= all.size()) return List.of();
        List<Course> slice = all.subList((int) from, (int) Math.min(all.size(), from + safeSize));
        List<String> ids = slice.stream().map(Course::getId).toList();

        Map<String, Long> totals = new HashMap<>();
        lessonRepository.countVisibleByCourseIdIn(ids).forEach(r -> totals.put(r.getCourseId(), r.getTotal()));
        Map<String, Long> completed = new HashMap<>();
        progressRepository.countCompletedVisibleByUserAndCourseIdIn(userId, ids)
                .forEach(r -> completed.put(r.getCourseId(), r.getTotal()));
        Set<String> doneLessons = new HashSet<>(progressRepository.findCompletedLessonIds(userId, ids));
        Map<String, String> next = new HashMap<>();
        for (LessonRepository.CurriculumLesson l : lessonRepository.findVisibleCurriculum(ids)) {
            if (!next.containsKey(l.getCourseId()) && !doneLessons.contains(l.getLessonId())) {
                next.put(l.getCourseId(), l.getLessonId());
            }
        }

        List<String> avatarIds = slice.stream().map(c -> classes.get(c.getClassId())).filter(java.util.Objects::nonNull)
                .map(Classroom::getAvatarMediaId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<String, String> avatarUrls = avatarIds.isEmpty() ? Map.of()
                : mediaService.presignedImageUrls(avatarIds, ClassroomService.AVATAR_MEDIA_PURPOSE);

        List<MyCourseDto> result = new ArrayList<>(slice.size());
        for (Course c : slice) {
            Classroom k = classes.get(c.getClassId());
            long total = totals.getOrDefault(c.getId(), 0L);
            long done = Math.min(completed.getOrDefault(c.getId(), 0L), total);
            int percent = total > 0 ? (int) Math.round(done * 100.0 / total) : 0;
            result.add(new MyCourseDto(c.getId(), c.getClassId(),
                    k == null ? null : k.getTitle(), k == null ? null : k.getSlug(),
                    k == null || k.getAvatarMediaId() == null ? null : avatarUrls.get(k.getAvatarMediaId()),
                    c.getTitle(), c.getDescription(), c.getCoverImageUrl(), c.getAccessMode(),
                    (int) total, (int) done, percent, lastActivity.get(c.getId()), next.get(c.getId()),
                    done > 0 || hasRow.contains(c.getId())));
        }
        return result;
    }
}
