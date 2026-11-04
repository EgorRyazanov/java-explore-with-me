package ru.practicum.ewm.event.repository;

import ru.practicum.ewm.event.model.Event;

import java.util.List;

public interface EventSearchRepository {

    List<Event> search(EventSearchFilter filter, int from, int size);

    List<Event> searchPublic(PublicEventSearchFilter filter, int from, int size);

    List<Long> searchPublicIds(PublicEventSearchFilter filter);

    List<Event> searchPublicByIds(PublicEventSearchFilter filter, List<Long> ids);
}
