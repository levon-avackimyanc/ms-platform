package com.logistics.planner.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

public record LocationRequest(
        @NotBlank String code,
        String address,
        @NotNull Double latitude,
        @NotNull Double longitude,
        LocalTime workingHoursStart,
        LocalTime workingHoursEnd,
        @NotNull Integer serviceTimeMinutes) {}
