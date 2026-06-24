package com.logistics.planner.optimizer;

import java.util.List;
import java.util.UUID;

public record RouteAssignment(UUID vehicleId, List<StopAssignment> stops) {}
