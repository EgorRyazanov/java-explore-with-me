package ru.practicum.ewm.compilation.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.practicum.ewm.compilation.model.Compilation;

import java.util.List;
import java.util.Optional;

public interface CompilationRepository extends JpaRepository<Compilation, Long> {

    @Query(value = """
            SELECT id, title, pinned FROM compilations
            WHERE (:allPinned = TRUE OR pinned = :pinned)
            ORDER BY id LIMIT :size OFFSET :offset
            """, nativeQuery = true)
    List<Compilation> findCompilations(@Param("allPinned") boolean allPinned, @Param("pinned") boolean pinned,
                                        @Param("offset") int from, @Param("size") int size);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Compilation c WHERE c.id = :id")
    Optional<Compilation> findByIdForUpdate(@Param("id") long id);

    @Query(value = """
            SELECT compilation_id AS "compilationId", event_id AS "eventId"
            FROM compilation_events WHERE compilation_id IN (:ids)
            ORDER BY compilation_id, event_id
            """, nativeQuery = true)
    List<CompilationEventReference> findEventReferences(@Param("ids") List<Long> ids);

    @Modifying
    @Query(value = """
            INSERT INTO compilation_events (compilation_id, event_id)
            SELECT :id, e.id FROM events e WHERE e.id IN (:events)
            """, nativeQuery = true)
    int addEvents(@Param("id") long id, @Param("events") List<Long> events);

    @Modifying
    @Query(value = "DELETE FROM compilation_events WHERE compilation_id = :id", nativeQuery = true)
    int removeEvents(@Param("id") long id);

    @Modifying
    @Query("DELETE FROM Compilation c WHERE c.id = :id")
    int deleteCompilation(@Param("id") long id);

    interface CompilationEventReference {

        Long getCompilationId();

        Long getEventId();
    }
}
