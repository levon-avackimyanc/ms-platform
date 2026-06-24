package com.logistics.planner.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record MoveOrderRequest(
        @NotNull UUID orderId, @NotNull UUID toVehicleId, int insertAtPosition) {}
