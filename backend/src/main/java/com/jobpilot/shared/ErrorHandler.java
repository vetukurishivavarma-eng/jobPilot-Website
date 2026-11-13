package com.jobpilot.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    public record ApiError(String code, String message, Map<String, String> fields) {}

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return ResponseEntity.status(e.status).body(new ApiError(e.code, e.getMessage(), null));
    }

    /** Upstream fetch failures: the user's own mistakes keep their status, anything else is a bad gateway. */
    @ExceptionHandler(com.jobpilot.job.SafeHttp.FetchException.class)
    ResponseEntity<ApiError> fetch(com.jobpilot.job.SafeHttp.FetchException e) {
        int s = e.status == 400 || e.status == 413 || e.status == 422 ? e.status : 502;
        return ResponseEntity.status(s).body(new ApiError("FETCH_FAILED", e.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        Map<String, String> fields = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(f -> f.getField(), f -> String.valueOf(f.getDefaultMessage()), (a, b) -> a));
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED", "Some fields are invalid", fields));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooBig(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413).body(new ApiError("FILE_TOO_LARGE", "Resume must be 5 MB or smaller", null));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(Exception e) {
        return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", "Request body is malformed", null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) throws Exception {
        if (e instanceof org.springframework.web.ErrorResponse) throw e; // let Spring map 404/405 etc.
        log.error("Unhandled error", e);
        return ResponseEntity.internalServerError().body(new ApiError("INTERNAL", "Something went wrong", null));
    }
}
