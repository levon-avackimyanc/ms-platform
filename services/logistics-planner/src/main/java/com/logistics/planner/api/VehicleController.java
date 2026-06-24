package com.logistics.planner.api;

import com.logistics.planner.api.dto.VehicleRequest;
import com.logistics.planner.api.dto.VehicleResponse;
import com.logistics.planner.service.VehicleService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vehicles")
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleResponse create(@Valid @RequestBody VehicleRequest request) {
        return vehicleService.create(request);
    }

    @GetMapping("/{id}")
    public VehicleResponse getById(@PathVariable UUID id) {
        return vehicleService.getById(id);
    }

    @GetMapping
    public List<VehicleResponse> listActive() {
        return vehicleService.listActive();
    }

    @PutMapping("/{id}")
    public VehicleResponse update(@PathVariable UUID id, @Valid @RequestBody VehicleRequest request) {
        return vehicleService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void deactivate(@PathVariable UUID id) {
        vehicleService.deactivate(id);
    }
}
