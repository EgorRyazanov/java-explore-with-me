package ru.practicum.ewm.request.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.practicum.ewm.request.dto.ParticipationRequestDto;
import ru.practicum.ewm.request.model.ParticipationRequest;
import ru.practicum.ewm.request.model.RequestStatus;

import java.util.List;
import java.util.Optional;

public interface ParticipationRequestRepository extends JpaRepository<ParticipationRequest, Long> {

    boolean existsByEventAndRequester(long event, long requester);

    Optional<ParticipationRequest> findByIdAndRequester(long id, long requester);

    List<ParticipationRequestDto> findAllByRequesterOrderByIdAsc(long requester);

    List<ParticipationRequestDto> findAllByEventOrderByIdAsc(long event);

    List<ParticipationRequestDto> findAllByEventAndIdInOrderByIdAsc(long event, List<Long> ids);

    List<ParticipationRequestDto> findAllByEventAndStatusOrderByIdAsc(long event, RequestStatus status);

    long countByEventAndStatus(long event, RequestStatus status);

    @Query("SELECT r.event FROM ParticipationRequest r WHERE r.id = :id AND r.requester = :requester")
    Optional<Long> findEventId(@Param("id") long id, @Param("requester") long requester);

    @Query("""
            SELECT r.event AS eventId, COUNT(r) AS total FROM ParticipationRequest r
            WHERE r.event IN :eventIds AND r.status = :status GROUP BY r.event
            """)
    List<ConfirmedRequestCount> countByEvents(@Param("eventIds") List<Long> eventIds,
                                             @Param("status") RequestStatus status);

    @Modifying
    @Query("UPDATE ParticipationRequest r SET r.status = :status WHERE r.id IN :ids")
    int updateStatus(@Param("ids") List<Long> ids, @Param("status") RequestStatus status);

    @Modifying
    @Query("UPDATE ParticipationRequest r SET r.status = :newStatus WHERE r.event = :event AND r.status = :oldStatus")
    int updateEventStatus(@Param("event") long event, @Param("oldStatus") RequestStatus oldStatus,
                          @Param("newStatus") RequestStatus newStatus);

    interface ConfirmedRequestCount {

        Long getEventId();

        long getTotal();
    }
}
