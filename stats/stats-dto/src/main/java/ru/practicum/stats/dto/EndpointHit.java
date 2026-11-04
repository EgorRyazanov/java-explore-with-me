package ru.practicum.stats.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.OptBoolean;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

@JsonIgnoreProperties(value = "id", allowGetters = true)
public record EndpointHit(
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long id,
        @NotBlank
        String app,
        @NotBlank
        String uri,
        @NotBlank
        String ip,
        @NotNull
        @JsonFormat(pattern = "uuuu-MM-dd HH:mm:ss", lenient = OptBoolean.FALSE)
        LocalDateTime timestamp
) {
}
