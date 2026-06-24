package com.logistics.planner.service;

import com.logistics.planner.api.dto.LocationRequest;
import com.logistics.planner.api.dto.LocationResponse;
import com.logistics.planner.domain.Location;
import com.logistics.planner.exception.LocationCodeConflictException;
import com.logistics.planner.exception.LocationNotFoundException;
import com.logistics.planner.repository.LocationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class LocationService {

    private final LocationRepository locationRepository;

    public LocationService(LocationRepository locationRepository) {
        this.locationRepository = locationRepository;
    }

    public LocationResponse create(LocationRequest req) {
        if (locationRepository.existsByCode(req.code())) {
            throw new LocationCodeConflictException(req.code());
        }
        Location loc = new Location();
        loc.setCode(req.code());
        loc.setAddress(req.address());
        loc.setLatitude(req.latitude());
        loc.setLongitude(req.longitude());
        loc.setWorkingHoursStart(req.workingHoursStart());
        loc.setWorkingHoursEnd(req.workingHoursEnd());
        loc.setServiceTimeMinutes(req.serviceTimeMinutes());
        loc.setActive(true);
        return toResponse(locationRepository.save(loc));
    }

    @Transactional(readOnly = true)
    public LocationResponse getById(UUID id) {
        return toResponse(
                locationRepository
                        .findById(id)
                        .orElseThrow(() -> new LocationNotFoundException(id)));
    }

    public LocationResponse update(UUID id, LocationRequest req) {
        Location loc =
                locationRepository
                        .findById(id)
                        .orElseThrow(() -> new LocationNotFoundException(id));
        loc.setCode(req.code());
        loc.setAddress(req.address());
        loc.setLatitude(req.latitude());
        loc.setLongitude(req.longitude());
        loc.setWorkingHoursStart(req.workingHoursStart());
        loc.setWorkingHoursEnd(req.workingHoursEnd());
        loc.setServiceTimeMinutes(req.serviceTimeMinutes());
        return toResponse(locationRepository.save(loc));
    }

    public void deactivate(UUID id) {
        Location loc =
                locationRepository
                        .findById(id)
                        .orElseThrow(() -> new LocationNotFoundException(id));
        loc.setActive(false);
        locationRepository.save(loc);
    }

    @Transactional(readOnly = true)
    public List<LocationResponse> listActive() {
        return locationRepository.findAll().stream()
                .filter(Location::isActive)
                .map(this::toResponse)
                .toList();
    }

    private LocationResponse toResponse(Location loc) {
        return new LocationResponse(
                loc.getId(),
                loc.getCode(),
                loc.getAddress(),
                loc.getLatitude(),
                loc.getLongitude(),
                loc.getWorkingHoursStart(),
                loc.getWorkingHoursEnd(),
                loc.getServiceTimeMinutes(),
                loc.isActive(),
                loc.getCreatedAt(),
                loc.getUpdatedAt());
    }
}
