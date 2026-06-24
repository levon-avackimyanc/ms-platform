package com.logistics.planner.optimizer;

import com.logistics.planner.domain.enums.StopAction;
import java.util.UUID;

public record StopAssignment(
    UUID locationId,
    UUID orderId,
    StopAction action,
    long arrivalTimeSec,
    long departureTimeSec) {}
