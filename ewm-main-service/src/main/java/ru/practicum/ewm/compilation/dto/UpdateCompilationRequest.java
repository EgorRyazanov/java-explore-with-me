package ru.practicum.ewm.compilation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record UpdateCompilationRequest(Set<@NotNull @Positive Long> events, Boolean pinned,
                                       @Size(min = 1, max = 50) @Pattern(regexp = "(?s).*\\S.*") String title) {
}
