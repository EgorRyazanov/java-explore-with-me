package ru.practicum.ewm.event.repository;

import java.time.LocalDateTime;
import java.util.List;

public record PublicEventSearchFilter(String text, List<Long> categories, Boolean paid,
                                      LocalDateTime rangeStart, LocalDateTime rangeEnd,
                                      boolean onlyAvailable, LocalDateTime futureAfter) {
}
