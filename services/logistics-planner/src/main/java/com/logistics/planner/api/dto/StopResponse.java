package com.logistics.planner.api.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record StopResponse(
        UUID locationId,
        UUID orderId,
        LocalDateTime arrivalTime,
        LocalDateTime departureTime,
        String action) {}
