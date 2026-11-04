package ru.practicum.ewm.event;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.practicum.stats.client.StatsClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@Import(EventApiTest.FixedTime.class)
class EventApiTest {

    @Container
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16.1");

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private CoordinatedClock clock;
    @MockBean
    private StatsClient statsClient;
    private long owner;
    private long other;
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
        jdbcTemplate.execute("TRUNCATE users, categories RESTART IDENTITY CASCADE");
        owner = post("/admin/users", Map.of("name", "Owner", "email", "owner@example.org")).path("id").asLong();
        other = post("/admin/users", Map.of("name", "Other", "email", "other@example.org")).path("id").asLong();
        category = post("/admin/categories", Map.of("name", "Concerts")).path("id").asLong();
        otherCategory = post("/admin/categories", Map.of("name", "Theatre")).path("id").asLong();
    }

    @Test
    void createsPendingEventWithServerOwnedFieldsAndDefaults() {
        Map<String, Object> body = newEvent();
        body.putAll(Map.of("id", 777, "state", "PUBLISHED", "initiator", Map.of("id", other),
                "createdOn", "2000-01-01 00:00:00", "publishedOn", "2000-01-01 00:00:00", "views", 12));
        JsonNode event = post(ownEvents(owner), body);
        assertEquals("PENDING", event.path("state").asText());
        assertEquals("2026-11-02 10:00:00", event.path("createdOn").asText());
        assertEquals("2026-11-02 12:00:00", event.path("eventDate").asText());
        assertEquals(owner, event.path("initiator").path("id").asLong());
        assertEquals(2, event.path("initiator").size());
        assertFalse(event.path("paid").asBoolean());
        assertEquals(0, event.path("participantLimit").asInt());
        assertTrue(event.path("requestModeration").asBoolean());
        assertEquals(0, event.path("confirmedRequests").asLong());
        assertEquals(0, event.path("views").asLong());
        assertTrue(event.path("publishedOn").isMissingNode() || event.path("publishedOn").isNull());
        assertEquals(category, event.path("category").path("id").asLong());
        assertEquals(55.75, event.path("location").path("lat").asDouble());
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM events", Long.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"annotation", "description", "title", "category", "eventDate", "location"})
    void rejectsMissingRequiredFields(String field) {
        Map<String, Object> body = newEvent();
        body.remove(field);
        assertStatus(HttpStatus.BAD_REQUEST, create(owner, body));
    }

    @ParameterizedTest
    @CsvSource({"annotation,19", "annotation,2001", "description,19", "description,7001", "title,2", "title,121"})
    void rejectsInvalidStringLengths(String field, int length) {
        Map<String, Object> body = newEvent();
        body.put(field, "a".repeat(length));
        assertStatus(HttpStatus.BAD_REQUEST, create(owner, body));
        assertStatus(HttpStatus.BAD_REQUEST, patch(ownEvent(owner, createEvent(owner)), Map.of(field, "a".repeat(length))));
        assertStatus(HttpStatus.BAD_REQUEST, patch(adminEvent(createEvent(owner)), Map.of(field, "a".repeat(length))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"annotation", "description", "title"})
    void rejectsBlankStrings(String field) {
        Map<String, Object> body = newEvent();
        body.put(field, " ".repeat(20));
        assertStatus(HttpStatus.BAD_REQUEST, create(owner, body));
        assertStatus(HttpStatus.BAD_REQUEST, patch(ownEvent(owner, createEvent(owner)), Map.of(field, " ".repeat(20))));
        assertStatus(HttpStatus.BAD_REQUEST, patch(adminEvent(createEvent(owner)), Map.of(field, " ".repeat(20))));
    }

    @Test
    void acceptsStringLengthBoundaries() {
        Map<String, Object> body = newEvent();
        body.putAll(Map.of("annotation", "a".repeat(20), "description", "d".repeat(20), "title", "abc"));
        assertStatus(HttpStatus.CREATED, create(owner, body));
        body.putAll(Map.of("annotation", "a".repeat(2000), "description", "d".repeat(7000), "title", "t".repeat(120)));
        assertStatus(HttpStatus.CREATED, create(owner, body));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-02T12:00:00", "2026-02-30 12:00:00", "2026-11-02 12:00", "not a date"})
    void rejectsMalformedDates(String date) {
        Map<String, Object> body = newEvent();
        body.put("eventDate", date);
        assertStatus(HttpStatus.BAD_REQUEST, create(owner, body));
        assertStatus(HttpStatus.BAD_REQUEST, patch(ownEvent(owner, createEvent(owner)), Map.of("eventDate", date)));
    }

    @Test
    void rejectsCreationEarlierThanTwoHours() {
        Map<String, Object> body = newEvent();
        body.put("eventDate", "2026-11-02 11:59:59");
        assertStatus(HttpStatus.CONFLICT, create(owner, body));
    }

    @Test
    void rejectsInvalidLimitAndCoordinates() {
        Map<String, Object> body = newEvent();
        body.put("participantLimit", -1);
        assertStatus(HttpStatus.BAD_REQUEST, create(owner, body));
        body.remove("participantLimit");
        for (Map<String, Double> location : List.of(Map.of("lat", 91.0, "lon", 0.0),
                Map.of("lat", 0.0, "lon", -181.0), Map.of("lat", 0.0))) {
            body.put("location", location);
            assertStatus(HttpStatus.BAD_REQUEST, create(owner, body));
        }
    }

    @Test
    void rejectsUnknownUserAndCategory() {
        assertStatus(HttpStatus.NOT_FOUND, create(999, newEvent()));
        Map<String, Object> body = newEvent();
        body.put("category", 999);
        assertStatus(HttpStatus.NOT_FOUND, create(owner, body));
    }

    @Test
    void getsOnlyAnOwnedEventWithOneJoinedQuery() {
        long id = createEvent(owner);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertEquals(id, get(ownEvent(owner, id)).path("id").asLong());
        assertEquals(2, statistics.getPrepareStatementCount());
        assertStatus(HttpStatus.NOT_FOUND, restTemplate.getForEntity(ownEvent(other, id), JsonNode.class));
        assertStatus(HttpStatus.NOT_FOUND, patch(ownEvent(other, id), Map.of("title", "Foreign")));
        assertStatus(HttpStatus.NOT_FOUND, restTemplate.getForEntity(ownEvent(owner, 999), JsonNode.class));
    }

    @Test
    void listsOwnedEventsWithExactOffsetAndShortShapeWithoutNPlusOne() {
        createEvent(owner);
        createEvent(other);
        long second = createEvent(owner);
        long third = createEvent(owner);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        JsonNode events = get(ownEvents(owner) + "?from=1&size=2");
        assertEquals(List.of(second, third), ids(events));
        assertEquals(3, statistics.getPrepareStatementCount());
        assertEquals(9, events.get(0).size());
        assertFalse(events.get(0).has("description"));
        assertEquals(0, get(ownEvents(other) + "?from=1").size());
        assertStatus(HttpStatus.NOT_FOUND, restTemplate.getForEntity(ownEvents(999), JsonNode.class));
    }

    @Test
    void updatesOnlyProvidedFieldsIncludingFalseAndZero() {
        Map<String, Object> body = newEvent();
        body.putAll(Map.of("paid", true, "participantLimit", 20, "requestModeration", true));
        long id = post(ownEvents(owner), body).path("id").asLong();
        Map<String, Object> update = new HashMap<>(Map.of("paid", false, "participantLimit", 0,
                "requestModeration", false, "category", otherCategory, "location", Map.of("lat", 0, "lon", 0),
                "eventDate", "2026-11-02 12:00:00", "id", 999, "state", "PUBLISHED"));
        update.put("title", null);
        JsonNode event = successfulPatch(ownEvent(owner, id), update);
        assertEquals("Concert", event.path("title").asText());
        assertFalse(event.path("paid").asBoolean());
        assertFalse(event.path("requestModeration").asBoolean());
        assertEquals(0, event.path("participantLimit").asInt());
        assertEquals(otherCategory, event.path("category").path("id").asLong());
        assertEquals(0, event.path("location").path("lat").asDouble());
        assertEquals(id, event.path("id").asLong());
        assertEquals("PENDING", event.path("state").asText());
        assertEquals(event, get(ownEvent(owner, id)));
        assertEquals(event, successfulPatch(ownEvent(owner, id), Map.of()));
    }

    @Test
    void rollsBackPatchWhenCategoryDoesNotExist() {
        long id = createEvent(owner);
        assertStatus(HttpStatus.NOT_FOUND, patch(ownEvent(owner, id), Map.of("title", "Changed", "category", 999)));
        assertEquals("Concert", get(ownEvent(owner, id)).path("title").asText());
    }

    @Test
    void ownerCancelsAndResubmitsButCannotEditPublishedEvent() {
        long id = createEvent(owner);
        assertEquals("CANCELED", ownerAction(id, "CANCEL_REVIEW").path("state").asText());
        assertEquals("Renamed", successfulPatch(ownEvent(owner, id), Map.of("title", "Renamed")).path("title").asText());
        assertEquals("PENDING", ownerAction(id, "SEND_TO_REVIEW").path("state").asText());
        adminAction(id, "PUBLISH_EVENT");
        assertStatus(HttpStatus.CONFLICT, patch(ownEvent(owner, id), Map.of("title", "Changed")));
        assertStatus(HttpStatus.CONFLICT, patch(ownEvent(owner, id), Map.of("stateAction", "CANCEL_REVIEW")));
    }

    @Test
    void ownerCannotMoveEventEarlierThanTwoHours() {
        long id = createEvent(owner);
        assertStatus(HttpStatus.CONFLICT, patch(ownEvent(owner, id), Map.of("eventDate", "2026-11-02 11:59:59")));
        assertEquals("2026-11-02 12:00:00", get(ownEvent(owner, id)).path("eventDate").asText());
    }

    @Test
    void publicationSetsTimestampAndCannotBeRepeatedOrRejected() {
        long id = createEvent(owner);
        JsonNode published = adminAction(id, "PUBLISH_EVENT");
        assertEquals("PUBLISHED", published.path("state").asText());
        assertEquals("2026-11-02 10:00:00", published.path("publishedOn").asText());
        assertEquals(published, get(ownEvent(owner, id)));
        assertStatus(HttpStatus.CONFLICT, patch(adminEvent(id), Map.of("stateAction", "PUBLISH_EVENT")));
        assertStatus(HttpStatus.CONFLICT, patch(adminEvent(id), Map.of("stateAction", "REJECT_EVENT")));
        assertEquals("Edited by admin", successfulPatch(adminEvent(id), Map.of("title", "Edited by admin"))
                .path("title").asText());
    }

    @Test
    void canceledEventNeedsResubmissionBeforePublishing() {
        long id = createEvent(owner);
        assertEquals("CANCELED", adminAction(id, "REJECT_EVENT").path("state").asText());
        assertStatus(HttpStatus.CONFLICT, patch(adminEvent(id), Map.of("stateAction", "PUBLISH_EVENT")));
        assertEquals("CANCELED", adminAction(id, "REJECT_EVENT").path("state").asText());
        ownerAction(id, "SEND_TO_REVIEW");
        assertEquals("PUBLISHED", adminAction(id, "PUBLISH_EVENT").path("state").asText());
    }

    @Test
    void administratorEnforcesOneHourForNewDateAndPublication() {
        long id = createEvent(owner);
        assertStatus(HttpStatus.CONFLICT, patch(adminEvent(id), Map.of("eventDate", "2026-11-02 10:59:59")));
        JsonNode published = successfulPatch(adminEvent(id), Map.of("eventDate", "2026-11-02 11:00:00",
                "stateAction", "PUBLISH_EVENT"));
        assertEquals("2026-11-02 11:00:00", published.path("eventDate").asText());
        long late = createEvent(owner);
        jdbcTemplate.update("UPDATE events SET event_date = ?::timestamp WHERE id = ?", "2026-11-02 10:59:59", late);
        assertStatus(HttpStatus.CONFLICT, patch(adminEvent(late), Map.of("stateAction", "PUBLISH_EVENT")));
        assertEquals("PENDING", get(ownEvent(owner, late)).path("state").asText());
    }

    @Test
    void administratorValidatesProvidedFieldsAndAllowsOmittedFields() {
        long id = createEvent(owner);
        assertStatus(HttpStatus.BAD_REQUEST, patch(adminEvent(id), Map.of("title", "X")));
        assertEquals("Concert", successfulPatch(adminEvent(id), Map.of()).path("title").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-02 09:59:59", "2026-11-02 10:00:00"})
    void pastOrCurrentDateIsBadRequestOnCreationAndBothUpdates(String date) {
        Map<String, Object> body = newEvent();
        body.put("eventDate", date);
        assertStatus(HttpStatus.BAD_REQUEST, create(owner, body));
        long id = createEvent(owner);
        assertStatus(HttpStatus.BAD_REQUEST, patch(ownEvent(owner, id), Map.of("eventDate", date)));
        assertStatus(HttpStatus.BAD_REQUEST, patch(adminEvent(id), Map.of("eventDate", date)));
        assertEquals("2026-11-02 12:00:00", get(ownEvent(owner, id)).path("eventDate").asText());
    }

    @Test
    void rejectsActionsBelongingToAnotherApiAndUnknownActions() {
        long id = createEvent(owner);
        for (String action : List.of("PUBLISH_EVENT", "REJECT_EVENT", "UNKNOWN")) {
            assertStatus(HttpStatus.BAD_REQUEST, patch(ownEvent(owner, id), Map.of("stateAction", action)));
        }
        for (String action : List.of("SEND_TO_REVIEW", "CANCEL_REVIEW", "UNKNOWN")) {
            assertStatus(HttpStatus.BAD_REQUEST, patch(adminEvent(id), Map.of("stateAction", action)));
        }
        assertStatus(HttpStatus.NOT_FOUND, patch(adminEvent(999), Map.of("title", "Absent")));
        assertStatus(HttpStatus.NOT_FOUND, patch(ownEvent(owner, 999), Map.of("title", "Absent")));
    }

    @Test
    void filtersAdminEventsBeforePaginationWithOneJoinedQuery() {
        long first = createEvent(owner);
        long second = createEvent(owner);
        long third = createEvent(other);
        adminAction(second, "PUBLISH_EVENT");
        ownerAction(first, "CANCEL_REVIEW");
        successfulPatch(ownEvent(other, third), Map.of("category", otherCategory, "eventDate", "2026-11-03 12:00:00"));
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertEquals(List.of(first, second, third), ids(get("/admin/events")));
        assertEquals(2, statistics.getPrepareStatementCount());
        assertEquals(List.of(second), ids(get("/admin/events?users=" + owner + "&from=1&size=1")));
        assertEquals(List.of(second), ids(get("/admin/events?states=PUBLISHED")));
        assertEquals(List.of(first, second), ids(get("/admin/events?states=CANCELED,PUBLISHED&categories=" + category)));
        assertEquals(List.of(third), ids(get("/admin/events?categories=" + otherCategory)));
        assertEquals(List.of(third), ids(get("/admin/events?rangeStart={start}&rangeEnd={end}",
                "2026-11-03 12:00:00", "2026-11-03 12:00:00")));
        assertEquals(List.of(first, second), ids(get("/admin/events?rangeEnd={end}", "2026-11-02 12:00:00")));
        assertEquals(List.of(first, second, third), ids(get("/admin/events?users=&states=&categories=")));
        assertEquals(List.of(), ids(get("/admin/events?users=999")));
        assertEquals(List.of(), ids(get("/admin/events?users=" + owner + "&states=PENDING")));
    }

    @Test
    void defaultPageSizeIsTenAndAdminSearchIncludesPastEvents() {
        for (int i = 0; i < 11; i++) {
            createEvent(owner);
        }
        jdbcTemplate.update("UPDATE events SET event_date = '2020-01-01 00:00:00'");
        assertEquals(10, get("/admin/events").size());
        assertEquals(10, get(ownEvents(owner)).size());
        assertEquals(0, get("/admin/events?from=11").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"from=-1", "size=0", "size=-1", "from=bad"})
    void validatesPaginationForBothLists(String query) {
        assertStatus(HttpStatus.BAD_REQUEST, restTemplate.getForEntity("/admin/events?" + query, JsonNode.class));
        assertStatus(HttpStatus.BAD_REQUEST, restTemplate.getForEntity(ownEvents(owner) + "?" + query, JsonNode.class));
    }

    @Test
    void rejectsUnknownStateMalformedAndReversedRanges() {
        assertStatus(HttpStatus.BAD_REQUEST, restTemplate.getForEntity("/admin/events?states=UNKNOWN", JsonNode.class));
        for (String date : List.of("2026-02-30 12:00:00", "2026-11-02T12:00:00", "bad")) {
            assertStatus(HttpStatus.BAD_REQUEST, restTemplate.getForEntity("/admin/events?rangeStart={date}",
                    JsonNode.class, date));
        }
        assertStatus(HttpStatus.BAD_REQUEST, restTemplate.getForEntity("/admin/events?rangeStart={start}&rangeEnd={end}",
                JsonNode.class, "2026-11-03 12:00:00", "2026-11-02 12:00:00"));
    }

    @Test
    void preventsDeletingUsedCategoryEvenAfterCancellationAndCascadesOwnerDeletion() {
        long id = createEvent(owner);
        assertStatus(HttpStatus.CONFLICT, restTemplate.exchange("/admin/categories/" + category,
                HttpMethod.DELETE, HttpEntity.EMPTY, JsonNode.class));
        ownerAction(id, "CANCEL_REVIEW");
        assertStatus(HttpStatus.CONFLICT, restTemplate.exchange("/admin/categories/" + category,
                HttpMethod.DELETE, HttpEntity.EMPTY, JsonNode.class));
        assertEquals(id, get(ownEvent(owner, id)).path("id").asLong());
        assertEquals(HttpStatus.NO_CONTENT, restTemplate.exchange("/admin/users/" + owner,
                HttpMethod.DELETE, HttpEntity.EMPTY, Void.class).getStatusCode());
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM events", Long.class));
        assertEquals(HttpStatus.NO_CONTENT, restTemplate.exchange("/admin/categories/" + category,
                HttpMethod.DELETE, HttpEntity.EMPTY, Void.class).getStatusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"reject", "ownerEdit"})
    void concurrentUpdatesCannotOverwritePublication(String concurrentAction) throws Exception {
        long id = createEvent(owner);
        clock.coordinateUpdates();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var publication = executor.submit(() -> patch(adminEvent(id), Map.of("stateAction", "PUBLISH_EVENT")));
            var competing = executor.submit(() -> concurrentAction.equals("reject")
                    ? patch(adminEvent(id), Map.of("stateAction", "REJECT_EVENT"))
                    : patch(ownEvent(owner, id), Map.of("title", "Concurrent edit")));
            ResponseEntity<JsonNode> first = publication.get(20, TimeUnit.SECONDS);
            ResponseEntity<JsonNode> second = competing.get(20, TimeUnit.SECONDS);
            assertTrue((first.getStatusCode() == HttpStatus.OK && second.getStatusCode() == HttpStatus.CONFLICT)
                    || (second.getStatusCode() == HttpStatus.OK && first.getStatusCode() == HttpStatus.CONFLICT),
                    () -> "Unexpected concurrent responses: " + first + " and " + second);
            JsonNode winning = first.getStatusCode() == HttpStatus.OK ? first.getBody() : second.getBody();
            clock.stopCoordinating();
            assertEquals(winning, get(ownEvent(owner, id)));
        } finally {
            clock.stopCoordinating();
        }
    }

    private Map<String, Object> newEvent() {
        return new HashMap<>(Map.of("annotation", "A sufficiently long annotation", "description",
                "A sufficiently long description", "title", "Concert", "category", category,
                "eventDate", "2026-11-02 12:00:00", "location", Map.of("lat", 55.75, "lon", 37.62)));
    }

    private ResponseEntity<JsonNode> create(long userId, Object body) {
        return restTemplate.postForEntity(ownEvents(userId), body, JsonNode.class);
    }

    private long createEvent(long userId) {
        return post(ownEvents(userId), newEvent()).path("id").asLong();
    }

    private JsonNode post(String path, Object body) {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(path, body, JsonNode.class);
        assertStatus(HttpStatus.CREATED, response);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> patch(String path, Object body) {
        return restTemplate.exchange(path, HttpMethod.PATCH, new HttpEntity<>(body), JsonNode.class);
    }

    private JsonNode successfulPatch(String path, Object body) {
        ResponseEntity<JsonNode> response = patch(path, body);
        assertStatus(HttpStatus.OK, response);
        return response.getBody();
    }

    private JsonNode ownerAction(long eventId, String action) {
        return successfulPatch(ownEvent(owner, eventId), Map.of("stateAction", action));
    }

    private JsonNode adminAction(long eventId, String action) {
        return successfulPatch(adminEvent(eventId), Map.of("stateAction", action));
    }

    private JsonNode get(String path, Object... variables) {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity(path, JsonNode.class, variables);
        assertStatus(HttpStatus.OK, response);
        return response.getBody();
    }

    private void assertStatus(HttpStatus expected, ResponseEntity<JsonNode> response) {
        assertEquals(expected, response.getStatusCode(), () -> String.valueOf(response.getBody()));
        assertNotNull(response.getBody());
        if (expected.isError()) {
            assertEquals(expected.name(), response.getBody().path("status").asText());
        }
    }

    private List<Long> ids(JsonNode events) {
        return StreamSupport.stream(events.spliterator(), false).map(event -> event.path("id").asLong()).toList();
    }

    private String ownEvents(long userId) {
        return "/users/" + userId + "/events";
    }

    private String ownEvent(long userId, long eventId) {
        return ownEvents(userId) + "/" + eventId;
    }

    private String adminEvent(long eventId) {
        return "/admin/events/" + eventId;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {

        @Bean
        @Primary
        CoordinatedClock fixedClock() {
            return new CoordinatedClock();
        }
    }

    static class CoordinatedClock extends Clock {

        private volatile CyclicBarrier barrier;

        void coordinateUpdates() {
            barrier = new CyclicBarrier(2);
        }

        void stopCoordinating() {
            barrier = null;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), zone);
        }

        @Override
        public Instant instant() {
            CyclicBarrier current = barrier;
            if (current != null) {
                try {
                    current.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                } catch (BrokenBarrierException | TimeoutException exception) {
                    throw new IllegalStateException(exception);
                }
            }
            return Instant.parse("2026-11-02T10:00:00Z");
        }
    }
}
