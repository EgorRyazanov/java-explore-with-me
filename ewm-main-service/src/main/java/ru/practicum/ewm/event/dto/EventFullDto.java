package ru.practicum.ewm.event.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import ru.practicum.ewm.category.dto.CategoryDto;
import ru.practicum.ewm.event.model.EventState;
import ru.practicum.ewm.user.dto.UserShortDto;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventFullDto(
        Long id, String annotation, CategoryDto category, long confirmedRequests,
        @JsonFormat(pattern = "uuuu-MM-dd HH:mm:ss") LocalDateTime createdOn,
        String description,
        @JsonFormat(pattern = "uuuu-MM-dd HH:mm:ss") LocalDateTime eventDate,
        UserShortDto initiator, Location location, boolean paid, int participantLimit,
        @JsonFormat(pattern = "uuuu-MM-dd HH:mm:ss") LocalDateTime publishedOn,
        boolean requestModeration, EventState state, String title, long views
) {

    public EventFullDto withViews(long newViews) {
        return new EventFullDto(id, annotation, category, confirmedRequests, createdOn, description, eventDate,
                initiator, location, paid, participantLimit, publishedOn, requestModeration, state, title, newViews);
    }
}
