package ru.practicum.ewm.category;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
class CategoryApiTest {

    @Container
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16.1");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeAll
    static void enablePatchRequests(@Autowired TestRestTemplate template) {
        template.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @BeforeEach
    void clearCategories() {
        jdbcTemplate.execute("TRUNCATE TABLE categories RESTART IDENTITY CASCADE");
    }

    @Test
    void createsCategoryAndPersistsNameWithGeneratedId() {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/admin/categories",
                Map.of("id", 42L, "name", "Концерты"), JsonNode.class);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        JsonNode category = response.getBody();
        assertNotNull(category);
        assertEquals(2, category.size());
        assertTrue(category.path("id").asLong() > 0);
        assertNotEquals(42L, category.path("id").asLong());
        assertEquals("Концерты", category.path("name").asText());

        Map<String, Object> saved = jdbcTemplate.queryForMap("SELECT id, name FROM categories");
        assertEquals(category.path("id").asLong(), ((Number) saved.get("id")).longValue());
        assertEquals("Концерты", saved.get("name"));
    }

    @ParameterizedTest
    @MethodSource("validBoundaryNames")
    void acceptsNameLengthBoundaries(String name) {
        ResponseEntity<JsonNode> response = createCategory(name);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(name, response.getBody().path("name").asText());
        assertEquals(name, jdbcTemplate.queryForObject("SELECT name FROM categories", String.class));
    }

    static Stream<String> validBoundaryNames() {
        return Stream.of("A", "x".repeat(50));
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void rejectsInvalidNameWithoutSaving(String name) {
        assertApiError(createCategory(name), HttpStatus.BAD_REQUEST);
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM categories", Long.class));
    }

    static Stream<String> invalidNames() {
        return Stream.of(null, "", " ", "x".repeat(51));
    }

    @Test
    void readsCategoryThroughPublicEndpoint() {
        long id = addCategory("Концерты");

        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/categories/{id}", JsonNode.class, id);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertEquals(id, response.getBody().path("id").asLong());
        assertEquals("Концерты", response.getBody().path("name").asText());
    }

    @Test
    void renamesCategoryUsingPathIdAndAcceptsMaximumLength() {
        long first = addCategory("First");
        long second = addCategory("Second");
        String newName = "x".repeat(50);

        ResponseEntity<JsonNode> response = restTemplate.exchange("/admin/categories/{id}", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("id", second, "name", newName)), JsonNode.class, first);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(first, response.getBody().path("id").asLong());
        assertEquals(newName, response.getBody().path("name").asText());
        assertEquals(newName, jdbcTemplate.queryForObject("SELECT name FROM categories WHERE id = ?", String.class, first));
        assertEquals("Second", jdbcTemplate.queryForObject("SELECT name FROM categories WHERE id = ?", String.class, second));
    }

    @Test
    void allowsKeepingTheSameName() {
        long id = addCategory("Концерты");

        ResponseEntity<JsonNode> response = patchCategory(id, "Концерты");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(id, response.getBody().path("id").asLong());
        assertEquals("Концерты", response.getBody().path("name").asText());
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM categories", Long.class));
    }

    @Test
    void rejectsDuplicateNameAtApiAndDatabaseLevels() {
        addCategory("Концерты");

        assertApiError(createCategory("Концерты"), HttpStatus.CONFLICT);
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO categories (name) VALUES (?)", "Концерты"));
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM categories", Long.class));
    }

