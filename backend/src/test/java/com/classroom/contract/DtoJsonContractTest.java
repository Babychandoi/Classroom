package com.classroom.contract;

import com.classroom.modules.classroom.dto.ClassroomDto;
import com.classroom.modules.exam.dto.ExamAttemptDto;
import com.classroom.modules.identity.dto.UserProfileDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the wire names of `is`-prefixed boolean DTO fields.
 *
 * Jackson derives "pro" from the getter isPro(), not "isPro", so these properties
 * silently disagreed with the TypeScript client (frontend/src/types/index.ts) and the
 * PRO badge never rendered. These assertions fail if the @JsonProperty overrides are lost.
 */
public class DtoJsonContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("ClassroomDto serializes isOwner/isMember/isPro, not owner/member/pro")
    void classroomDtoBooleanNames() throws Exception {
        ClassroomDto dto = new ClassroomDto();
        dto.setOwner(true);
        dto.setMember(true);
        dto.setPro(true);

        String json = mapper.writeValueAsString(dto);

        assertTrue(json.contains("\"isOwner\":true"), json);
        assertTrue(json.contains("\"isMember\":true"), json);
        assertTrue(json.contains("\"isPro\":true"), json);
        assertFalse(json.contains("\"pro\":"), json);
        assertFalse(json.contains("\"owner\":"), json);
        assertFalse(json.contains("\"member\":"), json);
    }

    @Test
    @DisplayName("D-27: ClassEventDto serializes isRegistered / isFull (record components), not registered / full")
    void classEventDtoBooleanNames() throws Exception {
        com.classroom.modules.event.dto.ClassEventDto dto = new com.classroom.modules.event.dto.ClassEventDto(
                "e", "c", null, null, "t", null, null, java.util.List.of(), "ONLINE", null, null, null, null, null, 0,
                true, true, new com.classroom.modules.classroom.dto.PersonSummaryDto("u", "Ten", null), null, null, "PUBLIC",
                "SCHEDULED", null);

        String json = mapper.writeValueAsString(dto);

        assertTrue(json.contains("\"isRegistered\":true"), json);
        assertTrue(json.contains("\"isFull\":true"), json);
        assertFalse(json.contains("\"registered\":"), json);
        assertFalse(json.contains("\"full\":"), json);
        assertTrue(json.contains("\"host\":{\"id\":\"u\",\"fullName\":\"Ten\""), json);
    }

    @Test
    @DisplayName("UserProfileDto serializes isPro")
    void userProfileDtoProName() throws Exception {
        UserProfileDto dto = new UserProfileDto();
        dto.setPro(true);

        String json = mapper.writeValueAsString(dto);

        assertTrue(json.contains("\"isPro\":true"), json);
        assertFalse(json.contains("\"pro\":"), json);
    }

    @Test
    @DisplayName("ExamAttemptDto serializes isPreview")
    void examAttemptDtoPreviewName() throws Exception {
        ExamAttemptDto dto = new ExamAttemptDto();
        dto.setPreview(true);

        String json = mapper.writeValueAsString(dto);

        assertTrue(json.contains("\"isPreview\":true"), json);
        assertFalse(json.contains("\"preview\":"), json);
    }

    @Test
    @DisplayName("Renamed boolean properties still round-trip through Jackson")
    void roundTrip() throws Exception {
        ClassroomDto dto = new ClassroomDto();
        dto.setPro(true);
        dto.setMember(true);

        ClassroomDto back = mapper.readValue(mapper.writeValueAsString(dto), ClassroomDto.class);

        assertTrue(back.isPro());
        assertTrue(back.isMember());
        assertFalse(back.isOwner());
    }
}
