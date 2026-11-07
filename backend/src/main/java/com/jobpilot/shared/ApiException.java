package com.jobpilot.shared;

import org.springframework.http.HttpStatus;

/** Thrown anywhere; rendered as {"code","message"} with the given status by {@link ErrorHandler}. */
public class ApiException extends RuntimeException {
    public final HttpStatus status;
    public final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }
}
