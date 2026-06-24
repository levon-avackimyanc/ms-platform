package com.logistics.planner.optimizer;

import java.util.List;
import java.util.Set;

public record VrpOutput(List<VrpRoute> routes, Set<Integer> droppedLocationIndices) {}
