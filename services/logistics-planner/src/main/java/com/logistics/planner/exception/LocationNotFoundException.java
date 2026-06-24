package com.logistics.planner.exception;

import java.util.UUID;

public class LocationNotFoundException extends RuntimeException {

    public LocationNotFoundException(UUID id) {
        super("Location not found: " + id);
    }
}
