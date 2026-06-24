package com.logistics.planner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.logistics.planner.api.dto.VehicleRequest;
import com.logistics.planner.api.dto.VehicleResponse;
import com.logistics.planner.domain.Location;
import com.logistics.planner.domain.Vehicle;
import com.logistics.planner.exception.LocationNotFoundException;
import com.logistics.planner.repository.LocationRepository;
import com.logistics.planner.repository.VehicleRepository;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VehicleServiceTest {

    @Mock VehicleRepository vehicleRepository;

    @Mock LocationRepository locationRepository;

    @InjectMocks VehicleService vehicleService;

    private Location buildDepot(UUID id) {
        Location depot = new Location();
        depot.setId(id);
        depot.setCode("DEPOT-1");
        depot.setLatitude(55.75);
        depot.setLongitude(37.61);
        depot.setActive(true);
        return depot;
    }

    private Vehicle buildSavedVehicle(UUID vehicleId, Location depot) {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(vehicleId);
        vehicle.setMaxWeightKg(1000.0);
        vehicle.setMaxVolumeM3(10.0);
        vehicle.setDepot(depot);
        vehicle.setShiftStart(LocalTime.of(8, 0));
        vehicle.setShiftEnd(LocalTime.of(20, 0));
        vehicle.setActive(true);
        return vehicle;
    }

    @Test
    void createVehicle_validData_returnsSavedVehicle() {
        UUID depotId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        Location depot = buildDepot(depotId);
        Vehicle savedVehicle = buildSavedVehicle(vehicleId, depot);

        VehicleRequest req =
                new VehicleRequest(1000.0, 10.0, depotId, LocalTime.of(8, 0), LocalTime.of(20, 0));

        when(locationRepository.findById(depotId)).thenReturn(Optional.of(depot));
        when(vehicleRepository.save(any(Vehicle.class))).thenReturn(savedVehicle);

        VehicleResponse result = vehicleService.create(req);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(vehicleId);
        assertThat(result.maxWeightKg()).isEqualTo(1000.0);
        assertThat(result.depotLocationId()).isEqualTo(depotId);
        assertThat(result.active()).isTrue();
        verify(vehicleRepository).save(any(Vehicle.class));
    }

    @Test
    void createVehicle_unknownDepot_throwsNotFoundException() {
        UUID unknownDepotId = UUID.randomUUID();
        VehicleRequest req =
                new VehicleRequest(
                        1000.0, 10.0, unknownDepotId, LocalTime.of(8, 0), LocalTime.of(20, 0));

        when(locationRepository.findById(unknownDepotId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vehicleService.create(req))
                .isInstanceOf(LocationNotFoundException.class)
                .hasMessageContaining(unknownDepotId.toString());
    }

    @Test
    void listActiveVehicles_returnsOnlyActive() {
        UUID depotId = UUID.randomUUID();
        Location depot = buildDepot(depotId);

        Vehicle activeVehicle1 = buildSavedVehicle(UUID.randomUUID(), depot);
        Vehicle activeVehicle2 = buildSavedVehicle(UUID.randomUUID(), depot);

        when(vehicleRepository.findAllByActiveTrue()).thenReturn(List.of(activeVehicle1, activeVehicle2));

        List<VehicleResponse> results = vehicleService.listActive();

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(VehicleResponse::active);
        verify(vehicleRepository).findAllByActiveTrue();
    }
}
