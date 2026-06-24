package com.logistics.planner.optimizer;

import static org.assertj.core.api.Assertions.assertThat;

import com.logistics.planner.domain.enums.StopAction;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for VrpSolver using real OR-Tools native libraries.
 *
 * <p>NOTE: The VrpSolver's weight/volume callbacks always return 0 (see VrpSolver.java lines 83-90)
 * — capacity constraints in the solver are structural placeholders only. The real capacity
 * validation happens at the service layer (PlanningService.moveOrder). Consequently,
 * "overcapacity" tests here verify solver behaviour under tight time-window conditions —
 * the only active constraint that can force orders to be dropped.
 */
@ExtendWith(MockitoExtension.class)
class VrpSolverTest {

    // VrpSolver uses real OR-Tools (no mock needed)
    VrpSolver vrpSolver = new VrpSolver();

    // Typical shift window matching production: 08:00-20:00
    private static final long SHIFT_START = 8 * 3600L; // 28800
    private static final long SHIFT_END = 20 * 3600L; // 72000
    private static final long DAY_SEC = 24 * 3600L;

    /**
     * Builds a minimal 3-node VrpInput: depot(0), pickup(1), delivery(2) with one vehicle.
     * Uses realistic shift-aligned time windows for reliable OR-Tools behaviour.
     */
    private VrpInput buildInput(
            long timeLimitSec,
            long pickupTwStart,
            long pickupTwEnd,
            long deliveryTwStart,
            long deliveryTwEnd,
            long travelTime) {

        UUID depotLocId = UUID.randomUUID();
        UUID pickupLocId = UUID.randomUUID();
        UUID deliveryLocId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        // depot TW spans the whole shift
        VrpLocation depotLoc =
                new VrpLocation(0, depotLocId, null, null, SHIFT_START, SHIFT_END, 0, -1);
        VrpLocation pickupLoc =
                new VrpLocation(1, pickupLocId, StopAction.PICKUP, orderId, pickupTwStart, pickupTwEnd, 0, 2);
        VrpLocation deliveryLoc =
                new VrpLocation(
                        2, deliveryLocId, StopAction.DELIVERY, orderId, deliveryTwStart, deliveryTwEnd, 0, 1);

        long[][] matrix = {
            {0, travelTime, travelTime},
            {travelTime, 0, travelTime},
            {travelTime, travelTime, 0}
        };

        VrpVehicle vehicle = new VrpVehicle(vehicleId, 0, 1000.0, 1000.0, SHIFT_START, SHIFT_END);

        return new VrpInput(List.of(depotLoc, pickupLoc, deliveryLoc), List.of(vehicle), matrix, timeLimitSec);
    }

    @Test
    void solve_singleOrderSingleVehicle_assignsOrderToRoute() {
        // Wide time windows aligned to vehicle shift, small travel time (60s) → order assigned
        long tw1 = SHIFT_START;
        long tw2 = SHIFT_END;
        VrpInput input = buildInput(5L, tw1, tw2, tw1, tw2, 60L);

        VrpOutput output = vrpSolver.solve(input);

        assertThat(output).isNotNull();
        // At least one route returned, and both nodes 1 (pickup) and 2 (delivery) are in it
        boolean orderAssigned =
                output.routes().stream()
                        .anyMatch(
                                r -> r.locationIndices().contains(1) && r.locationIndices().contains(2));
        assertThat(orderAssigned)
                .as("Pickup (node 1) and delivery (node 2) should both be in a route")
                .isTrue();
        assertThat(output.droppedLocationIndices()).isEmpty();
    }

