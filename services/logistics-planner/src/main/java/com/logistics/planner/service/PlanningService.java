package com.logistics.planner.service;

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
import com.logistics.planner.exception.PlanNotInDraftException;
import com.logistics.planner.exception.RouteNotFoundException;
import com.logistics.planner.exception.ShiftViolationException;
import com.logistics.planner.exception.TimeWindowViolationException;
import com.logistics.planner.maps.TimeMatrixBuilder;
import com.logistics.planner.optimizer.RouteAssignment;
import com.logistics.planner.optimizer.StopAssignment;
import com.logistics.planner.optimizer.VrpInput;
import com.logistics.planner.optimizer.VrpInputBuilder;
import com.logistics.planner.optimizer.VrpOutput;
import com.logistics.planner.optimizer.VrpOutputMapper;
import com.logistics.planner.optimizer.VrpSolver;
import com.logistics.planner.repository.LocationRepository;
import com.logistics.planner.repository.OrderRepository;
import com.logistics.planner.repository.PlanRepository;
import com.logistics.planner.repository.RouteRepository;
import com.logistics.planner.repository.StopRepository;
import com.logistics.planner.repository.VehicleRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanningService {

    private static final Logger log = LoggerFactory.getLogger(PlanningService.class);
    private static final long VRP_TIME_LIMIT_SECONDS = 28L;

    private final PlanRepository planRepository;
    private final OrderRepository orderRepository;
    private final VehicleRepository vehicleRepository;
    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final LocationRepository locationRepository;
    private final VrpSolver vrpSolver;
    private final VrpInputBuilder vrpInputBuilder;
    private final VrpOutputMapper vrpOutputMapper;
    private final TimeMatrixBuilder timeMatrixBuilder;

    // @Lazy self-injection to enable @Async proxy on initiatePlanBuild → buildPlanAsync call
    private PlanningService self;

    public PlanningService(
            PlanRepository planRepository,
            OrderRepository orderRepository,
            VehicleRepository vehicleRepository,
            RouteRepository routeRepository,
            StopRepository stopRepository,
            LocationRepository locationRepository,
            VrpSolver vrpSolver,
            VrpInputBuilder vrpInputBuilder,
            VrpOutputMapper vrpOutputMapper,
            TimeMatrixBuilder timeMatrixBuilder) {
        this.planRepository = planRepository;
        this.orderRepository = orderRepository;
        this.vehicleRepository = vehicleRepository;
        this.routeRepository = routeRepository;
        this.stopRepository = stopRepository;
        this.locationRepository = locationRepository;
        this.vrpSolver = vrpSolver;
        this.vrpInputBuilder = vrpInputBuilder;
        this.vrpOutputMapper = vrpOutputMapper;
        this.timeMatrixBuilder = timeMatrixBuilder;
    }

    @Autowired
    public void setSelf(@Lazy PlanningService self) {
        this.self = self;
    }

    /**
     * Creates a new Plan(BUILDING) and kicks off async optimization. Returns the planId immediately
     * (202 pattern).
     */
    @Transactional
    public UUID initiatePlanBuild(LocalDate planDate) {
        Plan plan = new Plan();
        plan.setPlanDate(planDate);
        plan.setStatus(PlanStatus.BUILDING);
        Plan saved = planRepository.save(plan);
        self.buildPlanAsync(saved.getId(), planDate); // @Async via proxy
        return saved.getId();
    }

    @Async("planningExecutor")
    @Transactional
    public void buildPlanAsync(UUID planId, LocalDate planDate) {
        try {
            // Load orders and vehicles
            List<Order> orders =
                    orderRepository.findByPlanDateAndStatusIn(planDate, List.of(OrderStatus.PENDING));
            List<Vehicle> vehicles = vehicleRepository.findAllByActiveTrue();

            if (vehicles.isEmpty()) {
                markFailed(planId, "No active vehicles");
                return;
            }

            // Collect unique locations: depots + all pickup/delivery locations
            Set<UUID> locIds = new LinkedHashSet<>();
            vehicles.forEach(v -> locIds.add(v.getDepot().getId()));
            orders.forEach(
                    o -> {
                        locIds.add(o.getPickupLocation().getId());
                        locIds.add(o.getDeliveryLocation().getId());
                    });

            List<Location> locations =
                    locIds.stream()
                            .map(id -> locationRepository.findById(id).orElseThrow())
                            .collect(Collectors.toList());

            // Build time matrix
            long[][] timeMatrix = timeMatrixBuilder.buildMatrix(locations, 9);

            // Build VRP input and solve
            VrpInput vrpInput =
                    vrpInputBuilder.build(
                            orders, vehicles, locations, timeMatrix, planDate, VRP_TIME_LIMIT_SECONDS);
            VrpOutput vrpOutput = vrpSolver.solve(vrpInput);
            List<RouteAssignment> assignments = vrpOutputMapper.map(vrpOutput, vrpInput, planDate);

            // Persist routes and stops
            persistPlan(planId, planDate, assignments, vrpOutput, vrpInput, orders, vehicles, locations);

        } catch (Exception e) {
            log.error("Plan building failed for planId={}", planId, e);
            markFailed(planId, e.getMessage());
        }
    }

    @Transactional
    protected void persistPlan(
            UUID planId,
            LocalDate planDate,
            List<RouteAssignment> assignments,
            VrpOutput vrpOutput,
            VrpInput vrpInput,
            List<Order> orders,
            List<Vehicle> vehicles,
            List<Location> locations) {
        Plan plan =
                planRepository
                        .findById(planId)
                        .orElseThrow(() -> new PlanNotFoundException(planId));

        // Build maps for lookups
        Map<UUID, Vehicle> vehicleMap =
                vehicles.stream().collect(Collectors.toMap(Vehicle::getId, v -> v));
        Map<UUID, Location> locationMap =
                locations.stream().collect(Collectors.toMap(Location::getId, l -> l));
        Map<UUID, Order> orderMap =
                orders.stream().collect(Collectors.toMap(Order::getId, o -> o));

        // Build location index map and time matrix for travel time lookups
        Map<UUID, Integer> locIndexMap = new HashMap<>();
        for (int i = 0; i < locations.size(); i++) {
            locIndexMap.put(locations.get(i).getId(), i);
        }
        long[][] timeMatrix = timeMatrixBuilder.buildMatrix(locations, 9);

        int routePosition = 0;
        for (RouteAssignment assignment : assignments) {
            Route route = new Route();
            route.setPlan(plan);
            route.setVehicle(vehicleMap.get(assignment.vehicleId()));
            route.setFrozen(false);
            route.setPosition(routePosition++);
            Route savedRoute = routeRepository.save(route);

            List<StopAssignment> stops = assignment.stops();
            LocalDateTime cursor =
                    LocalDateTime.of(
                            planDate, vehicleMap.get(assignment.vehicleId()).getShiftStart());

            UUID prevLocId = vehicleMap.get(assignment.vehicleId()).getDepot().getId();

            for (int i = 0; i < stops.size(); i++) {
                StopAssignment sa = stops.get(i);
                Location loc = locationMap.get(sa.locationId());

                // Travel time from previous location
                int fromIdx = locIndexMap.getOrDefault(prevLocId, 0);
                int toIdx = locIndexMap.getOrDefault(sa.locationId(), 0);
                long travelSec = timeMatrix[fromIdx][toIdx];

                LocalDateTime arrival = cursor.plusSeconds(travelSec);
                int serviceMin =
                        loc.getServiceTimeMinutes() != null ? loc.getServiceTimeMinutes() : 0;
                double buffer =
                        sa.orderId() != null && orderMap.containsKey(sa.orderId())
                                ? orderMap.get(sa.orderId()).getDelayBufferPercent()
                                : 0.0;
                long serviceSec = (long) (serviceMin * 60 * (1 + buffer / 100.0));
                LocalDateTime departure = arrival.plusSeconds(serviceSec);

                Stop stop = new Stop();
                stop.setRoute(savedRoute);
                stop.setLocation(loc);
                stop.setOrder(sa.orderId() != null ? orderMap.get(sa.orderId()) : null);
                stop.setPosition(i);
                stop.setAction(sa.action());
                stop.setArrivalTime(arrival);
                stop.setDepartureTime(departure);
                stopRepository.save(stop);

                cursor = departure;
                prevLocId = sa.locationId();

                // Mark order as assigned
                if (sa.orderId() != null && orderMap.containsKey(sa.orderId())) {
                    Order o = orderMap.get(sa.orderId());
                    o.setStatus(OrderStatus.ASSIGNED);
                    orderRepository.save(o);
                }
            }
        }

        // Handle dropped/unassigned orders
        Set<UUID> assignedOrderIds =
                assignments.stream()
                        .flatMap(a -> a.stops().stream())
                        .map(StopAssignment::orderId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

        List<Order> unassigned =
                orders.stream()
                        .filter(o -> !assignedOrderIds.contains(o.getId()))
                        .collect(Collectors.toList());

        unassigned.forEach(
                o -> {
                    o.setStatus(OrderStatus.UNASSIGNED_NEXT_DAY);
                    orderRepository.save(o);
                });
        plan.setUnassignedOrders(unassigned);
        plan.setStatus(PlanStatus.DRAFT);
        plan.setGeneratedAt(LocalDateTime.now());
        planRepository.save(plan);
    }

    @Transactional(readOnly = true)
    public Plan getPlan(UUID planId) {
        Plan plan =
                planRepository
                        .findById(planId)
                        .orElseThrow(() -> new PlanNotFoundException(planId));
        // Force-load lazy associations
        plan.getRoutes().forEach(r -> r.getStops().size());
        plan.getUnassignedOrders().size();
        return plan;
    }

    @Transactional
    public Plan confirmPlan(UUID planId) {
        Plan plan =
                planRepository
                        .findById(planId)
                        .orElseThrow(() -> new PlanNotFoundException(planId));
        if (plan.getStatus() != PlanStatus.DRAFT) {
            throw new PlanNotInDraftException(planId);
        }
        plan.setStatus(PlanStatus.CONFIRMED);
        return planRepository.save(plan);
    }

    @Transactional
    public Route freezeRoute(UUID planId, UUID vehicleId) {
        Plan plan =
                planRepository
                        .findById(planId)
                        .orElseThrow(() -> new PlanNotFoundException(planId));
        Route route =
                plan.getRoutes().stream()
                        .filter(r -> r.getVehicle().getId().equals(vehicleId))
                        .findFirst()
                        .orElseThrow(() -> new RouteNotFoundException(vehicleId));
        route.setFrozen(true);
        return routeRepository.save(route);
    }

    @Transactional
    public Plan moveOrder(UUID planId, UUID orderId, UUID toVehicleId, int insertAtPosition) {
        Plan plan =
                planRepository
                        .findById(planId)
                        .orElseThrow(() -> new PlanNotFoundException(planId));
        if (plan.getStatus() != PlanStatus.DRAFT) {
            throw new PlanNotInDraftException(planId);
        }

        // Find all stops for this order in any route
        List<Stop> orderStops =
                plan.getRoutes().stream()
                        .flatMap(r -> r.getStops().stream())
                        .filter(
                                s ->
                                        orderId.equals(
                                                s.getOrder() != null ? s.getOrder().getId() : null))
                        .collect(Collectors.toList());

        if (orderStops.isEmpty()) {
            throw new RouteNotFoundException(orderId);
        }

        Route sourceRoute = orderStops.get(0).getRoute();
        sourceRoute.getStops().removeAll(orderStops);
        orderStops.forEach(stopRepository::delete);
        reindexStops(sourceRoute);

        // Find target route
        Route targetRoute =
                plan.getRoutes().stream()
                        .filter(r -> r.getVehicle().getId().equals(toVehicleId) && !r.isFrozen())
                        .findFirst()
                        .orElseThrow(() -> new RouteNotFoundException(toVehicleId));

        // Find the order
        Order order = orderRepository.findById(orderId).orElseThrow();

        // Validate capacity
        double totalWeight =
                targetRoute.getStops().stream()
                        .filter(s -> s.getOrder() != null && s.getAction() == StopAction.PICKUP)
                        .mapToDouble(s -> s.getOrder().getWeightKg())
                        .sum()
                        + order.getWeightKg();
        double totalVolume =
                targetRoute.getStops().stream()
                        .filter(s -> s.getOrder() != null && s.getAction() == StopAction.PICKUP)
                        .mapToDouble(s -> s.getOrder().getVolumeM3())
                        .sum()
                        + order.getVolumeM3();

        Vehicle targetVehicle = targetRoute.getVehicle();
        if (totalWeight > targetVehicle.getMaxWeightKg()) {
            throw new CapacityViolationException(
                    "Weight capacity exceeded for vehicle " + toVehicleId);
        }
        if (totalVolume > targetVehicle.getMaxVolumeM3()) {
            throw new CapacityViolationException(
                    "Volume capacity exceeded for vehicle " + toVehicleId);
        }

        // Insert pickup and delivery stops at insertAtPosition
        int pos = Math.min(insertAtPosition, targetRoute.getStops().size());

        Stop pickupStop = new Stop();
        pickupStop.setRoute(targetRoute);
        pickupStop.setLocation(order.getPickupLocation());
        pickupStop.setOrder(order);
        pickupStop.setAction(StopAction.PICKUP);
        pickupStop.setPosition(pos);

        Stop deliveryStop = new Stop();
        deliveryStop.setRoute(targetRoute);
        deliveryStop.setLocation(order.getDeliveryLocation());
        deliveryStop.setOrder(order);
        deliveryStop.setAction(StopAction.DELIVERY);
        deliveryStop.setPosition(pos + 1);

        // Shift existing stops down
        targetRoute.getStops().stream()
                .filter(s -> s.getPosition() >= pos)
                .forEach(s -> s.setPosition(s.getPosition() + 2));

        targetRoute.getStops().add(pickupStop);
        targetRoute.getStops().add(deliveryStop);
        stopRepository.save(pickupStop);
        stopRepository.save(deliveryStop);
        reindexStops(targetRoute);

        // Recompute times for both routes
        recomputeRouteTimes(sourceRoute, plan.getPlanDate());
        recomputeRouteTimes(targetRoute, plan.getPlanDate());

        // Validate time window for delivery in target route
        Stop recomputedDelivery =
                targetRoute.getStops().stream()
                        .filter(
                                s ->
                                        s.getOrder() != null
                                                && s.getOrder().getId().equals(orderId)
                                                && s.getAction() == StopAction.DELIVERY)
                        .findFirst()
                        .orElseThrow();

        if (recomputedDelivery.getArrivalTime() != null
                && recomputedDelivery.getArrivalTime().isAfter(order.getDeliveryWindowEnd())) {
            throw new TimeWindowViolationException(
                    "Delivery time window violated for order " + orderId);
        }

        // Validate shift end
        List<Stop> targetStops =
                targetRoute.getStops().stream()
                        .sorted(Comparator.comparingInt(Stop::getPosition))
                        .collect(Collectors.toList());
        if (!targetStops.isEmpty()) {
            Stop lastStop = targetStops.get(targetStops.size() - 1);
            LocalDateTime shiftEnd =
                    LocalDateTime.of(plan.getPlanDate(), targetVehicle.getShiftEnd());
            if (lastStop.getDepartureTime() != null
                    && lastStop.getDepartureTime().isAfter(shiftEnd)) {
                throw new ShiftViolationException(
                        "Shift end violated for vehicle " + toVehicleId);
            }
        }

        planRepository.save(plan);
        return plan;
    }

    @Transactional
    public UUID rebuildPlan(UUID planId) {
        Plan oldPlan =
                planRepository
                        .findById(planId)
                        .orElseThrow(() -> new PlanNotFoundException(planId));
        if (oldPlan.getStatus() != PlanStatus.CONFIRMED) {
            throw new PlanNotInDraftException(planId);
        }

        // Get frozen routes from old plan
        List<Route> frozenRoutes =
                oldPlan.getRoutes().stream().filter(Route::isFrozen).collect(Collectors.toList());

        oldPlan.setStatus(PlanStatus.FROZEN);
        planRepository.save(oldPlan);

        // Create new plan
        UUID newPlanId = initiatePlanBuild(oldPlan.getPlanDate());
        Plan newPlan =
                planRepository.findById(newPlanId).orElseThrow();

        // Copy frozen routes into new plan
        for (int i = 0; i < frozenRoutes.size(); i++) {
            Route orig = frozenRoutes.get(i);
            Route copy = new Route();
            copy.setPlan(newPlan);
            copy.setVehicle(orig.getVehicle());
            copy.setFrozen(true);
            copy.setPosition(i);
            Route savedCopy = routeRepository.save(copy);

            List<Stop> origStops = stopRepository.findByRouteIdOrderByPosition(orig.getId());
            for (Stop os : origStops) {
                Stop sc = new Stop();
                sc.setRoute(savedCopy);
                sc.setLocation(os.getLocation());
                sc.setOrder(os.getOrder());
                sc.setAction(os.getAction());
                sc.setPosition(os.getPosition());
                sc.setArrivalTime(os.getArrivalTime());
                sc.setDepartureTime(os.getDepartureTime());
                stopRepository.save(sc);
            }
        }

        return newPlanId;
    }

    private void reindexStops(Route route) {
        List<Stop> sorted =
                route.getStops().stream()
                        .sorted(Comparator.comparingInt(Stop::getPosition))
                        .collect(Collectors.toList());
        for (int i = 0; i < sorted.size(); i++) {
            sorted.get(i).setPosition(i);
            stopRepository.save(sorted.get(i));
        }
    }

    private void recomputeRouteTimes(Route route, LocalDate planDate) {
        List<Stop> stops =
                route.getStops().stream()
                        .sorted(Comparator.comparingInt(Stop::getPosition))
                        .collect(Collectors.toList());

        if (stops.isEmpty()) {
            return;
        }

        // Collect all unique locations needed for this route
        List<Location> routeLocations =
                stops.stream().map(Stop::getLocation).distinct().collect(Collectors.toList());

        // Add depot
        Location depot = route.getVehicle().getDepot();
        if (!routeLocations.contains(depot)) {
            routeLocations.add(0, depot);
        }

        long[][] matrix = timeMatrixBuilder.buildMatrix(routeLocations, 9);
        Map<UUID, Integer> idxMap = new HashMap<>();
        for (int i = 0; i < routeLocations.size(); i++) {
            idxMap.put(routeLocations.get(i).getId(), i);
        }

        LocalDateTime cursor = LocalDateTime.of(planDate, route.getVehicle().getShiftStart());
        UUID prevLocId = depot.getId();

        for (Stop stop : stops) {
            int fromIdx = idxMap.getOrDefault(prevLocId, 0);
            int toIdx = idxMap.getOrDefault(stop.getLocation().getId(), 0);
            long travelSec = matrix[fromIdx][toIdx];

            LocalDateTime arrival = cursor.plusSeconds(travelSec);
            int serviceMin =
                    stop.getLocation().getServiceTimeMinutes() != null
                            ? stop.getLocation().getServiceTimeMinutes()
                            : 0;
            double buffer =
                    stop.getOrder() != null && stop.getOrder().getDelayBufferPercent() != null
                            ? stop.getOrder().getDelayBufferPercent()
                            : 0.0;
            long serviceSec = (long) (serviceMin * 60 * (1 + buffer / 100.0));

            stop.setArrivalTime(arrival);
            stop.setDepartureTime(arrival.plusSeconds(serviceSec));
            stopRepository.save(stop);

            cursor = stop.getDepartureTime();
            prevLocId = stop.getLocation().getId();
        }
    }

    private void markFailed(UUID planId, String reason) {
        try {
            Plan plan = planRepository.findById(planId).orElse(null);
            if (plan != null) {
                plan.setStatus(PlanStatus.FAILED);
                planRepository.save(plan);
            }
        } catch (Exception e) {
            log.error("Could not mark plan {} as FAILED", planId, e);
        }
    }
}
