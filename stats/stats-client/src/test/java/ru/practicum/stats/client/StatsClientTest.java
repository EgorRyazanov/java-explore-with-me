package ru.practicum.stats.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.client.MockServerRestTemplateCustomizer;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import ru.practicum.stats.dto.EndpointHit;
import ru.practicum.stats.dto.ViewStats;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class StatsClientTest {

    private static final String SERVER_URL = "http://stats-server:9090";
    private static final String STATS_URL = SERVER_URL
            + "/stats?start=2026-11-01%2010%3A00%3A00&end=2026-11-01%2011%3A00%3A00&unique=false";
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 1, 10, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 1, 11, 0);

    private MockRestServiceServer server;
    private StatsClient client;

    @BeforeEach
    void setUp() {
        var customizer = new MockServerRestTemplateCustomizer();
        client = new StatsClient(SERVER_URL, new RestTemplateBuilder(customizer));
        server = customizer.getServer();
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void sendsHitUsingApiJsonFormat() {
        server.expect(requestTo(SERVER_URL + "/hit"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"app\":\"ewm-main-service\",\"uri\":\"/events/1\","
                        + "\"ip\":\"192.168.1.10\",\"timestamp\":\"2026-11-01 10:00:00\"}", true))
                .andRespond(withStatus(HttpStatus.CREATED));

        client.hit(new EndpointHit(null, "ewm-main-service", "/events/1", "192.168.1.10", START));
    }

    @Test
    void encodesDatesAndEachUriOnceAndReadsTypedStats() {
        server.expect(requestTo(SERVER_URL
                        + "/stats?start=2026-11-01%2010%3A00%3A00&end=2026-11-01%2011%3A00%3A00&unique=true"
                        + "&uris=%2Fevents%2Fa%2Bb%20%26x%3D50%25%3Ftag%3D%D1%82%D0%B5%D1%81%D1%82"
                        + "&uris=%2Fevents%2F2"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"app\":\"ewm-main-service\",\"uri\":\"/events/2\","
                        + "\"hits\":3000000000}]", MediaType.APPLICATION_JSON));

        assertEquals(List.of(new ViewStats("ewm-main-service", "/events/2", 3_000_000_000L)),
                client.getStats(START, END, List.of("/events/a+b &x=50%?tag=тест", "/events/2"), true));
    }

    @ParameterizedTest
    @MethodSource("emptyUriLists")
    void omitsUriFilterAndReadsEmptyStats(List<String> uris) {
        server.expect(requestTo(STATS_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertEquals(List.of(), client.getStats(START, END, uris, false));
    }

    static Stream<List<String>> emptyUriLists() {
        return Stream.of(null, List.of());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 500})
    void propagatesStatsHttpErrors(int status) {
        server.expect(requestTo(STATS_URL))
                .andRespond(withStatus(HttpStatus.valueOf(status))
                        .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"Stats unavailable\"}"));

        HttpStatusCodeException exception = assertThrows(HttpStatusCodeException.class,
                () -> client.getStats(START, END, null, false));

        assertEquals(status, exception.getStatusCode().value());
        assertEquals("{\"error\":\"Stats unavailable\"}", exception.getResponseBodyAsString());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 500})
    void propagatesHitHttpErrors(int status) {
        server.expect(requestTo(SERVER_URL + "/hit"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));

        HttpStatusCodeException exception = assertThrows(HttpStatusCodeException.class,
                () -> client.hit(new EndpointHit(null, "ewm-main-service", "/events/1", "192.168.1.10", START)));

        assertEquals(status, exception.getStatusCode().value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{\"unexpected\":true}"})
    void rejectsInvalidStatsResponse(String body) {
        server.expect(requestTo(STATS_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThrows(RestClientException.class, () -> client.getStats(START, END, null, false));
    }

    @Test
    void propagatesStatsConnectionFailure() {
        server.expect(requestTo(STATS_URL)).andRespond(withException(new IOException("Connection failed")));

        assertThrows(ResourceAccessException.class, () -> client.getStats(START, END, null, false));
    }

    @Test
    void propagatesHitConnectionFailure() {
        server.expect(requestTo(SERVER_URL + "/hit")).andRespond(withException(new IOException("Connection failed")));

        assertThrows(ResourceAccessException.class,
                () -> client.hit(new EndpointHit(null, "ewm-main-service", "/events/1", "192.168.1.10", START)));
    }
}