    @Test
    void solve_vehicleOvercapacity_orderDropped() {
        // NOTE: VrpSolver capacity callbacks always return 0 — capacity never constrains node visits.
        // We simulate infeasibility via an impossible time window: TW=[SHIFT_START, SHIFT_START+1].
        // Travel time (300s) is far larger than the 1-second window → order must be dropped.
        long tinyTwEnd = SHIFT_START + 1L;
        VrpInput input = buildInput(3L, SHIFT_START, tinyTwEnd, SHIFT_START, tinyTwEnd, 300L);

        VrpOutput output = vrpSolver.solve(input);

        assertThat(output).isNotNull();
        boolean orderNotServed =
                !output.droppedLocationIndices().isEmpty()
                        || output.routes().stream().allMatch(r -> r.locationIndices().isEmpty());
        assertThat(orderNotServed)
                .as("Order with impossible TW should be dropped or left in no route")
                .isTrue();
    }

    @Test
    void solve_timeWindowViolation_orderDropped() {
        // Delivery TW is impossibly tight (1 second) with large travel time → delivery dropped
        long tinyDeliveryEnd = SHIFT_START + 1L;
        VrpInput input = buildInput(3L, SHIFT_START, SHIFT_END, SHIFT_START, tinyDeliveryEnd, 300L);

        VrpOutput output = vrpSolver.solve(input);

        assertThat(output).isNotNull();
        // Delivery node (index 2) must not appear in any route, or appear in dropped set
        boolean deliveryNotServed =
                output.droppedLocationIndices().contains(2)
                        || output.routes().stream()
                                .allMatch(r -> !r.locationIndices().contains(2));
        assertThat(deliveryNotServed)
                .as("Delivery with violated time window should be dropped or unrouted")
                .isTrue();
    }

    @Test
    void solve_multipleOrders_respectsCapacityConstraints() {
        // 2 orders, wide time windows, small travel time — both should be assigned.
        // Capacity callbacks return 0, so both fit regardless of declared weight/volume.
        UUID depotLocId = UUID.randomUUID();
        UUID pickupLoc1Id = UUID.randomUUID();
        UUID deliveryLoc1Id = UUID.randomUUID();
        UUID pickupLoc2Id = UUID.randomUUID();
        UUID deliveryLoc2Id = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        UUID orderId1 = UUID.randomUUID();
        UUID orderId2 = UUID.randomUUID();

        long tw1 = SHIFT_START;
        long tw2 = SHIFT_END;

        // 5 nodes: depot=0, pickup1=1, delivery1=2, pickup2=3, delivery2=4
        VrpLocation depotLoc = new VrpLocation(0, depotLocId, null, null, tw1, tw2, 0, -1);
        VrpLocation pickup1 = new VrpLocation(1, pickupLoc1Id, StopAction.PICKUP, orderId1, tw1, tw2, 0, 2);
        VrpLocation delivery1 = new VrpLocation(2, deliveryLoc1Id, StopAction.DELIVERY, orderId1, tw1, tw2, 0, 1);
        VrpLocation pickup2 = new VrpLocation(3, pickupLoc2Id, StopAction.PICKUP, orderId2, tw1, tw2, 0, 4);
        VrpLocation delivery2 = new VrpLocation(4, deliveryLoc2Id, StopAction.DELIVERY, orderId2, tw1, tw2, 0, 3);

        long t = 60L;
        long[][] matrix = {
            {0, t, t, t, t},
            {t, 0, t, t, t},
            {t, t, 0, t, t},
            {t, t, t, 0, t},
            {t, t, t, t, 0}
        };

        VrpVehicle vehicle = new VrpVehicle(vehicleId, 0, 1000.0, 1000.0, tw1, tw2);

        VrpInput input =
                new VrpInput(
                        List.of(depotLoc, pickup1, delivery1, pickup2, delivery2),
                        List.of(vehicle),
                        matrix,
                        5L);

        VrpOutput output = vrpSolver.solve(input);

        assertThat(output).isNotNull();
        // Both orders should be assigned (solver capacity constraints don't block them)
        List<Integer> assigned =
                output.routes().stream().flatMap(r -> r.locationIndices().stream()).toList();
        assertThat(assigned)
                .as("All 4 order nodes (1,2,3,4) should appear in some route")
                .contains(1, 2, 3, 4);
        assertThat(output.droppedLocationIndices())
                .as("No orders should be dropped with wide windows and small travel times")
                .isEmpty();
    }
}
