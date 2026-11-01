package ru.practicum.ewm.compilation;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.practicum.stats.client.StatsClient;
import ru.practicum.stats.dto.ViewStats;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@Import(CompilationApiTest.FixedTime.class)
class CompilationApiTest {

    @Container
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16.1");

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @MockBean
    private StatsClient statsClient;
    private long owner;
    private long category;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @BeforeAll
    static void enablePatchRequests(@Autowired TestRestTemplate template) {
        template.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE users, categories, compilations RESTART IDENTITY CASCADE");
        owner = post("/admin/users", Map.of("name", "Owner", "email", "owner@example.org")).path("id").asLong();
        category = post("/admin/categories", Map.of("name", "Concerts")).path("id").asLong();
    }

    @Test
    void createsEmptyCompilationWithDefaultsAndIgnoresClientId() {
        JsonNode result = compilation(Map.of("title", "Summer concerts", "id", 777));
        assertEquals(4, result.size());
        assertTrue(result.path("id").asLong() > 0);
        assertFalse(result.path("pinned").asBoolean());
        assertEquals("Summer concerts", result.path("title").asText());
        assertEquals(0, result.path("events").size());
        assertEquals(result, get("/compilations/" + result.path("id").asLong()));
        verify(statsClient, never()).getStats(any(), any(), anyList(), anyBoolean());
    }

    @Test
    void acceptsExplicitNullOrEmptyEventsOnCreation() {
        Map<String, Object> body = new HashMap<>(Map.of("title", "Null events"));
        body.put("events", null);
        body.put("pinned", null);
        assertEquals(0, compilation(body).path("events").size());
        assertEquals(0, compilation(Map.of("title", "Empty events", "events", List.of())).path("events").size());
    }

    @Test
    void createsWithDeduplicatedEventsAndSupportsSharedEvents() {
        long a = event();
        long b = event();
        JsonNode first = compilation(Map.of("title", "First", "pinned", true, "events", List.of(b, a, b)));
        assertTrue(first.path("pinned").asBoolean());
        assertEquals(List.of(a, b), ids(first.path("events")));
        assertEquals(9, first.path("events").get(0).size());
        assertEquals(2, first.path("events").get(0).path("initiator").size());
        JsonNode second = compilation(Map.of("title", "Second", "events", List.of(a)));
        assertEquals(List.of(a), ids(second.path("events")));
        assertEquals(3L, jdbcTemplate.queryForObject("SELECT count(*) FROM compilation_events", Long.class));
    }

