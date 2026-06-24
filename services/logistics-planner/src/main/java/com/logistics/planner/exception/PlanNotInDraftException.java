package com.logistics.planner.exception;

import java.util.UUID;

public class PlanNotInDraftException extends RuntimeException {

    public PlanNotInDraftException(UUID planId) {
        super("Plan is not in DRAFT status: " + planId);
    }
}