    @Test
    void rollsBackRenameToAnotherCategoryName() {
        long first = addCategory("First");
        addCategory("Second");

        assertApiError(patchCategory(first, "Second"), HttpStatus.CONFLICT);
        assertEquals("First", jdbcTemplate.queryForObject("SELECT name FROM categories WHERE id = ?", String.class, first));
        assertEquals(2L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM categories", Long.class));
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void rejectsInvalidRenameWithoutChangingCategory(String name) {
        long id = addCategory("Original");

        assertApiError(patchCategory(id, name), HttpStatus.BAD_REQUEST);
        assertEquals("Original", jdbcTemplate.queryForObject("SELECT name FROM categories WHERE id = ?", String.class, id));
    }

    @Test
    void listsCategoriesUsingExactOffsetAndSingleQuery() {
        addCategory("First");
        long second = addCategory("Second");
        long third = addCategory("Third");
        long before = statementCount();

        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/categories?from=1&size=2", JsonNode.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertEquals(second, response.getBody().get(0).path("id").asLong());
        assertEquals(third, response.getBody().get(1).path("id").asLong());
        assertEquals(1L, statementCount() - before);
    }

    @Test
    void appliesDefaultPageSize() {
        for (int index = 0; index < 12; index++) {
            addCategory("Category " + index);
        }

        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/categories", JsonNode.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(10, response.getBody().size());
        assertEquals(1L, response.getBody().get(0).path("id").asLong());
        assertEquals(10L, response.getBody().get(9).path("id").asLong());
    }

    @Test
    void returnsEmptyArrayWhenNoCategoriesExist() {
        assertEmptyList(restTemplate.getForEntity("/categories", JsonNode.class));
    }

    @Test
    void returnsEmptyArrayBeyondLastCategory() {
        addCategory("Only category");

        assertEmptyList(restTemplate.getForEntity("/categories?from=1", JsonNode.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"?from=-1", "?size=0", "?size=-1", "?from=word", "?size=word",
            "?from=2147483648", "?size=2147483648"})
    void rejectsInvalidPagination(String query) {
        assertApiError(restTemplate.getForEntity("/categories" + query, JsonNode.class), HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 9999})
    void returnsNotFoundForUnknownCategory(long id) {
        assertApiError(restTemplate.getForEntity("/categories/{id}", JsonNode.class, id), HttpStatus.NOT_FOUND);
        assertApiError(patchCategory(id, "Updated"), HttpStatus.NOT_FOUND);
        assertApiError(restTemplate.exchange("/admin/categories/{id}", HttpMethod.DELETE,
                HttpEntity.EMPTY, JsonNode.class, id), HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsInvalidCategoryId() {
        assertApiError(restTemplate.getForEntity("/categories/word", JsonNode.class), HttpStatus.BAD_REQUEST);
    }

    @Test
    void deletesCategoryWithSingleQueryAndEmptyResponse() {
        long id = addCategory("First");
        long remaining = addCategory("Second");
        long before = statementCount();

        ResponseEntity<String> response = restTemplate.exchange("/admin/categories/{id}", HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class, id);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
        assertEquals(1L, statementCount() - before);
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM categories", Long.class));
        assertEquals(remaining, jdbcTemplate.queryForObject("SELECT id FROM categories", Long.class));
        assertApiError(restTemplate.getForEntity("/categories/{id}", JsonNode.class, id), HttpStatus.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{}"})
    void rejectsMissingOrMalformedWriteBodies(String json) {
        long id = addCategory("Original");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> body = new HttpEntity<>(json, headers);

        assertApiError(restTemplate.postForEntity("/admin/categories", body, JsonNode.class), HttpStatus.BAD_REQUEST);
        assertApiError(restTemplate.exchange("/admin/categories/{id}", HttpMethod.PATCH,
                body, JsonNode.class, id), HttpStatus.BAD_REQUEST);
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM categories", Long.class));
        assertEquals("Original", jdbcTemplate.queryForObject("SELECT name FROM categories", String.class));
    }

    private ResponseEntity<JsonNode> createCategory(String name) {
        return restTemplate.postForEntity("/admin/categories", request(name), JsonNode.class);
    }

    private long addCategory(String name) {
        ResponseEntity<JsonNode> response = createCategory(name);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        return response.getBody().path("id").asLong();
    }

    private ResponseEntity<JsonNode> patchCategory(long id, String name) {
        return restTemplate.exchange("/admin/categories/{id}", HttpMethod.PATCH,
                new HttpEntity<>(request(name)), JsonNode.class, id);
    }

    private Map<String, String> request(String name) {
        Map<String, String> request = new HashMap<>();
        request.put("name", name);
        return request;
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
    }

    private void assertEmptyList(ResponseEntity<JsonNode> response) {
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isArray());
        assertTrue(response.getBody().isEmpty());
    }
}
