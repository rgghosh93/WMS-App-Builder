package com.wms.common.api;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * An error the API reports to the client as a problem response, optionally
 * with messages for individual request fields.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, String> fieldErrors;

    public ApiException(HttpStatus status, String message) {
        this(status, message, Map.of());
    }

    public ApiException(HttpStatus status, String message, Map<String, String> fieldErrors) {
        super(message);
        this.status = status;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message, Map<String, String> fieldErrors) {
        return new ApiException(HttpStatus.CONFLICT, message, fieldErrors);
    }

    public static ApiException invalid(String message, Map<String, String> fieldErrors) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, fieldErrors);
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
