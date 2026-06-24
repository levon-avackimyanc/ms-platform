package com.logistics.planner.exception;

import java.util.UUID;

public class VehicleNotFoundException extends RuntimeException {

    public VehicleNotFoundException(UUID id) {
        super("Vehicle not found: " + id);
    }
}
