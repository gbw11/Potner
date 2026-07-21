package com.potner.common.error;

import org.springframework.http.ProblemDetail;

import java.time.Instant;

public final class ProblemDetails {

    private ProblemDetails() {
    }

    public static ProblemDetail from(ErrorCode errorCode) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(errorCode.status(), errorCode.detail());
        problemDetail.setTitle(errorCode.title());
        problemDetail.setProperty("code", errorCode.name());
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }
}
