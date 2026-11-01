package ru.practicum.ewm.event.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record NewEventDto(
        @NotBlank @Size(min = 20, max = 2000) String annotation,
        @NotNull Long category,
        @NotBlank @Size(min = 20, max = 7000) String description,
        @NotNull @JsonFormat(pattern = "uuuu-MM-dd HH:mm:ss", lenient = com.fasterxml.jackson.annotation.OptBoolean.FALSE)
        LocalDateTime eventDate,
        @Valid @NotNull Location location,
        Boolean paid,
        @PositiveOrZero Integer participantLimit,
        Boolean requestModeration,
        @NotBlank @Size(min = 3, max = 120) String title
) {
}
