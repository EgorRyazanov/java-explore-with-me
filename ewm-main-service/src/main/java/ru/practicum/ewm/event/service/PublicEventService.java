package ru.practicum.ewm.event.service;

import org.springframework.stereotype.Service;
import ru.practicum.ewm.event.dto.EventFullDto;
import ru.practicum.ewm.event.dto.EventShortDto;
import ru.practicum.ewm.event.dto.EventSort;
import ru.practicum.ewm.event.repository.PublicEventSearchFilter;
import ru.practicum.ewm.exception.BadRequestException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class PublicEventService {

    private final EventService eventService;
    private final EventStatisticsService eventStatistics;
    private final Clock clock;

    public PublicEventService(EventService eventService, EventStatisticsService eventStatistics, Clock clock) {
        this.eventService = eventService;
        this.eventStatistics = eventStatistics;
        this.clock = clock;
    }

    public List<EventShortDto> getEvents(PublicEventSearchFilter filter, EventSort sort, int from, int size, String ip) {
        if (filter.rangeStart() != null && filter.rangeEnd() != null && filter.rangeStart().isAfter(filter.rangeEnd())) {
            throw new BadRequestException("rangeStart must not be after rangeEnd");
        }
        LocalDateTime futureAfter = filter.rangeStart() == null && filter.rangeEnd() == null
                ? LocalDateTime.now(clock).withNano(0) : null;
        PublicEventSearchFilter effective = new PublicEventSearchFilter(filter.text(), filter.categories(), filter.paid(),
                filter.rangeStart(), filter.rangeEnd(), filter.onlyAvailable(), futureAfter);
        List<EventShortDto> events;
        Map<Long, Long> views;
        if (sort == EventSort.VIEWS) {
            List<Long> ids = eventService.getPublicEventIds(effective);
            views = eventStatistics.getViews(ids);
            List<Long> page = ids.stream().sorted(Comparator.<Long>comparingLong(id -> views.getOrDefault(id, 0L))
                            .reversed().thenComparingLong(Long::longValue))
                    .skip(from).limit(size).toList();
            events = eventService.getPublicEventsByIds(effective, page);
        } else {
            events = eventService.getPublicEvents(effective, from, size);
            views = eventStatistics.getViews(events.stream().map(EventShortDto::id).toList());
        }
        List<EventShortDto> result = events.stream()
                .map(event -> event.withViews(views.getOrDefault(event.id(), 0L))).toList();
        eventStatistics.hit("/events", ip);
        return result;
    }

    public EventFullDto getEvent(long id, String ip) {
        EventFullDto event = eventService.getPublishedEvent(id);
        eventStatistics.hit("/events/" + id, ip);
        long views = eventStatistics.getViews(List.of(id)).getOrDefault(id, 0L);
        return event.withViews(views);
    }
}
