package ru.practicum.ewm.event.repository;

import ru.practicum.ewm.event.model.EventState;

import java.time.LocalDateTime;
import java.util.List;

public record EventSearchFilter(List<Long> users, List<EventState> states, List<Long> categories,
                                LocalDateTime rangeStart, LocalDateTime rangeEnd) {
}
