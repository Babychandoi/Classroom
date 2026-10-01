package com.classroom.modules.learning.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.model.Product;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.commerce.repository.OrderItemRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.policy.LearningPolicy;
import com.classroom.modules.learning.repository.*;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.media.service.MediaService;
import com.classroom.modules.outbox.service.OutboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A paid course and its product can only ever be linked one way round on creation: the course id
 * is minted inside {@code createCourse}, so no pre-existing product can already name it.
 */
@ExtendWith(MockitoExtension.class)
public class CourseProductLinkTest {

    @Mock private CourseRepository courseRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private LessonRepository lessonRepository;
    @Mock private LessonProgressRepository progressRepository;
    @Mock private LessonQuestionRepository questionRepository;
    @Mock private LessonAnswerRepository answerRepository;
    @Mock private UserRepository userRepository;
    @Mock private LearningPolicy learningPolicy;
    @Mock private AccessPolicy accessPolicy;
    @Mock private MediaService mediaService;
    @Mock private OutboxService outboxService;
    @Mock private ProductRepository productRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private com.classroom.modules.classroom.repository.StaffAssignmentRepository staffAssignmentRepository;
    @Mock private com.classroom.modules.classroom.repository.StaffPermissionRepository staffPermissionRepository;

    @InjectMocks
    private LearningService learningService;

    @Test
    @DisplayName("Creating a PURCHASE_REQUIRED course adopts an unclaimed same-class product")
    void createPaidCourseAdoptsUnclaimedProduct() {
        Product product = new Product("class-1", null, "Khoa hoc Pro", "mo ta");
        when(productRepository.findByIdForUpdate(product.getId())).thenReturn(Optional.of(product));
        when(courseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Course input = new Course("class-1", "Khoa hoc Pro", "PURCHASE_REQUIRED");
        input.setProductId(product.getId());

        Course created = learningService.createCourse("class-1", input, "owner-1");

        assertEquals("PURCHASE_REQUIRED", created.getAccessMode());
        assertEquals(product.getId(), created.getProductId());
        assertEquals(created.getId(), product.getTargetCourseId());
        verify(productRepository).save(product);
    }

    @Test
    @DisplayName("Creating a PURCHASE_REQUIRED course rejects a product bound to another course")
    void createPaidCourseRejectsProductBoundElsewhere() {
        Product product = new Product("class-1", "other-course", "Khoa hoc Pro", "mo ta");
        when(productRepository.findByIdForUpdate(product.getId())).thenReturn(Optional.of(product));

        Course input = new Course("class-1", "Khoa hoc Pro", "PURCHASE_REQUIRED");
        input.setProductId(product.getId());

        AppException ex = assertThrows(AppException.class,
                () -> learningService.createCourse("class-1", input, "owner-1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(courseRepository, never()).save(any());
    }

    @Test
    @DisplayName("Creating a PURCHASE_REQUIRED course rejects a product from another class")
    void createPaidCourseRejectsCrossClassProduct() {
        Product product = new Product("class-2", null, "Khoa hoc Pro", "mo ta");
        when(productRepository.findByIdForUpdate(product.getId())).thenReturn(Optional.of(product));

        Course input = new Course("class-1", "Khoa hoc Pro", "PURCHASE_REQUIRED");
        input.setProductId(product.getId());

        AppException ex = assertThrows(AppException.class,
                () -> learningService.createCourse("class-1", input, "owner-1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(productRepository, never()).save(any());
        verify(courseRepository, never()).save(any());
    }

    @Test
    @DisplayName("linkProduct adopts an unclaimed product but never steals one bound elsewhere")
    void linkProductAdoptsUnclaimedAndRejectsBound() {
        Course course = new Course("class-1", "Khoa hoc", "FREE");
        course.setId("course-1");
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));
        when(courseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Product unclaimed = new Product("class-1", null, "SP", "mo ta");
        when(productRepository.findByIdForUpdate(unclaimed.getId())).thenReturn(Optional.of(unclaimed));

        Course linked = learningService.linkProduct("course-1", unclaimed.getId(), "owner-1");
        assertEquals("PURCHASE_REQUIRED", linked.getAccessMode());
        assertEquals("course-1", unclaimed.getTargetCourseId());

        Product bound = new Product("class-1", "other-course", "SP2", "mo ta");
        when(productRepository.findByIdForUpdate(bound.getId())).thenReturn(Optional.of(bound));
        assertThrows(AppException.class,
                () -> learningService.linkProduct("course-1", bound.getId(), "owner-1"));
    }

    @Test
    @DisplayName("A product with settled purchases cannot be repurposed for a course")
    void linkProductRejectsPreviouslySoldProduct() {
        Course course = new Course("class-1", "Khoa hoc", "FREE");
        course.setId("course-1");
        Product product = new Product("class-1", null, "SP", "mo ta");
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));
        when(productRepository.findByIdForUpdate(product.getId())).thenReturn(Optional.of(product));
        when(orderItemRepository.countOrdersByProductId(product.getId())).thenReturn(1L);

        AppException error = assertThrows(AppException.class,
                () -> learningService.linkProduct("course-1", product.getId(), "owner-1"));
        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertNull(product.getTargetCourseId());
        verify(courseRepository, never()).save(any());
    }

    @Test
    @DisplayName("Course-only staff cannot create a product association without STORE:EDIT")
    void courseOnlyStaffCannotLinkProduct() {
        Course course = new Course("class-1", "Khoa hoc", "FREE");
        course.setId("course-1");
        Product product = new Product("class-1", null, "SP", "mo ta");
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));
        lenient().doThrow(new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "no store edit"))
                .when(accessPolicy).enforceManage("staff-1", "class-1", "STORE", "EDIT", null);

