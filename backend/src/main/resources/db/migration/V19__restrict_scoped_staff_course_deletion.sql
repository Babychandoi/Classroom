-- Never widen a scoped STAFF grant to class-wide when a course is removed.
ALTER TABLE staff_permissions DROP FOREIGN KEY fk_staff_perm_course;
ALTER TABLE staff_permissions ADD CONSTRAINT fk_staff_perm_course
    FOREIGN KEY (scope_course_id) REFERENCES courses(id) ON DELETE RESTRICT;
