package com.classroom.modules.blog.service;

import com.classroom.modules.blog.model.BlogPost;
import com.classroom.modules.blog.repository.BlogPostRepository;
import com.classroom.modules.classroom.dto.CreateClassroomRequest;
import com.classroom.modules.classroom.service.ClassroomService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-27 review: a blog post carries an optimistic-lock version (V45). Two writers that read the same version cannot both save - the second
 * one fails with OptimisticLockingFailureException, which GlobalExceptionHandler answers with 409 (so an edit can never silently undo a
 * concurrent publish / unpublish, or the other way round).
 */
@SpringBootTest
@ActiveProfiles("test")
class BlogPostOptimisticLockTest {

    @Autowired private BlogPostRepository repository;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassroomService classroomService;

    @Test
    @DisplayName("a new post starts at version 0; a stale copy cannot overwrite a newer save")
    void staleWriteIsRejected() {
        User owner = userRepository.save(new User(UUID.randomUUID().toString(), "lock-" + UUID.randomUUID() + "@d27.test", "h", "Lock", "USER"));
        CreateClassroomRequest req = new CreateClassroomRequest();
        req.setTitle("Lop khoa lac quan");
        req.setSlug("lock-" + UUID.randomUUID().toString().substring(0, 10));
        String classId = classroomService.createClassroom(owner.getId(), req).getId();

        BlogPost post = new BlogPost();
        post.setClassId(classId);
        post.setAuthorId(owner.getId());
        post.setTitle("Ban dau");
        post.setContentMarkdown("noi dung");
        BlogPost saved = repository.saveAndFlush(post);
        assertEquals(0L, saved.getVersion());

        BlogPost editorA = repository.findById(saved.getId()).orElseThrow();
        BlogPost editorB = repository.findById(saved.getId()).orElseThrow();
        editorA.setStatus(BlogPost.STATUS_PUBLISHED);
        assertEquals(1L, repository.saveAndFlush(editorA).getVersion());

        editorB.setTitle("Sua tren ban cu");
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> repository.saveAndFlush(editorB));
        BlogPost current = repository.findById(saved.getId()).orElseThrow();
        assertEquals(BlogPost.STATUS_PUBLISHED, current.getStatus(), "the publish was not undone");
        assertEquals("Ban dau", current.getTitle());
    }
}
