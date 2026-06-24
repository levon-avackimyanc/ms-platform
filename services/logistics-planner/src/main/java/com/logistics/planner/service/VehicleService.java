package com.logistics.planner.service;

import com.logistics.planner.api.dto.VehicleRequest;
import com.logistics.planner.api.dto.VehicleResponse;
import com.logistics.planner.domain.Location;
import com.logistics.planner.domain.Vehicle;
import com.logistics.planner.exception.LocationNotFoundException;
import com.logistics.planner.exception.VehicleNotFoundException;
import com.logistics.planner.repository.LocationRepository;
import com.logistics.planner.repository.VehicleRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final LocationRepository locationRepository;

    public VehicleService(VehicleRepository vehicleRepository, LocationRepository locationRepository) {
        this.vehicleRepository = vehicleRepository;
        this.locationRepository = locationRepository;
    }

    public VehicleResponse create(VehicleRequest req) {
        Location depot =
                locationRepository
                        .findById(req.depotLocationId())
                        .orElseThrow(() -> new LocationNotFoundException(req.depotLocationId()));
        Vehicle vehicle = new Vehicle();
        vehicle.setMaxWeightKg(req.maxWeightKg());
        vehicle.setMaxVolumeM3(req.maxVolumeM3());
        vehicle.setDepot(depot);
        vehicle.setShiftStart(req.shiftStart());
        vehicle.setShiftEnd(req.shiftEnd());
        vehicle.setActive(true);
        return toResponse(vehicleRepository.save(vehicle));
    }

    @Transactional(readOnly = true)
    public VehicleResponse getById(UUID id) {
        return toResponse(
                vehicleRepository
                        .findById(id)
                        .orElseThrow(() -> new VehicleNotFoundException(id)));
    }

    public VehicleResponse update(UUID id, VehicleRequest req) {
        Vehicle vehicle =
                vehicleRepository
                        .findById(id)
                        .orElseThrow(() -> new VehicleNotFoundException(id));
        Location depot =
                locationRepository
                        .findById(req.depotLocationId())
                        .orElseThrow(() -> new LocationNotFoundException(req.depotLocationId()));
        vehicle.setMaxWeightKg(req.maxWeightKg());
        vehicle.setMaxVolumeM3(req.maxVolumeM3());
        vehicle.setDepot(depot);
        vehicle.setShiftStart(req.shiftStart());
        vehicle.setShiftEnd(req.shiftEnd());
        return toResponse(vehicleRepository.save(vehicle));
    }

    public void deactivate(UUID id) {
        Vehicle vehicle =
                vehicleRepository
                        .findById(id)
                        .orElseThrow(() -> new VehicleNotFoundException(id));
        vehicle.setActive(false);
        vehicleRepository.save(vehicle);
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> listActive() {
        return vehicleRepository.findAllByActiveTrue().stream()
                .map(this::toResponse)
                .toList();
    }

    private VehicleResponse toResponse(Vehicle v) {
        return new VehicleResponse(
                v.getId(),
                v.getMaxWeightKg(),
                v.getMaxVolumeM3(),
                v.getDepot() != null ? v.getDepot().getId() : null,
                v.getShiftStart(),
                v.getShiftEnd(),
                v.isActive());
    }
}
