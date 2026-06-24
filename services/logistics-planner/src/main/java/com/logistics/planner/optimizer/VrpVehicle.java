package com.logistics.planner.optimizer;

import java.util.UUID;

public record VrpVehicle(
    UUID vehicleId,
    int depotIndex,
    double maxWeightKg,
    double maxVolumeM3,
    long shiftStartSec,
    long shiftEndSec) {}
