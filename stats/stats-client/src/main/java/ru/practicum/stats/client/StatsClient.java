package ru.practicum.stats.client;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriComponentsBuilder;
import ru.practicum.stats.dto.EndpointHit;
import ru.practicum.stats.dto.ViewStats;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StatsClient {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private final RestTemplate restTemplate;

    public StatsClient(String serverUrl, RestTemplateBuilder builder) {
        restTemplate = builder.uriTemplateHandler(new DefaultUriBuilderFactory(serverUrl))
                .requestFactory(SimpleClientHttpRequestFactory::new)
                .setConnectTimeout(Duration.ofSeconds(2))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void hit(EndpointHit hit) {
        restTemplate.postForEntity("/hit", hit, Void.class);
    }

    public List<ViewStats> getStats(LocalDateTime start, LocalDateTime end, List<String> uris, boolean unique) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromPath("/stats")
                .queryParam("start", "{start}")
                .queryParam("end", "{end}")
                .queryParam("unique", "{unique}");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("start", start.format(DATE_TIME_FORMATTER));
        parameters.put("end", end.format(DATE_TIME_FORMATTER));
        parameters.put("unique", unique);

        if (uris != null) {
            for (int index = 0; index < uris.size(); index++) {
                String parameter = "uri" + index;
                uriBuilder.queryParam("uris", "{" + parameter + "}");
                parameters.put(parameter, uris.get(index));
            }
        }

        ViewStats[] response = restTemplate.getForObject(uriBuilder.build().toUriString(), ViewStats[].class, parameters);
        if (response == null) {
            throw new RestClientException("Сервис статистики вернул пустое тело ответа вместо JSON-массива");
        }
        return List.of(response);
    }
}
