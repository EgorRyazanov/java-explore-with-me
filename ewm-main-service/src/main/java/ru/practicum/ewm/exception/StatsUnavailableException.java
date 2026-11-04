package ru.practicum.ewm.exception;

public class StatsUnavailableException extends RuntimeException {

    public StatsUnavailableException(Throwable cause) {
        super("The statistics service is unavailable. Please retry later.", cause);
    }
}
