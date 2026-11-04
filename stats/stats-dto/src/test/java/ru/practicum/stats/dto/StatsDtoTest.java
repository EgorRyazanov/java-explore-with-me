package ru.practicum.stats.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validation;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsDtoTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Test
    void readsHitAndIgnoresClientId() throws JsonProcessingException {
        EndpointHit hit = objectMapper.readValue("{\"id\":42,\"app\":\"ewm-main-service\","
                + "\"uri\":\"/events/1\",\"ip\":\"192.168.1.10\","
                + "\"timestamp\":\"2026-11-01 10:00:00\"}", EndpointHit.class);

        assertNull(hit.id());
        assertEquals("ewm-main-service", hit.app());
        assertEquals("/events/1", hit.uri());
        assertEquals("192.168.1.10", hit.ip());
        assertEquals(LocalDateTime.of(2026, 11, 1, 10, 0), hit.timestamp());
    }

    @Test
    void writesHitUsingApiDateFormatWithoutUnassignedId() throws JsonProcessingException {
        EndpointHit hit = new EndpointHit(null, "ewm-main-service", "/events/1", "192.168.1.10",
                LocalDateTime.of(2026, 11, 1, 10, 0));

        assertEquals(objectMapper.readTree("{\"app\":\"ewm-main-service\",\"uri\":\"/events/1\","
                + "\"ip\":\"192.168.1.10\",\"timestamp\":\"2026-11-01 10:00:00\"}"),
                objectMapper.valueToTree(hit));
    }

    @Test
    void rejectsTimestampInIsoFormat() {
        assertThrows(InvalidFormatException.class, () -> objectMapper.readValue(
                "{\"app\":\"ewm-main-service\",\"uri\":\"/events/1\",\"ip\":\"192.168.1.10\","
                        + "\"timestamp\":\"2026-11-01T10:00:00\"}", EndpointHit.class));
    }

    @Test
    void rejectsInvalidCalendarDate() {
        assertThrows(InvalidFormatException.class, () -> objectMapper.readValue(
                "{\"app\":\"ewm-main-service\",\"uri\":\"/events/1\",\"ip\":\"192.168.1.10\","
                        + "\"timestamp\":\"2026-02-30 10:00:00\"}", EndpointHit.class));
    }

    @Test
    void writesServerAssignedId() {
        EndpointHit hit = new EndpointHit(7L, "ewm-main-service", "/events/1", "192.168.1.10",
                LocalDateTime.of(2026, 11, 1, 10, 0));

        assertEquals(7L, objectMapper.valueToTree(hit).path("id").longValue());
    }

    @Test
    void writesStatsWithLongHitCount() throws JsonProcessingException {
        ViewStats stats = new ViewStats("ewm-main-service", "/events/1", 3_000_000_000L);

        assertEquals(objectMapper.readTree("{\"app\":\"ewm-main-service\",\"uri\":\"/events/1\","
                + "\"hits\":3000000000}"), objectMapper.valueToTree(stats));
    }

    @Test
    void validatesRequiredHitFields() {
        try (var factory = Validation.byDefaultProvider().configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory()) {
            var validator = factory.getValidator();
            EndpointHit invalidHit = new EndpointHit(null, null, " ", "", null);

            Set<String> invalidFields = validator.validate(invalidHit).stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .collect(Collectors.toSet());

            assertEquals(Set.of("app", "uri", "ip", "timestamp"), invalidFields);

            EndpointHit validHit = new EndpointHit(null, "ewm-main-service", "/events/1", "192.168.1.10",
                    LocalDateTime.of(2026, 11, 1, 10, 0));
            assertTrue(validator.validate(validHit).isEmpty());
        }
    }
}
