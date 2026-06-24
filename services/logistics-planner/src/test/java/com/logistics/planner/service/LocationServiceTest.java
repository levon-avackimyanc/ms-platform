package com.logistics.planner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.logistics.planner.api.dto.LocationRequest;
import com.logistics.planner.api.dto.LocationResponse;
import com.logistics.planner.domain.Location;
import com.logistics.planner.exception.LocationCodeConflictException;
import com.logistics.planner.exception.LocationNotFoundException;
import com.logistics.planner.repository.LocationRepository;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LocationServiceTest {

    @Mock LocationRepository locationRepository;

    @InjectMocks LocationService locationService;

    private LocationRequest buildRequest(String code) {
        return new LocationRequest(
                code,
                "Test address",
                55.75,
                37.61,
                LocalTime.of(8, 0),
                LocalTime.of(20, 0),
                15);
    }

    private Location buildSavedLocation(UUID id, String code) {
        Location loc = new Location();
        loc.setId(id);
        loc.setCode(code);
        loc.setAddress("Test address");
        loc.setLatitude(55.75);
        loc.setLongitude(37.61);
        loc.setWorkingHoursStart(LocalTime.of(8, 0));
        loc.setWorkingHoursEnd(LocalTime.of(20, 0));
        loc.setServiceTimeMinutes(15);
        loc.setActive(true);
        return loc;
    }

    @Test
    void createLocation_validInput_returnsCreatedLocationDto() {
        LocationRequest req = buildRequest("LOC-001");
        UUID savedId = UUID.randomUUID();
        Location savedLoc = buildSavedLocation(savedId, "LOC-001");

        when(locationRepository.existsByCode("LOC-001")).thenReturn(false);
        when(locationRepository.save(any(Location.class))).thenReturn(savedLoc);

        LocationResponse result = locationService.create(req);

        assertThat(result).isNotNull();
        assertThat(result.code()).isEqualTo("LOC-001");
        assertThat(result.id()).isEqualTo(savedId);
        assertThat(result.active()).isTrue();
        assertThat(result.latitude()).isEqualTo(55.75);
        verify(locationRepository).save(any(Location.class));
    }

    @Test
    void createLocation_duplicateCode_throwsConflictException() {
        LocationRequest req = buildRequest("EXISTING-CODE");
        when(locationRepository.existsByCode("EXISTING-CODE")).thenReturn(true);

        assertThatThrownBy(() -> locationService.create(req))
                .isInstanceOf(LocationCodeConflictException.class)
                .hasMessageContaining("EXISTING-CODE");
    }

    @Test
    void getLocation_unknownId_throwsNotFoundException() {
        UUID unknownId = UUID.randomUUID();
        when(locationRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> locationService.getById(unknownId))
                .isInstanceOf(LocationNotFoundException.class)
                .hasMessageContaining(unknownId.toString());
    }

    @Test
    void updateLocation_validInput_updatesFields() {
        UUID id = UUID.randomUUID();
        Location existingLoc = buildSavedLocation(id, "OLD-CODE");
        LocationRequest req = buildRequest("NEW-CODE");
        Location updatedLoc = buildSavedLocation(id, "NEW-CODE");

        when(locationRepository.findById(id)).thenReturn(Optional.of(existingLoc));
        when(locationRepository.save(any(Location.class))).thenReturn(updatedLoc);

        LocationResponse result = locationService.update(id, req);

        assertThat(result.code()).isEqualTo("NEW-CODE");
        ArgumentCaptor<Location> captor = ArgumentCaptor.forClass(Location.class);
        verify(locationRepository).save(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo("NEW-CODE");
        assertThat(captor.getValue().getLatitude()).isEqualTo(55.75);
    }

    @Test
    void deactivateLocation_active_setsActiveFalse() {
        UUID id = UUID.randomUUID();
        Location activeLoc = buildSavedLocation(id, "LOC-ACTIVE");
        activeLoc.setActive(true);

        when(locationRepository.findById(id)).thenReturn(Optional.of(activeLoc));
        when(locationRepository.save(any(Location.class))).thenReturn(activeLoc);

        locationService.deactivate(id);

        ArgumentCaptor<Location> captor = ArgumentCaptor.forClass(Location.class);
        verify(locationRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
    }
}
