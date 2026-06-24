package com.logistics.planner.api.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record OrderRequest(
        @NotNull UUID pickupLocationId,
        @NotNull UUID deliveryLocationId,
        @NotNull LocalDateTime deliveryWindowStart,
        @NotNull LocalDateTime deliveryWindowEnd,
        @NotNull Double weightKg,
        @NotNull Double volumeM3,
        Double delayBufferPercent,
        @NotNull LocalDate planDate) {}
