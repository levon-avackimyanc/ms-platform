package com.logistics.planner.optimizer;

import java.util.List;
import java.util.UUID;

public record VrpRoute(UUID vehicleId, List<Integer> locationIndices) {}
