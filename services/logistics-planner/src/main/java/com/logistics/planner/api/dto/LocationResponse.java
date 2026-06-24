package com.logistics.planner.api.dto;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

public record LocationResponse(
        UUID id,
        String code,
        String address,
        Double latitude,
        Double longitude,
        LocalTime workingHoursStart,
        LocalTime workingHoursEnd,
        Integer serviceTimeMinutes,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
