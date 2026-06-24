package com.logistics.planner.exception;

public class CapacityViolationException extends RuntimeException {

    public CapacityViolationException(String message) {
        super(message);
    }
}
