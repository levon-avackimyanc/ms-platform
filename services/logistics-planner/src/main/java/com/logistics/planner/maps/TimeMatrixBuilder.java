package com.logistics.planner.maps;

import com.logistics.planner.domain.Location;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class TimeMatrixBuilder {

    private final TravelTimeProvider provider;

    // Self-injection via @Lazy so that @Cacheable calls go through the Spring proxy
    // rather than bypassing it with a direct this.method() call.
    private TimeMatrixBuilder self;

    public TimeMatrixBuilder(TravelTimeProvider provider) {
        this.provider = provider;
    }

    @Autowired
    public void setSelf(@Lazy TimeMatrixBuilder self) {
        this.self = self;
    }

    public long[][] buildMatrix(List<Location> locations, int departureHour) {
        int n = locations.size();
        long[][] matrix = new long[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    matrix[i][j] = 0;
                    continue;
                }
                matrix[i][j] =
                        self.getTravelTime(locations.get(i), locations.get(j), departureHour);
            }
        }
        return matrix;
    }

    @Cacheable(value = "travelTimes", key = "#from.id + '_' + #to.id + '_' + #departureHour")
    public long getTravelTime(Location from, Location to, int departureHour) {
        return provider.getTravelTimeSeconds(
                from.getLatitude(),
                from.getLongitude(),
                to.getLatitude(),
                to.getLongitude(),
                departureHour);
    }
}
