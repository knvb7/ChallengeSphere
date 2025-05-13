package com.ironside.challengesphere.exception;

import lombok.Getter;

@Getter
public enum ErrorCodes {
    E1000(1000, "Api input validation failed."),
    E1001(1001, "Database Error."),
    E1002(1002, "Error during api call.");

    private final int code;
    private final String description;

    ErrorCodes(int code, String description) {
        this.code = code;
        this.description = description;
    }
}
