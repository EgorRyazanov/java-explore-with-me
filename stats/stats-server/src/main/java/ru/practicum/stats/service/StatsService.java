package ru.practicum.stats.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.stats.dto.EndpointHit;
import ru.practicum.stats.dto.ViewStats;
import ru.practicum.stats.exception.BadRequestException;
import ru.practicum.stats.model.EndpointHitEntity;
import ru.practicum.stats.repository.EndpointHitRepository;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class StatsService {

    private final EndpointHitRepository hitRepository;

    public StatsService(EndpointHitRepository hitRepository) {
        this.hitRepository = hitRepository;
    }

    @Transactional
    public void saveHit(EndpointHit hit) {
        hitRepository.save(new EndpointHitEntity(hit.app(), hit.uri(), hit.ip(), hit.timestamp()));
    }

    @Transactional(readOnly = true)
    public List<ViewStats> getStats(LocalDateTime start, LocalDateTime end, List<String> uris, boolean unique) {
        if (start.isAfter(end)) {
            throw new BadRequestException("Начало периода должно быть не позже конца");
        }

        List<String> requestedUris = uris == null ? List.of() : uris;
        return hitRepository.getStats(start, end, requestedUris, !requestedUris.isEmpty(), unique);
    }
}
