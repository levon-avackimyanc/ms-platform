package com.logistics.planner.api;

import com.logistics.planner.api.dto.PlanResponse;
import com.logistics.planner.api.dto.RouteResponse;
import com.logistics.planner.api.dto.StopResponse;
import com.logistics.planner.domain.Order;
import com.logistics.planner.domain.Plan;
import com.logistics.planner.domain.Route;
import com.logistics.planner.domain.Stop;
import com.logistics.planner.domain.enums.StopAction;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class PlanMapper {

    public static PlanResponse toResponse(Plan plan) {
        List<RouteResponse> routes =
                plan.getRoutes().stream()
                        .sorted(Comparator.comparingInt(Route::getPosition))
                        .map(PlanMapper::toRouteResponse)
                        .toList();

        List<UUID> unassigned =
                plan.getUnassignedOrders().stream().map(Order::getId).toList();

        String statusStr =
                switch (plan.getStatus()) {
                    case BUILDING -> "building";
                    case DRAFT -> "draft";
                    case CONFIRMED -> "confirmed";
                    case FROZEN -> "frozen";
                    case FAILED -> "failed";
                };

        return new PlanResponse(
                plan.getId(), statusStr, routes, unassigned, plan.getGeneratedAt());
    }

    public static RouteResponse toRouteResponse(Route route) {
        List<StopResponse> stops =
                route.getStops().stream()
                        .sorted(Comparator.comparingInt(Stop::getPosition))
                        .map(PlanMapper::toStopResponse)
                        .toList();
        return new RouteResponse(route.getVehicle().getId(), route.isFrozen(), stops);
    }

    public static StopResponse toStopResponse(Stop stop) {
        String actionStr = stop.getAction() == StopAction.PICKUP ? "pickup" : "delivery";
        UUID orderId = stop.getOrder() != null ? stop.getOrder().getId() : null;
        return new StopResponse(
                stop.getLocation().getId(),
                orderId,
                stop.getArrivalTime(),
                stop.getDepartureTime(),
                actionStr);
    }
}
