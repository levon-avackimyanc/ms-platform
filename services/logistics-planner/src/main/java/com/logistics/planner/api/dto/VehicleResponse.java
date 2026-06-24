package com.logistics.planner.api.dto;

import java.time.LocalTime;
import java.util.UUID;

public record VehicleResponse(
        UUID id,
        Double maxWeightKg,
        Double maxVolumeM3,
        UUID depotLocationId,
        LocalTime shiftStart,
        LocalTime shiftEnd,
        boolean active) {}
