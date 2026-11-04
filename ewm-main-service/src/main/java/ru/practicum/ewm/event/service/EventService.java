package ru.practicum.ewm.event.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.category.model.Category;
import ru.practicum.ewm.category.repository.CategoryRepository;
import ru.practicum.ewm.event.dto.EventFullDto;
import ru.practicum.ewm.event.dto.EventShortDto;
import ru.practicum.ewm.event.dto.EventStateAction;
import ru.practicum.ewm.event.dto.NewEventDto;
import ru.practicum.ewm.event.dto.UpdateEventRequest;
import ru.practicum.ewm.event.mapper.EventMapper;
import ru.practicum.ewm.event.model.Event;
import ru.practicum.ewm.event.model.EventState;
import ru.practicum.ewm.event.repository.EventRepository;
import ru.practicum.ewm.event.repository.EventSearchFilter;
import ru.practicum.ewm.event.repository.PublicEventSearchFilter;
import ru.practicum.ewm.exception.BadRequestException;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.request.model.RequestStatus;
import ru.practicum.ewm.request.repository.ParticipationRequestRepository;
import ru.practicum.ewm.user.model.User;
import ru.practicum.ewm.user.repository.UserRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class EventService {

    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final EventMapper eventMapper;
    private final ParticipationRequestRepository requestRepository;
    private final EventStatisticsService eventStatistics;
    private final Clock clock;

    public EventService(EventRepository eventRepository, UserRepository userRepository,
                        CategoryRepository categoryRepository, EventMapper eventMapper,
                        ParticipationRequestRepository requestRepository, EventStatisticsService eventStatistics, Clock clock) {
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.eventMapper = eventMapper;
        this.requestRepository = requestRepository;
        this.eventStatistics = eventStatistics;
        this.clock = clock;
    }

    @Transactional
    public EventFullDto createEvent(long userId, NewEventDto request) {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        requireFutureDate(request.eventDate(), now);
        requireDate(request.eventDate(), now.plusHours(2));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User with id=" + userId + " was not found"));
        Event event = new Event(user, findCategory(request.category()), request.annotation(), request.description(),
                request.title(), request.eventDate(), now, request.location().lat(), request.location().lon(),
                request.paid(), request.participantLimit(), request.requestModeration());
        return eventMapper.toFullDto(eventRepository.save(event), 0, 0);
    }

    public List<EventShortDto> getOwnEvents(long userId, int from, int size) {
        if (!userRepository.existsById(userId)) {
            throw new NotFoundException("User with id=" + userId + " was not found");
        }
        List<Event> events = eventRepository.search(new EventSearchFilter(List.of(userId), null, null, null, null), from, size);
        return toShortDtosWithViews(events);
    }

    public List<EventShortDto> getShortEventsByIds(List<Long> ids) {
        return ids.isEmpty() ? List.of() : toShortDtosWithViews(eventRepository.findByIdInOrderByIdAsc(ids));
    }

    public List<EventShortDto> getPublishedShortEventsByIds(List<Long> ids) {
        return ids.isEmpty() ? List.of() : toShortDtosWithViews(
                eventRepository.findByIdInAndStateOrderByIdAsc(ids, EventState.PUBLISHED));
    }

    private List<EventShortDto> toShortDtosWithViews(List<Event> events) {
        Map<Long, Long> counts = confirmedCounts(events);
        Map<Long, Long> views = eventStatistics.getViews(events.stream().map(Event::getId).toList());
        return events.stream().map(event -> eventMapper.toShortDto(event, counts.getOrDefault(event.getId(), 0L),
                views.getOrDefault(event.getId(), 0L))).toList();
    }

    public EventFullDto getOwnEvent(long userId, long eventId) {
        return toFullDto(findOwnEvent(userId, eventId));
    }

    @Transactional
    public EventFullDto updateOwnEvent(long userId, long eventId, UpdateEventRequest request) {
        Event event = findOwnEvent(userId, eventId);
        if (event.getState() == EventState.PUBLISHED) {
            throw new ConflictException("Only pending or canceled events can be changed");
        }
        EventStateAction action = request.stateAction();
        if (action != null && action != EventStateAction.SEND_TO_REVIEW && action != EventStateAction.CANCEL_REVIEW) {
            throw new BadRequestException("The action is not available in the private API");
        }
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        requireFutureDate(request.eventDate(), now);
        requireDate(request.eventDate(), now.plusHours(2));
        applyChanges(event, request);
        if (action == EventStateAction.SEND_TO_REVIEW) {
            event.submitForReview();
        } else if (action == EventStateAction.CANCEL_REVIEW) {
            event.cancel();
        }
        return toFullDto(event);
    }

    public List<EventFullDto> getAdminEvents(EventSearchFilter filter, int from, int size) {
        if (filter.rangeStart() != null && filter.rangeEnd() != null && filter.rangeStart().isAfter(filter.rangeEnd())) {
            throw new BadRequestException("rangeStart must not be after rangeEnd");
        }
        List<Event> events = eventRepository.search(filter, from, size);
        Map<Long, Long> counts = confirmedCounts(events);
        Map<Long, Long> views = eventStatistics.getViews(events.stream().map(Event::getId).toList());
        return events.stream().map(event -> eventMapper.toFullDto(event, counts.getOrDefault(event.getId(), 0L),
                views.getOrDefault(event.getId(), 0L))).toList();
    }

    @Transactional
    public EventFullDto updateAdminEvent(long eventId, UpdateEventRequest request) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Event with id=" + eventId + " was not found"));
        EventStateAction action = request.stateAction();
        if (action != null && action != EventStateAction.PUBLISH_EVENT && action != EventStateAction.REJECT_EVENT) {
            throw new BadRequestException("The action is not available in the admin API");
        }
        if (action == EventStateAction.PUBLISH_EVENT && event.getState() != EventState.PENDING) {
            throw new ConflictException("Cannot publish the event because it is not pending: " + event.getState());
        }
        if (action == EventStateAction.REJECT_EVENT && event.getState() == EventState.PUBLISHED) {
            throw new ConflictException("Cannot reject a published event");
        }
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        requireFutureDate(request.eventDate(), now);
        requireDate(request.eventDate(), now.plusHours(1));
        if (action == EventStateAction.PUBLISH_EVENT) {
            requireDate(request.eventDate() == null ? event.getEventDate() : request.eventDate(), now.plusHours(1));
        }
        applyChanges(event, request);
        if (action == EventStateAction.PUBLISH_EVENT) {
            event.publish(now);
        } else if (action == EventStateAction.REJECT_EVENT) {
            event.cancel();
        }
        return toFullDto(event);
    }

    private EventFullDto toFullDto(Event event) {
        // Ошибка статистики до завершения транзакции откатывает изменение события.
        long views = eventStatistics.getViews(List.of(event.getId())).getOrDefault(event.getId(), 0L);
        return eventMapper.toFullDto(event,
                requestRepository.countByEventAndStatus(event.getId(), RequestStatus.CONFIRMED), views);
    }

    public EventFullDto getPublishedEvent(long id) {
        Event event = eventRepository.findById(id).filter(value -> value.getState() == EventState.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("Event with id=" + id + " was not found"));
        return eventMapper.toFullDto(event,
                requestRepository.countByEventAndStatus(id, RequestStatus.CONFIRMED), 0);
    }

    public List<EventShortDto> getPublicEvents(PublicEventSearchFilter filter, int from, int size) {
        return toPublicDtos(eventRepository.searchPublic(filter, from, size));
    }

    public List<Long> getPublicEventIds(PublicEventSearchFilter filter) {
        return eventRepository.searchPublicIds(filter);
    }

    public List<EventShortDto> getPublicEventsByIds(PublicEventSearchFilter filter, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Event> events = eventRepository.searchPublicByIds(filter, ids).stream()
                .collect(Collectors.toMap(Event::getId, event -> event));
        return toPublicDtos(ids.stream().map(events::get).filter(Objects::nonNull).toList());
    }

    private List<EventShortDto> toPublicDtos(List<Event> events) {
        Map<Long, Long> counts = confirmedCounts(events);
        return events.stream().map(event -> eventMapper.toShortDto(event, counts.getOrDefault(event.getId(), 0L), 0)).toList();
    }

    private Map<Long, Long> confirmedCounts(List<Event> events) {
        if (events.isEmpty()) {
            return Map.of();
        }
        return requestRepository.countByEvents(events.stream().map(Event::getId).toList(), RequestStatus.CONFIRMED)
                .stream().collect(Collectors.toMap(ParticipationRequestRepository.ConfirmedRequestCount::getEventId,
                        ParticipationRequestRepository.ConfirmedRequestCount::getTotal));
    }

    private void applyChanges(Event event, UpdateEventRequest request) {
        if (request.annotation() != null) {
            event.setAnnotation(request.annotation());
        }
        if (request.category() != null) {
            event.setCategory(findCategory(request.category()));
        }
        if (request.description() != null) {
            event.setDescription(request.description());
        }
        if (request.eventDate() != null) {
            event.setEventDate(request.eventDate());
        }
        if (request.location() != null) {
            event.relocate(request.location().lat(), request.location().lon());
        }
        if (request.paid() != null) {
            event.setPaid(request.paid());
        }
        if (request.participantLimit() != null) {
            event.setParticipantLimit(request.participantLimit());
        }
        if (request.requestModeration() != null) {
            event.setRequestModeration(request.requestModeration());
        }
        if (request.title() != null) {
            event.setTitle(request.title());
        }
    }

    private Event findOwnEvent(long userId, long eventId) {
        return eventRepository.findByIdAndInitiatorId(eventId, userId)
                .orElseThrow(() -> new NotFoundException("Event with id=" + eventId + " was not found"));
    }

    private Category findCategory(long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new NotFoundException("Category with id=" + categoryId + " was not found"));
    }

    private void requireFutureDate(LocalDateTime date, LocalDateTime now) {
        if (date != null && !date.isAfter(now)) {
            throw new BadRequestException("Event date must be in the future");
        }
    }

    private void requireDate(LocalDateTime date, LocalDateTime earliest) {
        if (date != null && date.isBefore(earliest)) {
            throw new ConflictException("Event date must not be before " + earliest);
        }
    }
}
