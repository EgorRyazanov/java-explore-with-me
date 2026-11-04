package ru.practicum.ewm.compilation.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "compilations")
public class Compilation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String title;

    @Column(nullable = false)
    private boolean pinned;

    protected Compilation() {
    }

    public Compilation(String title, boolean pinned) {
        this.title = title;
        this.pinned = pinned;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void rename(String title) {
        this.title = title;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }
}
