package ru.practicum.ewm.event.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import ru.practicum.ewm.event.model.Event;
import ru.practicum.ewm.event.model.EventState;
import ru.practicum.ewm.request.model.ParticipationRequest;
import ru.practicum.ewm.request.model.RequestStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class EventSearchRepositoryImpl implements EventSearchRepository {

    private final EntityManager entityManager;

    public EventSearchRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<Event> search(EventSearchFilter filter, int from, int size) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Event> query = builder.createQuery(Event.class);
        Root<Event> event = query.from(Event.class);
        event.fetch("category");
        event.fetch("initiator");
        List<Predicate> predicates = new ArrayList<>();
        if (filter.users() != null && !filter.users().isEmpty()) {
            predicates.add(event.get("initiator").get("id").in(filter.users()));
        }
        if (filter.states() != null && !filter.states().isEmpty()) {
            predicates.add(event.get("state").in(filter.states()));
        }
        if (filter.categories() != null && !filter.categories().isEmpty()) {
            predicates.add(event.get("category").get("id").in(filter.categories()));
        }
        if (filter.rangeStart() != null) {
            predicates.add(builder.greaterThanOrEqualTo(event.get("eventDate"), filter.rangeStart()));
        }
        if (filter.rangeEnd() != null) {
            predicates.add(builder.lessThanOrEqualTo(event.get("eventDate"), filter.rangeEnd()));
        }
        query.select(event).where(predicates.toArray(Predicate[]::new)).orderBy(builder.asc(event.get("id")));
        return entityManager.createQuery(query).setFirstResult(from).setMaxResults(size).getResultList();
    }

    @Override
    public List<Event> searchPublic(PublicEventSearchFilter filter, int from, int size) {
        return entityManager.createQuery(publicQuery(filter, null))
                .setFirstResult(from).setMaxResults(size).getResultList();
    }

    @Override
    public List<Event> searchPublicByIds(PublicEventSearchFilter filter, List<Long> ids) {
        return entityManager.createQuery(publicQuery(filter, ids)).getResultList();
    }

    private CriteriaQuery<Event> publicQuery(PublicEventSearchFilter filter, List<Long> ids) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Event> query = builder.createQuery(Event.class);
        Root<Event> event = query.from(Event.class);
        event.fetch("category");
        event.fetch("initiator");
        Predicate restrictions = builder.and(publicPredicates(filter, builder, query, event));
        if (ids != null) {
            restrictions = builder.and(restrictions, event.get("id").in(ids));
        }
        return query.select(event).where(restrictions)
                .orderBy(builder.asc(event.get("eventDate")), builder.asc(event.get("id")));
    }

    @Override
    public List<Long> searchPublicIds(PublicEventSearchFilter filter) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<Event> event = query.from(Event.class);
        query.select(event.get("id")).where(publicPredicates(filter, builder, query, event))
                .orderBy(builder.asc(event.get("id")));
        return entityManager.createQuery(query).getResultList();
    }

    private Predicate[] publicPredicates(PublicEventSearchFilter filter, CriteriaBuilder builder,
                                          CriteriaQuery<?> query, Root<Event> event) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.equal(event.get("state"), EventState.PUBLISHED));
        if (filter.text() != null && !filter.text().isBlank()) {
            String text = filter.text().toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                    .replace("%", "\\%").replace("_", "\\_");
            String pattern = "%" + text + "%";
            predicates.add(builder.or(builder.like(builder.lower(event.get("annotation")), pattern, '\\'),
                    builder.like(builder.lower(event.get("description")), pattern, '\\')));
        }
        if (filter.categories() != null && !filter.categories().isEmpty()) {
            predicates.add(event.get("category").get("id").in(filter.categories()));
        }
        if (filter.paid() != null) {
            predicates.add(builder.equal(event.get("paid"), filter.paid()));
        }
        if (filter.rangeStart() != null) {
            predicates.add(builder.greaterThanOrEqualTo(event.get("eventDate"), filter.rangeStart()));
        }
        if (filter.rangeEnd() != null) {
            predicates.add(builder.lessThanOrEqualTo(event.get("eventDate"), filter.rangeEnd()));
        }
        if (filter.futureAfter() != null) {
            predicates.add(builder.greaterThan(event.get("eventDate"), filter.futureAfter()));
        }
        if (filter.onlyAvailable()) {
            Subquery<Long> confirmed = query.subquery(Long.class);
            Root<ParticipationRequest> request = confirmed.from(ParticipationRequest.class);
            confirmed.select(builder.count(request)).where(builder.equal(request.get("event"), event.get("id")),
                    builder.equal(request.get("status"), RequestStatus.CONFIRMED));
            predicates.add(builder.or(builder.equal(event.get("participantLimit"), 0),
                    builder.gt(event.get("participantLimit"), confirmed)));
        }
        return predicates.toArray(Predicate[]::new);
    }
}
