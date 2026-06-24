package com.logistics.planner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.logistics.planner.domain.Location;
import com.logistics.planner.domain.Order;
import com.logistics.planner.domain.Plan;
import com.logistics.planner.domain.Route;
import com.logistics.planner.domain.Stop;
import com.logistics.planner.domain.Vehicle;
import com.logistics.planner.domain.enums.OrderStatus;
import com.logistics.planner.domain.enums.PlanStatus;
import com.logistics.planner.domain.enums.StopAction;
import com.logistics.planner.exception.CapacityViolationException;
import com.logistics.planner.exception.PlanNotFoundException;
import com.logistics.planner.exception.TimeWindowViolationException;
import com.logistics.planner.maps.TimeMatrixBuilder;
import com.logistics.planner.optimizer.RouteAssignment;
import com.logistics.planner.optimizer.StopAssignment;
import com.logistics.planner.optimizer.VrpInput;
import com.logistics.planner.optimizer.VrpInputBuilder;
import com.logistics.planner.optimizer.VrpLocation;
import com.logistics.planner.optimizer.VrpOutput;
import com.logistics.planner.optimizer.VrpOutputMapper;
import com.logistics.planner.optimizer.VrpRoute;
import com.logistics.planner.optimizer.VrpSolver;
import com.logistics.planner.optimizer.VrpVehicle;
import com.logistics.planner.repository.LocationRepository;
import com.logistics.planner.repository.OrderRepository;
import com.logistics.planner.repository.PlanRepository;
import com.logistics.planner.repository.RouteRepository;
import com.logistics.planner.repository.StopRepository;
import com.logistics.planner.repository.VehicleRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlanningServiceTest {

    @Mock PlanRepository planRepository;

    @Mock OrderRepository orderRepository;

    @Mock VehicleRepository vehicleRepository;

    @Mock RouteRepository routeRepository;

    @Mock StopRepository stopRepository;

    @Mock LocationRepository locationRepository;

    @Mock VrpSolver vrpSolver;

    @Mock VrpInputBuilder vrpInputBuilder;

    @Mock VrpOutputMapper vrpOutputMapper;

    @Mock TimeMatrixBuilder timeMatrixBuilder;

    @InjectMocks PlanningService planningService;

    @BeforeEach
    void setUp() {
        // @Lazy self-injection: Mockito won't call the setter, so we wire it manually
        planningService.setSelf(planningService);
    }

    // ---- helpers ----

    private Location buildLocation(UUID id, String code) {
        Location loc = new Location();
        loc.setId(id);
        loc.setCode(code);
        loc.setLatitude(55.75);
        loc.setLongitude(37.61);
        loc.setWorkingHoursStart(LocalTime.of(8, 0));
        loc.setWorkingHoursEnd(LocalTime.of(20, 0));
        loc.setServiceTimeMinutes(0);
        loc.setActive(true);
        return loc;
    }

    private Vehicle buildVehicle(UUID vehicleId, Location depot) {
        Vehicle v = new Vehicle();
        v.setId(vehicleId);
        v.setMaxWeightKg(1000.0);
        v.setMaxVolumeM3(10.0);
        v.setDepot(depot);
        v.setShiftStart(LocalTime.of(8, 0));
        v.setShiftEnd(LocalTime.of(20, 0));
        v.setActive(true);
        return v;
    }

    private Order buildOrder(UUID orderId, Location pickup, Location delivery) {
        Order order = new Order();
        order.setId(orderId);
        order.setPickupLocation(pickup);
        order.setDeliveryLocation(delivery);
        order.setWeightKg(100.0);
        order.setVolumeM3(1.0);
        order.setDelayBufferPercent(0.0);
        order.setPlanDate(LocalDate.of(2026, 6, 25));
        order.setDeliveryWindowStart(LocalDateTime.of(2026, 6, 25, 9, 0));
        order.setDeliveryWindowEnd(LocalDateTime.of(2026, 6, 25, 18, 0));
        order.setStatus(OrderStatus.PENDING);
        return order;
    }

    private Plan buildPlan(UUID planId, PlanStatus status) {
        Plan plan = new Plan();
        plan.setId(planId);
        plan.setStatus(status);
        plan.setPlanDate(LocalDate.of(2026, 6, 25));
        return plan;
    }

    // ---- tests ----

    @Test
    void buildPlan_withOrdersAndVehicles_returnsDraftPlanWithRoutes() {
        UUID planId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 6, 25);

        UUID depotId = UUID.randomUUID();
        UUID pickupLocId = UUID.randomUUID();
        UUID deliveryLocId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Location depot = buildLocation(depotId, "DEPOT");
        Location pickupLoc = buildLocation(pickupLocId, "PICKUP");
        Location deliveryLoc = buildLocation(deliveryLocId, "DELIVERY");
        Vehicle vehicle = buildVehicle(vehicleId, depot);
        Order order = buildOrder(orderId, pickupLoc, deliveryLoc);

        Plan plan = buildPlan(planId, PlanStatus.BUILDING);

        // Time matrix: 3 x 3 (depot, pickup, delivery)
        long[][] matrix = {{0, 100, 200}, {100, 0, 100}, {200, 100, 0}};

        // VrpInput stub
        VrpLocation depotVrpLoc = new VrpLocation(0, depotId, null, null, 0, 86400, 0, -1);
        VrpLocation pickupVrpLoc = new VrpLocation(1, pickupLocId, StopAction.PICKUP, orderId, 0, 86400, 0, 2);
        VrpLocation deliveryVrpLoc =
                new VrpLocation(2, deliveryLocId, StopAction.DELIVERY, orderId, 0, 86400, 0, 1);
        VrpVehicle vrpVehicle = new VrpVehicle(vehicleId, 0, 1000.0, 10.0, 28800, 72000);
        VrpInput vrpInput =
                new VrpInput(List.of(depotVrpLoc, pickupVrpLoc, deliveryVrpLoc), List.of(vrpVehicle), matrix, 5L);

        // VrpOutput stub
        VrpRoute vrpRoute = new VrpRoute(vehicleId, List.of(1, 2));
        VrpOutput vrpOutput = new VrpOutput(List.of(vrpRoute), Set.of());

        // RouteAssignment stub
        StopAssignment pickupStop =
                new StopAssignment(pickupLocId, orderId, StopAction.PICKUP, 28900, 28900);
        StopAssignment deliveryStop =
                new StopAssignment(deliveryLocId, orderId, StopAction.DELIVERY, 29000, 29000);
        RouteAssignment routeAssignment = new RouteAssignment(vehicleId, List.of(pickupStop, deliveryStop));

        // Saved route stub
        Route savedRoute = new Route();
        savedRoute.setId(UUID.randomUUID());
        savedRoute.setPlan(plan);
        savedRoute.setVehicle(vehicle);

        when(orderRepository.findByPlanDateAndStatusIn(eq(date), anyList())).thenReturn(List.of(order));
        when(vehicleRepository.findAllByActiveTrue()).thenReturn(List.of(vehicle));
        when(locationRepository.findById(depotId)).thenReturn(Optional.of(depot));
        when(locationRepository.findById(pickupLocId)).thenReturn(Optional.of(pickupLoc));
        when(locationRepository.findById(deliveryLocId)).thenReturn(Optional.of(deliveryLoc));
        when(timeMatrixBuilder.buildMatrix(anyList(), anyInt())).thenReturn(matrix);
        when(vrpInputBuilder.build(anyList(), anyList(), anyList(), any(long[][].class), eq(date), any(Long.class)))
                .thenReturn(vrpInput);
        when(vrpSolver.solve(vrpInput)).thenReturn(vrpOutput);
        when(vrpOutputMapper.map(eq(vrpOutput), eq(vrpInput), eq(date)))
                .thenReturn(List.of(routeAssignment));
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(routeRepository.save(any(Route.class))).thenReturn(savedRoute);
        when(stopRepository.save(any(Stop.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));

        // Call buildPlanAsync directly (bypasses @Async — no Spring context)
        planningService.buildPlanAsync(planId, date);

        // Verify plan is saved with DRAFT status at the end
        ArgumentCaptor<Plan> planCaptor = ArgumentCaptor.forClass(Plan.class);
        verify(planRepository, atLeastOnce()).save(planCaptor.capture());
        List<Plan> savedPlans = planCaptor.getAllValues();
        assertThat(savedPlans).anyMatch(p -> p.getStatus() == PlanStatus.DRAFT);
    }

    @Test
    void rebuildPlan_frozenRoutesAreCopiedToNewPlan() {
        UUID oldPlanId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        UUID depotId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID stopLocId = UUID.randomUUID();

        Location depot = buildLocation(depotId, "DEPOT");
        Vehicle vehicle = buildVehicle(vehicleId, depot);

        Location stopLoc = buildLocation(stopLocId, "STOP-LOC");

        Stop frozenStop = new Stop();
        frozenStop.setId(UUID.randomUUID());
        frozenStop.setLocation(stopLoc);
        frozenStop.setOrder(null);
        frozenStop.setAction(StopAction.DELIVERY);
        frozenStop.setPosition(0);
        frozenStop.setArrivalTime(LocalDateTime.of(2026, 6, 25, 9, 30));
        frozenStop.setDepartureTime(LocalDateTime.of(2026, 6, 25, 9, 45));

        Route frozenRoute = new Route();
        frozenRoute.setId(routeId);
        frozenRoute.setVehicle(vehicle);
        frozenRoute.setFrozen(true);
        frozenRoute.setPosition(0);
        frozenRoute.setStops(new ArrayList<>(List.of(frozenStop)));
        frozenStop.setRoute(frozenRoute);

        Plan oldPlan = buildPlan(oldPlanId, PlanStatus.CONFIRMED);
        oldPlan.setRoutes(new ArrayList<>(List.of(frozenRoute)));

        // New plan that initiatePlanBuild will create
        UUID newPlanId = UUID.randomUUID();
        Plan newPlan = buildPlan(newPlanId, PlanStatus.BUILDING);

        // Route copy
        Route copiedRoute = new Route();
        copiedRoute.setId(UUID.randomUUID());
        copiedRoute.setFrozen(true);
        copiedRoute.setVehicle(vehicle);

        when(planRepository.findById(oldPlanId)).thenReturn(Optional.of(oldPlan));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> {
            Plan p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(newPlanId);
            }
            return p;
        });
        when(planRepository.findById(newPlanId)).thenReturn(Optional.of(newPlan));
        when(stopRepository.findByRouteIdOrderByPosition(routeId)).thenReturn(List.of(frozenStop));
        when(routeRepository.save(any(Route.class))).thenReturn(copiedRoute);
        when(stopRepository.save(any(Stop.class))).thenAnswer(inv -> inv.getArgument(0));

        planningService.rebuildPlan(oldPlanId);

        // The frozen route copy must be saved with frozen=true
        ArgumentCaptor<Route> routeCaptor = ArgumentCaptor.forClass(Route.class);
        verify(routeRepository, atLeastOnce()).save(routeCaptor.capture());
        List<Route> savedRoutes = routeCaptor.getAllValues();
        assertThat(savedRoutes).anyMatch(Route::isFrozen);
    }

    @Test
    void movePlan_capacityExceeded_throwsCapacityViolationException() {
        UUID planId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID depotId = UUID.randomUUID();
        UUID pickupLocId = UUID.randomUUID();
        UUID deliveryLocId = UUID.randomUUID();

        Location depot = buildLocation(depotId, "DEPOT");
        Location pickupLoc = buildLocation(pickupLocId, "PICKUP");
        Location deliveryLoc = buildLocation(deliveryLocId, "DELIVERY");

        // Vehicle with very limited weight capacity
        Vehicle vehicle = buildVehicle(vehicleId, depot);
        vehicle.setMaxWeightKg(50.0);
        vehicle.setMaxVolumeM3(10.0);

        // The order to be moved is 100kg, which will exceed 50kg max
        Order movingOrder = buildOrder(orderId, pickupLoc, deliveryLoc);
        movingOrder.setWeightKg(100.0);
        movingOrder.setVolumeM3(1.0);

        // Existing stop in target route already has 10kg (total after move: 110 > 50)
        Order existingOrder = buildOrder(UUID.randomUUID(), pickupLoc, deliveryLoc);
        existingOrder.setWeightKg(10.0);
        existingOrder.setVolumeM3(0.5);

        Stop existingPickupStop = new Stop();
        existingPickupStop.setId(UUID.randomUUID());
        existingPickupStop.setOrder(existingOrder);
        existingPickupStop.setAction(StopAction.PICKUP);
        existingPickupStop.setLocation(pickupLoc);
        existingPickupStop.setPosition(0);

        Stop movingPickupStop = new Stop();
        movingPickupStop.setId(UUID.randomUUID());
        movingPickupStop.setOrder(movingOrder);
        movingPickupStop.setAction(StopAction.PICKUP);
        movingPickupStop.setLocation(pickupLoc);
        movingPickupStop.setPosition(1);

        Stop movingDeliveryStop = new Stop();
        movingDeliveryStop.setId(UUID.randomUUID());
        movingDeliveryStop.setOrder(movingOrder);
        movingDeliveryStop.setAction(StopAction.DELIVERY);
        movingDeliveryStop.setLocation(deliveryLoc);
        movingDeliveryStop.setPosition(2);

        // Source route contains the moving order stops
        Route sourceRoute = new Route();
        sourceRoute.setId(UUID.randomUUID());
        sourceRoute.setVehicle(vehicle);
        sourceRoute.setFrozen(false);
        sourceRoute.setPosition(0);
        sourceRoute.setStops(new ArrayList<>(List.of(movingPickupStop, movingDeliveryStop)));
        movingPickupStop.setRoute(sourceRoute);
        movingDeliveryStop.setRoute(sourceRoute);

        // Target route (same vehicle, different route object for the test)
        Route targetRoute = new Route();
        targetRoute.setId(UUID.randomUUID());
        targetRoute.setVehicle(vehicle);
        targetRoute.setFrozen(false);
        targetRoute.setPosition(1);
        targetRoute.setStops(new ArrayList<>(List.of(existingPickupStop)));
        existingPickupStop.setRoute(targetRoute);

        Plan plan = buildPlan(planId, PlanStatus.DRAFT);
        plan.setRoutes(new ArrayList<>(List.of(sourceRoute, targetRoute)));

        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(movingOrder));

        assertThatThrownBy(() -> planningService.moveOrder(planId, orderId, vehicleId, 0))
                .isInstanceOf(CapacityViolationException.class)
                .hasMessageContaining("Weight capacity exceeded");
    }

    @Test
    void movePlan_timeWindowViolated_throwsTimeWindowViolationException() {
        UUID planId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID depotId = UUID.randomUUID();
        UUID pickupLocId = UUID.randomUUID();
        UUID deliveryLocId = UUID.randomUUID();

        Location depot = buildLocation(depotId, "DEPOT");
        Location pickupLoc = buildLocation(pickupLocId, "PICKUP");
        Location deliveryLoc = buildLocation(deliveryLocId, "DELIVERY");

        // Very short delivery window — already in the past relative to shift start
        Order movingOrder = buildOrder(orderId, pickupLoc, deliveryLoc);
        movingOrder.setWeightKg(10.0);
        movingOrder.setVolumeM3(0.5);
        // Delivery window ends at 08:01 — but with travel time the arrival will be after that
        movingOrder.setDeliveryWindowStart(LocalDateTime.of(2026, 6, 25, 8, 0));
        movingOrder.setDeliveryWindowEnd(LocalDateTime.of(2026, 6, 25, 8, 1));

        Stop movingPickupStop = new Stop();
        movingPickupStop.setId(UUID.randomUUID());
        movingPickupStop.setOrder(movingOrder);
        movingPickupStop.setAction(StopAction.PICKUP);
        movingPickupStop.setLocation(pickupLoc);
        movingPickupStop.setPosition(0);

        Stop movingDeliveryStop = new Stop();
        movingDeliveryStop.setId(UUID.randomUUID());
        movingDeliveryStop.setOrder(movingOrder);
        movingDeliveryStop.setAction(StopAction.DELIVERY);
        movingDeliveryStop.setLocation(deliveryLoc);
        movingDeliveryStop.setPosition(1);

        Vehicle vehicle = buildVehicle(vehicleId, depot);
        vehicle.setMaxWeightKg(1000.0);
        vehicle.setMaxVolumeM3(100.0);

        // Source route holds the order (will be removed from here)
        Route sourceRoute = new Route();
        sourceRoute.setId(UUID.randomUUID());
        sourceRoute.setVehicle(vehicle);
        sourceRoute.setFrozen(false);
        sourceRoute.setPosition(0);
        sourceRoute.setStops(new ArrayList<>(List.of(movingPickupStop, movingDeliveryStop)));
        movingPickupStop.setRoute(sourceRoute);
        movingDeliveryStop.setRoute(sourceRoute);

        // Target route (empty, same vehicle)
        Route targetRoute = new Route();
        targetRoute.setId(UUID.randomUUID());
        targetRoute.setVehicle(vehicle);
        targetRoute.setFrozen(false);
        targetRoute.setPosition(1);
        targetRoute.setStops(new ArrayList<>());

        Plan plan = buildPlan(planId, PlanStatus.DRAFT);
        plan.setRoutes(new ArrayList<>(List.of(sourceRoute, targetRoute)));

        // Large travel time (3600s = 1 hour) so arrival >> delivery window end at 08:01
        long[][] bigMatrix = {{0, 3600, 3600}, {3600, 0, 3600}, {3600, 3600, 0}};

        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(movingOrder));
        when(stopRepository.save(any(Stop.class))).thenAnswer(inv -> inv.getArgument(0));
        when(timeMatrixBuilder.buildMatrix(anyList(), anyInt())).thenReturn(bigMatrix);

        assertThatThrownBy(() -> planningService.moveOrder(planId, orderId, vehicleId, 0))
                .isInstanceOf(TimeWindowViolationException.class)
                .hasMessageContaining("time window violated");
    }

    @Test
    void confirmPlan_draftPlan_changesStatusToConfirmed() {
        UUID planId = UUID.randomUUID();
        Plan plan = buildPlan(planId, PlanStatus.DRAFT);

        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));

        Plan result = planningService.confirmPlan(planId);

        assertThat(result.getStatus()).isEqualTo(PlanStatus.CONFIRMED);
        ArgumentCaptor<Plan> captor = ArgumentCaptor.forClass(Plan.class);
        verify(planRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PlanStatus.CONFIRMED);
    }

    @Test
    void getPlan_unknownId_throwsPlanNotFoundException() {
        UUID unknownId = UUID.randomUUID();
        when(planRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> planningService.getPlan(unknownId))
                .isInstanceOf(PlanNotFoundException.class)
                .hasMessageContaining(unknownId.toString());
    }
}
