package com.logistics.planner.optimizer;

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.FirstSolutionStrategy;
import com.google.ortools.constraintsolver.LocalSearchMetaheuristic;
import com.google.ortools.constraintsolver.RoutingDimension;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.google.ortools.constraintsolver.RoutingSearchParameters;
import com.google.ortools.constraintsolver.main;
import com.google.protobuf.Duration;
import com.logistics.planner.domain.enums.StopAction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class VrpSolver {

    static {
        Loader.loadNativeLibraries();
    }

    public VrpOutput solve(VrpInput input) {
        List<VrpLocation> locs = input.locations();
        List<VrpVehicle> vehs = input.vehicles();
        int nNodes = locs.size();
        int nVehicles = vehs.size();

        int[] starts = new int[nVehicles];
        int[] ends = new int[nVehicles];
        for (int v = 0; v < nVehicles; v++) {
            starts[v] = vehs.get(v).depotIndex();
            ends[v] = vehs.get(v).depotIndex();
        }

        RoutingIndexManager manager = new RoutingIndexManager(nNodes, nVehicles, starts, ends);
        RoutingModel routing = new RoutingModel(manager);

        final long[][] timeMatrix = input.timeMatrix();
        final long[] serviceTimes =
                locs.stream().mapToLong(VrpLocation::serviceTimeSec).toArray();

        int transitCallbackIndex =
                routing.registerTransitCallback(
                        (fromIndex, toIndex) -> {
                            int from = manager.indexToNode(fromIndex);
                            int to = manager.indexToNode(toIndex);
                            return timeMatrix[from][to] + serviceTimes[from];
                        });
        routing.setArcCostEvaluatorOfAllVehicles(transitCallbackIndex);

        long maxShift = 24 * 3600L;
        routing.addDimension(transitCallbackIndex, maxShift, maxShift, false, "Time");
        RoutingDimension timeDimension = routing.getMutableDimension("Time");
        timeDimension.setGlobalSpanCostCoefficient(100);

        for (int i = 0; i < nNodes; i++) {
            long index = manager.nodeToIndex(i);
            timeDimension
                    .cumulVar(index)
                    .setRange(locs.get(i).timeWindowStartSec(), locs.get(i).timeWindowEndSec());
        }

        for (int v = 0; v < nVehicles; v++) {
            VrpVehicle veh = vehs.get(v);
            long startIndex = routing.start(v);
            long endIndex = routing.end(v);
            timeDimension.cumulVar(startIndex).setRange(veh.shiftStartSec(), veh.shiftEndSec());
            timeDimension.cumulVar(endIndex).setRange(veh.shiftStartSec(), veh.shiftEndSec());
        }

        long[] weightCapacities = new long[nVehicles];
        long[] volumeCapacities = new long[nVehicles];
        for (int v = 0; v < nVehicles; v++) {
            weightCapacities[v] = (long) (vehs.get(v).maxWeightKg() * 1000);
            volumeCapacities[v] = (long) (vehs.get(v).maxVolumeM3() * 1000);
        }

        int weightCallbackIdx =
                routing.registerUnaryTransitCallback(fromIndex -> 0L);
        routing.addDimensionWithVehicleCapacity(
                weightCallbackIdx, 0, weightCapacities, true, "Weight");

        int volumeCallbackIdx =
                routing.registerUnaryTransitCallback(fromIndex -> 0L);
        routing.addDimensionWithVehicleCapacity(
                volumeCallbackIdx, 0, volumeCapacities, true, "Volume");

        long penalty = 1_000_000L;
        for (VrpLocation loc : locs) {
            if (loc.action() == StopAction.PICKUP && loc.pairedIndex() >= 0) {
                long pickupIdx = manager.nodeToIndex(loc.index());
                long deliveryIdx = manager.nodeToIndex(loc.pairedIndex());
                routing.addPickupAndDelivery(pickupIdx, deliveryIdx);
                routing
                        .solver()
                        .addConstraint(
                                routing
                                        .solver()
                                        .makeEquality(
                                                routing.vehicleVar(pickupIdx),
                                                routing.vehicleVar(deliveryIdx)));
                routing
                        .solver()
                        .addConstraint(
                                routing
                                        .solver()
                                        .makeLessOrEqual(
                                                timeDimension.cumulVar(pickupIdx),
                                                timeDimension.cumulVar(deliveryIdx)));
                // maxCardinality=2: serve both or pay penalty per unserved node
                routing.addDisjunction(new long[] {pickupIdx, deliveryIdx}, penalty, 2);
            }
        }

        RoutingSearchParameters searchParams =
                main.defaultRoutingSearchParameters()
                        .toBuilder()
                        .setFirstSolutionStrategy(
                                FirstSolutionStrategy.Value.PARALLEL_CHEAPEST_INSERTION)
                        .setLocalSearchMetaheuristic(
                                LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
                        .setTimeLimit(
                                Duration.newBuilder()
                                        .setSeconds(input.timeLimitSeconds())
                                        .build())
                        .build();

        Assignment solution = routing.solveWithParameters(searchParams);

        if (solution == null) {
            throw new VrpSolverException("OR-Tools returned no solution");
        }

        List<VrpRoute> routes = new ArrayList<>();
        Set<Integer> visitedNodes = new HashSet<>();

        for (int v = 0; v < nVehicles; v++) {
            List<Integer> indices = new ArrayList<>();
            long index = routing.start(v);
            while (!routing.isEnd(index)) {
                int node = manager.indexToNode(index);
                if (node != starts[v]) {
                    indices.add(node);
                    visitedNodes.add(node);
                }
                index = solution.value(routing.nextVar(index));
            }
            if (!indices.isEmpty()) {
                routes.add(new VrpRoute(vehs.get(v).vehicleId(), indices));
            }
        }

        Set<Integer> dropped = new HashSet<>();
        for (int node = 0; node < routing.size(); node++) {
            if (routing.isStart(node) || routing.isEnd(node)) {
                continue;
            }
            if (solution.value(routing.nextVar(node)) == node) {
                int locationNode = manager.indexToNode(node);
                VrpLocation loc = locs.get(locationNode);
                if (loc.action() != null) {
                    dropped.add(locationNode);
                }
            }
        }

        return new VrpOutput(routes, dropped);
    }
}
