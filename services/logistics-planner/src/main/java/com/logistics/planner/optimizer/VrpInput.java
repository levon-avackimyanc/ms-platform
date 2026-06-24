package com.logistics.planner.optimizer;

import java.util.List;

public record VrpInput(
    List<VrpLocation> locations,
    List<VrpVehicle> vehicles,
    long[][] timeMatrix,
    long timeLimitSeconds) {}
