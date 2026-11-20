package com.wms.common.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns errors into RFC 9457 problem responses. Field-level messages go in an
 * {@code errors} property keyed by request field name, so forms can show them
 * next to the right input.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException e) {
        return problem(e.status(), e.getMessage(), e.fieldErrors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleInvalid(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return problem(HttpStatus.BAD_REQUEST, "Some fields need attention", errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "The request body is not valid JSON or has a value of the wrong type", Map.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid value for '" + e.getName() + "'", Map.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, "This record was changed by someone else. Reload it and try again.", Map.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleIntegrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation", e);
        return problem(HttpStatus.CONFLICT, "This change conflicts with existing data", Map.of());
    }

    private static ProblemDetail problem(HttpStatus status, String detail, Map<String, String> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }
}
