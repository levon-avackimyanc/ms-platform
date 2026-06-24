package com.logistics.planner.exception;

public class LocationCodeConflictException extends RuntimeException {

    public LocationCodeConflictException(String code) {
        super("Location code already exists: " + code);
    }
}
