package ru.practicum.ewm.event.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import ru.practicum.ewm.exception.StatsUnavailableException;
import ru.practicum.stats.client.StatsClient;
import ru.practicum.stats.dto.EndpointHit;
import ru.practicum.stats.dto.ViewStats;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class EventStatisticsService {

    private static final int URI_BATCH_SIZE = 100;
    private static final LocalDateTime STATS_START = LocalDateTime.of(1970, 1, 1, 0, 0);

    private final StatsClient statsClient;
    private final Clock clock;
    private final String app;

    public EventStatisticsService(StatsClient statsClient, Clock clock, @Value("${spring.application.name}") String app) {
        this.statsClient = statsClient;
        this.clock = clock;
        this.app = app;
    }

    public Map<Long, Long> getViews(List<Long> ids) {
        Map<Long, Long> result = new HashMap<>();
        LocalDateTime end = LocalDateTime.now(clock).withNano(0);
        for (int offset = 0; offset < ids.size(); offset += URI_BATCH_SIZE) {
            Map<String, Long> uris = new HashMap<>();
            ids.subList(offset, Math.min(offset + URI_BATCH_SIZE, ids.size()))
                    .forEach(id -> uris.put("/events/" + id, id));
            try {
                List<ViewStats> stats = statsClient.getStats(STATS_START, end, List.copyOf(uris.keySet()), true);
                for (ViewStats stat : stats) {
                    Long id = uris.get(stat.uri());
                    if (app.equals(stat.app()) && id != null) {
                        result.put(id, stat.hits());
                    }
                }
            } catch (RestClientException exception) {
                throw new StatsUnavailableException(exception);
            }
        }
        return result;
    }

    public void hit(String uri, String ip) {
        try {
            statsClient.hit(new EndpointHit(null, app, uri, ip, LocalDateTime.now(clock).withNano(0)));
        } catch (RestClientException exception) {
            throw new StatsUnavailableException(exception);
        }
    }
}
