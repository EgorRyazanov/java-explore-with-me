package ru.practicum.stats;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.practicum.stats.client.StatsClient;
import ru.practicum.stats.dto.EndpointHit;
import ru.practicum.stats.dto.ViewStats;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class StatsServerTest {

    private static final String STATS_URL = "/stats?start={start}&end={end}";
    private static final String START = "2026-11-01 10:00:00";
    private static final String END = "2026-11-01 11:00:00";

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

    @BeforeEach
    void clearHits() {
        jdbcTemplate.update("DELETE FROM endpoint_hits");
    }

    @Test
    void savesHitAndReturnsCreated() {
        EndpointHit hit = new EndpointHit(42L, "ewm-main-service", "/events/1", "192.168.1.10",
                LocalDateTime.of(2026, 11, 1, 10, 0));

        ResponseEntity<String> response = restTemplate.postForEntity("/hit", hit, String.class);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNull(response.getBody());
        Map<String, Object> saved = jdbcTemplate.queryForMap("SELECT * FROM endpoint_hits");
        assertEquals("ewm-main-service", saved.get("app"));
        assertEquals("/events/1", saved.get("uri"));
        assertEquals("192.168.1.10", saved.get("ip"));
        assertEquals(Timestamp.valueOf("2026-11-01 10:00:00"), saved.get("hit_timestamp"));
        assertTrue(((Number) saved.get("id")).longValue() > 0);
        assertNotEquals(42L, ((Number) saved.get("id")).longValue());
    }

    @Test
    void savesRepeatedVisitsSeparately() {
        EndpointHit hit = new EndpointHit(null, "ewm-main-service", "/events/1", "192.168.1.10",
                LocalDateTime.of(2026, 11, 1, 10, 0));

        assertEquals(HttpStatus.CREATED, restTemplate.postForEntity("/hit", hit, String.class).getStatusCode());
        assertEquals(HttpStatus.CREATED, restTemplate.postForEntity("/hit", hit, String.class).getStatusCode());
        assertEquals(2L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM endpoint_hits", Long.class));
    }

    @ParameterizedTest
    @MethodSource("invalidHits")
    void rejectsInvalidHitFields(EndpointHit hit) {
        assertEquals(HttpStatus.BAD_REQUEST, restTemplate.postForEntity("/hit", hit, String.class).getStatusCode());
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM endpoint_hits", Long.class));
    }

    static Stream<EndpointHit> invalidHits() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 11, 1, 10, 0);
        return Stream.of(
                new EndpointHit(null, null, "/events/1", "192.168.1.10", timestamp),
                new EndpointHit(null, "ewm-main-service", null, "192.168.1.10", timestamp),
                new EndpointHit(null, "ewm-main-service", "/events/1", null, timestamp),
                new EndpointHit(null, "ewm-main-service", "/events/1", "192.168.1.10", null),
                new EndpointHit(null, " ", "/events/1", "192.168.1.10", timestamp),
                new EndpointHit(null, "ewm-main-service", " ", "192.168.1.10", timestamp),
                new EndpointHit(null, "ewm-main-service", "/events/1", " ", timestamp)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-02-30 10:00:00", "2026-11-01T10:00:00"})
    void rejectsInvalidTimestamp(String timestamp) {
        String json = "{\"app\":\"ewm-main-service\",\"uri\":\"/events/1\",\"ip\":\"192.168.1.10\","
                + "\"timestamp\":\"" + timestamp + "\"}";

        assertEquals(HttpStatus.BAD_REQUEST, postJson(json).getStatusCode());
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM endpoint_hits", Long.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{}"})
    void rejectsMissingOrMalformedBody(String json) {
        assertEquals(HttpStatus.BAD_REQUEST, postJson(json).getStatusCode());
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM endpoint_hits", Long.class));
    }

    @Test
    void reportsHealthyApplicationAndDatabase() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health", Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().get("status"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "&unique=false"})
    void countsVisitsByAppAndUriInDescendingOrder(String query) {
        seedStats();

        ResponseEntity<ViewStats[]> response = restTemplate.getForEntity(
                STATS_URL + query, ViewStats[].class, START, END);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(new ViewStats[]{
                new ViewStats("ewm-main-service", "/events/1", 5L),
                new ViewStats("ewm-main-service", "/events/2", 3L),
                new ViewStats("other-service", "/events/1", 1L)
        }, response.getBody());
    }

    @Test
    void countsUniqueIpsWithinEachAppAndUri() {
        seedStats();

        ResponseEntity<ViewStats[]> response = restTemplate.getForEntity(
                STATS_URL + "&unique=true", ViewStats[].class, START, END);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(new ViewStats[]{
                new ViewStats("ewm-main-service", "/events/2", 3L),
                new ViewStats("ewm-main-service", "/events/1", 2L),
                new ViewStats("other-service", "/events/1", 1L)
        }, response.getBody());
    }

    @ParameterizedTest
    @CsvSource({"false,5", "true,2"})
    void filtersUriForBothCountingModes(boolean unique, long expectedHits) {
        seedStats();

        ResponseEntity<ViewStats[]> response = restTemplate.getForEntity(
                STATS_URL + "&uris={uri}&unique={unique}", ViewStats[].class, START, END, "/events/1", unique);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(new ViewStats[]{
                new ViewStats("ewm-main-service", "/events/1", expectedHits),
                new ViewStats("other-service", "/events/1", 1L)
        }, response.getBody());
    }

    @Test
    void acceptsSeveralUriParameters() {
        seedStats();
        insertHit("ewm-main-service", "/events/3", "192.168.1.10", "2026-11-01 10:30:00");

        ResponseEntity<ViewStats[]> response = restTemplate.getForEntity(
                STATS_URL + "&uris={first}&uris={second}", ViewStats[].class,
                START, END, "/events/1", "/events/2");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(new ViewStats[]{
                new ViewStats("ewm-main-service", "/events/1", 5L),
                new ViewStats("ewm-main-service", "/events/2", 3L),
                new ViewStats("other-service", "/events/1", 1L)
        }, response.getBody());
    }

    @Test
    void treatsEmptyUriListAsNoFilter() {
        seedStats();

        ResponseEntity<ViewStats[]> response = restTemplate.getForEntity(
                STATS_URL + "&uris=", ViewStats[].class, START, END);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(new ViewStats[]{
                new ViewStats("ewm-main-service", "/events/1", 5L),
                new ViewStats("ewm-main-service", "/events/2", 3L),
                new ViewStats("other-service", "/events/1", 1L)
        }, response.getBody());
    }

    @Test
    void returnsEmptyStatsOutsidePeriod() {
        seedStats();

        ResponseEntity<ViewStats[]> response = restTemplate.getForEntity(
                STATS_URL, ViewStats[].class, "2026-11-01 12:00:00", "2026-11-01 13:00:00");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(new ViewStats[0], response.getBody());
    }

    @Test
    void returnsEmptyStatsForUnknownUri() {
        seedStats();

        ResponseEntity<ViewStats[]> response = restTemplate.getForEntity(
                STATS_URL + "&uris={uri}", ViewStats[].class, START, END, "/missing");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(new ViewStats[0], response.getBody());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-01 10:00:00", "2026-11-01 11:00:00"})
    void includesBothPeriodBoundariesAndAcceptsEqualDates(String timestamp) {
        seedStats();

        ResponseEntity<ViewStats[]> response = restTemplate.getForEntity(
                STATS_URL, ViewStats[].class, timestamp, timestamp);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(new ViewStats[]{
                new ViewStats("ewm-main-service", "/events/1", 1L)
        }, response.getBody());
    }

    @Test
    void rejectsReversedPeriod() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity(STATS_URL, JsonNode.class, END, START);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().path("error").isTextual());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-date", "2026-02-30 10:00:00", "2026-11-01T10:00:00",
            "2026-11-01 10:00:00+03:00"})
    void rejectsInvalidStatsDates(String timestamp) {
        assertEquals(HttpStatus.BAD_REQUEST,
                restTemplate.getForEntity(STATS_URL, String.class, timestamp, END).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                restTemplate.getForEntity(STATS_URL, String.class, START, timestamp).getStatusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/stats", "/stats?start=2026-11-01T10:00:00", "/stats?end=2026-11-01T11:00:00"})
    void requiresBothPeriodParameters(String url) {
        assertEquals(HttpStatus.BAD_REQUEST, restTemplate.getForEntity(url, String.class).getStatusCode());
    }

    @Test
    void rejectsInvalidUniqueFlag() {
        assertEquals(HttpStatus.BAD_REQUEST,
                restTemplate.getForEntity(STATS_URL + "&unique=unknown", String.class, START, END).getStatusCode());
    }

    @Test
    void savesAndReadsStatsThroughHttpClient() {
        StatsClient client = new StatsClient(restTemplate.getRootUri(), new RestTemplateBuilder());
        LocalDateTime start = LocalDateTime.of(2026, 11, 1, 10, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 1, 11, 0);
        String uri = "/events/a+b &x=50%?tag=тест";

        client.hit(new EndpointHit(null, "ewm-main-service", uri, "192.168.1.10", start));
        client.hit(new EndpointHit(null, "ewm-main-service", uri, "192.168.1.10", start));
        client.hit(new EndpointHit(null, "ewm-main-service", uri, "192.168.1.20", end));

        assertEquals(List.of(new ViewStats("ewm-main-service", uri, 3L)),
                client.getStats(start, end, null, false));
        assertEquals(List.of(new ViewStats("ewm-main-service", uri, 2L)),
                client.getStats(start, end, List.of(uri), true));
        assertEquals(List.of(), client.getStats(start, end, List.of("/missing"), false));
        assertEquals(3L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM endpoint_hits", Long.class));
    }

    @Test
    void preservesCommaInsideSingleUriFilter() {
        insertHit("ewm-main-service", "/events/a,b", "192.168.1.10", START);
        insertHit("ewm-main-service", "/events/a", "192.168.1.20", START);
        StatsClient client = new StatsClient(restTemplate.getRootUri(), new RestTemplateBuilder());

        assertEquals(List.of(new ViewStats("ewm-main-service", "/events/a,b", 1L)),
                client.getStats(LocalDateTime.of(2026, 11, 1, 10, 0),
                        LocalDateTime.of(2026, 11, 1, 11, 0), List.of("/events/a,b"), false));
    }

    private void seedStats() {
        insertHit("ewm-main-service", "/events/1", "192.168.1.10", "2026-11-01 10:00:00");
        insertHit("ewm-main-service", "/events/1", "192.168.1.10", "2026-11-01 10:05:00");
        insertHit("ewm-main-service", "/events/1", "192.168.1.10", "2026-11-01 10:10:00");
        insertHit("ewm-main-service", "/events/1", "192.168.1.10", "2026-11-01 10:15:00");
        insertHit("ewm-main-service", "/events/1", "192.168.1.20", "2026-11-01 11:00:00");
        insertHit("ewm-main-service", "/events/2", "192.168.1.10", "2026-11-01 10:20:00");
        insertHit("ewm-main-service", "/events/2", "192.168.1.20", "2026-11-01 10:25:00");
        insertHit("ewm-main-service", "/events/2", "192.168.1.30", "2026-11-01 10:30:00");
        insertHit("other-service", "/events/1", "192.168.1.10", "2026-11-01 10:40:00");
        insertHit("ewm-main-service", "/events/1", "192.168.1.40", "2026-11-01 09:59:59");
        insertHit("ewm-main-service", "/events/1", "192.168.1.50", "2026-11-01 11:00:01");
    }

    private void insertHit(String app, String uri, String ip, String timestamp) {
        jdbcTemplate.update("INSERT INTO endpoint_hits (app, uri, ip, hit_timestamp) VALUES (?, ?, ?, ?)",
                app, uri, ip, Timestamp.valueOf(timestamp));
    }

    private ResponseEntity<String> postJson(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/hit", new HttpEntity<>(json, headers), String.class);
    }
}
