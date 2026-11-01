package ru.practicum.ewm.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.LocalDateTime;
import java.util.List;

@RestControllerAdvice
public class ErrorHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorHandler.class);

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
                                                              HttpHeaders headers, HttpStatusCode statusCode,
                                                              WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        List<String> errors = List.of();
        String message = exception.getMessage();

        if (exception instanceof MethodArgumentNotValidException validationException) {
            errors = validationException.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .toList();
            message = "Request validation failed.";
        } else if (exception instanceof HttpMessageNotReadableException) {
            message = "Malformed request body.";
        }

        if (status.is5xxServerError()) {
            LOG.error("Request processing failed", exception);
            message = "An unexpected error occurred.";
        }

        return super.handleExceptionInternal(exception, apiError(errors, message, status), headers, statusCode, request);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(apiError(List.of(), exception.getMessage(), HttpStatus.NOT_FOUND));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> handleBadRequest(BadRequestException exception) {
        return ResponseEntity.badRequest()
                .body(apiError(List.of(), exception.getMessage(), HttpStatus.BAD_REQUEST));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(List.of(), exception.getMessage(),
                "For the requested operation the conditions are not met.", HttpStatus.CONFLICT, LocalDateTime.now()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(apiError(List.of(), "The operation violates a data integrity constraint.", HttpStatus.CONFLICT));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleConcurrentUpdate() {
        return handleConflict(new ConflictException("The event was changed by another request. Please retry."));
    }

    @ExceptionHandler(StatsUnavailableException.class)
    public ResponseEntity<ApiError> handleStatsUnavailable(StatsUnavailableException exception) {
        LOG.warn("Statistics service request failed", exception.getCause());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(apiError(List.of(), exception.getMessage(), HttpStatus.SERVICE_UNAVAILABLE));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedException(Exception exception) {
        LOG.error("Request processing failed", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(apiError(List.of(), "An unexpected error occurred.", HttpStatus.INTERNAL_SERVER_ERROR));
    }

    private ApiError apiError(List<String> errors, String message, HttpStatus status) {
        String reason = switch (status) {
            case BAD_REQUEST -> "Incorrectly made request.";
            case NOT_FOUND -> "The required object was not found.";
            case CONFLICT -> "Integrity constraint has been violated.";
            default -> status.getReasonPhrase();
        };
        return new ApiError(errors, message, reason, status, LocalDateTime.now());
    }
}
