package ru.practicum.stats.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.practicum.stats.dto.ViewStats;
import ru.practicum.stats.model.EndpointHitEntity;

import java.time.LocalDateTime;
import java.util.List;

public interface EndpointHitRepository extends JpaRepository<EndpointHitEntity, Long> {

    @Query("""
            SELECT new ru.practicum.stats.dto.ViewStats(
                h.app, h.uri,
                CASE WHEN :unique = true THEN COUNT(DISTINCT h.ip) ELSE COUNT(h.id) END
            )
            FROM EndpointHitEntity h
            WHERE h.timestamp BETWEEN :start AND :end
                AND (:filterUris = false OR h.uri IN :uris)
            GROUP BY h.app, h.uri
            ORDER BY CASE WHEN :unique = true THEN COUNT(DISTINCT h.ip) ELSE COUNT(h.id) END DESC,
                h.app, h.uri
            """)
    List<ViewStats> getStats(@Param("start") LocalDateTime start,
                             @Param("end") LocalDateTime end,
                             @Param("uris") List<String> uris,
                             @Param("filterUris") boolean filterUris,
                             @Param("unique") boolean unique);
}
