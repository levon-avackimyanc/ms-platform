package com.logistics.planner.optimizer;

import com.logistics.planner.domain.enums.StopAction;
import java.util.UUID;

public record VrpLocation(
    int index,
    UUID locationId,
    StopAction action,
    UUID orderId,
    long timeWindowStartSec,
    long timeWindowEndSec,
    long serviceTimeSec,
    int pairedIndex) {}
