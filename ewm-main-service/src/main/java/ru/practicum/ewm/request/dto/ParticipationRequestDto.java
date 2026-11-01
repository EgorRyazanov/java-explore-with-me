package ru.practicum.ewm.request.dto;

import ru.practicum.ewm.request.model.RequestStatus;

import java.time.LocalDateTime;

public record ParticipationRequestDto(LocalDateTime created, Long event, Long id, Long requester, RequestStatus status) {
}
