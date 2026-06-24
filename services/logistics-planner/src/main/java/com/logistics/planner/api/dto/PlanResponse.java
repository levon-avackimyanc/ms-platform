package com.logistics.planner.api.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PlanResponse(
        UUID planId,
        String status,
        List<RouteResponse> routes,
        List<UUID> unassignedOrders,
        LocalDateTime generatedAt) {}
