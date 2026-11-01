package ru.practicum.ewm.event.mapper;

import org.springframework.stereotype.Component;
import ru.practicum.ewm.category.mapper.CategoryMapper;
import ru.practicum.ewm.event.dto.EventFullDto;
import ru.practicum.ewm.event.dto.EventShortDto;
import ru.practicum.ewm.event.dto.Location;
import ru.practicum.ewm.event.model.Event;
import ru.practicum.ewm.user.dto.UserShortDto;

@Component
public class EventMapper {

    private final CategoryMapper categoryMapper;

    public EventMapper(CategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    public EventFullDto toFullDto(Event event, long confirmedRequests, long views) {
        return new EventFullDto(event.getId(), event.getAnnotation(), categoryMapper.toDto(event.getCategory()), confirmedRequests,
                event.getCreatedOn(), event.getDescription(), event.getEventDate(),
                new UserShortDto(event.getInitiator().getId(), event.getInitiator().getName()),
                new Location(event.getLat(), event.getLon()), event.isPaid(), event.getParticipantLimit(),
                event.getPublishedOn(), event.isRequestModeration(), event.getState(), event.getTitle(), views);
    }

    public EventShortDto toShortDto(Event event, long confirmedRequests, long views) {
        return new EventShortDto(event.getId(), event.getAnnotation(), categoryMapper.toDto(event.getCategory()), confirmedRequests,
                event.getEventDate(), new UserShortDto(event.getInitiator().getId(), event.getInitiator().getName()),
                event.isPaid(), event.getTitle(), views);
    }
}
