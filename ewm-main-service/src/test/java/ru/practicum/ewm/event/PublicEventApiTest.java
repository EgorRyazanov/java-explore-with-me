package ru.practicum.ewm.event;

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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.practicum.stats.client.StatsClient;
import ru.practicum.stats.dto.EndpointHit;
import ru.practicum.stats.dto.ViewStats;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@Import(PublicEventApiTest.FixedTime.class)
class PublicEventApiTest {

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
    private final Map<Long, Set<String>> visitors = new ConcurrentHashMap<>();
    private final List<EndpointHit> hits = new CopyOnWriteArrayList<>();
    private final AtomicBoolean statsInsideTransaction = new AtomicBoolean();
    private long owner;
    private long requester;
    private long category;
    private long otherCategory;

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
        reset(statsClient);
        visitors.clear();
        hits.clear();
        when(statsClient.getStats(any(), any(), anyList(), eq(true))).thenAnswer(invocation -> {
            statsInsideTransaction.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive());
            List<String> uris = invocation.getArgument(2);
            List<ViewStats> result = new ArrayList<>();
            for (String uri : uris) {
                long id = Long.parseLong(uri.substring("/events/".length()));
                result.add(new ViewStats("ewm-main-service", uri, (long) visitors.getOrDefault(id, Set.of()).size()));
                result.add(new ViewStats("another-service", uri, 1000L));
            }
            return result;
        });
        doAnswer(invocation -> {
            EndpointHit hit = invocation.getArgument(0);
            hits.add(hit);
            if (hit.uri().startsWith("/events/")) {
                long id = Long.parseLong(hit.uri().substring("/events/".length()));
                visitors.computeIfAbsent(id, ignored -> ConcurrentHashMap.newKeySet()).add(hit.ip());
            }
            return null;
        }).when(statsClient).hit(any());
        jdbcTemplate.execute("TRUNCATE users, categories RESTART IDENTITY CASCADE");
        owner = post("/admin/users", Map.of("name", "Owner", "email", "owner@example.org")).path("id").asLong();
        requester = post("/admin/users", Map.of("name", "Guest", "email", "guest@example.org")).path("id").asLong();
        category = post("/admin/categories", Map.of("name", "Concerts")).path("id").asLong();
        otherCategory = post("/admin/categories", Map.of("name", "Theatre")).path("id").asLong();
        statsInsideTransaction.set(false);
    }

    @Test
    void returnsOnlyPublishedFutureEventsAndRecordsSearch() {
        long published = event("2026-11-03 12:00:00", true);
        event("2026-11-04 12:00:00", false);
        long canceled = event("2026-11-05 12:00:00", false);
        patch("/admin/events/" + canceled, Map.of("stateAction", "REJECT_EVENT"));
        long past = event("2026-11-06 12:00:00", true);
        jdbcTemplate.update("UPDATE events SET event_date = '2026-11-01 12:00:00' WHERE id = ?", past);
        statsInsideTransaction.set(false);
        JsonNode result = get("/events");
        assertEquals(List.of(published), ids(result));
        assertEquals(9, result.get(0).size());
        assertEquals(0, result.get(0).path("views").asLong());
        assertEquals(1, hits.size());
        EndpointHit hit = hits.get(0);
        assertEquals("ewm-main-service", hit.app());
        assertEquals("/events", hit.uri());
        assertFalse(hit.ip().isBlank());
        assertEquals(LocalDateTime.of(2026, 11, 2, 10, 0), hit.timestamp());
        assertFalse(statsInsideTransaction.get());
    }

    @Test
    void showsFullPublishedEventAndCountsUniqueViewBeforeReturning() {
        long id = event("2026-11-03 12:00:00", true);
        statsInsideTransaction.set(false);
        JsonNode first = get("/events/" + id);
        assertEquals("PUBLISHED", first.path("state").asText());
        assertEquals(1, first.path("views").asLong());
        assertEquals(2, first.path("initiator").size());
        assertEquals(1, get("/events/" + id).path("views").asLong());
        assertEquals(2, hits.size());
        assertFalse(statsInsideTransaction.get());
        assertEquals(1, get("/users/" + owner + "/events/" + id).path("views").asLong());
        assertEquals(1, get("/users/" + owner + "/events").get(0).path("views").asLong());
        assertEquals(1, get("/admin/events").get(0).path("views").asLong());
        assertEquals(1, patch("/admin/events/" + id, Map.of("title", "Changed")).path("views").asLong());
        assertEquals(1, get("/events/000" + id).path("views").asLong());
        assertEquals("/events/" + id, hits.get(hits.size() - 1).uri());
    }

    @Test
    void hidesUnpublishedOrMissingEventsWithoutWritingHits() {
        long pending = event("2026-11-03 12:00:00", false);
        clearInvocations(statsClient);
        assertStatus(HttpStatus.NOT_FOUND, restTemplate.getForEntity("/events/" + pending, JsonNode.class));
        assertStatus(HttpStatus.NOT_FOUND, restTemplate.getForEntity("/events/999", JsonNode.class));
        assertStatus(HttpStatus.BAD_REQUEST, restTemplate.getForEntity("/events/bad", JsonNode.class));
        verify(statsClient, never()).hit(any());
        verify(statsClient, never()).getStats(any(), any(), anyList(), anyBoolean());
    }

    @Test
    void searchesAnnotationAndDescriptionWithoutCaseButDoesNotSearchTitle() {
        long annotation = event("2026-11-03 12:00:00", true);
        long description = event("2026-11-04 12:00:00", true);
        long titleOnly = event("2026-11-05 12:00:00", true);
        jdbcTemplate.update("UPDATE events SET annotation = 'Welcome to the ТеАтР today' WHERE id = ?", annotation);
        jdbcTemplate.update("UPDATE events SET description = 'An exciting visit to the ТЕАТР' WHERE id = ?", description);
        jdbcTemplate.update("UPDATE events SET title = 'театр' WHERE id = ?", titleOnly);
        assertEquals(List.of(annotation, description), ids(get("/events?text={text}", "театр")));
        assertEquals(0, get("/events?text=missing").size());
    }

    @Test
    void treatsSqlWildcardsAsLiteralSearchCharacters() {
        long literal = event("2026-11-03 12:00:00", true);
        long decoy = event("2026-11-04 12:00:00", true);
        jdbcTemplate.update("UPDATE events SET annotation = ? WHERE id = ?", "Literal 100%_\\ information", literal);
        jdbcTemplate.update("UPDATE events SET annotation = ? WHERE id = ?", "Literal 100percent information", decoy);
        assertEquals(List.of(literal), ids(get("/events?text={text}", "100%_\\")));
    }

    @Test
    void combinesCategoryPaidAndInclusiveDateFilters() {
        long first = event("2026-11-03 12:00:00", true);
        long second = event("2026-11-04 12:00:00", true);
        jdbcTemplate.update("UPDATE events SET category_id = ?, paid = TRUE WHERE id = ?", otherCategory, second);
        assertEquals(List.of(second), ids(get("/events?categories=" + otherCategory + "&paid=true")));
        assertEquals(List.of(first), ids(get("/events?paid=false")));
        assertEquals(List.of(first, second), ids(get("/events?categories=" + category + "," + otherCategory)));
        assertEquals(List.of(first, second), ids(get("/events?categories=")));
        assertEquals(List.of(second), ids(get("/events?rangeStart={start}&rangeEnd={end}",
                "2026-11-04 12:00:00", "2026-11-04 12:00:00")));
        assertEquals(List.of(second), ids(get("/events?rangeStart={date}", "2026-11-04 12:00:00")));
        jdbcTemplate.update("UPDATE events SET event_date = '2026-11-01 12:00:00' WHERE id = ?", first);
        assertEquals(List.of(first), ids(get("/events?rangeEnd={date}", "2026-11-01 12:00:00")));
    }

    @Test
    void filtersAvailabilityBeforePaginationAndAllowsUnlimitedEvents() {
        long full = event("2026-11-03 12:00:00", true);
        long available = event("2026-11-04 12:00:00", true);
        long unlimited = event("2026-11-05 12:00:00", true);
        jdbcTemplate.update("UPDATE events SET participant_limit = 1, request_moderation = FALSE WHERE id IN (?, ?)", full, available);
        post("/users/" + requester + "/requests?eventId=" + full, null);
        post("/users/" + requester + "/requests?eventId=" + unlimited, null);
        assertEquals(List.of(available, unlimited), ids(get("/events?onlyAvailable=true&size=2")));
        assertEquals(List.of(full, available, unlimited), ids(get("/events?onlyAvailable=false")));
        assertEquals(List.of(1L, 0L, 1L), values(get("/events"), "confirmedRequests"));
    }

    @Test
    void sortsDatesBeforeExactOffsetWithStableTies() {
        long later = event("2026-11-05 12:00:00", true);
        long earlier = event("2026-11-03 12:00:00", true);
        long tied = event("2026-11-03 12:00:00", true);
        assertEquals(List.of(earlier, tied, later), ids(get("/events?sort=EVENT_DATE")));
        assertEquals(List.of(tied, later), ids(get("/events?sort=EVENT_DATE&from=1&size=2")));
    }

    @Test
    void sortsAllMatchingViewsBeforePaginationAndFiltersOtherApplications() {
        long first = event("2026-11-03 12:00:00", true);
        long second = event("2026-11-04 12:00:00", true);
        long third = event("2026-11-05 12:00:00", true);
        seedViews(first, 1);
        seedViews(second, 8);
        seedViews(third, 5);
        assertEquals(List.of(second, third), ids(get("/events?sort=VIEWS&size=2")));
        assertEquals(List.of(third, first), ids(get("/events?sort=VIEWS&from=1&size=2")));
        assertEquals(List.of(5L, 1L), values(get("/events?sort=VIEWS&from=1&size=2"), "views"));
        visitors.clear();
        assertEquals(List.of(first, second, third), ids(get("/events?sort=VIEWS")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"paid", "category", "date", "text", "availability"})
    void rechecksFiltersIfEventChangesWhileLoadingViews(String changedField) {
        long id = event("2026-11-03 12:00:00", true);
        String query = switch (changedField) {
            case "paid" -> "&paid=false";
            case "category" -> "&categories=" + category;
            case "text" -> "&text=sufficiently";
            case "availability" -> "&onlyAvailable=true";
            default -> "";
        };
        when(statsClient.getStats(any(), any(), anyList(), eq(true))).thenAnswer(invocation -> {
            switch (changedField) {
                case "paid" -> jdbcTemplate.update("UPDATE events SET paid = TRUE WHERE id = ?", id);
                case "category" -> jdbcTemplate.update("UPDATE events SET category_id = ? WHERE id = ?", otherCategory, id);
                case "date" -> jdbcTemplate.update("UPDATE events SET event_date = '2020-01-01 00:00:00' WHERE id = ?", id);
                case "text" -> jdbcTemplate.update("UPDATE events SET annotation = 'Changed', description = 'Changed' WHERE id = ?", id);
                case "availability" -> {
                    jdbcTemplate.update("UPDATE events SET participant_limit = 1 WHERE id = ?", id);
                    jdbcTemplate.update("""
                            INSERT INTO participation_requests (event_id, requester_id, created, status)
                            VALUES (?, ?, '2026-11-02 10:00:00', 'CONFIRMED')
                            """, id, requester);
                }
                default -> throw new IllegalArgumentException(changedField);
            }
            return List.of(new ViewStats("ewm-main-service", "/events/" + id, 1L));
        });
        assertEquals(0, get("/events?sort=VIEWS" + query).size());
    }

    @Test
    void emptySearchRecordsHitAndDoesNotRequestStatistics() {
        clearInvocations(statsClient);
        assertEquals(0, get("/events").size());
        verify(statsClient, never()).getStats(any(), any(), anyList(), anyBoolean());
        verify(statsClient).hit(any());
    }

    @Test
    void defaultsToTenAndHandlesOffsetBeyondResults() {
        for (int i = 0; i < 11; i++) {
            event("2026-11-03 12:00:00", true);
        }
        assertEquals(10, get("/events").size());
        assertEquals(0, get("/events?from=20").size());
        assertEquals(0, get("/events?sort=VIEWS&from=2147483647&size=2147483647").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"from=-1", "size=0", "size=-1", "sort=UNKNOWN", "paid=bad", "onlyAvailable=bad",
            "text=", "rangeStart=bad", "rangeStart=2026-02-30%2012:00:00", "rangeStart=2026-11-02T12:00:00"})
    void rejectsInvalidParametersWithoutWritingHit(String query) {
        assertStatus(HttpStatus.BAD_REQUEST, restTemplate.getForEntity("/events?" + query, JsonNode.class));
        assertEquals(0, hits.size());
    }

    @Test
    void rejectsLongTextAndReversedRange() {
        assertStatus(HttpStatus.BAD_REQUEST, restTemplate.getForEntity("/events?text={text}", JsonNode.class, "a".repeat(7001)));
        assertStatus(HttpStatus.BAD_REQUEST, restTemplate.getForEntity("/events?rangeStart={start}&rangeEnd={end}",
                JsonNode.class, "2026-11-04 12:00:00", "2026-11-03 12:00:00"));
        assertEquals(0, hits.size());
    }

    @Test
    void publicListsUseFixedNumberOfSqlQueriesAndOneStatsBatch() {
        for (int i = 0; i < 5; i++) {
            event("2026-11-03 12:00:00", true);
        }
        clearInvocations(statsClient);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertEquals(5, get("/events").size());
        assertEquals(2, statistics.getPrepareStatementCount());
        verify(statsClient).getStats(any(), any(), anyList(), eq(true));
        clearInvocations(statsClient);
        statistics.clear();
        assertEquals(2, get("/events?sort=VIEWS&size=2").size());
        assertEquals(3, statistics.getPrepareStatementCount());
        verify(statsClient).getStats(any(), any(), anyList(), eq(true));
    }

    @Test
    void viewStatisticsAreSplitIntoBoundedBatches() {
        long sample = event("2026-11-03 12:00:00", true);
        jdbcTemplate.update("""
                INSERT INTO events (initiator_id, category_id, annotation, description, title, event_date,
                    created_on, lat, lon, paid, participant_limit, request_moderation, state, published_on)
                SELECT initiator_id, category_id, annotation, description, title, event_date,
                    created_on, lat, lon, paid, participant_limit, request_moderation, state, published_on
                FROM events CROSS JOIN generate_series(1, 204) WHERE id = ?
                """, sample);
        clearInvocations(statsClient);
        assertEquals(1, get("/events?sort=VIEWS&size=1").size());
        verify(statsClient, times(3)).getStats(any(), any(), anyList(), eq(true));
    }

    @Test
    void statisticsFailuresReturnServiceUnavailableAndDoNotCommitEventEdits() {
        long event = event("2026-11-03 12:00:00", true);
        when(statsClient.getStats(any(), any(), anyList(), anyBoolean()))
                .thenThrow(new ResourceAccessException("connection failed"));
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, restTemplate.getForEntity("/events", JsonNode.class));
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, restTemplate.exchange("/admin/events/" + event, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("title", "Should roll back")), JsonNode.class));
        assertEquals("Concert", jdbcTemplate.queryForObject("SELECT title FROM events WHERE id = ?", String.class, event));
        assertEquals(0, hits.size());
    }

    @Test
    void failingHitReturnsServiceUnavailable() {
        long event = event("2026-11-03 12:00:00", true);
        doThrow(new ResourceAccessException("connection failed")).when(statsClient).hit(any());
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, restTemplate.getForEntity("/events/" + event, JsonNode.class));
    }

    private void seedViews(long id, int count) {
        Set<String> ips = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < count; i++) {
            ips.add("192.0.2." + i);
        }
        visitors.put(id, ips);
    }

    private long event(String date, boolean published) {
        long id = post("/users/" + owner + "/events", Map.of("annotation", "A sufficiently long annotation",
                "description", "A sufficiently long description", "title", "Concert", "category", category,
                "eventDate", date, "location", Map.of("lat", 55.75, "lon", 37.62))).path("id").asLong();
        if (published) {
            patch("/admin/events/" + id, Map.of("stateAction", "PUBLISH_EVENT"));
        }
        return id;
    }

    private JsonNode post(String path, Object body) {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(path, body, JsonNode.class);
        assertStatus(HttpStatus.CREATED, response);
        return response.getBody();
    }

    private JsonNode patch(String path, Object body) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(path, HttpMethod.PATCH, new HttpEntity<>(body), JsonNode.class);
        assertStatus(HttpStatus.OK, response);
        return response.getBody();
    }

    private JsonNode get(String path, Object... variables) {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity(path, JsonNode.class, variables);
        assertStatus(HttpStatus.OK, response);
        return response.getBody();
    }

    private List<Long> ids(JsonNode events) {
        return values(events, "id");
    }

    private List<Long> values(JsonNode events, String field) {
        return StreamSupport.stream(events.spliterator(), false).map(event -> event.path(field).asLong()).toList();
    }

    private void assertStatus(HttpStatus status, ResponseEntity<JsonNode> response) {
        assertEquals(status, response.getStatusCode(), () -> String.valueOf(response.getBody()));
        assertNotNull(response.getBody());
        if (status.isError()) {
            assertEquals(status.name(), response.getBody().path("status").asText());
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
