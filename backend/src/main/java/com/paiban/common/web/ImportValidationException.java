package com.paiban.common.web;

import java.util.List;

/**
 * Row-level validation failure raised by bulk imports. The global handler turns
 * this into HTTP 400 with an {@code errors} array so the UI can highlight cells.
 */
public class ImportValidationException extends RuntimeException {

    /**
     * One rejected row. {@code candidates} carries the system values the user may
     * pick instead when the failure was an unmatched name; it is empty otherwise.
     */
    public record RowError(int row, String field, String message, List<String> candidates) {

        public RowError(int row, String field, String message) {
            this(row, field, message, List.of());
        }
    }

    private final List<RowError> errors;

    public ImportValidationException(String message, List<RowError> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    public List<RowError> getErrors() {
        return errors;
    }
}
