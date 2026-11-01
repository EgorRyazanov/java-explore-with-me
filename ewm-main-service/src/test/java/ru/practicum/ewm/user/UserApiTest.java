package ru.practicum.ewm.user;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
class UserApiTest {

    @Container
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16.1");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @BeforeEach
    void clearUsers() {
        jdbcTemplate.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
    }

    @Test
    void createsUserAndPersistsExactFields() {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/admin/users",
                Map.of("id", 42L, "name", "Egor", "email", "egor@example.org"), JsonNode.class);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        JsonNode user = response.getBody();
        assertNotNull(user);
        assertEquals(3, user.size());
        assertTrue(user.path("id").asLong() > 0);
        assertNotEquals(42L, user.path("id").asLong());
        assertEquals("Egor", user.path("name").asText());
        assertEquals("egor@example.org", user.path("email").asText());

        Map<String, Object> saved = jdbcTemplate.queryForMap("SELECT id, name, email FROM users");
        assertEquals(user.path("id").asLong(), ((Number) saved.get("id")).longValue());
        assertEquals("Egor", saved.get("name"));
        assertEquals("egor@example.org", saved.get("email"));
    }

    @ParameterizedTest
    @MethodSource("validBoundaryUsers")
    void acceptsFieldLengthBoundaries(String name, String email) {
        ResponseEntity<JsonNode> response = createUser(name, email);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(name, response.getBody().path("name").asText());
        assertEquals(email, response.getBody().path("email").asText());
        assertEquals(email, jdbcTemplate.queryForObject("SELECT email FROM users", String.class));
    }

    static Stream<Arguments> validBoundaryUsers() {
        return Stream.of(Arguments.of("Ab", "a@b.co"), Arguments.of("x".repeat(250), maxLengthEmail()));
    }

    @ParameterizedTest
    @MethodSource("invalidUsers")
    void rejectsInvalidUserWithoutSaving(String name, String email) {
        ResponseEntity<JsonNode> response = createUser(name, email);

        assertApiError(response, HttpStatus.BAD_REQUEST);
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class));
    }

    static Stream<Arguments> invalidUsers() {
        return Stream.of(
                Arguments.of(null, "a@b.co"),
                Arguments.of("", "a@b.co"),
                Arguments.of(" ", "a@b.co"),
                Arguments.of("A", "a@b.co"),
                Arguments.of("x".repeat(251), "a@b.co"),
                Arguments.of("Valid", null),
                Arguments.of("Valid", ""),
                Arguments.of("Valid", " "),
                Arguments.of("Valid", "abc"),
                Arguments.of("Valid", "invalid-email"),
                Arguments.of("Valid", "a".repeat(65) + "@example.org"),
                Arguments.of("Valid", maxLengthEmail() + "a")
        );
    }

    @Test
    void rejectsDuplicateEmailAtApiAndDatabaseLevels() {
        addUser("First", "same@example.org");

        assertApiError(createUser("Second", "same@example.org"), HttpStatus.CONFLICT);
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO users (name, email) VALUES (?, ?)", "Direct insert", "same@example.org"));
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class));
    }

    @Test
    void listsUsersUsingExactOffsetAndSingleQuery() {
        addUser("First", "first@example.org");
        long second = addUser("Second", "second@example.org");
        long third = addUser("Third", "third@example.org");
        long before = statementCount();

        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/admin/users?from=1&size=2", JsonNode.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertEquals(second, response.getBody().get(0).path("id").asLong());
        assertEquals(third, response.getBody().get(1).path("id").asLong());
        assertEquals(1L, statementCount() - before);
    }

    @Test
    void filtersIdsBeforePaginationWithoutDuplicateResults() {
        long first = addUser("First", "first@example.org");
        addUser("Second", "second@example.org");
        long third = addUser("Third", "third@example.org");
        long fourth = addUser("Fourth", "fourth@example.org");

        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                "/admin/users?ids={a}&ids={b}&ids={c}&ids={d}&ids=9999&from=1&size=2",
                JsonNode.class, fourth, first, third, fourth);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertEquals(third, response.getBody().get(0).path("id").asLong());
        assertEquals(fourth, response.getBody().get(1).path("id").asLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin/users", "/admin/users?ids="})
    void appliesDefaultPageToAllUsers(String url) {
        for (int index = 0; index < 12; index++) {
            addUser("User " + index, "user" + index + "@example.org");
        }

        ResponseEntity<JsonNode> response = restTemplate.getForEntity(url, JsonNode.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(10, response.getBody().size());
        assertEquals(1L, response.getBody().get(0).path("id").asLong());
        assertEquals(10L, response.getBody().get(9).path("id").asLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin/users?ids=9999", "/admin/users?from=20"})
    void returnsEmptyArrayWhenNoUsersMatch(String url) {
        addUser("First", "first@example.org");

        ResponseEntity<JsonNode> response = restTemplate.getForEntity(url, JsonNode.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isArray());
        assertTrue(response.getBody().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"?from=-1", "?size=0", "?size=-1", "?from=word", "?size=word", "?ids=word",
            "?from=2147483648", "?size=2147483648"})
    void rejectsInvalidListParameters(String query) {
        assertApiError(restTemplate.getForEntity("/admin/users" + query, JsonNode.class), HttpStatus.BAD_REQUEST);
    }

    @Test
    void deletesUserWithSingleQueryAndEmptyResponse() {
        long id = addUser("First", "first@example.org");
        long before = statementCount();

        ResponseEntity<String> response = restTemplate.exchange("/admin/users/{id}", HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class, id);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertEquals(null, response.getBody());
        assertEquals(1L, statementCount() - before);
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 9999})
    void returnsNotFoundForUnknownUser(long id) {
        ResponseEntity<JsonNode> response = restTemplate.exchange("/admin/users/{id}", HttpMethod.DELETE,
                HttpEntity.EMPTY, JsonNode.class, id);

        assertApiError(response, HttpStatus.NOT_FOUND);
    }

    @Test
    void allowsReusingEmailAfterDeletion() {
        long id = addUser("First", "same@example.org");
        assertEquals(HttpStatus.NO_CONTENT, restTemplate.exchange("/admin/users/{id}", HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class, id).getStatusCode());

        assertEquals(HttpStatus.CREATED, createUser("Second", "same@example.org").getStatusCode());
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{}"})
    void rejectsMissingOrMalformedBody(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/admin/users",
                new HttpEntity<>(json, headers), JsonNode.class);

        assertApiError(response, HttpStatus.BAD_REQUEST);
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class));
    }

    private ResponseEntity<JsonNode> createUser(String name, String email) {
        Map<String, String> request = new HashMap<>();
        request.put("name", name);
        request.put("email", email);
        return restTemplate.postForEntity("/admin/users", request, JsonNode.class);
    }

    private long addUser(String name, String email) {
        ResponseEntity<JsonNode> response = createUser(name, email);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        return response.getBody().path("id").asLong();
    }

    private long statementCount() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getPrepareStatementCount();
    }

    private void assertApiError(ResponseEntity<JsonNode> response, HttpStatus status) {
        assertEquals(status, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(status.name(), response.getBody().path("status").asText());
        assertTrue(response.getBody().path("errors").isArray());
        assertTrue(response.getBody().path("message").isTextual());
        assertTrue(response.getBody().path("reason").isTextual());
        assertTrue(response.getBody().path("timestamp").isTextual());
    }

    private static String maxLengthEmail() {
        return "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
    }
}
