package com.logistics.planner.maps;

public interface TravelTimeProvider {

    /**
     * Returns travel duration in seconds from (fromLat,fromLng) to (toLat,toLng)
     * at given departure hour (0-23) for traffic estimation.
     */
    long getTravelTimeSeconds(
            double fromLat, double fromLng, double toLat, double toLng, int departureHour);
}
