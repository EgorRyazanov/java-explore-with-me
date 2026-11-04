package ru.practicum.ewm.request.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.event.model.Event;
import ru.practicum.ewm.event.model.EventState;
import ru.practicum.ewm.event.repository.EventRepository;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.request.dto.EventRequestStatusUpdateRequest;
import ru.practicum.ewm.request.dto.EventRequestStatusUpdateResult;
import ru.practicum.ewm.request.dto.ParticipationRequestDto;
import ru.practicum.ewm.request.dto.RequestDecision;
import ru.practicum.ewm.request.mapper.ParticipationRequestMapper;
import ru.practicum.ewm.request.model.ParticipationRequest;
import ru.practicum.ewm.request.model.RequestStatus;
import ru.practicum.ewm.request.repository.ParticipationRequestRepository;
import ru.practicum.ewm.user.repository.UserRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ParticipationRequestService {

    private final ParticipationRequestRepository requestRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final ParticipationRequestMapper requestMapper;
    private final Clock clock;

    public ParticipationRequestService(ParticipationRequestRepository requestRepository, EventRepository eventRepository,
                                       UserRepository userRepository, ParticipationRequestMapper requestMapper,
                                       Clock clock) {
        this.requestRepository = requestRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.requestMapper = requestMapper;
        this.clock = clock;
    }

    @Transactional
    public ParticipationRequestDto createRequest(long userId, long eventId) {
        requireUser(userId);
        Event event = lockEvent(eventId);
        if (event.getInitiator().getId() == userId) {
            throw new ConflictException("The initiator cannot participate in their own event");
        }
        if (event.getState() != EventState.PUBLISHED) {
            throw new ConflictException("Only published events accept participation requests");
        }
        if (requestRepository.existsByEventAndRequester(eventId, userId)) {
            throw new ConflictException("A participation request for this event already exists");
        }
        if (event.getParticipantLimit() > 0 && confirmedCount(eventId) >= event.getParticipantLimit()) {
            throw new ConflictException("The participant limit has been reached");
        }
        RequestStatus status = event.getParticipantLimit() == 0 || !event.isRequestModeration()
                ? RequestStatus.CONFIRMED : RequestStatus.PENDING;
        ParticipationRequest request = new ParticipationRequest(eventId, userId,
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS), status);
        return requestMapper.toDto(requestRepository.save(request));
    }

    public List<ParticipationRequestDto> getOwnRequests(long userId) {
        requireUser(userId);
        return requestRepository.findAllByRequesterOrderByIdAsc(userId);
    }

    @Transactional
    public ParticipationRequestDto cancelRequest(long userId, long requestId) {
        long eventId = requestRepository.findEventId(requestId, userId)
                .orElseThrow(() -> requestNotFound(requestId));
        lockEvent(eventId);
        ParticipationRequest request = requestRepository.findByIdAndRequester(requestId, userId)
                .orElseThrow(() -> requestNotFound(requestId));
        request.cancel();
        return requestMapper.toDto(request);
    }

    public List<ParticipationRequestDto> getEventRequests(long userId, long eventId) {
        eventRepository.findByIdAndInitiatorId(eventId, userId)
                .orElseThrow(() -> new NotFoundException("Event with id=" + eventId + " was not found"));
        return requestRepository.findAllByEventOrderByIdAsc(eventId);
    }

    @Transactional
    public EventRequestStatusUpdateResult updateStatuses(long userId, long eventId,
                                                         EventRequestStatusUpdateRequest update) {
        Event event = lockEvent(eventId);
        if (event.getInitiator().getId() != userId) {
            throw new NotFoundException("Event with id=" + eventId + " was not found");
        }
        List<Long> ids = update.requestIds().stream().distinct().sorted().toList();
        List<ParticipationRequestDto> selected = requestRepository.findAllByEventAndIdInOrderByIdAsc(eventId, ids);
        if (selected.size() != ids.size()) {
            throw new NotFoundException("Some participation requests were not found for this event");
        }
        if (update.status() == RequestDecision.CONFIRMED
                && (event.getParticipantLimit() == 0 || !event.isRequestModeration())) {
            return new EventRequestStatusUpdateResult(List.of(), List.of());
        }
        if (selected.stream().anyMatch(request -> request.status() != RequestStatus.PENDING)) {
            throw new ConflictException("Only pending requests can be changed");
        }
        if (update.status() == RequestDecision.REJECTED) {
            requestRepository.updateStatus(ids, RequestStatus.REJECTED);
            return new EventRequestStatusUpdateResult(List.of(), withStatus(selected, RequestStatus.REJECTED));
        }
        long confirmed = confirmedCount(eventId);
        long available = event.getParticipantLimit() - confirmed;
        if (available <= 0) {
            throw new ConflictException("The participant limit has been reached");
        }
        List<ParticipationRequestDto> accepted = selected.stream().limit(available).toList();
        requestRepository.updateStatus(accepted.stream().map(ParticipationRequestDto::id).toList(), RequestStatus.CONFIRMED);
        List<ParticipationRequestDto> rejected = List.of();
        if (confirmed + accepted.size() == event.getParticipantLimit()) {
            rejected = requestRepository.findAllByEventAndStatusOrderByIdAsc(eventId, RequestStatus.PENDING);
            requestRepository.updateEventStatus(eventId, RequestStatus.PENDING, RequestStatus.REJECTED);
        }
        return new EventRequestStatusUpdateResult(withStatus(accepted, RequestStatus.CONFIRMED),
                withStatus(rejected, RequestStatus.REJECTED));
    }

    private List<ParticipationRequestDto> withStatus(List<ParticipationRequestDto> requests, RequestStatus status) {
        return requests.stream().map(request -> new ParticipationRequestDto(request.created(), request.event(),
                request.id(), request.requester(), status)).toList();
    }

    private long confirmedCount(long eventId) {
        return requestRepository.countByEventAndStatus(eventId, RequestStatus.CONFIRMED);
    }

    private Event lockEvent(long eventId) {
        // Все изменения заявок одного события берут эту блокировку до проверки лимита и статуса.
        return eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new NotFoundException("Event with id=" + eventId + " was not found"));
    }

    private void requireUser(long userId) {
        if (!userRepository.existsById(userId)) {
            throw new NotFoundException("User with id=" + userId + " was not found");
        }
    }

    private NotFoundException requestNotFound(long requestId) {
        return new NotFoundException("Request with id=" + requestId + " was not found");
    }
}