        assertThrows(AppException.class, () -> learningService.linkProduct("course-1", product.getId(), "staff-1"));
        assertNull(course.getProductId());
        assertNull(product.getTargetCourseId());
        verify(courseRepository, never()).save(any());
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("Creating a paid course requires STORE:EDIT as well as COURSE:CREATE")
    void creatingPaidCourseRequiresStoreEdit() {
        Product product = new Product("class-1", null, "SP", "mo ta");
        lenient().doThrow(new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "no store edit"))
                .when(accessPolicy).enforceManage("staff-1", "class-1", "STORE", "EDIT", null);
        Course input = new Course("class-1", "Khoa hoc", "PURCHASE_REQUIRED");
        input.setProductId(product.getId());

        assertThrows(AppException.class, () -> learningService.createCourse("class-1", input, "staff-1"));
        verify(productRepository, never()).save(any());
        verify(courseRepository, never()).save(any());
    }

    @Test
    @DisplayName("A course with any existing order cannot be relinked")
    void courseWithPendingOrSettledOrdersCannotBeRelinked() {
        Course course = new Course("class-1", "Khoa hoc", "PURCHASE_REQUIRED");
        course.setId("course-1");
        course.setProductId("old-product");
        Product replacement = new Product("class-1", null, "SP", "mo ta");
        replacement.setId("replacement-product");
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));
        when(productRepository.findByIdForUpdate(replacement.getId())).thenReturn(Optional.of(replacement));
        Product oldProduct = new Product("class-1", "course-1", "old", "old");
        oldProduct.setId("old-product");
        when(productRepository.findByIdForUpdate("old-product")).thenReturn(Optional.of(oldProduct));
        when(orderItemRepository.countOrdersByProductId("old-product")).thenReturn(1L);

        assertThrows(AppException.class,
                () -> learningService.linkProduct("course-1", replacement.getId(), "owner-1"));
        verify(courseRepository, never()).save(any());
    }

    @Test
    @DisplayName("createCourse without a title is a 400 contract error, not a 500 at flush")
    void createCourseRejectsMissingTitle() {
        Course noTitle = new Course("class-1", null, "FREE");
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(AppException.class,
                () -> learningService.createCourse("class-1", noTitle, "owner-1")).getErrorCode());

        Course blankTitle = new Course("class-1", "   ", "FREE");
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(AppException.class,
                () -> learningService.createCourse("class-1", blankTitle, "owner-1")).getErrorCode());

        verify(courseRepository, never()).save(any());
    }

    @Test
    @DisplayName("createSection without a title is a 400 contract error, not a 500 at flush")
    void createSectionRejectsMissingTitle() {
        Course course = new Course("class-1", "Khoa hoc", "FREE");
        course.setId("course-1");
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));

        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(AppException.class,
                () -> learningService.createSection("course-1", null, 0, "owner-1")).getErrorCode());

        verify(sectionRepository, never()).save(any());
    }

    @Test
    @DisplayName("R18-04: a new course is appended after the class's highest position (the client sends none)")
    void createCourseAppendsAfterHighestPosition() {
        when(courseRepository.findMaxPositionByClassId("class-1")).thenReturn(4);
        when(courseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Course input = new Course("class-1", "Khoa moi", "FREE");
        input.setPosition(99); // client-supplied positions are ignored

        assertEquals(5, learningService.createCourse("class-1", input, "owner-1").getPosition());
    }

    @Test
    @DisplayName("R18-04: the first course in a class gets position 0")
    void createFirstCourseGetsPositionZero() {
        when(courseRepository.findMaxPositionByClassId("class-1")).thenReturn(-1);
        when(courseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertEquals(0, learningService.createCourse("class-1", new Course("class-1", "Dau tien", "FREE"), "owner-1").getPosition());
    }
}