    @Test
    void acceptsTitleLengthBoundaries() {
        assertEquals("A", compilation(Map.of("title", "A")).path("title").asText());
        assertEquals(50, compilation(Map.of("title", "x".repeat(50))).path("title").asText().length());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\n", "123456789012345678901234567890123456789012345678901"})
    void rejectsInvalidTitlesOnCreateAndPatch(String title) {
        assertStatus(HttpStatus.BAD_REQUEST, restTemplate.postForEntity("/admin/compilations", Map.of("title", title), JsonNode.class));
        long id = compilation(Map.of("title", "Valid")).path("id").asLong();
        assertStatus(HttpStatus.BAD_REQUEST, patchResponse(id, Map.of("title", title)));
    }

    @Test
    void requiresTitleAndRejectsNullOrNonpositiveEventIds() {
        assertStatus(HttpStatus.BAD_REQUEST, restTemplate.postForEntity("/admin/compilations", Map.of(), JsonNode.class));
        for (String body : List.of("{\"title\":\"Valid\",\"events\":[null]}",
                "{\"title\":\"Valid\",\"events\":[0]}", "{\"title\":\"Valid\",\"events\":[-1]}")) {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            assertStatus(HttpStatus.BAD_REQUEST, restTemplate.exchange("/admin/compilations", HttpMethod.POST,
                    new HttpEntity<>(body, headers), JsonNode.class));
        }
    }

    @Test
    void rejectsMissingEventsWithoutPartiallyCreatingOrReplacingCompilation() {
        long event = event();
        assertStatus(HttpStatus.NOT_FOUND, restTemplate.postForEntity("/admin/compilations",
                Map.of("title", "Missing", "events", List.of(event, 999L)), JsonNode.class));
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM compilations", Long.class));
        JsonNode original = compilation(Map.of("title", "Original", "events", List.of(event)));
        long id = original.path("id").asLong();
        assertStatus(HttpStatus.NOT_FOUND, patchResponse(id, Map.of("title", "Changed", "events", List.of(999))));
        assertEquals(original, get("/compilations/" + id));
    }

    @Test
    void duplicateTitleReturnsConflictAndRenameRollsBackMembershipChanges() {
        long event = event();
        compilation(Map.of("title", "Taken"));
        assertStatus(HttpStatus.CONFLICT, restTemplate.postForEntity("/admin/compilations", Map.of("title", "Taken"), JsonNode.class));
        JsonNode original = compilation(Map.of("title", "Original", "events", List.of(event)));
        long id = original.path("id").asLong();
        assertStatus(HttpStatus.CONFLICT, patchResponse(id, Map.of("title", "Taken", "events", List.of(), "pinned", true)));
        assertEquals(original, get("/compilations/" + id));
        assertEquals(original, patch(id, Map.of("title", "Original")));
    }

    @Test
    void patchReplacesEventsAppliesFalseAndTreatsNullAsUnchanged() {
        long a = event();
        long b = event();
        long id = compilation(Map.of("title", "Before", "events", List.of(a), "pinned", true)).path("id").asLong();
        JsonNode updated = patch(id, Map.of("title", "After", "events", List.of(b), "pinned", false, "id", 999));
        assertEquals("After", updated.path("title").asText());
        assertEquals(List.of(b), ids(updated.path("events")));
        assertFalse(updated.path("pinned").asBoolean());
        assertEquals(id, updated.path("id").asLong());
        Map<String, Object> nulls = new HashMap<>();
        nulls.put("title", null);
        nulls.put("pinned", null);
        nulls.put("events", null);
        assertEquals(updated, patch(id, nulls));
        assertEquals(updated, patch(id, Map.of()));
        assertEquals(updated, get("/compilations/" + id));
    }

    @Test
    void emptyArrayClearsOnlyMembershipAndDeleteDoesNotDeleteEvents() {
        long event = event();
        long id = compilation(Map.of("title", "Clear", "events", List.of(event))).path("id").asLong();
        assertEquals(0, patch(id, Map.of("events", List.of())).path("events").size());
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM events", Long.class));
        patch(id, Map.of("events", List.of(event)));
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        ResponseEntity<Void> response = restTemplate.exchange("/admin/compilations/" + id, HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertEquals(null, response.getBody());
        assertEquals(1, statistics.getPrepareStatementCount());
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM compilation_events", Long.class));
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM events", Long.class));
    }

    @Test
    void filtersPinnedBeforeExactOffsetAndUsesStableOrder() {
        compilation(Map.of("title", "Unpinned"));
        long a = compilation(Map.of("title", "Pinned A", "pinned", true)).path("id").asLong();
        long b = compilation(Map.of("title", "Pinned B", "pinned", true)).path("id").asLong();
        long c = compilation(Map.of("title", "Pinned C", "pinned", true)).path("id").asLong();
        assertEquals(List.of(b, c), ids(get("/compilations?pinned=true&from=1&size=2")));
        assertEquals(List.of(a, b, c), ids(get("/compilations?pinned=true")));
        assertEquals(1, get("/compilations?pinned=false").size());
        assertEquals(4, get("/compilations").size());
        assertEquals(0, get("/compilations?from=4").size());
    }

    @Test
    void emptyListAndDefaultPageSizeWork() {
        assertEquals(0, get("/compilations").size());
        for (int i = 0; i < 11; i++) {
            compilation(Map.of("title", "Compilation " + i));
        }
        assertEquals(10, get("/compilations").size());
        assertEquals(0, get("/compilations?pinned=true").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"from=-1", "size=0", "size=-1", "from=bad", "pinned=bad"})
    void validatesListParameters(String query) {
        assertStatus(HttpStatus.BAD_REQUEST, restTemplate.getForEntity("/compilations?" + query, JsonNode.class));
    }

    @Test
    void missingCompilationReturnsNotFoundAndInvalidPathReturnsBadRequest() {
        assertStatus(HttpStatus.NOT_FOUND, restTemplate.getForEntity("/compilations/999", JsonNode.class));
        assertStatus(HttpStatus.NOT_FOUND, patchResponse(999, Map.of("title", "Absent")));
        assertStatus(HttpStatus.NOT_FOUND, restTemplate.exchange("/admin/compilations/999", HttpMethod.DELETE, HttpEntity.EMPTY, JsonNode.class));
        assertStatus(HttpStatus.BAD_REQUEST, restTemplate.getForEntity("/compilations/bad", JsonNode.class));
    }

    @Test
    void pageLoadsEventsAndCountersInBatchesWithoutRecordingViews() {
        long a = event();
        long b = event();
        long guest = post("/admin/users", Map.of("name", "Guest", "email", "guest@example.org")).path("id").asLong();
        post("/users/" + guest + "/requests?eventId=" + a, null);
        compilation(Map.of("title", "First", "events", List.of(a, b)));
        compilation(Map.of("title", "Second", "events", List.of(a)));
        compilation(Map.of("title", "Empty"));
        when(statsClient.getStats(any(), any(), anyList(), eq(true)))
                .thenReturn(List.of(new ViewStats("ewm-main-service", "/events/" + a, 7L)));
        clearInvocations(statsClient);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        JsonNode result = get("/compilations");
        assertEquals(4, statistics.getPrepareStatementCount());
        verify(statsClient).getStats(any(), any(), anyList(), eq(true));
        verify(statsClient, never()).hit(any());
        JsonNode first = result.get(0).path("events").get(0);
        assertEquals(1, first.path("confirmedRequests").asLong());
        assertEquals(7, first.path("views").asLong());
        assertEquals(0, result.get(0).path("events").get(1).path("views").asLong());
        assertEquals(first, result.get(1).path("events").get(0));
    }

    @Test
    void deletedEventDisappearsFromMembershipButCompilationSurvives() {
        long event = event();
        long id = compilation(Map.of("title", "Remains", "events", List.of(event))).path("id").asLong();
        restTemplate.delete("/admin/users/" + owner);
        JsonNode result = get("/compilations/" + id);
        assertEquals("Remains", result.path("title").asText());
        assertEquals(0, result.path("events").size());
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM compilation_events", Long.class));
    }

    @Test
    void publicCompilationHidesPendingEventsButKeepsThemForAdministration() {
        long pending = event(false);
        long published = event();
        JsonNode created = compilation(Map.of("title", "For later", "events", List.of(pending, published)));
        long id = created.path("id").asLong();
        assertEquals(List.of(pending, published), ids(created.path("events")));
        assertEquals(List.of(published), ids(get("/compilations/" + id).path("events")));
        assertEquals(List.of(published), ids(get("/compilations").get(0).path("events")));
        assertEquals(List.of(pending, published), ids(patch(id, Map.of()).path("events")));
        assertStatus(HttpStatus.OK, restTemplate.exchange("/admin/events/" + pending, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("stateAction", "PUBLISH_EVENT")), JsonNode.class));
        assertEquals(List.of(pending, published), ids(get("/compilations/" + id).path("events")));
    }

    @Test
    void statisticsFailureRollsBackCompilationChanges() {
        long event = event();
        JsonNode original = compilation(Map.of("title", "Original", "events", List.of(event)));
        when(statsClient.getStats(any(), any(), anyList(), anyBoolean()))
                .thenThrow(new ResourceAccessException("connection failed"));
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, patchResponse(original.path("id").asLong(), Map.of("title", "Changed")));
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, restTemplate.postForEntity("/admin/compilations",
                Map.of("title", "Not created", "events", List.of(event)), JsonNode.class));
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM compilations", Long.class));
        assertEquals("Original", jdbcTemplate.queryForObject("SELECT title FROM compilations", String.class));
    }

    @Test
    void simultaneousReplacementNeverMergesDifferentEventSets() throws Exception {
        long a = event();
        long b = event();
        long id = compilation(Map.of("title", "Concurrent")).path("id").asLong();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return patchResponse(id, Map.of("events", List.of(a)));
            });
            var second = executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return patchResponse(id, Map.of("events", List.of(b)));
            });
            start.countDown();
            assertStatus(HttpStatus.OK, first.get(20, TimeUnit.SECONDS));
            assertStatus(HttpStatus.OK, second.get(20, TimeUnit.SECONDS));
        }
        List<Long> actual = ids(get("/compilations/" + id).path("events"));
        assertTrue(actual.equals(List.of(a)) || actual.equals(List.of(b)), actual.toString());
    }

    private long event() {
        return event(true);
    }

    private long event(boolean published) {
        long id = post("/users/" + owner + "/events", Map.of("annotation", "A sufficiently long annotation",
                "description", "A sufficiently long description", "title", "Concert", "category", category,
                "eventDate", "2026-11-03 12:00:00", "location", Map.of("lat", 55.75, "lon", 37.62))).path("id").asLong();
        if (published) {
            assertStatus(HttpStatus.OK, restTemplate.exchange("/admin/events/" + id, HttpMethod.PATCH,
                    new HttpEntity<>(Map.of("stateAction", "PUBLISH_EVENT")), JsonNode.class));
        }
        return id;
    }

    private JsonNode compilation(Object body) {
        return post("/admin/compilations", body);
    }

    private JsonNode post(String path, Object body) {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(path, body, JsonNode.class);
        assertStatus(HttpStatus.CREATED, response);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> patchResponse(long id, Object body) {
        return restTemplate.exchange("/admin/compilations/" + id, HttpMethod.PATCH, new HttpEntity<>(body), JsonNode.class);
    }

    private JsonNode patch(long id, Object body) {
        ResponseEntity<JsonNode> response = patchResponse(id, body);
        assertStatus(HttpStatus.OK, response);
        return response.getBody();
    }

    private JsonNode get(String path) {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity(path, JsonNode.class);
        assertStatus(HttpStatus.OK, response);
        return response.getBody();
    }

    private List<Long> ids(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(value -> value.path("id").asLong()).toList();
    }

    private void assertStatus(HttpStatus expected, ResponseEntity<JsonNode> response) {
        assertEquals(expected, response.getStatusCode(), () -> String.valueOf(response.getBody()));
        assertNotNull(response.getBody());
        if (expected.isError()) {
            assertEquals(expected.name(), response.getBody().path("status").asText());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {

        @Bean
        @Primary
        Clock clockForTests() {
            return Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC);
        }
    }
}
