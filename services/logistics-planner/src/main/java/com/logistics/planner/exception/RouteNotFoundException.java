package com.logistics.planner.exception;

import java.util.UUID;

public class RouteNotFoundException extends RuntimeException {

    public RouteNotFoundException(UUID id) {
        super("Route not found: " + id);
    }
}
