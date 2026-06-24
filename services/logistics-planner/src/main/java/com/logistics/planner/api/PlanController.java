package com.logistics.planner.api;

import com.logistics.planner.api.dto.MoveOrderRequest;
import com.logistics.planner.api.dto.PlanInitRequest;
import com.logistics.planner.api.dto.PlanResponse;
import com.logistics.planner.api.dto.RouteResponse;
import com.logistics.planner.domain.Plan;
import com.logistics.planner.domain.Route;
import com.logistics.planner.domain.enums.PlanStatus;
import com.logistics.planner.service.PlanningService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    private final PlanningService planningService;

    public PlanController(PlanningService planningService) {
        this.planningService = planningService;
    }

    /** POST /api/v1/plans → 202 + { "plan_id": "..." } */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, UUID> initiatePlan(@RequestBody @Valid PlanInitRequest request) {
        UUID planId = planningService.initiatePlanBuild(request.planDate());
        return Map.of("plan_id", planId);
    }

    /** GET /api/v1/plans/{id} → 202 (building) or 200 (draft/confirmed) or 404 */
    @GetMapping("/{id}")
    public ResponseEntity<PlanResponse> getPlan(@PathVariable UUID id) {
        Plan plan = planningService.getPlan(id);
        PlanResponse response = PlanMapper.toResponse(plan);
        HttpStatus status =
                plan.getStatus() == PlanStatus.BUILDING ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    /** POST /api/v1/plans/{id}/confirm → 200 */
    @PostMapping("/{id}/confirm")
    public PlanResponse confirmPlan(@PathVariable UUID id) {
        Plan plan = planningService.confirmPlan(id);
        return PlanMapper.toResponse(plan);
    }

    /** POST /api/v1/plans/{id}/rebuild → 202 + { "new_plan_id": "..." } */
    @PostMapping("/{id}/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, UUID> rebuildPlan(@PathVariable UUID id) {
        UUID newPlanId = planningService.rebuildPlan(id);
        return Map.of("new_plan_id", newPlanId);
    }

    /** POST /api/v1/plans/{id}/moves → 200 or 422 */
    @PostMapping("/{id}/moves")
    public PlanResponse moveOrder(
            @PathVariable UUID id, @RequestBody @Valid MoveOrderRequest request) {
        Plan plan =
                planningService.moveOrder(
                        id, request.orderId(), request.toVehicleId(), request.insertAtPosition());
        return PlanMapper.toResponse(plan);
    }

    /** PATCH /api/v1/plans/{planId}/routes/{vehicleId}/freeze → 200 */
    @PatchMapping("/{planId}/routes/{vehicleId}/freeze")
    public RouteResponse freezeRoute(
            @PathVariable UUID planId, @PathVariable UUID vehicleId) {
        Route route = planningService.freezeRoute(planId, vehicleId);
        return PlanMapper.toRouteResponse(route);
    }
}
