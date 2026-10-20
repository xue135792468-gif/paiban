package com.paiban.common.web;

import java.util.List;

/**
 * Row-level validation failure raised by bulk imports. The global handler turns
 * this into HTTP 400 with an {@code errors} array so the UI can highlight cells.
 */
public class ImportValidationException extends RuntimeException {

    public record RowError(int row, String field, String message) {}

    private final List<RowError> errors;

    public ImportValidationException(String message, List<RowError> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    public List<RowError> getErrors() {
        return errors;
    }
}
