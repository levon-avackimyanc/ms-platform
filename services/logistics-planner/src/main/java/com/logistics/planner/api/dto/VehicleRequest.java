package com.logistics.planner.api.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import java.util.UUID;

public record VehicleRequest(
        @NotNull Double maxWeightKg,
        @NotNull Double maxVolumeM3,
        @NotNull UUID depotLocationId,
        @NotNull LocalTime shiftStart,
        @NotNull LocalTime shiftEnd) {}
