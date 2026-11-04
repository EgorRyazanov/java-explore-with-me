package ru.practicum.ewm.event.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import ru.practicum.ewm.category.model.Category;
import ru.practicum.ewm.user.model.User;

import java.time.LocalDateTime;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiator_id", nullable = false)
    private User initiator;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false, length = 2000)
    private String annotation;

    @Column(nullable = false, length = 7000)
    private String description;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(name = "event_date", nullable = false)
    private LocalDateTime eventDate;

    @Column(name = "created_on", nullable = false, updatable = false)
    private LocalDateTime createdOn;

    @Column(name = "published_on")
    private LocalDateTime publishedOn;

    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lon;

    @Column(nullable = false)
    private boolean paid;

    @Column(name = "participant_limit", nullable = false)
    private int participantLimit;

    @Column(name = "request_moderation", nullable = false)
    private boolean requestModeration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventState state;

    protected Event() {
    }

    public Event(User initiator, Category category, String annotation, String description, String title,
                 LocalDateTime eventDate, LocalDateTime createdOn, Double lat, Double lon,
                 Boolean paid, Integer participantLimit, Boolean requestModeration) {
        this.initiator = initiator;
        this.category = category;
        this.annotation = annotation;
        this.description = description;
        this.title = title;
        this.eventDate = eventDate;
        this.createdOn = createdOn;
        this.lat = lat;
        this.lon = lon;
        this.paid = Boolean.TRUE.equals(paid);
        this.participantLimit = participantLimit == null ? 0 : participantLimit;
        this.requestModeration = requestModeration == null || requestModeration;
        this.state = EventState.PENDING;
    }

    public Long getId() {
        return id;
    }

    public User getInitiator() {
        return initiator;
    }

    public Category getCategory() {
        return category;
    }

    public String getAnnotation() {
        return annotation;
    }

    public String getDescription() {
        return description;
    }

    public String getTitle() {
        return title;
    }

    public LocalDateTime getEventDate() {
        return eventDate;
    }

    public LocalDateTime getCreatedOn() {
        return createdOn;
    }

    public LocalDateTime getPublishedOn() {
        return publishedOn;
    }

    public Double getLat() {
        return lat;
    }

    public Double getLon() {
        return lon;
    }

    public boolean isPaid() {
        return paid;
    }

    public int getParticipantLimit() {
        return participantLimit;
    }

    public boolean isRequestModeration() {
        return requestModeration;
    }

    public EventState getState() {
        return state;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public void setAnnotation(String annotation) {
        this.annotation = annotation;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setEventDate(LocalDateTime eventDate) {
        this.eventDate = eventDate;
    }

    public void setPaid(boolean paid) {
        this.paid = paid;
    }

    public void setParticipantLimit(int participantLimit) {
        this.participantLimit = participantLimit;
    }

    public void setRequestModeration(boolean requestModeration) {
        this.requestModeration = requestModeration;
    }

    public void relocate(Double lat, Double lon) {
        this.lat = lat;
        this.lon = lon;
    }

    public void submitForReview() {
        state = EventState.PENDING;
    }

    public void cancel() {
        state = EventState.CANCELED;
    }

    public void publish(LocalDateTime now) {
        state = EventState.PUBLISHED;
        publishedOn = now;
    }
}
