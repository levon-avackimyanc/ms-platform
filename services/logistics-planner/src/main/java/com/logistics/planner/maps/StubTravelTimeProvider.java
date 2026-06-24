package com.logistics.planner.maps;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "maps.provider", havingValue = "stub", matchIfMissing = true)
public class StubTravelTimeProvider implements TravelTimeProvider {

    private static final long MIN_TRAVEL_SECONDS = 60L;

    @Override
    public long getTravelTimeSeconds(
            double fromLat, double fromLng, double toLat, double toLng, int departureHour) {
        double distanceKm = haversineKm(fromLat, fromLng, toLat, toLng);
        long travelSeconds = (long) ((distanceKm * 1.4) / 40.0 * 3600);
        return Math.max(travelSeconds, MIN_TRAVEL_SECONDS);
    }

    static double haversineKm(double fromLat, double fromLng, double toLat, double toLng) {
        double R = 6371;
        double dLat = Math.toRadians(toLat - fromLat);
        double dLng = Math.toRadians(toLng - fromLng);
        double a =
                Math.sin(dLat / 2) * Math.sin(dLat / 2)
                        + Math.cos(Math.toRadians(fromLat))
                                * Math.cos(Math.toRadians(toLat))
                                * Math.sin(dLng / 2)
                                * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
