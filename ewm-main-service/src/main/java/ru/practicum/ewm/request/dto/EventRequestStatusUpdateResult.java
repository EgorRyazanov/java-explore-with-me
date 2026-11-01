package ru.practicum.ewm.request.dto;

import java.util.List;

public record EventRequestStatusUpdateResult(List<ParticipationRequestDto> confirmedRequests,
                                            List<ParticipationRequestDto> rejectedRequests) {
}
