package com.classroom.integration;

import com.classroom.config.JwtTokenProvider;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.media.model.MediaAsset;
import com.classroom.modules.media.repository.MediaAssetRepository;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * R2-01: /media/{id}/download streams its response body via StreamingResponseBody, which Spring
 * MVC completes on an ASYNC servlet dispatch. JwtAuthenticationFilter is a OncePerRequestFilter
 * and never runs again on that dispatch, so the stateless SecurityContext is empty by the time
 * SecurityConfig's anyRequest().authenticated() rule evaluates it. Before the SecurityConfig fix,
 * that threw AuthorizationDeniedException after the response was already committed, truncating
 * every download in a real servlet container (MockMvc alone cannot reproduce this: it does not
 * dispatch async completions through the real filter chain the way an embedded container does).
 * This test therefore boots a real embedded server (RANDOM_PORT) and downloads a full file
 * end-to-end through it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MediaDownloadAsyncDispatchIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ClassroomRepository classroomRepository;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @MockBean(name = "minioClient")
    private MinioClient minioClient;

    @MockBean(name = "minioPresigningClient")
    private MinioClient minioPresigningClient;

    private String ownerToken;
    private MediaAsset asset;
    private byte[] fileBytes;

    @BeforeEach
    void setUp() throws Exception {
        Classroom classroom = classroomRepository.findBySlug("lop-toan-nang-cao").orElseThrow();
        User owner = userRepository.findById(classroom.getOwnerId()).orElseThrow();
        ownerToken = tokenProvider.generateToken(owner.getId(), owner.getEmail(), owner.getRole());

        // A payload larger than a single network buffer/chunk, to make truncation observable.
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            body.append("line-").append(i).append('-').append(UUID.randomUUID()).append('\n');
        }
        fileBytes = body.toString().getBytes(StandardCharsets.UTF_8);

        asset = new MediaAsset(classroom.getId(), owner.getId(),
                "classes/" + classroom.getId() + "/media/report.txt",
                "report.txt", "text/plain", fileBytes.length);
        asset.setStatus("UPLOADED");
        asset = mediaAssetRepository.save(asset);

        when(minioClient.getObject(any(GetObjectArgs.class))).thenAnswer(invocation -> new GetObjectResponse(
                new Headers.Builder().build(),
                "classroom-media",
                "us-east-1",
                asset.getObjectKey(),
                new ByteArrayInputStream(fileBytes)));
    }

    @Test
    @DisplayName("R2-01: authenticated download completes fully through the real servlet container's async dispatch")
    void downloadCompletesFullyThroughRealContainer() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + ownerToken);

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/media/" + asset.getId() + "/download",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(fileBytes, response.getBody(),
                "download must not be truncated by an unauthorized ASYNC dispatch");
    }
}
