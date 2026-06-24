package com.logistics.planner.api.dto;

import java.util.List;
import java.util.UUID;

public record RouteResponse(UUID vehicleId, boolean frozen, List<StopResponse> stops) {}
