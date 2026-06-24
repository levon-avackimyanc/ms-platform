package com.logistics.planner.api;

import com.logistics.planner.api.dto.LocationRequest;
import com.logistics.planner.api.dto.LocationResponse;
import com.logistics.planner.service.LocationService;
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
@RequestMapping("/api/v1/locations")
public class LocationController {

    private final LocationService locationService;

    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LocationResponse create(@Valid @RequestBody LocationRequest request) {
        return locationService.create(request);
    }

    @GetMapping("/{id}")
    public LocationResponse getById(@PathVariable UUID id) {
        return locationService.getById(id);
    }

    @GetMapping
    public List<LocationResponse> listActive() {
        return locationService.listActive();
    }

    @PutMapping("/{id}")
    public LocationResponse update(@PathVariable UUID id, @Valid @RequestBody LocationRequest request) {
        return locationService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void deactivate(@PathVariable UUID id) {
        locationService.deactivate(id);
    }
}
