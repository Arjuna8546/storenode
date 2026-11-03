package com.storenode.web;

import com.storenode.domain.exception.DomainException;
import com.storenode.domain.exception.OrderNotFoundException;
import com.storenode.domain.exception.OutOfStockException;
import com.storenode.domain.exception.ProductNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Turns exceptions into JSON error bodies (RFC 9457 "problem details") with a stable "code". */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final String DEADLOCK_DETECTED = "40P01";

    @ExceptionHandler(DomainException.class)
    ProblemDetail domainRuleBroken(DomainException e) {
        return problem(statusOf(e), e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidFields(MethodArgumentNotValidException e) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request has invalid fields");
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request body is missing or is not valid JSON");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException e) {
        return problem(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "The header '" + e.getHeaderName() + "' is required");
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail badValue(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    // The database was too busy for us right now (a lock held too long, a deadlock, no free connection).
    // Nothing was saved, so the terminal can safely send the same order again in a moment.
    @ExceptionHandler({PessimisticLockingFailureException.class, CannotCreateTransactionException.class,
            CannotGetJdbcConnectionException.class})
    ResponseEntity<ProblemDetail> tooBusy(Exception e) {
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "TRY_AGAIN",
                "The store node is busy right now. Please try again.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1").body(problem);
    }

    // Spring does not recognise Postgres's "lock timeout", so it arrives as a generic SQL error.
    // The SQL state tells us what it really was.
    @ExceptionHandler(UncategorizedSQLException.class)
    ResponseEntity<ProblemDetail> unclassifiedSqlError(UncategorizedSQLException e) {
        String state = e.getSQLException().getSQLState();
        if (LOCK_NOT_AVAILABLE.equals(state) || DEADLOCK_DETECTED.equals(state)) {
            return tooBusy(e);
        }
        log.error("Unexpected SQL error", e);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong");
        return ResponseEntity.internalServerError().body(problem);
    }

    private static HttpStatus statusOf(DomainException e) {
        return switch (e) {
            case ProductNotFoundException notFound -> HttpStatus.NOT_FOUND;
            case OrderNotFoundException notFound -> HttpStatus.NOT_FOUND;
            case OutOfStockException outOfStock -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return problem;
    }
}
