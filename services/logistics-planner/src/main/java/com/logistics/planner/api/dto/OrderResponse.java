package com.logistics.planner.api.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        UUID pickupLocationId,
        UUID deliveryLocationId,
        LocalDateTime deliveryWindowStart,
        LocalDateTime deliveryWindowEnd,
        Double weightKg,
        Double volumeM3,
        Double delayBufferPercent,
        LocalDate planDate,
        String status,
        LocalDateTime createdAt) {}
