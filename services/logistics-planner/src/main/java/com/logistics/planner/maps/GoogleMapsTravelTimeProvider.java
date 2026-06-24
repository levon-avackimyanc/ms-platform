package com.logistics.planner.maps;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@ConditionalOnProperty(name = "maps.provider", havingValue = "google")
public class GoogleMapsTravelTimeProvider implements TravelTimeProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleMapsTravelTimeProvider.class);
    private static final long MIN_TRAVEL_SECONDS = 60L;

    private final RestClient restClient;
    private final String apiKey;

    public GoogleMapsTravelTimeProvider(
            RestClient mapsRestClient, @Value("${maps.api-key}") String apiKey) {
        this.restClient = mapsRestClient;
        this.apiKey = apiKey;
    }

    @Override
    public long getTravelTimeSeconds(
            double fromLat, double fromLng, double toLat, double toLng, int departureHour) {
        try {
            long departureEpoch = toDepartureEpoch(departureHour);
            String origins = fromLat + "," + fromLng;
            String destinations = toLat + "," + toLng;

            DistanceMatrixResponse response =
                    restClient
                            .get()
                            .uri(
                                    "/maps/api/distancematrix/json"
                                            + "?origins={origins}"
                                            + "&destinations={destinations}"
                                            + "&departure_time={departureTime}"
                                            + "&key={key}",
                                    origins,
                                    destinations,
                                    departureEpoch,
                                    apiKey)
                            .retrieve()
                            .body(DistanceMatrixResponse.class);

            if (response != null
                    && response.rows() != null
                    && !response.rows().isEmpty()
                    && response.rows().get(0).elements() != null
                    && !response.rows().get(0).elements().isEmpty()) {

                DistanceMatrixResponse.Element element =
                        response.rows().get(0).elements().get(0);

                if ("OK".equals(element.status())) {
                    DistanceMatrixResponse.Duration traffic = element.durationInTraffic();
                    if (traffic != null) {
                        return Math.max(traffic.value(), MIN_TRAVEL_SECONDS);
                    }
                    DistanceMatrixResponse.Duration duration = element.duration();
                    if (duration != null) {
                        return Math.max(duration.value(), MIN_TRAVEL_SECONDS);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn(
                    "Google Distance Matrix API call failed, falling back to Haversine estimate: {}",
                    ex.getMessage());
        }

        return haversineFallback(fromLat, fromLng, toLat, toLng);
    }

    private long haversineFallback(
            double fromLat, double fromLng, double toLat, double toLng) {
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
        double distKm = R * c;
        long travelSeconds = (long) ((distKm * 1.4) / 40.0 * 3600);
        return Math.max(travelSeconds, MIN_TRAVEL_SECONDS);
    }

    private long toDepartureEpoch(int departureHour) {
        return LocalDateTime.of(LocalDate.now(), LocalTime.of(departureHour, 0))
                .toEpochSecond(ZoneOffset.UTC);
    }

    private record DistanceMatrixResponse(List<Row> rows) {
        record Row(List<Element> elements) {}

        record Element(
                String status,
                Duration duration,
                @JsonProperty("duration_in_traffic") Duration durationInTraffic) {}

        record Duration(int value) {}
    }
}
