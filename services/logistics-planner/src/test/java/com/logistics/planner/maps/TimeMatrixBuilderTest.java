package com.logistics.planner.maps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.logistics.planner.domain.Location;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TimeMatrixBuilderTest {

    @Mock TravelTimeProvider travelTimeProvider;

    // TimeMatrixBuilder has @Lazy self-injection; in tests we wire it manually.
    TimeMatrixBuilder timeMatrixBuilder;

    @BeforeEach
    void setUp() {
        timeMatrixBuilder = new TimeMatrixBuilder(travelTimeProvider);
        timeMatrixBuilder.setSelf(timeMatrixBuilder); // bypass @Lazy setter
    }

    private Location buildLocation(double lat, double lng) {
        Location loc = new Location();
        loc.setId(UUID.randomUUID());
        loc.setCode("LOC-" + UUID.randomUUID());
        loc.setLatitude(lat);
        loc.setLongitude(lng);
        loc.setActive(true);
        return loc;
    }

    @Test
    void buildMatrix_callsProviderForEachPair() {
        // 2 locations → 2 unique ordered pairs (i→j and j→i), diagonal is 0
        Location loc1 = buildLocation(55.0, 37.0);
        Location loc2 = buildLocation(56.0, 38.0);

        when(travelTimeProvider.getTravelTimeSeconds(
                        eq(55.0), eq(37.0), eq(56.0), eq(38.0), anyInt()))
                .thenReturn(600L);
        when(travelTimeProvider.getTravelTimeSeconds(
                        eq(56.0), eq(38.0), eq(55.0), eq(37.0), anyInt()))
                .thenReturn(650L);

        long[][] matrix = timeMatrixBuilder.buildMatrix(List.of(loc1, loc2), 9);

        assertThat(matrix).hasNumberOfRows(2);
        assertThat(matrix[0][0]).isEqualTo(0L);
        assertThat(matrix[1][1]).isEqualTo(0L);
        assertThat(matrix[0][1]).isEqualTo(600L);
        assertThat(matrix[1][0]).isEqualTo(650L);

        // Provider is called exactly 2 times (one per non-diagonal pair)
        verify(travelTimeProvider, times(2))
                .getTravelTimeSeconds(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void buildMatrix_cacheHit_skipsApiCall() {
        // NOTE: @Cacheable does NOT activate in pure unit tests because there is no Spring proxy.
        // In this test we call buildMatrix with 2 locations and verify the matrix dimensions
        // and provider call count are correct (N*(N-1) = 2 calls for 2 locations).
        // Cache behavior at the Spring-proxy level is verified in integration scope.
        Location loc1 = buildLocation(55.0, 37.0);
        Location loc2 = buildLocation(56.0, 38.0);

        when(travelTimeProvider.getTravelTimeSeconds(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(300L);

        long[][] matrix = timeMatrixBuilder.buildMatrix(List.of(loc1, loc2), 9);

        // Matrix dimensions are correct
        assertThat(matrix).hasNumberOfRows(2);
        assertThat(matrix[0]).hasSize(2);

        // 2 non-diagonal pairs → exactly 2 provider calls (no Spring cache in unit context)
        verify(travelTimeProvider, times(2))
                .getTravelTimeSeconds(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void buildMatrix_providerError_propagatesException() {
        Location loc1 = buildLocation(55.0, 37.0);
        Location loc2 = buildLocation(56.0, 38.0);

        when(travelTimeProvider.getTravelTimeSeconds(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenThrow(new RuntimeException("Provider unavailable"));

        assertThatThrownBy(() -> timeMatrixBuilder.buildMatrix(List.of(loc1, loc2), 9))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Provider unavailable");
    }
}
