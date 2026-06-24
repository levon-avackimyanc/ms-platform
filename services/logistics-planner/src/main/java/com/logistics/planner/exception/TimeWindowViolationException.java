package com.logistics.planner.exception;

public class TimeWindowViolationException extends RuntimeException {

    public TimeWindowViolationException(String message) {
        super(message);
    }
}
