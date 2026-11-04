package ru.practicum.ewm.request.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "participation_requests")
public class ParticipationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long event;

    @Column(name = "requester_id", nullable = false)
    private Long requester;

    @Column(nullable = false, updatable = false)
    private LocalDateTime created;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RequestStatus status;

    protected ParticipationRequest() {
    }

    public ParticipationRequest(Long event, Long requester, LocalDateTime created, RequestStatus status) {
        this.event = event;
        this.requester = requester;
        this.created = created;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public Long getEvent() {
        return event;
    }

    public Long getRequester() {
        return requester;
    }

    public LocalDateTime getCreated() {
        return created;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public void cancel() {
        status = RequestStatus.CANCELED;
    }
}
