package ru.practicum.ewm;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(EwmMainApplicationTest.ErrorProbeController.class)
class EwmMainApplicationTest {

    @Container
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16.1");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Test
    void startsApplicationWithHealthyDatabase() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/actuator/health", JsonNode.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().path("status").asText());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT 1", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":\" \"}"})
    void returnsApiErrorForInvalidFields(String json) {
        ResponseEntity<JsonNode> response = postJson(json);

        assertApiError(response, HttpStatus.BAD_REQUEST);
        assertTrue(response.getBody().path("errors").get(0).asText().contains("name"));
    }

    @Test
    void returnsApiErrorForMalformedJson() {
        assertApiError(postJson("{"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void returnsApiErrorForUnsupportedContentType() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);

        assertApiError(restTemplate.postForEntity("/test/errors", new HttpEntity<>("name", headers), JsonNode.class),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void returnsApiErrorForUnknownEndpoint() {
        assertApiError(restTemplate.getForEntity("/does-not-exist", JsonNode.class), HttpStatus.NOT_FOUND);
    }

    @Test
    void preservesAllowedMethodsHeaderInApiError() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/test/errors", JsonNode.class);

        assertApiError(response, HttpStatus.METHOD_NOT_ALLOWED);
        assertTrue(response.getHeaders().getAllow().contains(org.springframework.http.HttpMethod.POST));
    }

    @Test
    void returnsApiErrorForMissingParameter() {
        assertApiError(restTemplate.getForEntity("/test/errors/required", JsonNode.class), HttpStatus.BAD_REQUEST);
    }

    @Test
    void returnsApiErrorForInvalidParameterType() {
        assertApiError(restTemplate.getForEntity("/test/errors/required?id=word", JsonNode.class), HttpStatus.BAD_REQUEST);
    }

    @Test
    void hidesInternalExceptionDetails() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/test/errors/failure", JsonNode.class);

        assertApiError(response, HttpStatus.INTERNAL_SERVER_ERROR);
        assertFalse(response.getBody().toString().contains("internal-sensitive-detail"));
        assertTrue(response.getBody().path("errors").isEmpty());
    }

    private ResponseEntity<JsonNode> postJson(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/test/errors", new HttpEntity<>(json, headers), JsonNode.class);
    }

    private void assertApiError(ResponseEntity<JsonNode> response, HttpStatus status) {
        assertEquals(status, response.getStatusCode());
        JsonNode error = response.getBody();
        assertNotNull(error);
        assertEquals(status.name(), error.path("status").asText());
        assertFalse(error.path("message").asText().isBlank());
        assertFalse(error.path("reason").asText().isBlank());
        assertTrue(error.path("errors").isArray());
        LocalDateTime.parse(error.path("timestamp").asText(), DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"));
    }

    @RestController
    @RequestMapping("/test/errors")
    static class ErrorProbeController {

        @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
        ProbeRequest validate(@Valid @RequestBody ProbeRequest request) {
            return request;
        }

        @GetMapping("/required")
        void requireParameter(@RequestParam long id) {
        }

        @GetMapping("/failure")
        void fail() {
            throw new IllegalStateException("internal-sensitive-detail");
        }
    }

    record ProbeRequest(@NotBlank String name) {
    }
}
