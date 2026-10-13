package com.classroom.modules.assessment.policy;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.policy.AccessPolicy;
import org.springframework.stereotype.Component;

/** Class visibility, current membership and the separate assessment grants apply to every endpoint. */
@Component
public class AssessmentPolicy {
    private final AccessPolicy access;
    public AssessmentPolicy(AccessPolicy access) { this.access = access; }
    public boolean can(String userId, String classId, String action) {
        return access.canManage(userId, classId, "ASSESSMENT", action, null);
    }
    public void require(String userId, String classId, String action) {
        access.requireVisibleClass(classId, userId, "Không tìm thấy lớp học");
        access.enforceManage(userId, classId, "ASSESSMENT", action, null);
    }
    public void requireRead(String userId, String classId, String action) {
        access.requireVisibleClass(classId, userId, "Không tìm thấy lớp học");
        access.enforceManageRead(userId, classId, "ASSESSMENT", action, null);
    }
    public void requireAnyStudioGrant(String userId, String classId) {
        access.requireVisibleClass(classId, userId, "Không tìm thấy lớp học");
        if (!access.canManageAny(userId, classId, "ASSESSMENT", "VIEW", "CREATE", "EDIT", "PUBLISH", "GRADE", "DELETE"))
            throw new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "Bạn không có quyền quản lý bài tập của lớp");
    }
    public void requireAuthorRead(String userId, String classId) {
        access.requireVisibleClass(classId, userId, "Không tìm thấy đề");
        if (!access.canManageAny(userId, classId, "ASSESSMENT", "CREATE", "EDIT", "PUBLISH"))
            throw new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "Bạn không có quyền đọc nội dung biên soạn");
    }
    public void requireStudent(String userId, String classId) { access.enforceMember(userId, classId); }
    public void requireStudentWrite(String userId, String classId, boolean starting) {
        requireStudent(userId, classId);
        access.enforceNotSuspended(classId);
        if (starting && access.isClassFrozen(classId))
            throw new AppException(ErrorCode.CONFLICT, "Lớp đã lưu trữ, không thể bắt đầu bài mới");
    }
}
