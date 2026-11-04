package ru.practicum.ewm.request.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record EventRequestStatusUpdateRequest(
        @NotEmpty List<@NotNull @Positive Long> requestIds,
        @NotNull RequestDecision status
) {
}
