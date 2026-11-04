package ru.practicum.ewm.event.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import ru.practicum.ewm.category.dto.CategoryDto;
import ru.practicum.ewm.user.dto.UserShortDto;

import java.time.LocalDateTime;

public record EventShortDto(
        Long id, String annotation, CategoryDto category, long confirmedRequests,
        @JsonFormat(pattern = "uuuu-MM-dd HH:mm:ss") LocalDateTime eventDate,
        UserShortDto initiator, boolean paid, String title, long views
) {

    public EventShortDto withViews(long newViews) {
        return new EventShortDto(id, annotation, category, confirmedRequests, eventDate, initiator, paid, title, newViews);
    }
}
