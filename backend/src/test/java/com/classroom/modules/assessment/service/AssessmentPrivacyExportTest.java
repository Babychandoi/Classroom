package com.classroom.modules.assessment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AssessmentPrivacyExportTest {
    private JdbcTemplate jdbc;
    private AssessmentPrivacyExport export;
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:privacy-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", ""));
        export = new AssessmentPrivacyExport(jdbc);
    }
    private void schema() {
        jdbc.execute("CREATE TABLE assessment_assignments(id VARCHAR PRIMARY KEY,result_visibility VARCHAR,due_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE assessment_attempts(id VARCHAR PRIMARY KEY,assignment_id VARCHAR,class_id VARCHAR,user_id VARCHAR,attempt_no INT,status VARCHAR,started_at TIMESTAMP,deadline_at TIMESTAMP,submitted_at TIMESTAMP,late BOOLEAN,max_score DECIMAL,total_score DECIMAL,teacher_comment VARCHAR)");
        jdbc.execute("CREATE TABLE assessment_attempt_answers(id VARCHAR PRIMARY KEY,attempt_id VARCHAR,item_id VARCHAR,response_json VARCHAR,recording_asset_id VARCHAR,updated_at TIMESTAMP,final_points DECIMAL,teacher_comment VARCHAR)");
        jdbc.execute("CREATE TABLE assessment_attempt_uploads(asset_id VARCHAR,attempt_id VARCHAR,item_id VARCHAR,duration_ms BIGINT,completed BOOLEAN,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE media_assets(id VARCHAR,original_filename VARCHAR,mime_type VARCHAR,size_bytes BIGINT)");
    }
    @Test void legacySchemaExportsEmptyGroups() {
        Map<String,Object> copy = new HashMap<>();
        assertFalse(export.append(copy,"student",0));
        assertEquals(List.of(),copy.get("assessmentAttempts"));
        assertEquals(List.of(),copy.get("assessmentAnswers"));
        assertEquals(List.of(),copy.get("assessmentUploads"));
    }
    @SuppressWarnings("unchecked")
    @Test void privateWorkAndUnpublishedGradesRemainSeparated() {
        schema();
        jdbc.update("INSERT INTO assessment_assignments VALUES('s','MANUAL',NULL)");
        for (String user : List.of("student","other")) {
            jdbc.update("INSERT INTO assessment_attempts(id,assignment_id,user_id,status,total_score,teacher_comment) VALUES(?,'s',?,'GRADED',2,'private grade')",user,user);
            jdbc.update("INSERT INTO assessment_attempt_answers(id,attempt_id,item_id,response_json,final_points,teacher_comment) VALUES(?,?,?,'own work',2,'private grade')",user,user,"q1");
        }
        jdbc.update("INSERT INTO media_assets VALUES('a','voice.webm','audio/webm',100)");
        jdbc.update("INSERT INTO assessment_attempt_uploads VALUES('a','student','q1',1000,TRUE,CURRENT_TIMESTAMP)");
        Map<String,Object> copy = new HashMap<>();
        assertFalse(export.append(copy,"student",0));
        var attempts=(List<Map<String,Object>>)copy.get("assessmentAttempts");
        var answers=(List<Map<String,Object>>)copy.get("assessmentAnswers");
        assertEquals(1,attempts.size()); assertEquals("student",attempts.get(0).get("ID"));
        assertNull(attempts.get(0).get("TOTAL_SCORE")); assertNull(answers.get(0).get("TEACHER_COMMENT"));
        assertEquals("own work",answers.get(0).get("RESPONSE_JSON"));
        assertEquals(1,((List<?>)copy.get("assessmentUploads")).size());
        jdbc.update("UPDATE assessment_attempts SET status='RETURNED' WHERE id='student'");
        export.append(copy,"student",0);
        assertNotNull(((List<Map<String,Object>>)copy.get("assessmentAttempts")).get(0).get("TOTAL_SCORE"));
        jdbc.update("UPDATE assessment_attempts SET status='IN_PROGRESS' WHERE id='student'");
        jdbc.update("UPDATE assessment_assignments SET result_visibility='IMMEDIATE'");
        export.append(copy,"student",0);
        assertNull(((List<Map<String,Object>>)copy.get("assessmentAnswers")).get(0).get("FINAL_POINTS"));
        jdbc.update("UPDATE assessment_attempts SET status='GRADED' WHERE id='student'");
        jdbc.update("UPDATE assessment_assignments SET result_visibility='AFTER_DUE',due_at=DATEADD('DAY',1,CURRENT_TIMESTAMP)");
        export.append(copy,"student",0);
        assertNull(((List<Map<String,Object>>)copy.get("assessmentAnswers")).get(0).get("FINAL_POINTS"));
        jdbc.update("UPDATE assessment_assignments SET due_at=DATEADD('DAY',-1,CURRENT_TIMESTAMP)");
        export.append(copy,"student",0);
        assertNotNull(((List<Map<String,Object>>)copy.get("assessmentAnswers")).get(0).get("FINAL_POINTS"));
        assertFalse(copy.toString().contains("grading_json"));
    }
}
