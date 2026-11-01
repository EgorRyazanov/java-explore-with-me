package ru.practicum.ewm.compilation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record NewCompilationDto(Set<@NotNull @Positive Long> events, Boolean pinned,
                                 @NotBlank @Size(min = 1, max = 50) String title) {
}
