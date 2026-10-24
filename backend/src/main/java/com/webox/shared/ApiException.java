package com.webox.shared;

import java.util.List;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final List<FieldError> fieldErrors;

    public ApiException(int status, String code, String detail) {
        this(status, code, detail, List.of());
    }

    public ApiException(int status, String code, String detail, List<FieldError> fieldErrors) {
        super(detail);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public int status() { return status; }
    public String code() { return code; }
    public List<FieldError> fieldErrors() { return fieldErrors; }

    public record FieldError(String field, String message) { }
}
