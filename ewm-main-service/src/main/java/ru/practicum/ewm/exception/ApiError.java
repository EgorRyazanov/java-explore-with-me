package ru.practicum.ewm.exception;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;

public record ApiError(
        List<String> errors,
        String message,
        String reason,
        HttpStatus status,
        @JsonFormat(pattern = "uuuu-MM-dd HH:mm:ss")
        LocalDateTime timestamp
) {
}
