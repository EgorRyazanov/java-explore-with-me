package ru.practicum.ewm.request;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.practicum.stats.client.StatsClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@Import(ParticipationRequestApiTest.FixedTime.class)
class ParticipationRequestApiTest {

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
    private long requester;
    private long other;
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
        jdbcTemplate.execute("TRUNCATE users, categories RESTART IDENTITY CASCADE");
        owner = user("owner");
        requester = user("requester");
        other = user("other");
        category = post("/admin/categories", Map.of("name", "Concerts")).path("id").asLong();
    }

    @Test
    void createsPendingRequestWithServerOwnedFields() {
        long event = event(3, true, true);
        JsonNode request = request(requester, event);
        assertEquals(5, request.size());
        assertTrue(request.path("id").asLong() > 0);
        assertEquals(event, request.path("event").asLong());
        assertEquals(requester, request.path("requester").asLong());
        assertEquals("PENDING", request.path("status").asText());
        assertEquals(LocalDateTime.of(2026, 11, 2, 10, 0, 0, 123_456_000),
                LocalDateTime.parse(request.path("created").asText()));
        assertEquals(request, get("/users/" + requester + "/requests").get(0));
        assertEquals(0, ownEvent(event).path("confirmedRequests").asLong());
    }

    @Test
    void automaticallyConfirmsWithoutModerationOrLimit() {
        for (long event : List.of(event(2, false, true), event(0, true, true))) {
            assertEquals("CONFIRMED", request(requester, event).path("status").asText());
            assertEquals(1, ownEvent(event).path("confirmedRequests").asLong());
        }
    }

    @Test
    void rejectsDuplicateEvenAfterCancellation() {
        long event = event(3, true, true);
        long id = request(requester, event).path("id").asLong();
        assertStatus(HttpStatus.CONFLICT, create(requester, event));
        cancel(requester, id);
        assertStatus(HttpStatus.CONFLICT, create(requester, event));
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM participation_requests", Long.class));
    }

    @Test
    void rejectsOwnUnpublishedAndMissingResources() {
        long pending = event(3, true, false);
        assertStatus(HttpStatus.CONFLICT, create(requester, pending));
        patch("/admin/events/" + pending, Map.of("stateAction", "REJECT_EVENT"));
        assertStatus(HttpStatus.CONFLICT, create(requester, pending));
        long published = event(3, true, true);
        assertStatus(HttpStatus.CONFLICT, create(owner, published));
        assertStatus(HttpStatus.NOT_FOUND, create(999, published));
        assertStatus(HttpStatus.NOT_FOUND, create(requester, 999));
    }

    @Test
    void limitCountsConfirmedRequestsAndCancellationFreesAPlace() {
        long event = event(1, false, true);
        long id = request(requester, event).path("id").asLong();
        assertStatus(HttpStatus.CONFLICT, create(other, event));
        cancel(requester, id);
        assertEquals(0, ownEvent(event).path("confirmedRequests").asLong());
        assertEquals("CONFIRMED", request(other, event).path("status").asText());
        assertEquals(1, ownEvent(event).path("confirmedRequests").asLong());
        long moderated = event(1, true, true);
        assertEquals("PENDING", request(requester, moderated).path("status").asText());
        assertEquals("PENDING", request(other, moderated).path("status").asText());
    }

    @Test
    void listsOnlyOwnRequestsAndOnlyTheAuthorsEventRequests() {
        long first = event(4, true, true);
        long second = event(4, true, true);
        long a = request(requester, first).path("id").asLong();
        long b = request(other, first).path("id").asLong();
        long c = request(requester, second).path("id").asLong();
        assertEquals(List.of(a, c), ids(get("/users/" + requester + "/requests")));
        assertEquals(List.of(a, b), ids(get(eventRequests(owner, first))));
        assertEquals(0, get("/users/" + owner + "/requests").size());
        assertStatus(HttpStatus.NOT_FOUND, restTemplate.getForEntity(eventRequests(other, first), JsonNode.class));
        assertStatus(HttpStatus.NOT_FOUND, restTemplate.getForEntity(eventRequests(owner, 999), JsonNode.class));
        assertStatus(HttpStatus.NOT_FOUND, restTemplate.getForEntity("/users/999/requests", JsonNode.class));
    }

    @Test
    void cancellationIsPrivateAndIdempotent() {
        long event = event(4, true, true);
        JsonNode before = request(requester, event);
        long id = before.path("id").asLong();
        assertStatus(HttpStatus.NOT_FOUND, exchangePatch("/users/" + other + "/requests/" + id + "/cancel", null));
        JsonNode canceled = cancel(requester, id);
        assertEquals("CANCELED", canceled.path("status").asText());
        assertEquals(before.path("created"), canceled.path("created"));
        assertEquals(canceled, cancel(requester, id));
        assertStatus(HttpStatus.NOT_FOUND, exchangePatch("/users/" + requester + "/requests/999/cancel", null));
    }

    @Test
    void confirmsSelectedRequestsAndRejectsAllRemainingWhenLimitIsReached() {
        long event = event(1, true, true);
        long a = request(requester, event).path("id").asLong();
        long b = request(other, event).path("id").asLong();
        JsonNode result = decide(event, List.of(a), "CONFIRMED");
        assertEquals(List.of(a), ids(result.path("confirmedRequests")));
        assertEquals(List.of(b), ids(result.path("rejectedRequests")));
        assertEquals("REJECTED", get("/users/" + other + "/requests").get(0).path("status").asText());
        assertEquals(1, ownEvent(event).path("confirmedRequests").asLong());
        assertStatus(HttpStatus.CONFLICT, create(user("late"), event));
        assertStatus(HttpStatus.CONFLICT, decisionResponse(owner, event, List.of(b), "CONFIRMED"));
    }

    @Test
    void oversizedBatchConfirmsOnlyAvailablePlacesInIdOrderAndDeduplicatesIds() {
        long event = event(1, true, true);
        long a = request(requester, event).path("id").asLong();
        long b = request(other, event).path("id").asLong();
        JsonNode result = decide(event, List.of(b, a, b), "CONFIRMED");
        assertEquals(List.of(a), ids(result.path("confirmedRequests")));
        assertEquals(List.of(b), ids(result.path("rejectedRequests")));
    }

    @Test
    void rejectionDoesNotConsumePlacesAndPartialConfirmationLeavesOthersPending() {
        long event = event(3, true, true);
        long a = request(requester, event).path("id").asLong();
        long b = request(other, event).path("id").asLong();
        JsonNode first = decide(event, List.of(a), "CONFIRMED");
        assertEquals(0, first.path("rejectedRequests").size());
        assertEquals("PENDING", get("/users/" + other + "/requests").get(0).path("status").asText());
        JsonNode rejected = decide(event, List.of(b), "REJECTED");
        assertEquals(List.of(b), ids(rejected.path("rejectedRequests")));
        assertEquals(0, rejected.path("confirmedRequests").size());
        assertEquals(1, ownEvent(event).path("confirmedRequests").asLong());
        assertEquals("CANCELED", cancel(other, b).path("status").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMED", "REJECTED", "CANCELED"})
    void entireBatchRollsBackIfAnySelectedRequestIsNotPending(String status) {
        long event = event(4, true, true);
        long a = request(requester, event).path("id").asLong();
        long b = request(other, event).path("id").asLong();
        if (status.equals("CANCELED")) {
            cancel(requester, a);
        } else {
            decide(event, List.of(a), status);
        }
        assertStatus(HttpStatus.CONFLICT, decisionResponse(owner, event, List.of(b, a), "CONFIRMED"));
        assertEquals("PENDING", get("/users/" + other + "/requests").get(0).path("status").asText());
    }

    @Test
    void foreignOrMissingRequestAndForeignOwnerCannotChangeBatch() {
        long event = event(3, true, true);
        long otherEvent = event(3, true, true);
        long a = request(requester, event).path("id").asLong();
        long b = request(other, otherEvent).path("id").asLong();
        assertStatus(HttpStatus.NOT_FOUND, decisionResponse(owner, event, List.of(a, b), "CONFIRMED"));
        assertStatus(HttpStatus.NOT_FOUND, decisionResponse(owner, event, List.of(a, 999L), "CONFIRMED"));
        assertStatus(HttpStatus.NOT_FOUND, decisionResponse(other, event, List.of(a), "CONFIRMED"));
        assertEquals("PENDING", get("/users/" + requester + "/requests").get(0).path("status").asText());
    }

    @Test
    void confirmationIsANoOpWhenItIsNotRequired() {
        for (long event : List.of(event(0, true, true), event(2, false, true))) {
            long a = request(requester, event).path("id").asLong();
            JsonNode result = decide(event, List.of(a), "CONFIRMED");
            assertEquals(0, result.path("confirmedRequests").size());
            assertEquals(0, result.path("rejectedRequests").size());
            assertEquals(1, ownEvent(event).path("confirmedRequests").asLong());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"requestIds\":[],\"status\":\"CONFIRMED\"}",
            "{\"requestIds\":[1]}", "{\"requestIds\":[1],\"status\":\"PENDING\"}",
            "{\"requestIds\":[null],\"status\":\"CONFIRMED\"}",
            "{\"requestIds\":[0],\"status\":\"REJECTED\"}"})
    void validatesDecisionBody(String body) {
        long event = event(3, true, true);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        assertStatus(HttpStatus.BAD_REQUEST, restTemplate.exchange(eventRequests(owner, event), HttpMethod.PATCH,
                new HttpEntity<>(body, headers), JsonNode.class));
    }

    @Test
    void validatesRequiredEventIdAndNumericPaths() {
        assertStatus(HttpStatus.BAD_REQUEST, restTemplate.postForEntity("/users/" + requester + "/requests", null, JsonNode.class));
        assertStatus(HttpStatus.BAD_REQUEST, restTemplate.postForEntity("/users/" + requester + "/requests?eventId=bad", null, JsonNode.class));
        assertStatus(HttpStatus.BAD_REQUEST, restTemplate.getForEntity("/users/bad/requests", JsonNode.class));
    }

    @Test
    void countsAreBatchedForEventListsAndDoNotExposeRequesterData() {
        long first = event(2, false, true);
        long second = event(0, true, true);
        long third = event(3, true, true);
        request(requester, first);
        request(other, first);
        request(requester, second);
        request(requester, third);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        JsonNode admin = get("/admin/events");
        assertEquals(List.of(2L, 1L, 0L), values(admin, "confirmedRequests"));
        assertEquals(2, statistics.getPrepareStatementCount());
        statistics.clear();
        assertEquals(List.of(2L, 1L, 0L), values(get("/users/" + owner + "/events"), "confirmedRequests"));
        assertEquals(3, statistics.getPrepareStatementCount());
        statistics.clear();
        JsonNode own = get("/users/" + requester + "/requests");
        assertEquals(2, statistics.getPrepareStatementCount());
        assertEquals(5, own.get(0).size());
        assertEquals(2, patch("/admin/events/" + first, Map.of("title", "Changed")).path("confirmedRequests").asInt());
    }

    @Test
    void deletingRequesterUpdatesCountsAndDeletingOwnerCascadesRequests() {
        long event = event(3, false, true);
        request(requester, event);
        request(other, event);
        restTemplate.delete("/admin/users/" + requester);
        assertEquals(1, ownEvent(event).path("confirmedRequests").asLong());
        restTemplate.delete("/admin/users/" + owner);
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM participation_requests", Long.class));
    }

    @Test
    void simultaneousAutomaticRequestsCannotExceedLastPlace() throws Exception {
        long event = event(1, false, true);
        List<Long> users = new ArrayList<>(List.of(requester, other));
        for (int i = 0; i < 6; i++) {
            users.add(user("racer" + i));
        }
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(users.size())) {
            var futures = users.stream().map(userId -> executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return create(userId, event);
            })).toList();
            start.countDown();
            int created = 0;
            for (var future : futures) {
                ResponseEntity<JsonNode> response = future.get(20, TimeUnit.SECONDS);
                if (response.getStatusCode() == HttpStatus.CREATED) {
                    created++;
                } else {
                    assertStatus(HttpStatus.CONFLICT, response);
                }
            }
            assertEquals(1, created);
        }
        assertEquals(1, ownEvent(event).path("confirmedRequests").asInt());
    }

    @Test
    void simultaneousModerationCannotConfirmMoreThanTheLimit() throws Exception {
        long event = event(1, true, true);
        long a = request(requester, event).path("id").asLong();
        long b = request(other, event).path("id").asLong();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return decisionResponse(owner, event, List.of(a), "CONFIRMED");
            });
            var second = executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return decisionResponse(owner, event, List.of(b), "CONFIRMED");
            });
            start.countDown();
            List<Integer> statuses = List.of(first.get(20, TimeUnit.SECONDS).getStatusCode().value(),
                    second.get(20, TimeUnit.SECONDS).getStatusCode().value());
            assertTrue(statuses.contains(200) && statuses.contains(409), statuses.toString());
        }
        assertEquals(1, ownEvent(event).path("confirmedRequests").asInt());
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM participation_requests WHERE status='REJECTED'", Long.class));
    }

    private long user(String name) {
        return post("/admin/users", Map.of("name", name, "email", name + "@example.org")).path("id").asLong();
    }

    private long event(int limit, boolean moderation, boolean publish) {
        long id = post("/users/" + owner + "/events", Map.of("annotation", "A sufficiently long annotation",
                "description", "A sufficiently long description", "title", "Concert", "category", category,
                "eventDate", "2026-11-03 12:00:00", "location", Map.of("lat", 55.75, "lon", 37.62),
                "participantLimit", limit, "requestModeration", moderation)).path("id").asLong();
        if (publish) {
            patch("/admin/events/" + id, Map.of("stateAction", "PUBLISH_EVENT"));
        }
        return id;
    }

    private ResponseEntity<JsonNode> create(long userId, long eventId) {
        return restTemplate.postForEntity("/users/" + userId + "/requests?eventId=" + eventId, null, JsonNode.class);
    }

    private JsonNode request(long userId, long eventId) {
        ResponseEntity<JsonNode> response = create(userId, eventId);
        assertStatus(HttpStatus.CREATED, response);
        return response.getBody();
    }

    private JsonNode cancel(long userId, long requestId) {
        return patch("/users/" + userId + "/requests/" + requestId + "/cancel", null);
    }

    private JsonNode ownEvent(long eventId) {
        return get("/users/" + owner + "/events/" + eventId);
    }

    private String eventRequests(long userId, long eventId) {
        return "/users/" + userId + "/events/" + eventId + "/requests";
    }

    private ResponseEntity<JsonNode> decisionResponse(long userId, long eventId, List<Long> ids, String status) {
        return exchangePatch(eventRequests(userId, eventId), Map.of("requestIds", ids, "status", status));
    }

    private JsonNode decide(long eventId, List<Long> ids, String status) {
        ResponseEntity<JsonNode> response = decisionResponse(owner, eventId, ids, status);
        assertStatus(HttpStatus.OK, response);
        return response.getBody();
    }

    private JsonNode post(String path, Object body) {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(path, body, JsonNode.class);
        assertStatus(HttpStatus.CREATED, response);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> exchangePatch(String path, Object body) {
        return restTemplate.exchange(path, HttpMethod.PATCH, new HttpEntity<>(body), JsonNode.class);
    }

    private JsonNode patch(String path, Object body) {
        ResponseEntity<JsonNode> response = exchangePatch(path, body);
        assertStatus(HttpStatus.OK, response);
        return response.getBody();
    }

    private JsonNode get(String path) {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity(path, JsonNode.class);
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

    private List<Long> ids(JsonNode array) {
        return values(array, "id");
    }

    private List<Long> values(JsonNode array, String field) {
        return StreamSupport.stream(array.spliterator(), false).map(value -> value.path(field).asLong()).toList();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-02T10:00:00.123456789Z"), ZoneOffset.UTC);
        }
    }
}
